package com.parampilot.auto

/**
 * Pure classification of navigation maneuvers, shared by every display
 * (phone banner, Android Auto surface card, AA template) so all screens
 * make the same decision about what to show (Param, 2026-11-03: "use
 * same logic in both displays").
 */
object ManeuverLogic {

    /**
     * True when a maneuver is a pass-through step: it merely continues
     * along the current road (continue / straight / depart) and carries
     * no turn, ramp, merge or arrival of its own. For a pass-through step
     * the displays derive the NEXT real maneuver from the route steps
     * instead of presenting "Continue" with the destination's name —
     * the behavior Param saw differ between the phone and the car
     * screen on 2026-11-03.
     *
     * Anything with a direction (left / right / u-turn), an arrival, or
     * a real maneuver type is NOT pass-through and is shown exactly as
     * the comma issued it.
     */
    fun isPassThrough(type: String, modifier: String): Boolean {
        val t = type.lowercase().trim()
        val m = modifier.lowercase().trim()
        if (t == "arrive" || m == "arrive") return false
        if (m.contains("left") || m.contains("right")) return false
        if (m.contains("uturn") || m.contains("u-turn")) return false
        if (t in setOf("turn", "ramp", "on ramp", "off ramp", "fork", "roundabout", "rotary", "merge", "exit", "end of road")) return false
        return t in setOf("continue", "straight", "depart") || m == "straight"
    }
}
