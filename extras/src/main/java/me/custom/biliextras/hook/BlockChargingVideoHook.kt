package me.custom.biliextras.hook

import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * 屏蔽充电专属（充电）视频，覆盖：
 *  1. 首页推荐（Pegasus JSON feed）
 *  2. 竖屏视频流（StoryPagerPlayer 的 StoryDetail 列表）
 *  3. 普通视频下方相关视频（view.v1 / viewunite.v1 的 relates）
 *  4. 普通视频自动连播的 AI 推荐（relatesFeed）
 *
 * 判定优先使用各处“视频自身”的结构化标记：
 *  - 主视频（被打开的视频）：Arc.right.is_charging_pay（实测可靠，仅用于调试确认）
 *  - 竖屏流：StoryDetail.getUpowerInfo() != null（充电/充电预览专属）
 *  - view.v1 相关视频：Relate.hasPowerIconStyle() 或角标文字含关键词
 *  - viewunite.v1 相关视频：RelateAVCard/CardBasicInfo 不含结构化充电字段（已对照 proto
 *    及运行日志确认），改用 report_flow_data 的 flow_source=chg_plt_up 渠道标记 + 标题
 *    “充电专属”关键词组合判定。
 *  - 首页（Pegasus JSON）：BasicIndexItem 系列无结构化充电字段（已对照 jadx 确认），
 *    只能用角标文字含“充电”或角标 uri/event 指向 upower H5 判定。
 *
 * 另外提供独立开关 block_promoted_video：屏蔽相关视频/连播里购买的运营推广位
 * （CardBasicInfo.from == "operation"，实测普通卡片该字段为空）。两个开关互不依赖，
 * 任一开启都会挂钩 view.v1 / viewunite.v1 的相关视频列表。
 */
class BlockChargingVideoHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val logEnabled by lazy { ePrefs.getBoolean("block_charging_video_log", false) }
    private val blockCharging by lazy { ePrefs.getBoolean("block_charging_video", false) }
    private val blockPromoted by lazy { ePrefs.getBoolean("block_promoted_video", false) }

    private val jsonFieldCache = ConcurrentHashMap<Class<*>, MutableMap<String, Field?>>()

    override fun startHook() {
        if (!blockCharging && !blockPromoted) return
        Log.d("startHook: BlockChargingVideo charging=$blockCharging promoted=$blockPromoted")

        // 相关视频/连播两处都要过滤充电或运营推广位，只要任一开关开启就挂钩。
        hookViewV1()
        hookViewUnite()
        // 以下三处仅与充电屏蔽相关。
        if (blockCharging) {
            hookStoryFeed()
            hookHomeFeed()
        }
    }

    // region 竖屏视频流
    private fun hookStoryFeed() {
        val storyClass = instance.storyPagerPlayerClass ?: run {
            Log.w("BlockChargingVideo: StoryPagerPlayer not found")
            return
        }
        val listMethods = storyClass.declaredMethods.filter { method ->
            method.parameterTypes.isNotEmpty() && method.parameterTypes[0] == List::class.java
        }
        if (listMethods.isEmpty()) {
            Log.w("BlockChargingVideo: no List-param method on StoryPagerPlayer")
            return
        }
        Log.d("BlockChargingVideo story methods: ${listMethods.joinToString { it.name }}")
        storyClass.hookAllMethods(listMethods) { chain ->
            @Suppress("UNCHECKED_CAST")
            val primary = chain.args.getOrNull(0) as? MutableList<Any?>
            if (primary != null) {
                @Suppress("UNCHECKED_CAST")
                val second = chain.args.getOrNull(1) as? MutableList<Any?>
                val parallel = if (second != null && second.size == primary.size) second else null
                removeChargingStory(primary, parallel)
            }
            chain.proceed()
        }
    }

    private fun removeChargingStory(primary: MutableList<Any?>, parallel: MutableList<Any?>?) {
        var i = 0
        var removed = 0
        while (i < primary.size) {
            val item = primary[i]
            if (item != null && isChargingStory(item)) {
                primary.removeAt(i)
                parallel?.let { if (i < it.size) it.removeAt(i) }
                removed++
            } else {
                i++
            }
        }
        if (removed > 0) Log.d("BlockChargingVideo: removed $removed story item(s)")
    }

    private fun isChargingStory(item: Any): Boolean {
        val upowerInfo = item.callMethodOrNull("getUpowerInfo")
        if (logEnabled && upowerInfo != null) {
            Log.d("ChargingStory upowerInfo present on ${item.javaClass.simpleName}")
        }
        return upowerInfo != null
    }
    // endregion

    // region view.v1 相关视频 / 连播
    private fun hookViewV1() {
        val moss = instance.viewMossClass ?: return
        val viewReq = instance.viewReqClass
        if (viewReq != null) {
            moss.hookMethod(
                if (instance.useNewMossFunc) "executeView" else "view",
                viewReq,
            ) { chain ->
                val result = chain.proceed()
                result?.runCatchingOrNull {
                    if (logEnabled) logMainVideo("view.v1", this)
                    callMethodOrNull("ensureRelatesIsMutable")
                    @Suppress("UNCHECKED_CAST")
                    (callMethodOrNull("getRelatesList") as? MutableList<Any>)
                        ?.removeAll { shouldRemoveRelateV1(it) }
                }
                result
            }
        }
        moss.hookMethod(
            if (instance.useNewMossFunc) "executeRelatesFeed" else "relatesFeed",
            "com.bapis.bilibili.app.view.v1.RelatesFeedReq",
        ) { chain ->
            val result = chain.proceed()
            result?.runCatchingOrNull {
                callMethodOrNull("ensureListIsMutable")
                @Suppress("UNCHECKED_CAST")
                (callMethodOrNull("getListList") as? MutableList<Any>)
                    ?.removeAll { shouldRemoveRelateV1(it) }
            }
            result
        }
        Log.d("BlockChargingVideo: hooked view.v1")
    }

    private fun isChargingRelateV1(item: Any): Boolean {
        val hasPowerIcon = item.callMethodOrNull("hasPowerIconStyle") == true
        val badge = item.callMethodOrNullAs<String>("getBadge").orEmpty()
        val badgeStyleText = item.callMethodOrNull("getBadgeStyle")
            ?.callMethodOrNullAs<String>("getText").orEmpty()
        if (logEnabled) {
            Log.d(
                "ChargingRelateV1 goto=${item.callMethodOrNull("getGoto")} " +
                    "title=${item.callMethodOrNull("getTitle")} " +
                    "powerIcon=$hasPowerIcon badge=$badge badgeStyle=$badgeStyleText",
            )
            Log.d("ChargingRelateV1 dump >>> ${item.protoSummary()}")
        }
        return hasPowerIcon ||
            badge.containsCharge() ||
            badgeStyleText.containsCharge()
    }

    private fun shouldRemoveRelateV1(item: Any): Boolean =
        (blockCharging && isChargingRelateV1(item)) ||
            (blockPromoted && isPromotedRelateV1(item))

    /** view.v1 Relate 的运营推广位：basic_info/from 取不到时回退到 Relate.getFrom()。 */
    private fun isPromotedRelateV1(item: Any): Boolean {
        val from = item.callMethodOrNullAs<String>("getFrom").orEmpty()
        val promoted = from == PROMOTED_FROM
        if (logEnabled && promoted) Log.d("PromotedRelateV1 removed from=$from")
        return promoted
    }
    // endregion

    // region viewunite.v1 相关视频 / 连播
    private fun hookViewUnite() {
        val moss = instance.viewUniteMossClass ?: return
        val viewReq = instance.viewUniteReqClass
        if (viewReq != null) {
            moss.hookMethod(
                if (instance.useNewMossFunc) "executeView" else "view",
                viewReq,
            ) { chain ->
                val result = chain.proceed()
                result?.runCatchingOrNull {
                    if (logEnabled) logMainVideo("viewunite.v1", this)
                    filterUniteViewRelates(this)
                }
                result
            }
        }
        moss.hookMethod(
            if (instance.useNewMossFunc) "executeRelatesFeed" else "relatesFeed",
            "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReq",
        ) { chain ->
            val result = chain.proceed()
            result?.runCatchingOrNull {
                callMethodOrNull("ensureRelatesIsMutable")
                @Suppress("UNCHECKED_CAST")
                (callMethodOrNull("getRelatesList") as? MutableList<Any>)
                    ?.removeAll { shouldRemoveRelateUnite(it) }
            }
            result
        }
        Log.d("BlockChargingVideo: hooked viewunite.v1")
    }

    private fun filterUniteViewRelates(viewReply: Any) {
        val tab = viewReply.callMethodOrNull("getTab") ?: return
        tab.callMethodOrNull("ensureTabModuleIsMutable")
        @Suppress("UNCHECKED_CAST")
        val tabModules = tab.callMethodOrNull("getTabModuleList") as? List<Any> ?: return
        tabModules.forEach { tabModule ->
            if (tabModule.callMethodOrNull("hasIntroduction") != true) return@forEach
            val intro = tabModule.callMethodOrNull("getIntroduction") ?: return@forEach
            intro.callMethodOrNull("ensureModulesIsMutable")
            @Suppress("UNCHECKED_CAST")
            val modules = intro.callMethodOrNull("getModulesList") as? List<Any> ?: return@forEach
            modules.forEach { module ->
                if (module.callMethodOrNull("hasRelates") != true) return@forEach
                val relates = module.callMethodOrNull("getRelates") ?: return@forEach
                relates.callMethodOrNull("ensureCardsIsMutable")
                @Suppress("UNCHECKED_CAST")
                (relates.callMethodOrNull("getCardsList") as? MutableList<Any>)
                    ?.removeAll { shouldRemoveRelateUnite(it) }
            }
        }
    }

    private fun isChargingRelateUnite(card: Any): Boolean {
        val basicInfo = if (card.callMethodOrNull("hasBasicInfo") == true) {
            card.callMethodOrNull("getBasicInfo")
        } else null
        val title = basicInfo?.callMethodOrNullAs<String>("getTitle").orEmpty()
        val reportFlowData = basicInfo?.callMethodOrNullAs<String>("getReportFlowData").orEmpty()
        // viewunite 的 RelateAVCard / CardBasicInfo 没有 is_charging_pay 等结构化充电字段
        // （已对照 proto 确认）。运行日志显示充电专属视频要么标题含“充电专属”，要么走
        // report_flow_data 里 flow_source=chg_plt_up（充电 UP 推广位）这个渠道。两者结合
        // 命中率最高且实测无误杀（普通卡片 flow_source 为 recent_off/swing/merge_* 等）。
        val viaChargingChannel = reportFlowData.contains(CHARGE_FLOW_SOURCE)
        val viaTitle = title.contains(CHARGE_EXCLUSIVE_KEYWORD)
        if (logEnabled) {
            Log.d(
                "ChargingRelateUnite cardCase=${card.callMethodOrNull("getCardCase")} " +
                    "title=$title chgChannel=$viaChargingChannel flowData=$reportFlowData",
            )
        }
        return viaChargingChannel || viaTitle
    }

    private fun shouldRemoveRelateUnite(card: Any): Boolean =
        (blockCharging && isChargingRelateUnite(card)) ||
            (blockPromoted && isPromotedRelateUnite(card))

    /**
     * 运营推广位（购买的“热搜/定向”推送），实测整张相关视频列表里此类卡片的
     * basic_info.from == "operation"（普通卡片该字段为空，from_source_type 也为 0）。
     */
    private fun isPromotedRelateUnite(card: Any): Boolean {
        val from = if (card.callMethodOrNull("hasBasicInfo") == true) {
            card.callMethodOrNull("getBasicInfo")?.callMethodOrNullAs<String>("getFrom").orEmpty()
        } else ""
        val promoted = from == PROMOTED_FROM
        if (logEnabled && promoted) {
            Log.d(
                "PromotedRelateUnite removed cardCase=${card.callMethodOrNull("getCardCase")} from=$from",
            )
        }
        return promoted
    }
    // endregion

    // region 首页推荐
    private fun hookHomeFeed() {
        val convertClass = instance.pegasusConvertClass ?: run {
            Log.w("BlockChargingVideo: pegasus convert class not found")
            return
        }
        convertClass.hookMethod("convert", Any::class.java) { chain ->
            val result = chain.proceed() ?: return@hookMethod null
            runCatchingOrNull {
                val data = result.getObjectFieldOrNull("data") ?: return@runCatchingOrNull
                @Suppress("UNCHECKED_CAST")
                val items = data.getJsonField("items") as? MutableList<Any> ?: return@runCatchingOrNull
                val removed = items.size
                items.removeAll { isChargingFeedItem(it) }
                val diff = removed - items.size
                if (diff > 0) Log.d("BlockChargingVideo: removed $diff feed item(s)")
            }
            result
        }
        Log.d("BlockChargingVideo: hooked home feed on ${convertClass.name}")
    }

    private fun isChargingFeedItem(item: Any): Boolean {
        // Pegasus 首页卡片（BasicIndexItem 及其子类）没有 upower_info / ugc_pay 这类结构化
        // 充电字段（已对照 jadx 源码确认），充电只能体现在角标上：text 含“充电”或角标
        // uri/event 指向 upower H5（…/h5/upower/index…）。后者更接近结构化标记。
        var hit = false
        val seen = ArrayList<String>(FEED_BADGE_FIELDS.size)
        for (name in FEED_BADGE_FIELDS) {
            val badge = item.getJsonField(name) ?: continue
            val text: String
            val link: String
            if (badge is String) {
                text = badge
                link = ""
            } else {
                text = badge.getJsonField("text") as? String ?: ""
                link = (badge.getJsonField("uri") as? String).orEmpty() +
                    (badge.getJsonField("event") as? String).orEmpty()
            }
            if (logEnabled) seen += "$name=$text"
            if (text.containsCharge() || link.contains(UPOWER_KEYWORD)) hit = true
        }
        if (logEnabled) {
            Log.d(
                "ChargingFeed goto=${item.getJsonField("goto")} " +
                    "cardType=${item.getJsonField("card_type")} " +
                    "title=${item.getJsonField("title")} hit=$hit badges=$seen",
            )
            if (hit) {
                val json = instance.fastJsonClass?.callStaticMethodOrNull("toJSONString", item) as? String
                if (json != null) Log.d("ChargingFeed dump >>> ${json.truncate()}")
            }
        }
        return hit
    }
    // endregion

    // region 工具
    private fun String?.containsCharge(): Boolean = !this.isNullOrEmpty() && contains(CHARGE_KEYWORD)

    private fun String.truncate(max: Int = 3000): String =
        if (length > max) substring(0, max) + "...(truncated ${length - max})" else this

    /** protobuf GeneratedMessageLite.toString() 会按字段名打印所有已赋值字段，方便定位充电标记。 */
    private fun Any.protoSummary(): String =
        runCatchingOrNull { toString().truncate() } ?: "<dump failed>"

    /** 打印当前打开视频自身的充电标记，确认这确实是充电视频，并暴露响应结构。 */
    private fun logMainVideo(tag: String, viewReply: Any) {
        val arc = viewReply.callMethodOrNull("getArc")
        val aid = arc?.callMethodOrNull("getAid")
        val title = arc?.callMethodOrNull("getTitle")
        val isChargingPay = arc?.callMethodOrNull("getRight")?.callMethodOrNull("getIsChargingPay")
            ?: arc?.callMethodOrNull("getRights")?.callMethodOrNull("getIsChargingPay")
        val reqUser = viewReply.callMethodOrNull("getReqUser")
        Log.d(
            "ChargingMain($tag) aid=$aid title=$title isChargingPay=$isChargingPay",
        )
        if (arc != null) Log.d("ChargingMain($tag) arc >>> ${arc.protoSummary()}")
        if (reqUser != null) Log.d("ChargingMain($tag) reqUser >>> ${reqUser.protoSummary()}")
    }

    /** 优先按 fastjson @JSONField(name) 取字段，找不到再退回到 Java 字段名。 */
    private fun Any.getJsonField(name: String): Any? {
        val cache = jsonFieldCache.getOrPut(javaClass) { ConcurrentHashMap() }
        if (cache.containsKey(name)) {
            return cache[name]?.runCatchingOrNull { get(this@getJsonField) }
        }
        val annotation = instance.fastjsonFieldAnnotation
        var clazz: Class<*>? = javaClass
        var found: Field? = null
        while (clazz != null && clazz != Any::class.java && found == null) {
            for (field in clazz.declaredFields) {
                val matches = if (annotation != null) {
                    @Suppress("UNCHECKED_CAST")
                    field.getAnnotation(annotation as Class<out Annotation>)
                        ?.callMethodOrNull("name") == name
                } else false
                if (matches) {
                    field.isAccessible = true
                    found = field
                    break
                }
            }
            clazz = clazz.superclass
        }
        if (found == null) {
            found = javaClass.findFieldOrNull(name)
        }
        cache[name] = found
        return found?.runCatchingOrNull { get(this@getJsonField) }
    }
    // endregion

    companion object {
        private const val CHARGE_KEYWORD = "充电"
        private const val CHARGE_EXCLUSIVE_KEYWORD = "充电专属"
        private const val CHARGE_FLOW_SOURCE = "chg_plt_up"
        private const val UPOWER_KEYWORD = "upower"
        private const val PROMOTED_FROM = "operation"
        private val FEED_BADGE_FIELDS = listOf(
            "cover_badge_style",
            "cover_badge_style_2",
            "cover_info_badge",
            "cover_badge",
            "badge_style",
            "left_cover_badge_style",
            "right_cover_badge_style",
        )
    }
}
