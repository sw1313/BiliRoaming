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

        val playerClass = instance.storyPagerPlayerClass ?: return
        var hooked = false

        instance.addVideoMethod()?.name?.let { methodName ->
            hookListIngress(playerClass, methodName, "addVideo")
            hooked = true
        } ?: Log.w { "BlockStoryLive: addVideo method not found" }

        instance.insertStoryCardsMethod()?.name?.let { methodName ->
            hookListIngress(playerClass, methodName, "insertStoryCards")
            hooked = true
        }

        instance.updateStoryListMethod()?.name?.let { methodName ->
            playerClass.hookMethod(
                methodName,
                Int::class.javaPrimitiveType!!,
                List::class.java,
            ) { chain ->
                (chain.args[1] as? MutableList<Any?>)?.filterStoryLiveList()
                chain.proceed()
            }
            Log.s("startHook: BlockStoryLive on ${playerClass.name}#$methodName(updateList)")
            hooked = true
        }

        if (!hooked) {
            Log.w { "BlockStoryLive: no StoryPagerPlayer ingress methods found" }
        }
    }

    private fun hookListIngress(playerClass: Class<*>, methodName: String, label: String) {
        playerClass.hookMethod(methodName, List::class.java) { chain ->
            (chain.args[0] as? MutableList<Any?>)?.filterStoryLiveList()
            chain.proceed()
        }
        Log.s("startHook: BlockStoryLive on ${playerClass.name}#$methodName ($label)")
    }

    private fun isStoryLive(item: Any, storyDetail: Class<*>): Boolean {
        val methods = storyDetailMethodCache.getOrPut(storyDetail) {
            StoryDetailMethods(
                StoryDetailReflection.findNoArgMethod(storyDetail, "isLive"),
                StoryDetailReflection.findNoArgMethod(storyDetail, "isAdLive"),
                StoryDetailReflection.findNoArgMethod(storyDetail, "getLiveRoom"),
            )
        }
        StoryDetailReflection.invokeBool(methods.isLive, item)?.let { if (it) return true }
        StoryDetailReflection.invokeBool(methods.isAdLive, item)?.let { if (it) return true }
        methods.getLiveRoom?.invoke(item)?.let { liveRoom ->
            val liveRoomMethods = liveRoomMethodCache.getOrPut(liveRoom.javaClass) {
                val type = liveRoom.javaClass
                LiveRoomMethods(
                    StoryDetailReflection.findNoArgMethod(type, "isLiving"),
                    StoryDetailReflection.findNoArgMethod(type, "isShowLiving"),
                    StoryDetailReflection.findNoArgMethod(type, "getLiveType"),
                )
            }
            if (StoryDetailReflection.invokeBool(liveRoomMethods.isLiving, liveRoom) == true ||
                StoryDetailReflection.invokeBool(liveRoomMethods.isShowLiving, liveRoom) == true
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
            Log.d { "BlockStoryLive: removed $removed live item(s)" }
        }
    }
}
