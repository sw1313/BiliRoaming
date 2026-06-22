package me.custom.biliextras.sponsorblock

import org.json.JSONObject

data class SponsorSegment(
    val start: Double,
    val end: Double,
    val category: String,
    val uuid: String,
    val actionType: String,
) {
    val duration: Double
        get() = end - start

    val startMs: Long
        get() = (start * 1000).toLong().coerceAtLeast(0L)

    /** Rounded end matches SponsorBlock API sub-second boundaries for skip + progress overlay. */
    val endMs: Long
        get() = kotlin.math.round(end * 1000).toLong().coerceAtLeast(startMs)

    companion object {
        fun fromJson(obj: JSONObject): SponsorSegment? {
            val segment = obj.optJSONArray("segment") ?: return null
            if (segment.length() < 2) return null
            val start = segment.optDouble(0, Double.NaN)
            val end = segment.optDouble(1, Double.NaN)
            if (start.isNaN() || end.isNaN() || end <= start) return null
            return SponsorSegment(
                start = start,
                end = end,
                category = obj.optString("category"),
                uuid = obj.optString("UUID", obj.optString("uuid")),
                actionType = obj.optString("actionType", "skip"),
            )
        }
    }
}
