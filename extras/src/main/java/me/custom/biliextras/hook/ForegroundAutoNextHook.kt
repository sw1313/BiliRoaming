package me.custom.biliextras.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import me.custom.biliextras.utils.findClassOrNull
import me.custom.biliextras.utils.hookMethod
import java.lang.reflect.Modifier

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
        if (!ePrefs.getBoolean(PREF_KEY, false)) return
        hookIsInBackground()
        hookPlayNextInternal()
        hookCompletion()
        hookUgcDetailPauseGuard()
        hookOldPageTeardownGuard()
        Log.s("startHook: ForegroundAutoNext (${ForegroundAutoNextPrefs.enabledShortTitles().joinToString("、")}, ${ForegroundAutoNextPrefs.orientation().title})")
    }

    private fun readRealBackground(repo: Any): Boolean = Companion.readRealBackground(repo)

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
            val repo0 = runCatching {
                service.javaClass.getDeclaredField("b").apply { isAccessible = true }.get(service)
            }.getOrNull()
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
            val ctx = runCatching {
                service.javaClass.getDeclaredField("n")
                    .apply { isAccessible = true }.get(service) as Context
            }.getOrNull()
            if (ctx == null) {
                resumePendingCompletion(mClassLoader)
                return@hookMethod chain.proceed()
            }
            val preferPortrait = ForegroundAutoNextPrefs.resolvePreferPortrait(service)
            if (preferPortrait != null) {
                cancelFallback()
                openNextWithOrientationPick(mClassLoader, service, ctx, preferPortrait, openGeneration)
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
            val service = chain.thisObject
            val repo = service.javaClass.getDeclaredField("b")
                .apply { isAccessible = true }.get(service)

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
            activating = true
            pendingCompletion = chain.args.firstOrNull()

            runCatching { repo.javaClass.getMethod("C").invoke(repo) }
                .onFailure { Log.x("ForegroundAutoNext: C() failed: ${it.message}") }

            runCatching {
                repo.javaClass.getMethod("G", Boolean::class.javaPrimitiveType)
                    .invoke(repo, true)
            }.onFailure { Log.x("ForegroundAutoNext: G(true) failed: ${it.message}") }

            runCatching {
                service.javaClass.getDeclaredMethod("p")
                    .apply { isAccessible = true }.invoke(service)
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
            val fallback = Runnable {
                if (activating && gen == openGeneration) {
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
            mainHandler.postDelayed(fallback, FALLBACK_MS)

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

        /** AutoFullscreen (2): enters fullscreen but keeps back-to-halfscreen handler. ForcedInFullscreen (1) disables it. */
        private const val FULLSCREEN_MODE_AUTO = "2"

        const val FALLBACK_MS = 2500L

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

        fun readRealBackground(repo: Any): Boolean {
            val oFlow = repo.javaClass.getDeclaredField("o")
                .apply { isAccessible = true }.get(repo)
            return oFlow.javaClass.getMethod("getValue").invoke(oFlow) as Boolean
        }

        fun abortActivation(classLoader: ClassLoader, reason: String) {
            if (!activating && pendingCompletion == null && fallbackRunnable == null) return
            Log.x("ForegroundAutoNext: abort ($reason)")
            openGeneration++
            activating = false
            cancelFallback()
            if (pendingCompletion != null) {
                resumePendingCompletion(classLoader)
            }
        }

        fun isEligibleForForegroundAutoNext(service: Any): Boolean {
            val repo = runCatching {
                service.javaClass.getDeclaredField("b").apply { isAccessible = true }.get(service)
            }.getOrNull() ?: return false
            if (readRealBackground(repo)) return false
            val ctx = runCatching {
                service.javaClass.getDeclaredField("n").apply { isAccessible = true }.get(service) as Context
            }.getOrNull() ?: return false
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
            val ctx = runCatching {
                service.javaClass.getDeclaredField("n").apply { isAccessible = true }.get(service) as Context
            }.getOrNull()
            if (ctx == null) {
                Log.s("ForegroundAutoNext: no context for relate feed")
                resumePendingCompletion(classLoader)
                return
            }
            val preferPortrait = ForegroundAutoNextPrefs.resolvePreferPortrait(service)
            if (preferPortrait != null) {
                openNextWithOrientationPick(classLoader, service, ctx, preferPortrait, generation)
                return
            }
            val avid = getCurrentAvid(service)
            if (avid == null || avid <= 0L) {
                Log.s("ForegroundAutoNext: no current avid for relate feed")
                resumePendingCompletion(classLoader)
                return
            }
            val fullscreen = isInFullscreen(service)
            Thread {
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
            }.start()
        }

        private data class RelateCandidate(
            val avid: Long,
            val uri: String?,
            val portrait: Boolean?,
        )

        private fun openNextWithOrientationPick(
            classLoader: ClassLoader,
            service: Any,
            ctx: Context,
            preferPortrait: Boolean,
            generation: Int = openGeneration,
        ) {
            val currentAvid = getCurrentAvid(service)
            if (currentAvid == null || currentAvid <= 0L) {
                Log.s("ForegroundAutoNext: no current avid for oriented pick")
                resumePendingCompletion(classLoader)
                return
            }
            val fullscreen = isInFullscreen(service)
            Thread {
                val feed = runCatching { requestRelateCandidates(classLoader, currentAvid) }
                    .onFailure { Log.s("ForegroundAutoNext: relate feed failed: ${it.message}") }
                    .getOrDefault(emptyList())
                val aiAvids = collectAiAvids(service)
                val picked = pickRelate(aiAvids, feed, preferPortrait)
                mainHandler.post {
                    if (generation != openGeneration) {
                        Log.x("ForegroundAutoNext: stale oriented pick, cancelled")
                        resumePendingCompletion(classLoader)
                        return@post
                    }
                    if (!isEligibleForForegroundAutoNext(service)) {
                        Log.x("ForegroundAutoNext: oriented pick skipped, not eligible")
                        resumePendingCompletion(classLoader)
                        return@post
                    }
                    if (picked == null) {
                        Log.s("ForegroundAutoNext: oriented pick found nothing for avid=$currentAvid")
                        resumePendingCompletion(classLoader)
                        return@post
                    }
                    runCatching {
                        if (!picked.uri.isNullOrBlank()) {
                            openUri(ctx, picked.uri, fullscreen)
                            finishSourceActivity(ctx)
                        } else {
                            openRelatePage(ctx, picked.avid, fullscreen)
                        }
                        Log.s(
                            "ForegroundAutoNext: oriented pick avid=${picked.avid} " +
                                "portrait=${picked.portrait} prefer=$preferPortrait ai=${aiAvids.size} feed=${feed.size}",
                        )
                    }.onFailure {
                        Log.s("ForegroundAutoNext: oriented open failed: ${it.message}")
                        resumePendingCompletion(classLoader)
                        return@post
                    }
                    clearPendingCompletion()
                }
            }.start()
        }

        private fun finishSourceActivity(ctx: Context) {
            if (ctx is Activity && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.finish()
            }
        }

        private fun getCurrentAvid(service: Any): Long? = runCatching {
            val playbackRepo = service.javaClass.getDeclaredField("d")
                .apply { isAccessible = true }.get(service)
            val current = playbackRepo.javaClass.getMethod("w").invoke(playbackRepo)
            current?.javaClass?.getMethod("b")?.invoke(current) as? Long
        }.getOrNull()

        private fun collectAiAvids(service: Any): List<Long> {
            val raw = collectRawAiAvids(service)
            if (raw.isEmpty()) return emptyList()
            BlockChargingVideoHook.resolveAvidsSync(raw)
            return raw.filter { !BlockChargingVideoHook.shouldBlockAvid(it) }
        }

        private fun collectRawAiAvids(service: Any): List<Long> = runCatching {
            val repo = service.javaClass.getDeclaredField("b")
                .apply { isAccessible = true }.get(service)
            val size = repo.javaClass.getMethod("m").invoke(repo) as Int
            val cur = repo.javaClass.getMethod("q").invoke(repo) as Int
            if (size <= 0 || cur + 1 >= size) return@runCatching emptyList<Long>()
            ((cur + 1) until size).mapNotNull { idx ->
                val item = repo.javaClass.getMethod("o", Int::class.javaPrimitiveType)
                    .invoke(repo, idx) ?: return@mapNotNull null
                item.javaClass.getMethod("a").invoke(item) as? Long
            }.filter { it > 0L }
        }.getOrDefault(emptyList())

        private fun pickNextAiAvid(service: Any): Long? = collectAiAvids(service).firstOrNull()

        private fun pickRelate(
            aiAvidsInOrder: List<Long>,
            feed: List<RelateCandidate>,
            preferPortrait: Boolean,
        ): RelateCandidate? {
            val portraitByAvid = feed.associate { it.avid to it.portrait }
            val uriByAvid = feed.associate { it.avid to it.uri }
            for (avid in aiAvidsInOrder) {
                if (BlockChargingVideoHook.shouldBlockAvid(avid)) continue
                if (portraitByAvid[avid] == preferPortrait) {
                    return RelateCandidate(avid, uriByAvid[avid], preferPortrait)
                }
            }
            for (candidate in feed) {
                if (BlockChargingVideoHook.shouldBlockAvid(candidate.avid)) continue
                if (candidate.portrait == preferPortrait) return candidate
            }
            val fallbackAvid = aiAvidsInOrder.firstOrNull { !BlockChargingVideoHook.shouldBlockAvid(it) }
            if (fallbackAvid != null) {
                return RelateCandidate(fallbackAvid, uriByAvid[fallbackAvid], portraitByAvid[fallbackAvid])
            }
            return feed.firstOrNull { !BlockChargingVideoHook.shouldBlockAvid(it.avid) }
        }

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
            return RelateCandidate(avid, uri, portrait)
        }

        private fun avidFromUri(uri: String): Long? {
            val path = Uri.parse(uri).path ?: return null
            return path.split("/").lastOrNull()?.toLongOrNull()
        }

        private fun isPortraitDimension(dimension: Any): Boolean {
            val rawW = (dimension.javaClass.getMethod("getWidth").invoke(dimension) as Number).toLong()
            val rawH = (dimension.javaClass.getMethod("getHeight").invoke(dimension) as Number).toLong()
            val rotate = (dimension.javaClass.getMethod("getRotate").invoke(dimension) as Number).toLong()
            val width = if (rotate == 1L) rawH else rawW
            val height = if (rotate == 1L) rawW else rawH
            return height > width
        }

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
            val ctx = runCatching {
                service.javaClass.getDeclaredField("n").apply { isAccessible = true }.get(service)
            }.getOrNull()
            val roots = listOfNotNull(service, ctx)
            for (root in roots) {
                findScreenStateRepo(root)?.let { repo ->
                    val fullscreen = readScreenStateFullscreen(repo)
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

        private fun readScreenStateFullscreen(repo: Any): Boolean {
            val state = repo.javaClass.getMethod("h").invoke(repo) ?: return false
            return state.javaClass.getMethod("b").invoke(state) as? Boolean ?: false
        }

        private fun looksLikeScreenStateRepo(obj: Any): Boolean = runCatching {
            val cls = obj.javaClass
            cls.getMethod("h")
            cls.getMethod("c")
            cls.getMethod("j", Any::class.java, Boolean::class.javaPrimitiveType)
            true
        }.getOrDefault(false)

        private fun findScreenStateRepo(root: Any, maxDepth: Int = 4): Any? {
            val visited = mutableSetOf<Int>()
            val queue = ArrayDeque<Pair<Any, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty()) {
                val (obj, depth) = queue.removeFirst()
                val id = System.identityHashCode(obj)
                if (!visited.add(id)) continue
                if (looksLikeScreenStateRepo(obj)) return obj
                if (depth >= maxDepth) continue
                for (field in obj.javaClass.declaredFields) {
                    runCatching {
                        field.isAccessible = true
                        val value = field.get(obj) ?: return@runCatching
                        if (shouldTraverseForScreenState(value)) {
                            queue.add(value to depth + 1)
                        }
                    }
                }
            }
            return null
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
                        if (shouldTraverseForScreenState(value)) {
                            queue.add(value to depth + 1)
                        }
                    }
                }
            }
            return null
        }

        private fun shouldTraverseForScreenState(value: Any): Boolean {
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
