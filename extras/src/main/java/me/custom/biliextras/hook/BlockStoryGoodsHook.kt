package me.custom.biliextras.hook

import android.view.View
import me.custom.biliextras.utils.*

class BlockStoryGoodsHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!ePrefs.getBoolean(PREF_KEY, false)) return

        val widgetClass = "com.bilibili.video.story.action.widget.StoryShopCartWidget"
            .findClassOrNull(mClassLoader) ?: run {
            Log.w("BlockStoryGoods: StoryShopCartWidget not found")
            return
        }

        widgetClass.hookMethod("c") {
            false
        }
        widgetClass.hookMethod("setVisibility", Int::class.javaPrimitiveType) { chain ->
            chain.args[0] = View.GONE
            chain.proceed()
        }
        Log.d("startHook: BlockStoryGoods")
    }

    companion object {
        private const val PREF_KEY = "block_story_goods"
    }
}
