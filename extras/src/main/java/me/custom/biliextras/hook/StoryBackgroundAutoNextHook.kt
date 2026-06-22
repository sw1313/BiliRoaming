package me.custom.biliextras.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import me.custom.biliextras.BiliPackageLite.Companion.instance
import me.custom.biliextras.sponsorblock.SponsorBlockController
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

        /** Minimum position before seeding official f208480t for bg engine-ahead F2 catch-up. */
        const val BACKGROUND_RESUME_MIN_MS = 500L
        val hookedConcreteClasses = ConcurrentHashMap.newKeySet<String>()
        val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        var activeStoryPlayer: Any? = null

        /** Main-feed StoryPagerPlayer; survives activeStoryPlayer pointing at UP-space tab. */
        @Volatile
        var mainFeedStoryPlayer: Any? = null

        /** UP-space StoryPagerPlayer; parallel to mainFeedStoryPlayer. */
        @Volatile
        var upSpaceStoryPlayer: Any? = null

        /** Which tab owns the current background auto-next session (HOST_MAIN / HOST_SPACE). */
        @Volatile
        var backgroundSessionHost: String? = null

        /** StoryVideoActivity outer ViewPager index: 0=main feed, 1=UP space. */
        @Volatile
        var visibleOuterTabIndex = 0

        @Volatile
        var activePlayerCore: Any? = null

        @Volatile
        var activeStoryHost: Any? = null

        @Volatile
        var activeStoryKey: String? = null

        /** bvid/cid of the video the engine is actually playing; survives adapter reloads. */
        @Volatile
        var playingStoryKey: String? = null

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

        /** Active story host class name; only updated for [activeStoryPlayer], never from the other tab's player. */
        @Volatile
        var trackedIndexHost: String? = null

        /** StoryPagerPlayer.I2 host per player instance (field F); StoryVideoActivity has two players. */
        val playerHostByInstance = ConcurrentHashMap<Int, String>()

        /** Engine index from background auto-next; kept until pager D1 catches up. */
        @Volatile
        var liveEngineIndex = -1

        internal const val HOST_MAIN = "com.bilibili.video.story.StoryVideoFragment"
        internal const val HOST_SPACE = "com.bilibili.video.story.space.StorySpaceFragment"

        @Volatile
        var backgroundLoadActive = false

        @Volatile
        var isInBackground = false

        /** StoryVideoActivity onPause/onResume — false during outer-tab switches while activity stays visible. */
        @Volatile
        var activityPaused = false

        /**
         * Set in u2 when Activity truly pauses (app background). Cleared after w2 handoff finishes
         * or on tab switch / new StoryVideoActivity — gates progress bar retention only to bg→fg.
         */
        @Volatile
        var appBackgroundResumeHandoffEligible = false

        /** True when triggerNextStory/triggerPreviousBackground moved the engine this background session. */
        @Volatile
        var backgroundEngineMoved = false

        /** Last engine index reached by background auto-next; survives D1=0 pager staleness. */
        @Volatile
        var backgroundEngineIndex = -1

        /** Host + player instance that ran the current background session (StoryVideoActivity has two players). */
        @Volatile
        var backgroundEngineHost: String? = null

        @Volatile
        var backgroundEnginePlayerId: Int = -1

        /** Guards syncPagerToEngineIndex ↔ rebindOfficialSurfaceAtD1 reentry on the main thread. */
        @Volatile
        var pagerSyncInProgressPlayerId: Int = -1

        /** Our rebind path invokes x2 — must not be blocked by the i3→x2 guard hook. */
        @Volatile
        var officialX2RebindInProgress: Boolean = false

        @Volatile
        var cachedAppContext: Context? = null

        // When false (media-button-only), we only capture the player + expose
        // triggerNext()/triggerPrevious(), and never alter looping or auto-advance.
        fun isAutoNextEnabled(): Boolean = ePrefs.getBoolean(PREF_KEY, false)

        // Live instance so MediaButtonControlHook can drive next/previous on demand.
        @Volatile
        var liveInstance: StoryBackgroundAutoNextHook? = null

        @Volatile
        var pendingForegroundHandoffPosMs: Long = -1L

        @Volatile
        var pendingForegroundHandoffId: String? = null

        @Volatile
        var pendingForegroundHandoffPlayerId: Int = -1

        /** StoryPagerPlayer that owns an in-flight bg→fg f208480t seed. */
        @Volatile
        var foregroundHandoffStoryPlayer: Any? = null

        /** Player instance we seeded via StoryPagerPlayer int field t (f208480t). */
        @Volatile
        var nativeStartPositionSeededPlayerId: Int = -1

        @Volatile
        var postResumeCatchUpRunnable: Runnable? = null

        /** Saved on u2 before official w2 h1 can truncate the feed (log: count=36 → 1 before F2). */
        @Volatile
        var resumeHandoffIndex = -1

        @Volatile
        var resumeHandoffId: String? = null

        @Volatile
        var resumeHandoffCount = -1

        @Volatile
        var resumeHandoffPlayerId = -1

        /** StoryDetail items snapshotted on u2; restored when w2 h1 collapses the chain (log: 39→1). */
        @Volatile
        var resumeFeedSnapshot: List<Any>? = null

        @Volatile
        var resumeFeedRestoreInProgress = false

        @Volatile
        var blockDestructiveH1UntilMs = 0L

        /** Main-feed video identity to carry when switching outer tab to UP space (and reverse). */
        @Volatile
        var outerTabHandoffId: String? = null

        @Volatile
        var outerTabHandoffSourceHost: String? = null

        /** Set on outer tab switch; consumed by the target tab's w2 or a short delayed catch-up. */
        @Volatile
        var outerTabHandoffPending = false

        @Volatile
        var outerTabHandoffSetAtMs = 0L

        @Volatile
        var outerTabHandoffCatchUpRunnable: Runnable? = null

        /** Only apply outer-tab handoff shortly after a visible tab switch (log 02:34:37 stale 26min id). */
        private const val OUTER_TAB_HANDOFF_TTL_MS = 5_000L

        @Volatile
        var endPollingRunnable: Runnable? = null

        fun cancelOuterTabHandoffCatchUp() {
            outerTabHandoffCatchUpRunnable?.let { mainHandler.removeCallbacks(it) }
            outerTabHandoffCatchUpRunnable = null
        }

        fun cancelPostResumeCatchUp() {
            postResumeCatchUpRunnable?.let { mainHandler.removeCallbacks(it) }
            postResumeCatchUpRunnable = null
        }

        fun cancelForegroundResumeHandoff(player: Any? = null) {
            val handoffPlayer = player ?: foregroundHandoffStoryPlayer
            clearNativeStartPositionIfSeeded(handoffPlayer)
            pendingForegroundHandoffPosMs = -1L
            pendingForegroundHandoffId = null
            pendingForegroundHandoffPlayerId = -1
            foregroundHandoffStoryPlayer = null
        }

        fun finishAppBackgroundResumeSession() {
            appBackgroundResumeHandoffEligible = false
            resumeHandoffIndex = -1
            resumeHandoffId = null
            resumeHandoffCount = -1
            resumeHandoffPlayerId = -1
            resumeFeedSnapshot = null
            blockDestructiveH1UntilMs = 0L
        }

        /** Clear our seek-on-prepare seed so the next item does not inherit bg resume position. */
        fun clearNativeStartPositionIfSeeded(player: Any?) {
            if (player == null) return
            if (nativeStartPositionSeededPlayerId != System.identityHashCode(player)) return
            runCatching {
                val field = player.javaClass.declaredFields.firstOrNull {
                    it.name == "t" && it.type == Int::class.javaPrimitiveType
                } ?: return
                field.isAccessible = true
                field.setInt(player, 0)
                nativeStartPositionSeededPlayerId = -1
            }.onFailure { Log.e(it) }
        }

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
        hookStoryVideoActivityTab()
        hookStoryPagerPlayer()
        Log.s("startHook: StoryBackgroundAutoNext (autoNext=${isAutoNextEnabled()} mediaButton=${ePrefs.getBoolean(MEDIA_BUTTON_KEY, false)})")
    }

    /** Called when the in-player menu toggles background auto-next. */
    fun onAutoNextPrefChanged(enabled: Boolean) {
        val player = if (isInBackground) storyPlayerForBackgroundAutoNext() else activeStoryPlayer
        if (player != null && isInBackground) {
            applyBackgroundLoopMode(player, enabled)
        }
        if (enabled) {
            ensurePolling()
            Log.trace { "StoryAutoNext: auto-next ON (background loop off, polling=$polling)" }
        } else {
            Log.trace { "StoryAutoNext: auto-next OFF (single loop restored)" }
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
            Log.trace { "StoryAutoNext: fg no previous (current=$current)" }
            return@runCatching false
        }
        if (forward && (count <= 0 || target >= count - 1)) {
            // Approaching/at the tail: ask the feed to load more so the page exists.
            root.callOfficialStoryLoadMore(target, force = false)
        }
        var moved = false
        root.withStoryPagerActiveFlag {
            moved = root.callOfficialPagerAdvance(target, smooth = true)
        }
        if (moved) {
            trackedIndex = target
            liveEngineIndex = target
            backgroundEngineMoved = false
            backgroundEngineIndex = -1
            root.captureEnginePlayingIdentity()?.let {
                playingStoryKey = it
                activeStoryKey = it
            } ?: storyIdentityAt(target)?.let {
                playingStoryKey = it
                activeStoryKey = it
            }
            cancelPostResumeCatchUp()
            cancelForegroundResumeHandoff()
            Log.trace { "StoryAutoNext: fg scroll ${if (forward) "next" else "prev"} index=$target (current=$current count=$count)" }
        }
        moved
    }.getOrElse {
        Log.e(it)
        false
    }

    /** Background previous: jump to the prior already-loaded item via the engine. */
    private fun triggerPreviousBackground(): Boolean = runCatching {
        val root = storyPlayerForBackgroundAutoNext() ?: return@runCatching false
        val d1 = root.invokeIntGetter("D1", "getIndex") ?: return@runCatching false
        val current = root.resolveBackgroundCurrentIndex()
        if (current <= 0) {
            Log.trace { "StoryAutoNext: no previous story (current=$current)" }
            return@runCatching false
        }
        val prevIndex = current - 1
        val moved = root.callOfficialStoryNext(prevIndex)
        if (moved) {
            trackedIndex = prevIndex
            liveEngineIndex = prevIndex
            backgroundEngineIndex = prevIndex
            backgroundEngineMoved = true
            rememberBackgroundEnginePlayer(root)
            storyIdentityAt(prevIndex)?.let {
                playingStoryKey = it
                activeStoryKey = it
            }
            Log.trace { "StoryAutoNext: moved to previous index=$prevIndex (d1=$d1)" }
        }
        moved
    }.getOrElse {
        Log.e(it)
        false
    }

    private fun rememberBackgroundEnginePlayer(player: Any) {
        backgroundEnginePlayerId = System.identityHashCode(player)
        backgroundEngineHost = player.storyPagerHostClass()
            ?: playerHostByInstance[backgroundEnginePlayerId]
            ?: trackedIndexHost
    }

    /**
     * StoryVideoActivity binds two StoryPagerPlayers (main feed + UP space). After a long pause
     * activeStoryPlayer can point at the wrong instance while w2 on the main-feed player still
     * needs F2 sync (see log active=false skip F2 engine=10).
     */
    private fun Any.shouldRunW2PagerSync(pendingIndex: Int, pagerBefore: Int): Boolean {
        if (pendingIndex < 0) return false
        val pagerBehindEngine = pendingIndex > pagerBefore
        val playingId = captureEnginePlayingIdentity() ?: playingStoryKey ?: activeStoryKey
        val pagerId = if (pagerBefore >= 0) storyIdentityAt(pagerBefore) else null
        val identityMismatch = playingId != null && pagerId != null && !identitiesMatch(playingId, pagerId)
        if (!backgroundEngineMoved && !pagerBehindEngine && !identityMismatch) return false
        val playerId = System.identityHashCode(this)
        val host = storyPagerHostClass() ?: playerHostByInstance[playerId]
        if (backgroundEngineHost != null && host != null && host != backgroundEngineHost) {
            return false
        }
        if (host == HOST_SPACE && storyPagerTag() == "StorySpaceFragment" && pagerBehindEngine &&
            visibleSessionHost() != HOST_SPACE &&
            backgroundEngineHost != HOST_SPACE
        ) {
            return false
        }
        if (host != null && backgroundSessionHost != null && host != backgroundSessionHost) {
            return false
        }
        if (this === activeStoryPlayer) return true
        if (backgroundEnginePlayerId >= 0 && playerId == backgroundEnginePlayerId) {
            captureActiveStoryPlayer(this)
            Log.trace { "StoryAutoNext: w2 adopt background-engine player for F2 sync" }
            return true
        }
        if (host == HOST_MAIN && storyPagerTag() == "StoryVideoFragment") {
            captureActiveStoryPlayer(this)
            Log.trace { "StoryAutoNext: w2 adopt main-feed player for F2 sync (stale activeStoryPlayer)" }
            return true
        }
        return false
    }

    private fun Any.playerHostName(): String? =
        storyPagerHostClass() ?: playerHostByInstance[System.identityHashCode(this)]

    private fun Any.isMainFeedStoryPlayer(): Boolean =
        playerHostName() == HOST_MAIN && storyPagerTag() == "StoryVideoFragment"

    private fun rememberMainFeedStoryPlayer(player: Any) {
        if (player.isMainFeedStoryPlayer()) {
            mainFeedStoryPlayer = player
        }
    }

    private fun rememberUpSpaceStoryPlayer(player: Any) {
        if (player.playerHostName() == HOST_SPACE && player.storyPagerTag() == "StorySpaceFragment") {
            upSpaceStoryPlayer = player
        }
    }

    private fun clearOuterTabHandoff() {
        outerTabHandoffId = null
        outerTabHandoffSourceHost = null
        outerTabHandoffPending = false
        outerTabHandoffSetAtMs = 0L
        cancelOuterTabHandoffCatchUp()
    }

    private fun scheduleOuterTabHandoffCatchUp() {
        if (!outerTabHandoffPending) return
        cancelOuterTabHandoffCatchUp()
        val runnable = Runnable {
            outerTabHandoffCatchUpRunnable = null
            if (!outerTabHandoffPending || activityPaused || isInBackground) return@Runnable
            if (System.currentTimeMillis() - outerTabHandoffSetAtMs > OUTER_TAB_HANDOFF_TTL_MS) {
                clearOuterTabHandoff()
                return@Runnable
            }
            val player = when (visibleSessionHost()) {
                HOST_SPACE -> upSpaceStoryPlayer
                else -> mainFeedStoryPlayer
            } ?: activeStoryPlayer ?: run {
                clearOuterTabHandoff()
                return@Runnable
            }
            if (player.playerHostName() != visibleSessionHost()) return@Runnable
            player.trySyncOuterTabHandoff()
            if (outerTabHandoffPending) clearOuterTabHandoff()
        }
        outerTabHandoffCatchUpRunnable = runnable
        mainHandler.postDelayed(runnable, 900L)
    }

    /** Save the leaving tab's playing video before session keys are cleared for the target tab w2(). */
    private fun captureOuterTabHandoffBeforeSwitch() {
        val targetHost = visibleSessionHost()
        val sourcePlayer = when (targetHost) {
            HOST_SPACE -> mainFeedStoryPlayer
            else -> upSpaceStoryPlayer
        }
        val sourceD1 = sourcePlayer?.invokeIntGetter("D1", "getIndex") ?: -1
        // jadx: F1 = adapter.Z0(D1); engine l() can lag after swipe (log 034854: stale cid on tab switch).
        outerTabHandoffId = sourcePlayer?.currentStoryIdentity()
            ?: if (sourceD1 >= 0) sourcePlayer?.storyIdentityAt(sourceD1) else null
            ?: sourcePlayer?.captureEnginePlayingIdentity()
            ?: playingStoryKey
            ?: activeStoryKey
        outerTabHandoffSourceHost = when (targetHost) {
            HOST_SPACE -> HOST_MAIN
            else -> HOST_SPACE
        }
        if (outerTabHandoffId != null) {
            outerTabHandoffPending = true
            outerTabHandoffSetAtMs = System.currentTimeMillis()
            Log.trace {
                "StoryAutoNext: outer tab handoff from $outerTabHandoffSourceHost " +
                    "id=$outerTabHandoffId -> $targetHost"
            }
        } else {
            outerTabHandoffSourceHost = null
            outerTabHandoffPending = false
            outerTabHandoffSetAtMs = 0L
        }
    }

    private fun Any.shouldSyncOuterTabHandoff(): Boolean {
        if (!outerTabHandoffPending) return false
        if (appBackgroundResumeHandoffEligible) return false
        if (System.currentTimeMillis() - outerTabHandoffSetAtMs > OUTER_TAB_HANDOFF_TTL_MS) {
            Log.trace { "StoryAutoNext: outer tab handoff expired id=$outerTabHandoffId" }
            clearOuterTabHandoff()
            return false
        }
        val handoffId = outerTabHandoffId ?: return false
        val sourceHost = outerTabHandoffSourceHost ?: return false
        val myHost = playerHostName() ?: return false
        if (myHost != visibleSessionHost()) return false
        if (myHost == sourceHost) return false
        return handoffId.isNotBlank()
    }

    /** Align target-tab pager to the video the user was watching on the other outer tab. */
    private fun Any.trySyncOuterTabHandoff(): Boolean {
        if (!shouldSyncOuterTabHandoff()) return false
        val handoffId = outerTabHandoffId ?: return false
        val d1 = invokeIntGetter("D1", "getIndex") ?: -1
        val pagerId = if (d1 >= 0) storyIdentityAt(d1) else null
        if (pagerId != null && identitiesMatch(pagerId, handoffId)) {
            clearOuterTabHandoff()
            playingStoryKey = handoffId
            activeStoryKey = handoffId
            trackedIndex = d1
            liveEngineIndex = d1
            trackedIndexHost = playerHostName()
            Log.trace { "StoryAutoNext: outer tab handoff already at d1=$d1 id=$handoffId" }
            return true
        }
        val idx = indexOfStoryIdentity(handoffId)
        if (idx < 0) {
            Log.trace {
                "StoryAutoNext: outer tab handoff miss id=$handoffId count=${invokeIntGetter("N1")}"
            }
            clearOuterTabHandoff()
            return false
        }
        // User swiped ahead on this tab — never F2 backward for a stale/wrong handoff id (log 034854: d1=1 -> 0).
        if (d1 > idx && pagerId != null && !identitiesMatch(pagerId, handoffId)) {
            Log.trace {
                "StoryAutoNext: outer tab handoff skip backward F2 d1=$d1 -> $idx " +
                    "current=$pagerId handoff=$handoffId"
            }
            clearOuterTabHandoff()
            return false
        }
        Log.trace { "StoryAutoNext: outer tab handoff F2 d1=$d1 -> $idx id=$handoffId" }
        return runCatching {
            syncPagerToEngineIndex(idx, handoffId)
            clearOuterTabHandoff()
            true
        }.getOrElse {
            Log.e(it)
            false
        }
    }

    /**
     * Outer tab switch while activity is still visible — not true background; stop bg polling.
     * Do NOT sync trackedIndex here: onPageSelected runs before the target tab's w2() resumes
     * the adapter (UP space D1/F1 are still stale — log 220906 timing gap ~600ms).
     */
    private fun reconcileForegroundOnOuterTabSwitch() {
        if (activityPaused) return
        captureOuterTabHandoffBeforeSwitch()
        if (isInBackground || polling) {
            isInBackground = false
            backgroundSessionHost = null
            stopEndPolling()
            Log.trace {
                "StoryAutoNext: outer tab foreground — stop bg polling host=${visibleSessionHost()}"
            }
        }
        mainFeedStoryPlayer?.let { applyBackgroundLoopMode(it, autoNext = false) }
        upSpaceStoryPlayer?.let { applyBackgroundLoopMode(it, autoNext = false) }
        appBackgroundResumeHandoffEligible = false
        cancelForegroundResumeHandoff()
        // Drop cross-tab keys before target tab w2(); log 00:16:36 had main-feed id on UP d1=0.
        playingStoryKey = null
        activeStoryKey = null
        trackedIndex = -1
        liveEngineIndex = -1
        backgroundEngineMoved = false
        backgroundEngineIndex = -1
        trackedIndexHost = visibleSessionHost()
        when (visibleSessionHost()) {
            HOST_SPACE -> upSpaceStoryPlayer?.let { activeStoryPlayer = it }
            else -> mainFeedStoryPlayer?.let { activeStoryPlayer = it }
        }
        scheduleOuterTabHandoffCatchUp()
    }

    private fun Any.syncVisibleTabTrackingAfterW2() {
        if (isInBackground || activityPaused) return
        if (playerHostName() != visibleSessionHost()) return
        if (!backgroundEngineMoved) {
            val d1 = invokeIntGetter("D1", "getIndex") ?: -1
            val engineIdx = captureEnginePlayingIndex()
            val engineId = captureEnginePlayingIdentity() ?: playingStoryKey
            if (engineIdx >= 0 && d1 >= 0 && engineId != null) {
                val pagerId = storyIdentityAt(d1)
                if (!identitiesMatch(engineId, pagerId)) {
                    trackedIndex = engineIdx
                    liveEngineIndex = engineIdx
                    playingStoryKey = engineId
                    activeStoryKey = engineId
                    trackedIndexHost = playerHostName()
                    Log.trace { "StoryAutoNext: w2 tracking from engine=$engineIdx id=$engineId " +
                            "(d1=$d1 preloaded/stale pager)" }
                    captureActiveStoryPlayer(this)
                    return
                }
            }
            if (d1 >= 0 && trackedIndex > d1) {
                val pagerId = storyIdentityAt(d1)
                val sessionId = playingStoryKey ?: activeStoryKey
                if (sessionId != null && pagerId != null && !identitiesMatch(sessionId, pagerId)) {
                    Log.trace { "StoryAutoNext: w2 keep session tracked=$trackedIndex live=$liveEngineIndex " +
                            "(d1=$d1 stale pager after recreate)" }
                    captureActiveStoryPlayer(this)
                    return
                }
            }
        }
        // Official w2 only resumes adapter — never realigns indices; refresh metadata only.
        syncPlayerTrackingFromPager("w2-visible", full = false)
        captureActiveStoryPlayer(this)
    }

    /** Outer ViewPager tab → session host (authoritative over stale activeStoryPlayer). */
    private fun visibleSessionHost(): String =
        if (visibleOuterTabIndex == 1) HOST_SPACE else HOST_MAIN

    /** Drop stale companion state only when StoryVideoActivity is finishing — not lock-screen recreate. */
    private fun resetStorySessionOnActivityBoundary(reason: String) {
        isInBackground = false
        activityPaused = false
        backgroundSessionHost = null
        backgroundEngineMoved = false
        backgroundEngineIndex = -1
        backgroundEngineHost = null
        backgroundEnginePlayerId = -1
        appBackgroundResumeHandoffEligible = false
        liveEngineIndex = -1
        trackedIndex = -1
        trackedIndexHost = null
        playingStoryKey = null
        tailWaitKey = null
        pagerSyncInProgressPlayerId = -1
        officialX2RebindInProgress = false
        clearOuterTabHandoff()
        resumeFeedSnapshot = null
        cancelForegroundResumeHandoff()
        stopEndPolling()
        Log.trace { "StoryAutoNext: reset session ($reason)" }
    }

    private fun syncVisibleOuterTabFromActivity(activity: Any) {
        val pager = runCatching {
            activity.javaClass.declaredFields.firstOrNull { f ->
                f.type.name == "com.bilibili.video.story.view.StoryViewPager"
            }?.apply { isAccessible = true }?.get(activity)
        }.getOrNull() ?: return
        val index = runCatching {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as? Int
        }.getOrNull() ?: return
        if (visibleOuterTabIndex != index) {
            visibleOuterTabIndex = index
            Log.trace { "StoryAutoNext: outer tab index=$index host=${visibleSessionHost()}" }
        }
    }

    /**
     * Track StoryVideoActivity outer tab (main feed vs UP space).
     * onPause runs before fragment u2/w2 so bg session picks the visible tab.
     */
    private fun hookStoryVideoActivityTab() {
        val activityClass = runCatching {
            Class.forName("com.bilibili.video.story.StoryVideoActivity", false, mClassLoader)
        }.getOrNull() ?: return
        activityClass.hookMethod("onDestroy") { chain ->
            val finishing = runCatching {
                chain.thisObject.javaClass.getMethod("isFinishing").invoke(chain.thisObject) as? Boolean
            }.getOrNull() == true
            if (finishing) {
                resetStorySessionOnActivityBoundary("onDestroy finishing")
            }
            chain.proceed()
        }
        activityClass.hookMethod("onPause") { chain ->
            activityPaused = true
            // i3→x2 runs on onPagerIn before fragment w2; mark session early so x2 guard works.
            appBackgroundResumeHandoffEligible = true
            syncVisibleOuterTabFromActivity(chain.thisObject)
            chain.proceed()
        }
        activityClass.hookMethod("onResume") { chain ->
            activityPaused = false
            syncVisibleOuterTabFromActivity(chain.thisObject)
            chain.proceed()
        }
        val pagerListenerClass = runCatching {
            Class.forName("com.bilibili.video.story.u", false, mClassLoader)
        }.getOrNull() ?: return
        pagerListenerClass.hookMethod("onPageSelected", Int::class.javaPrimitiveType!!) { chain ->
            val index = chain.args[0] as Int
            if (visibleOuterTabIndex != index) {
                visibleOuterTabIndex = index
                Log.trace { "StoryAutoNext: outer tab selected index=$index host=${visibleSessionHost()}" }
                reconcileForegroundOnOuterTabSwitch()
            }
            chain.proceed()
        }
        Log.s("StoryAutoNext: hooked StoryVideoActivity tab tracking")
    }

    /** Tab that owns bg auto-next: explicit session, visible tab, then fallbacks. */
    private fun currentBackgroundSessionHost(): String? =
        backgroundSessionHost
            ?: visibleSessionHost().takeIf { isInBackground }
            ?: activeStoryPlayer?.playerHostName()
            ?: trackedIndexHost

    /** Player that owns background auto-next for the visible tab. */
    private fun storyPlayerForBackgroundAutoNext(): Any? {
        when (visibleSessionHost()) {
            HOST_SPACE -> {
                upSpaceStoryPlayer?.let { return it }
                activeStoryPlayer?.takeIf { it.playerHostName() == HOST_SPACE }?.let { return it }
            }
            else -> {
                mainFeedStoryPlayer?.let { return it }
                activeStoryPlayer?.takeIf { it.isMainFeedStoryPlayer() }?.let { return it }
            }
        }
        if (backgroundEngineHost == HOST_SPACE) {
            return upSpaceStoryPlayer ?: activeStoryPlayer
        }
        return mainFeedStoryPlayer ?: activeStoryPlayer
    }

    /** UP space tab is idle while main-feed outer tab is visible. */
    private fun isUpSpacePlayerWithoutBgSession(host: String?): Boolean =
        host == HOST_SPACE && visibleSessionHost() != HOST_SPACE

    /** Only the player on the visible outer tab should toggle bg loop / polling. */
    private fun Any.shouldManageBackgroundLoopMode(): Boolean {
        val host = playerHostName() ?: return false
        val visible = visibleSessionHost()
        if (host == HOST_SPACE && storyPagerTag() == "StorySpaceFragment") {
            return visible == HOST_SPACE
        }
        if (isMainFeedStoryPlayer()) {
            return visible == HOST_MAIN
        }
        return false
    }

    /** StoryPagerPlayer tag, e.g. "StoryVideoFragment" / "StorySpaceFragment" (field f208454a). */
    private fun Any.storyPagerTag(): String? = runCatching {
        javaClass.declaredFields.firstOrNull { f ->
            if (f.type != String::class.java) return@firstOrNull false
            f.isAccessible = true
            val v = f.get(this) as? String
            v == "StoryVideoFragment" || v == "StorySpaceFragment"
        }?.apply { isAccessible = true }?.get(this) as? String
    }.getOrNull()

    /** Host fragment bound via I2(host) -> StoryPagerPlayer field F (action.f). */
    private fun Any.storyPagerHostClass(): String? = runCatching {
        val actionHostType = runCatching {
            Class.forName("com.bilibili.video.story.action.f", false, javaClass.classLoader)
        }.getOrNull()
        val field = javaClass.declaredFields.firstOrNull { f ->
            f.name == "F" || (actionHostType != null && actionHostType.isAssignableFrom(f.type))
        } ?: return@runCatching null
        field.isAccessible = true
        val host = field.get(this) ?: return@runCatching null
        host.javaClass.name
    }.getOrNull()

    private fun rememberStoryPlayerHost(player: Any, host: Any?) {
        if (host == null) return
        val hostName = host.javaClass.name
        if (hostName !in setOf(HOST_MAIN, HOST_SPACE)) return
        playerHostByInstance[System.identityHashCode(player)] = hostName
        if (hostName == HOST_MAIN) {
            mainFeedStoryPlayer = player
        }
        if (hostName == HOST_SPACE) {
            upSpaceStoryPlayer = player
        }
        if (player === activeStoryPlayer) {
            activeStoryHost = host
            trackedIndexHost = hostName
        }
    }

    /**
     * Sync story keys from this player's pager. [full]=true also realigns tracked/live indices
     * (required on outer-tab switch — log 220906: playingStoryKey=UP video, trackedIndex=13).
     */
    private fun Any.syncPlayerTrackingFromPager(source: String, full: Boolean = false) {
        val d1 = invokeIntGetter("D1", "getIndex") ?: return
        val host = playerHostName()
        // F1() = adapter.Z0(D1); prefer over storyIdentityAt when outer tab just resumed.
        val pagerId = currentStoryIdentity() ?: storyIdentityAt(d1)
        if (pagerId == null) {
            if (host != null) {
                trackedIndexHost = host
            }
            if (full) {
                trackedIndex = d1
                liveEngineIndex = d1
            }
            Log.trace { "StoryAutoNext: defer sync — F1 not ready d1=$d1 host=$host source=$source" }
            return
        }
        playingStoryKey = pagerId
        activeStoryKey = pagerId
        if (host != null) {
            trackedIndexHost = host
        }
        if (full) {
            val engineIdx = captureEnginePlayingIndex()
            val engineId = captureEnginePlayingIdentity() ?: playingStoryKey
            val d1Authoritative = engineId != null && pagerId != null &&
                identitiesMatch(engineId, pagerId)
            if (!d1Authoritative && trackedIndex > d1 &&
                (engineIdx < 0 || engineIdx <= d1)
            ) {
                // Activity recreate: pager restarts at D1=0 while bg audio/session index is preserved.
                engineId?.let {
                    playingStoryKey = it
                    activeStoryKey = it
                }
            } else if (d1Authoritative || engineIdx <= d1) {
                trackedIndex = d1
                liveEngineIndex = d1
            } else {
                // D1 preloaded ahead of the engine — do not pull tracking to stale pager tail.
                trackedIndex = engineIdx
                liveEngineIndex = engineIdx
                engineId?.let {
                    playingStoryKey = it
                    activeStoryKey = it
                }
            }
            backgroundEngineIndex = -1
            if (!isInBackground) {
                backgroundEngineMoved = false
            }
            tailWaitKey = null
        }
        SponsorBlockController.rebindStoryFromPager(this)
        Log.trace { "StoryAutoNext: sync tracking d1=$d1 host=$host full=$full source=$source " +
                "(live=$liveEngineIndex tracked=$trackedIndex id=$playingStoryKey)" }
    }

    private fun captureActiveStoryPlayer(player: Any) {
        activeStoryPlayer = player
        rememberMainFeedStoryPlayer(player)
        rememberUpSpaceStoryPlayer(player)
        player.storyPagerHostClass()?.let { hostName ->
            playerHostByInstance[System.identityHashCode(player)] = hostName
            trackedIndexHost = hostName
        }
    }

    /** Do not let the secondary UP-space player replace main-feed tracking during bg playback. */
    private fun captureActiveStoryPlayerIfDominant(player: Any) {
        if (isUpSpacePlayerWithoutBgSession(player.playerHostName())) {
            val activeHost = activeStoryPlayer?.playerHostName()
            if (activeHost == HOST_MAIN && (
                    isInBackground || backgroundEngineMoved || backgroundEngineHost == HOST_MAIN ||
                    backgroundSessionHost == HOST_MAIN
                )
            ) {
                return
            }
        }
        captureActiveStoryPlayer(player)
    }

    /** Direct F2 fallback — prefer [callOfficialPagerAdvance] (D2→F2) which matches official j1/j3 paths. */
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
     * After background engine advance, align ViewPager2/D1 via official D2→F2 + J2.
     * Never W2 (truncates feed). Never forcePlayAtIndex here — h2 without F2 breaks swipe chain.
     */
    private fun Any.syncPagerToEngineIndex(
        preferredIndex: Int,
        targetId: String?,
        attempt: Int = 0,
    ) {
        val playerId = System.identityHashCode(this)
        if (pagerSyncInProgressPlayerId == playerId) {
            Log.trace { "StoryAutoNext: F2 sync reentry blocked preferred=$preferredIndex " +
                    "id=$targetId d1=${invokeIntGetter("D1", "getIndex")}" }
            return
        }
        pagerSyncInProgressPlayerId = playerId
        try {
            syncPagerToEngineIndexInner(preferredIndex, targetId, attempt)
        } finally {
            if (pagerSyncInProgressPlayerId == playerId) {
                pagerSyncInProgressPlayerId = -1
            }
        }
    }

    private fun Any.syncPagerToEngineIndexInner(
        preferredIndex: Int,
        targetId: String?,
        attempt: Int = 0,
    ) {
        if (attempt == 0) {
            restoreResumeFeedSnapshotIfNeeded()
        }
        val resolved = resolveCatchUpIndex(preferredIndex, targetId)
        if (resolved < 0) {
            if (attempt < 4) {
                val delayMs = when (attempt) {
                    0 -> 16L
                    1 -> 50L
                    2 -> 120L
                    else -> 250L
                }
                mainHandler.postDelayed({
                    runCatching {
                        syncPagerToEngineIndexInner(preferredIndex, targetId, attempt + 1)
                    }.onFailure { Log.e(it) }
                }, delayMs)
                return
            }
            Log.trace { "StoryAutoNext: F2 sync failed preferred=$preferredIndex " +
                    "count=${invokeIntGetter("N1")} id=$targetId" }
            reconcileEngineToVisiblePagerOnSyncFailure(preferredIndex, targetId)
            finishForegroundResumeHandoffIfPending(this)
            finishAppBackgroundResumeSession()
            return
        }
        val d1Before = invokeIntGetter("D1", "getIndex") ?: -1
        val pagerIdBefore = if (d1Before >= 0) storyIdentityAt(d1Before) else null
        if (d1Before == resolved && (targetId == null || identitiesMatch(pagerIdBefore, targetId))) {
            applyOfficialResumePosition()
            clearBackgroundSession(resolved, targetId)
            rebindOfficialSurfaceAtD1("x2 after F2 align skip")
            Log.trace { "StoryAutoNext: pager already at index=$resolved id=$targetId" }
            finishForegroundResumeHandoffIfPending(this)
            finishAppBackgroundResumeSession()
            return
        }
        Log.trace { "StoryAutoNext: F2 sync start engine=$resolved pager=$d1Before id=$targetId" }
        applyOfficialPagerAlign(resolved, targetId, attempt = 0)
    }

    /** Official D2→F2 + J2; retry until D1 matches — same stack as official w2 resume catch-up. */
    private fun Any.applyOfficialPagerAlign(resolved: Int, targetId: String?, attempt: Int) {
        withStoryPagerActiveFlag {
            applyOfficialResumePosition()
            callOfficialPagerAdvance(resolved, smooth = false)
            syncOfficialPagerIndex(resolved)
            trackedIndex = resolved
            liveEngineIndex = resolved
            targetId?.let { playingStoryKey = it }
        }
        val d1 = invokeIntGetter("D1", "getIndex") ?: -1
        val pagerId = if (d1 >= 0) storyIdentityAt(d1) else null
        val f1Id = this.currentStoryIdentity()
        // F1() = adapter.Z0(D1) drives title/author; D1 alone is not enough (log: d1=16 F1=other).
        val identityOk = targetId == null ||
            (identitiesMatch(pagerId, targetId) && identitiesMatch(f1Id, targetId))
        if (d1 == resolved && identityOk) {
            syncStoryPlayerDirectorIndex(resolved)
            // jadx i3→x2 binds holder after F2; F2 alone leaves frozen frame + audio (log 00:17:19).
            rebindOfficialSurfaceAtD1("x2 after F2 sync ok")
            clearBackgroundSession(resolved, targetId)
            Log.trace { "StoryAutoNext: F2 sync ok index=$resolved id=$targetId d1=$d1 F1=$f1Id" }
            finishForegroundResumeHandoffIfPending(this)
            finishAppBackgroundResumeSession()
            return
        }
        if (attempt < 4) {
            val delayMs = when (attempt) {
                0 -> 16L
                1 -> 50L
                2 -> 120L
                else -> 250L
            }
            mainHandler.postDelayed({
                runCatching {
                    applyOfficialPagerAlign(resolved, targetId, attempt + 1)
                }.onFailure { Log.e(it) }
            }, delayMs)
            return
        }
        Log.trace { "StoryAutoNext: F2 sync incomplete index=$resolved id=$targetId " +
                "d1=$d1 F1=$f1Id attempt=$attempt" }
        reconcileEngineToVisiblePagerOnSyncFailure(resolved, targetId)
        finishForegroundResumeHandoffIfPending(this)
        finishAppBackgroundResumeSession()
    }

    /**
     * When F2 retries exhaust: re-run official F2 metadata path (m1 + f.a), then engine h2
     * only if still in background — never naked h2 in foreground.
     */
    private fun Any.reconcileEngineToVisiblePagerOnSyncFailure(preferredIndex: Int, targetId: String?) {
        val d1 = invokeIntGetter("D1", "getIndex") ?: 0
        val syncIndex = preferredIndex.coerceAtLeast(0)
        val visibleId = storyIdentityAt(syncIndex) ?: currentStoryIdentity()
        if (targetId != null && identitiesMatch(visibleId, targetId) &&
            identitiesMatch(currentStoryIdentity(), targetId)
        ) {
            clearBackgroundSession(syncIndex, targetId)
            Log.trace { "StoryAutoNext: F2 sync skipped — pager already shows target id=$targetId d1=$d1" }
            return
        }
        val count = invokeIntGetter("N1") ?: 0
        Log.trace { "StoryAutoNext: F2 sync gave up — official pager metadata index=$syncIndex " +
                "id=$targetId d1=$d1 count=$count F1=${currentStoryIdentity()}" }
        if (syncIndex >= count && targetId != null &&
            identitiesMatch(captureEnginePlayingIdentity(), targetId)
        ) {
            seedNativeStartFromEngineIfNeeded()
            trackedIndex = syncIndex
            liveEngineIndex = syncIndex
            playingStoryKey = targetId
            activeStoryKey = targetId
            Log.trace { "StoryAutoNext: feed truncated — keep engine session id=$targetId idx=$syncIndex" }
        } else {
            syncOfficialPagerMetadata(syncIndex, smooth = false, targetId)
        }
        if (isInBackground) {
            val engineId = captureEnginePlayingIdentity()
            if (targetId == null || !identitiesMatch(engineId, targetId)) {
                forceEnginePlayAtIndex(syncIndex)
            }
        }
        clearBackgroundSession(syncIndex, targetId ?: visibleId)
    }

    /**
     * Official programmatic page change: D2 -> F2 (adapter.m1 + ViewPager2.setCurrentItem).
     * Foreground auto-next uses smooth=true; background uses smooth=false (no fake swipe anim).
     */
    private fun Any.callOfficialPagerAdvance(index: Int, smooth: Boolean): Boolean = runCatching {
        if (index < 0) return@runCatching false
        val d2 = javaClass.methods.firstOrNull {
            it.name == "D2" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (d2 != null) {
            d2.isAccessible = true
            d2.invoke(this, index, smooth)
        } else {
            callPagerScroll(index, smooth)
        }
        true
    }.getOrElse {
        Log.e(it)
        false
    }

    /** StoryVideoFragment.f.a(index) — refresh title/author when onPageSelected did not run. */
    private fun Any.notifyOfficialPageMetadata(index: Int) {
        runCatching {
            val field = javaClass.declaredFields.firstOrNull {
                it.type.name == "com.bilibili.video.story.player.f"
            } ?: return@runCatching
            field.isAccessible = true
            val listener = field.get(this) ?: return@runCatching
            val method = listener.javaClass.methods.firstOrNull {
                it.name == "a" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return@runCatching
            method.isAccessible = true
            method.invoke(listener, index)
        }.onFailure { Log.e(it) }
    }

    /**
     * jadx F2/D2 + K2/J2: adapter.m1 + setCurrentItem, PlayHandler.T(index,-1,null).
     * M2 (director.play) is W2 bulk-reload only — not part of j1/G2 per-step advance.
     */
    private fun Any.syncOfficialPagerPosition(index: Int, smooth: Boolean) {
        callOfficialPagerAdvance(index, smooth)
        syncOfficialPagerIndex(index)
    }

    /** Foreground programmatic advance (j1 uses D2 smooth=true; report path uses G2/F2 smooth=false). */
    private fun Any.syncOfficialPagerMetadata(index: Int, smooth: Boolean, targetId: String? = null) {
        syncOfficialPagerPosition(index, smooth)
        val f1Id = currentStoryIdentity()
        if (targetId != null && !identitiesMatch(f1Id, targetId)) {
            notifyOfficialPageMetadata(index)
        }
    }

    /** jadx StoryPagerPlayer.v1: scroll inner RecyclerView to D1 (t2 when Q=4 && N1>1). */
    private fun Any.invokeOfficialRecyclerAlignToD1() {
        runCatching {
            val v1 = javaClass.declaredMethods.firstOrNull {
                it.name == "v1" && it.parameterCount == 0
            } ?: return@runCatching
            v1.isAccessible = true
            v1.invoke(this)
        }.onFailure { Log.e(it) }
    }

    /**
     * StoryPagerPlayer.I must be true for D2/h2()-gated paths; u2 clears I while Q=4 inactive.
     * jadx h2(): return I || Q==2; j3 unregisters OnPageChangeCallback so setCurrentItem won't
     * fire onPageSelected/x2/f.a — must call J2 + f.a manually after F2.
     */
    private fun Any.withStoryPagerActiveFlag(block: () -> Unit) {
        val fieldI = javaClass.declaredFields.firstOrNull {
            it.name == "I" && it.type == Boolean::class.javaPrimitiveType
        }
        if (fieldI == null) {
            block()
            return
        }
        fieldI.isAccessible = true
        val wasI = fieldI.getBoolean(this)
        if (!wasI) fieldI.setBoolean(this, true)
        try {
            block()
        } finally {
            if (!wasI) fieldI.setBoolean(this, wasI)
        }
    }

    /**
     * Background per-episode pager sync — mirror official j1: D2→F2→J2→f.a (I=true for h2 gate).
     * j3 unregisters OnPageChangeCallback; engine h2 is separate while Q=4 blocks x2 at D1.
     */
    private fun Any.syncBackgroundPagerStep(index: Int, targetId: String?) {
        withStoryPagerActiveFlag {
            syncOfficialPagerPosition(index, smooth = false)
            notifyOfficialPageMetadata(index)
            syncStoryPlayerDirectorIndex(index)
        }
        var d1 = invokeIntGetter("D1", "getIndex") ?: -1
        if (d1 != index) {
            callOfficialPagerAdvance(index, smooth = false)
            syncOfficialPagerIndex(index)
            notifyOfficialPageMetadata(index)
            syncStoryPlayerDirectorIndex(index)
            d1 = invokeIntGetter("D1", "getIndex") ?: -1
        }
        if (d1 != index) {
            invokeOfficialRecyclerAlignToD1()
            d1 = invokeIntGetter("D1", "getIndex") ?: -1
        }
        if (d1 != index) {
            Log.trace { "StoryAutoNext: bg pager step incomplete expected=$index d1=$d1 id=$targetId" }
        }
    }

    /** StoryPagerPlayer.J2(index, -1, null) -> StoryPlayer.t2 -> PlayHandler.T (official page index). */
    private fun Any.syncOfficialPagerIndex(index: Int) {
        runCatching {
            val j2 = javaClass.methods.firstOrNull {
                it.name == "J2" && it.parameterCount == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: return@runCatching
            j2.isAccessible = true
            j2.invoke(this, index, -1, null)
        }.onFailure { Log.e(it) }
    }

    /** StoryPlayer.M2(index) — jadx W2 only: setCurrentItem(f208481u,false) then M2 (not j1/G2). */
    private fun Any.syncStoryPlayerDirectorIndex(index: Int) {
        runCatching {
            val storyPlayer = javaClass.declaredFields.firstOrNull {
                it.type.name == "com.bilibili.video.story.player.StoryPlayer"
            }?.apply { isAccessible = true }?.get(this) ?: return@runCatching
            val m2 = storyPlayer.javaClass.methods.firstOrNull {
                it.name == "M2" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return@runCatching
            m2.isAccessible = true
            m2.invoke(storyPlayer, index)
        }.onFailure { Log.e(it) }
    }

    private fun clearBackgroundSession(index: Int, targetId: String?) {
        backgroundEngineMoved = false
        backgroundEngineIndex = -1
        backgroundEngineHost = null
        backgroundEnginePlayerId = -1
        trackedIndex = index
        liveEngineIndex = index
        targetId?.let {
            playingStoryKey = it
            activeStoryKey = it
        }
        cancelPostResumeCatchUp()
    }

    /**
     * Seed f208480t only when bg engine ran ahead and w2 will F2 catch-up.
     * Simple bg pause: official inactive (Q=4) already saves position into f208480t — do not touch.
     */
    private fun Any.shouldCaptureForegroundResumeHandoff(runSync: Boolean, @Suppress("UNUSED_PARAMETER") managesBg: Boolean): Boolean {
        if (!isAutoNextEnabled()) return false
        if (!appBackgroundResumeHandoffEligible) return false
        if (!backgroundEngineMoved || !runSync) return false
        val host = playerHostName()
        if (backgroundSessionHost != null && host != null && host != backgroundSessionHost) {
            return false
        }
        return true
    }

    /** Capture engine position; f208480t is written in applyOfficialResumePosition() right before F2→x2. */
    private fun Any.beginForegroundResumeHandoff(targetId: String?, runSync: Boolean) {
        val pos = liveStoryPlayerCoreFrom()
            ?.invokeLongGetter("getCurrentPosition", "getRealCurrentPosition") ?: 0L
        if (pos < BACKGROUND_RESUME_MIN_MS) return
        pendingForegroundHandoffPosMs = pos
        pendingForegroundHandoffId = targetId
        pendingForegroundHandoffPlayerId = System.identityHashCode(this)
        foregroundHandoffStoryPlayer = this
        Log.trace { "StoryAutoNext: capture bg resume pos=$pos id=$targetId runSync=$runSync" }
    }

    /**
     * Official resume: StoryPlayer.d.onStateChanged(3) seeks f208480t once then clears it.
     * w2/i3() may zero t (jadx i3 J-branch); must re-write immediately before F2→x2→h2.
     */
    private fun Any.applyOfficialResumePosition(): Boolean {
        if (!appBackgroundResumeHandoffEligible) return false
        if (pendingForegroundHandoffPlayerId != System.identityHashCode(this)) return false
        val pos = pendingForegroundHandoffPosMs
        if (pos < BACKGROUND_RESUME_MIN_MS) return false
        setNativeStartPosition(pos)
        Log.trace { "StoryAutoNext: apply f208480t=$pos before F2→x2 (official seek-on-prepare)" }
        return true
    }

    /** jadx StoryPagerPlayer.Q — 3=active; x2/d2() no-op when Q!=3. */
    private fun Any.readOfficialPagerStateQ(): Int? = runCatching {
        javaClass.declaredFields.firstOrNull {
            it.name == "Q" && it.type == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }?.getInt(this)
    }.getOrNull()

    /**
     * jadx StoryVideoAdapter.w1 returns null when storyDetail == f206257h ("+++has play").
     * App bg (u2 only, Q stays 3) keeps f206257h; j3(FALSE) clears via L1(null, 1).
     */
    private fun Any.getStoryVideoAdapter(): Any? = runCatching {
        javaClass.declaredFields.firstOrNull {
            it.type.name == "com.bilibili.video.story.StoryVideoAdapter"
        }?.apply { isAccessible = true }?.get(this)
    }.getOrNull()

    /** True when adapter playing marker equals Z0(D1) — official x2/w1 would no-op ("+++has play"). */
    private fun Any.officialAdapterPlayingMarkerBlocksX2AtD1(): Boolean = runCatching {
        val adapter = getStoryVideoAdapter() ?: return false
        val d1 = invokeIntGetter("D1", "getIndex") ?: return false
        if (d1 < 0) return false
        val z0 = adapter.javaClass.methods.firstOrNull {
            it.name == "Z0" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return false
        z0.isAccessible = true
        val atD1 = z0.invoke(adapter, d1) ?: return false
        val atD1Id = atD1.storyIdentity()
        adapter.javaClass.declaredFields.any { field ->
            if (field.type.name != "com.bilibili.video.story.StoryDetail") return@any false
            field.isAccessible = true
            val marker = field.get(adapter) ?: return@any false
            identitiesMatch(marker.storyIdentity(), atD1Id)
        }
    }.getOrDefault(false)

    private fun Any.clearOfficialAdapterPlayingMarkerIfNeeded() {
        if (!officialAdapterPlayingMarkerBlocksX2AtD1()) return
        runCatching {
            val adapter = getStoryVideoAdapter() ?: return@runCatching
            val l1 = adapter.javaClass.declaredMethods.firstOrNull {
                it.name == "L1" && it.parameterCount == 2
            } ?: return@runCatching
            l1.isAccessible = true
            // jadx j3(FALSE): StoryVideoAdapter.L1(null, 1)
            l1.invoke(adapter, null, 1)
        }.onFailure { Log.e(it) }
    }

    /** When i3 J-branch zeroed f208480t, re-seed from live engine before x2 seek-on-prepare. */
    private fun Any.seedNativeStartFromEngineIfNeeded() {
        if (applyOfficialResumePosition()) return
        val pos = liveStoryPlayerCoreFrom()
            ?.invokeLongGetter("getCurrentPosition", "getRealCurrentPosition") ?: return
        if (pos < BACKGROUND_RESUME_MIN_MS) return
        setNativeStartPosition(pos)
        Log.trace { "StoryAutoNext: seed f208480t=$pos before x2 rebind" }
    }

    /** StoryPagerPlayer.x2 — bind holder + h2 at D1; triggers official f208480t consume on prepare. */
    private fun Any.invokeOfficialPlayAtD1(smooth: Boolean = false) {
        runCatching {
            val x2 = javaClass.declaredMethods.firstOrNull {
                it.name == "x2" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            } ?: return@runCatching
            x2.isAccessible = true
            officialX2RebindInProgress = true
            try {
                x2.invoke(this, smooth)
            } finally {
                officialX2RebindInProgress = false
            }
        }.onFailure { Log.e(it) }
    }

    /**
     * i3→x2 runs on onPagerIn before w2. Block only during bg-resume handoff when D1 does not
     * match the engine slot (stale D1=0 or preloaded ahead). After w2 clears eligible, user swipes
     * are never blocked — see finishAppBackgroundResumeSession in completeW2ResumeHandoffCycle.
     */
    private fun Any.shouldBlockOfficialX2ForBgResume(): Boolean {
        if (officialX2RebindInProgress) return false
        if (!appBackgroundResumeHandoffEligible) return false
        if (playerHostName() != visibleSessionHost()) return false
        val d1 = invokeIntGetter("D1", "getIndex") ?: -1
        if (d1 < 0) return false
        val engineId = captureEnginePlayingIdentity() ?: playingStoryKey ?: activeStoryKey
        val pagerId = storyIdentityAt(d1)
        if (engineId != null && pagerId != null && identitiesMatch(engineId, pagerId)) {
            return false
        }
        val pending = resolvePendingEngineIndex(d1)
        if (pending > d1) return true
        if (engineId != null && pagerId != null && !identitiesMatch(engineId, pagerId)) {
            return true
        }
        return false
    }

    /**
     * Force w1 bind after bg: clear stale f206257h, align RecyclerView, then x2 at D1.
     * Official i3→x2 may run before w2 but w1 no-ops on "+++has play" — must clear first.
     */
    private fun Any.rebindOfficialSurfaceAtD1(logTag: String) {
        val q = readOfficialPagerStateQ()
        if (q != 3) {
            Log.trace { "StoryAutoNext: $logTag skipped inactive Q=$q" }
            return
        }
        val d1 = invokeIntGetter("D1", "getIndex") ?: -1
        val pending = resolvePendingEngineIndex(d1)
        if (pending > d1) {
            Log.trace { "StoryAutoNext: $logTag skip rebind — F2 pending engine=$pending d1=$d1 " +
                    "(w2 will sync)" }
            return
        }
        if (d1 >= 0 && trackedIndex > d1 && !backgroundEngineMoved) {
            val sessionId = playingStoryKey ?: activeStoryKey
            val pagerId = storyIdentityAt(d1)
            if (sessionId != null && pagerId != null && !identitiesMatch(sessionId, pagerId)) {
                Log.trace { "StoryAutoNext: $logTag skip rebind — d1=$d1 stale vs tracked=$trackedIndex" }
                return
            }
        }
        seedNativeStartFromEngineIfNeeded()
        clearOfficialAdapterPlayingMarkerIfNeeded()
        invokeOfficialRecyclerAlignToD1()
        invokeOfficialPlayAtD1(smooth = false)
        Log.trace { "StoryAutoNext: $logTag (d1=$d1, Q=$q)" }
    }

    private fun finishForegroundResumeHandoffIfPending(player: Any? = null) {
        if (player != null && pendingForegroundHandoffPlayerId > 0 &&
            pendingForegroundHandoffPlayerId != System.identityHashCode(player)
        ) {
            return
        }
        pendingForegroundHandoffPosMs = -1L
        pendingForegroundHandoffId = null
        pendingForegroundHandoffPlayerId = -1
        foregroundHandoffStoryPlayer = null
    }

    /** End w2 handoff for the visible-tab player only — hidden-tab w2 must not touch global bg state. */
    private fun Any.completeW2ResumeHandoffCycle() {
        if (!shouldManageBackgroundLoopMode()) return
        if (pendingForegroundHandoffPosMs >= BACKGROUND_RESUME_MIN_MS &&
            pendingForegroundHandoffPlayerId == System.identityHashCode(this)
        ) {
            finishForegroundResumeHandoffIfPending(this)
        } else {
            cancelForegroundResumeHandoff(this)
        }
        if (!isInBackground) {
            backgroundEngineMoved = false
            backgroundEngineIndex = -1
            backgroundEngineHost = null
            finishAppBackgroundResumeSession()
        }
    }

    /**
     * Post surface rebind until all StoryPagerPlayer.w2 hooks finish (UP space w2 used to clear
     * appBackgroundResumeHandoffEligible before main-feed rebind — log 22:34:31 / 22:34:56).
     */
    private fun Any.postOfficialSurfaceRebindAfterAppBgResume() {
        mainHandler.post { scheduleOfficialSurfaceRebindAfterW2() }
    }

    /**
     * Official i3→x2 often no-ops on bg return (p8→j3 leaves stale f206257h → w1 "+++has play").
     * Rebind after w2 for both simple pause and bg auto-next without F2 gap — does not F2 pager.
     */
    private fun Any.refreshOfficialSurfaceAfterBgResume(
        managesBg: Boolean,
        runSync: Boolean,
        appBgResume: Boolean,
    ) {
        if (!managesBg || runSync || !appBgResume) return
        if (playerHostName() != visibleSessionHost()) return
        postOfficialSurfaceRebindAfterAppBgResume()
    }

    /** Wait for Q=3 (i3 from onPagerIn) then rebind; w2 runs after adapter.onResume(). */
    private fun Any.scheduleOfficialSurfaceRebindAfterW2(attempt: Int = 0) {
        val q = readOfficialPagerStateQ()
        if (q != 3) {
            if (attempt < 5) {
                val delayMs = when (attempt) {
                    0 -> 0L
                    1 -> 16L
                    2 -> 50L
                    else -> 120L
                }
                mainHandler.postDelayed({ scheduleOfficialSurfaceRebindAfterW2(attempt + 1) }, delayMs)
            } else {
                Log.trace { "StoryAutoNext: x2 rebind aborted inactive Q=$q" }
            }
            return
        }
        rebindOfficialSurfaceAtD1("x2 rebind after bg resume (no F2 gap, attempt=$attempt)")
        finishAppBackgroundResumeSession()
    }

    /** Engine index pending F2 sync after official w2(); independent of isInBackground flag. */
    private fun Any.resolvePendingEngineIndex(pagerIndex: Int): Int {
        if (!isAutoNextEnabled()) return -1
        if (isUpSpacePlayerWithoutBgSession(playerHostName())) return -1
        val host = playerHostName()
        if (host != null && trackedIndexHost != null && host != trackedIndexHost && !backgroundEngineMoved) {
            return -1
        }
        resolveStalePagerCatchUpIndex(pagerIndex).takeIf { it >= 0 }?.let { return it }
        if (resumeHandoffIndex >= 0 && resumeHandoffIndex > pagerIndex &&
            (resumeHandoffCount < 0 || resumeHandoffCount > pagerIndex)
        ) {
            val pagerId = if (pagerIndex >= 0) storyIdentityAt(pagerIndex) else null
            if (resumeHandoffId == null || pagerId == null ||
                !identitiesMatch(resumeHandoffId, pagerId)
            ) {
                return resumeHandoffIndex
            }
            if (resumeHandoffIndex >= resumeHandoffCount) {
                return resumeHandoffIndex
            }
        }
        val playingId = captureEnginePlayingIdentity() ?: playingStoryKey ?: activeStoryKey
        val pagerId = if (pagerIndex >= 0) storyIdentityAt(pagerIndex) else null
        if (playingId != null && pagerId != null && identitiesMatch(playingId, pagerId)) {
            return -1
        }
        val identityMismatch = playingId != null && pagerId != null && !identitiesMatch(playingId, pagerId)
        val engineIdx = captureEnginePlayingIndex()
        val preferred = when {
            backgroundEngineMoved && backgroundEngineIndex >= 0 -> backgroundEngineIndex
            identityMismatch && pagerIndex > engineIdx && engineIdx >= 0 && !backgroundEngineMoved ->
                engineIdx
            identityMismatch -> maxOf(
                liveEngineIndex,
                trackedIndex,
                backgroundEngineIndex,
                engineIdx,
            ).coerceAtLeast(0)
            backgroundEngineMoved && liveEngineIndex > pagerIndex ->
                maxOf(liveEngineIndex, trackedIndex, backgroundEngineIndex).coerceAtLeast(0)
            else -> -1
        }
        if (preferred < 0) return -1
        if (preferred <= pagerIndex && !identityMismatch) return -1
        return preferred
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
        Log.s("StoryAutoNext: hooked u.c(Context) cancel check bypass")
    }

    private fun hookStoryPagerPlayer() {
        val addVideo = instance.addVideoMethod()?.name ?: run {
            Log.w { "StoryAutoNext: addVideo method not found" }
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
        playerClass.hookMethod("h1", List::class.java) { chain ->
            val incoming = chain.args.firstOrNull() as? List<*>
            val player = chain.thisObject
            if (incoming != null && player.shouldBlockDestructiveH1(incoming)) {
                return@hookMethod null
            }
            chain.proceed()
        }
        playerClass.declaredMethods.firstOrNull {
            it.name == "W2" && it.parameterCount == 3 &&
                it.parameterTypes[0] == List::class.java
        }?.hookMethod { chain ->
            val incoming = chain.args[0] as? List<*> ?: return@hookMethod chain.proceed()
            val player = chain.thisObject
            if (player.shouldBlockDeferredW2Replace(incoming)) {
                return@hookMethod null
            }
            chain.proceed()
        }
        playerClass.hookMethod(addVideo, List::class.java) { chain ->
            val firstItem = (chain.args[0] as? List<*>)?.firstOrNull()
            val player = chain.thisObject
            if (backgroundLoadActive) {
                val result = chain.proceed()
                val deferred = player.clearDeferredFeedQueue()
                if (deferred > 0) {
                    Log.trace { "StoryAutoNext: cleared deferred queue after bg addVideo size=$deferred" }
                }
                captureActiveStoryPlayerIfDominant(player)
                // Do not overwrite playingStoryKey from append batch head — engine may still
                // be at backgroundEngineIndex while firstItem is at tail (see log engine=4 -> 17).
                reconcileTrackedIndexOnAddVideo(player, backgroundLoadActive = true)
                hookRuntimeTargets(player)
                ensurePolling()
                return@hookMethod result
            }
            val result = chain.proceed()
            captureActiveStoryPlayerIfDominant(player)
            if (trackedIndex < 0) {
                activeStoryKey = firstItem?.storyIdentity()
                playingStoryKey = activeStoryKey
            }
            reconcileTrackedIndexOnAddVideo(player, backgroundLoadActive = false)
            hookRuntimeTargets(player)
            ensurePolling()
            result
        }
        Log.s("StoryAutoNext: hooked ${playerClass.name}#$addVideo and setLooping")
    }

    private fun hookStoryHostCapture(playerClass: Class<*>) {
        val actionHostClass = runCatching {
            Class.forName("com.bilibili.video.story.action.f", false, mClassLoader)
        }.getOrNull() ?: return
        playerClass.hookMethod("I2", actionHostClass) { chain ->
            val player = chain.thisObject
            val host = chain.args.firstOrNull()
            val prevHost = player.storyPagerHostClass()
                ?: playerHostByInstance[System.identityHashCode(player)]
            if (host != null && host.javaClass.name == HOST_SPACE &&
                player === activeStoryPlayer &&
                (prevHost == HOST_MAIN || prevHost == null)
            ) {
                when {
                    backgroundEngineMoved && backgroundEngineHost == HOST_MAIN ->
                        player.ensurePagerAlignedForNavigation()
                    player.shouldSyncOuterTabHandoff() ->
                        player.trySyncOuterTabHandoff()
                }
            }
            if (host != null && host.javaClass.name == HOST_MAIN &&
                player === activeStoryPlayer &&
                prevHost == HOST_SPACE &&
                player.shouldSyncOuterTabHandoff()
            ) {
                player.trySyncOuterTabHandoff()
            }
            val result = chain.proceed()
            rememberStoryPlayerHost(player, host)
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
        Log.s("StoryAutoNext: hooked ${storyPlayerClass.name}.setLooping")
    }

    /**
     * When the engine ran ahead in background (D1 still 0), align ViewPager2 before in-app
     * navigation (e.g. opening UP space) so Bilibili picks the currently playing video.
     */
    private fun Any.ensurePagerAlignedForNavigation(): Boolean = runCatching {
        val d1 = invokeIntGetter("D1", "getIndex") ?: -1
        val preferred = trackedIndex
            .coerceAtLeast(liveEngineIndex)
            .coerceAtLeast(backgroundEngineIndex)
        if (preferred <= d1 || preferred < 0) return@runCatching false
        val targetId = playingStoryKey ?: activeStoryKey ?: storyIdentityAt(preferred)
        Log.trace { "StoryAutoNext: align pager for navigation d1=$d1 -> $preferred id=$targetId" }
        syncPagerToEngineIndex(preferred, targetId)
        invokeIntGetter("D1", "getIndex") == preferred
    }.getOrElse {
        Log.e(it)
        false
    }

    /**
     * Hook u2/w2 to match official Fragment pause/resume (I flag + deferred h1 flush).
     * We only add: loop mode in background, and F2 pager sync after w2 when engine ran ahead.
     */
    private fun hookForegroundSync(playerClass: Class<*>) {
        val u2PauseMethod = playerClass.declaredMethods.firstOrNull {
            it.name == "u2" && it.parameterCount == 0
        }
        if (u2PauseMethod != null) {
            u2PauseMethod.hookMethod { chain ->
                val player = chain.thisObject
                if (player.shouldManageBackgroundLoopMode() && activityPaused) {
                    clearOuterTabHandoff()
                    cancelForegroundResumeHandoff()
                    appBackgroundResumeHandoffEligible = true
                    backgroundSessionHost = visibleSessionHost()
                    isInBackground = true
                    captureActiveStoryPlayer(player)
                    val d1OnPause = player.invokeIntGetter("D1", "getIndex") ?: -1
                    player.captureResumeHandoffIfNeeded(d1OnPause)
                    if (backgroundEngineMoved) {
                        player.realignEngineTrackingToPager("u2")
                    } else if (d1OnPause >= 0 &&
                        (liveEngineIndex > d1OnPause || trackedIndex > d1OnPause)
                    ) {
                        if (player.isStalePagerVersusSession(d1OnPause) ||
                            resumeHandoffIndex > d1OnPause ||
                            trackedIndex > d1OnPause ||
                            liveEngineIndex > d1OnPause
                        ) {
                            Log.trace {
                                "StoryAutoNext: u2 keep session tracked=$trackedIndex live=$liveEngineIndex " +
                                    "(d1=$d1OnPause stale pager after recreate)"
                            }
                        } else {
                            player.realignEngineTrackingToPager("u2-stale-track", force = true)
                        }
                    }
                    if (isAutoNextEnabled()) {
                        applyBackgroundLoopMode(player, autoNext = true)
                    }
                    ensurePolling()
                    Log.trace { "StoryAutoNext: bg pause tab=$visibleOuterTabIndex " +
                            "host=$backgroundSessionHost tag=${player.storyPagerTag()}" }
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
                val playerHost = player.playerHostName()
                val upSpaceEntry = isUpSpacePlayerWithoutBgSession(playerHost)
                val managesBg = player.shouldManageBackgroundLoopMode()
                val pagerBefore = player.invokeIntGetter("D1", "getIndex") ?: -1
                val pendingIndex = player.resolvePendingEngineIndex(pagerBefore)
                val appBgResume = appBackgroundResumeHandoffEligible
                val pendingId = when {
                    resumeHandoffIndex > pagerBefore && resumeHandoffId != null -> resumeHandoffId
                    appBgResume && resumeHandoffId != null -> resumeHandoffId
                    else -> player.captureEnginePlayingIdentity()
                        ?: playingStoryKey ?: activeStoryKey ?: resumeHandoffId
                }
                val catchUpIndex = when {
                    pendingIndex >= 0 -> pendingIndex
                    else -> player.resolveStalePagerCatchUpIndex(pagerBefore)
                }
                if (catchUpIndex >= 0) {
                    Log.trace { "StoryAutoNext: w2 pending F2 sync engine=$catchUpIndex " +
                            "pager=$pagerBefore id=$pendingId moved=$backgroundEngineMoved " +
                            "host=$playerHost tag=${player.storyPagerTag()} active=${player === activeStoryPlayer}" }
                }
                val runSync = catchUpIndex >= 0 && player.shouldRunW2PagerSync(catchUpIndex, pagerBefore)
                if (player.shouldCaptureForegroundResumeHandoff(runSync, managesBg)) {
                    player.beginForegroundResumeHandoff(pendingId, runSync)
                }
                if (managesBg) {
                    isInBackground = false
                    backgroundSessionHost = null
                    stopEndPolling()
                    captureActiveStoryPlayer(player)
                    if (isAutoNextEnabled()) {
                        applyBackgroundLoopMode(player, autoNext = false)
                    }
                }
                val outerHandoffBeforeProceed = if (!runSync && !appBgResume) {
                    player.trySyncOuterTabHandoff()
                } else {
                    false
                }
                val preW2Synced = if (runSync) {
                    player.trySyncPagerBeforeW2Proceed(catchUpIndex, pendingId, pagerBefore)
                } else {
                    false
                }
                if (appBgResume) {
                    player.guardOfficialW2FeedFlushBeforeResume()
                }
                // Official w2: I=true, adapter.onResume(), flush f208484w via h1 (never W2 here).
                chain.proceed()
                if (runSync && !preW2Synced && appBgResume) {
                    player.restoreResumeFeedSnapshotIfNeeded()
                }
                when {
                    preW2Synced -> {
                        player.syncVisibleTabTrackingAfterW2()
                        player.syncStoryPlayerDirectorIndex(catchUpIndex)
                        clearBackgroundSession(catchUpIndex, pendingId)
                        player.rebindOfficialSurfaceAtD1("x2 after pre-w2 F2")
                        player.completeW2ResumeHandoffCycle()
                    }
                    runSync -> {
                        cancelPostResumeCatchUp()
                        runCatching {
                            player.syncPagerToEngineIndex(catchUpIndex, pendingId)
                        }.onFailure {
                            Log.e(it)
                            player.completeW2ResumeHandoffCycle()
                        }
                    }
                    else -> {
                        val outerHandoffSynced = outerHandoffBeforeProceed ||
                            player.trySyncOuterTabHandoff()
                        player.syncVisibleTabTrackingAfterW2()
                        if (!outerHandoffSynced && upSpaceEntry && !isInBackground) {
                            player.syncPlayerTrackingFromPager("w2-space", full = false)
                        }
                        if (!runSync && catchUpIndex >= 0) {
                            Log.trace { "StoryAutoNext: w2 skip F2 sync host=$playerHost " +
                                    "tag=${player.storyPagerTag()} engine=$catchUpIndex " +
                                    "bgHost=$backgroundEngineHost active=${player === activeStoryPlayer}" }
                        }
                        player.refreshOfficialSurfaceAfterBgResume(managesBg, runSync, appBgResume)
                        player.completeW2ResumeHandoffCycle()
                    }
                }
                hookRuntimeTargets(player)
                if (upSpaceEntry && !managesBg && !isInBackground) {
                    captureActiveStoryPlayerIfDominant(player)
                }
            }
            Log.s("StoryAutoNext: hooked ${playerClass.name}.w2 for post-official F2 sync")
        }

        val x2Method = playerClass.declaredMethods.firstOrNull {
            it.name == "x2" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (x2Method != null) {
            x2Method.hookMethod { chain ->
                val player = chain.thisObject
                if (player.shouldBlockOfficialX2ForBgResume()) {
                    val d1 = player.invokeIntGetter("D1", "getIndex") ?: -1
                    val pending = player.resolvePendingEngineIndex(d1)
                    Log.trace { "StoryAutoNext: block official x2 at d1=$d1 pending=$pending moved=$backgroundEngineMoved " +
                            "(defer F2→x2 on w2)" }
                    return@hookMethod null
                }
                chain.proceed()
            }
            Log.s("StoryAutoNext: hooked ${playerClass.name}.x2 bg-resume guard")
        }

        // Do NOT hook J2 post-proceed realign: official auto-next calls J2 before ViewPager D1
        // catches up; reading D1 there overwrites playingStoryKey with stale adapter metadata.

        // Do NOT hook F2 — official scroll must not re-seed f208480t (fought user scrubbing).

        val seekToMethod = playerClass.declaredMethods.firstOrNull {
            it.name == "seekTo" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (seekToMethod != null) {
            seekToMethod.hookMethod { chain ->
                val player = chain.thisObject
                // User/progress-bar seek only — do not clear f208480t during bg→fg F2 catch-up.
                if (!isInBackground && !appBackgroundResumeHandoffEligible) {
                    cancelForegroundResumeHandoff(player)
                    player.clearNativeStartPositionIfSeeded()
                }
                chain.proceed()
            }
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
            nativeStartPositionSeededPlayerId = System.identityHashCode(this)
            Log.trace { "StoryAutoNext: write f208480t=$posMs" }
        }.onFailure { Log.e(it) }
    }

    /** Clear seek-on-prepare seed for this player instance. */
    private fun Any.clearNativeStartPositionIfSeeded() {
        clearNativeStartPositionIfSeeded(this)
    }

    /** Identity (bvid/cid) of the story item currently shown by this pager player (F1). */
    private fun Any.currentStoryIdentity(): String? = runCatching {
        val item = javaClass.methods.firstOrNull { it.name == "F1" && it.parameterCount == 0 }
            ?.invoke(this) ?: return null
        item.storyIdentity()
    }.getOrNull()

    /** Identity (bvid/cid) of the adapter item at the given index (V1). */
    private fun Any.storyItemAt(index: Int): Any? = runCatching {
        if (index < 0) return null
        val v1 = javaClass.methods.firstOrNull {
            it.name == "V1" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return null
        v1.invoke(this, index)
    }.getOrNull()

    /** Ad/live cards have no stable BV id — engine/pager sync breaks if background auto-next stops on them. */
    private fun isBackgroundAutoNextUnsafeCard(item: Any): Boolean {
        val type = item.javaClass
        fun bool(name: String) = runCatching {
            type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(item) as? Boolean
        }.getOrNull() == true
        return bool("isAd") || bool("isLive")
    }

    private fun Any.isUnsafeStoryIndex(index: Int): Boolean {
        val item = storyItemAt(index) ?: return false
        return isBackgroundAutoNextUnsafeCard(item)
    }

    /** Prefer BV-backed UGC when pause/resume lands on ad/live (unknown/cid). */
    private fun Any.preferredResumeIdentity(d1: Int): String? {
        if (d1 >= 0 && !isUnsafeStoryIndex(d1)) {
            storyIdentityAt(d1)?.let { return it }
        }
        val count = invokeIntGetter("N1") ?: 0
        if (d1 >= 0) {
            for (i in d1 downTo 0) {
                if (!isUnsafeStoryIndex(i)) return storyIdentityAt(i)
            }
            for (i in (d1 + 1) until count) {
                if (!isUnsafeStoryIndex(i)) return storyIdentityAt(i)
            }
        }
        return null
    }

    /** Identity (bvid/cid) of the adapter item at the given index (V1). */
    private fun Any.storyIdentityAt(index: Int): String? = runCatching {
        if (index < 0) return null
        val v1 = javaClass.methods.firstOrNull {
            it.name == "V1" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return null
        val item = v1.invoke(this, index) ?: return null
        item.storyIdentity()
    }.getOrNull()

    private fun storyIdentityAt(index: Int): String? = activeStoryPlayer?.storyIdentityAt(index)

    private fun currentStoryIdentity(): String? = activeStoryPlayer?.currentStoryIdentity()

    /**
     * After Activity recreate, ViewPager D1 resets to 0 while the engine/session stayed on a
     * later item (log: w2 keep session tracked=22 d1=0, then u2-stale-track wrongly realigned to 0).
     */
    private fun Any.isStalePagerVersusSession(d1: Int): Boolean {
        if (d1 < 0) return false
        val sessionIdx = maxOf(
            liveEngineIndex,
            trackedIndex,
            backgroundEngineIndex,
            resumeHandoffIndex,
        ).coerceAtLeast(-1)
        return sessionIdx > d1
    }

    private fun Any.resolveStalePagerCatchUpIndex(pagerIndex: Int): Int {
        if (pagerIndex < 0 || !isStalePagerVersusSession(pagerIndex)) return -1
        return maxOf(
            liveEngineIndex,
            trackedIndex,
            backgroundEngineIndex,
            resumeHandoffIndex,
            captureEnginePlayingIndex(),
        ).coerceAtLeast(0)
    }

    /** u2: save feed size + session before official w2 h1 can replace the list with a single item. */
    private fun Any.captureResumeHandoffIfNeeded(d1OnPause: Int) {
        val feedCount = invokeIntGetter("N1") ?: -1
        val currentId = preferredResumeIdentity(d1OnPause)
            ?: playingStoryKey ?: activeStoryKey ?: captureEnginePlayingIdentity()
        val sessionIdx = maxOf(
            liveEngineIndex,
            trackedIndex,
            backgroundEngineIndex,
            captureEnginePlayingIndex(),
        ).coerceAtLeast(-1)
        if (feedCount >= 2) {
            resumeHandoffCount = feedCount
            resumeHandoffPlayerId = System.identityHashCode(this)
            blockDestructiveH1UntilMs = System.currentTimeMillis() + 15_000L
            if (currentId != null) resumeHandoffId = currentId
            captureResumeFeedSnapshot()?.let { snap ->
                resumeFeedSnapshot = snap
                Log.trace {
                    "StoryAutoNext: snapshot feed size=${snap.size} idx=$resumeHandoffIndex id=$currentId"
                }
            }
        }
        if (sessionIdx < 0 && d1OnPause < 0) return
        if (sessionIdx <= d1OnPause && !isStalePagerVersusSession(d1OnPause)) {
            if (feedCount >= 2) {
                resumeHandoffIndex = maxOf(sessionIdx, d1OnPause).coerceAtLeast(0)
                Log.trace {
                    "StoryAutoNext: capture resume handoff idx=${resumeHandoffIndex} count=$feedCount " +
                        "id=$currentId (h1-guard)"
                }
            }
            return
        }
        val idx = maxOf(sessionIdx, d1OnPause).coerceAtLeast(0)
        resumeHandoffIndex = idx
        resumeHandoffId = currentId
        resumeHandoffCount = feedCount
        resumeHandoffPlayerId = System.identityHashCode(this)
        blockDestructiveH1UntilMs = System.currentTimeMillis() + 15_000L
        Log.trace {
            "StoryAutoNext: capture resume handoff idx=$idx count=$resumeHandoffCount id=$resumeHandoffId"
        }
    }

    /** Copy adapter chain on u2 so w2 can rebuild it if official h1 collapses N1. */
    private fun Any.captureResumeFeedSnapshot(): List<Any>? = runCatching {
        val count = invokeIntGetter("N1") ?: return@runCatching null
        if (count < 2) return@runCatching null
        val v1 = javaClass.methods.firstOrNull {
            it.name == "V1" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return@runCatching null
        val items = ArrayList<Any>(count)
        for (i in 0 until count) {
            v1.invoke(this, i)?.let { items.add(it) }
        }
        items.takeIf { it.size >= 2 }
    }.getOrNull()

    /**
     * Re-apply saved feed when w2/h1 left N1 truncated (not just wrong D1).
     * Invokes official h1 with the u2 snapshot — bypasses shrink guard because size matches snapshot.
     */
    private fun Any.restoreResumeFeedSnapshotIfNeeded(): Boolean {
        if (resumeFeedRestoreInProgress) return false
        val snapshot = resumeFeedSnapshot ?: return false
        if (snapshot.size < 2 || resumeHandoffCount < 2) return false
        val countBefore = invokeIntGetter("N1") ?: 0
        if (countBefore >= resumeHandoffCount) {
            resumeFeedSnapshot = null
            return false
        }
        return runCatching {
            resumeFeedRestoreInProgress = true
            val h1 = javaClass.methods.firstOrNull {
                it.name == "h1" && it.parameterCount == 1 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: return@runCatching false
            h1.invoke(this, snapshot)
            val countAfter = invokeIntGetter("N1") ?: 0
            Log.trace {
                "StoryAutoNext: restore feed snapshot items=${snapshot.size} " +
                    "count $countBefore->$countAfter resumeIdx=$resumeHandoffIndex"
            }
            resumeFeedSnapshot = null
            countAfter >= snapshot.size || countAfter > countBefore
        }.getOrElse {
            Log.e(it)
            false
        }.also {
            resumeFeedRestoreInProgress = false
        }
    }

    /** Block w2 deferred h1 that shrinks the fg session feed (log: count=39 → 1). */
    private fun Any.shouldBlockDestructiveH1(incoming: List<*>): Boolean {
        if (resumeFeedRestoreInProgress) return false
        if (System.currentTimeMillis() > blockDestructiveH1UntilMs) return false
        if (resumeHandoffCount < 2) return false
        val host = playerHostName()
        if (host != null && host != visibleSessionHost()) return false
        if (incoming.size >= resumeHandoffCount) return false
        resumeHandoffId?.let { targetId ->
            val incomingHasTarget = incoming.any { item ->
                identitiesMatch(item?.storyIdentity(), targetId)
            }
            if (incomingHasTarget &&
                resumeHandoffIndex >= 0 &&
                resumeHandoffIndex < incoming.size
            ) {
                return false
            }
        }
        if (resumeHandoffCount > incoming.size) {
            Log.trace {
                "StoryAutoNext: block destructive h1 shrink $resumeHandoffCount->${incoming.size} " +
                    "resumeIdx=$resumeHandoffIndex id=$resumeHandoffId"
            }
            return true
        }
        return false
    }

    /**
     * F2 while the adapter still holds the full fg chain — official w2 h1 flush runs in proceed()
     * and can collapse N1 to 1 before post-proceed F2 (log 175335: engine=35 count=1).
     */
    private fun Any.trySyncPagerBeforeW2Proceed(
        preferredIndex: Int,
        targetId: String?,
        pagerBefore: Int,
    ): Boolean {
        if (preferredIndex < 0 || !shouldRunW2PagerSync(preferredIndex, pagerBefore)) return false
        restoreResumeFeedSnapshotIfNeeded()
        var count = invokeIntGetter("N1") ?: 0
        val idIndex = if (targetId != null) indexOfStoryIdentity(targetId) else -1
        if (count <= preferredIndex && idIndex < 0) {
            Log.trace {
                "StoryAutoNext: pre-w2 F2 skipped — count=$count need>$preferredIndex id=$targetId"
            }
            return false
        }
        val resolved = resolveCatchUpIndex(preferredIndex, targetId)
        if (resolved < 0) return false
        Log.trace {
            "StoryAutoNext: pre-w2 F2 sync engine=$resolved pager=$pagerBefore count=$count id=$targetId"
        }
        applyOfficialResumePosition()
        withStoryPagerActiveFlag {
            callOfficialPagerAdvance(resolved, smooth = false)
            syncOfficialPagerIndex(resolved)
            trackedIndex = resolved
            liveEngineIndex = resolved
            targetId?.let {
                playingStoryKey = it
                activeStoryKey = it
            }
        }
        val d1 = invokeIntGetter("D1", "getIndex") ?: -1
        val pagerId = if (d1 >= 0) storyIdentityAt(d1) else null
        val ok = d1 == resolved &&
            (targetId == null || identitiesMatch(pagerId, targetId))
        if (ok) {
            Log.trace { "StoryAutoNext: pre-w2 F2 ok index=$resolved id=$targetId d1=$d1" }
        }
        return ok
    }

    /**
     * When the user swipes back in foreground (pager index drops) but a prior background session
     * left liveEngineIndex ahead, the next background auto-next must start from the pager — not
     * continue from stale engine index (see log d1=0 advanced to index=5).
     */
    private fun Any.indexOfStoryIdentity(targetId: String?): Int {
        if (targetId == null) return -1
        val count = invokeIntGetter("N1") ?: 0
        for (i in 0 until count) {
            if (identitiesMatch(storyIdentityAt(i), targetId)) return i
        }
        return -1
    }

    /** Index the engine is actually playing — not ViewPager D1 which can preload ahead. */
    private fun Any.captureEnginePlayingIndex(): Int {
        val engineId = captureEnginePlayingIdentity() ?: playingStoryKey ?: activeStoryKey
        indexOfStoryIdentity(engineId).takeIf { it >= 0 }?.let { return it }
        val d1 = invokeIntGetter("D1", "getIndex") ?: -1
        if (liveEngineIndex >= 0) return liveEngineIndex
        if (trackedIndex >= 0) return trackedIndex
        return d1
    }

    /**
     * Reset companion indices to pager D1 after the user swipes in foreground or re-enters
     * background on an earlier item. [force] skips guards that keep engine-ahead during bg play.
     */
    private fun Any.reconcileForegroundPageFromPager(source: String) {
        if (isInBackground || activityPaused) return
        if (System.identityHashCode(this) == pagerSyncInProgressPlayerId) return
        if (officialX2RebindInProgress || appBackgroundResumeHandoffEligible) return
        if (playerHostName() != visibleSessionHost()) return
        val d1 = invokeIntGetter("D1", "getIndex") ?: return
        if (liveEngineIndex == d1 && trackedIndex == d1 && !backgroundEngineMoved) return
        realignEngineTrackingToPager(source, force = true)
    }

    private fun Any.realignEngineTrackingToPager(source: String, force: Boolean = false) {
        val d1 = invokeIntGetter("D1", "getIndex") ?: return
        val host = playerHostName()
        val upSpaceOwnFeed = isUpSpacePlayerWithoutBgSession(host)
        if (!force) {
            // D1 can preload the next page while audio still plays the current item — never pull engine up.
            if (source == "u2" && liveEngineIndex >= 0 && d1 > liveEngineIndex && !upSpaceOwnFeed) {
                return
            }
            if (backgroundEngineMoved && liveEngineIndex > d1) {
                if (!upSpaceOwnFeed) return
            }
            // w2 adapter flush / u2 pause can report D1=0 while engine is still at the paused index.
            if (liveEngineIndex > d1 && source in setOf("addVideo", "resolve", "u2") && !upSpaceOwnFeed) {
                return
            }
        }
        if (liveEngineIndex == d1 && trackedIndex == d1 && !backgroundEngineMoved) return
        Log.trace { "StoryAutoNext: realign engine to pager d1=$d1 " +
                "(live=$liveEngineIndex tracked=$trackedIndex source=$source)" }
        trackedIndex = d1
        liveEngineIndex = d1
        backgroundEngineIndex = -1
        backgroundEngineMoved = false
        tailWaitKey = null
        storyIdentityAt(d1)?.let {
            playingStoryKey = it
            activeStoryKey = it
        }
    }

    /** Current engine slot for background auto-next / media-button previous. */
    private fun Any.resolveBackgroundCurrentIndex(): Int {
        val engineIdx = captureEnginePlayingIndex()
        val d1 = invokeIntGetter("D1", "getIndex") ?: 0
        val engineId = captureEnginePlayingIdentity() ?: playingStoryKey ?: activeStoryKey
        val pagerIdAtD1 = if (d1 >= 0) storyIdentityAt(d1) else null
        val d1MatchesEngine = engineId != null && pagerIdAtD1 != null &&
            identitiesMatch(engineId, pagerIdAtD1)
        // Pager and engine agree — D1 is canonical (covers fg swipe-back before u2 realign).
        if (d1MatchesEngine && d1 >= 0) {
            return d1
        }
        if (backgroundEngineMoved) {
            return maxOf(
                trackedIndex.takeIf { it >= 0 } ?: engineIdx,
                liveEngineIndex,
                backgroundEngineIndex,
                engineIdx,
            )
        }
        // Ignore preload-ahead D1 and stale tracked indices left from bg→fg w2 sync.
        var current = engineIdx
        trackedIndex.takeIf { it >= 0 && it <= engineIdx }?.let { current = maxOf(current, it) }
        liveEngineIndex.takeIf { it >= 0 && it <= engineIdx }?.let { current = maxOf(current, it) }
        return current
    }

    /**
     * Map engine index to pager index. Prefer the canonical engine slot when identity matches
     * there; otherwise pick the duplicate closest to [preferredIndex] (not global firstAny tail).
     * After bg feed refresh the same id may sit at a lower index than [preferredIndex] — still
     * follow identity (official nq() uses aid/cid only).
     */
    private fun Any.resolveCatchUpIndex(preferredIndex: Int, targetId: String?): Int {
        val count = invokeIntGetter("N1") ?: 0
        if (count <= 0) return -1
        if (preferredIndex in 0 until count && targetId != null) {
            val atPreferred = storyIdentityAt(preferredIndex)
            if (identitiesMatch(atPreferred, targetId)) {
                return preferredIndex
            }
        }
        if (preferredIndex >= count &&
            resumeHandoffIndex == preferredIndex &&
            resumeHandoffId != null &&
            targetId != null &&
            identitiesMatch(resumeHandoffId, targetId)
        ) {
            return -1
        }
        if (targetId != null) {
            var best = -1
            var bestDist = Int.MAX_VALUE
            for (i in 0 until count) {
                if (!identitiesMatch(storyIdentityAt(i), targetId)) continue
                val dist = if (preferredIndex >= 0) kotlin.math.abs(i - preferredIndex) else i
                if (best < 0 || dist < bestDist || (dist == bestDist && i < best)) {
                    best = i
                    bestDist = dist
                }
            }
            if (best >= 0) {
                if (best != preferredIndex) {
                    Log.trace {
                        "StoryAutoNext: resolve by id $targetId preferred=$preferredIndex -> $best count=$count"
                    }
                }
                return best
            }
        }
        if (preferredIndex in 0 until count) return preferredIndex
        return -1
    }

    /** Match full bvid/cid keys; cid-only when bvid is unknown (StoryPlayer.l vs adapter V1). */
    private fun identitiesMatch(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a == b) return true
        val aParts = a.split('/', limit = 2)
        val bParts = b.split('/', limit = 2)
        val aBvid = aParts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "unknown" }
        val bBvid = bParts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "unknown" }
        val aCid = aParts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        val bCid = bParts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        if (aCid != null && bCid != null && aCid == bCid) return true
        if (aBvid != null && bBvid != null && aBvid == bBvid) return true
        return false
    }

    /** StoryPagerPlayer.h1 defer buffer (f208484w / field `w`); clear after bg append to avoid w2 double-flush. */
    private fun Any.clearDeferredFeedQueue(): Int = runCatching {
        val field = javaClass.declaredFields.firstOrNull { it.name == "w" }
            ?: return@runCatching 0
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(this) as? ArrayList<Any?> ?: return@runCatching 0
        if (list.isEmpty()) return@runCatching 0
        val first = list.firstOrNull() ?: return@runCatching 0
        if (!first.javaClass.name.contains("StoryDetail")) return@runCatching 0
        val n = list.size
        field.set(this, null)
        runCatching {
            javaClass.getDeclaredField("x").apply { isAccessible = true }.setBoolean(this, false)
        }
        n
    }.getOrElse {
        Log.e(it)
        0
    }

    /** jadx StoryPagerPlayer.h2(): I || Q==2 — when false, W2/h1 defer into field `w`. */
    private fun Any.isOfficialPagerActive(): Boolean {
        val resumed = runCatching {
            javaClass.getDeclaredField("I").apply { isAccessible = true }.getBoolean(this)
        }.getOrNull() ?: false
        val q = invokeIntGetter("Q") ?: 0
        return resumed || q == 2
    }

    private fun isResumeHandoffArmed(): Boolean =
        System.currentTimeMillis() <= blockDestructiveH1UntilMs && resumeHandoffCount >= 2

    private fun Any.officialDeferredFeedQueueSize(): Int = runCatching {
        javaClass.getDeclaredField("w").apply { isAccessible = true }
            .get(this)?.let { (it as? List<*>)?.size } ?: 0
    }.getOrDefault(0)

    private fun Any.officialDeferredFeedIsFullReplace(): Boolean = runCatching {
        javaClass.getDeclaredField("x").apply { isAccessible = true }.getBoolean(this)
    }.getOrDefault(false)

    /** Drop f208484w before w2(); official w2 with f208485x=true calls W2/F1 and wipes the chain. */
    private fun Any.clearOfficialDeferredFeedQueue() {
        runCatching {
            javaClass.getDeclaredField("w").apply { isAccessible = true }.set(this, null)
            javaClass.getDeclaredField("x").apply { isAccessible = true }.setBoolean(this, false)
        }.onFailure { Log.e(it) }
    }

    /**
     * jadx w2(): flush f208484w — if f208485x then W2/F1 (clear+replace), else h1/P0 (append).
     * Background API often queues a 1-card W2 while I=false; w2 then replaces 39 items with 1.
     */
    private fun Any.guardOfficialW2FeedFlushBeforeResume() {
        if (!isResumeHandoffArmed()) return
        val n1 = invokeIntGetter("N1") ?: 0
        if (n1 == 0) {
            restoreResumeFeedSnapshotIfNeeded()
        }
        if (!officialDeferredFeedIsFullReplace()) return
        val queued = officialDeferredFeedQueueSize()
        if (queued <= 0 || queued >= resumeHandoffCount) return
        Log.trace {
            "StoryAutoNext: drop deferred W2 queue size=$queued saved=$resumeHandoffCount " +
                "(official w2 would F1-replace chain)"
        }
        clearOfficialDeferredFeedQueue()
    }

    /** Block W2 while I=false from queueing a shrink-replace batch into f208484w. */
    private fun Any.shouldBlockDeferredW2Replace(incoming: List<*>): Boolean {
        if (!isResumeHandoffArmed()) return false
        if (isOfficialPagerActive()) return false
        if (incoming.size >= resumeHandoffCount) return false
        Log.trace {
            "StoryAutoNext: block deferred W2 size=${incoming.size} saved=$resumeHandoffCount " +
                "tag=${storyPagerTag()}"
        }
        return true
    }

    /** Identity of the video StoryPlayer is actually playing (not stale adapter index). */
    private fun Any.captureEnginePlayingIdentity(): String? = runCatching {
        val storyPlayer = javaClass.declaredFields.firstOrNull {
            it.type.name == "com.bilibili.video.story.player.StoryPlayer"
        }?.apply { isAccessible = true }?.get(this) ?: return@runCatching null
        val params = storyPlayer.javaClass.methods.firstOrNull {
            it.name == "l" && it.parameterCount == 0
        }?.invoke(storyPlayer) ?: return@runCatching null
        params.storyIdentity()
    }.getOrNull()

    /**
     * The live IPlayerCoreService that StoryPlayer actually plays through (StoryPlayer field
     * b, concrete type == playerCoreMethods.serviceClass / vd3.p0). This is the authoritative
     * position/seek target for both background and foreground; the reflection-scanned
     * activePlayerCore can resolve to an unrelated object that reports position 0.
     */
    private fun liveStoryPlayerCore(): Any? =
        foregroundHandoffStoryPlayer?.liveStoryPlayerCoreFrom()
            ?: (if (isInBackground) storyPlayerForBackgroundAutoNext() else activeStoryPlayer)
                ?.liveStoryPlayerCoreFrom()

    /** IPlayerCoreService bound to a specific StoryPagerPlayer (not global activeStoryPlayer). */
    private fun Any.liveStoryPlayerCoreFrom(): Any? = runCatching {
        val serviceClass = instance.playerCoreMethods?.serviceClass ?: return@runCatching null
        val storyPlayer = javaClass.declaredFields.firstOrNull {
            it.type.name == "com.bilibili.video.story.player.StoryPlayer"
        }?.apply { isAccessible = true }?.get(this) ?: return@runCatching null
        storyPlayer.javaClass.declaredFields.asSequence()
            .mapNotNull { f -> runCatching { f.isAccessible = true; f.get(storyPlayer) }.getOrNull() }
            .firstOrNull { serviceClass.isInstance(it) }
    }.getOrNull()

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
        Log.trace { "StoryAutoNext: background loop mode autoNext=$autoNext singleLoop=${!autoNext}" }
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
        Log.s("StoryAutoNext: hooked runtime looping class ${clazz.name} methods=${methods.joinToString { it.name }}")
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
        if (!isAutoNextEnabled() || !isInBackground || !activityPaused) return
        if (polling) return
        polling = true
        val runnable = object : Runnable {
            override fun run() {
                if (!isAutoNextEnabled() || !isInBackground || !activityPaused) {
                    stopEndPolling()
                    return
                }
                runCatching { checkPlaybackEndAndNext() }.onFailure { Log.e(it) }
                if (polling && isInBackground) {
                    mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        endPollingRunnable = runnable
        mainHandler.post(runnable)
        Log.trace { "StoryAutoNext: started end polling" }
    }

    private fun stopEndPolling() {
        endPollingRunnable?.let { mainHandler.removeCallbacks(it) }
        endPollingRunnable = null
        polling = false
    }

    /**
     * Keep trackedIndex aligned with the pager on addVideo. In background the engine runs ahead
     * of ViewPager2/D1(); never pull trackedIndex down to stale D1=0 during loadMore h1().
     */
    private fun reconcileTrackedIndexOnAddVideo(player: Any, backgroundLoadActive: Boolean) {
        if (isInBackground || backgroundEngineMoved) return
        if (isUpSpacePlayerWithoutBgSession(player.playerHostName())) {
            player.syncPlayerTrackingFromPager("addVideo-space", full = false)
            return
        }
        // Foreground: follow pager metadata, not engine — engine can lag during auto-next/swipe.
        player.currentStoryIdentity()?.let {
            playingStoryKey = it
            activeStoryKey = it
        }
        val currentD1 = player.invokeIntGetter("D1", "getIndex") ?: 0
        if (!backgroundLoadActive && trackedIndex >= 0 && currentD1 < trackedIndex) {
            if (liveEngineIndex <= currentD1) {
                trackedIndex = currentD1
                liveEngineIndex = currentD1
            }
            tailWaitKey = null
        } else if (trackedIndex < currentD1 && liveEngineIndex <= currentD1) {
            trackedIndex = currentD1
            liveEngineIndex = currentD1
        }
    }

    private fun checkPlaybackEndAndNext() {
        if (!isAutoNextEnabled() || !isInBackground || !activityPaused) return
        val root = storyPlayerForBackgroundAutoNext() ?: return
        val core = liveStoryPlayerCore() ?: return
        val position = core.invokeLongGetter("getCurrentPosition", "getRealCurrentPosition") ?: return
        val duration = core.invokeLongGetter("getDuration", "getRealDuration") ?: return
        prefetchOfficialStoryListIfNeeded()
        if (duration <= 0L || position <= 0L || duration - position > END_THRESHOLD_MS) return
        val now = System.currentTimeMillis()
        if (now - lastNextAtMs < NEXT_COOLDOWN_MS) return
        Log.trace { "StoryAutoNext: playback ended key=$activeStoryKey, position=$position, duration=$duration, " +
                "core=${core.javaClass.name}#${System.identityHashCode(core)} bg=$isInBackground" }
        if (triggerNextStory()) {
            lastNextAtMs = now
        }
    }

    private fun prefetchOfficialStoryListIfNeeded() {
        // Background auto-next only consumes existing feed slots; prefetch would append
        // extra recommendation batches and mix them with the A→B→C→D chain.
        if (isInBackground) return
        val root = activeStoryPlayer ?: return
        val d1 = root.invokeIntGetter("D1", "getIndex") ?: return
        val current = if (trackedIndex >= d1) trackedIndex else d1
        val count = root.invokeIntGetter("N1") ?: return
        if (count <= 0 || current < count - 3) return
        root.callOfficialStoryLoadMore(current, force = false, prefetch = true)
    }

    private fun triggerNextStory(): Boolean = runCatching {
        val root = storyPlayerForBackgroundAutoNext() ?: return@runCatching false
        val d1 = root.invokeIntGetter("D1", "getIndex") ?: return false
        val current = root.resolveBackgroundCurrentIndex()
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
            Log.trace { "StoryAutoNext: waiting official loadMore current=$current count=$count" }
            return false
        }
        tailWaitKey = null
        var nextIndex = current + 1
        var skippedSkippable = 0
        while (nextIndex < count && skippedSkippable < 8 && root.isUnsafeStoryIndex(nextIndex)) {
            nextIndex++
            skippedSkippable++
        }
        if (skippedSkippable > 0) {
            Log.trace {
                "StoryAutoNext: skipped $skippedSkippable ad/live card(s) before index=$nextIndex"
            }
        }
        if (nextIndex >= count) {
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
            Log.trace { "StoryAutoNext: waiting official loadMore current=$current count=$count" }
            return false
        }
        // Only fetch when the next adapter slot does not exist yet (official tail behaviour).
        // Unconditional loadMore on every step was appending fresh API batches at the tail
        // while the engine walked indices 1,2,3… — mixing EFG with the original BCD chain.
        val moved = root.callOfficialStoryNext(nextIndex)
        if (moved) {
            trackedIndex = nextIndex
            backgroundEngineIndex = nextIndex
            backgroundEngineMoved = true
            liveEngineIndex = nextIndex
            rememberBackgroundEnginePlayer(root)
            val identity = root.captureEnginePlayingIdentity()
                ?: root.storyIdentityAt(nextIndex)
            activeStoryKey = identity ?: activeStoryKey
            playingStoryKey = identity ?: playingStoryKey
            val d1After = root.invokeIntGetter("D1", "getIndex") ?: -1
            Log.trace { "StoryAutoNext: advanced engine to index=$nextIndex " +
                    "(d1=$d1After was=$d1 id=$playingStoryKey)" }
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
        var resolved = hostFromPlayer?.takeIf { it.isStoryHost() }
            ?: activeStoryHost?.takeIf { it.isStoryHost() }
            ?: findStoryHost()
        if (resolved == null) return@runCatching null
        if (isMainFeedStoryPlayer() && visibleSessionHost() == HOST_MAIN &&
            resolved.javaClass.name == HOST_SPACE
        ) {
            resolved = findStoryHost()?.takeIf { it.javaClass.name == HOST_MAIN } ?: resolved
            Log.trace { "StoryAutoNext: loadMore rerouted from UP-space host to main feed" }
        }
        resolved
    }.getOrNull()

    private fun Any.callOfficialStoryLoadMore(
        index: Int,
        force: Boolean,
        prefetch: Boolean = false,
    ): Boolean = runCatching {
        if (!force) {
            val count = invokeIntGetter("N1") ?: return@runCatching false
            if (prefetch) {
                if (count <= 0 || index < count - 3) return@runCatching false
            } else if (index + 1 < count) {
                Log.trace { "StoryAutoNext: skip loadMore index=$index count=$count (next slot exists)" }
                return@runCatching false
            }
        }
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
            mainHandler.postDelayed({ backgroundLoadActive = false }, 3_000L)
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
                                val deferred = player.clearDeferredFeedQueue()
                                if (deferred > 0) {
                                    Log.trace { "StoryAutoNext: cleared deferred queue after bg h1 size=$deferred" }
                                }
                                Log.trace { "StoryAutoNext: video feed added ${items.size} items, new count=${player.invokeIntGetter("N1")}" }
                                backgroundLoadActive = false
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
            Log.trace { "StoryAutoNext: called StoryVideoLoader.i() directly for recommendation feed" }
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
                                val deferred = player.clearDeferredFeedQueue()
                                if (deferred > 0) {
                                    Log.trace { "StoryAutoNext: cleared deferred queue after loader h1 size=$deferred" }
                                }
                                Log.trace { "StoryAutoNext: loader direct added ${items.size} items, new count=${player.invokeIntGetter("N1")}" }
                            }.onFailure { Log.e(it) }
                        }
                    }
                    Unit
                }
                "onError" -> {
                    Log.trace { "StoryAutoNext: loader direct onError" }
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
            Log.trace { "StoryAutoNext: called loader.g() directly, refresh=$isRefresh" }
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
     * Advance to [index]. Always runs official F2/D2 first so D1 + adapter metadata stay aligned
     * (StoryPagerPlayer.F1 = adapter.Z0(D1)). Background still needs StoryPlayer.h2 after F2
     * because ViewPager2 won't bind/play while inactive — but naked h2 alone caused "video switched,
     * no official swipe, metadata stuck on previous" (engine ahead of D1).
     */
    private fun Any.forcePlayAtIndex(index: Int) {
        if (!isInBackground) {
            Log.trace { "StoryAutoNext: foreground advance via official F2 index=$index (no naked h2)" }
            syncOfficialPagerMetadata(index, smooth = true)
            return
        }
        val targetId = playingStoryKey ?: storyIdentityAt(index)
        syncBackgroundPagerStep(index, targetId)
        forceEnginePlayAtIndex(index)
    }

    /** Background-only engine switch after official F2; never call without syncOfficialPagerMetadata. */
    private fun Any.forceEnginePlayAtIndex(index: Int) {
        if (!isInBackground) return
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
                Log.trace { "StoryAutoNext: forced StoryPlayer.h2($index) after PlayHandler reset" }
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
        Log.trace { "StoryAutoNext: switched official play mode to next" }
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
            Log.trace { "StoryAutoNext: requested direct StorySpaceFragment.Cq loadMore" }
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
