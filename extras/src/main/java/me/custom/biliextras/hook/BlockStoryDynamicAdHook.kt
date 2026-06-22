package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*

/**
 * Blocks commercial story ads on dynamic ingress paths not covered by roaming StoryPlayerAdHook
 * (addVideo/h1 only): mid-feed insert (c2) and AdStoryReRank updateList (p3).
 */
class BlockStoryDynamicAdHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!ePrefs.getBoolean("block_story_ad_dynamic", false)) return

        val playerClass = instance.storyPagerPlayerClass ?: return
        var hooked = false

        instance.insertStoryCardsMethod()?.name?.let { methodName ->
            playerClass.hookMethod(methodName, List::class.java) { chain ->
                filterAdList(chain.args[0] as? MutableList<Any?>)
                chain.proceed()
            }
            Log.s("startHook: BlockStoryDynamicAd on ${playerClass.name}#$methodName")
            hooked = true
        }

        instance.updateStoryListMethod()?.name?.let { methodName ->
            playerClass.hookMethod(
                methodName,
                Int::class.javaPrimitiveType!!,
                List::class.java,
            ) { chain ->
                filterAdList(chain.args[1] as? MutableList<Any?>)
                chain.proceed()
            }
            Log.s("startHook: BlockStoryDynamicAd on ${playerClass.name}#$methodName(updateList)")
            hooked = true
        }

        if (!hooked) {
            Log.w { "BlockStoryDynamicAd: no insert/updateList methods found" }
        }
    }

    private fun filterAdList(list: MutableList<Any?>?) {
        list ?: return
        val before = list.size
        list.removeAll { item -> item != null && StoryDetailReflection.isStoryAd(item) }
        val removed = before - list.size
        if (removed > 0) Log.d { "BlockStoryDynamicAd: removed $removed ad item(s)" }
    }
}
