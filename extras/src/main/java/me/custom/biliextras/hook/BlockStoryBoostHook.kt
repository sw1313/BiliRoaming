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
            StoryBoostFilter.filteredCopyIfChanged(chain.args[0] as? List<Any?>)?.let {
                val args = chain.args.toTypedArray()
                args[0] = it
                return@hookMethod chain.proceed(args)
            }
            chain.proceed()
        }
        playerClass.declaredMethods.firstOrNull {
            it.name == "W2" && it.parameterCount == 3 &&
                it.parameterTypes[0] == List::class.java
        }?.hookMethod { chain ->
            StoryBoostFilter.filteredCopyIfChanged(chain.args[0] as? List<Any?>)?.let {
                val args = chain.args.toTypedArray()
                args[0] = it
                return@hookMethod chain.proceed(args)
            }
            chain.proceed()
        }
        Log.s("startHook: BlockStoryBoost on ${playerClass.name}#$methodName (+W2)")
    }
}
