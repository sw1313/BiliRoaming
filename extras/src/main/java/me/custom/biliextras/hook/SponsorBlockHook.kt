package me.custom.biliextras.hook

import android.os.Handler
import android.os.Looper
import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.sponsorblock.SponsorBlockBackground
import me.custom.biliextras.sponsorblock.SponsorBlockController
import me.custom.biliextras.sponsorblock.SponsorBlockApi
import me.custom.biliextras.sponsorblock.SponsorBlockCache
import me.custom.biliextras.sponsorblock.SponsorBlockCategory
import me.custom.biliextras.sponsorblock.SponsorBlockPrefs
import me.custom.biliextras.sponsorblock.SponsorBlockState
import me.custom.biliextras.sponsorblock.SponsorSegment
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.callMethodOrNull
import me.custom.biliextras.utils.callMethodOrNullAs
import me.custom.biliextras.utils.hookAllMethods
import me.custom.biliextras.utils.hookAllConstructors
import me.custom.biliextras.utils.hookMethod
import me.custom.biliextras.utils.mossResponseHandlerReplaceProxy
import java.lang.reflect.Proxy
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class SponsorBlockHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private companion object {
        const val POLL_INTERVAL_MS = 1000L
        const val POLL_FALLBACK_INTERVAL_MS = 4000L
        const val SKIP_LOOKAHEAD_MS = 1000L
        const val FETCH_RETRY_DELAY_MS = 3000L
        const val PLAYER_CORE_STARTUP_PREWARM_DELAY_MS = 3000L
        const val PLAYER_CORE_PLAYVIEW_PREWARM_DELAY_MS = 300L
        /** Min gap between any two skip seeks (progress observer can fire very often). */
        const val MIN_SKIP_INTERVAL_MS = 1800L
        /** Per-segment cooldown so a short intro/outro cannot re-trigger while position settles. */
        const val SEGMENT_SKIP_COOLDOWN_MS = 8000L
        const val TAIL_SEGMENT_COOLDOWN_MS = 12_000L
        const val CLEAR_PAST_TOLERANCE_MS = 350L
        const val TAIL_SEGMENT_MARGIN_MS = 2000L
        const val START_SEGMENT_MARGIN_MS = 1500L
        const val SHORT_SEGMENT_EXTRA_PAD_MS = 700L
        /** Playback jumped back at least this far — user rewound, allow skip again. */
        const val SEEK_BACK_RESET_TOLERANCE_MS = 1500L
    }

    private data class VideoKey(
        val bvid: String,
        val cid: Long,
        val durationMs: Long = 0L,
    ) {
        fun sameVideo(other: VideoKey): Boolean =
            bvid == other.bvid && cid == other.cid
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastCheckedSecond: Long? = null
    private var currentVideo: VideoKey? = null
    private var pendingBvid: String? = null
    private var currentSegments: List<SponsorSegment> = emptyList()
    private var playerCoreService: Any? = null
    private var polling = false
    private var fetchingKey: VideoKey? = null
    private var allowInsideSegmentOnce = false
    private var lastSkipAtMs = 0L
    private val skippedSegmentUntilMs = ConcurrentHashMap<String, Long>()
    private var playerCoreHooked = false
    private var progressObserver: Any? = null
    private var progressObserverService: Any? = null
    private val positionMethodsByClass = ConcurrentHashMap<Class<*>, List<Method>>()
    private val fastPositionMethodByClass = ConcurrentHashMap<Class<*>, Method?>()
    private val fastDurationMethodByClass = ConcurrentHashMap<Class<*>, Method?>()
    private var pollIntervalMs = POLL_INTERVAL_MS
    private var lastPlaybackPositionMs = -1L
    private val pollRunnable = object : Runnable {
        override fun run() {
            checkAndSkip()
            if (polling) handler.postDelayed(this, pollIntervalMs)
        }
    }

    override fun startHook() {
        Log.s("startHook: SponsorBlock")
        SponsorBlockController.registerBridge(hookBridge)
        hookViewReplies()
        hookPlayViewUnite()
        hookStoryVideoChange()
        ensurePlayerCoreHooked()
        if (SponsorBlockPrefs.enabled) {
            ensurePolling()
        }
    }

    private val hookBridge = object : SponsorBlockController.HookBridge {
        override fun onEnabled() {
            currentVideo?.let(::fetchSegments)
            ensurePolling()
        }

        override fun onDisabled() {
            stopPolling()
            currentSegments = emptyList()
            val video = currentVideo?.toStateVideo()
            SponsorBlockState.reset(video)
        }

        override fun refetchCurrent() {
            currentVideo?.let(::fetchSegments)
        }

        override fun manualSkip(segment: SponsorBlockState.SegmentView) {
            seekToSegmentEnd(segment)
        }

        override fun seekToSegment(segment: SponsorBlockState.SegmentView) {
            seekToSegmentStart(segment)
        }

        override fun readPlaybackPositionMs(): Long? {
            val service = resolvePlayerCoreService() ?: return SponsorBlockState.playbackPositionMs.takeIf { it >= 0 }
            val methods = instance.playerCoreMethods ?: return null
            return readPositionMs(service, methods)
        }

        override fun currentSegments(): List<SponsorSegment> = currentSegments

        override fun rebindStoryFromPager(storyPagerPlayer: Any?) {
            rebindStorySegmentsFromPager(storyPagerPlayer, requireActiveQ = false)
        }
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun hookViewReplies() {
        instance.viewUniteMossClass?.hookMethod("executeView", instance.viewUniteReqClass) { chain ->
            updateFromViewReq(chain.args[0])
            chain.proceed()?.also { updateFromViewReply(it) }
        }

        val viewMethod = if (instance.useNewMossFunc) "executeView" else "view"
        instance.viewMossClass?.hookMethod(viewMethod, instance.viewReqClass) { chain ->
            updateFromViewReq(chain.args[0])
            chain.proceed()?.also { updateFromViewReply(it) }
        }
    }

    private fun hookPlayViewUnite() {
        val playerMossClass = instance.playerMossClass ?: return
        val reqClass = instance.playViewUniteReqClass ?: return
        val handlerClass = instance.mossResponseHandlerClass

        val unaryHandles = playerMossClass.hookAllMethods("executePlayViewUnite") { chain ->
            val args = chain.args.toTypedArray()
            val req = args.firstOrNull { reqClass.isInstance(it) }
            req?.let(::updateFromPlayViewReq)
            val result = chain.proceed()
            updateFromPlayViewReply(req, result)
            handler.post { onPlayViewUniteCompleted() }
            result
        }
        if (unaryHandles.isNotEmpty()) {
            Log.s("SponsorBlock: hooked PlayerMoss.executePlayViewUnite x${unaryHandles.size}")
        }

        if (handlerClass != null) {
            val streamHandles = playerMossClass.hookAllMethods("playViewUnite") { chain ->
                val args = chain.args.toTypedArray()
                val req = args.firstOrNull { reqClass.isInstance(it) }
                req?.let(::updateFromPlayViewReq)
                val handlerIndex = args.indexOfFirst { handlerClass.isInstance(it) }
                if (handlerIndex >= 0 && args[handlerIndex] != null) {
                    args[handlerIndex] = args[handlerIndex]!!.mossResponseHandlerReplaceProxy { reply ->
                        reply ?: return@mossResponseHandlerReplaceProxy null
                        updateFromPlayViewReply(req, reply)
                        ensurePlayerCoreHooked()
                        handler.post { onPlayViewUniteCompleted() }
                        null
                    }
                }
                chain.proceed(args)
            }
            if (streamHandles.isNotEmpty()) {
                Log.s("SponsorBlock: hooked PlayerMoss.playViewUnite (stream handler) x${streamHandles.size}")
            }
        }
    }

    private fun hookStoryVideoChange() {
        val addVideoName = instance.addVideoMethod()?.name ?: return
        val playerClass = instance.storyPagerPlayerClass ?: return
        playerClass.hookMethod(addVideoName, List::class.java) { chain ->
            // The argument is a *prefetch batch* appended to the pager; its first item is
            // NOT the video currently on screen. Using it would fetch a neighbour video's
            // segments and (via the inside-segment skip) apply them to the playing video,
            // causing a phantom ad skip. Always read the player's real current item (F1 =
            // adapter.get(ViewPager2.getCurrentItem())) and fall back to the batch only if
            // it is unavailable.
            val player = chain.thisObject
            val list = chain.args[0] as? List<*>
            rebindStorySegmentsFromPager(player)
            if (player.callMethodOrNull("F1") == null && shouldBindStorySegmentsFromPager(player)) {
                updateFromStoryItem(list?.firstOrNull(), player)
            }
            chain.proceed()
        }
        // Swiping between already-loaded videos (especially in UP space, where the list is
        // prefetched) triggers neither addVideo nor a fresh playView request, so the current
        // video and its segments would go stale: a no-ad video keeps a previous video's bar,
        // and an ad video shows mismatched segments that never skip. x2() is the player's
        // "play the current page" entry, invoked on every page change in both story feeds, so
        // read the now-current item there to keep segments bound to the on-screen video.
        val x2Method = playerClass.declaredMethods.firstOrNull {
            it.name == "x2" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (x2Method != null) {
            x2Method.hookMethod { chain ->
                val player = chain.thisObject
                val result = chain.proceed()
                // jadx x2: no-op when !d2() (Q!=3); hidden tab's F1 must not bind segments.
                rebindStorySegmentsFromPager(player)
                result
            }
            Log.s("SponsorBlock: hooked StoryPagerPlayer.x2 for page-change detection")
        }
        Log.s("SponsorBlock: hooked StoryPagerPlayer.$addVideoName for video change detection")
    }

    /**
     * Official StoryPagerPlayer.F1 = adapter.Z0(D1). Only the visible pager with Q==3 (d2) is
     * authoritative — prefetch playView and inactive-tab x2 must not drive segment binding.
     */
    private fun rebindStorySegmentsFromPager(storyPagerPlayer: Any?, requireActiveQ: Boolean = true) {
        val player = storyPagerPlayer ?: return
        if (!shouldBindStorySegmentsFromPager(player, requireActiveQ)) return
        updateFromStoryItem(player.callMethodOrNull("F1"), player)
    }

    /** jadx StoryPagerPlayer.d2(): Q == 3 — this pager owns playback. */
    private fun Any.isOfficialStoryPagerActive(): Boolean =
        readOfficialStoryPagerQ() == 3

    private fun Any.readOfficialStoryPagerQ(): Int? = runCatching {
        javaClass.declaredFields.firstOrNull {
            it.name == "Q" && it.type == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }?.getInt(this)
    }.getOrNull()

    private fun visibleStoryPagerPlayer(): Any? =
        when (StoryBackgroundAutoNextHook.visibleOuterTabIndex) {
            1 -> StoryBackgroundAutoNextHook.upSpaceStoryPlayer
            else -> StoryBackgroundAutoNextHook.mainFeedStoryPlayer
        } ?: StoryBackgroundAutoNextHook.activeStoryPlayer

    private fun isVisibleOuterTabStoryPager(player: Any): Boolean {
        val tabPlayer = visibleStoryPagerPlayer() ?: return false
        return player === tabPlayer
    }

    /**
     * Vertical Story pager owns SponsorBlock (F1/x2/w2). [StoryBackgroundAutoNextHook.hasActiveStory]
     * stays true after opening UGC detail (UnitedBizDetailsActivity) — must not use that alone.
     */
    private fun isStorySponsorBlockAuthority(): Boolean {
        if (!StoryBackgroundAutoNextHook.hasActiveStory()) return false
        val hook = StoryBackgroundAutoNextHook
        if (hook.activityPaused && !hook.isInBackground) return false
        val pager = visibleStoryPagerPlayer() ?: return false
        if (!isVisibleOuterTabStoryPager(pager)) return false
        return pager.isOfficialStoryPagerActive() || hook.isInBackground
    }

    private fun onPlayViewUniteCompleted() {
        if (isStorySponsorBlockAuthority()) {
            adoptStoryPlayerCore()
        } else {
            checkAndSkip()
        }
    }

    /** StoryPagerPlayer.F1 item → bvid/cid; authoritative for on-screen video in story mode. */
    private fun storyF1VideoKey(pager: Any? = visibleStoryPagerPlayer()): VideoKey? {
        val item = pager?.callMethodOrNull("F1") ?: return null
        val bvid = firstValidBvid(
            item.callMethodOrNullAs<String?>("getBvid"),
            item.callMethodOrNullAs<String?>("getBvId"),
        ) ?: item.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
        if (bvid.isNullOrBlank() || !bvid.startsWith("BV")) return null
        val cid = item.callMethodOrNullAs<Long?>("getCid") ?: 0L
        if (cid <= 0L) return null
        return VideoKey(bvid, cid)
    }

    private fun acceptStoryVideoBinding(video: VideoKey): Boolean {
        if (!isStorySponsorBlockAuthority()) return true
        val f1 = storyF1VideoKey() ?: return true
        return f1.sameVideo(video)
    }

    private fun ensureStorySegmentBinding(): Boolean {
        if (!isStorySponsorBlockAuthority()) return true
        val bound = currentVideo ?: return false
        val f1 = storyF1VideoKey() ?: return true
        if (bound.sameVideo(f1)) return true
        Log.trace {
            "SponsorBlock: story F1 mismatch bound=${bound.bvid}/${bound.cid} f1=${f1.bvid}/${f1.cid}, rebind"
        }
        rebindStorySegmentsFromPager(visibleStoryPagerPlayer(), requireActiveQ = false)
        return false
    }

    private fun shouldBindStorySegmentsFromPager(player: Any, requireActiveQ: Boolean = true): Boolean {
        if (!StoryBackgroundAutoNextHook.hasActiveStory()) return false
        if (!isVisibleOuterTabStoryPager(player)) return false
        if (requireActiveQ && !player.isOfficialStoryPagerActive()) return false
        return true
    }

    /** Resolve a story item's bvid/cid and (re)bind SponsorBlock to it. */
    private fun updateFromStoryItem(item: Any?, storyPagerPlayer: Any? = null) {
        item ?: return
        val bvid = firstValidBvid(
            item.callMethodOrNullAs<String?>("getBvid"),
            item.callMethodOrNullAs<String?>("getBvId"),
        ) ?: item.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
        if (bvid.isNullOrBlank() || !bvid.startsWith("BV")) return
        val cid = item.callMethodOrNullAs<Long?>("getCid") ?: 0L
        if (cid > 0L) {
            updateVideo(VideoKey(bvid, cid))
        } else {
            clearStaleProgressState(bvid, cid)
        }
        if (isStorySponsorBlockAuthority()) {
            adoptStoryPlayerCore(storyPagerPlayer)
        }
    }

    /**
     * Story mode binds vd3.p0 through StoryPlayer before our constructor hook runs, so
     * playerCoreService stays null and skip never fires (log: segments ready but player
     * service is not captured yet). Walk StoryPagerPlayer → StoryPlayer → IPlayerCoreService.
     */
    private fun Any.storyPlayerCoreFrom(): Any? = runCatching {
        val serviceClass = instance.playerCoreMethods?.serviceClass ?: return@runCatching null
        val storyPlayer = javaClass.declaredFields.firstOrNull {
            it.type.name == "com.bilibili.video.story.player.StoryPlayer"
        }?.apply { isAccessible = true }?.get(this) ?: return@runCatching null
        storyPlayer.javaClass.declaredFields.asSequence()
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(storyPlayer)
                }.getOrNull()
            }
            .firstOrNull { serviceClass.isInstance(it) }
    }.getOrNull()

    private fun adoptStoryPlayerCore(preferredStoryPager: Any? = null): Boolean {
        val serviceClass = instance.playerCoreMethods?.serviceClass ?: return false
        val candidates = buildList {
            preferredStoryPager?.let { add(it) }
            visibleStoryPagerPlayer()?.let { add(it) }
            StoryBackgroundAutoNextHook.activeStoryPlayer?.let { add(it) }
            StoryBackgroundAutoNextHook.mainFeedStoryPlayer?.let { add(it) }
            StoryBackgroundAutoNextHook.upSpaceStoryPlayer?.let { add(it) }
        }.distinctBy { System.identityHashCode(it) }
        for (pager in candidates) {
            val core = pager.storyPlayerCoreFrom() ?: continue
            if (playerCoreService !== core) {
                updatePlayerService(core, checkNow = true)
                Log.trace { "SponsorBlock: adopted story player core ${core.javaClass.name}#" +
                        System.identityHashCode(core) }
            } else if (progressObserver == null) {
                registerOfficialProgressObserver(core)
                checkAndSkip()
            }
            return true
        }
        StoryBackgroundAutoNextHook.activePlayerCore
            ?.takeIf { serviceClass.isInstance(it) }
            ?.let { core ->
                if (playerCoreService !== core) {
                    updatePlayerService(core, checkNow = true)
                    Log.trace { "SponsorBlock: adopted scanned story player core ${core.javaClass.name}#" +
                            System.identityHashCode(core) }
                }
                return true
            }
        return false
    }

    private fun resolvePlayerCoreService(): Any? {
        playerCoreService?.let { return it }
        adoptStoryPlayerCore()
        return playerCoreService
    }

    private fun ensurePlayerCoreHooked() {
        if (playerCoreHooked) return
        synchronized(this) {
            if (playerCoreHooked) return
            playerCoreHooked = true
            runCatching { hookPlayerCore() }.onFailure { Log.e(it) }
        }
    }

    private fun schedulePlayerCorePrewarm(delayMs: Long = PLAYER_CORE_PLAYVIEW_PREWARM_DELAY_MS) {
        if (playerCoreHooked) {
            handler.post { adoptStoryPlayerCore() }
            return
        }
        handler.postDelayed({ ensurePlayerCoreHooked() }, delayMs)
    }

    private fun hookPlayerCore() {
        val methods = instance.playerCoreMethods ?: run {
            Log.trace { "SponsorBlock: player core methods not found" }
            return
        }
        if (methods.currentPosition == null) {
            Log.trace { "SponsorBlock: current position method not found" }
        }
        Log.s("SponsorBlock: player core=${methods.serviceClass.name}, " +
                "seek=${methods.seekTo.name}/${methods.seekTo.parameterCount}, " +
                "position=${methods.currentPosition?.name}")
        hookPlayerCoreLazyCapture(methods)
        methods.serviceClass.hookAllConstructors { chain ->
            chain.proceed()
            updatePlayerService(chain.thisObject, checkNow = true)
            null
        }
    }

    /**
     * Bilibili reuses a single IPlayerCoreService for long sessions. Constructor hooks miss
     * instances created before our hook installs (log: segments=1 but player service never
     * captured — affects Story and normal UGC player alike).
     */
    private fun hookPlayerCoreLazyCapture(methods: me.custom.biliextras.BiliPackageLite.PlayerCoreMethods) {
        val serviceClass = methods.serviceClass
        val captureHook: (Any) -> Unit = { service ->
            val adopt = playerCoreService == null ||
                (!isStorySponsorBlockAuthority() && playerCoreService !== service)
            if (adopt) {
                Log.trace { "SponsorBlock: lazy-captured player core ${service.javaClass.name}#" +
                        System.identityHashCode(service) }
                updatePlayerService(service, checkNow = true)
            }
        }
        serviceClass.declaredMethods.filter { method ->
            method.parameterCount == 0 &&
                (method.returnType == Int::class.javaPrimitiveType ||
                    method.returnType == Long::class.javaPrimitiveType) &&
                (method.name == "getCurrentPosition" ||
                    method.name == "getRealCurrentPosition" ||
                    method.name.contains("CurrentPosition", ignoreCase = true))
        }.forEach { method ->
            serviceClass.hookMethod(method.name) { chain ->
                captureHook(chain.thisObject)
                chain.proceed()
            }
        }
        serviceClass.hookAllMethods("seekTo") { chain ->
            captureHook(chain.thisObject)
            val targetMs = (chain.args.firstOrNull() as? Number)?.toLong()
            chain.proceed()
            targetMs?.let { ms ->
                handler.post { prepareSkipStateForPosition(ms, fromUserSeek = true) }
            }
        }
    }

    /**
     * After the first skip, [skippedSegmentUntilMs] blocked re-entry for 8s even when the user
     * scrubbed back before the segment (log 23:22:10 hit once, no second hit after rewind).
     */
    private fun prepareSkipStateForPosition(positionMs: Long, fromUserSeek: Boolean = false) {
        val previous = lastPlaybackPositionMs
        lastPlaybackPositionMs = positionMs
        val jumpedBack = previous >= 0 && positionMs + SEEK_BACK_RESET_TOLERANCE_MS < previous
        if (!jumpedBack && !fromUserSeek) return
        // Auto-skip seek often lands slightly inside the segment; small backward jitter must not
        // clear cooldown or re-arm allowInsideSegmentOnce (log: reset every second → seek loop).
        if (jumpedBack && !fromUserSeek) {
            val rewoundBeforeSegment = currentSegments.any { segment ->
                positionMs < segment.startMs - CLEAR_PAST_TOLERANCE_MS
            }
            if (!rewoundBeforeSegment) return
        }
        var reset = false
        currentSegments.forEach { segment ->
            val startMs = segment.startMs
            val endMs = segment.endMs
            val key = segment.uuid.ifBlank { "${segment.category}:${segment.start}:${segment.end}" }
            if (positionMs < endMs - CLEAR_PAST_TOLERANCE_MS) {
                skippedSegmentUntilMs.remove(key)
                reset = true
            }
            if (positionMs in startMs until endMs) {
                allowInsideSegmentOnce = true
                reset = true
            }
        }
        if (reset || jumpedBack) {
            lastCheckedSecond = null
            lastSkipAtMs = 0L
            Log.trace { "SponsorBlock: reset skip eligibility at ${positionMs}ms " +
                    "(jumpedBack=$jumpedBack userSeek=$fromUserSeek)" }
        }
    }

    private fun updatePlayerService(service: Any, checkNow: Boolean) {
        val previousService = playerCoreService
        if (previousService != null && previousService !== service) {
            resetSkipThrottle()
            SponsorBlockState.invalidatePosition()
        }
        playerCoreService = service
        registerOfficialProgressObserver(service)
        ensurePolling()
        if (checkNow) checkAndSkip()
    }

    private fun registerOfficialProgressObserver(service: Any) {
        if (progressObserverService === service && progressObserver != null) return
        progressObserverService?.unregisterOfficialProgressObserver()
        progressObserver = null
        progressObserverService = null
        syncPollInterval()

        val registerMethod = service.javaClass.methods.firstOrNull {
            it.name == "registerPlayerProgressObserver" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == "tv.danmaku.biliplayerv2.service.PlayerProgressObserver"
        } ?: run {
            Log.trace { "SponsorBlock: registerPlayerProgressObserver missing on ${service.javaClass.name}" }
            return
        }
        val observerClass = registerMethod.parameterTypes[0]
        val observer = Proxy.newProxyInstance(mClassLoader, arrayOf(observerClass)) { _, method, args ->
            if (method.name == "onPlayerProgressChange" && args?.size == 2) {
                val positionMs = (args[0] as? Number)?.toLong()
                val durationMs = (args[1] as? Number)?.toLong()
                if (positionMs != null && durationMs != null) {
                    checkAndSkip(positionMs, durationMs)
                }
            }
            null
        }
        runCatching {
            registerMethod.invoke(service, observer)
            progressObserver = observer
            progressObserverService = service
            syncPollInterval()
            Log.s("SponsorBlock: registered official PlayerProgressObserver on ${service.javaClass.name}")
        }.onFailure { Log.e(it) }
    }

    private fun Any.unregisterOfficialProgressObserver() {
        val observer = progressObserver ?: return
        val unregisterMethod = javaClass.methods.firstOrNull {
            it.name == "unregisterPlayerProgressObserver" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isInstance(observer)
        } ?: return
        runCatching { unregisterMethod.invoke(this, observer) }.onFailure { Log.e(it) }
    }

    private fun updateFromViewReply(reply: Any) {
        if (isStorySponsorBlockAuthority()) return
        val arc = reply.callMethodOrNull("getArc")
        val bvid = firstValidBvid(
            arc?.callMethodOrNullAs<String?>("getBvid"),
            arc?.callMethodOrNullAs<String?>("getBvId"),
            arc?.callMethodOrNullAs<String?>("getBvidStr"),
        )
            ?: arc?.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
            ?: pendingBvid
        val aidBvid = bvid?.takeIf { it.startsWith("BV") }
        val cid = firstCidFromViewReply(reply, arc)
        if (aidBvid != null && cid > 0) {
            updateVideo(VideoKey(aidBvid, cid))
        } else {
            Log.d { "SponsorBlock: view reply missing bvid/cid, bvid=$bvid cid=$cid" }
        }
    }

    private fun updateFromViewReq(req: Any?) {
        req ?: return
        if (isStorySponsorBlockAuthority()) return
        val bvid = firstValidBvid(
            req.callMethodOrNullAs<String?>("getBvid"),
            req.callMethodOrNullAs<String?>("getBvId"),
        )
            ?: req.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
        if (!bvid.isNullOrBlank() && bvid.startsWith("BV")) {
            pendingBvid = bvid
            Log.trace { "SponsorBlock: pending bvid from view req $bvid" }
            val cid = req.callMethodOrNullAs<Long?>("getCid")
                ?: req.callMethodOrNull("getVod")?.callMethodOrNullAs<Long?>("getCid")
                ?: 0L
            if (cid > 0L) {
                updateVideo(VideoKey(bvid, cid))
            }
        }
    }

    private fun firstCidFromViewReply(reply: Any, arc: Any? = reply.callMethodOrNull("getArc")): Long {
        arc?.callMethodOrNullAs<Long?>("getCid")?.takeIf { it > 0L }?.let { return it }
        val pages = reply.callMethodOrNull("getPagesList") as? List<*>
            ?: reply.callMethodOrNull("getPages") as? List<*>
            ?: return 0L
        return pages.firstOrNull { page ->
            val pageCid = page?.callMethodOrNull("getPage")?.callMethodOrNullAs<Long?>("getCid")
                ?: page?.callMethodOrNullAs<Long?>("getCid")
            pageCid != null && pageCid > 0
        }?.let { page ->
            page.callMethodOrNull("getPage")?.callMethodOrNullAs<Long?>("getCid")
                ?: page.callMethodOrNullAs<Long?>("getCid")
        } ?: 0L
    }

    private fun updateFromPlayViewReq(req: Any?) {
        req ?: return
        val vod = req.callMethodOrNull("getVod")
        val cid = vod?.callMethodOrNullAs<Long?>("getCid") ?: 0L
        val bvid = firstValidBvid(
            req.callMethodOrNullAs<String?>("getBvid"),
            req.callMethodOrNullAs<String?>("getBvId"),
            vod?.callMethodOrNullAs<String?>("getBvid"),
            vod?.callMethodOrNullAs<String?>("getBvId"),
        )
            ?: req.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
            ?: vod?.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
            ?: pendingBvid
        if (cid <= 0) {
            if (!bvid.isNullOrBlank() && bvid.startsWith("BV")) {
                pendingBvid = bvid
            }
            return
        }
        if (isStorySponsorBlockAuthority()) {
            // Story: segments follow F1/x2, not neighbour prefetch playView (jadx F1 = Z0(D1)).
            return
        }
        if (!bvid.isNullOrBlank() && bvid.startsWith("BV")) {
            clearStaleProgressState(bvid, cid)
            pendingBvid = bvid
            updateVideo(VideoKey(bvid, cid))
            return
        }
        val current = currentVideo
        if (current != null && current.cid != cid) {
            updateVideo(current.copy(cid = cid))
        } else if (current == null) {
            Log.d { "SponsorBlock: playView cid=$cid but current bvid is null" }
        }
    }

    private fun clearStaleProgressState(bvid: String, cid: Long) {
        val current = currentVideo
        if (current != null && current.bvid == bvid && (cid <= 0L || current.cid == cid)) return
        currentSegments = emptyList()
        resetSkipThrottle()
        val pendingVideo = VideoKey(bvid, cid.coerceAtLeast(0L))
        SponsorBlockState.reset(pendingVideo.toStateVideo())
    }

    private fun updateFromPlayViewReply(req: Any?, reply: Any?) {
        reply ?: return
        val playArc = reply.callMethodOrNull("getPlayArc")
        val cid = playArc?.callMethodOrNullAs<Long?>("getCid") ?: 0L
        val durationMs = playArc?.callMethodOrNullAs<Long?>("getDurationMs")
            ?: playArc?.callMethodOrNullAs<Long?>("getWatchTimeLength")
            ?: (playArc?.callMethodOrNullAs<Long?>("getDuration") ?: 0L) * 1000L
        if (cid <= 0) {
            return
        }
        val reqVod = req?.callMethodOrNull("getVod")
        val bvid = firstValidBvid(
            req?.callMethodOrNullAs<String?>("getBvid"),
            req?.callMethodOrNullAs<String?>("getBvId"),
            reqVod?.callMethodOrNullAs<String?>("getBvid"),
            reqVod?.callMethodOrNullAs<String?>("getBvId"),
        )
            ?: req?.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
            ?: reqVod?.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
            ?: pendingBvid
        if (!bvid.isNullOrBlank() && bvid.startsWith("BV")) {
            if (isStorySponsorBlockAuthority()) {
                ensurePlayerCoreHooked()
                handler.post { adoptStoryPlayerCore() }
                return
            }
            updateVideo(VideoKey(bvid, cid, durationMs.coerceAtLeast(0L)))
            ensurePlayerCoreHooked()
            handler.post {
                adoptStoryPlayerCore()
                checkAndSkip()
            }
        } else {
            Log.trace { "SponsorBlock: playView reply cid=$cid but bvid is null" }
        }
    }


    private fun updateVideo(video: VideoKey) {
        if (!acceptStoryVideoBinding(video)) {
            Log.trace {
                "SponsorBlock: drop updateVideo ${video.bvid}/${video.cid} (story F1 mismatch)"
            }
            return
        }
        val current = currentVideo
        if (current?.bvid == video.bvid && current.cid == video.cid) {
            if (video.durationMs > current.durationMs) {
                currentVideo = video
                if (SponsorBlockPrefs.enabled) {
                    SponsorBlockState.update(video.toStateVideo(), currentSegments)
                }
            }
            return
        }
        currentVideo = video
        currentSegments = emptyList()
        resetSkipThrottle()
        if (!SponsorBlockPrefs.enabled) {
            SponsorBlockState.reset(video.toStateVideo())
            return
        }
        val cachedSegments = peekCachedSegments(video)
        if (cachedSegments.isNotEmpty()) {
            currentSegments = cachedSegments
            allowInsideSegmentOnce = true
            SponsorBlockState.update(video.toStateVideo(), currentSegments)
            Log.trace { "SponsorBlock: cache hit ${video.bvid}/${video.cid}, segments=${currentSegments.size}" }
        } else {
            SponsorBlockState.reset(video.toStateVideo())
        }
        fetchSegments(video)
    }

    private fun peekCachedSegments(video: VideoKey): List<SponsorSegment> {
        val categories = SponsorBlockPrefs.requestCategories
        if (categories.isEmpty()) return emptyList()
        val cached = SponsorBlockCache.peek(video.bvid, video.cid, categories) ?: return emptyList()
        return filterEnabledSegments(cached)
    }

    private fun filterEnabledSegments(segments: List<SponsorSegment>): List<SponsorSegment> =
        segments.filter { segment ->
            SponsorBlockPrefs.modeOf(segment.category) != SponsorBlockCategory.SkipMode.Disabled &&
                segment.duration >= SponsorBlockPrefs.blockLimit
        }

    private fun resetSkipThrottle() {
        lastCheckedSecond = null
        lastSkipAtMs = 0L
        skippedSegmentUntilMs.clear()
        allowInsideSegmentOnce = false
        lastPlaybackPositionMs = -1L
    }

    private fun fetchSegments(video: VideoKey) {
        if (!SponsorBlockPrefs.enabled) return
        if (fetchingKey?.sameVideo(video) == true) return
        fetchingKey = video
        val categories = SponsorBlockPrefs.requestCategories
        if (categories.isEmpty()) {
            Log.trace { "SponsorBlock: no SponsorBlock category enabled" }
            fetchingKey = null
            return
        }
        SponsorBlockBackground.submitFetch {
            val fetchStartedAt = System.currentTimeMillis()
            val result = SponsorBlockCache.getOrFetch(video.bvid, video.cid, categories)
            handler.post {
                fetchingKey = null
                val activeVideo = currentVideo?.takeIf { it.sameVideo(video) } ?: return@post
                val fetchedSegments = result.getOrNull()
                if (fetchedSegments != null) {
                    currentSegments = filterEnabledSegments(fetchedSegments)
                    allowInsideSegmentOnce = true
                    SponsorBlockState.update(activeVideo.toStateVideo(), currentSegments)
                }
                val error = result.exceptionOrNull()?.message
                val elapsed = System.currentTimeMillis() - fetchStartedAt
                if (currentSegments.isNotEmpty() || error != null) {
                    Log.trace {
                        "SponsorBlock: ${video.bvid}/${video.cid}, segments=${currentSegments.size}, " +
                            "elapsed=${elapsed}ms, error=$error"
                    }
                }
                if (currentSegments.isNotEmpty() && playerCoreService == null) {
                    if (!adoptStoryPlayerCore()) {
                        Log.trace { "SponsorBlock: segments ready but player service is not captured yet" }
                    }
                }
                checkAndSkip()
                if (result.isFailure && currentVideo?.sameVideo(video) == true) {
                    handler.postDelayed({
                        if (currentVideo?.sameVideo(video) == true && fetchingKey == null) {
                            Log.trace { "SponsorBlock: retry fetch after failure for ${video.bvid}/${video.cid}" }
                            fetchSegments(currentVideo ?: video)
                        }
                    }, FETCH_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun syncPollInterval() {
        pollIntervalMs = if (progressObserver != null) POLL_FALLBACK_INTERVAL_MS else POLL_INTERVAL_MS
    }

    private fun ensurePolling() {
        if (!SponsorBlockPrefs.enabled) return
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    private fun checkAndSkip() {
        val service = resolvePlayerCoreService() ?: return
        if (instance.playerCoreMethods == null) return
        val positions = positionSnapshotFast(service)
        val positionMs = selectPositionMs(positions) ?: return
        val durationMs = positions["getRealDuration"] ?: positions["getDuration"]
        checkAndSkip(positionMs, durationMs)
    }

    private fun checkAndSkip(positionMs: Long, durationMs: Long?) {
        if (!SponsorBlockPrefs.enabled) return
        if (!ensureStorySegmentBinding()) return
        prepareSkipStateForPosition(positionMs)
        if (currentSegments.isNotEmpty()) {
            SponsorBlockState.updatePlaybackPosition(positionMs)
        }
        val now = System.currentTimeMillis()
        if (now - lastSkipAtMs < MIN_SKIP_INTERVAL_MS) return
        val positionSecond = positionMs / 1000L
        if (lastCheckedSecond == positionSecond) return
        lastCheckedSecond = positionSecond
        val segment = currentSegments.firstOrNull {
            val mode = SponsorBlockPrefs.modeOf(it.category)
            if (mode != SponsorBlockCategory.SkipMode.Always && mode != SponsorBlockCategory.SkipMode.Once) {
                return@firstOrNull false
            }
            val startMs = it.startMs
            val endMs = it.endMs
            if (positionMs >= endMs - CLEAR_PAST_TOLERANCE_MS) return@firstOrNull false
            val key = it.uuid.ifBlank { "${it.category}:${it.start}:${it.end}" }
            val cooldownUntil = skippedSegmentUntilMs[key] ?: 0L
            if (cooldownUntil > now) {
                if (positionMs < startMs - CLEAR_PAST_TOLERANCE_MS) {
                    skippedSegmentUntilMs.remove(key)
                } else {
                    return@firstOrNull false
                }
            }
            (positionMs <= startMs && startMs <= positionMs + SKIP_LOOKAHEAD_MS) ||
                (allowInsideSegmentOnce && positionMs in startMs until endMs)
        } ?: return
        val key = segment.uuid.ifBlank { "${segment.category}:${segment.start}:${segment.end}" }
        allowInsideSegmentOnce = false
        val service = resolvePlayerCoreService() ?: return
        val startMs = segment.startMs
        val endMs = segment.endMs
        val resolvedDuration = durationMs?.takeIf { it > 0L } ?: positionSnapshot(service).let { positions ->
            (positions["getRealDuration"] ?: positions["getDuration"])?.takeIf { it > 0L }
        }
        Log.trace { "SponsorBlock: hit segment key=$key, position=${positionMs}ms, " +
                "range=${startMs}-${endMs}ms, service=${service.javaClass.name}#${System.identityHashCode(service)}" }
        lastSkipAtMs = now
        skippedSegmentUntilMs[key] = now + cooldownForSegment(segment, resolvedDuration)
        seekTo(service, segment, resolvedDuration)
    }

    private fun cooldownForSegment(segment: SponsorSegment, durationMs: Long?): Long {
        val endMs = segment.endMs
        val isTail = durationMs != null && durationMs > 0L && endMs >= durationMs - TAIL_SEGMENT_MARGIN_MS
        return if (isTail) TAIL_SEGMENT_COOLDOWN_MS else SEGMENT_SKIP_COOLDOWN_MS
    }

    private fun seekToSegmentEnd(segment: SponsorBlockState.SegmentView, attempt: Int = 0) {
        val service = resolvePlayerCoreService()
        if (service == null) {
            if (attempt < 5) {
                schedulePlayerCorePrewarm(0)
                handler.postDelayed({ seekToSegmentEnd(segment, attempt + 1) }, 300L)
            } else {
                Log.toast("无法跳过：播放器未就绪")
            }
            return
        }
        val sponsorSegment = SponsorSegment(
            start = segment.startMs / 1000.0,
            end = segment.endMs / 1000.0,
            category = segment.category,
            uuid = segment.uuid,
            actionType = segment.actionType,
        )
        val durationMs = currentVideo?.durationMs?.takeIf { it > 0 }
            ?: positionSnapshot(service).let { positions ->
                positions["getRealDuration"] ?: positions["getDuration"]
            }
        seekTo(service, sponsorSegment, durationMs)
    }

    private fun seekToSegmentStart(segment: SponsorBlockState.SegmentView, attempt: Int = 0) {
        val service = resolvePlayerCoreService()
        if (service == null) {
            if (attempt < 5) {
                schedulePlayerCorePrewarm(0)
                handler.postDelayed({ seekToSegmentStart(segment, attempt + 1) }, 300L)
            } else {
                Log.toast("无法跳转：播放器未就绪")
            }
            return
        }
        val methods = instance.playerCoreMethods ?: return
        val targetMs = segment.startMs.toInt().coerceAtLeast(0)
        invokeSeek(methods.seekTo, service, targetMs, false).onFailure {
            Log.trace { "SponsorBlock: seek to start failed target=$targetMs error=${it.message}" }
            Log.toast("跳转到起点失败")
        }
    }

    private fun seekTo(service: Any, segment: SponsorSegment, knownDurationMs: Long? = null) {
        val methods = instance.playerCoreMethods ?: return
        val startMs = segment.startMs
        val endMs = segment.endMs
        val segmentLenMs = (endMs - startMs).coerceAtLeast(0L)
        val durationMs = knownDurationMs ?: positionSnapshot(service).let { positions ->
            positions["getRealDuration"] ?: positions["getDuration"]
        }?.takeIf { it > 0L }
        val beforeMs = readPositionMs(service, methods)
        if (beforeMs != null && beforeMs >= endMs - CLEAR_PAST_TOLERANCE_MS) return
        val isTail = durationMs != null && endMs >= durationMs - TAIL_SEGMENT_MARGIN_MS
        if (isTail && durationMs != null && beforeMs != null && beforeMs >= durationMs - 1200L) {
            return
        }
        val extraPad = when {
            startMs <= START_SEGMENT_MARGIN_MS && segmentLenMs < 3000L -> SHORT_SEGMENT_EXTRA_PAD_MS
            segmentLenMs < 2000L -> SHORT_SEGMENT_EXTRA_PAD_MS / 2
            else -> 0L
        }
        var targetMs = if (extraPad > 0L) (endMs + extraPad).toInt() else endMs.toInt()
        if (durationMs != null) {
            val maxTarget = (durationMs - 200L).coerceAtLeast(endMs).toInt()
            targetMs = targetMs.coerceAtMost(maxTarget)
            if (isTail) {
                targetMs = maxTarget
            }
        }
        if (beforeMs != null) {
            targetMs = targetMs.coerceAtLeast((beforeMs + 200L).toInt())
        }
        if (beforeMs != null && targetMs <= beforeMs + 150) return
        val accurate = methods.seekTo.parameterCount == 2
        invokeSeek(methods.seekTo, service, targetMs, accurate).onSuccess {
            SponsorBlockPrefs.addStats(((targetMs.toLong() - (beforeMs ?: targetMs.toLong())) / 1000L).coerceAtLeast(0L))
            Log.trace { "SponsorBlock: seek ok target=$targetMs end=$endMs accurate=$accurate before=$beforeMs" }
            if (SponsorBlockPrefs.showToast) {
                Log.toast("空降：${SponsorBlockCategory.titleOf(segment.category)}")
            }
            if (SponsorBlockPrefs.trackStats && segment.uuid.isNotBlank()) {
                SponsorBlockBackground.submit {
                    SponsorBlockApi.viewedVideoSponsorTime(segment.uuid)
                }
            }
        }.onFailure {
            Log.trace { "SponsorBlock: seek primary failed method=${methodSignature(methods.seekTo)}, " +
                    "target=$targetMs, before=$beforeMs, error=${it.javaClass.name}: ${it.message}" }
            Log.e(it)
        }
    }

    private fun readPositionMs(service: Any, methods: me.custom.biliextras.BiliPackageLite.PlayerCoreMethods): Long? {
        selectPositionMs(positionSnapshotFast(service))?.let { return it }
        return methods.currentPosition?.let { method ->
            runCatching { (method.invoke(service) as? Number)?.toLong() }.getOrNull()
        }
    }

    private fun positionSnapshotFast(service: Any): Map<String, Long> {
        val clazz = service.javaClass
        val posMethod = fastPositionMethodByClass.getOrPut(clazz) { resolvePositionMethod(clazz) }
        val durMethod = fastDurationMethodByClass.getOrPut(clazz) { resolveDurationMethod(clazz) }
        val map = HashMap<String, Long>(2)
        posMethod?.let { method ->
            runCatching { (method.invoke(service) as? Number)?.toLong() }
                .getOrNull()
                ?.let { map[method.name] = it }
        }
        durMethod?.let { method ->
            runCatching { (method.invoke(service) as? Number)?.toLong() }
                .getOrNull()
                ?.let { map[method.name] = it }
        }
        if (map.isNotEmpty()) return map
        return positionSnapshot(service)
    }

    private fun resolvePositionMethod(clazz: Class<*>): Method? {
        instance.playerCoreMethods?.currentPosition
            ?.takeIf { it.declaringClass.isAssignableFrom(clazz) }
            ?.let { return it }
        return clazz.methods.firstOrNull { it.parameterCount == 0 && it.name == "getRealCurrentPosition" }
            ?: clazz.methods.firstOrNull { it.parameterCount == 0 && it.name == "getCurrentPosition" }
    }

    private fun resolveDurationMethod(clazz: Class<*>): Method? =
        clazz.methods.firstOrNull { it.parameterCount == 0 && it.name == "getRealDuration" }
            ?: clazz.methods.firstOrNull { it.parameterCount == 0 && it.name == "getDuration" }

    private fun positionSnapshot(service: Any): Map<String, Long> {
        val methods = positionMethodsByClass.computeIfAbsent(service.javaClass) { clazz ->
            clazz.methods.filter { method ->
                method.parameterCount == 0 &&
                    (method.returnType == Int::class.javaPrimitiveType || method.returnType == Long::class.javaPrimitiveType) &&
                    (method.name.contains("position", ignoreCase = true) ||
                        method.name.contains("duration", ignoreCase = true))
            }
        }
        return methods
            .asSequence()
            .mapNotNull { method ->
                val value = runCatching { (method.invoke(service) as? Number)?.toLong() }.getOrNull()
                value?.let { method.name to it }
            }
            .toMap()
    }

    private fun selectPositionMs(positions: Map<String, Long>): Long? {
        return positions["getRealCurrentPosition"]?.takeIf { it > 0 }
            ?: positions["getCurrentPosition"]?.takeIf { it > 0 }
            ?: positions.values.firstOrNull { it > 0 }
            ?: positions["getCurrentPosition"]
    }

    private fun invokeSeek(method: Method, service: Any, targetMs: Int, booleanValue: Boolean): Result<Unit> =
        runCatching {
            when (method.parameterCount) {
                1 -> method.invoke(service, targetMs)
                2 -> method.invoke(service, targetMs, booleanValue)
                else -> method.invoke(service, targetMs)
            }
            Unit
        }

    private fun av2bv(aid: Long): String {
        val table = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
        val positions = intArrayOf(11, 10, 3, 8, 4, 6, 5, 7, 9)
        val chars = "BV1  4 1 7  ".toCharArray()
        var x = aid.xor(23442827791579L).or(2251799813685248L)
        positions.forEach { position ->
            chars[position] = table[(x % 58).toInt()]
            x /= 58
        }
        return String(chars)
    }

    private fun firstValidBvid(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() && it.startsWith("BV") }

    private fun VideoKey.toStateVideo(): SponsorBlockState.Video {
        val duration = durationMs.takeIf { it > 0 }
            ?: playerCoreService?.let(::positionSnapshot)
                ?.let { positions -> positions["getRealDuration"] ?: positions["getDuration"] }
            ?: 0L
        return SponsorBlockState.Video(bvid, cid, duration)
    }

    private fun methodSignature(method: Method): String =
        "${method.declaringClass.name}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }}):${method.returnType.simpleName}"
}
