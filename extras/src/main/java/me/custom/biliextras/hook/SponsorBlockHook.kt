package me.custom.biliextras.hook

import android.os.Handler
import android.os.Looper
import me.custom.biliextras.BiliPackageLite.Companion.instance
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
import java.lang.reflect.Proxy
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class SponsorBlockHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private companion object {
        const val POLL_INTERVAL_MS = 1000L
        const val SKIP_LOOKAHEAD_MS = 1000L
        const val SKIP_TARGET_PADDING_MS = 0
        const val FETCH_RETRY_DELAY_MS = 3000L
        const val PLAYER_CORE_STARTUP_PREWARM_DELAY_MS = 3000L
        const val PLAYER_CORE_PLAYVIEW_PREWARM_DELAY_MS = 300L
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
    private var playerCoreHooked = false
    private var progressObserver: Any? = null
    private var progressObserverService: Any? = null
    private val positionMethodsByClass = ConcurrentHashMap<Class<*>, List<Method>>()
    private val pollRunnable = object : Runnable {
        override fun run() {
            checkAndSkip()
            if (polling) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun startHook() {
        val enabled = SponsorBlockPrefs.enabled
        if (!enabled) return
        Log.x("startHook: SponsorBlock")
        hookViewReplies()
        hookPlayViewUnite()
        hookStoryVideoChange()
        schedulePlayerCorePrewarm(PLAYER_CORE_STARTUP_PREWARM_DELAY_MS)
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
            result
        }
        if (unaryHandles.isNotEmpty()) {
            Log.x("SponsorBlock: hooked PlayerMoss.executePlayViewUnite x${unaryHandles.size}")
        }

        if (handlerClass != null) {
            val streamHandles = playerMossClass.hookAllMethods("playViewUnite") { chain ->
                val args = chain.args.toTypedArray()
                val req = args.firstOrNull { reqClass.isInstance(it) }
                req?.let(::updateFromPlayViewReq)
                chain.proceed()
            }
            if (streamHandles.isNotEmpty()) {
                Log.x("SponsorBlock: hooked PlayerMoss.playViewUnite (req-only) x${streamHandles.size}")
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
            val firstItem = player.callMethodOrNull("F1") ?: list?.firstOrNull()
            if (firstItem != null) {
                val bvid = firstValidBvid(
                    firstItem.callMethodOrNullAs<String?>("getBvid"),
                    firstItem.callMethodOrNullAs<String?>("getBvId"),
                ) ?: firstItem.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
                val cid = firstItem.callMethodOrNullAs<Long?>("getCid") ?: 0L
                if (!bvid.isNullOrBlank() && bvid.startsWith("BV")) {
                    if (cid > 0L) {
                        updateVideo(VideoKey(bvid, cid))
                    } else {
                        clearStaleProgressState(bvid, cid)
                    }
                }
            }
            chain.proceed()
        }
        Log.x("SponsorBlock: hooked StoryPagerPlayer.$addVideoName for video change detection")
    }

    private fun hookPlayerCore() {
        val methods = instance.playerCoreMethods ?: run {
            Log.x("SponsorBlock: player core methods not found")
            return
        }
        if (methods.currentPosition == null) {
            Log.x("SponsorBlock: current position method not found")
        }
        Log.x(
            "SponsorBlock: player core=${methods.serviceClass.name}, " +
                "seek=${methods.seekTo.name}/${methods.seekTo.parameterCount}, " +
                "position=${methods.currentPosition?.name}",
        )
        methods.serviceClass.hookAllConstructors { chain ->
            chain.proceed()
            updatePlayerService(chain.thisObject, checkNow = true)
            null
        }
    }

    private fun prewarmPlayerCoreHook() {
        if (playerCoreHooked) return
        thread(name = "BiliExtrasSponsorBlockPrewarm") {
            synchronized(this) {
                if (playerCoreHooked) return@synchronized
                playerCoreHooked = true
                runCatching { hookPlayerCore() }.onFailure {
                    Log.e(it)
                }
            }
        }
    }

    private fun schedulePlayerCorePrewarm(delayMs: Long = PLAYER_CORE_PLAYVIEW_PREWARM_DELAY_MS) {
        if (playerCoreHooked) return
        handler.postDelayed({ prewarmPlayerCoreHook() }, delayMs)
    }

    private fun updatePlayerService(service: Any, checkNow: Boolean) {
        val previousService = playerCoreService
        if (previousService != null && previousService !== service) {
            lastCheckedSecond = null
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

        val registerMethod = service.javaClass.methods.firstOrNull {
            it.name == "registerPlayerProgressObserver" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name == "tv.danmaku.biliplayerv2.service.PlayerProgressObserver"
        } ?: return
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
            Log.x("SponsorBlock: registered official PlayerProgressObserver on ${service.javaClass.name}")
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
        val arc = reply.callMethodOrNull("getArc")
        val bvid = firstValidBvid(
            arc?.callMethodOrNullAs<String?>("getBvid"),
            arc?.callMethodOrNullAs<String?>("getBvId"),
            arc?.callMethodOrNullAs<String?>("getBvidStr"),
        )
            ?: arc?.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
            ?: pendingBvid
        val aidBvid = bvid?.takeIf { it.startsWith("BV") }
        val cid = firstCidFromViewReply(reply)
        if (aidBvid != null && cid > 0) {
            updateVideo(VideoKey(aidBvid, cid))
        } else {
            Log.d("SponsorBlock: view reply missing bvid/cid, bvid=$bvid cid=$cid")
        }
    }

    private fun updateFromViewReq(req: Any?) {
        req ?: return
        val bvid = firstValidBvid(
            req.callMethodOrNullAs<String?>("getBvid"),
            req.callMethodOrNullAs<String?>("getBvId"),
        )
            ?: req.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }?.let(::av2bv)
        if (!bvid.isNullOrBlank() && bvid.startsWith("BV")) {
            pendingBvid = bvid
            Log.x("SponsorBlock: pending bvid from view req $bvid")
        }
    }

    private fun firstCidFromViewReply(reply: Any): Long {
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
        if (!bvid.isNullOrBlank() && bvid.startsWith("BV")) {
            clearStaleProgressState(bvid, cid)
            pendingBvid = bvid
        }
        if (cid <= 0) {
            return
        }
        if (!bvid.isNullOrBlank() && bvid.startsWith("BV")) {
            updateVideo(VideoKey(bvid, cid))
            return
        }
        val current = currentVideo
        if (current != null && current.cid != cid) {
            updateVideo(current.copy(cid = cid))
        } else if (current == null) {
            Log.d("SponsorBlock: playView cid=$cid but current bvid is null")
        }
    }

    private fun clearStaleProgressState(bvid: String, cid: Long) {
        val current = currentVideo
        if (current != null && current.bvid == bvid && (cid <= 0L || current.cid == cid)) return
        currentSegments = emptyList()
        lastCheckedSecond = null
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
            updateVideo(VideoKey(bvid, cid, durationMs.coerceAtLeast(0L)))
            schedulePlayerCorePrewarm()
        } else {
            Log.x("SponsorBlock: playView reply cid=$cid but bvid is null")
        }
    }


    private fun updateVideo(video: VideoKey) {
        val current = currentVideo
        if (current?.bvid == video.bvid && current.cid == video.cid) {
            if (video.durationMs > current.durationMs) {
                currentVideo = video
                SponsorBlockState.update(video.toStateVideo(), currentSegments)
            }
            return
        }
        currentVideo = video
        currentSegments = emptyList()
        lastCheckedSecond = null
        SponsorBlockState.reset(video.toStateVideo())
        fetchSegments(video)
    }

    private fun fetchSegments(video: VideoKey) {
        if (fetchingKey?.sameVideo(video) == true) return
        fetchingKey = video
        val categories = SponsorBlockPrefs.requestCategories
        if (categories.isEmpty()) {
            Log.x("SponsorBlock: no SponsorBlock category enabled")
            return
        }
        thread(name = "BiliExtrasSponsorBlockFetch") {
            val result = SponsorBlockCache.getOrFetch(video.bvid, video.cid, categories)
            handler.post {
                fetchingKey = null
                val activeVideo = currentVideo?.takeIf { it.sameVideo(video) } ?: return@post
                val fetchedSegments = result.getOrNull()
                if (fetchedSegments != null) {
                    currentSegments = fetchedSegments.filter { segment ->
                        SponsorBlockPrefs.modeOf(segment.category) != SponsorBlockCategory.SkipMode.Disabled &&
                            segment.duration >= SponsorBlockPrefs.blockLimit
                    }
                    allowInsideSegmentOnce = true
                    SponsorBlockState.update(activeVideo.toStateVideo(), currentSegments)
                }
                val error = result.exceptionOrNull()?.message
                if (currentSegments.isNotEmpty() || error != null) {
                    Log.x("SponsorBlock: ${video.bvid}/${video.cid}, segments=${currentSegments.size}, error=$error")
                }
                if (currentSegments.isNotEmpty() && playerCoreService == null) {
                    Log.x("SponsorBlock: segments ready but player service is not captured yet")
                }
                checkAndSkip()
                if (result.isFailure && currentVideo?.sameVideo(video) == true) {
                    handler.postDelayed({
                        if (currentVideo?.sameVideo(video) == true && fetchingKey == null) {
                            Log.x("SponsorBlock: retry fetch after failure for ${video.bvid}/${video.cid}")
                            fetchSegments(currentVideo ?: video)
                        }
                    }, FETCH_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun ensurePolling() {
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    private fun checkAndSkip() {
        val service = playerCoreService ?: return
        val methods = instance.playerCoreMethods ?: return
        val positions = positionSnapshot(service)
        val positionMs = selectPositionMs(positions) ?: return
        val durationMs = positions["getRealDuration"] ?: positions["getDuration"]
        checkAndSkip(positionMs, durationMs)
    }

    private fun checkAndSkip(positionMs: Long, durationMs: Long?) {
        if (currentSegments.isNotEmpty()) {
            SponsorBlockState.updatePlaybackPosition(positionMs)
        }
        val positionSecond = positionMs / 1000L
        if (lastCheckedSecond == positionSecond) return
        lastCheckedSecond = positionSecond
        val segment = currentSegments.firstOrNull {
            val mode = SponsorBlockPrefs.modeOf(it.category)
            if (mode != SponsorBlockCategory.SkipMode.Always && mode != SponsorBlockCategory.SkipMode.Once) {
                return@firstOrNull false
            }
            val startMs = (it.start * 1000).toLong()
            val endMs = (it.end * 1000).toLong()
            (positionMs <= startMs && startMs <= positionMs + SKIP_LOOKAHEAD_MS) ||
                (allowInsideSegmentOnce && positionMs in startMs until endMs)
        } ?: return
        val key = segment.uuid.ifBlank { "${segment.category}:${segment.start}:${segment.end}" }
        allowInsideSegmentOnce = false
        val service = playerCoreService ?: return
        Log.x(
            "SponsorBlock: hit segment key=$key, position=${positionMs}ms, " +
                "target=${(segment.end * 1000).toInt()}ms, service=${service.javaClass.name}#${System.identityHashCode(service)}",
        )
        seekTo(service, segment, durationMs)
    }

    private fun seekTo(service: Any, segment: SponsorSegment, knownDurationMs: Long? = null) {
        val methods = instance.playerCoreMethods ?: return
        val requestedTargetMs = (segment.end * 1000).toInt() + SKIP_TARGET_PADDING_MS
        val durationMs = knownDurationMs ?: positionSnapshot(service).let { positions -> positions["getRealDuration"] ?: positions["getDuration"] }
            ?.takeIf { it > 0L }
        val targetMs = durationMs
            ?.let { duration -> requestedTargetMs.coerceAtMost((duration - 500L).coerceAtLeast(0L).toInt()) }
            ?: requestedTargetMs
        val beforeMs = readPositionMs(service, methods)
        invokeSeek(methods.seekTo, service, targetMs, false).onSuccess {
            SponsorBlockPrefs.addStats(((targetMs.toLong() - (beforeMs ?: targetMs.toLong())) / 1000L).coerceAtLeast(0L))
            verifySeek(service, targetMs)
            if (SponsorBlockPrefs.showToast) {
                Log.toast("空降：${SponsorBlockCategory.titleOf(segment.category)}")
            }
            if (SponsorBlockPrefs.trackStats && segment.uuid.isNotBlank()) {
                thread(name = "BiliExtrasSponsorBlockTrack") {
                    SponsorBlockApi.viewedVideoSponsorTime(segment.uuid)
                }
            }
        }.onFailure {
            Log.x(
                "SponsorBlock: seek primary failed method=${methodSignature(methods.seekTo)}, " +
                    "target=$targetMs, before=$beforeMs, error=${it.javaClass.name}: ${it.message}",
            )
            Log.e(it)
        }
    }

    private fun readPositionMs(service: Any, methods: me.custom.biliextras.BiliPackageLite.PlayerCoreMethods): Long? {
        return selectPositionMs(positionSnapshot(service))
    }

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

    private fun verifySeek(service: Any, targetMs: Int) {
        val methods = instance.playerCoreMethods ?: return
        handler.postDelayed({
            val afterMs = readPositionMs(service, methods)
            if (afterMs != null && kotlin.math.abs(afterMs - targetMs) > 3000 && methods.seekTo.parameterCount == 2) {
                invokeSeek(methods.seekTo, service, targetMs, true).onSuccess {
                    Log.x("SponsorBlock: seek retry ok method=${methodSignature(methods.seekTo)}, bool=true, target=$targetMs")
                }.onFailure {
                    Log.x(
                        "SponsorBlock: seek retry failed method=${methodSignature(methods.seekTo)}, " +
                            "target=$targetMs, error=${it.javaClass.name}: ${it.message}",
                    )
                    Log.e(it)
                }
            }
        }, 300L)
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
