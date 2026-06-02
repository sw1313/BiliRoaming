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
        hookOldPageTeardownGuard()
        Log.s("startHook: ForegroundAutoNext")
    }

    private fun readRealBackground(repo: Any): Boolean {
        val oFlow = repo.javaClass.getDeclaredField("o")
            .apply { isAccessible = true }.get(repo)
        return oFlow.javaClass.getMethod("getValue").invoke(oFlow) as Boolean
    }

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
                cancelFallback()
                val ctx = runCatching {
                    service.javaClass.getDeclaredField("n")
                        .apply { isAccessible = true }.get(service) as Context
                }.getOrNull()
                if (ctx == null) {
                    resumePendingCompletion(mClassLoader)
                    return@hookMethod chain.proceed()
                }
                runCatching {
                    openRelatePage(ctx, avid)
                    Log.s("ForegroundAutoNext: opened relate page avid=$avid")
                }.onFailure {
                    Log.s("ForegroundAutoNext: openRelatePage failed: ${it.message}")
                    resumePendingCompletion(mClassLoader)
                    return@hookMethod chain.proceed()
                }
                clearPendingCompletion()
                return@hookMethod null
            }

            Log.x("ForegroundAutoNext: no relate avid, falling back to native")
            resumePendingCompletion(mClassLoader)
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
                activating = false
                clearPendingCompletion()
                return@hookMethod chain.proceed()
            }

            if (hasNextEpisode(service)) {
                Log.x("ForegroundAutoNext: has next episode, native handles it")
                clearPendingCompletion()
                return@hookMethod chain.proceed()
            }

            Log.s("ForegroundAutoNext: foreground completion, requesting relates")
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
                if (activating) {
                    activating = false
                    Log.s("ForegroundAutoNext: AI relate timed out, querying relate feed")
                    openNextRelate(mClassLoader, service)
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

        val mainHandler = Handler(Looper.getMainLooper())

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
        fun openRelatePage(context: Context, avid: Long) {
            openVideo(context, avid)
            if (context is Activity) {
                mainHandler.post {
                    if (!context.isFinishing && !context.isDestroyed) {
                        context.finish()
                    }
                }
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

        fun openNextRelate(classLoader: ClassLoader, service: Any) {
            cancelFallback()
            activating = false
            val avid = runCatching {
                val playbackRepo = service.javaClass.getDeclaredField("d")
                    .apply { isAccessible = true }.get(service)
                val current = playbackRepo.javaClass.getMethod("w").invoke(playbackRepo)
                current?.javaClass?.getMethod("b")?.invoke(current) as? Long
            }.getOrNull()
            if (avid == null || avid <= 0L) {
                Log.s("ForegroundAutoNext: no current avid for relate feed")
                resumePendingCompletion(classLoader)
                return
            }
            val ctx = runCatching {
                service.javaClass.getDeclaredField("n").apply { isAccessible = true }.get(service) as Context
            }.getOrNull()
            if (ctx == null) {
                Log.s("ForegroundAutoNext: no context for relate feed")
                resumePendingCompletion(classLoader)
                return
            }
            Thread {
                val uri = runCatching { requestRelateUri(classLoader, avid) }
                    .onFailure { Log.s("ForegroundAutoNext: relate feed failed: ${it.message}") }
                    .getOrNull()
                mainHandler.post {
                    if (!uri.isNullOrBlank()) {
                        runCatching {
                            openUri(ctx, uri)
                            if (ctx is Activity && !ctx.isFinishing && !ctx.isDestroyed) {
                                ctx.finish()
                            }
                            Log.s("ForegroundAutoNext: opened relate feed uri=$uri")
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

        private fun requestRelateUri(classLoader: ClassLoader, avid: Long): String? {
            val viewMossClass = "com.bapis.bilibili.app.viewunite.v1.ViewMoss"
                .findClassOrNull(classLoader) ?: return null
            val reqClass = "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReq"
                .findClassOrNull(classLoader) ?: return null
            val builder = reqClass.getMethod("newBuilder").invoke(null)
            builder.javaClass.getMethod("setAid", Long::class.javaPrimitiveType).invoke(builder, avid)
            val req = builder.javaClass.getMethod("build").invoke(builder)
            val moss = runCatching {
                viewMossClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.getOrNull() ?: return null
            val reply = viewMossClass.getMethod("executeRelatesFeed", reqClass).invoke(moss, req)
                ?: return null
            return extractRelateUri(reply)
        }

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
