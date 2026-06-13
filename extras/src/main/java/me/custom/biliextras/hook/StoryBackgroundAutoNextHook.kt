package me.custom.biliextras.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import me.custom.biliextras.utils.hookMethod
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

class StoryBackgroundAutoNextHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    companion object {
        const val PREF_KEY = "story_background_auto_next"
        const val MEDIA_BUTTON_KEY = "media_button_control"
        const val POLL_INTERVAL_MS = 500L
        const val END_THRESHOLD_MS = 800L
        const val NEXT_COOLDOWN_MS = 3_000L
        const val LOAD_MORE_COOLDOWN_MS = 1_500L
        const val TAIL_FORCE_LOAD_MORE_MS = 2_500L

        // Foreground progress hand-off (seek back to background position on resume).
        const val FOREGROUND_SEEK_MIN_MS = 3_000L
        const val FOREGROUND_SEEK_POLL_MS = 200L
        const val FOREGROUND_SEEK_TIMEOUT_MS = 6_000L
        const val FOREGROUND_SEEK_RESET_MARGIN_MS = 2_000L
        /** Close enough to the captured position — stop polling (avoids jittery re-seeks). */
        const val FOREGROUND_SEEK_NEAR_TARGET_MS = 3_500L
        // After the first seek, keep watching briefly and re-seek only if position snaps back
        // near the start (not when the player is still converging toward the target).
        const val FOREGROUND_SEEK_HOLD_MS = 1_500L
        const val FOREGROUND_SEEK_CLOBBER_MAX_MS = 2_500L
        const val FOREGROUND_SEEK_MAX_RETRIES = 2
        val hookedConcreteClasses = ConcurrentHashMap.newKeySet<String>()
        val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        var activeStoryPlayer: Any? = null

        @Volatile
        var activePlayerCore: Any? = null

        @Volatile
        var activeStoryHost: Any? = null

        @Volatile
        var activeStoryKey: String? = null

        @Volatile
        var polling = false

        @Volatile
        var lastNextAtMs = 0L

        @Volatile
        var lastLoadMoreAtMs = 0L

        @Volatile
        var tailWaitKey: String? = null

        @Volatile
        var tailWaitStartAtMs = 0L

        @Volatile
        var trackedIndex = -1

        /** Active story host; used only to detect main-feed -> UP-space navigation. */
        @Volatile
        var trackedIndexHost: String? = null

        /** Engine index from background auto-next; kept until pager D1 catches up. */
        @Volatile
        var liveEngineIndex = -1

        internal const val HOST_MAIN = "com.bilibili.video.story.StoryVideoFragment"
        internal const val HOST_SPACE = "com.bilibili.video.story.space.StorySpaceFragment"

        @Volatile
        var backgroundLoadActive = false

        @Volatile
        var isInBackground = false

        /** True when triggerNextStory/triggerPreviousBackground moved the engine this background session. */
        @Volatile
        var backgroundEngineMoved = false

        /** Last engine index reached by background auto-next; survives D1=0 pager staleness. */
        @Volatile
        var backgroundEngineIndex = -1

        @Volatile
        var cachedAppContext: Context? = null

        // When false (media-button-only), we only capture the player + expose
        // triggerNext()/triggerPrevious(), and never alter looping or auto-advance.
        fun isAutoNextEnabled(): Boolean = ePrefs.getBoolean(PREF_KEY, false)

        // Live instance so MediaButtonControlHook can drive next/previous on demand.
        @Volatile
        var liveInstance: StoryBackgroundAutoNextHook? = null

        @Volatile
        var foregroundSeekRunnable: Runnable? = null

        fun hasActiveStory(): Boolean = activeStoryPlayer != null

        fun mediaNext(): Boolean = liveInstance?.triggerNext() ?: false

        fun mediaPrevious(): Boolean = liveInstance?.triggerPrevious() ?: false

        fun syncAutoNextFromPrefs() {
            // Kept for callers that expect an explicit sync; pref is read live via isAutoNextEnabled().
        }

        /** Successful Method lookups only — never cache a miss (class may not be ready yet). */
        private val storyMethodCache = ConcurrentHashMap<Pair<Class<*>, String>, Method>()

