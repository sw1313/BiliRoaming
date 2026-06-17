package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Removes whole ad videos from the vertical (story) stream, mirroring BlockStoryLiveHook.
 *
 * StoryDetail.isAd() == getGotoIsAd() && adInfo != null, where getGotoIsAd() is true for
 * goto in {vertical_ad_av (硬广/飞天), vertical_ad_live (广告直播), vertical_ad_picture (广告图)}.
 * These are real commercial ads (advertiser-placed), often disguised as organic creator
 * videos. We also check the goto-based predicates directly as a fallback in case adInfo is
 * absent. The card is dropped from the list before the pager binds it.
 */
class BlockStoryAdHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private data class StoryDetailAdMethods(
        val isAd: Method?,
        val isAdHardAndFly: Method?,
        val isAdImage: Method?,
        val isAdLive: Method?,
        val isAdLocal: Method?,
    )

    private val methodCache = ConcurrentHashMap<Class<*>, StoryDetailAdMethods>()

    override fun startHook() {
        if (!ePrefs.getBoolean("block_story_ad", false)) return

        val addVideo = instance.addVideoMethod()?.name ?: run {
            Log.w { "BlockStoryAd: addVideo method not found" }
            return
        }
        val playerClass = instance.storyPagerPlayerClass ?: return

        Log.s("startHook: BlockStoryAd on ${playerClass.name}#$addVideo")
        playerClass.hookMethod(addVideo, List::class.java) { chain ->
            val list = chain.args[0] as? MutableList<Any?> ?: return@hookMethod chain.proceed()
            val before = list.size
            list.removeAll { item -> item != null && isStoryAd(item) }
            val removed = before - list.size
            if (removed > 0) Log.trace { "BlockStoryAd: removed $removed ad item(s)" }
            chain.proceed()
        }
    }

    private fun isStoryAd(item: Any): Boolean {
        val methods = methodCache.getOrPut(item.javaClass) {
            val type = item.javaClass
            StoryDetailAdMethods(
                StoryDetailReflection.findNoArgMethod(type, "isAd"),
                StoryDetailReflection.findNoArgMethod(type, "isAdHardAndFly"),
                StoryDetailReflection.findNoArgMethod(type, "isAdImage"),
                StoryDetailReflection.findNoArgMethod(type, "isAdLive"),
                StoryDetailReflection.findNoArgMethod(type, "isAdLocal"),
            )
        }
        if (StoryDetailReflection.invokeBool(methods.isAd, item) == true) return true
        if (StoryDetailReflection.invokeBool(methods.isAdHardAndFly, item) == true) return true
        if (StoryDetailReflection.invokeBool(methods.isAdImage, item) == true) return true
        if (StoryDetailReflection.invokeBool(methods.isAdLive, item) == true) return true
        if (StoryDetailReflection.invokeBool(methods.isAdLocal, item) == true) return true
        return false
    }
}
