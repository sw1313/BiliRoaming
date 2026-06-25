package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*

/**
 * Blocks Story feed items marked with the rocket/lightning play-count icon ([StoryDetail.isVt]).
 * Same ingress as [BlockStoryLiveHook] (addVideo / W2).
 */
class BlockStoryBoostHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        val playerClass = instance.storyPagerPlayerClass ?: return
        val methodName = instance.addVideoMethod()?.name ?: run {
            Log.w { "BlockStoryBoost: addVideo method not found" }
            return
        }
        playerClass.hookMethod(methodName, List::class.java) { chain ->
            StoryBoostFilter.filterMutableList(chain.args[0] as? MutableList<Any?>)
            chain.proceed()
        }
        playerClass.declaredMethods.firstOrNull {
            it.name == "W2" && it.parameterCount == 3 &&
                it.parameterTypes[0] == List::class.java
        }?.hookMethod { chain ->
            StoryBoostFilter.filterMutableList(chain.args[0] as? MutableList<Any?>)
            chain.proceed()
        }
        Log.s("startHook: BlockStoryBoost on ${playerClass.name}#$methodName (+W2)")
    }
}
