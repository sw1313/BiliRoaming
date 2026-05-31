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
            Log.w("BlockStoryAd: addVideo method not found")
            return
        }
        val playerClass = instance.storyPagerPlayerClass ?: return

        Log.s("startHook: BlockStoryAd on ${playerClass.name}#$addVideo")
        playerClass.hookMethod(addVideo, List::class.java) { chain ->
            val list = chain.args[0] as? MutableList<Any?> ?: return@hookMethod chain.proceed()
            val before = list.size
            list.removeAll { item -> item != null && isStoryAd(item) }
            val removed = before - list.size
            if (removed > 0) Log.d("BlockStoryAd: removed $removed ad item(s)")
            chain.proceed()
        }
    }

    private fun isStoryAd(item: Any): Boolean {
        val methods = methodCache.getOrPut(item.javaClass) {
            StoryDetailAdMethods(
                item.javaClass.findNoArgMethod("isAd"),
                item.javaClass.findNoArgMethod("isAdHardAndFly"),
                item.javaClass.findNoArgMethod("isAdImage"),
                item.javaClass.findNoArgMethod("isAdLive"),
                item.javaClass.findNoArgMethod("isAdLocal"),
            )
        }
        if (methods.isAd?.invokeBool(item) == true) return true
        if (methods.isAdHardAndFly?.invokeBool(item) == true) return true
        if (methods.isAdImage?.invokeBool(item) == true) return true
        if (methods.isAdLive?.invokeBool(item) == true) return true
        if (methods.isAdLocal?.invokeBool(item) == true) return true
        return false
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
