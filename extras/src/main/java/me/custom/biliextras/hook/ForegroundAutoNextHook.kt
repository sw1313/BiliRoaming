package me.custom.biliextras.hook

import android.app.Activity
import android.content.Context
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
 * Approach: instead of an in-place player switch (which never rebuilds the page
 * UI in the foreground), we simply grab the next AI-recommended video's avid and
 * open it as a brand new video page via the bilibili://video/{avid} router. A
 * fresh page guarantees the whole UI (player / metadata / intro / comments)
 * rebuilds, exactly like tapping a related video.
 *
 * Flow:
 *  1. Hook UGCBackgroundPlayService.x() (the completion handler). For a
 *     foreground normal video, reset the anchor (C()), enable AI mode (G(true)),
 *     build the anchor + request relates (p()/y()), set the pending flag (E) and
 *     suspend so x()'s native loop/pause branch never runs.
 *  2. When the relates finish loading, the native requestSuccess collector
 *     (UGCBackgroundPlayService$3$1) calls t(). We intercept t(), read the next
 *     relate's avid from the repository and open it as a new page instead of
 *     doing the in-place switch.
 *
 * The reusable pieces (hasNextEpisode, openNextRelate, URI helpers) live in the
 * companion object so other hooks (e.g. MediaButtonControlHook) can drive a
 * "next related video" jump on demand.
 */
class ForegroundAutoNextHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    override fun startHook() {
        if (!ePrefs.getBoolean(PREF_KEY, false)) return
        hookIsInBackground()
        hookPlayNextInternal()
        hookCompletion()
        Log.s("startHook: ForegroundAutoNext")
    }

    private fun readRealBackground(repo: Any): Boolean {
        val oFlow = repo.javaClass.getDeclaredField("o")
            .apply { isAccessible = true }.get(repo)
        return oFlow.javaClass.getMethod("getValue").invoke(oFlow) as Boolean
    }

    /**
     * Force isInBackground() = true while we are loading the relates, so the
     * loadAIRelatesIfNeeded callback (which checks w()) actually appends them and
     * emits its success event. We never touch the real StateFlow.
     */
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

    /**
     * Intercept t() (playNextInternal) and, instead of the in-place switch, open
     * the next relate as a new page.
     */
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
            // Safety net: t() is the shared switch point for BOTH our foreground relate
            // jump and the native background AI auto-next. We only fake w() (the method)
            // while loading relates; the real background flag is field `o`. If we are
            // genuinely in the background, never hijack - let the native AI switch run,
            // otherwise background auto-play would stop (openVideo/startActivity is
            // blocked from the background) or fall back to playlist loop.
            if (repo0 != null && runCatching { readRealBackground(repo0) }.getOrDefault(false)) {
                activating = false
                Log.x("ForegroundAutoNext: t() in real background, leaving to native AI")
                return@hookMethod chain.proceed()
            }
            activating = false
            val avid = runCatching {
                val repo = service.javaClass.getDeclaredField("b")
                    .apply { isAccessible = true }.get(service)
                val size = repo.javaClass.getMethod("m").invoke(repo) as Int
                val cur = repo.javaClass.getMethod("q").invoke(repo) as Int
                if (size <= 0) return@runCatching null
                val idx = (cur + 1).coerceIn(0, size - 1)
                val item = repo.javaClass.getMethod("o", Int::class.javaPrimitiveType)
                    .invoke(repo, idx) ?: return@runCatching null
                item.javaClass.getMethod("a").invoke(item) as Long
            }.getOrNull()

            if (avid != null && avid > 0L) {
                runCatching {
                    val ctx = service.javaClass.getDeclaredField("n")
                        .apply { isAccessible = true }.get(service) as Context
                    openVideo(ctx, avid)
                    Log.x("ForegroundAutoNext: opening relate as new page, avid=$avid")
                }.onFailure {
                    Log.x("ForegroundAutoNext: openVideo failed: ${it.message}")
                    return@hookMethod chain.proceed()
                }
                // Do NOT proceed: skip the in-place switch, the new page takes over.
                return@hookMethod null
            }

            Log.x("ForegroundAutoNext: no relate avid, falling back to native")
            chain.proceed()
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
                // Genuine background completion: this is the native "background auto-play
                // related" path. Never touch it; also clear any stale activating flag so
                // the t() switch below is not hijacked.
                activating = false
                return@hookMethod chain.proceed()
            }

            // Collection with a next part -> let the native handler play it.
            if (hasNextEpisode(service)) {
                Log.x("ForegroundAutoNext: has next episode, native handles it")
                return@hookMethod chain.proceed()
            }

            Log.x("ForegroundAutoNext: foreground completion, requesting relates")
            activating = true

            // Fresh start so p() rebuilds the anchor and re-requests relates.
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

            // Diagnostics: why did relates (not) load?
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

            mainHandler.postDelayed({
                if (activating) {
                    activating = false
                    // AI ContinuousPlay returned nothing (common for old videos).
                    // Actively fetch the foreground relate feed, which is universal.
                    Log.x("ForegroundAutoNext: AI relate timed out, querying relate feed")
                    openNextRelate(mClassLoader, service)
                }
            }, FALLBACK_MS)

            val suspended = getCoroutineSuspended(mClassLoader)
            if (suspended != null) {
                Log.x("ForegroundAutoNext: suspending, awaiting relate")
                return@hookMethod suspended
            }

            Log.x("ForegroundAutoNext: COROUTINE_SUSPENDED not found, falling through")
            activating = false
            chain.proceed()
        }
        Log.x("ForegroundAutoNext: hooked UGCBackgroundPlayService.x()")
    }

    companion object {
        const val PREF_KEY = "foreground_auto_next"

        // If relates never load, reset so we don't stay stuck.
        const val FALLBACK_MS = 5000L

        @Volatile
        var activating = false

        @Volatile
        var cachedSuspended: Any? = null

        val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Whether the current video has a next episode in its collection / multi-part.
         * Mirrors UGCPlayListSchedulingService.b(): episodeListRepo.j(playbackRepo.w()).
         * service.c = UGCEpisodeListRepository, service.d = UGCPlaybackRepository.
         */
        fun hasNextEpisode(service: Any): Boolean {
            return runCatching {
                val episodeRepo = service.javaClass.getDeclaredField("c")
                    .apply { isAccessible = true }.get(service)
                val playbackRepo = service.javaClass.getDeclaredField("d")
                    .apply { isAccessible = true }.get(service)
                val current = playbackRepo.javaClass.getMethod("w").invoke(playbackRepo)

                // Diagnostics
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

        /**
         * Actively fetch the foreground relate feed for the video currently playing
         * in the given UGCBackgroundPlayService and open the top result as a new page.
         * Network call runs on a background thread. Used both as the auto-complete
         * fallback and as the on-demand "media button next" action.
         */
        fun openNextRelate(classLoader: ClassLoader, service: Any) {
            val avid = runCatching {
                val playbackRepo = service.javaClass.getDeclaredField("d")
                    .apply { isAccessible = true }.get(service)
                val current = playbackRepo.javaClass.getMethod("w").invoke(playbackRepo)
                current?.javaClass?.getMethod("b")?.invoke(current) as? Long
            }.getOrNull()
            if (avid == null || avid <= 0L) {
                Log.x("ForegroundAutoNext: no current avid for relate feed")
                return
            }
            val ctx = runCatching {
                service.javaClass.getDeclaredField("n").apply { isAccessible = true }.get(service) as Context
            }.getOrNull()
            if (ctx == null) {
                Log.x("ForegroundAutoNext: no context for relate feed")
                return
            }
            Thread {
                val uri = runCatching { requestRelateUri(classLoader, avid) }
                    .onFailure { Log.x("ForegroundAutoNext: relate feed request failed: ${it.message}") }
                    .getOrNull()
                if (!uri.isNullOrBlank()) {
                    mainHandler.post {
                        runCatching {
                            openUri(ctx, uri)
                            Log.x("ForegroundAutoNext: opened foreground relate, uri=$uri")
                        }.onFailure { Log.x("ForegroundAutoNext: openUri failed: ${it.message}") }
                    }
                } else {
                    Log.x("ForegroundAutoNext: foreground relate feed returned nothing for avid=$avid")
                }
            }.start()
        }

        /**
         * Actively query the foreground "相关视频" (RelatesFeed) View API for the given
         * video and return the first plain-UGC (non-ad / non-promo) relate's router
         * URI. Unlike the background AI ContinuousPlay API, this endpoint has data for
         * every video (including very old ones). Must be called off the main thread:
         * it performs a blocking gRPC call.
         */
        private fun requestRelateUri(classLoader: ClassLoader, avid: Long): String? {
            val viewMossClass = "com.bapis.bilibili.app.viewunite.v1.ViewMoss"
                .findClassOrNull(classLoader) ?: run {
                Log.x("ForegroundAutoNext: ViewMoss not found")
                return null
            }
            val reqClass = "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReq"
                .findClassOrNull(classLoader) ?: run {
                Log.x("ForegroundAutoNext: RelatesFeedReq not found")
                return null
            }
            val builder = reqClass.getMethod("newBuilder").invoke(null)
            builder.javaClass.getMethod("setAid", Long::class.javaPrimitiveType).invoke(builder, avid)
            val req = builder.javaClass.getMethod("build").invoke(builder)

            val moss = newViewMoss(viewMossClass) ?: run {
                Log.x("ForegroundAutoNext: cannot construct ViewMoss")
                return null
            }
            val reply = viewMossClass.getMethod("executeRelatesFeed", reqClass).invoke(moss, req)
                ?: return null
            return extractRelateUri(reply)
        }

        /**
         * Construct a ViewMoss via its public no-arg constructor (confirmed from the
         * decompiled class): it defaults to host grpc.biliapi.net:443 with DEF_OPTIONS
         * and the standard internal (auth) middlewares.
         */
        private fun newViewMoss(cls: Class<*>): Any? {
            return runCatching {
                cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.onFailure { Log.x("ForegroundAutoNext: ViewMoss ctor failed: ${it.message}") }
                .getOrNull()
        }

        /** First plain-UGC relate's router URI, skipping bangumi/game/ad/promo cards. */
        private fun extractRelateUri(reply: Any): String? {
            val relates = runCatching {
                reply.javaClass.getMethod("getRelatesList").invoke(reply) as? List<*>
            }.getOrNull() ?: return null
            for (card in relates) {
                card ?: continue
                val hasAv = runCatching {
                    card.javaClass.getMethod("hasAv").invoke(card) as Boolean
                }.getOrDefault(false)
                if (!hasAv) continue
                val isPromo = runCatching {
                    card.javaClass.getMethod("hasCmStock").invoke(card) as Boolean
                }.getOrDefault(false)
                if (isPromo) continue
                val basic = card.javaClass.getMethod("getBasicInfo").invoke(card) ?: continue
                val uri = basic.javaClass.getMethod("getUri").invoke(basic) as? String
                if (!uri.isNullOrBlank()) return uri
            }
            return null
        }

        fun openVideo(context: Context, avid: Long) {
            openUri(context, "bilibili://video/$avid")
        }

        fun openUri(context: Context, uri: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(context.packageName)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
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
