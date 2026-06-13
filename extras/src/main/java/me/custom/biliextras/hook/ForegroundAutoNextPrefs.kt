package me.custom.biliextras.hook

import me.custom.biliextras.utils.UnitedScreenStateReflection
import me.custom.biliextras.utils.ePrefs

/**
 * Which playback situations use foreground AI relate auto-next (with RelatesFeed fallback).
 */
object ForegroundAutoNextPrefs {

    enum class Scope {
        SINGLE,
        COLLECTION_MIDDLE,
        COLLECTION_LAST,
    }

    data class Entry(
        val key: String,
        val title: String,
        val scope: Scope,
    )

    const val KEY_SINGLE = "foreground_auto_next_single"
    const val KEY_COLLECTION_MIDDLE = "foreground_auto_next_collection_mid"
    const val KEY_COLLECTION_LAST = "foreground_auto_next_collection_last"

    val allEntries = listOf(
        Entry(KEY_SINGLE, "单集", Scope.SINGLE),
        Entry(KEY_COLLECTION_MIDDLE, "合集（非最后一集）", Scope.COLLECTION_MIDDLE),
        Entry(KEY_COLLECTION_LAST, "合集（最后一集）", Scope.COLLECTION_LAST),
    )

    fun defaultEnabled(key: String): Boolean = when (key) {
        KEY_SINGLE, KEY_COLLECTION_LAST -> true
        else -> false
    }

    fun isEnabled(key: String): Boolean = ePrefs.getBoolean(key, defaultEnabled(key))

    fun isScopeEnabled(scope: Scope): Boolean = when (scope) {
        Scope.SINGLE -> isEnabled(KEY_SINGLE)
        Scope.COLLECTION_MIDDLE -> isEnabled(KEY_COLLECTION_MIDDLE)
        Scope.COLLECTION_LAST -> isEnabled(KEY_COLLECTION_LAST)
    }

    fun shouldApplyAiAutoNext(service: Any): Boolean = isScopeEnabled(classify(service))

    fun enabledShortTitles(): List<String> =
        allEntries.filter { isEnabled(it.key) }.map { it.title }

    enum class Orientation(val value: String, val title: String) {
        NONE("none", "无"),
        MATCH("match", "与上一视频方向尽量一致"),
        PORTRAIT("portrait", "尽量竖屏"),
        LANDSCAPE("landscape", "尽量横屏"),
    }

    enum class UpPref(val value: String, val title: String) {
        NONE("none", "无"),
        DIFFERENT("different", "尽量与上一 up 不一致"),
        SAME("same", "尽量与上一 up 一致"),
    }

    enum class TagPref(val value: String, val title: String) {
        NONE("none", "无"),
        SAME("same", "尽量与上一视频含相同 tag"),
        DIFFERENT("different", "尽量与上一视频不含相同 tag"),
    }

    const val KEY_ORIENTATION = "foreground_auto_next_orientation"
    const val KEY_UP = "foreground_auto_next_up"
    const val KEY_TAG = "foreground_auto_next_tag"

    val orientationEntries = Orientation.entries.toList()
    val upEntries = UpPref.entries.toList()
    val tagEntries = TagPref.entries.toList()

    fun orientation(): Orientation =
        orientationEntries.firstOrNull { it.value == ePrefs.getString(KEY_ORIENTATION, Orientation.MATCH.value) }
            ?: Orientation.MATCH

    fun upPref(): UpPref =
        upEntries.firstOrNull { it.value == ePrefs.getString(KEY_UP, UpPref.NONE.value) }
            ?: UpPref.NONE

    fun tagPref(): TagPref =
        tagEntries.firstOrNull { it.value == ePrefs.getString(KEY_TAG, TagPref.NONE.value) }
            ?: TagPref.NONE

    fun hasActiveVideoPickPrefs(): Boolean =
        orientation() != Orientation.NONE || upPref() != UpPref.NONE || tagPref() != TagPref.NONE

    /** up / tag matching needs View API; orientation can use relate-feed dimensions only. */
    fun needsNetworkMeta(): Boolean =
        upPref() != UpPref.NONE || tagPref() != TagPref.NONE

    fun videoPrefsSummary(): String =
        "方向：${orientation().title} · up：${upPref().title} · tag：${tagPref().title}"

    /** null = no orientation filter (pick first in AI / feed order). */
    fun resolvePreferPortrait(service: Any): Boolean? = when (orientation()) {
        Orientation.NONE -> null
        Orientation.PORTRAIT -> true
        Orientation.LANDSCAPE -> false
        Orientation.MATCH -> getCurrentVideoPortrait(service)
    }

    fun getCurrentVideoPortrait(service: Any): Boolean? {
        val ctx = UgcBackgroundPlayReflection.context(service)
        val roots = listOfNotNull(service, ctx)
        for (root in roots) {
            UnitedScreenStateReflection.findScreenStateRepo(root)?.let { repo ->
                return UnitedScreenStateReflection.readPortrait(repo)
            }
        }
        return null
    }

    /**
     * Classify the currently playing item:
     * - 合集非最后一集: episode list has a next part after current
     * - 合集最后一集: multi-part list but no next part
     * - 单集: only one part in the episode list
     */
    fun classify(service: Any): Scope = UgcBackgroundPlayReflection.classifyScope(service)
}
