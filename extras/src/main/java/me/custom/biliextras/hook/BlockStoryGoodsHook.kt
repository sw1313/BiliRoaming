package me.custom.biliextras.hook

import android.view.View
import me.custom.biliextras.utils.*

class BlockStoryGoodsHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        val blockedGotos = StoryDiversionPrefs.blockedGotos()
        val shopCart = StoryDiversionPrefs.shopCartBlocked()
        val adOverlay = StoryDiversionPrefs.adOverlayBlocked()
        if (blockedGotos.isEmpty() && !shopCart && !adOverlay) return

        if (shopCart) blockShopCartWidget()
        if (adOverlay) blockStoryAdWidget()
        if (blockedGotos.isNotEmpty()) blockStoryDiversionEntry(blockedGotos)
        Log.x("startHook: BlockStoryGoods (shopCart=$shopCart, adOverlay=$adOverlay, diversion=$blockedGotos)")
    }

    /**
     * StoryShopCartWidget hosts the floating shopping-cart proxy. The cart view is created &
     * attached by the w12.a proxy (AdCartStoryCartProxy) inside O(StoryActionType, j): it builds a
     * w12.c from the StoryDetail's cartIconInfo and calls aVar.a(this, cVar, new c()). So O() is the
     * actual call site that creates/attaches the cart. We no-op O() (primary block), and as
     * backstops also null the w12.a field after j2() and force the container GONE.
     */
    private fun blockShopCartWidget() {
        val widgetClass = "com.bilibili.video.story.action.widget.StoryShopCartWidget"
            .findClassOrNull(mClassLoader) ?: run {
            Log.x("BlockStoryGoods: StoryShopCartWidget not found")
            return
        }

        val oHandle = widgetClass.hookMethod(
            "O",
            "com.bilibili.video.story.action.StoryActionType",
            "com.bilibili.video.story.action.j",
        ) { null }
        Log.x("BlockStoryGoods: hooked StoryShopCartWidget.O -> ${oHandle != null}")

        val proxyInterface = "w12.a".findClassOrNull(mClassLoader)
        widgetClass.hookMethod("j2", "com.bilibili.video.story.action.h") { chain ->
            val result = chain.proceed()
            runCatching {
                val widget = chain.thisObject
                val field = widget.javaClass.declaredFields.firstOrNull {
                    if (proxyInterface != null) proxyInterface.isAssignableFrom(it.type)
                    else it.type.name == "w12.a"
                }
                if (field != null) {
                    field.isAccessible = true
                    field.set(widget, null)
                }
            }.onFailure { Log.e(it) }
            result
        }

        widgetClass.hookMethod("c") { false }
        widgetClass.hookMethod("setVisibility", Int::class.javaPrimitiveType) { chain ->
            chain.proceed(arrayOf<Any?>(View.GONE))
        }
    }

    /**
     * StoryDiversionEntryWidget is the bottom-left "导流入口" chip in the vertical stream. Its
     * content/visibility is driven entirely by z0() (called only from O(StoryActionType.ALL)),
     * which reads the current StoryDetail's cartIconInfo. cartIconInfo.entryGoto decides what the
     * entry is: "cart"/"anchor_nature" (购物), "game" (游戏), "ogv" (番剧), "vip" (大会员),
     * "ad"/"anchor_ad" (广告). For "cart" z0() hides this chip and spawns the floating
     * "购物 / 视频同款 / 立即购买" card through the ad route service (d.e(...)).
     *
     * The "话题 / 音乐" tags are a SEPARATE widget and are never touched here. We only suppress
     * entries whose entryGoto is in [blockedGotos] (chosen per-type in the settings sub-menu): for
     * those we force the chip GONE and skip O() so z0() never runs and the floating card is never
     * spawned. Every other goto proceeds normally.
     */
    private fun blockStoryDiversionEntry(blockedGotos: Set<String>) {
        val diversionClass = "com.bilibili.video.story.action.widget.StoryDiversionEntryWidget"
            .findClassOrNull(mClassLoader) ?: run {
            Log.x("BlockStoryGoods: StoryDiversionEntryWidget not found")
            return
        }
        val oHandle = diversionClass.hookMethod(
            "O",
            "com.bilibili.video.story.action.StoryActionType",
            "com.bilibili.video.story.action.j",
        ) { chain ->
            val goto = currentEntryGoto(chain.thisObject)
            if (goto != null && goto in blockedGotos) {
                (chain.thisObject as? View)?.visibility = View.GONE
                null
            } else {
                chain.proceed()
            }
        }
        Log.x("BlockStoryGoods: hooked StoryDiversionEntryWidget.O -> ${oHandle != null}")
    }

    /**
     * Reads the diversion widget's bound controller -> StoryDetail -> cartIconInfo.entryGoto.
     * All accessor names (getData / getCartIconInfo / getEntryGoto) are kept un-obfuscated in the
     * app. Returns null on any failure so an unknown entry is never blocked by mistake.
     */
    private fun currentEntryGoto(widget: Any): String? = runCatching {
        val ctrlField = widget.javaClass.declaredFields.firstOrNull {
            it.type.name == "com.bilibili.video.story.action.h"
        } ?: return null
        ctrlField.isAccessible = true
        val controller = ctrlField.get(widget) ?: return null
        val data = controller.javaClass.getMethod("getData").invoke(controller) ?: return null
        val cartInfo = data.javaClass.getMethod("getCartIconInfo").invoke(data) ?: return null
        cartInfo.javaClass.getMethod("getEntryGoto").invoke(cartInfo) as? String
    }.getOrNull()

    /**
     * StoryAdWidget renders the in-video ad CTA overlay (e.g. "应用 · 点击直达…百亿补贴")
     * for ad-marked story videos. It binds that overlay in j2(host): inflates the banner via
     * q(), attaches it to the controller (host.t(view)) and creates the ad presenter
     * (com.bilibili.adcommon.biz.story.s). This does NOT go through StoryShopCartWidget, so
     * the cart block above can't catch it.
     *
     * j2()/O() are public interface overrides (com.bilibili.video.story.action.j) so R8 cannot
     * inline or rename them - they are reliable choke points. We:
     *  - j2: set the controller field (so the lifecycle methods don't NPE) and SKIP the original
     *    body, so the banner is never inflated/attached and the ad presenter is never created.
     *  - O: no-op.
     */
    private fun blockStoryAdWidget() {
        val adWidgetClass = "com.bilibili.video.story.action.widget.StoryAdWidget"
            .findClassOrNull(mClassLoader) ?: run {
            Log.x("BlockStoryGoods: StoryAdWidget not found")
            return
        }
        adWidgetClass.hookMethod("j2", "com.bilibili.video.story.action.h") { chain ->
            runCatching {
                val widget = chain.thisObject
                val hVar = chain.args.firstOrNull()
                val ctrlField = widget.javaClass.declaredFields.firstOrNull {
                    it.type.name == "com.bilibili.video.story.action.h"
                }
                ctrlField?.isAccessible = true
                ctrlField?.set(widget, hVar)
                (widget as? View)?.visibility = View.GONE
            }.onFailure { Log.e(it) }
            null
        }
        adWidgetClass.hookMethod("O", "com.bilibili.video.story.action.StoryActionType", "com.bilibili.video.story.action.j") {
            null
        }
    }
}
