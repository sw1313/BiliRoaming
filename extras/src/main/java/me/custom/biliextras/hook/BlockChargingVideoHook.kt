package me.custom.biliextras.hook

import android.content.Context
import android.content.SharedPreferences
import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.*
import org.json.JSONObject
import java.lang.reflect.Field
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 *  - viewunite.v1 相关视频：RelateAVCard/CardBasicInfo 不含任何结构化充电字段（已对照 proto
 *    及完整运行日志逐字段确认——充电专属 AV 卡片与普通 AV 卡片完全一致：flow_source=swing、
 *    无角标、无 upower、from_source_type=0）。因此结构上无法在相关列表里直接识别充电视频。
 *    退而求其次，采用“已知充电 aid 缓存”：任何视频被打开成主视频时若 Arc.right.is_charging_pay
 *    为真，就记下它的 aid（recordMainVideoCharging）；之后该视频再出现在任意相关列表 / 连播 /
 *    AI 推荐里，便按 aid 命中删除。叠加 report_flow_data 的 flow_source=chg_plt_up 渠道标记 +
 *    标题“充电专属”关键词作为首见兜底。
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
    private val blockChargingNet by lazy { ePrefs.getBoolean("block_charging_video_net", false) }
    private val blockPromoted by lazy { ePrefs.getBoolean("block_promoted_video", false) }

    private val jsonFieldCache = ConcurrentHashMap<Class<*>, MutableMap<String, Field?>>()

    private val ugcAnyModelClass by lazy {
        "com.bapis.bilibili.app.playerunite.ugcanymodel.UGCAnyModel".from(mClassLoader)
    }

    override fun startHook() {
        if (!blockCharging && !blockPromoted) return
        Log.s("startHook: BlockChargingVideo charging=$blockCharging promoted=$blockPromoted")

        // 相关视频/连播两处都要过滤充电或运营推广位，只要任一开关开启就挂钩。
        hookViewV1()
        hookViewUnite()
        // 以下三处仅与充电屏蔽相关。
        if (blockCharging) {
            hookStoryFeed()
            hookHomeFeed()
            hookPlayViewCharging()
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
        val charging = upowerInfo != null
        if (charging) {
            // 竖屏流能结构化识别充电，顺手把 aid 记进缓存，供相关列表/推荐按 aid 拦截。
            val aid = (item.callMethodOrNull("getAid") as? Long) ?: 0L
            if (rememberChargingAid(aid)) {
                Log.d("BlockChargingVideo: cached charging aid=$aid (story upowerInfo)")
            } else if (logEnabled) {
                Log.d("ChargingStory upowerInfo present on ${item.javaClass.simpleName} aid=$aid")
            }
        }
        return charging
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
                    recordMainVideoCharging("view.v1", this)
                    if (logEnabled) logMainVideo("view.v1", this)
                    callMethodOrNull("ensureRelatesIsMutable")
                    @Suppress("UNCHECKED_CAST")
                    (callMethodOrNull("getRelatesList") as? MutableList<Any>)?.let { list ->
                        prefetchV1Charging(list)
                        list.removeAll { shouldRemoveRelateV1(it) }
                    }
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
                (callMethodOrNull("getListList") as? MutableList<Any>)?.let { list ->
                    prefetchV1Charging(list)
                    list.removeAll { shouldRemoveRelateV1(it) }
                }
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
        val aid = (item.callMethodOrNull("getAid") as? Long) ?: 0L
        val viaKnownAid = aid > 0L && knownChargingAids.contains(aid)
        if (logEnabled) {
            Log.d(
                "ChargingRelateV1 goto=${item.callMethodOrNull("getGoto")} aid=$aid " +
                    "title=${item.callMethodOrNull("getTitle")} " +
                    "powerIcon=$hasPowerIcon badge=$badge badgeStyle=$badgeStyleText knownAid=$viaKnownAid",
            )
        }
        return hasPowerIcon ||
            badge.containsCharge() ||
            badgeStyleText.containsCharge() ||
            viaKnownAid
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
                    recordMainVideoCharging("viewunite.v1", this)
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
                (callMethodOrNull("getRelatesList") as? MutableList<Any>)?.let { list ->
                    prefetchUniteCharging(list)
                    list.removeAll { shouldRemoveRelateUnite(it) }
                }
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
                (relates.callMethodOrNull("getCardsList") as? MutableList<Any>)?.let { list ->
                    prefetchUniteCharging(list)
                    list.removeAll { shouldRemoveRelateUnite(it) }
                }
            }
        }
    }

    private fun isChargingRelateUnite(card: Any): Boolean {
        val basicInfo = if (card.callMethodOrNull("hasBasicInfo") == true) {
            card.callMethodOrNull("getBasicInfo")
        } else null
        val title = basicInfo?.callMethodOrNullAs<String>("getTitle").orEmpty()
        val reportFlowData = basicInfo?.callMethodOrNullAs<String>("getReportFlowData").orEmpty()
        // viewunite 的 RelateAVCard / CardBasicInfo 没有任何结构化充电字段（已对照 proto 与
        // 完整字段日志确认）。首见兜底：标题含“充电专属”，或 report_flow_data 里
        // flow_source=chg_plt_up（充电 UP 推广位渠道）。普通卡片 flow_source 为
        // recent_off/swing/merge_* 等，不会误杀。
        val viaChargingChannel = reportFlowData.contains(CHARGE_FLOW_SOURCE)
        val viaTitle = title.contains(CHARGE_EXCLUSIVE_KEYWORD)
        // 主力判据：该 aid 之前被打开过且确认是充电视频（见 recordMainVideoCharging）。
        // AV 卡片的 basic_info.id 即 aid。
        val aid = (basicInfo?.callMethodOrNull("getId") as? Long) ?: 0L
        val viaKnownAid = aid > 0L && knownChargingAids.contains(aid)
        if (logEnabled) {
            val coverRightText = basicInfo?.callMethodOrNullAs<String>("getCoverRightText").orEmpty()
            val uri = basicInfo?.callMethodOrNullAs<String>("getUri").orEmpty()
            Log.d(
                "ChargingRelateUnite cardCase=${card.callMethodOrNull("getCardCase")} aid=$aid " +
                    "title=$title chgChannel=$viaChargingChannel knownAid=$viaKnownAid " +
                    "flowData=$reportFlowData coverRightText=$coverRightText uri=$uri",
            )
        }
        return viaChargingChannel || viaTitle || viaKnownAid
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

    // region 充电播放确认（按播放/试看结果回填缓存）
    /**
     * 相关列表里的卡片结构上无法识别充电视频，唯一可靠信号在“播放接口”的返回里：
     * 充电专属视频没充电时只会下发试看流（PlayArc.isPreview=true），且 UGCAnyModel.PlayLimit
     * 的 code 为 2/3/4（ChargingPlusNotPass/Upgrade/Reject）。注意 code=1 是普通付费视频，
     * 不属于充电、不拦。任何视频（普通页 / 竖屏 / 连播 / 试看）只要被播放一次命中充电限制，
     * 就把它的 aid 记进缓存，之后它再出现在任意相关列表/推荐里即按 aid 删除。
     */
    private fun hookPlayViewCharging() {
        val moss = instance.playerMossClass ?: run {
            Log.w("BlockChargingVideo: PlayerMoss not found, skip play-based charging confirm")
            return
        }
        val reqClass = instance.playViewUniteReqClass
        val handles = moss.hookAllMethods("executePlayViewUnite") { chain ->
            val result = chain.proceed()
            runCatchingOrNull {
                val req = chain.args.toTypedArray()
                    .firstOrNull { reqClass == null || reqClass.isInstance(it) }
                cacheIfChargingPlay(req, result)
            }
            result
        }
        if (handles.isNotEmpty()) {
            Log.d("BlockChargingVideo: hooked PlayerMoss.executePlayViewUnite x${handles.size} for charging confirm")
        }
    }

    private fun cacheIfChargingPlay(req: Any?, reply: Any?) {
        reply ?: return
        if (!replyIsChargingLocked(reply)) return
        val aid = extractReqAid(req)
        if (rememberChargingAid(aid)) {
            Log.d("BlockChargingVideo: cached charging aid=$aid (playViewUnite limit)")
        }
    }

    private fun extractReqAid(req: Any?): Long {
        req ?: return 0L
        val vod = req.callMethodOrNull("getVod")
        return (vod?.callMethodOrNullAs<Long>("getAid")?.takeIf { it > 0L })
            ?: (req.callMethodOrNullAs<Long>("getAid")?.takeIf { it > 0L })
            ?: 0L
    }

    /** supplement 是 protobuf Any，里面打包了 UGCAnyModel；解出 PlayLimit.code 判断是否充电锁。 */
    private fun replyIsChargingLocked(reply: Any): Boolean {
        val supplement = reply.callMethodOrNull("getSupplement") ?: return false
        val typeUrl = supplement.callMethodOrNullAs<String>("getTypeUrl").orEmpty()
        if (!typeUrl.contains(UGC_ANY_MODEL_NAME)) return false
        val ugcClass = ugcAnyModelClass ?: return false
        val bytes = supplement.callMethodOrNull("getValue")
            ?.callMethodOrNullAs<ByteArray>("toByteArray") ?: return false
        val model = ugcClass.callStaticMethodOrNull("parseFrom", bytes) ?: return false
        val code = model.callMethodOrNull("getPlayLimit")
            ?.callMethodOrNullAs<Int>("getCodeValue") ?: 0
        return code in CHARGING_LIMIT_CODES
    }
    // endregion

    // region 充电联网精准识别（仿 userscript：逐个查 view 接口的 is_upower_exclusive）
    /**
     * 相关列表/推荐卡片本身不带充电标记，唯一能在“首见”就判定的办法是像油猴脚本那样，
     * 对每个 AV 卡片调用 https://api.bilibili.com/x/web-interface/view?aid= 读取
     * data.is_upower_exclusive。结果写入缓存（充电=持久黑名单，非充电=会话内白名单），
     * 同一 aid 只查一次，后续秒过。仅在 blockCharging + blockChargingNet 同时开启时生效。
     */
    private fun prefetchUniteCharging(cards: List<Any>) {
        if (!blockCharging || !blockChargingNet) return
        resolveChargingAids(cards.mapNotNull(::uniteCardAid))
    }

    private fun prefetchV1Charging(cards: List<Any>) {
        if (!blockCharging || !blockChargingNet) return
        resolveChargingAids(cards.mapNotNull(::v1CardAid))
    }

    private fun uniteCardAid(card: Any): Long? {
        if (card.callMethodOrNull("getCardCase")?.toString() != "AV") return null
        if (card.callMethodOrNull("hasBasicInfo") != true) return null
        return (card.callMethodOrNull("getBasicInfo")?.callMethodOrNull("getId") as? Long)
            ?.takeIf { it > 0L }
    }

    private fun v1CardAid(card: Any): Long? {
        if (card.callMethodOrNullAs<String>("getGoto").orEmpty() != "av") return null
        return (card.callMethodOrNull("getAid") as? Long)?.takeIf { it > 0L }
    }

    /** 批量解析：过滤已知 aid，整页并发查询，命中充电写黑名单、否则写会话白名单。 */
    private fun resolveChargingAids(aids: List<Long>) {
        val todo = aids.asSequence()
            .filter { it > 0L && it !in knownChargingAids && it !in knownNonChargingAids }
            .distinct()
            .toList()
        if (todo.isEmpty()) return
        // 整页一次性提交到常驻线程池：连接复用(keep-alive)+足够并发，单页基本一轮打完。
        val futures = todo.map { aid ->
            netPool.submit {
                when (queryIsUpowerExclusive(aid)) {
                    true -> {
                        if (rememberChargingAid(aid)) {
                            Log.d("BlockChargingVideo: cached charging aid=$aid (view api)")
                        }
                    }
                    false -> knownNonChargingAids.add(aid)
                    null -> {} // 查询失败/风控：不缓存，下次仍可重试，绝不误删
                }
            }
        }
        val deadline = System.currentTimeMillis() + NET_TOTAL_BUDGET_MS
        for (f in futures) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                f.cancel(true)
                continue
            }
            runCatchingOrNull { f.get(remaining, TimeUnit.MILLISECONDS) } ?: f.cancel(true)
        }
    }

    /** 调 view 接口读取 is_upower_exclusive；返回 null 表示请求失败或被风控，不可信。 */
    private fun queryIsUpowerExclusive(aid: Long): Boolean? {
        val body = runCatchingOrNull {
            val conn = (URL("$VIEW_API$aid").openConnection() as HttpURLConnection).apply {
                connectTimeout = NET_CONN_TIMEOUT_MS
                readTimeout = NET_READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", NET_UA)
                setRequestProperty("Referer", "https://www.bilibili.com")
                // 不调用 disconnect()，让底层 socket 进连接池，后续请求免去 TLS 握手。
                setRequestProperty("Connection", "keep-alive")
            }
            if (conn.responseCode != 200) {
                // 读干 errorStream 才能让该连接回到池里复用。
                runCatchingOrNull { conn.errorStream?.use { it.readBytes() } }
                return@runCatchingOrNull null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } ?: return null
        return runCatchingOrNull {
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) return null
            json.optJSONObject("data")?.optBoolean("is_upower_exclusive", false)
        }
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
                if (blockChargingNet) resolveChargingAids(items.mapNotNull(::feedCardAid))
                val removed = items.size
                items.removeAll { isChargingFeedItem(it) }
                val diff = removed - items.size
                if (diff > 0) Log.d("BlockChargingVideo: removed $diff feed item(s)")
            }
            result
        }
        Log.d("BlockChargingVideo: hooked home feed on ${convertClass.name}")
    }

    /** Pegasus 普通视频卡片的 aid：优先 param（av 卡片即 aid 字符串），回退到 uri 里的 video/<aid>。 */
    private fun feedCardAid(item: Any): Long? {
        if ((item.getJsonField("goto") as? String) != "av") return null
        (item.getJsonField("param")?.toString())?.toLongOrNull()?.let { if (it > 0L) return it }
        val uri = item.getJsonField("uri") as? String ?: return null
        return FEED_URI_AID_REGEX.find(uri)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun isChargingFeedItem(item: Any): Boolean {
        // Pegasus 首页卡片（BasicIndexItem 及其子类）没有 upower_info / ugc_pay 这类结构化
        // 充电字段（已对照 jadx 源码确认），充电只能体现在角标上：text 含“充电”或角标
        // uri/event 指向 upower H5（…/h5/upower/index…）。后者更接近结构化标记。
        // 联网精准识别命中的 aid 直接删（首页卡片本身也不带结构化充电标记）。
        val aid = feedCardAid(item) ?: 0L
        if (aid > 0L && aid in knownChargingAids) {
            if (logEnabled) Log.d("ChargingFeed removed by aid=$aid (view api)")
            return true
        }
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

    /**
     * 把"被打开成主视频且确认是充电专属"的视频 aid 记进缓存。相关列表 / 连播 / AI 推荐
     * 的卡片结构上无法识别充电视频，只能靠这份缓存在它们再次出现时按 aid 删除。
     */
    private fun recordMainVideoCharging(tag: String, viewReply: Any) {
        if (!blockCharging) return
        val arc = viewReply.callMethodOrNull("getArc") ?: return
        val aid = (arc.callMethodOrNull("getAid") as? Long) ?: return
        if (aid <= 0L) return
        val charging = (
            arc.callMethodOrNull("getRight")?.callMethodOrNull("getIsChargingPay")
                ?: arc.callMethodOrNull("getRights")?.callMethodOrNull("getIsChargingPay")
            ) == true
        if (charging && rememberChargingAid(aid)) {
            Log.d("BlockChargingVideo: cached charging aid=$aid ($tag)")
        }
    }

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
        private const val UGC_ANY_MODEL_NAME = "UGCAnyModel"
        private val FEED_URI_AID_REGEX = Regex("video/(\\d+)")

        /**
         * UGCAnyModel.PlayLimit.code：2=ChargingPlusNotPass、3=ChargingPlusUpgrade、
         * 4=ChargingPlusReject，均为充电专属未通过。1=PLC_UGCNOTPAYED 是普通付费视频，不拦。
         */
        private val CHARGING_LIMIT_CODES = setOf(2, 3, 4)

        private const val CACHE_PREFS_NAME = "biliextras_charging_aids"
        private const val CACHE_KEY = "aids"
        private const val CACHE_MAX = 4000

        /**
         * "已确认充电专属"的视频 aid 缓存：相关列表/推荐里的卡片结构上无法识别充电视频，
         * 只能靠这份缓存在它们再次出现时按 aid 删除。缓存在任意可识别充电的入口（竖屏流
         * upowerInfo、主视频 is_charging_pay、播放接口 PlayLimit）写入，并持久化到宿主
         * App 私有 prefs（App 进程对自己的 prefs 可读写），重启后仍生效，逐步积累成个人黑名单。
         */
        private val knownChargingAids: MutableSet<Long> by lazy { loadCachedAids() }

        /** 会话内"已确认非充电"的 aid，避免对普通视频反复联网查询（重启后清空，允许复查）。 */
        private val knownNonChargingAids: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        private const val VIEW_API = "https://api.bilibili.com/x/web-interface/view?aid="
        private const val NET_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val NET_MAX_PARALLEL = 16
        private const val NET_TOTAL_BUDGET_MS = 2500L
        private const val NET_CONN_TIMEOUT_MS = 3000
        private const val NET_READ_TIMEOUT_MS = 3000

        /** 常驻守护线程池，整页 aid 一次性并发；线程保活，连接池复用，省去重复建池/握手开销。 */
        private val netPool: java.util.concurrent.ExecutorService by lazy {
            System.setProperty("http.maxConnections", NET_MAX_PARALLEL.toString())
            Executors.newFixedThreadPool(NET_MAX_PARALLEL) { r ->
                Thread(r, "biliextras-charging-net").apply { isDaemon = true }
            }
        }

        private fun cachePrefs(): SharedPreferences? = runCatchingOrNull {
            currentContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
        }

        private fun loadCachedAids(): MutableSet<Long> {
            val set = ConcurrentHashMap.newKeySet<Long>()
            runCatchingOrNull {
                cachePrefs()?.getStringSet(CACHE_KEY, null)?.forEach { s ->
                    s.toLongOrNull()?.let { set.add(it) }
                }
            }
            return set
        }

        /** 记一个充电 aid 并写回持久化存储；返回是否是首次记入。 */
        private fun rememberChargingAid(aid: Long): Boolean {
            if (aid <= 0L) return false
            if (!knownChargingAids.add(aid)) return false
            if (knownChargingAids.size > CACHE_MAX) {
                // 超额时丢掉任意一个，避免无限增长（充电视频很少，几乎不会触发）。
                knownChargingAids.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            }
            runCatchingOrNull {
                cachePrefs()?.edit()
                    ?.putStringSet(CACHE_KEY, knownChargingAids.map(Long::toString).toSet())
                    ?.apply()
            }
            return true
        }
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
