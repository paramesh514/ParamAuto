package com.parampilot.auto

import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing with Comma's open claim window: this phone sends an ephemeral P-256 public key
 * and gets its own per-phone 32-byte AES key back, sealed under an ECDH-derived key.
 *
 * Wire protocol:
 *   Request:  "GCL1" | 65-byte uncompressed P-256 public key | UTF-8 phone name
 *   Reply:    "GCR1" | 65-byte uncompressed P-256 comma public key | 12-byte nonce | AES-GCM ciphertext + tag
 *   ECDH on P-256 then HKDF-SHA256 (RFC 5869) with the session challenge as salt.
 *   AAD: "galaxy-claim-v1/reply".
 */
object ClaimPairing {
    data class Claim(
        val key: ByteArray,
        val id: String,
        val adapterAddress: String,
        val adapterName: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Claim
            return key.contentEquals(other.key) && id == other.id &&
                adapterAddress == other.adapterAddress && adapterName == other.adapterName
        }

        override fun hashCode(): Int {
            var result = key.contentHashCode()
            result = 31 * result + id.hashCode()
            result = 31 * result + adapterAddress.hashCode()
            result = 31 * result + adapterName.hashCode()
            return result
        }
    }

    private val REQUEST_MAGIC = "GCL1".toByteArray(Charsets.US_ASCII)
    private val REPLY_MAGIC = "GCR1".toByteArray(Charsets.US_ASCII)
    private val INFO = "galaxy-claim-v1".toByteArray(Charsets.US_ASCII)
    private val AAD = "galaxy-claim-v1/reply".toByteArray(Charsets.US_ASCII)
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val PHONE_ID = Regex("^[0-9a-fA-F]{8}$")
    private val ADDRESS = Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
    private const val POINT = 65

    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
    }

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    /** The 65-byte uncompressed point: 0x04 | X (32 bytes) | Y (32 bytes). */
    fun point(key: ECPublicKey): ByteArray {
        fun fixed(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size == 33 && bytes[0].toInt() == 0 -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> throw IllegalArgumentException("Invalid coordinate size: ${bytes.size}")
            }
        }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    fun request(publicPoint: ByteArray, name: String): ByteArray {
        require(publicPoint.size == POINT && publicPoint[0].toInt() == 4) { "Invalid public point" }
        val nameBytes = name.take(40).toByteArray(Charsets.UTF_8)
        return REQUEST_MAGIC + publicPoint + nameBytes
    }

    fun privateKey(scalar: BigInteger): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, curve))

    private fun publicKey(point: ByteArray): ECPublicKey {
        require(point.size == POINT && point[0].toInt() == 4) { "Invalid public point" }
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** ECDH on P-256 then HKDF-SHA256 (RFC 5869) with the session challenge as salt -> 32-byte key. */
    fun derive(privateKey: PrivateKey, peerPoint: ByteArray, challenge: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey(peerPoint), true)
        val secret = agreement.generateSecret()
        require(secret.size == 32) { "ECDH shared secret length is ${secret.size}, expected 32" }
        val prk = hmac(challenge, secret)
        return hmac(prk, INFO + byteArrayOf(1))
    }

    /** Opens comma's reply. Throws when it is not for this request. */
    fun open(reply: ByteArray, privateKey: PrivateKey, challenge: ByteArray): Claim {
        require(reply.size > 4 + POINT + 12 + 16) { "Reply too short: ${reply.size}" }
        require(reply.copyOfRange(0, 4).contentEquals(REPLY_MAGIC)) { "Not a claim reply (bad magic)" }
        val peerPoint = reply.copyOfRange(4, 4 + POINT)
        val key = derive(privateKey, peerPoint, challenge)
        val nonce = reply.copyOfRange(4 + POINT, 4 + POINT + 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        val ciphertextWithTag = reply.copyOfRange(4 + POINT + 12, reply.size)
        val plaintext = cipher.doFinal(ciphertextWithTag)
        return parse(String(plaintext, Charsets.UTF_8))
    }

    fun parse(json: String): Claim {
        val obj = JSONObject(json)
        val hex = obj.optString("key", "")
        require(HEX64.matches(hex)) { "Missing or invalid 64-hex key in claim response" }
        val id = obj.optString("id", "")
        val address = obj.optString("adapterAddress", "")
        val name = obj.optString("adapterName", "")
        return Claim(
            key = GalaxyBle.hexToBytes(hex.lowercase()),
            id = if (PHONE_ID.matches(id)) id else "",
            adapterAddress = if (ADDRESS.matches(address)) address.uppercase() else "",
            adapterName = name.take(80)
        )
    }
}
