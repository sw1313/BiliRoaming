package me.custom.biliextras.sponsorblock

import me.custom.biliextras.utils.ePrefs
import org.json.JSONArray
import org.json.JSONObject

/** Persistent segment cache so revisiting a video does not wait for SponsorBlock API. */
object SponsorBlockDiskCache {
    private const val KEY_PREFIX = "sb_seg_v1_"
    private const val MAX_ENTRIES = 64

    fun load(bvid: String, cid: Long): List<SponsorSegment>? {
        if (bvid.isBlank() || cid <= 0L) return null
        val raw = ePrefs.getString(storageKey(bvid, cid), null) ?: return null
        return decode(raw)
    }

    fun save(bvid: String, cid: Long, segments: List<SponsorSegment>) {
        if (bvid.isBlank() || cid <= 0L) return
        val editor = ePrefs.edit()
        if (segments.isEmpty()) {
            editor.remove(storageKey(bvid, cid)).apply()
            return
        }
        editor.putString(storageKey(bvid, cid), encode(segments))
        trimOldEntries(editor)
        editor.apply()
    }

    fun remove(bvid: String, cid: Long) {
        if (bvid.isBlank() || cid <= 0L) return
        ePrefs.edit().remove(storageKey(bvid, cid)).apply()
    }

    private fun storageKey(bvid: String, cid: Long) = "$KEY_PREFIX$bvid/$cid"

    private fun encode(segments: List<SponsorSegment>): String {
        val array = JSONArray()
        segments.forEach { segment ->
            array.put(
                JSONObject().apply {
                    put("segment", JSONArray().put(segment.start).put(segment.end))
                    put("category", segment.category)
                    put("UUID", segment.uuid)
                    put("actionType", segment.actionType)
                },
            )
        }
        return array.toString()
    }

    private fun decode(raw: String): List<SponsorSegment>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                SponsorSegment.fromJson(array.getJSONObject(i))?.let(::add)
            }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun trimOldEntries(editor: android.content.SharedPreferences.Editor) {
        val keys = ePrefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
        if (keys.size <= MAX_ENTRIES) return
        keys.sorted().take(keys.size - MAX_ENTRIES).forEach { editor.remove(it) }
    }
}
