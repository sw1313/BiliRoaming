package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class BlockStoryLiveHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private data class StoryDetailMethods(
        val isLive: Method?,
        val isAdLive: Method?,
        val getLiveRoom: Method?,
    )

    private data class LiveRoomMethods(
        val isLiving: Method?,
        val isShowLiving: Method?,
        val getLiveType: Method?,
    )

    private val storyDetailMethodCache = ConcurrentHashMap<Class<*>, StoryDetailMethods>()
    private val liveRoomMethodCache = ConcurrentHashMap<Class<*>, LiveRoomMethods>()

    override fun startHook() {
        if (!ePrefs.getBoolean("block_story_live", false)) return

        hookStoryPagerPlayer()
    }

    private fun hookStoryPagerPlayer() {
        val addVideo = instance.addVideoMethod()?.name ?: run {
            Log.w("BlockStoryLive: addVideo method not found")
            return
        }
        val playerClass = instance.storyPagerPlayerClass ?: return

        Log.d("startHook: BlockStoryLive on ${playerClass.name}#$addVideo")
        playerClass.hookMethod(addVideo, List::class.java) { chain ->
            val storyDetailList = chain.args[0] as? MutableList<Any?> ?: return@hookMethod chain.proceed()
            storyDetailList.filterStoryLiveList()
            chain.proceed()
        }
    }

    private fun isStoryLive(item: Any, storyDetail: Class<*>): Boolean {
        val methods = storyDetailMethodCache.getOrPut(storyDetail) {
            StoryDetailMethods(
                storyDetail.findNoArgMethod("isLive"),
                storyDetail.findNoArgMethod("isAdLive"),
                storyDetail.findNoArgMethod("getLiveRoom"),
            )
        }
        methods.isLive?.invokeBool(item)?.let { if (it) return true }
        methods.isAdLive?.invokeBool(item)?.let { if (it) return true }
        methods.getLiveRoom?.invoke(item)?.let { liveRoom ->
            val liveRoomMethods = liveRoomMethodCache.getOrPut(liveRoom.javaClass) {
                LiveRoomMethods(
                    liveRoom.javaClass.findNoArgMethod("isLiving"),
                    liveRoom.javaClass.findNoArgMethod("isShowLiving"),
                    liveRoom.javaClass.findNoArgMethod("getLiveType"),
                )
            }
            if (liveRoomMethods.isLiving?.invokeBool(liveRoom) == true ||
                liveRoomMethods.isShowLiving?.invokeBool(liveRoom) == true
            ) return true
            val liveType = liveRoomMethods.getLiveType?.invoke(liveRoom) as? String
            if (!liveType.isNullOrBlank()) return true
        }
        return false
    }

    private fun MutableList<Any?>.filterStoryLiveList() {
        val before = size
        removeAll { item ->
            item != null && isStoryLive(item, item.javaClass)
        }
        val removed = before - size
        if (removed > 0) {
            Log.d("BlockStoryLive: removed $removed live item(s)")
        }
    }

    private fun Class<*>.findNoArgMethod(name: String): Method? = runCatching {
        getDeclaredMethod(name).apply { isAccessible = true }
    }.getOrNull()

    private fun Method.invokeBool(item: Any): Boolean? = try {
        invoke(item) as? Boolean
    } catch (_: Exception) {
        null
    }
}
