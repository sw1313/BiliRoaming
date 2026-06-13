package me.custom.biliextras.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.UnitedScreenStateReflection
import me.custom.biliextras.utils.callMethodOrNullAs
import me.custom.biliextras.utils.ePrefs
import me.custom.biliextras.utils.findClassOrNull
import me.custom.biliextras.utils.hookMethod
import me.custom.biliextras.utils.hookAllMethods
import java.lang.reflect.Modifier
import java.util.concurrent.Executors

/**
 * Foreground auto-play of related (AI-recommended) videos for normal
 * (non-collection) videos.
 *
 * Opens the next relate as a new page via bilibili://video/{avid}, then
 * finishes the current Activity so the back stack stays clean. Pressing back
 * from the auto-opened video returns to wherever you were before (feed / search),
 * not a half-torn-down player page with missing UI.
 *
 * Flow:
 *  1. Hook UGCBackgroundPlayService.x() — for a foreground normal video, reset
 *     the anchor, enable AI mode, request relates, suspend x() while they load.
 *  2. Hook t() — read the next relate's avid and open it as a new page instead
 *     of the in-place switch; finish the source Activity.
 */
class ForegroundAutoNextHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    override fun startHook() {
        liveClassLoader = mClassLoader
        hookIsInBackground()
        hookPlayNextInternal()
        hookCompletion()
        hookPlaybackPrefetch()
        hookViewPagePrefetch()
        hookUgcDetailPauseGuard()
        hookOldPageTeardownGuard()
        Log.s("startHook: ForegroundAutoNext (${ForegroundAutoNextPrefs.enabledShortTitles().joinToString("、")}, ${ForegroundAutoNextPrefs.videoPrefsSummary()})")
    }

    private fun readRealBackground(repo: Any): Boolean = UgcBackgroundPlayReflection.readRealBackground(repo)

    private fun hookIsInBackground() {
        val repoClass =
            "com.bilibili.ship.theseus.united.page.background.PageBackgroundPlayRepository"
                .findClassOrNull(mClassLoader) ?: run {
                Log.x("ForegroundAutoNext: PageBackgroundPlayRepository not found")
                return
            }
        repoClass.hookMethod("w") { chain ->
            if (activating) true else chain.proceed()
        }
    }

    private fun hookPlayNextInternal() {
        val serviceClass =
            "com.bilibili.ship.theseus.ugc.backgroundplay.UGCBackgroundPlayService"
                .findClassOrNull(mClassLoader) ?: return
        serviceClass.hookMethod("t") { chain ->
            if (!activating) {
                return@hookMethod chain.proceed()
            }
            val service = chain.thisObject
            trackService(service)
            val repo0 = UgcBackgroundPlayReflection.repo(service)
            if (repo0 != null && runCatching { readRealBackground(repo0) }.getOrDefault(false)) {
                activating = false
                clearPendingCompletion()
                Log.x("ForegroundAutoNext: t() in real background, leaving to native AI")
                return@hookMethod chain.proceed()
            }
            if (!isEligibleForForegroundAutoNext(service)) {
                abortActivation(mClassLoader, "t() no longer eligible")
                return@hookMethod chain.proceed()
            }
            activating = false
            val ctx = UgcBackgroundPlayReflection.context(service) as? Context
            if (ctx == null) {
                resumePendingCompletion(mClassLoader)
                return@hookMethod chain.proceed()
            }
            if (ForegroundAutoNextPrefs.hasActiveVideoPickPrefs()) {
                cancelFallback()
                if (pickCompletedForGeneration == openGeneration) {
                    clearPendingCompletion()
                    return@hookMethod null
                }
                val currentAvid = getCurrentAvid(service) ?: 0L
                val refMeta = resolveRefMeta(mClassLoader, service, currentAvid)
                val cachedPlayback = takePlaybackPrefetch(currentAvid, refMeta)
                if (cachedPlayback != null) {
                    applyPreferencePick(mClassLoader, service, ctx, openGeneration, cachedPlayback)
                    return@hookMethod null
                }
                val cached = takePrefetchedPick(openGeneration)
                if (cached != null) {
                    applyPreferencePick(mClassLoader, service, ctx, openGeneration, cached)
                    return@hookMethod null
                }
                openNextWithPreferencePick(mClassLoader, service, ctx, openGeneration)
                clearPendingCompletion()
                return@hookMethod null
            }
            val avid = pickNextAiAvid(service)

            if (avid != null && avid > 0L) {
                cancelFallback()
                runCatching {
                    val fullscreen = isInFullscreen(service)
                    openRelatePage(ctx, avid, fullscreen)
                    Log.s("ForegroundAutoNext: opened relate page avid=$avid fullscreen=$fullscreen")
                }.onFailure {
                    Log.s("ForegroundAutoNext: openRelatePage failed: ${it.message}")
                    resumePendingCompletion(mClassLoader)
                    return@hookMethod chain.proceed()
                }
                clearPendingCompletion()
                return@hookMethod null
            }

            Log.x("ForegroundAutoNext: no playable relate avid, trying relate feed")
            openNextRelate(mClassLoader, service, openGeneration)
            clearPendingCompletion()
            return@hookMethod null
        }
    }

    private fun hookCompletion() {
        val serviceClass =
            "com.bilibili.ship.theseus.ugc.backgroundplay.UGCBackgroundPlayService"
                .findClassOrNull(mClassLoader) ?: run {
                Log.x("ForegroundAutoNext: UGCBackgroundPlayService not found")
                return
            }
        val continuationClass = "kotlin.coroutines.Continuation".findClassOrNull(mClassLoader)
            ?: run {
                Log.x("ForegroundAutoNext: Continuation class not found")
                return
            }

        serviceClass.hookMethod("x", continuationClass) { chain ->
            if (!isEnabled()) {
                return@hookMethod chain.proceed()
            }
            val service = chain.thisObject
            trackService(service)
            val repo = UgcBackgroundPlayReflection.repo(service) ?: return@hookMethod chain.proceed()

            if (readRealBackground(repo)) {
                activating = false
                clearPendingCompletion()
                return@hookMethod chain.proceed()
            }

            if (!ForegroundAutoNextPrefs.shouldApplyAiAutoNext(service)) {
                val scope = ForegroundAutoNextPrefs.classify(service)
                Log.x("ForegroundAutoNext: scope=$scope disabled, native handles completion")
                clearPendingCompletion()
                return@hookMethod chain.proceed()
            }

            if (!isEligibleForForegroundAutoNext(service)) {
                Log.x("ForegroundAutoNext: skip completion, not eligible foreground UGC page")
                clearPendingCompletion()
                return@hookMethod chain.proceed()
            }

            Log.s("ForegroundAutoNext: foreground completion, requesting relates")
            val gen = ++openGeneration
            pickCompletedForGeneration = -1
            prefetchedPick = null
            prefetchedGeneration = -1
            activating = true
            pendingCompletion = chain.args.firstOrNull()

            runCatching { repo.javaClass.getMethod("C").invoke(repo) }
                .onFailure { Log.x("ForegroundAutoNext: C() failed: ${it.message}") }

            runCatching {
                repo.javaClass.getMethod("G", Boolean::class.javaPrimitiveType)
                    .invoke(repo, true)
            }.onFailure { Log.x("ForegroundAutoNext: G(true) failed: ${it.message}") }

            runCatching {
                UgcBackgroundPlayReflection.invokeServiceP(service)
            }.onFailure { Log.x("ForegroundAutoNext: p() failed: ${it.message}") }

            runCatching {
                repo.javaClass.getMethod("E", Boolean::class.javaPrimitiveType)
                    .invoke(repo, true)
            }.onFailure { Log.x("ForegroundAutoNext: E(true) failed: ${it.message}") }

            runCatching {
                val k = repo.javaClass.getDeclaredField("k").apply { isAccessible = true }.get(repo)
                val hSize = (repo.javaClass.getDeclaredField("h")
                    .apply { isAccessible = true }.get(repo) as? List<*>)?.size
                val loading = repo.javaClass.getDeclaredField("d")
                    .apply { isAccessible = true }.get(repo)
                val aiMode = repo.javaClass.getMethod("n").invoke(repo)
                Log.x("ForegroundAutoNext: relate-state k=$k hSize=$hSize loading=$loading aiMode=$aiMode")
            }.onFailure { Log.x("ForegroundAutoNext: diag failed: ${it.message}") }

            val yResult = runCatching { repo.javaClass.getMethod("y").invoke(repo) }
                .onFailure { Log.x("ForegroundAutoNext: y() failed: ${it.message}") }
                .getOrNull()
            Log.x("ForegroundAutoNext: y() returned $yResult")

            cancelFallback()
            val fallbackMs = if (ForegroundAutoNextPrefs.hasActiveVideoPickPrefs()) {
                PREF_FALLBACK_MS
            } else {
                FALLBACK_MS
            }
            if (ForegroundAutoNextPrefs.hasActiveVideoPickPrefs()) {
                schedulePreferencePrefetch(mClassLoader, service, gen)
            }
            val fallback = Runnable {
                if (activating && gen == openGeneration) {
                    if (pickCompletedForGeneration == gen) return@Runnable
                    if (!isEligibleForForegroundAutoNext(service)) {
                        abortActivation(mClassLoader, "fallback not eligible")
                        return@Runnable
                    }
                    activating = false
                    Log.s("ForegroundAutoNext: AI relate timed out, querying relate feed")
                    openNextRelate(mClassLoader, service, gen)
                }
            }
            fallbackRunnable = fallback
            mainHandler.postDelayed(fallback, fallbackMs)

            val suspended = getCoroutineSuspended(mClassLoader)
            if (suspended != null) {
                Log.s("ForegroundAutoNext: x() suspended, awaiting t()")
                return@hookMethod suspended
            }

            Log.s("ForegroundAutoNext: COROUTINE_SUSPENDED not found, falling through")
            activating = false
            clearPendingCompletion()
            chain.proceed()
        }
        Log.x("ForegroundAutoNext: hooked UGCBackgroundPlayService.x()")
    }

    /** Prefetch next-video pick while the current video is still playing. */
    private fun hookPlaybackPrefetch() {
        val repoClass =
            "com.bilibili.ship.theseus.united.page.background.PageBackgroundPlayRepository"
                .findClassOrNull(mClassLoader) ?: return

        repoClass.hookAllMethods("l") { chain ->
            if (!isEnabled()) return@hookAllMethods chain.proceed()
            if (chain.args.size != 2) return@hookAllMethods chain.proceed()
            chain.proceed()
            onRepoAnchored(mClassLoader, chain.thisObject, chain.args.getOrNull(0))
            null
        }

        repoClass.hookAllMethods("z") { chain ->
            if (!isEnabled()) return@hookAllMethods chain.proceed()
            if (chain.args.isNotEmpty()) return@hookAllMethods chain.proceed()
            chain.proceed()
            val repo = chain.thisObject
            if (readRealBackground(repo)) return@hookAllMethods null
            val anchor = UgcBackgroundPlayReflection.anchor(repo) ?: return@hookAllMethods null
            val avid = anchorAvid(anchor) ?: return@hookAllMethods null
            schedulePlaybackPrefetch(mClassLoader, repo, avid, "ai-list", refresh = true)
            null
        }

        val serviceClass =
            "com.bilibili.ship.theseus.ugc.backgroundplay.UGCBackgroundPlayService"
                .findClassOrNull(mClassLoader)
        serviceClass?.hookAllMethods("p") { chain ->
            if (!isEnabled()) return@hookAllMethods chain.proceed()
            if (chain.args.isNotEmpty()) return@hookAllMethods chain.proceed()
            chain.proceed()
            val service = chain.thisObject
            trackService(service)
            val repo = UgcBackgroundPlayReflection.repo(service) ?: return@hookAllMethods null
            if (readRealBackground(repo)) return@hookAllMethods null
            val anchor = UgcBackgroundPlayReflection.anchor(repo)
            val avid = anchor?.let { anchorAvid(it) } ?: getCurrentAvid(service)
            if (avid != null && avid > 0L) {
                schedulePlaybackPrefetch(mClassLoader, repo, avid, "play-start")
            }
            null
        }
        Log.x("ForegroundAutoNext: hooked playback prefetch")
    }

    /** Cache tags/up from the view page response and prefetch while the user is watching. */
    private fun hookViewPagePrefetch() {
        val mossClass = instance.viewUniteMossClass ?: return
        val reqClass = "com.bapis.bilibili.app.viewunite.v1.ViewReq".findClassOrNull(mClassLoader) ?: return
        val viewMethod = if (instance.useNewMossFunc) "executeView" else "view"
        mossClass.hookMethod(viewMethod, reqClass) { chain ->
            if (!isEnabled()) return@hookMethod chain.proceed()
            val result = chain.proceed()
            if (!ForegroundAutoNextPrefs.hasActiveVideoPickPrefs()) return@hookMethod result
            val req = chain.args.getOrNull(0) ?: return@hookMethod result
            val aid = runCatching {
                req.javaClass.getMethod("getAid").invoke(req) as Long
            }.getOrDefault(0L)
            if (aid <= 0L) return@hookMethod result
            result?.let { reply ->
                ForegroundAutoNextVideoMeta.cacheFromViewUniteReply(aid, reply)
                val service = trackedService?.get()
                if (service != null && isEligibleForForegroundAutoNext(service)) {
                    UgcBackgroundPlayReflection.repo(service)?.let { repo ->
                        if (!readRealBackground(repo)) {
                            schedulePlaybackPrefetch(mClassLoader, repo, aid, "view-page")
                        }
                    }
                }
            }
            result
        }
        Log.x("ForegroundAutoNext: hooked view-page prefetch")
    }

    private fun onRepoAnchored(classLoader: ClassLoader, repo: Any, anchor: Any?) {
        if (readRealBackground(repo)) return
        val avid = anchor?.let { anchorAvid(it) } ?: return
        schedulePlaybackPrefetch(classLoader, repo, avid, "anchor")
    }

    private fun anchorAvid(anchor: Any): Long? = runCatching {
        anchor.javaClass.getMethod("getAvid").invoke(anchor) as Long
    }.getOrNull()?.takeIf { it > 0L }

    /** Cancel pending relate open when the UGC detail page leaves the foreground (e.g. user switches to Story). */
    private fun hookUgcDetailPauseGuard() {
        val activityClass =
            "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity".findClassOrNull(mClassLoader)
                ?: return
        activityClass.hookMethod("onPause") { chain ->
            if (activating || pendingCompletion != null || fallbackRunnable != null) {
                abortActivation(mClassLoader, "UGC detail onPause")
            }
            chain.proceed()
        }
    }

    /** Skip pause/end-page only when resuming handleCompleted on a failure path. */
    private fun hookOldPageTeardownGuard() {
        val playerClass =
            "com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer".findClassOrNull(mClassLoader)
                ?: return
        playerClass.hookMethod("pause") { chain ->
            if (suppressOldPageTeardown) null else chain.proceed()
        }

        val endPageClass =
            "com.bilibili.ship.theseus.ugc.endpage.UGCEndPageService".findClassOrNull(mClassLoader)
                ?: return
        val continuationClass = "kotlin.coroutines.Continuation".findClassOrNull(mClassLoader)
            ?: return
        endPageClass.hookMethod("k", continuationClass) { chain ->
            if (suppressOldPageTeardown) {
                unitInstance(mClassLoader)
            } else {
                chain.proceed()
            }
        }
    }

    companion object {
        const val PREF_KEY = "foreground_auto_next"

        @Volatile
        private var liveClassLoader: ClassLoader? = null

        fun requireClassLoader(): ClassLoader =
            liveClassLoader ?: error("ForegroundAutoNextHook not initialized")

        fun isEnabled(): Boolean = ePrefs.getBoolean(PREF_KEY, false)

        /** AutoFullscreen (2): enters fullscreen but keeps back-to-halfscreen handler. ForcedInFullscreen (1) disables it. */
        private const val FULLSCREEN_MODE_AUTO = "2"

        const val FALLBACK_MS = 2500L
        private const val PREF_FALLBACK_MS = 800L

        @Volatile
        var pickCompletedForGeneration = -1

        @Volatile
        private var prefetchedPick: RelateCandidate? = null

        @Volatile
        private var prefetchedGeneration = -1

        @Volatile
        private var playbackPrefetchSourceAvid: Long = -1L

        @Volatile
        private var playbackPrefetchPick: RelateCandidate? = null

        @Volatile
        private var playbackPrefetchLoadingAvid: Long = -1L

        @Volatile
        private var playbackPrefetchRefUpMid: Long? = null

        private val playbackPrefetchLock = Any()

        @Volatile
        private var trackedService: java.lang.ref.WeakReference<Any>? = null

        fun trackService(service: Any) {
            trackedService = java.lang.ref.WeakReference(service)
        }

        @Volatile
        var activating = false

        @Volatile
        var fallbackRunnable: Runnable? = null

        @Volatile
        var pendingCompletion: Any? = null

        @Volatile
        var suppressOldPageTeardown = false

        @Volatile
        var cachedSuspended: Any? = null

        /** Bumped when activation is aborted or a new completion starts; stale async opens are dropped. */
        @Volatile
        var openGeneration = 0

        val mainHandler = Handler(Looper.getMainLooper())

        private val netExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "biliextras-fg-autonext-net").apply { isDaemon = true }
        }

        fun readRealBackground(repo: Any): Boolean = UgcBackgroundPlayReflection.readRealBackground(repo)

        fun abortActivation(classLoader: ClassLoader, reason: String) {
            if (!activating && pendingCompletion == null && fallbackRunnable == null) return
            Log.x("ForegroundAutoNext: abort ($reason)")
            openGeneration++
            activating = false
            pickCompletedForGeneration = -1
            prefetchedPick = null
            prefetchedGeneration = -1
            clearPlaybackPrefetch()
            cancelFallback()
            if (pendingCompletion != null) {
                resumePendingCompletion(classLoader)
            }
        }

        fun isEligibleForForegroundAutoNext(service: Any): Boolean {
            val repo = UgcBackgroundPlayReflection.repo(service) ?: return false
            if (readRealBackground(repo)) return false
            val ctx = UgcBackgroundPlayReflection.context(service) as? Context ?: return false
            return isEligibleForegroundContext(ctx)
        }

        fun isEligibleForegroundContext(ctx: Context): Boolean {
            val activity = findActivity(ctx) ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            if (!isActivityResumed(activity)) return false
            if (hasResumedStoryFragment(activity)) return false
            if (!isUgcDetailActivity(activity)) return false
            return true
        }

        private fun findActivity(ctx: Context): Activity? {
            var c: Context? = ctx
            while (c != null) {
                when (c) {
                    is Activity -> return c
                    is ContextWrapper -> c = c.baseContext
                    else -> return null
                }
            }
            return null
        }

        private fun isActivityResumed(activity: Activity): Boolean =
            runCatching {
                activity.javaClass.getMethod("isResumed").invoke(activity) as Boolean
            }.getOrElse { true }

        private fun isUgcDetailActivity(activity: Activity): Boolean {
            val name = activity.javaClass.name
            return name.contains("UnitedBizDetailsActivity") || name.contains("VideoDetailsActivity")
        }

        private fun hasResumedStoryFragment(activity: Activity): Boolean = runCatching {
            val getFm = activity.javaClass.methods.firstOrNull {
                it.name == "getSupportFragmentManager" && it.parameterCount == 0
            } ?: return@runCatching false
            val fm = getFm.invoke(activity) ?: return@runCatching false
            val fragments = fm.javaClass.methods.firstOrNull { it.name == "getFragments" }
                ?.invoke(fm) as? List<*> ?: return@runCatching false
            fragments.any { frag ->
                frag ?: return@any false
                val resumed = frag.javaClass.methods.firstOrNull { it.name == "isResumed" }
                    ?.invoke(frag) as? Boolean ?: false
                resumed && frag.javaClass.name.contains("story", ignoreCase = true)
            }
        }.getOrDefault(false)

        fun cancelFallback() {
            fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
            fallbackRunnable = null
        }

        fun clearPendingCompletion() {
            pendingCompletion = null
        }

        /**
         * Open the relate as a new page and remove the current Activity from the stack
         * so back navigation is clean (no broken half-completed page underneath).
         */
        fun openRelatePage(context: Context, avid: Long, fullscreen: Boolean = false) {
            openVideo(context, avid, fullscreen)
            if (context is Activity) {
                mainHandler.post { finishSourceActivity(context) }
            }
        }

        fun resumePendingCompletion(classLoader: ClassLoader) {
            val cont = pendingCompletion ?: return
            pendingCompletion = null
            suppressOldPageTeardown = true
            runCatching {
                val unitClass = Class.forName("kotlin.Unit", false, classLoader)
                val unit = unitClass.getField("INSTANCE").get(null)
                val resultClass = Class.forName("kotlin.Result", false, classLoader)
                val result = resultClass.getMethod("success", Any::class.java).invoke(null, unit)
                var cls: Class<*>? = cont.javaClass
                while (cls != null) {
                    try {
                        val m = cls.getDeclaredMethod("resumeWith", resultClass)
                        m.isAccessible = true
                        m.invoke(cont, result)
                        Log.s("ForegroundAutoNext: resumed handleCompleted")
                        return
                    } catch (_: NoSuchMethodException) {
                        cls = cls.superclass
                    }
                }
                error("resumeWith not found on ${cont.javaClass.name}")
            }.onFailure {
                Log.s("ForegroundAutoNext: resume failed: ${it.message}")
                Log.e(it)
            }.also {
                suppressOldPageTeardown = false
            }
        }

        fun unitInstance(classLoader: ClassLoader): Any? = runCatching {
            Class.forName("kotlin.Unit", false, classLoader).getField("INSTANCE").get(null)
        }.getOrNull()

        fun hasNextEpisode(service: Any): Boolean {
            return runCatching {
                val episodeRepo = service.javaClass.getDeclaredField("c")
                    .apply { isAccessible = true }.get(service)
                val playbackRepo = service.javaClass.getDeclaredField("d")
                    .apply { isAccessible = true }.get(service)
                val current = playbackRepo.javaClass.getMethod("w").invoke(playbackRepo)

                val curId = runCatching {
                    val avid = current?.javaClass?.getMethod("b")?.invoke(current)
                    val cid = current?.javaClass?.getMethod("d")?.invoke(current)
                    "avid=$avid cid=$cid"
                }.getOrDefault("?")
                val listSize = runCatching {
                    (episodeRepo.javaClass.getMethod("g").invoke(episodeRepo) as? List<*>)?.size
                }.getOrNull()

                val jMethod = episodeRepo.javaClass.declaredMethods
                    .firstOrNull { it.name == "j" && it.parameterCount == 1 }
                    ?.apply { isAccessible = true } ?: return@runCatching false
                val next = jMethod.invoke(episodeRepo, current)
                Log.x("ForegroundAutoNext: hasNextEpisode? current[$curId] listSize=$listSize next=${next != null}")
                next != null
            }.getOrDefault(false)
        }

        fun openNextRelate(classLoader: ClassLoader, service: Any, generation: Int = openGeneration) {
            cancelFallback()
            activating = false
            val ctx = UgcBackgroundPlayReflection.context(service) as? Context
            if (ctx == null) {
                Log.s("ForegroundAutoNext: no context for relate feed")
                resumePendingCompletion(classLoader)
                return
            }
            if (ForegroundAutoNextPrefs.hasActiveVideoPickPrefs()) {
                if (pickCompletedForGeneration == generation) return
                val currentAvid = getCurrentAvid(service) ?: 0L
                val refMeta = resolveRefMeta(classLoader, service, currentAvid)
                val cachedPlayback = takePlaybackPrefetch(currentAvid, refMeta)
                if (cachedPlayback != null) {
                    val ctx = UgcBackgroundPlayReflection.context(service) as? Context ?: return
                    applyPreferencePick(classLoader, service, ctx, generation, cachedPlayback)
                    return
                }
                val cached = takePrefetchedPick(generation)
                if (cached != null) {
                    applyPreferencePick(classLoader, service, ctx, generation, cached)
                    return
                }
                openNextWithPreferencePick(classLoader, service, ctx, generation)
                return
            }
            val avid = getCurrentAvid(service)
            if (avid == null || avid <= 0L) {
                Log.s("ForegroundAutoNext: no current avid for relate feed")
                resumePendingCompletion(classLoader)
                return
            }
            val fullscreen = isInFullscreen(service)
            netExecutor.execute {
                val uri = runCatching { requestRelateUri(classLoader, avid) }
                    .onFailure { Log.s("ForegroundAutoNext: relate feed failed: ${it.message}") }
                    .getOrNull()
                mainHandler.post {
                    if (generation != openGeneration) {
                        Log.x("ForegroundAutoNext: stale relate feed open, cancelled")
                        resumePendingCompletion(classLoader)
                        return@post
                    }
                    if (!isEligibleForForegroundAutoNext(service)) {
                        Log.x("ForegroundAutoNext: relate feed open skipped, not eligible")
                        resumePendingCompletion(classLoader)
                        return@post
                    }
                    if (!uri.isNullOrBlank()) {
                        runCatching {
                            openUri(ctx, uri, fullscreen)
                            finishSourceActivity(ctx)
                            Log.s("ForegroundAutoNext: opened relate feed uri=$uri fullscreen=$fullscreen")
                        }.onFailure {
                            Log.s("ForegroundAutoNext: openUri failed: ${it.message}")
                            resumePendingCompletion(classLoader)
                        }
                        clearPendingCompletion()
                    } else {
                        Log.s("ForegroundAutoNext: relate feed empty for avid=$avid")
                        resumePendingCompletion(classLoader)
                    }
                }
            }
        }

        private data class RelateCandidate(
            val avid: Long,
            val uri: String?,
            val portrait: Boolean?,
            val upMid: Long?,
            val tagNames: Set<String>,
        )

        private fun openNextWithPreferencePick(
            classLoader: ClassLoader,
            service: Any,
            ctx: Context,
            generation: Int = openGeneration,
        ) {
            val currentAvid = getCurrentAvid(service)
            if (currentAvid == null || currentAvid <= 0L) {
                Log.s("ForegroundAutoNext: no current avid for preference pick")
                resumePendingCompletion(classLoader)
                return
            }
            val refMeta = resolveRefMeta(classLoader, service, currentAvid)
            val cached = takePlaybackPrefetch(currentAvid, refMeta)
            if (cached != null) {
                applyPreferencePick(classLoader, service, ctx, generation, cached)
                return
            }
            val fullscreen = isInFullscreen(service)
            netExecutor.execute {
                val picked = runPreferencePick(classLoader, service, currentAvid)
                mainHandler.post {
                    if (generation != openGeneration || pickCompletedForGeneration == generation) {
                        Log.x("ForegroundAutoNext: stale preference pick, cancelled")
                        resumePendingCompletion(classLoader)
                        return@post
                    }
                    if (!isEligibleForForegroundAutoNext(service)) {
                        Log.x("ForegroundAutoNext: preference pick skipped, not eligible")
                        resumePendingCompletion(classLoader)
                        return@post
                    }
                    if (picked == null) {
                        Log.s("ForegroundAutoNext: preference pick found nothing for avid=$currentAvid")
                        resumePendingCompletion(classLoader)
                        return@post
                    }
                    applyPreferencePick(classLoader, service, ctx, generation, picked, fullscreen)
                }
            }
        }

        private fun schedulePlaybackPrefetch(
            classLoader: ClassLoader,
            repo: Any,
            sourceAvid: Long,
            reason: String,
            refresh: Boolean = false,
        ) {
            if (!isEnabled()) return
            if (!ForegroundAutoNextPrefs.hasActiveVideoPickPrefs()) return
            synchronized(playbackPrefetchLock) {
                if (!refresh &&
                    playbackPrefetchSourceAvid == sourceAvid &&
                    playbackPrefetchPick != null
                ) {
                    return
                }
                if (playbackPrefetchLoadingAvid == sourceAvid) return
                playbackPrefetchLoadingAvid = sourceAvid
            }
            netExecutor.execute {
                val service = trackedService?.get()
                val picked = runPreferencePick(classLoader, repo, service, sourceAvid)
                val refMeta = buildReferenceMeta(classLoader, repo, service, sourceAvid)
                synchronized(playbackPrefetchLock) {
                    playbackPrefetchLoadingAvid = -1L
                    if (picked != null && isPickValidForPrefs(picked, refMeta)) {
                        playbackPrefetchSourceAvid = sourceAvid
                        playbackPrefetchPick = picked
                        playbackPrefetchRefUpMid = refMeta.upMid
                        Log.s(
                            "ForegroundAutoNext: playback prefetch ($reason) " +
                                "source=$sourceAvid refUp=${refMeta.upMid} -> ${picked.avid} upMid=${picked.upMid}",
                        )
                    } else if (picked != null) {
                        Log.x(
                            "ForegroundAutoNext: playback prefetch ($reason) rejected " +
                                "avid=${picked.avid} upMid=${picked.upMid} refUp=${refMeta.upMid}",
                        )
                    }
                }
            }
        }

        private fun takePlaybackPrefetch(
            sourceAvid: Long,
            refMeta: ForegroundAutoNextVideoMeta.Meta?,
        ): RelateCandidate? {
            if (sourceAvid <= 0L || refMeta == null) return null
            synchronized(playbackPrefetchLock) {
                if (playbackPrefetchSourceAvid != sourceAvid) return null
                val pick = playbackPrefetchPick ?: return null
                if (refMeta.upMid != null && playbackPrefetchRefUpMid != null &&
                    refMeta.upMid != playbackPrefetchRefUpMid
                ) {
                    Log.x("ForegroundAutoNext: playback prefetch stale refUp, discarded")
                    playbackPrefetchPick = null
                    playbackPrefetchSourceAvid = -1L
                    playbackPrefetchRefUpMid = null
                    return null
                }
                if (!isPickValidForPrefs(pick, refMeta)) {
                    Log.x(
                        "ForegroundAutoNext: playback prefetch failed validation " +
                            "avid=${pick.avid} upMid=${pick.upMid} refUp=${refMeta.upMid}",
                    )
                    playbackPrefetchPick = null
                    playbackPrefetchSourceAvid = -1L
                    playbackPrefetchRefUpMid = null
                    return null
                }
                playbackPrefetchPick = null
                playbackPrefetchSourceAvid = -1L
                playbackPrefetchRefUpMid = null
                return pick
            }
        }

        private fun isPickValidForPrefs(
            pick: RelateCandidate,
            refMeta: ForegroundAutoNextVideoMeta.Meta,
        ): Boolean =
            matchesPreference(pick, refMeta, tagStrict = true) ||
                (ForegroundAutoNextPrefs.tagPref() != ForegroundAutoNextPrefs.TagPref.NONE &&
                    matchesPreference(pick, refMeta, tagStrict = false))

        private fun clearPlaybackPrefetch() {
            synchronized(playbackPrefetchLock) {
                playbackPrefetchSourceAvid = -1L
                playbackPrefetchPick = null
                playbackPrefetchLoadingAvid = -1L
                playbackPrefetchRefUpMid = null
            }
        }

        private fun resolveRefMeta(
            classLoader: ClassLoader,
            service: Any,
            currentAvid: Long,
        ): ForegroundAutoNextVideoMeta.Meta? {
            if (currentAvid <= 0L) return null
            val repo = UgcBackgroundPlayReflection.repo(service) ?: return null
            return buildReferenceMeta(classLoader, repo, service, currentAvid)
        }

        private fun schedulePreferencePrefetch(classLoader: ClassLoader, service: Any, generation: Int) {
            val currentAvid = getCurrentAvid(service) ?: return
            val refMeta = resolveRefMeta(classLoader, service, currentAvid)
            val cached = takePlaybackPrefetch(currentAvid, refMeta)
            if (cached != null) {
                mainHandler.post {
                    if (generation != openGeneration || pickCompletedForGeneration == generation) return@post
                    if (!isEligibleForForegroundAutoNext(service)) return@post
                    val ctx = UgcBackgroundPlayReflection.context(service) as? Context ?: return@post
                    Log.s("ForegroundAutoNext: completion used playback prefetch avid=${cached.avid}")
                    applyPreferencePick(classLoader, service, ctx, generation, cached)
                }
                return
            }
            netExecutor.execute {
                if (generation != openGeneration) return@execute
                val picked = runPreferencePick(classLoader, service, currentAvid) ?: return@execute
                mainHandler.post {
                    if (generation != openGeneration || pickCompletedForGeneration == generation) return@post
                    prefetchedPick = picked
                    prefetchedGeneration = generation
                    if (!activating || generation != openGeneration) return@post
                    if (!isEligibleForForegroundAutoNext(service)) return@post
                    val ctx = UgcBackgroundPlayReflection.context(service) as? Context ?: return@post
                    Log.s("ForegroundAutoNext: prefetch ready, opening early avid=${picked.avid}")
                    applyPreferencePick(classLoader, service, ctx, generation, picked)
                }
            }
        }

        private fun takePrefetchedPick(generation: Int): RelateCandidate? {
            if (prefetchedGeneration != generation) return null
            val pick = prefetchedPick ?: return null
            prefetchedPick = null
            prefetchedGeneration = -1
            return pick
        }

        private fun applyPreferencePick(
            classLoader: ClassLoader,
            service: Any,
            ctx: Context,
            generation: Int,
            picked: RelateCandidate,
            fullscreen: Boolean = isInFullscreen(service),
        ) {
            if (generation != openGeneration || pickCompletedForGeneration == generation) return
            pickCompletedForGeneration = generation
            activating = false
            cancelFallback()
            runCatching {
                if (!picked.uri.isNullOrBlank()) {
                    openUri(ctx, picked.uri, fullscreen)
                    finishSourceActivity(ctx)
                } else {
                    openRelatePage(ctx, picked.avid, fullscreen)
                }
                Log.s(
                    "ForegroundAutoNext: preference pick avid=${picked.avid} " +
                        "portrait=${picked.portrait} upMid=${picked.upMid} tags=${picked.tagNames.size} " +
                        "prefs=${ForegroundAutoNextPrefs.videoPrefsSummary()}",
                )
            }.onFailure {
                Log.s("ForegroundAutoNext: preference open failed: ${it.message}")
                pickCompletedForGeneration = -1
                resumePendingCompletion(classLoader)
                return
            }
            clearPendingCompletion()
        }

        private fun runPreferencePick(
            classLoader: ClassLoader,
            service: Any,
            currentAvid: Long,
        ): RelateCandidate? {
            val repo = UgcBackgroundPlayReflection.repo(service) ?: return null
            return runPreferencePick(classLoader, repo, service, currentAvid)
        }

        private fun runPreferencePick(
            classLoader: ClassLoader,
            repo: Any,
            service: Any?,
            currentAvid: Long,
        ): RelateCandidate? {
            val continuous = runCatching {
                requestContinuousPlayCandidates(classLoader, repo, service)
            }.onFailure {
                Log.s("ForegroundAutoNext: ContinuousPlay failed: ${it.message}")
            }.getOrDefault(emptyList())
            val feed = continuous.ifEmpty {
                runCatching { requestRelateCandidates(classLoader, currentAvid) }
                    .onFailure { Log.s("ForegroundAutoNext: relate feed failed: ${it.message}") }
                    .getOrDefault(emptyList())
            }
            val aiAvids = collectAiAvidsFast(repo, service)
            val source = if (continuous.isNotEmpty()) "ContinuousPlay" else "RelatesFeed"
            val refMeta = buildReferenceMeta(classLoader, repo, service, currentAvid)
            Log.x(
                "ForegroundAutoNext: pick source=$source ai=${aiAvids.size} feed=${feed.size} " +
                    "refUp=${refMeta.upMid} refTags=${refMeta.tagNames.size}",
            )
            return when {
                !ForegroundAutoNextPrefs.needsNetworkMeta() ->
                    pickByOrientationOnly(aiAvids, feed, refMeta.portrait)
                else ->
                    pickByPreferences(aiAvids, feed, refMeta)
            }
        }

        private fun requestContinuousPlayCandidates(
            classLoader: ClassLoader,
            repo: Any,
            service: Any? = null,
        ): List<RelateCandidate> =
            ForegroundAutoNextContinuousPlay.fetchCandidates(classLoader, repo, service).map { c ->
                RelateCandidate(c.avid, c.uri, c.portrait, c.upMid, c.tagNames)
            }

        private fun buildReferenceMeta(
            classLoader: ClassLoader,
            repo: Any,
            service: Any?,
            currentAvid: Long,
        ): ForegroundAutoNextVideoMeta.Meta {
            val cached = ForegroundAutoNextVideoMeta.cached(currentAvid)
            val anchorMid = UgcBackgroundPlayReflection.anchor(repo)
                ?.callMethodOrNullAs<Long>("getMid")
                ?.takeIf { it > 0L }
            val needUp = ForegroundAutoNextPrefs.upPref() != ForegroundAutoNextPrefs.UpPref.NONE
            val needTags = ForegroundAutoNextPrefs.tagPref() != ForegroundAutoNextPrefs.TagPref.NONE
            val needsFetch = (needUp && cached?.upMid == null && anchorMid == null) ||
                (needTags && cached?.tagNames.isNullOrEmpty())
            val fetched = if (needsFetch) {
                ForegroundAutoNextVideoMeta.fetch(classLoader, currentAvid)
            } else {
                null
            }
            val portrait = when (ForegroundAutoNextPrefs.orientation()) {
                ForegroundAutoNextPrefs.Orientation.MATCH ->
                    service?.let { ForegroundAutoNextPrefs.getCurrentVideoPortrait(it) }
                        ?: cached?.portrait ?: fetched?.portrait
                ForegroundAutoNextPrefs.Orientation.PORTRAIT -> true
                ForegroundAutoNextPrefs.Orientation.LANDSCAPE -> false
                ForegroundAutoNextPrefs.Orientation.NONE -> cached?.portrait ?: fetched?.portrait
            }
            return ForegroundAutoNextVideoMeta.Meta(
                upMid = cached?.upMid ?: fetched?.upMid ?: anchorMid,
                tagNames = cached?.tagNames ?: fetched?.tagNames.orEmpty(),
                portrait = portrait,
            )
        }

        /** Orientation-only pick uses relate-feed dimensions; no View API storm. */
        private fun pickByOrientationOnly(
            aiAvidsInOrder: List<Long>,
            feed: List<RelateCandidate>,
            preferPortrait: Boolean?,
        ): RelateCandidate? {
            if (preferPortrait == null) {
                val fallbackAvid = aiAvidsInOrder.firstOrNull { !BlockChargingVideoHook.shouldBlockAvid(it) }
                if (fallbackAvid != null) {
                    return feed.firstOrNull { it.avid == fallbackAvid }
                        ?: RelateCandidate(fallbackAvid, null, null, null, emptySet())
                }
                return feed.firstOrNull { !BlockChargingVideoHook.shouldBlockAvid(it.avid) }
            }
            val portraitByAvid = feed.associate { it.avid to it.portrait }
            val uriByAvid = feed.associate { it.avid to it.uri }
            val feedByAvid = feed.associateBy { it.avid }
            for (avid in aiAvidsInOrder) {
                if (BlockChargingVideoHook.shouldBlockAvid(avid)) continue
                if (portraitByAvid[avid] == preferPortrait) {
                    return feedByAvid[avid] ?: RelateCandidate(avid, uriByAvid[avid], preferPortrait, null, emptySet())
                }
            }
            for (candidate in feed) {
                if (BlockChargingVideoHook.shouldBlockAvid(candidate.avid)) continue
                if (candidate.portrait == preferPortrait) return candidate
            }
            val fallbackAvid = aiAvidsInOrder.firstOrNull { !BlockChargingVideoHook.shouldBlockAvid(it) }
            if (fallbackAvid != null) {
                return feedByAvid[fallbackAvid] ?: RelateCandidate(
                    fallbackAvid, uriByAvid[fallbackAvid], portraitByAvid[fallbackAvid], null, emptySet(),
                )
            }
            return feed.firstOrNull { !BlockChargingVideoHook.shouldBlockAvid(it.avid) }
        }

        private fun pickByPreferences(
            aiAvidsInOrder: List<Long>,
            feed: List<RelateCandidate>,
            refMeta: ForegroundAutoNextVideoMeta.Meta,
        ): RelateCandidate? {
            findPreferenceMatch(aiAvidsInOrder, feed, refMeta, tagStrict = true)?.let { return it }
            if (ForegroundAutoNextPrefs.tagPref() != ForegroundAutoNextPrefs.TagPref.NONE) {
                findPreferenceMatch(aiAvidsInOrder, feed, refMeta, tagStrict = false)?.let { return it }
            }
            Log.x(
                "ForegroundAutoNext: no preference match refUp=${refMeta.upMid} " +
                    "feed=${feed.size} ai=${aiAvidsInOrder.size}",
            )
            return null
        }

        private fun findPreferenceMatch(
            aiAvidsInOrder: List<Long>,
            feed: List<RelateCandidate>,
            refMeta: ForegroundAutoNextVideoMeta.Meta,
            tagStrict: Boolean,
        ): RelateCandidate? {
            val feedByAvid = feed.associateBy { it.avid }
            fun candidateFor(avid: Long): RelateCandidate =
                feedByAvid[avid] ?: RelateCandidate(avid, null, null, null, emptySet())

            for (avid in aiAvidsInOrder) {
                if (BlockChargingVideoHook.shouldBlockAvid(avid)) continue
                val candidate = candidateFor(avid)
                if (matchesPreference(candidate, refMeta, tagStrict)) return candidate
            }
            for (item in feed) {
                if (BlockChargingVideoHook.shouldBlockAvid(item.avid)) continue
                if (matchesPreference(item, refMeta, tagStrict)) return item
            }
            return null
        }

        private fun matchesPreference(
            candidate: RelateCandidate,
            refMeta: ForegroundAutoNextVideoMeta.Meta,
            tagStrict: Boolean = true,
        ): Boolean {
            when (ForegroundAutoNextPrefs.orientation()) {
                ForegroundAutoNextPrefs.Orientation.PORTRAIT ->
                    if (candidate.portrait != true) return false
                ForegroundAutoNextPrefs.Orientation.LANDSCAPE ->
                    if (candidate.portrait != false) return false
                ForegroundAutoNextPrefs.Orientation.MATCH -> {
                    val ref = refMeta.portrait
                    if (ref != null && candidate.portrait != null && candidate.portrait != ref) return false
                }
                ForegroundAutoNextPrefs.Orientation.NONE -> Unit
            }
            when (ForegroundAutoNextPrefs.upPref()) {
                ForegroundAutoNextPrefs.UpPref.SAME -> {
                    val refMid = refMeta.upMid ?: return false
                    val mid = candidate.upMid ?: return false
                    if (mid != refMid) return false
                }
                ForegroundAutoNextPrefs.UpPref.DIFFERENT -> {
                    val refMid = refMeta.upMid ?: return false
                    val mid = candidate.upMid ?: return false
                    if (mid == refMid) return false
                }
                ForegroundAutoNextPrefs.UpPref.NONE -> Unit
            }
            when (ForegroundAutoNextPrefs.tagPref()) {
                ForegroundAutoNextPrefs.TagPref.SAME -> {
                    if (!tagStrict) return true
                    if (refMeta.tagNames.isEmpty() || candidate.tagNames.isEmpty()) return false
                    if (candidate.tagNames.intersect(refMeta.tagNames).isEmpty()) return false
                }
                ForegroundAutoNextPrefs.TagPref.DIFFERENT -> {
                    if (!tagStrict) return true
                    if (refMeta.tagNames.isNotEmpty() && candidate.tagNames.isNotEmpty() &&
                        candidate.tagNames.intersect(refMeta.tagNames).isNotEmpty()
                    ) {
                        return false
                    }
                }
                ForegroundAutoNextPrefs.TagPref.NONE -> Unit
            }
            return true
        }

        private fun finishSourceActivity(ctx: Context) {
            if (ctx is Activity && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.finish()
            }
        }

        private fun getCurrentAvid(service: Any): Long? = UgcBackgroundPlayReflection.currentAvid(service)

        private fun collectAiAvidsFast(repo: Any, service: Any? = null): List<Long> =
            collectRawAiAvids(repo, service).filter { !BlockChargingVideoHook.shouldBlockAvid(it) }

        private fun collectAiAvids(repo: Any, service: Any? = null): List<Long> {
            val raw = collectRawAiAvids(repo, service)
            if (raw.isEmpty()) return emptyList()
            BlockChargingVideoHook.resolveAvidsSync(raw)
            return raw.filter { !BlockChargingVideoHook.shouldBlockAvid(it) }
        }

        private fun collectRawAiAvids(repo: Any, service: Any? = null): List<Long> {
            val fromRepo = UgcBackgroundPlayReflection.rawAiAvidsFromRepo(repo)
            if (fromRepo.isNotEmpty()) return fromRepo
            return service?.let { UgcBackgroundPlayReflection.rawAiAvids(it) }.orEmpty()
        }

        private fun collectAiAvids(service: Any): List<Long> {
            val repo = UgcBackgroundPlayReflection.repo(service) ?: return emptyList()
            return collectAiAvids(repo, service)
        }

        private fun collectRawAiAvids(service: Any): List<Long> =
            UgcBackgroundPlayReflection.rawAiAvids(service)

        private fun pickNextAiAvid(service: Any): Long? = collectAiAvids(service).firstOrNull()

        private fun requestRelateCandidates(classLoader: ClassLoader, avid: Long): List<RelateCandidate> {
            val viewMossClass = "com.bapis.bilibili.app.viewunite.v1.ViewMoss"
                .findClassOrNull(classLoader) ?: return emptyList()
            val reqClass = "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReq"
                .findClassOrNull(classLoader) ?: return emptyList()
            val builder = reqClass.getMethod("newBuilder").invoke(null)
            builder.javaClass.getMethod("setAid", Long::class.javaPrimitiveType).invoke(builder, avid)
            val req = builder.javaClass.getMethod("build").invoke(builder)
            val moss = runCatching {
                viewMossClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.getOrNull() ?: return emptyList()
            val reply = viewMossClass.getMethod("executeRelatesFeed", reqClass).invoke(moss, req)
                ?: return emptyList()
            val relates = runCatching {
                reply.javaClass.getMethod("getRelatesList").invoke(reply) as? List<*>
            }.getOrNull().orEmpty()
            BlockChargingVideoHook.resolveAvidsSync(
                relates.mapNotNull { card -> card?.let { BlockChargingVideoHook.uniteCardAid(it) } },
            )
            return parseRelateCandidates(relates)
        }

        private fun parseRelateCandidates(relates: List<*>): List<RelateCandidate> =
            relates.mapNotNull { card -> card?.let { parseRelateCard(it) } }

        private fun parseRelateCard(card: Any): RelateCandidate? {
            val hasAv = runCatching {
                card.javaClass.getMethod("hasAv").invoke(card) as Boolean
            }.getOrDefault(false)
            if (!hasAv) return null
            if (BlockChargingVideoHook.shouldBlockUniteRelateCard(card)) return null
            val isPromo = runCatching {
                card.javaClass.getMethod("hasCmStock").invoke(card) as Boolean
            }.getOrDefault(false)
            if (isPromo) return null
            val basic = card.javaClass.getMethod("getBasicInfo").invoke(card) ?: return null
            val uri = basic.javaClass.getMethod("getUri").invoke(basic) as? String
            if (uri.isNullOrBlank()) return null
            val avid = avidFromUri(uri) ?: return null
            if (BlockChargingVideoHook.shouldBlockAvid(avid)) return null
            val portrait = runCatching {
                val av = card.javaClass.getMethod("getAv").invoke(card) ?: return@runCatching null
                if (av.javaClass.getMethod("hasDimension").invoke(av) as Boolean) {
                    isPortraitDimension(av.javaClass.getMethod("getDimension").invoke(av)!!)
                } else {
                    null
                }
            }.getOrNull()
            val upMid = ForegroundAutoNextVideoMeta.parseUpMidFromRelateCard(card)
            return RelateCandidate(avid, uri, portrait, upMid, emptySet())
        }

        private fun avidFromUri(uri: String): Long? {
            val path = Uri.parse(uri).path ?: return null
            return path.split("/").lastOrNull()?.toLongOrNull()
        }

        private fun isPortraitDimension(dimension: Any): Boolean =
            ForegroundAutoNextVideoMeta.isPortraitDimension(dimension)

        private fun requestRelateUri(classLoader: ClassLoader, avid: Long): String? {
            return requestRelateCandidates(classLoader, avid).firstOrNull()?.uri
        }

        fun openVideo(context: Context, avid: Long, fullscreen: Boolean = false) {
            val uri = if (fullscreen) {
                "bilibili://video/$avid?fullscreen_mode=$FULLSCREEN_MODE_AUTO"
            } else {
                "bilibili://video/$avid"
            }
            openUri(context, uri)
        }

        fun openUri(context: Context, uri: String, fullscreen: Boolean = false) {
            val targetUri = if (fullscreen) withFullscreenMode(uri) else uri
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUri)).apply {
                setPackage(context.packageName)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /** Whether the current UGC page is in fullscreen (whole-scene) mode. */
        fun isInFullscreen(service: Any): Boolean {
            val ctx = UgcBackgroundPlayReflection.context(service)
            val roots = listOfNotNull(service, ctx)
            for (root in roots) {
                UnitedScreenStateReflection.findScreenStateRepo(root)?.let { repo ->
                    val fullscreen = UnitedScreenStateReflection.readFullscreen(repo)
                    Log.x("ForegroundAutoNext: isInFullscreen=$fullscreen (screenstate)")
                    return fullscreen
                }
            }
            for (root in roots) {
                findPlayerContainer(root)?.let { container ->
                    val fullscreen = runCatching {
                        val render = container.javaClass.getMethod("getRenderContainerService")
                            .invoke(container)
                        render.javaClass.getMethod("isInWholeSceneMode").invoke(render) as Boolean
                    }.getOrDefault(false)
                    Log.x("ForegroundAutoNext: isInFullscreen=$fullscreen (wholeScene)")
                    return fullscreen
                }
            }
            return false
        }

        private fun withFullscreenMode(uri: String): String {
            val parsed = Uri.parse(uri)
            val builder = parsed.buildUpon().clearQuery()
            for (name in parsed.queryParameterNames) {
                if (name == "fullscreen_mode") continue
                for (value in parsed.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, value)
                }
            }
            builder.appendQueryParameter("fullscreen_mode", FULLSCREEN_MODE_AUTO)
            return builder.build().toString()
        }

        private fun findPlayerContainer(root: Any, maxDepth: Int = 4): Any? {
            val visited = mutableSetOf<Int>()
            val queue = ArrayDeque<Pair<Any, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty()) {
                val (obj, depth) = queue.removeFirst()
                val id = System.identityHashCode(obj)
                if (!visited.add(id)) continue
                if (obj.javaClass.name == "tv.danmaku.biliplayerv2.PlayerContainer") return obj
                if (depth >= maxDepth) continue
                for (field in obj.javaClass.declaredFields) {
                    runCatching {
                        field.isAccessible = true
                        val value = field.get(obj) ?: return@runCatching
                        if (fieldShouldTraverse(value)) {
                            queue.add(value to depth + 1)
                        }
                    }
                }
            }
            return null
        }

        private fun fieldShouldTraverse(value: Any): Boolean {
            if (value is String || value is Number || value is Boolean || value is Char) return false
            if (value is Class<*>) return false
            val name = value.javaClass.name
            if (name.startsWith("java.") || name.startsWith("kotlin.") ||
                name.startsWith("kotlinx.") || name.startsWith("android.") && value !is Activity
            ) {
                return false
            }
            return true
        }

        fun getCoroutineSuspended(classLoader: ClassLoader): Any? {
            cachedSuspended?.let { return it }
            val cls = "kotlin.coroutines.intrinsics.IntrinsicsKt".findClassOrNull(classLoader)
                ?: return null
            for (m in cls.declaredMethods) {
                if (Modifier.isStatic(m.modifiers) && m.parameterCount == 0) {
                    m.isAccessible = true
                    val result = runCatching { m.invoke(null) }.getOrNull() ?: continue
                    if (result.javaClass.isEnum) {
                        cachedSuspended = result
                        return result
                    }
                }
            }
            return null
        }
    }
}
