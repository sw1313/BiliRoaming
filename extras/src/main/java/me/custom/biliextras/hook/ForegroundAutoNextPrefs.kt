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
        MATCH("match", "与上一视频方向一致"),
        PORTRAIT("portrait", "尽量竖屏"),
        LANDSCAPE("landscape", "尽量横屏"),
    }

    const val KEY_ORIENTATION = "foreground_auto_next_orientation"

    val orientationEntries = Orientation.entries.toList()

    fun orientation(): Orientation =
        orientationEntries.firstOrNull { it.value == ePrefs.getString(KEY_ORIENTATION, Orientation.MATCH.value) }
            ?: Orientation.MATCH

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
