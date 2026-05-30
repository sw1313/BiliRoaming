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

        // c() decides whether the cart should be shown; force it false where it survives R8
        // (it is often inlined, so this alone is unreliable - see setVisibility below).
        widgetClass.hookMethod("c") {
            false
        }
        // StoryShopCartWidget.setVisibility(i): `if (i != 0 || c()) super.setVisibility(i)`.
        // The cart entry view is added into this widget (aVar.a(this, ...)), so forcing the
        // container to GONE hides it. Pass the override through proceed(args) - chain.args is
        // an immutable list, so the previous `chain.args[0] = GONE` threw and silently let the
        // original (visible) value through, leaving the cart on screen.
        widgetClass.hookMethod("setVisibility", Int::class.javaPrimitiveType) { chain ->
            chain.proceed(arrayOf<Any?>(View.GONE))
        }
        Log.d("startHook: BlockStoryGoods")
    }

    companion object {
        private const val PREF_KEY = "block_story_goods"
    }
}
