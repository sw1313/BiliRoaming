package me.custom.biliextras.hook

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
        val ctx = runCatching {
            service.javaClass.getDeclaredField("n").apply { isAccessible = true }.get(service)
        }.getOrNull()
        val roots = listOfNotNull(service, ctx)
        for (root in roots) {
            findScreenStateRepo(root)?.let { repo ->
                val state = repo.javaClass.getMethod("h").invoke(repo) ?: return null
                return state.javaClass.getMethod("e").invoke(state) as? Boolean
            }
        }
        return null
    }

    private fun findScreenStateRepo(root: Any, maxDepth: Int = 4): Any? {
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (obj, depth) = queue.removeFirst()
            val id = System.identityHashCode(obj)
            if (!visited.add(id)) continue
            if (looksLikeScreenStateRepo(obj)) return obj
            if (depth >= maxDepth) continue
            for (field in obj.javaClass.declaredFields) {
                runCatching {
                    field.isAccessible = true
                    val value = field.get(obj) ?: return@runCatching
                    if (shouldTraverse(value)) queue.add(value to depth + 1)
                }
            }
        }
        return null
    }

    private fun looksLikeScreenStateRepo(obj: Any): Boolean = runCatching {
        val cls = obj.javaClass
        cls.getMethod("h")
        cls.getMethod("c")
        cls.getMethod("j", Any::class.java, Boolean::class.javaPrimitiveType)
        true
    }.getOrDefault(false)

    private fun shouldTraverse(value: Any): Boolean {
        if (value is String || value is Number || value is Boolean || value is Char) return false
        if (value is Class<*>) return false
        val name = value.javaClass.name
        return !(name.startsWith("java.") || name.startsWith("kotlin.") || name.startsWith("kotlinx."))
    }

    /**
     * Classify the currently playing item:
     * - 合集非最后一集: episode list has a next part after current
     * - 合集最后一集: multi-part list but no next part
     * - 单集: only one part in the episode list
     */
    fun classify(service: Any): Scope {
        val episodeRepo = runCatching {
            service.javaClass.getDeclaredField("c").apply { isAccessible = true }.get(service)
        }.getOrNull() ?: return Scope.SINGLE
        val playbackRepo = runCatching {
            service.javaClass.getDeclaredField("d").apply { isAccessible = true }.get(service)
        }.getOrNull() ?: return Scope.SINGLE
        val current = runCatching {
            playbackRepo.javaClass.getMethod("w").invoke(playbackRepo)
        }.getOrNull()
        val listSize = runCatching {
            (episodeRepo.javaClass.getMethod("g").invoke(episodeRepo) as? List<*>)?.size ?: 1
        }.getOrDefault(1)
        val hasNext = runCatching {
            val jMethod = episodeRepo.javaClass.declaredMethods
                .firstOrNull { it.name == "j" && it.parameterCount == 1 }
                ?.apply { isAccessible = true } ?: return@runCatching false
            jMethod.invoke(episodeRepo, current) != null
        }.getOrDefault(false)
        return when {
            hasNext -> Scope.COLLECTION_MIDDLE
            listSize > 1 -> Scope.COLLECTION_LAST
            else -> Scope.SINGLE
        }
    }
}