        internal fun Class<*>.cachedStoryMethod(name: String, vararg paramTypes: Class<*>): Method? {
            val sig = buildString {
                append(name)
                paramTypes.forEach { append('#').append(it.name) }
            }
            val key = this to sig
            storyMethodCache[key]?.let { return it }
            val method = if (paramTypes.isEmpty()) {
                methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            } else {
                methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(paramTypes) }
            }?.also { it.isAccessible = true }
            if (method != null) storyMethodCache[key] = method
            return method
        }
    }

    override fun startHook() {
        liveInstance = this
        hookCancelCheck()
        hookFragmentLifecycleBypass()
        hookStoryPagerPlayer()
        Log.s("startHook: StoryBackgroundAutoNext (autoNext=${isAutoNextEnabled()} mediaButton=${ePrefs.getBoolean(MEDIA_BUTTON_KEY, false)})")
    }

    /** Called when the in-player menu toggles background auto-next. */
    fun onAutoNextPrefChanged(enabled: Boolean) {
        val player = activeStoryPlayer
        if (player != null && isInBackground) {
            applyBackgroundLoopMode(player, enabled)
        }
        if (enabled) {
            ensurePolling()
            Log.x("StoryAutoNext: auto-next ON (background loop off, polling=$polling)")
        } else {
            Log.x("StoryAutoNext: auto-next OFF (single loop restored)")
        }
    }

    /**
     * Media-button next. Foreground portrait drives a real ViewPager2 page change
     * (smooth scroll); background reuses the engine-level switch.
     */
    fun triggerNext(): Boolean = runOnMain {
        if (isInBackground) triggerNextStory() else triggerForegroundScroll(forward = true)
    }

    /** Media-button previous. Foreground = pager scroll back; background = engine switch. */
    fun triggerPrevious(): Boolean = runOnMain {
        if (isInBackground) triggerPreviousBackground() else triggerForegroundScroll(forward = false)
    }

    /**
     * Foreground portrait next/previous: drive the actual ViewPager2 page change via
     * StoryPagerPlayer.F2(index, smooth=true) — i.e. ViewPager2.setCurrentItem with a
     * smooth scroll. This is the same low-level programmatic scroll the player uses
     * internally; the registered OnPageChangeCallback then switches the video exactly
     * like a user swipe, with no MotionEvent injection. F2() self-bounds-checks
     * (N1() > index), so an out-of-range target is a safe no-op.
     */
    private fun triggerForegroundScroll(forward: Boolean): Boolean = runCatching {
        val root = activeStoryPlayer ?: return@runCatching false
        val current = root.invokeIntGetter("D1", "getIndex") ?: return@runCatching false
        val count = root.invokeIntGetter("N1") ?: 0
        val target = if (forward) current + 1 else current - 1
        if (target < 0) {
            Log.x("StoryAutoNext: fg no previous (current=$current)")
            return@runCatching false
        }
        if (forward && (count <= 0 || target >= count - 1)) {
            // Approaching/at the tail: ask the feed to load more so the page exists.
            root.callOfficialStoryLoadMore(target, force = false)
        }
        val moved = root.callPagerScroll(target)
        if (moved) {
            trackedIndex = target
            Log.x("StoryAutoNext: fg scroll ${if (forward) "next" else "prev"} index=$target (current=$current count=$count)")
        }
        moved
    }.getOrElse {
        Log.e(it)
        false
    }

    /** Background previous: jump to the prior already-loaded item via the engine. */
    private fun triggerPreviousBackground(): Boolean = runCatching {
        val root = activeStoryPlayer ?: return@runCatching false
        val d1 = root.invokeIntGetter("D1", "getIndex") ?: return@runCatching false
        val current = if (trackedIndex >= d1) trackedIndex else d1
        if (current <= 0) {
            Log.x("StoryAutoNext: no previous story (current=$current)")
            return@runCatching false
        }
        val prevIndex = current - 1
        val moved = root.callOfficialStoryNext(prevIndex)
        if (moved) {
            trackedIndex = prevIndex
            backgroundEngineIndex = prevIndex
            backgroundEngineMoved = true
            Log.x("StoryAutoNext: moved to previous index=$prevIndex (d1=$d1)")
        }
        moved
    }.getOrElse {
        Log.e(it)
        false
    }

    /** Invoke StoryPagerPlayer.F2(index, smooth) -> ViewPager2.setCurrentItem. */
    private fun Any.callPagerScroll(index: Int, smooth: Boolean = true): Boolean = runCatching {
        val m = javaClass.methods.firstOrNull {
            it.name == "F2" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return@runCatching false
        m.isAccessible = true
        m.invoke(this, index, smooth)
        true
    }.getOrElse {
        Log.e(it)
        false
    }

    /**
     * After background engine advance, sync ViewPager2/D1 to the real index via F2 — keep the
     * full feed so swipe-up reaches the previous item. W2-truncate drops earlier items and
     * makes the current video index 0, which breaks upward navigation.
     */
    private fun Any.syncForegroundPagerToIndex(index: Int, savedPos: Long, targetId: String?) {
        setNativeStartPosition(savedPos)
        callPagerScroll(index, smooth = false)
        trackedIndex = index
        mainHandler.post {
            runCatching {
                val nowId = currentStoryIdentity()
                if (targetId != null && nowId != null && nowId != targetId) {
                    forcePlayAtIndex(index)
                }
                Log.x("StoryAutoNext: foreground sync pager to index=$index id=$targetId (F1=$nowId)")
            }.onFailure { Log.e(it) }
        }
        scheduleForegroundSeek(savedPos, targetId, nativeSeeded = true)
    }

    /** Media-button callbacks may arrive off the main thread; pager work needs the UI thread. */
    private fun runOnMain(block: () -> Boolean): Boolean {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { runCatching { block() }.onFailure { Log.e(it) } }
            true
        }
    }

    /**
     * Hook u.c(Context) to return false when background auto-next is loading.
     * This prevents BiliApiCallback.isCancel() from discarding API responses
     * when the Activity is finishing/destroyed during background playback.
     */
    private fun hookCancelCheck() {
        val helperU = runCatching {
            Class.forName("com.bilibili.video.story.helper.u", false, mClassLoader)
        }.getOrNull() ?: return
        helperU.hookMethod("c", Context::class.java) { chain ->
            if (backgroundLoadActive) {
                return@hookMethod false
            }
            chain.proceed()
        }
        Log.x("StoryAutoNext: hooked u.c(Context) cancel check bypass")
    }

    private fun hookFragmentLifecycleBypass() {
        // Not needed - StoryVideoLoader.i() is called directly with our own callback,
        // bypassing Fragment lifecycle checks entirely.
    }

    private fun hookStoryPagerPlayer() {
        val addVideo = instance.addVideoMethod()?.name ?: run {
            Log.x("StoryAutoNext: addVideo method not found")
            return
        }
        val playerClass = instance.storyPagerPlayerClass ?: return
        hookStoryHostCapture(playerClass)
        playerClass.hookMethod("setLooping", Boolean::class.javaPrimitiveType!!) { chain ->
            val requested = chain.args.firstOrNull() as? Boolean
            if (requested == true && isInBackground && isAutoNextEnabled()) {
                return@hookMethod null
            }
            chain.proceed()
        }
        hookStoryPlayerLooping()
        hookForegroundSync(playerClass)
        playerClass.hookMethod(addVideo, List::class.java) { chain ->
            val firstItem = (chain.args[0] as? List<*>)?.firstOrNull()
            val player = chain.thisObject
            if (backgroundLoadActive) {
                val fieldI = runCatching {
                    player.javaClass.declaredFields.firstOrNull {
                        it.name == "I" && it.type == Boolean::class.javaPrimitiveType
                    }?.also { it.isAccessible = true }
                }.getOrNull()
                val wasI = fieldI?.getBoolean(player) ?: true
                if (!wasI) fieldI?.setBoolean(player, true)
                val result = chain.proceed()
                if (!wasI) fieldI?.setBoolean(player, wasI)
                activeStoryPlayer = player
                activeStoryKey = firstItem?.storyIdentity()
                reconcileTrackedIndexOnAddVideo(player, backgroundLoadActive = true)
                hookRuntimeTargets(player)
                ensurePolling()
                return@hookMethod result
            }
            val result = chain.proceed()
            activeStoryPlayer = player
            activeStoryKey = firstItem?.storyIdentity()
            reconcileTrackedIndexOnAddVideo(player, backgroundLoadActive = false)
            hookRuntimeTargets(player)
            ensurePolling()
            result
        }
        Log.x("StoryAutoNext: hooked ${playerClass.name}#$addVideo and setLooping")
    }

    private fun hookStoryHostCapture(playerClass: Class<*>) {
        val actionHostClass = runCatching {
            Class.forName("com.bilibili.video.story.action.f", false, mClassLoader)
        }.getOrNull() ?: return
        playerClass.hookMethod("I2", actionHostClass) { chain ->
            val player = chain.thisObject
            val host = chain.args.firstOrNull()
            val prevHost = trackedIndexHost
            if (host != null && host.javaClass.name == HOST_SPACE &&
                (prevHost == HOST_MAIN || prevHost == null)
            ) {
                // Background auto-next advances the engine while D1 stays 0; Bilibili opens UP
                // space for the pager item unless we align D1 to the engine first.
                player.ensurePagerAlignedForNavigation()
            }
            val result = chain.proceed()
            if (host != null && host.javaClass.name in setOf(HOST_MAIN, HOST_SPACE)) {
                activeStoryHost = host
                trackedIndexHost = host.javaClass.name
            }
            if (host != null && host.javaClass.name in setOf(HOST_MAIN, HOST_SPACE) && cachedAppContext == null) {
                runCatching {
                    val ctx = host.javaClass.getMethod("getContext").invoke(host) as? Context
                    cachedAppContext = ctx?.applicationContext
                }
            }
            result
        }
    }

    private fun hookStoryPlayerLooping() {
        val storyPlayerClass = "com.bilibili.video.story.player.StoryPlayer".let {
            runCatching { Class.forName(it, false, mClassLoader) }.getOrNull()
        } ?: return
        storyPlayerClass.hookMethod("setLooping", Boolean::class.javaPrimitiveType!!) { chain ->
            val requested = chain.args.firstOrNull() as? Boolean
            if (requested == true && isInBackground && isAutoNextEnabled()) {
                return@hookMethod null
            }
            chain.proceed()
        }
        Log.x("StoryAutoNext: hooked ${storyPlayerClass.name}.setLooping")
    }

    /**
     * When the engine ran ahead in background (D1 still 0), align ViewPager2 before in-app
     * navigation (e.g. opening UP space) so Bilibili picks the currently playing video.
     */
    private fun Any.ensurePagerAlignedForNavigation(): Boolean = runCatching {
        val d1 = invokeIntGetter("D1", "getIndex") ?: -1
        val target = trackedIndex
            .coerceAtLeast(liveEngineIndex)
            .coerceAtLeast(backgroundEngineIndex)
        if (target <= d1 || target < 0) return@runCatching false
        val count = invokeIntGetter("N1") ?: return@runCatching false
        if (target !in 0 until count) return@runCatching false
        callPagerScroll(target, smooth = false)
        trackedIndex = target
        if (d1 >= target) liveEngineIndex = d1
        Log.x("StoryAutoNext: align pager for navigation d1=$d1 -> $target")
        true
    }.getOrElse {
        Log.e(it)
        false
    }

    /**
     * Background pause/resume hooks: only touch loop mode and post-background pager catch-up.
     */
    private fun hookForegroundSync(playerClass: Class<*>) {
        val u2PauseMethod = playerClass.declaredMethods.firstOrNull {
            it.name == "u2" && it.parameterCount == 0
        }
        if (u2PauseMethod != null) {
            u2PauseMethod.hookMethod { chain ->
                val player = chain.thisObject
                val enteringBackground = !isInBackground
                isInBackground = true
                if (enteringBackground) {
                    backgroundEngineMoved = false
                    backgroundEngineIndex = -1
                }
                if (isAutoNextEnabled()) {
                    applyBackgroundLoopMode(player, autoNext = true)
                } else {
                    applyBackgroundLoopMode(player, autoNext = false)
                }
                chain.proceed()
            }
        }

        val w2ResumeMethod = playerClass.declaredMethods.firstOrNull {
            it.name == "w2" && it.parameterCount == 0
        }
        if (w2ResumeMethod != null) {
            w2ResumeMethod.hookMethod { chain ->
                val player = chain.thisObject
                val engineMoved = backgroundEngineMoved
                val syncIndex = when {
                    engineMoved && backgroundEngineIndex >= 0 -> backgroundEngineIndex
                    engineMoved && trackedIndex >= 0 -> trackedIndex
                    else -> -1
                }
                val savedPos = if (engineMoved) {
                    liveStoryPlayerCore()
                        ?.invokeLongGetter("getCurrentPosition", "getRealCurrentPosition") ?: 0L
                } else {
                    0L
                }
                val targetId = if (syncIndex >= 0) storyIdentityAt(syncIndex) else null
                if (engineMoved) {
                    Log.x(
                        "StoryAutoNext: bg resume syncIndex=$syncIndex pager=" +
                            "${player.invokeIntGetter("D1", "getIndex")} engineIdx=$backgroundEngineIndex",
                    )
                }
                isInBackground = false
                chain.proceed()
                if (engineMoved && syncIndex >= 0 && isAutoNextEnabled()) {
                    val count = player.invokeIntGetter("N1") ?: 0
                    val pagerAfter = player.invokeIntGetter("D1", "getIndex") ?: -1
                    if (syncIndex in 0 until count && pagerAfter != syncIndex) {
                        runCatching {
                            player.syncForegroundPagerToIndex(syncIndex, savedPos, targetId)
                        }.onFailure { Log.e(it) }
                    } else if (savedPos > 0L) {
                        player.setNativeStartPosition(savedPos)
                        scheduleForegroundSeek(savedPos, targetId, nativeSeeded = true)
                    }
                    liveEngineIndex = syncIndex
                }
                backgroundEngineMoved = false
                backgroundEngineIndex = -1
                hookRuntimeTargets(player)
            }
            Log.x("StoryAutoNext: hooked ${playerClass.name}.w2 for background pager catch-up")
        }
    }

    /**
     * Seed StoryPagerPlayer's native "seek-to on prepare" field (the int field "t",
     * f208480t). The app sets this to the current position when going inactive (Q2 state 4)
     * and, in its StoryPlayer.d.onStateChanged, seeks to it once the player reaches the
     * prepared state (3), then clears it. Reusing it lets the rebuild's reopen resume at the
     * background position natively, instead of starting at 0:00 and visibly jumping.
     */
    private fun Any.setNativeStartPosition(posMs: Long) {
        if (posMs <= 0L) return
        runCatching {
            val field = javaClass.declaredFields.firstOrNull {
                it.name == "t" && it.type == Int::class.javaPrimitiveType
            } ?: return
            field.isAccessible = true
            field.setInt(this, posMs.toInt())
            Log.x("StoryAutoNext: seeded native start position t=$posMs")
        }.onFailure { Log.e(it) }
    }

    /** Identity (bvid/cid) of the story item currently shown by the pager player (F1). */
    private fun currentStoryIdentity(): String? = runCatching {
        val player = activeStoryPlayer ?: return null
        val item = player.javaClass.methods.firstOrNull { it.name == "F1" && it.parameterCount == 0 }
            ?.invoke(player) ?: return null
        item.storyIdentity()
    }.getOrNull()

    /** Identity (bvid/cid) of the adapter item at the given index (V1). */
    private fun storyIdentityAt(index: Int): String? = runCatching {
        if (index < 0) return null
        val player = activeStoryPlayer ?: return null
        val v1 = player.javaClass.methods.firstOrNull {
            it.name == "V1" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return null
        val item = v1.invoke(player, index) ?: return null
        item.storyIdentity()
    }.getOrNull()

    /**
     * The live IPlayerCoreService that StoryPlayer actually plays through (StoryPlayer field
     * b, concrete type == playerCoreMethods.serviceClass / vd3.p0). This is the authoritative
     * position/seek target for both background and foreground; the reflection-scanned
     * activePlayerCore can resolve to an unrelated object that reports position 0.
     */
    private fun liveStoryPlayerCore(): Any? = runCatching {
        val serviceClass = instance.playerCoreMethods?.serviceClass ?: return null
        val pager = activeStoryPlayer ?: return null
        val storyPlayer = pager.javaClass.declaredFields.firstOrNull {
            it.type.name == "com.bilibili.video.story.player.StoryPlayer"
        }?.apply { isAccessible = true }?.get(pager) ?: return null
        storyPlayer.javaClass.declaredFields.asSequence()
            .mapNotNull { f -> runCatching { f.isAccessible = true; f.get(storyPlayer) }.getOrNull() }
            .firstOrNull { serviceClass.isInstance(it) }
    }.getOrNull()

    /**
     * Progress hand-off: after the foreground rebuild reopens the current video from 0:00,
     * poll until the player core has re-prepared (duration>0) and the reopen has reset the
     * position back near the start, then seek to the captured background position.
     *
     * Phase 1 (wait + seek): wait until duration>0 and the position has dropped clearly below
     * what we captured (the reopen reset it), then seek once.
     * Phase 2 (hold + re-seek): the reopen's own resolve->resume can start the video from 0
     * AFTER our first seek (a race short videos tend to lose). So for a short hold window we
     * keep watching, and if the position gets clobbered back toward the start we re-seek.
     *
     * The identity guard skips seeking until the pager has settled on the target video; a
     * genuine user swipe never matches and harmlessly times out (no seek).
     */
    private fun scheduleForegroundSeek(savedPosMs: Long, targetId: String?, nativeSeeded: Boolean = false) {
        if (savedPosMs < FOREGROUND_SEEK_MIN_MS) return
        val methods = instance.playerCoreMethods ?: return
        foregroundSeekRunnable?.let { mainHandler.removeCallbacks(it) }
        val deadline = System.currentTimeMillis() + FOREGROUND_SEEK_TIMEOUT_MS
        val runnable = object : Runnable {
            private var seeked = false
            private var seekedAtMs = 0L
            private var retries = 0

            override fun run() {
                runCatching {
                    val now = System.currentTimeMillis()
                    if (now > deadline) {
                        if (!seeked && !nativeSeeded) {
                            Log.x("StoryAutoNext: foreground seek timeout savedPos=$savedPosMs id=$targetId")
                        }
                        foregroundSeekRunnable = null
                        return
                    }
                    val nowId = currentStoryIdentity()
                    val idReady = targetId == null || nowId == null || nowId == targetId
                    val core = liveStoryPlayerCore()
                    val duration = core?.invokeLongGetter("getDuration", "getRealDuration") ?: 0L
                    val position = core?.invokeLongGetter("getCurrentPosition", "getRealCurrentPosition") ?: 0L
                    if (!idReady || core == null || duration <= 0L) {
                        mainHandler.postDelayed(this, FOREGROUND_SEEK_POLL_MS)
                        return
                    }
                    val target = savedPosMs.coerceAtMost((duration - 500L).coerceAtLeast(0L)).toInt()

                    if (isNearForegroundTarget(position, target)) {
                        foregroundSeekRunnable = null
                        return
                    }

                    if (!seeked) {
                        if (nativeSeeded) {
                            if (position >= FOREGROUND_SEEK_CLOBBER_MAX_MS) {
                                foregroundSeekRunnable = null
                                return
                            }
                        } else if (position >= savedPosMs - FOREGROUND_SEEK_RESET_MARGIN_MS) {
                            mainHandler.postDelayed(this, FOREGROUND_SEEK_POLL_MS)
                            return
                        }
                        if (invokeForegroundSeek(methods.seekTo, core, target)) {
                            seeked = true
                            seekedAtMs = now
                            Log.x(
                                "StoryAutoNext: foreground seek to $target ms " +
                                    "(duration=$duration id=$targetId native=$nativeSeeded)",
                            )
                        }
                        mainHandler.postDelayed(this, FOREGROUND_SEEK_POLL_MS)
                        return
                    }

                    if (now - seekedAtMs > FOREGROUND_SEEK_HOLD_MS) {
                        foregroundSeekRunnable = null
                        return
                    }
                    if (isClobberedNearStart(position, target) && retries < FOREGROUND_SEEK_MAX_RETRIES) {
                        retries++
                        invokeForegroundSeek(methods.seekTo, core, target)
                        Log.x("StoryAutoNext: foreground re-seek($retries) to $target ms (pos=$position)")
                    }
                    mainHandler.postDelayed(this, FOREGROUND_SEEK_POLL_MS)
                }.onFailure {
                    Log.e(it)
                    foregroundSeekRunnable = null
                }
            }
        }
        foregroundSeekRunnable = runnable
        val initialDelay = if (nativeSeeded) 400L else FOREGROUND_SEEK_POLL_MS
        mainHandler.postDelayed(runnable, initialDelay)
    }

    private fun isNearForegroundTarget(position: Long, targetMs: Int): Boolean =
        position >= targetMs - FOREGROUND_SEEK_NEAR_TARGET_MS

    /** True only when playback snapped back near 0:00, not when still seeking toward target. */
    private fun isClobberedNearStart(position: Long, targetMs: Int): Boolean {
        val nearStart = position < FOREGROUND_SEEK_CLOBBER_MAX_MS
        val farBelowTarget = position < targetMs / 4
        return nearStart || farBelowTarget
    }

    private fun invokeForegroundSeek(seekTo: Method, core: Any, targetMs: Int): Boolean = runCatching {
        seekTo.isAccessible = true
        when (seekTo.parameterCount) {
            1 -> seekTo.invoke(core, targetMs)
            2 -> seekTo.invoke(core, targetMs, false)
            else -> seekTo.invoke(core, targetMs)
        }
        true
    }.getOrElse {
        Log.e(it)
        false
    }

    private fun hookRuntimeTargets(root: Any?) {
        if (root == null) return
        val targets = collectReachableObjects(root, 4)
        activePlayerCore = targets.firstOrNull { it.javaClass.hasPlaybackPositionMethods() }
        targets.filter { it.javaClass.isStoryLoopingTarget() }.forEach { target ->
            hookConcreteLoopingClass(target.javaClass)
        }
    }

    /**
     * Background playback: auto-next OFF = native single-loop; ON = loop disabled so end-of-play can advance.
     */
    private fun applyBackgroundLoopMode(root: Any, autoNext: Boolean) {
        if (autoNext) {
            forceLoopingOffPlayer(root)
        } else {
            forceLoopingOnPlayer(root)
        }
        invokeRootSetLooping(root, loop = !autoNext)
        Log.x("StoryAutoNext: background loop mode autoNext=$autoNext singleLoop=${!autoNext}")
    }

    /** Disable looping so playback-end can trigger auto-next. */
    private fun forceLoopingOffPlayer(root: Any) {
        collectReachableObjects(root, 4)
            .filter { it.javaClass.isStoryLoopingTarget() }
            .forEach { forceLoopingOff(it) }
    }

    /** Restore native single-loop when auto-next is off. */
    private fun forceLoopingOnPlayer(root: Any) {
        collectReachableObjects(root, 4)
            .filter { it.javaClass.isStoryLoopingTarget() }
            .forEach { forceLoopingOn(it) }
    }

    private fun invokeRootSetLooping(root: Any, loop: Boolean) {
        runCatching {
            root.javaClass.methods.firstOrNull { it.isLoopingSetter() }?.let { method ->
                method.isAccessible = true
                method.invoke(root, loop)
            }
        }.onFailure { Log.e(it) }
    }

    private fun collectReachableObjects(root: Any?, maxDepth: Int): List<Any> {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return collectReachableObjects(root, visited, 0, maxDepth)
    }

    private fun collectReachableObjects(
        obj: Any?,
        visited: MutableSet<Any>,
        depth: Int,
        maxDepth: Int,
    ): List<Any> {
        if (obj == null || depth > maxDepth || !visited.add(obj)) return emptyList()
        val clazz = obj.javaClass
        if (clazz.isPrimitiveLike()) return emptyList()
        val out = mutableListOf<Any>()
        out.add(obj)
        if (obj is ViewGroup) {
            repeat(obj.childCount) { index ->
                out.addAll(collectReachableObjects(obj.getChildAt(index), visited, depth + 1, maxDepth))
            }
        }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(obj)
                }.getOrNull()?.let { value ->
                    if (value.javaClass.isStoryCandidateContainer()) {
                        out.addAll(collectReachableObjects(value, visited, depth + 1, maxDepth))
                    }
                }
            }
            current = current.superclass
        }
        return out.distinctBy { System.identityHashCode(it) }
    }

    private fun Class<*>.isStoryCandidateContainer(): Boolean {
        val name = name
        return name.startsWith("com.bilibili.video.story.") ||
            name.startsWith("tv.danmaku.biliplayerv2.") ||
            name.startsWith("com.bilibili.playerbizcommon.") ||
            name.startsWith("tv.danmaku.videoplayer.") ||
            name.startsWith("vd3.") ||
            name.startsWith("androidx.viewpager.") ||
            name.startsWith("androidx.recyclerview.") ||
            name.startsWith("android.view.") ||
            interfaces.any { it.name == "com.bilibili.video.story.player.t" } ||
            declaredMethods.any {
                it.isLoopingSetter() || it.hasPlaybackPositionSignature() || it.isPageControllerMethod()
            }
    }

    private fun Class<*>.isStoryLoopingTarget(): Boolean =
        !isInterface &&
            !Modifier.isAbstract(modifiers) &&
            declaredMethods.any { it.isLoopingSetter() || it.isIndexedLoopingSetter() }

    private fun Class<*>.isPrimitiveLike(): Boolean =
        isPrimitive ||
            name.startsWith("java.lang.") ||
            name.startsWith("java.util.") ||
            name.startsWith("kotlin.")

    private fun Method.isLoopingSetter(): Boolean =
        name == "setLooping" &&
            parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
            !Modifier.isAbstract(modifiers)

    private fun Method.isIndexedLoopingSetter(): Boolean =
        parameterTypes.size == 2 &&
            parameterTypes[0] == Int::class.javaPrimitiveType &&
            parameterTypes[1] == Boolean::class.javaPrimitiveType &&
            returnType == Void.TYPE &&
            !Modifier.isAbstract(modifiers) &&
            name.contains("loop", ignoreCase = true)

    private fun Method.hasPlaybackPositionSignature(): Boolean =
        parameterCount == 0 &&
            (returnType == Int::class.javaPrimitiveType || returnType == Long::class.javaPrimitiveType) &&
            name in setOf("getCurrentPosition", "getRealCurrentPosition", "getDuration", "getRealDuration")

    private fun Class<*>.hasPlaybackPositionMethods(): Boolean =
        declaredMethods.any {
            it.parameterCount == 0 && it.name in setOf("getCurrentPosition", "getRealCurrentPosition")
        } &&
            declaredMethods.any {
                it.parameterCount == 0 && it.name in setOf("getDuration", "getRealDuration")
            }

    private fun Method.isPageControllerMethod(): Boolean =
        (name == "setCurrentItem" && parameterTypes.size in 1..2 && parameterTypes[0] == Int::class.javaPrimitiveType) ||
            (name == "smoothScrollToPosition" && parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)))

    private fun hookConcreteLoopingClass(clazz: Class<*>) {
        if (!hookedConcreteClasses.add(clazz.name)) return
        val methods = clazz.declaredMethods.filter { it.isLoopingSetter() || it.isIndexedLoopingSetter() }
        if (methods.isEmpty()) return
        methods.forEach { method ->
            method.isAccessible = true
            method.hookMethod { chain ->
                val boolIndex = chain.args.indexOfLast { it is Boolean }
                val requested = chain.args.getOrNull(boolIndex) as? Boolean
                if (boolIndex >= 0 && requested == true && isInBackground && isAutoNextEnabled()) {
                    return@hookMethod null
                }
                chain.proceed()
            }
        }
        Log.x("StoryAutoNext: hooked runtime looping class ${clazz.name} methods=${methods.joinToString { it.name }}")
    }

    private fun forceLoopingOff(target: Any) {
        target.javaClass.declaredMethods.firstOrNull { it.isLoopingSetter() }?.let { method ->
            runCatching {
                method.isAccessible = true
                method.invoke(target, false)
            }.onFailure { Log.e(it) }
        }
    }

    private fun forceLoopingOn(target: Any) {
        target.javaClass.declaredMethods.firstOrNull { it.isLoopingSetter() }?.let { method ->
            runCatching {
                method.isAccessible = true
                method.invoke(target, true)
            }.onFailure { Log.e(it) }
        }
    }

    private fun ensurePolling() {
        if (!isAutoNextEnabled()) return
        if (polling) return
        polling = true
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!isAutoNextEnabled()) return
                runCatching { checkPlaybackEndAndNext() }.onFailure { Log.e(it) }
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        })
        Log.x("StoryAutoNext: started end polling")
    }

    /**
     * Keep trackedIndex aligned with the pager on addVideo. In background the engine runs ahead
     * of ViewPager2/D1(); never pull trackedIndex down to stale D1=0 during loadMore h1().
     */
    private fun reconcileTrackedIndexOnAddVideo(player: Any, backgroundLoadActive: Boolean) {
        if (isInBackground || backgroundEngineMoved) return
        val currentD1 = player.invokeIntGetter("D1", "getIndex") ?: 0
        if (!backgroundLoadActive && trackedIndex >= 0 && currentD1 < trackedIndex) {
            trackedIndex = currentD1
            tailWaitKey = null
        } else if (trackedIndex < currentD1) {
            trackedIndex = currentD1
        }
    }

    private fun checkPlaybackEndAndNext() {
        if (!isAutoNextEnabled()) return
        // Prefer StoryPlayer's live core; activePlayerCore from graph scan can be a stale wrapper.
        val core = liveStoryPlayerCore() ?: activePlayerCore ?: return
        val position = core.invokeLongGetter("getCurrentPosition", "getRealCurrentPosition") ?: return
        val duration = core.invokeLongGetter("getDuration", "getRealDuration") ?: return
        prefetchOfficialStoryListIfNeeded()
        if (duration <= 0L || position <= 0L || duration - position > END_THRESHOLD_MS) return
        val now = System.currentTimeMillis()
        if (now - lastNextAtMs < NEXT_COOLDOWN_MS) return
        Log.x(
            "StoryAutoNext: playback ended key=$activeStoryKey, position=$position, duration=$duration, " +
                "core=${core.javaClass.name}#${System.identityHashCode(core)} bg=$isInBackground",
        )
        if (!isInBackground) return
        if (triggerNextStory()) {
            lastNextAtMs = now
        }
    }

    private fun prefetchOfficialStoryListIfNeeded() {
        val root = activeStoryPlayer ?: return
        val d1 = root.invokeIntGetter("D1", "getIndex") ?: return
        val current = if (trackedIndex >= d1) trackedIndex else d1
        val count = root.invokeIntGetter("N1") ?: return
        if (count <= 0 || current < count - 3) return
        root.callOfficialStoryLoadMore(current, force = false)
    }

    private fun triggerNextStory(): Boolean = runCatching {
        val root = activeStoryPlayer ?: return@runCatching false
        val d1 = root.invokeIntGetter("D1", "getIndex") ?: return false
        val current = if (trackedIndex >= d1) trackedIndex else d1
        val count = root.invokeIntGetter("N1") ?: 0
        if (count <= current + 1) {
            val now = System.currentTimeMillis()
            val key = "$current/$count"
            val waitingSince = if (tailWaitKey == key) tailWaitStartAtMs else now
            tailWaitKey = key
            tailWaitStartAtMs = waitingSince
            val forceLoad = now - waitingSince >= TAIL_FORCE_LOAD_MORE_MS
            root.callOfficialStoryLoadMore(current, force = forceLoad)
            if (forceLoad) {
                root.callOfficialStoryPlayModeNext()
                tailWaitStartAtMs = now
            }
            Log.x("StoryAutoNext: waiting official loadMore current=$current count=$count")
            return false
        }
        tailWaitKey = null
        val nextIndex = current + 1
        root.callOfficialStoryLoadMore(nextIndex, force = false)
        val moved = root.callOfficialStoryNext(nextIndex)
        if (moved) {
            trackedIndex = nextIndex
            backgroundEngineIndex = nextIndex
            backgroundEngineMoved = true
            liveEngineIndex = nextIndex
            Log.x("StoryAutoNext: advanced engine to index=$nextIndex (d1=$d1 engineIdx=$backgroundEngineIndex)")
        }
        return moved
    }.getOrElse {
        Log.e(it)
        false
    }

    private fun Any.resolveStoryHostFromPlayer(): Any? = runCatching {
        val hostFromPlayer = runCatching {
            javaClass.declaredFields.firstOrNull { it.name == "F" }
                ?.also { it.isAccessible = true }
                ?.get(this)
        }.getOrNull()
        hostFromPlayer?.takeIf { it.isStoryHost() }
            ?: activeStoryHost?.takeIf { it.isStoryHost() }
            ?: findStoryHost()
    }.getOrNull()

    private fun Any.callOfficialStoryLoadMore(index: Int, force: Boolean): Boolean = runCatching {
        var host = resolveStoryHostFromPlayer() ?: return@runCatching false
        val now = System.currentTimeMillis()
        if (!force && now - lastLoadMoreAtMs < LOAD_MORE_COOLDOWN_MS) return@runCatching false
        backgroundLoadActive = true
        try {
            if (host.javaClass.name == "com.bilibili.video.story.StoryVideoFragment") {
                return@runCatching host.callVideoFeedLoadMore(index, force)
            }
            if (host.javaClass.name == "com.bilibili.video.story.space.StorySpaceFragment") {
                if (force) {
                    host.forceStorySpaceHasMore()
                }
                if (host.callLoaderDirectly(force)) {
                    lastLoadMoreAtMs = now
                    return@runCatching true
                }
                if (host.callStorySpaceLoadMoreDirect()) {
                    lastLoadMoreAtMs = now
                    return@runCatching true
                }
                return@runCatching false
            }
            val videoFragment = collectReachableObjects(this, 6).firstOrNull {
                it.javaClass.name == "com.bilibili.video.story.StoryVideoFragment"
            }
            if (videoFragment != null) {
                activeStoryHost = videoFragment
                host = videoFragment
                return@runCatching host.callVideoFeedLoadMore(index, force)
            }
            false
        } finally {
            mainHandler.postDelayed({ backgroundLoadActive = false }, 10_000L)
        }
    }.getOrElse {
        Log.e(it)
        false
    }

    /**
     * For StoryVideoFragment: directly call StoryVideoLoader.i() with our cached
     * context, bypassing xq()'s getContext()==null check that blocks background loads.
     * Creates a w0 proxy callback that directly calls h1() on the player.
     */
    private fun Any.callVideoFeedLoadMore(index: Int, force: Boolean): Boolean {
        val ctx = runCatching {
            javaClass.getMethod("getContext").invoke(this) as? Context
        }.getOrNull() ?: cachedAppContext ?: return false

        val loaderField = javaClass.declaredFields.firstOrNull {
            it.type.name.contains("StoryVideoLoader")
        } ?: return false
        loaderField.isAccessible = true
        val loader = loaderField.get(this) ?: return false

        if (force) {
            runCatching {
                loader.javaClass.getMethod("k", Boolean::class.javaPrimitiveType).invoke(loader, false)
            }
        }

        val a0Field = javaClass.declaredFields.firstOrNull {
            it.type.name == "com.bilibili.video.story.player.a0"
        }
        a0Field?.isAccessible = true
        val a0Var = a0Field?.get(this) ?: return false

        val player = activeStoryPlayer ?: return false
        val dMethod = player.javaClass.methods.firstOrNull {
            it.name == "D" && it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType
        }
        val dValue = runCatching { dMethod?.invoke(player) as? Int }.getOrNull() ?: 0

        val currentDetail = runCatching {
            player.javaClass.methods.firstOrNull { it.name == "F1" && it.parameterCount == 0 }
                ?.invoke(player)
        }.getOrNull()
        val aid = runCatching {
            currentDetail?.javaClass?.getMethod("getAid")?.invoke(currentDetail)?.toString()
        }.getOrNull() ?: "0"
        val cid = runCatching {
            currentDetail?.javaClass?.getMethod("getCid")?.invoke(currentDetail)?.toString()
        }.getOrNull() ?: "0"

        val w0Class = runCatching {
            Class.forName("com.bilibili.video.story.w0", false, mClassLoader)
        }.getOrNull() ?: return false

        val w0Proxy = java.lang.reflect.Proxy.newProxyInstance(
            mClassLoader,
            arrayOf(w0Class),
        ) { _, method, args ->
            when (method.name) {
                "w1" -> {
                    val items = args?.firstOrNull() as? List<*>
                    if (items != null && items.isNotEmpty()) {
                        mainHandler.post {
                            runCatching {
                                val fieldI = player.javaClass.declaredFields.firstOrNull {
                                    it.name == "I" && it.type == Boolean::class.javaPrimitiveType
                                }
                                fieldI?.isAccessible = true
                                val wasI = fieldI?.getBoolean(player) ?: true
                                if (!wasI) fieldI?.setBoolean(player, true)
                                val h1 = player.javaClass.methods.firstOrNull {
                                    it.name == "h1" && it.parameterTypes.size == 1 &&
                                        List::class.java.isAssignableFrom(it.parameterTypes[0])
                                }
                                h1?.invoke(player, items)
                                if (!wasI) fieldI?.setBoolean(player, wasI)
                                Log.x("StoryAutoNext: video feed added ${items.size} items, new count=${player.invokeIntGetter("N1")}")
                            }.onFailure { Log.e(it) }
                        }
                    }
                    Unit
                }
                "v1" -> Unit
                "onError" -> Unit
                else -> Unit
            }
        }

        val iMethod = loader.javaClass.declaredMethods.firstOrNull {
            it.name == "i" && it.parameterTypes.size == 16
        } ?: return false
        iMethod.isAccessible = true

        return runCatching {
            iMethod.invoke(
                loader, ctx, a0Var, null, dValue,
                aid, cid, "", 0L,
                false, false, false, 0, 0L, "", 0L, w0Proxy,
            )
            Log.x("StoryAutoNext: called StoryVideoLoader.i() directly for recommendation feed")
            true
        }.getOrElse {
            Log.e(it)
            false
        }
    }

    /**
     * Directly call j.g() on the loader with application context,
     * bypassing Fragment.getContext() which may return null in background.
     * The aProxy returns FRESH player data each time so the pagination cursor advances.
     * The bProxy updates the loader's page state after receiving response data.
     */
    private fun Any.callLoaderDirectly(isRefresh: Boolean): Boolean {
        if (javaClass.name != "com.bilibili.video.story.space.StorySpaceFragment") return false
        val loaderField = javaClass.declaredFields.firstOrNull {
            it.type.name == "com.bilibili.video.story.space.j"
        } ?: return false
        loaderField.isAccessible = true
        val loader = loaderField.get(this) ?: return false
        val ctx = runCatching {
            javaClass.getMethod("getContext").invoke(this) as? Context
        }.getOrNull() ?: cachedAppContext ?: return false
        val player = activeStoryPlayer ?: return false
        val initialCount = player.invokeIntGetter("N1") ?: return false
        if (initialCount <= 0) return false
        val v1Method = player.javaClass.methods.firstOrNull {
            it.name == "V1" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return false
        val fragment = this
        val ownerFallback = runCatching {
            javaClass.declaredMethods.firstOrNull { it.name == "vq" && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }?.invoke(this)
        }.getOrNull()
        val loaderAClass = runCatching {
            Class.forName("com.bilibili.video.story.space.j\$a", false, mClassLoader)
        }.getOrNull() ?: return false
        val loaderBClass = runCatching {
            Class.forName("com.bilibili.video.story.space.j\$b", false, mClassLoader)
        }.getOrNull() ?: return false
        val aProxy = java.lang.reflect.Proxy.newProxyInstance(
            mClassLoader,
            arrayOf(loaderAClass),
        ) { _, method, args ->
            when (method.name) {
                "a" -> player.invokeIntGetter("N1") ?: 0
                "b" -> {
                    val idx = args?.firstOrNull() as? Int ?: -1
                    val currentCount = player.invokeIntGetter("N1") ?: 0
                    if (idx >= 0) {
                        runCatching { v1Method.invoke(player, idx) }.getOrNull()
                    } else {
                        runCatching { v1Method.invoke(player, (currentCount - 1).coerceAtLeast(0)) }.getOrNull()
                    }
                }
                "getOwner" -> {
                    val currentCount = player.invokeIntGetter("N1") ?: 0
                    val lastItem = runCatching { v1Method.invoke(player, (currentCount - 1).coerceAtLeast(0)) }.getOrNull()
                    runCatching { lastItem?.javaClass?.getMethod("getOwner")?.invoke(lastItem) }.getOrNull()
                        ?: ownerFallback
                }
                else -> null
            }
        }
        val loaderKMethod = loader.javaClass.methods.firstOrNull { it.name == "k" && it.parameterCount == 1 }
        val loaderJMethod = loader.javaClass.methods.firstOrNull { it.name == "j" && it.parameterCount == 1 }
        val bProxy = java.lang.reflect.Proxy.newProxyInstance(
            mClassLoader,
            arrayOf(loaderBClass),
        ) { _, method, args ->
            when (method.name) {
                "a" -> {
                    val data = args?.firstOrNull() ?: return@newProxyInstance Unit
                    val page = runCatching { data.javaClass.getMethod("getPage").invoke(data) }.getOrNull()
                    if (page != null) {
                        runCatching { loaderKMethod?.invoke(loader, page) }
                    }
                    val meta = runCatching { data.javaClass.getMethod("getMeta").invoke(data) }.getOrNull()
                    if (meta != null) {
                        runCatching { loaderJMethod?.invoke(loader, meta) }
                    }
                    val items = runCatching {
                        data.javaClass.getMethod("getItems").invoke(data) as? List<*>
                    }.getOrNull()
                    if (items != null && items.isNotEmpty()) {
                        mainHandler.post {
                            runCatching {
                                val fieldI = player.javaClass.declaredFields.firstOrNull {
                                    it.name == "I" && it.type == Boolean::class.javaPrimitiveType
                                }
                                fieldI?.isAccessible = true
                                val wasI = fieldI?.getBoolean(player) ?: true
                                if (!wasI) fieldI?.setBoolean(player, true)
                                val h1 = player.javaClass.methods.firstOrNull {
                                    it.name == "h1" && it.parameterTypes.size == 1 &&
                                        List::class.java.isAssignableFrom(it.parameterTypes[0])
                                }
                                h1?.invoke(player, items)
                                if (!wasI) fieldI?.setBoolean(player, wasI)
                                Log.x("StoryAutoNext: loader direct added ${items.size} items, new count=${player.invokeIntGetter("N1")}")
                            }.onFailure { Log.e(it) }
                        }
                    }
                    Unit
                }
                "onError" -> {
                    Log.x("StoryAutoNext: loader direct onError")
                    Unit
                }
                else -> Unit
            }
        }
        val gMethod = loader.javaClass.declaredMethods.firstOrNull {
            it.name == "g" && it.parameterTypes.size == 8
        } ?: return false
        val maskField = runCatching {
            val playerField = javaClass.declaredFields.firstOrNull {
                it.type.name == "com.bilibili.video.story.player.StoryPagerPlayer"
            }
            playerField?.isAccessible = true
            val pagerPlayer = playerField?.get(this) ?: player
            pagerPlayer.javaClass.methods.firstOrNull {
                it.name == "D" && it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType
            }?.invoke(pagerPlayer) as? Int
        }.getOrNull() ?: 0
        return runCatching {
            gMethod.isAccessible = true
            gMethod.invoke(loader, ctx, maskField, isRefresh, true, null, false, aProxy, bProxy)
            Log.x("StoryAutoNext: called loader.g() directly, refresh=$isRefresh")
            true
        }.getOrElse {
            Log.e(it)
            false
        }
    }

    private fun Any.invokeIntGetter(vararg names: String): Int? {
        names.forEach { name ->
            val method = javaClass.cachedStoryMethod(name) ?: return@forEach
            val value = runCatching { method.invoke(this) as? Number }.getOrNull()
            if (value != null) return value.toInt()
        }
        return null
    }

    private fun Any.invokeLongGetter(vararg names: String): Long? {
        names.forEach { name ->
            val method = javaClass.cachedStoryMethod(name) ?: return@forEach
            val value = runCatching { method.invoke(this) as? Number }.getOrNull()
            if (value != null) return value.toLong()
        }
        return null
    }

    private fun Any.callOfficialStoryNext(index: Int): Boolean = runCatching {
        forcePlayAtIndex(index)
        true
    }.getOrElse {
        Log.e(it)
        false
    }

    /**
     * After F2 sets the ViewPager2 position, ViewPager2 won't bind new views in
     * background, so the video never switches. We bypass this by directly invoking
     * StoryPlayer.h2(index, ...) after resetting the PlayHandler's resolved state
     * to force O2() (fresh resolve & play) instead of resume().
     */
    private fun Any.forcePlayAtIndex(index: Int) {
        runCatching {
            val storyPlayerField = javaClass.declaredFields.firstOrNull {
                it.type.name == "com.bilibili.video.story.player.StoryPlayer"
            } ?: return
            storyPlayerField.isAccessible = true
            val storyPlayer = storyPlayerField.get(this) ?: return

            val playHandlerField = storyPlayer.javaClass.declaredFields.firstOrNull {
                it.type.name.contains("StoryVideoPlayHandler")
            }
            playHandlerField?.isAccessible = true
            val playHandler = playHandlerField?.get(storyPlayer)

            val i2Method = storyPlayer.javaClass.declaredMethods.firstOrNull {
                it.name == "i2" &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }
            i2Method?.isAccessible = true
            i2Method?.invoke(storyPlayer, index)

            if (playHandler != null) {
                val resetMethod = playHandler.javaClass.declaredMethods.firstOrNull {
                    it.name == "Y" && it.parameterCount == 0
                }
                resetMethod?.isAccessible = true
                resetMethod?.invoke(playHandler)
            }

            val h2Method = storyPlayer.javaClass.declaredMethods.firstOrNull {
                it.name == "h2" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (h2Method != null) {
                h2Method.isAccessible = true
                h2Method.invoke(storyPlayer, index, true, false, true)
                Log.x("StoryAutoNext: forced StoryPlayer.h2($index) after PlayHandler reset")
            }
        }.onFailure { Log.e(it) }
    }

    private fun Any.callOfficialStoryPlayModeNext(): Boolean = runCatching {
        val method = javaClass.methods.firstOrNull {
            it.name == "S2" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
        } ?: return@runCatching false
        method.isAccessible = true
        method.invoke(this, 1, false)
        Log.x("StoryAutoNext: switched official play mode to next")
        true
    }.getOrElse {
        Log.e(it)
        false
    }


    private fun Any.callStorySpaceLoadMoreDirect(): Boolean {
        if (javaClass.name != "com.bilibili.video.story.space.StorySpaceFragment") return false
        val method = javaClass.declaredMethods.firstOrNull {
            it.name == "Cq" &&
                it.parameterTypes.size == 7 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[3] == Boolean::class.javaPrimitiveType
        } ?: return false
        return runCatching {
            forceStorySpaceHasMore()
            method.isAccessible = true
            method.invoke(this, false, true, false, true, null, null, null)
            Log.x("StoryAutoNext: requested direct StorySpaceFragment.Cq loadMore")
            true
        }.getOrElse {
            Log.e(it)
            false
        }
    }

    private fun Any.forceStorySpaceHasMore() {
        if (javaClass.name != "com.bilibili.video.story.space.StorySpaceFragment") return
        runCatching {
            val loader = javaClass.declaredFields.firstOrNull {
                it.type.name == "com.bilibili.video.story.space.j"
            } ?: return
            loader.isAccessible = true
            val loaderInstance = loader.get(this) ?: return
            loaderInstance.javaClass.methods.firstOrNull { it.name == "i" && it.parameterCount == 0 }
                ?.invoke(loaderInstance)
        }.onFailure { Log.e(it) }
    }

    private fun Any.findStoryHost(): Any? {
        val candidates = collectReachableObjects(this, 6).filter { it.isStoryHost() }
        val preferred = candidates.firstOrNull {
            it.javaClass.name == "com.bilibili.video.story.StoryVideoFragment"
        } ?: candidates.firstOrNull()
        if (preferred != null) activeStoryHost = preferred
        return preferred
    }

    private fun Any.isStoryHost(): Boolean =
        javaClass.name == HOST_MAIN || javaClass.name == HOST_SPACE

    private fun Any.storyIdentity(): String {
        val bvid = runCatching {
            javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name in setOf("getBvid", "getBvId") }
                ?.invoke(this) as? String
        }.getOrNull()
        val cid = runCatching {
            javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name == "getCid" }
                ?.invoke(this) as? Number
        }.getOrNull()?.toLong()
        return "${bvid ?: "unknown"}/${cid ?: 0L}"
    }
}
