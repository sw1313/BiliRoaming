package me.custom.biliextras.hook

import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import me.custom.biliextras.utils.*
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class BlockStoryGoodsHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        private val widgetControllerFieldCache = ConcurrentHashMap<Class<*>, Field>()
        /** Widget classes already scanned with no resolvable controller field. */
        private val widgetControllerFieldMiss = ConcurrentHashMap.newKeySet<Class<*>>()
        private val controllerCartMethodsCache = ConcurrentHashMap<Class<*>, ControllerCartMethods>()
        /** Controller classes whose cart accessor chain could not be resolved. */
        private val controllerCartMethodsMiss = ConcurrentHashMap.newKeySet<Class<*>>()
    }

    private data class ControllerCartMethods(
        val getData: Method,
    )
    override fun startHook() {
        val blockedGotos = StoryDiversionPrefs.blockedGotos()
        val shopCart = StoryDiversionPrefs.shopCartBlocked()
        val adOverlay = StoryDiversionPrefs.adOverlayBlocked()
        val ogvCategories = StoryDiversionPrefs.blockedOgvCategories()
        val textLabels = StoryDiversionPrefs.blockedTextLabels()
        val ogvVipBar = StoryDiversionPrefs.ogvVipBarBlocked()
        val liveReservation = StoryDiversionPrefs.liveReservationBlocked()
        val freeData = StoryDiversionPrefs.freeDataBlocked()
        if (blockedGotos.isEmpty() && !shopCart && !adOverlay && textLabels.isEmpty() &&
            !ogvVipBar && !liveReservation && !freeData
        ) {
            return
        }

        val ogvKeywords = ogvCategories.flatten().toSet()
        if (shopCart) blockShopCartWidget()
        if (adOverlay) blockStoryAdWidget()
        if (ogvVipBar) blockStoryOgvVipBar()
        if (liveReservation) blockStoryLiveReservation()
        if (freeData) blockStoryFreeDataPrompt()
        if (ogvKeywords.isNotEmpty()) blockOgvCollection(ogvKeywords)
        if (blockedGotos.isNotEmpty() || textLabels.isNotEmpty()) {
            blockCartIconInfo(blockedGotos, textLabels)
            blockStoryDiversionEntry(blockedGotos, textLabels)
        }
        Log.s(
            "startHook: BlockStoryGoods (shopCart=$shopCart, adOverlay=$adOverlay, " +
                "ogvVipBar=$ogvVipBar, liveReservation=$liveReservation, freeData=$freeData, " +
                "ogvCats=$ogvCategories, textLabels=$textLabels, diversion=$blockedGotos)",
        )
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
            Log.trace { "BlockStoryGoods: StoryShopCartWidget not found" }
            return
        }

        val oHandle = widgetClass.hookMethod(
            "O",
            "com.bilibili.video.story.action.StoryActionType",
            "com.bilibili.video.story.action.j",
        ) { null }
        Log.s("BlockStoryGoods: hooked StoryShopCartWidget.O -> ${oHandle != null}")

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
     * "ad"/"anchor_ad" (广告), "consult"/"clue"/"form" (咨询 / 课程推广). For "cart" z0() hides this chip and spawns the floating
     * "购物 / 视频同款 / 立即购买" card through the ad route service (d.e(...)).
     *
     * The "话题 / 音乐" tags are a SEPARATE widget and are never touched here.
     *
     * Two block sources feed this single chip:
     *  - [blockedGotos]: per-type chip toggles (cart/game/vip/ad). For those we force the chip GONE
     *    and skip O() so z0() never runs and the floating card is never spawned.
     *  - [textLabels]: the 番剧/电影「影视溯源胶片卡」and the 充电 entry. Those cards are ALSO diversion
     *    entries, but their entryGoto is NOT stable (observed "charge", may also be "ogv" etc.) and
     *    overlaps across types, so we do NOT key off goto. Instead the chip renders
     *    entryText="<类型/充电>" (e.g. "国创"/"电影"/"充电") + entryTitle="<片名>", and that leading label
     *    is exactly one of our keywords. We block whenever entryText matches an enabled label, so
     *    each toggle is independent and ordinary labels ("话题"/"音乐") never match.
     *
     * Every non-matching entry proceeds normally.
     */
    private fun blockStoryDiversionEntry(blockedGotos: Set<String>, textLabels: Set<String>) {
        val diversionClass = "com.bilibili.video.story.action.widget.StoryDiversionEntryWidget"
            .findClassOrNull(mClassLoader) ?: run {
            Log.trace { "BlockStoryGoods: StoryDiversionEntryWidget not found" }
            return
        }
        val oHandle = diversionClass.hookMethod(
            "O",
            "com.bilibili.video.story.action.StoryActionType",
            "com.bilibili.video.story.action.j",
        ) { chain ->
            val info = currentCartInfo(chain.thisObject)
            val blocked = shouldBlockDiversionEntry(info?.goto, info?.text, blockedGotos, textLabels)
            if (blocked) {
                (chain.thisObject as? View)?.visibility = View.GONE
                null
            } else {
                chain.proceed()
            }
        }
        Log.s("BlockStoryGoods: hooked StoryDiversionEntryWidget.O -> ${oHandle != null}")
    }

    private data class CartInfo(val goto: String?, val text: String?)

    private fun shouldBlockDiversionEntry(
        goto: String?,
        text: String?,
        blockedGotos: Set<String>,
        textLabels: Set<String>,
    ): Boolean {
        if (goto == null) return false
        if (textLabels.isNotEmpty() && textLabels.any { text.orEmpty().contains(it) }) return true
        return goto in blockedGotos
    }

    /**
     * Root-cause block for the bottom-left diversion chip. StoryDiversionEntryWidget.z0() reads
     * StoryDetail.getCartIconInfo() directly; nulling blocked entries hides the widget before render.
     */
    private fun blockCartIconInfo(blockedGotos: Set<String>, textLabels: Set<String>) {
        val storyDetail = "com.bilibili.video.story.StoryDetail".findClassOrNull(mClassLoader) ?: run {
            Log.trace { "BlockStoryGoods: StoryDetail not found (getCartIconInfo)" }
            return
        }
        var mGetEntryGoto: Method? = null
        var mGetEntryText: Method? = null
        val handle = storyDetail.hookMethod("getCartIconInfo") { chain ->
            val info = chain.proceed() ?: return@hookMethod null
            if (mGetEntryGoto == null) {
                val c = info.javaClass
                mGetEntryGoto = runCatching { c.getMethod("getEntryGoto").also { it.isAccessible = true } }.getOrNull()
                mGetEntryText = runCatching { c.getMethod("getEntryText").also { it.isAccessible = true } }.getOrNull()
            }
            val goto = runCatching { mGetEntryGoto?.invoke(info) as? String }.getOrNull()
            val text = runCatching { mGetEntryText?.invoke(info) as? String }.getOrNull()
            if (shouldBlockDiversionEntry(goto, text, blockedGotos, textLabels)) null else info
        }
        Log.s("BlockStoryGoods: hooked StoryDetail.getCartIconInfo -> ${handle != null}")
    }

    /**
     * Reads the diversion widget's bound controller -> StoryDetail -> cartIconInfo, returning its
     * entryGoto/entryText.
     *
     * The controller field's declared type is the obfuscated interface com.bilibili.video.story
     * .action.h, but its single-letter obfuscated name can differ between Bilibili builds, so we
     * do NOT match by type name. Instead we duck-type: scan every declared field and try the
     * getData()/getCartIconInfo()/getEntryGoto() accessor chain (getCartIconInfo lives on StoryDetail,
     * not the controller). The concrete controller impl is a non-public class, so each method needs setAccessible(true)
     * before invoke() or reflection throws IllegalAccessException. Returns null when nothing
     * resolves (never blocking an unknown entry).
     */
    private fun currentCartInfo(widget: Any): CartInfo? {
        val field = controllerField(widget) ?: return null
        return readCartInfoFromField(widget, field)
    }

    private fun controllerField(widget: Any): Field? {
        val widgetClass = widget.javaClass
        if (widgetClass in widgetControllerFieldMiss) return null
        widgetControllerFieldCache[widgetClass]?.let { return it }
        for (field in widgetClass.declaredFields) {
            if (readCartInfoFromField(widget, field) != null) {
                widgetControllerFieldCache[widgetClass] = field
                return field
            }
        }
        widgetControllerFieldMiss.add(widgetClass)
        return null
    }

    private fun readCartInfoFromField(widget: Any, field: Field): CartInfo? = runCatching {
        field.isAccessible = true
        val controller = field.get(widget) ?: return@runCatching null
        val getData = cartMethodsFor(controller)?.getData ?: return@runCatching null
        val data = getData.invoke(controller) ?: return@runCatching null
        val detailClass = data.javaClass
        val cartInfo = detailClass.getMethod("getCartIconInfo")
            .also { it.isAccessible = true }
            .invoke(data) ?: return@runCatching null
        val cartClass = cartInfo.javaClass
        val goto = cartClass.getMethod("getEntryGoto")
            .also { it.isAccessible = true }
            .invoke(cartInfo) as? String ?: return@runCatching null
        val text = runCatching {
            cartClass.getMethod("getEntryText")
                .also { it.isAccessible = true }
                .invoke(cartInfo) as? String
        }.getOrNull()
        CartInfo(goto, text)
    }.getOrNull()

    private fun cartMethodsFor(controller: Any): ControllerCartMethods? {
        val clazz = controller.javaClass
        if (clazz in controllerCartMethodsMiss) return null
        controllerCartMethodsCache[clazz]?.let { return it }
        val methods = runCatching {
            ControllerCartMethods(
                getData = clazz.getMethod("getData").also { it.isAccessible = true },
            )
        }.getOrNull()
        if (methods != null) {
            controllerCartMethodsCache[clazz] = methods
        } else {
            controllerCartMethodsMiss.add(clazz)
        }
        return methods
    }

    /**
     * 番剧/电影「影视溯源胶片卡」root-cause block. The visible "<类型> | <片名>" chip (e.g.
     * "电影 | 大创业家") is driven by StoryDetail.getCollection() with cmd=="ogv-season": the leading
     * 类型 label is collection.title ("电影"/"番剧"/...), the name is collection.seasonTitle. The same
     * collection feeds BOTH the OGV season bar (StoryOgvWidget, gated by u.o()) and the inline title
     * chip (StoryTitleWidget.M2). getCollection() is a real (non-inlined) call site in both.
     *
     * We hook getCollection() and return null only when it is an "ogv-season" collection whose
     * title matches one of the enabled category keyword sets ([categoryKeywords]). That kills both
     * renderings for the chosen categories (番剧 vs 电影 independently) and leaves every other
     * collection (UGC 合集 / 分P, and ogv categories not toggled) untouched.
     */
    private fun blockOgvCollection(categoryKeywords: Set<String>) {
        val storyDetail = "com.bilibili.video.story.StoryDetail".findClassOrNull(mClassLoader) ?: run {
            Log.trace { "BlockStoryGoods: StoryDetail not found" }
            return
        }
        // Resolve the accessors from the actual Collection instance's class at runtime: the inner
        // class name / classloader can differ, and a pre-looked-up class returning null silently
        // disabled the whole filter before. Lazily cache once we see the first non-null collection.
        var mGetCmd: java.lang.reflect.Method? = null
        var mGetTitle: java.lang.reflect.Method? = null
        val handle = storyDetail.hookMethod("getCollection") { chain ->
            val col = chain.proceed()
            if (col == null) {
                col
            } else {
                if (mGetCmd == null) {
                    val c = col.javaClass
                    mGetCmd = runCatching { c.getMethod("getCmd").also { it.isAccessible = true } }.getOrNull()
                    mGetTitle = runCatching { c.getMethod("getTitle").also { it.isAccessible = true } }.getOrNull()
                }
                val cmd = runCatching { mGetCmd?.invoke(col) as? String }.getOrNull()
                val title = runCatching { mGetTitle?.invoke(col) as? String }.getOrNull()
                val blocked = cmd == "ogv-season" && categoryKeywords.any { title.orEmpty().contains(it) }
                if (blocked) null else col
            }
        }
        Log.s("BlockStoryGoods: hooked StoryDetail.getCollection -> ${handle != null} (keywords=$categoryKeywords)")
    }

    /**
     * StoryOgvVipBarWidget renders the OGV "大会员 | 月均仅9.8元 / 抢大会员年卡" bar above the
     * season title. Visibility is driven by O(ALL) -> z0() and onStart animations; blocking O()
     * and onStart keeps the bar GONE.
     */
    private fun blockStoryOgvVipBar() {
        val widgetClass = "com.bilibili.video.story.action.widget.StoryOgvVipBarWidget"
            .findClassOrNull(mClassLoader) ?: run {
            Log.trace { "BlockStoryGoods: StoryOgvVipBarWidget not found" }
            return
        }
        widgetClass.hookMethod(
            "O",
            "com.bilibili.video.story.action.StoryActionType",
            "com.bilibili.video.story.action.j",
        ) { chain ->
            (chain.thisObject as? View)?.visibility = View.GONE
            null
        }
        widgetClass.hookMethod("onStart", Int::class.javaPrimitiveType!!) { null }
        widgetClass.hookMethod("j2", "com.bilibili.video.story.action.h") { chain ->
            bindStoryController(chain)
            null
        }
        Log.s("BlockStoryGoods: hooked StoryOgvVipBarWidget")
    }

    /**
     * StoryLiveReservationWidget shows the "06-21 21:00 开始直播 | 预约" strip. O(ALL) calls s()
     * and onRender() also triggers a show pass — block both and keep GONE.
     */
    private fun blockStoryLiveReservation() {
        val widgetClass = "com.bilibili.video.story.action.widget.StoryLiveReservationWidget"
            .findClassOrNull(mClassLoader) ?: run {
            Log.trace { "BlockStoryGoods: StoryLiveReservationWidget not found" }
            return
        }
        widgetClass.hookMethod(
            "O",
            "com.bilibili.video.story.action.StoryActionType",
            "com.bilibili.video.story.action.j",
        ) { chain ->
            (chain.thisObject as? View)?.visibility = View.GONE
            null
        }
        widgetClass.hookMethod("onRender") { chain ->
            (chain.thisObject as? View)?.visibility = View.GONE
            null
        }
        widgetClass.hookMethod("j2", "com.bilibili.video.story.action.h") { chain ->
            bindStoryController(chain)
            null
        }
        Log.s("BlockStoryGoods: hooked StoryLiveReservationWidget")
    }

    /**
     * StoryFreeDataPromptComponent attaches the mobile-data "流量卡 / 免流" toast when not on WiFi.
     * Hook m(controller, container) so the Compose toast is never added.
     */
    private fun blockStoryFreeDataPrompt() {
        val componentClass = "com.bilibili.video.story.action.widget.StoryFreeDataPromptComponent"
            .findClassOrNull(mClassLoader) ?: run {
            Log.trace { "BlockStoryGoods: StoryFreeDataPromptComponent not found" }
            return
        }
        componentClass.hookMethod(
            "m",
            "com.bilibili.video.story.action.h",
            ViewGroup::class.java,
        ) { null }
        Log.s("BlockStoryGoods: hooked StoryFreeDataPromptComponent.m")
    }

    private fun bindStoryController(chain: XposedInterface.Chain) {
        runCatching {
            val widget = chain.thisObject
            val controller = chain.args.firstOrNull() ?: return
            val field = widget.javaClass.declaredFields.firstOrNull {
                it.type.name == "com.bilibili.video.story.action.h"
            }
            field?.isAccessible = true
            field?.set(widget, controller)
            (widget as? View)?.visibility = View.GONE
        }.onFailure { Log.e(it) }
    }

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
            Log.trace { "BlockStoryGoods: StoryAdWidget not found" }
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
