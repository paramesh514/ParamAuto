package com.parampilot.auto

import org.json.JSONArray
import org.json.JSONObject

/**
 * Layer 2: THE parser for StarPilot's navigation instruction — the
 * one and only place an SG status snapshot's `instruction` object
 * becomes a [NavManeuver].
 *
 * Why this exists (Param's field report, 2026-11-06: "Lane
 * information is blinking in Auto display"): two 1-second loops
 * rewrite the shared nav state from the same comma status — the
 * CommaClient poll (TelemetryPacket.fromSg) and NavigationManager's
 * nav-refresh (applySgSnapshotInternal). Each had its own inline
 * copy of this parse, and the copies had drifted: the poll read the
 * instruction's lane fields (lanes / laneCount / activeLaneIndex),
 * the refresh hardcoded lanes = emptyList(). Whichever loop wrote
 * last won, so the lane strip — and the maneuver card height that
 * depends on it (96 vs 140) — flipped about once a second.
 *
 * Both writers now call [parse]; there is no second copy to drift.
 * Field rules are exactly the poll's original ones, including the
 * fallbacks: lanes from instruction.lanes, else instruction.allLanes,
 * else the status-level lanes array; laneCount from the explicit key
 * (either spelling) else the parsed lanes size; modifier "none"
 * normalizes to "".
 */
object SgInstruction {

    /** Parse [instr] (the snapshot's instruction object, may be null)
     *  in the context of its full status snapshot [navStatus]. */
    fun parse(instr: JSONObject?, navStatus: JSONObject): NavManeuver {
        if (instr == null) return NavManeuver()
        val modifier = instr.optString("maneuverModifier", "")
        val lanesJson = instr.optJSONArray("lanes")
            ?: instr.optJSONArray("allLanes")
            ?: navStatus.optJSONArray("lanes")
        val lanes = parseLanes(lanesJson)
        return NavManeuver(
            valid = true,
            type = instr.optString("maneuverType", ""),
            modifier = if (modifier == "none") "" else modifier,
            distanceM = instr.optDouble("distanceMeters", 0.0),
            remainingDistanceM = instr.optDouble("remainingDistanceMeters", 0.0),
            remainingDurationSec = instr.optDouble("remainingDurationSeconds", 0.0),
            street = instr.optString("text", ""),
            laneCount = instr.optInt("laneCount", instr.optInt("lane_count", lanes.size)),
            activeLaneIndex = instr.optInt(
                "activeLaneIndex", instr.optInt("active_lane_index", -1)
            ),
            activeLaneDirection = instr.optString(
                "activeLaneDirection", instr.optString("active_lane_direction", "")
            ),
            lanes = lanes
        )
    }

    /** Per-lane directions + active flag from a lanes JSON array. */
    fun parseLanes(lanesJson: JSONArray?): List<LaneInfo> {
        if (lanesJson == null) return emptyList()
        val lanes = mutableListOf<LaneInfo>()
        for (i in 0 until lanesJson.length()) {
            val l = lanesJson.optJSONObject(i) ?: continue
            val dirsJson = l.optJSONArray("directions")
            val dirs = mutableListOf<String>()
            if (dirsJson != null) {
                for (j in 0 until dirsJson.length()) {
                    val d = dirsJson.optString(j, "")
                    if (d.isNotEmpty()) dirs.add(d)
                }
            }
            lanes.add(LaneInfo(dirs, l.optBoolean("active", false)))
        }
        return lanes
    }
}
