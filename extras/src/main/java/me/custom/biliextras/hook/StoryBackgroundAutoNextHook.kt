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

        @Volatile
        var endPollingRunnable: Runnable? = null

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
            appBackgroundResumeHandoffEligible = false
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
        hookFragmentLifecycleBypass()
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
            Log.x("StoryAutoNext: fg scroll ${if (forward) "next" else "prev"} index=$target (current=$current count=$count)")
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
            Log.x("StoryAutoNext: no previous story (current=$current)")
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
            Log.x("StoryAutoNext: moved to previous index=$prevIndex (d1=$d1)")
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
        if (!backgroundEngineMoved && !pagerBehindEngine) return false
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
            Log.x("StoryAutoNext: w2 adopt background-engine player for F2 sync")
            return true
        }
        if (host == HOST_MAIN && storyPagerTag() == "StoryVideoFragment") {
            captureActiveStoryPlayer(this)
            Log.x("StoryAutoNext: w2 adopt main-feed player for F2 sync (stale activeStoryPlayer)")
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

    /**
     * Outer tab switch while activity is still visible — not true background; stop bg polling.
     * Do NOT sync trackedIndex here: onPageSelected runs before the target tab's w2() resumes
     * the adapter (UP space D1/F1 are still stale — log 220906 timing gap ~600ms).
     */
    private fun reconcileForegroundOnOuterTabSwitch() {
        if (activityPaused) return
        if (isInBackground || polling) {
            isInBackground = false
            backgroundSessionHost = null
            stopEndPolling()
            Log.x(
                "StoryAutoNext: outer tab foreground — stop bg polling host=${visibleSessionHost()}",
            )
        }
        mainFeedStoryPlayer?.let { applyBackgroundLoopMode(it, autoNext = false) }
        upSpaceStoryPlayer?.let { applyBackgroundLoopMode(it, autoNext = false) }
        appBackgroundResumeHandoffEligible = false
        cancelForegroundResumeHandoff()
    }

    private fun Any.syncVisibleTabTrackingAfterW2() {
        if (isInBackground || activityPaused) return
        if (playerHostName() != visibleSessionHost()) return
        syncPlayerTrackingFromPager("w2-visible", full = true)
        captureActiveStoryPlayer(this)
    }

    /** Outer ViewPager tab → session host (authoritative over stale activeStoryPlayer). */
    private fun visibleSessionHost(): String =
        if (visibleOuterTabIndex == 1) HOST_SPACE else HOST_MAIN

    /** Drop stale companion state when StoryVideoActivity is created/destroyed (home re-entry). */
    private fun resetStorySessionOnActivityBoundary(reason: String) {
        isInBackground = false
        activityPaused = false
        backgroundSessionHost = null
        backgroundEngineMoved = false
        backgroundEngineIndex = -1
        backgroundEngineHost = null
        backgroundEnginePlayerId = -1
        appBackgroundResumeHandoffEligible = false
        cancelForegroundResumeHandoff()
        stopEndPolling()
        Log.x("StoryAutoNext: reset session ($reason)")
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
            Log.x("StoryAutoNext: outer tab index=$index host=${visibleSessionHost()}")
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
        activityClass.hookMethod("onCreate", android.os.Bundle::class.java) { chain ->
            resetStorySessionOnActivityBoundary("onCreate")
            chain.proceed()
        }
        activityClass.hookMethod("onDestroy") { chain ->
            resetStorySessionOnActivityBoundary("onDestroy")
            chain.proceed()
        }
        activityClass.hookMethod("onPause") { chain ->
            activityPaused = true
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
                Log.x("StoryAutoNext: outer tab selected index=$index host=${visibleSessionHost()}")
                reconcileForegroundOnOuterTabSwitch()
            }
            chain.proceed()
        }
        Log.x("StoryAutoNext: hooked StoryVideoActivity tab tracking")
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
        storyIdentityAt(d1)?.let {
            playingStoryKey = it
            activeStoryKey = it
        }
        if (host != null) {
            trackedIndexHost = host
        }
        if (full) {
            trackedIndex = d1
            liveEngineIndex = d1
            backgroundEngineIndex = -1
            if (!isInBackground) {
                backgroundEngineMoved = false
            }
            tailWaitKey = null
        }
        Log.x(
            "StoryAutoNext: sync tracking d1=$d1 host=$host full=$full source=$source " +
                "(live=$liveEngineIndex tracked=$trackedIndex id=$playingStoryKey)",
        )
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
     * After background engine advance, align ViewPager2/D1 via official F2 + J2.
     * Never W2 (truncates feed). Never forcePlayAtIndex here — h2 without F2 breaks swipe chain.
     */
    private fun Any.syncPagerToEngineIndex(
        preferredIndex: Int,
        targetId: String?,
        attempt: Int = 0,
    ) {
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
                        syncPagerToEngineIndex(preferredIndex, targetId, attempt + 1)
                    }.onFailure { Log.e(it) }
                }, delayMs)
                return
            }
            Log.x(
                "StoryAutoNext: F2 sync failed preferred=$preferredIndex " +
                    "count=${invokeIntGetter("N1")} id=$targetId",
            )
            reconcileEngineToVisiblePagerOnSyncFailure(preferredIndex, targetId)
            finishForegroundResumeHandoffIfPending(this)
            return
        }
        val d1Before = invokeIntGetter("D1", "getIndex") ?: -1
        val pagerIdBefore = if (d1Before >= 0) storyIdentityAt(d1Before) else null
        if (d1Before == resolved && (targetId == null || identitiesMatch(pagerIdBefore, targetId))) {
            if (applyOfficialResumePosition()) {
                invokeOfficialPlayAtD1(smooth = false)
            }
            clearBackgroundSession(resolved, targetId)
            Log.x("StoryAutoNext: pager already at index=$resolved id=$targetId")
            finishForegroundResumeHandoffIfPending(this)
            return
        }
        Log.x(
            "StoryAutoNext: F2 sync start engine=$resolved pager=$d1Before id=$targetId",
        )
        applyOfficialPagerAlign(resolved, targetId, attempt = 0)
    }

    /** Official F2 + J2; retry until D1 matches — same stack as official W2 resume. */
    private fun Any.applyOfficialPagerAlign(resolved: Int, targetId: String?, attempt: Int) {
        applyOfficialResumePosition()
        // w2 catch-up: engine already plays target in bg — instant F2 only (smooth replays scroll).
        callPagerScroll(resolved, smooth = false)
        syncOfficialPagerIndex(resolved)
        trackedIndex = resolved
        liveEngineIndex = resolved
        targetId?.let { playingStoryKey = it }

        val d1 = invokeIntGetter("D1", "getIndex") ?: -1
        val pagerId = if (d1 >= 0) storyIdentityAt(d1) else null
        val f1Id = this.currentStoryIdentity()
        // F1() = adapter.Z0(D1) drives title/author; D1 alone is not enough (log: d1=16 F1=other).
        val identityOk = targetId == null ||
            (identitiesMatch(pagerId, targetId) && identitiesMatch(f1Id, targetId))
        if (d1 == resolved && identityOk) {
            syncStoryPlayerDirectorIndex(resolved)
            clearBackgroundSession(resolved, targetId)
            Log.x(
                "StoryAutoNext: F2 sync ok index=$resolved id=$targetId d1=$d1 F1=$f1Id",
            )
            finishForegroundResumeHandoffIfPending(this)
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
        Log.x(
            "StoryAutoNext: F2 sync incomplete index=$resolved id=$targetId " +
                "d1=$d1 F1=$f1Id attempt=$attempt",
        )
        reconcileEngineToVisiblePagerOnSyncFailure(resolved, targetId)
        finishForegroundResumeHandoffIfPending(this)
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
            Log.x("StoryAutoNext: F2 sync skipped — pager already shows target id=$targetId d1=$d1")
            return
        }
        Log.x(
            "StoryAutoNext: F2 sync gave up — official pager metadata index=$syncIndex " +
                "id=$targetId d1=$d1 F1=${currentStoryIdentity()}",
        )
        syncOfficialPagerMetadata(syncIndex, smooth = false, targetId)
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
     * Background per-episode pager sync (extension — official j1 skips D2 when O1() bg running).
     * jadx stack: I=true → D2(index,false) → F2(m1+setCurrentItem) → J2 → f.a(index).
     * x2/h2 at D1 blocked (d2 requires Q=3); engine h2 is separate in forceEnginePlayAtIndex.
     */
    private fun Any.syncBackgroundPagerStep(index: Int, targetId: String?) {
        withStoryPagerActiveFlag {
            syncOfficialPagerPosition(index, smooth = false)
            notifyOfficialPageMetadata(index)
        }
        var d1 = invokeIntGetter("D1", "getIndex") ?: -1
        if (d1 != index) {
            invokeOfficialRecyclerAlignToD1()
            d1 = invokeIntGetter("D1", "getIndex") ?: -1
        }
        if (d1 != index) {
            Log.x(
                "StoryAutoNext: bg pager step incomplete expected=$index d1=$d1 id=$targetId",
            )
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
        Log.x(
            "StoryAutoNext: capture bg resume pos=$pos id=$targetId runSync=$runSync",
        )
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
        Log.x("StoryAutoNext: apply f208480t=$pos before F2→x2 (official seek-on-prepare)")
        return true
    }

    /** StoryPagerPlayer.x2 — bind holder + h2 at D1; triggers official f208480t consume on prepare. */
    private fun Any.invokeOfficialPlayAtD1(smooth: Boolean = false) {
        runCatching {
            val x2 = javaClass.declaredMethods.firstOrNull {
                it.name == "x2" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            } ?: return@runCatching
            x2.isAccessible = true
            x2.invoke(this, smooth)
        }.onFailure { Log.e(it) }
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
        appBackgroundResumeHandoffEligible = false
    }

    /** End w2 when no F2 catch-up: drop any stale handoff flags. */
    private fun Any.completeW2ResumeHandoffCycle() {
        if (pendingForegroundHandoffPosMs >= BACKGROUND_RESUME_MIN_MS &&
            pendingForegroundHandoffPlayerId == System.identityHashCode(this)
        ) {
            finishForegroundResumeHandoffIfPending(this)
        } else {
            cancelForegroundResumeHandoff(this)
        }
    }

    /** Engine index pending F2 sync after official w2(); independent of isInBackground flag. */
    private fun Any.resolvePendingEngineIndex(pagerIndex: Int): Int {
        if (!isAutoNextEnabled()) return -1
        if (isUpSpacePlayerWithoutBgSession(playerHostName())) return -1
        val host = playerHostName()
        if (host != null && trackedIndexHost != null && host != trackedIndexHost && !backgroundEngineMoved) {
            return -1
        }
        val playingId = captureEnginePlayingIdentity() ?: playingStoryKey ?: activeStoryKey
        val pagerId = if (pagerIndex >= 0) storyIdentityAt(pagerIndex) else null
        val identityMismatch = playingId != null && pagerId != null && !identitiesMatch(playingId, pagerId)
        if (identityMismatch && !backgroundEngineMoved) return -1
        val preferred = when {
            backgroundEngineMoved && backgroundEngineIndex >= 0 -> backgroundEngineIndex
            identityMismatch -> maxOf(
                liveEngineIndex,
                trackedIndex,
                backgroundEngineIndex,
            ).coerceAtLeast(0)
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
                val result = chain.proceed()
                val deferred = player.clearDeferredFeedQueue()
                if (deferred > 0) {
                    Log.x("StoryAutoNext: cleared deferred queue after bg addVideo size=$deferred")
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
        Log.x("StoryAutoNext: hooked ${playerClass.name}#$addVideo and setLooping")
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
                (prevHost == HOST_MAIN || prevHost == null) &&
                backgroundEngineMoved && backgroundEngineHost == HOST_MAIN
            ) {
                // Main-feed bg auto-next ran ahead; align pager before UP-space host binds (I2).
                player.ensurePagerAlignedForNavigation()
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
        Log.x("StoryAutoNext: hooked ${storyPlayerClass.name}.setLooping")
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
        Log.x("StoryAutoNext: align pager for navigation d1=$d1 -> $preferred id=$targetId")
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
                    cancelForegroundResumeHandoff()
                    appBackgroundResumeHandoffEligible = true
                    backgroundSessionHost = visibleSessionHost()
                    isInBackground = true
                    captureActiveStoryPlayer(player)
                    player.preserveEngineIndexOnBackgroundPause()
                    player.realignEngineTrackingToPager("u2")
                    if (isAutoNextEnabled()) {
                        applyBackgroundLoopMode(player, autoNext = true)
                    }
                    ensurePolling()
                    Log.x(
                        "StoryAutoNext: bg pause tab=$visibleOuterTabIndex " +
                            "host=$backgroundSessionHost tag=${player.storyPagerTag()}",
                    )
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
                val engineIdNow = player.captureEnginePlayingIdentity()
                val pendingId = engineIdNow ?: playingStoryKey ?: activeStoryKey
                if (pendingIndex >= 0) {
                    Log.x(
                        "StoryAutoNext: w2 pending F2 sync engine=$pendingIndex " +
                            "pager=$pagerBefore id=$pendingId moved=$backgroundEngineMoved " +
                            "host=$playerHost tag=${player.storyPagerTag()} active=${player === activeStoryPlayer}",
                    )
                }
                val runSync = player.shouldRunW2PagerSync(pendingIndex, pagerBefore)
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
                // Official w2: I=true, adapter.onResume(), flush f208484w via h1 (never W2 here).
                chain.proceed()
                when {
                    pendingIndex < 0 -> {
                        player.syncVisibleTabTrackingAfterW2()
                        if (upSpaceEntry && !isInBackground) {
                            player.syncPlayerTrackingFromPager("w2-space", full = false)
                        }
                        player.completeW2ResumeHandoffCycle()
                    }
                    !runSync -> {
                        player.syncVisibleTabTrackingAfterW2()
                        if (upSpaceEntry && !isInBackground) {
                            player.syncPlayerTrackingFromPager("w2-space", full = false)
                        }
                        Log.x(
                            "StoryAutoNext: w2 skip F2 sync host=$playerHost " +
                                "tag=${player.storyPagerTag()} engine=$pendingIndex " +
                                "bgHost=$backgroundEngineHost active=${player === activeStoryPlayer}",
                        )
                        player.completeW2ResumeHandoffCycle()
                    }
                    else -> {
                        cancelPostResumeCatchUp()
                        runCatching {
                            player.syncPagerToEngineIndex(pendingIndex, pendingId)
                        }.onFailure {
                            Log.e(it)
                            player.completeW2ResumeHandoffCycle()
                        }
                    }
                }
                hookRuntimeTargets(player)
                if (upSpaceEntry && !managesBg && !isInBackground) {
                    captureActiveStoryPlayerIfDominant(player)
                }
            }
            Log.x("StoryAutoNext: hooked ${playerClass.name}.w2 for post-official F2 sync")
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
            Log.x("StoryAutoNext: write f208480t=$posMs")
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
     * When the user swipes back in foreground (pager index drops) but a prior background session
     * left liveEngineIndex ahead, the next background auto-next must start from the pager — not
     * continue from stale engine index (see log d1=0 advanced to index=5).
     */
    /** Remember story index when pausing in background without auto-next (for w2 F2 catch-up). */
    private fun Any.preserveEngineIndexOnBackgroundPause() {
        val d1 = invokeIntGetter("D1", "getIndex") ?: return
        val preserved = maxOf(
            d1,
            liveEngineIndex.coerceAtLeast(0),
            trackedIndex.coerceAtLeast(0),
        )
        if (preserved == liveEngineIndex && preserved == trackedIndex) return
        Log.x(
            "StoryAutoNext: preserve engine on bg pause d1=$d1 -> $preserved " +
                "(live=$liveEngineIndex tracked=$trackedIndex)",
        )
        trackedIndex = preserved
        liveEngineIndex = preserved
        storyIdentityAt(preserved)?.let {
            playingStoryKey = it
            activeStoryKey = it
        }
    }

    private fun Any.realignEngineTrackingToPager(source: String) {
        val d1 = invokeIntGetter("D1", "getIndex") ?: return
        val host = playerHostName()
        val upSpaceOwnFeed = isUpSpacePlayerWithoutBgSession(host)
        if (backgroundEngineMoved && liveEngineIndex > d1) {
            if (!upSpaceOwnFeed) return
        }
        // w2 adapter flush / u2 pause can report D1=0 while engine is still at the paused index.
        if (liveEngineIndex > d1 && source in setOf("addVideo", "resolve", "u2") && !upSpaceOwnFeed) {
            return
        }
        if (liveEngineIndex == d1 && trackedIndex == d1) return
        Log.x(
            "StoryAutoNext: realign engine to pager d1=$d1 " +
                "(live=$liveEngineIndex tracked=$trackedIndex source=$source)",
        )
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
        realignEngineTrackingToPager("resolve")
        val d1 = invokeIntGetter("D1", "getIndex") ?: 0
        if (backgroundEngineMoved && liveEngineIndex > d1) {
            return maxOf(
                trackedIndex.takeIf { it >= 0 } ?: d1,
                liveEngineIndex,
                d1,
            )
        }
        return d1
    }

    /**
     * Map engine index to pager index. Prefer the canonical engine slot when identity matches
     * there; otherwise pick the duplicate closest to preferredIndex (not global firstAny tail).
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
                    Log.x(
                        "StoryAutoNext: resolve by id $targetId engine=$preferredIndex -> $best count=$count",
                    )
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

    /** StoryPagerPlayer.h1 defer buffer (f208484w); clear after bg append to avoid w2 double-flush. */
    private fun Any.clearDeferredFeedQueue(): Int = runCatching {
        val field = javaClass.declaredFields.firstOrNull { f ->
            java.util.ArrayList::class.java.isAssignableFrom(f.type)
        } ?: return@runCatching 0
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(this) as? ArrayList<Any?> ?: return@runCatching 0
        if (list.isEmpty()) return@runCatching 0
        val first = list.firstOrNull() ?: return@runCatching 0
        if (!first.javaClass.name.contains("StoryDetail")) return@runCatching 0
        val n = list.size
        list.clear()
        n
    }.getOrElse {
        Log.e(it)
        0
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
        Log.x("StoryAutoNext: started end polling")
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
        Log.x(
            "StoryAutoNext: playback ended key=$activeStoryKey, position=$position, duration=$duration, " +
                "core=${core.javaClass.name}#${System.identityHashCode(core)} bg=$isInBackground",
        )
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
            Log.x("StoryAutoNext: waiting official loadMore current=$current count=$count")
            return false
        }
        tailWaitKey = null
        val nextIndex = current + 1
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
            Log.x(
                "StoryAutoNext: advanced engine to index=$nextIndex " +
                    "(d1=$d1After was=$d1 id=$playingStoryKey)",
            )
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
            Log.x("StoryAutoNext: loadMore rerouted from UP-space host to main feed")
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
                Log.x(
                    "StoryAutoNext: skip loadMore index=$index count=$count (next slot exists)",
                )
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
                                    Log.x(
                                        "StoryAutoNext: cleared deferred queue after bg h1 size=$deferred",
                                    )
                                }
                                Log.x("StoryAutoNext: video feed added ${items.size} items, new count=${player.invokeIntGetter("N1")}")
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
                                val deferred = player.clearDeferredFeedQueue()
                                if (deferred > 0) {
                                    Log.x(
                                        "StoryAutoNext: cleared deferred queue after loader h1 size=$deferred",
                                    )
                                }
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
     * Advance to [index]. Always runs official F2/D2 first so D1 + adapter metadata stay aligned
     * (StoryPagerPlayer.F1 = adapter.Z0(D1)). Background still needs StoryPlayer.h2 after F2
     * because ViewPager2 won't bind/play while inactive — but naked h2 alone caused "video switched,
     * no official swipe, metadata stuck on previous" (engine ahead of D1).
     */
    private fun Any.forcePlayAtIndex(index: Int) {
        if (!isInBackground) {
            Log.x("StoryAutoNext: foreground advance via official F2 index=$index (no naked h2)")
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
