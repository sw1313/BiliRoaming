package me.custom.biliextras.hook

import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Shared Story feed live-card detection for addVideo (h1) and bg handoff snapshot restore. */
object StoryLiveFilter {
    private data class StoryDetailMethods(
        val isLive: Method?,
        val isAdLive: Method?,
        val getLiveRoom: Method?,
        val getGoto: Method?,
        val getRoomId: Method?,
    )

    private data class LiveRoomMethods(
        val isLiving: Method?,
        val isShowLiving: Method?,
        val getLiveType: Method?,
    )

    private val storyDetailMethodCache = ConcurrentHashMap<Class<*>, StoryDetailMethods>()
    private val liveRoomMethodCache = ConcurrentHashMap<Class<*>, LiveRoomMethods>()

    fun enabled(): Boolean = ePrefs.getBoolean("block_story_live", false)

    fun filterMutableList(list: MutableList<Any?>?) {
        if (!enabled() || list == null) return
        val before = list.size
        list.removeAll { item -> item != null && isStoryLive(item) }
        val removed = before - list.size
        if (removed > 0) {
            Log.trace { "StoryLiveFilter: removed $removed live item(s)" }
        }
    }

    fun isStoryLive(item: Any): Boolean {
        val storyDetail = item.javaClass
        val methods = storyDetailMethodCache.getOrPut(storyDetail) {
            StoryDetailMethods(
                findNoArgMethod(storyDetail, "isLive"),
                findNoArgMethod(storyDetail, "isAdLive"),
                findNoArgMethod(storyDetail, "getLiveRoom"),
                findNoArgMethod(storyDetail, "getGoto"),
                findNoArgMethod(storyDetail, "getRoomId"),
            )
        }
        invokeBool(methods.isLive, item)?.let { if (it) return true }
        invokeBool(methods.isAdLive, item)?.let { if (it) return true }
        when (val goto = methods.getGoto?.invoke(item) as? String) {
            "vertical_live", "vertical_ad_live" -> return true
        }
        val roomId = (methods.getRoomId?.invoke(item) as? Number)?.toLong() ?: 0L
        if (roomId > 0L) {
            val goto = methods.getGoto?.invoke(item) as? String
            if (goto == "vertical_live" || goto == "vertical_ad_live") return true
        }
        methods.getLiveRoom?.invoke(item)?.let { liveRoom ->
            val liveRoomMethods = liveRoomMethodCache.getOrPut(liveRoom.javaClass) {
                val type = liveRoom.javaClass
                LiveRoomMethods(
                    findNoArgMethod(type, "isLiving"),
                    findNoArgMethod(type, "isShowLiving"),
                    findNoArgMethod(type, "getLiveType"),
                )
            }
            if (invokeBool(liveRoomMethods.isLiving, liveRoom) == true ||
                invokeBool(liveRoomMethods.isShowLiving, liveRoom) == true
            ) {
                return true
            }
            val liveType = liveRoomMethods.getLiveType?.invoke(liveRoom) as? String
            if (!liveType.isNullOrBlank()) return true
        }
        return false
    }

    private fun findNoArgMethod(type: Class<*>, name: String): Method? = runCatching {
        type.getDeclaredMethod(name).apply { isAccessible = true }
    }.getOrNull()

    private fun invokeBool(method: Method?, target: Any): Boolean? = try {
        method?.invoke(target) as? Boolean
    } catch (_: Exception) {
        null
    }
}
