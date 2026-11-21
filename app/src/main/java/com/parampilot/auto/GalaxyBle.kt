package com.parampilot.auto

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Galaxy BLE wire protocol implementation matching Comma's starpilot galaxy_bluetooth
 * and the official companion app BleWire.kt byte-for-byte.
 */
object GalaxyBle {
    const val SERVICE_UUID = "bd490001-6dc1-4de7-a7d0-6cdb441f7650"
    const val RX_UUID = "bd490002-6dc1-4de7-a7d0-6cdb441f7650"
    const val TX_UUID = "bd490003-6dc1-4de7-a7d0-6cdb441f7650"
    const val INFO_UUID = "bd490004-6dc1-4de7-a7d0-6cdb441f7650"
    const val NOTIFY_UUID = "bd490005-6dc1-4de7-a7d0-6cdb441f7650"

    const val MAX_FRAME = 2 * 1024 * 1024

    fun uint32be(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    fun readUint32be(b: ByteArray, off: Int = 0): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd-length hex string" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    /**
     * Accepts a 64-character hex key (or Comma JSON) and returns the 32-byte AES key.
     */
    fun parsePairingKey(text: String): ByteArray {
        val trimmed = text.trim()
        val keyHex: String = if (trimmed.startsWith("{")) {
            val obj = try {
                JSONObject(trimmed)
            } catch (_: Exception) {
                throw IllegalArgumentException("This isn't a Galaxy Bluetooth pairing code.")
            }
            obj.optString("key", trimmed)
        } else {
            trimmed
        }
        require(keyHex.length == 64) { "Pairing key must be 64 hex characters." }
        return hexToBytes(keyHex.lowercase())
    }

    /**
     * Seals request JSON into length-prefixed frame: [4-byte frame len][12-byte nonce][ciphertext + tag].
     * Uses Codec 0 (uncompressed envelope) matching BleWire.kt.
     */
    fun sealRequest(
        requestJson: JSONObject,
        key: ByteArray,
        direction: String = "request",
        nonce: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }
    ): ByteArray {
        val json = requestJson.toString().toByteArray(Charsets.UTF_8)
        require(json.size in 1..MAX_FRAME && key.size == 32 && nonce.size == 12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("galaxy-ble-v1/$direction".toByteArray(Charsets.UTF_8))

        val encoded = ByteBuffer.allocate(5 + json.size)
            .put(0) // codec 0
            .putInt(json.size)
            .put(json)
            .array()

        val data = nonce + cipher.doFinal(encoded)
        require(data.size <= MAX_FRAME)
        return ByteBuffer.allocate(4 + data.size).putInt(data.size).put(data).array()
    }

    /**
     * Opens sealed response frame (supports Codec 0, 1, and 2 with raw inflate).
     */
    fun openResponse(data: ByteArray, key: ByteArray, direction: String = "response"): JSONObject {
        require(data.size in 33..MAX_FRAME && key.size == 32) { "Invalid message length" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD("galaxy-ble-v1/$direction".toByteArray(Charsets.UTF_8))
        val encoded = cipher.doFinal(data.copyOfRange(12, data.size))
        require(encoded.size >= 5) { "Invalid envelope" }

        val size = ByteBuffer.wrap(encoded, 1, 4).int
        require(size in 1..MAX_FRAME)
        val codec = encoded[0].toInt()

        val plaintext: ByteArray = when (codec) {
            0 -> encoded.copyOfRange(5, encoded.size).also { require(it.size == size) }
            1, 2 -> {
                val inflater = Inflater(true /* raw deflate, nowrap */)
                try {
                    inflater.setInput(encoded, 5, encoded.size - 5)
                    val result = ByteArray(size + 1)
                    var count = 0
                    while (!inflater.finished() && count < result.size) {
                        val n = inflater.inflate(result, count, result.size - count)
                        require(n > 0 || inflater.finished())
                        count += n
                    }
                    require(inflater.finished() && inflater.remaining == 0 && count == size)
                    result.copyOf(size)
                } finally {
                    inflater.end()
                }
            }
            else -> throw IllegalArgumentException("Unknown Bluetooth codec $codec")
        }

        if (codec == 2) {
            require(size >= 4)
            val metadataLen = ByteBuffer.wrap(plaintext, 0, 4).int
            require(metadataLen in 1..(size - 4))
            val metaJson = JSONObject(String(plaintext.copyOfRange(4, 4 + metadataLen), Charsets.UTF_8))
            val bodyBytes = plaintext.copyOfRange(4 + metadataLen, size)
            if (bodyBytes.isNotEmpty()) {
                metaJson.put("body", Base64.getEncoder().encodeToString(bodyBytes))
            }
            return metaJson
        }

        return JSONObject(String(plaintext, Charsets.UTF_8))
    }

    /**
     * Splits sealed frame into packets [1][seq 4-bytes][chunk].
     */
    fun packets(frame: ByteArray, size: Int): List<ByteArray> {
        val payloadSize = (size - 5).coerceIn(15, 507)
        return (frame.indices step payloadSize).mapIndexed { index, start ->
            val count = minOf(payloadSize, frame.size - start)
            ByteBuffer.allocate(5 + count).put(1).putInt(index).put(frame, start, count).array()
        }
    }

    /**
     * Reassembles TX fragments into one frame.
     */
    class FrameAssembler {
        private val data = java.io.ByteArrayOutputStream()
        private var sequence = 0
        private var expectedLength: Int? = null

        fun reset() {
            data.reset()
            sequence = 0
            expectedLength = null
        }

        fun append(packet: ByteArray): ByteArray? {
            require(packet.size >= 6 && packet[0].toInt() == 1) { "Invalid fragment" }
            val seq = ByteBuffer.wrap(packet, 1, 4).int
            require(seq == sequence) { "Out of order fragment: got $seq, expected $sequence" }
            sequence++
            data.write(packet, 5, packet.size - 5)

            val bytes = data.toByteArray()
            if (expectedLength == null && bytes.size >= 4) {
                expectedLength = ByteBuffer.wrap(bytes, 0, 4).int
            }
            val exp = expectedLength ?: return null
            require(exp in 28..MAX_FRAME)
            if (bytes.size >= exp + 4) {
                require(bytes.size == exp + 4) { "Trailing frame bytes" }
                val result = bytes.copyOfRange(4, exp + 4)
                reset()
                return result
            }
            return null
        }
    }

    data class BridgeResponse(
        val id: String,
        val status: Int,
        val headers: JSONObject,
        val body: ByteArray
    ) {
        fun bodyText(): String = String(body, Charsets.UTF_8)
        fun bodyJson(): JSONObject? = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun buildRequest(
        id: String,
        session: String,
        counter: Long,
        path: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0)
    ): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("session", session)
        o.put("counter", counter)
        o.put("path", path)
        o.put("method", method)
        if (headers.isNotEmpty()) {
            val h = JSONObject()
            for ((k, v) in headers) h.put(k, v)
            o.put("headers", h)
        }
        if (body.isNotEmpty()) {
            o.put("body", Base64.getEncoder().encodeToString(body))
        }
        o.put("responseCodec", 2)
        o.put("responseFlow", "read-stream")
        return o
    }

    fun parseResponse(
        json: JSONObject,
        expectId: String,
        expectSession: String,
        expectCounter: Long
    ): BridgeResponse {
        require(json.optString("id") == expectId) { "Response id mismatch" }
        require(json.optString("session") == expectSession) { "Response session mismatch" }
        require(json.optLong("counter") == expectCounter) { "Response counter mismatch" }
        val bodyB64 = json.optString("body", "")
        val body = if (bodyB64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(bodyB64)
        return BridgeResponse(
            id = expectId,
            status = json.optInt("status", 0),
            headers = json.optJSONObject("headers") ?: JSONObject(),
            body = body
        )
    }
}
