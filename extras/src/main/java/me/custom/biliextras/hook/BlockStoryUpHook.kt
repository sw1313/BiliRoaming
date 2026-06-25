package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*

/**
 * Blocks Story feed items from blocked UPs on addVideo (h1) and W2 bulk reload,
 * same ingress as [BlockStoryLiveHook].
 */
class BlockStoryUpHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        val playerClass = instance.storyPagerPlayerClass ?: return
        val methodName = instance.addVideoMethod()?.name ?: run {
            Log.w { "BlockStoryUp: addVideo method not found" }
            return
        }
        playerClass.hookMethod(methodName, List::class.java) { chain ->
            StoryUpFilter.filterMutableList(chain.args[0] as? MutableList<Any?>)
            chain.proceed()
        }
        playerClass.declaredMethods.firstOrNull {
            it.name == "W2" && it.parameterCount == 3 &&
                it.parameterTypes[0] == List::class.java
        }?.hookMethod { chain ->
            StoryUpFilter.filterMutableList(chain.args[0] as? MutableList<Any?>)
            chain.proceed()
        }
        Log.s("startHook: BlockStoryUp on ${playerClass.name}#$methodName (+W2)")
    }
}
