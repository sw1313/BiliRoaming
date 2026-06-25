package me.custom.biliextras.hook

import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Story cards with the rocket/lightning icon beside play count use [StoryDetail.isVt] (JSON `is_vt`).
 */
object StoryBoostFilter {
    const val KEY_ENABLED = "block_story_boost"

    private data class StoryDetailMethods(
        val isVt: Method?,
        val getBvid: Method?,
        val getCid: Method?,
    )

    private val storyDetailMethodCache = ConcurrentHashMap<Class<*>, StoryDetailMethods>()

    fun enabled(): Boolean = ePrefs.getBoolean(KEY_ENABLED, false)

    fun filterMutableList(list: MutableList<Any?>?) {
        if (!enabled() || list == null) return
        val before = list.size
        list.removeAll { item -> item != null && isBoostMarked(item) }
        val removed = before - list.size
        if (removed > 0) {
            Log.trace { "StoryBoostFilter: removed $removed boost-marked item(s)" }
        }
    }

    fun isBoostMarked(item: Any): Boolean {
        if (!enabled()) return false
        return invokeBool(storyMethods(item).isVt, item) == true
    }

    fun cardId(item: Any): String {
        val methods = storyMethods(item)
        val bvid = invokeString(methods.getBvid, item)
        val cid = (invokeLong(methods.getCid, item) ?: 0L)
        return "${bvid ?: "unknown"}/$cid"
    }

    private fun storyMethods(item: Any): StoryDetailMethods {
        val clazz = item.javaClass
        return storyDetailMethodCache.getOrPut(clazz) {
            StoryDetailMethods(
                isVt = findNoArgMethod(clazz, "isVt"),
                getBvid = findNoArgMethod(clazz, "getBvid", "getBvId"),
                getCid = findNoArgMethod(clazz, "getCid"),
            )
        }
    }

    private fun findNoArgMethod(type: Class<*>, vararg names: String): Method? {
        for (name in names) {
            runCatching {
                return type.getDeclaredMethod(name).apply { isAccessible = true }
            }
        }
        return null
    }

    private fun invokeBool(method: Method?, target: Any): Boolean? = try {
        method?.invoke(target) as? Boolean
    } catch (_: Exception) {
        null
    }

    private fun invokeString(method: Method?, target: Any): String? = try {
        method?.invoke(target) as? String
    } catch (_: Exception) {
        null
    }

    private fun invokeLong(method: Method?, target: Any): Long? = try {
        (method?.invoke(target) as? Number)?.toLong()
    } catch (_: Exception) {
        null
    }
}
