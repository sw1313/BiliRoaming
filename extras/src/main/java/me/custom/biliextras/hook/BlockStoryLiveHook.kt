package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*

/**
 * Blocks story live cards on the same ingress as roaming [StoryPlayerAdHook] (addVideo / h1).
 * Also filters W2 bulk-reload batches — bg handoff snapshot restore can bypass a one-shot h1 filter.
 */
class BlockStoryLiveHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        val playerClass = instance.storyPagerPlayerClass ?: return
        val methodName = instance.addVideoMethod()?.name ?: run {
            Log.w { "BlockStoryLive: addVideo method not found" }
            return
        }
        playerClass.hookMethod(methodName, List::class.java) { chain ->
            StoryLiveFilter.filteredCopyIfChanged(chain.args[0] as? List<Any?>)?.let {
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
            StoryLiveFilter.filteredCopyIfChanged(chain.args[0] as? List<Any?>)?.let {
                val args = chain.args.toTypedArray()
                args[0] = it
                return@hookMethod chain.proceed(args)
            }
            chain.proceed()
        }
        Log.s("startHook: BlockStoryLive on ${playerClass.name}#$methodName (+W2)")
    }
}
