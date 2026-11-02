package me.custom.biliextras.hook;

import com.bilibili.video.story.StoryDetail;
import com.bilibili.video.story.space.StorySpaceFragment;
import com.bilibili.video.story.space.j;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class StoryResumeRegressionTest {
    private StoryBackgroundAutoNextHook hook;
    private final StoryBackgroundAutoNextHook.Companion state = StoryBackgroundAutoNextHook.Companion;

    private Object call(String name, Object... args) throws Exception {
        for (Method method : StoryBackgroundAutoNextHook.class.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return method.invoke(hook, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Before public void reset() throws Exception {
        hook = new StoryBackgroundAutoNextHook(getClass().getClassLoader());
        call("clearSpaceSnapshots");
        call("clearPausedResumeIntent", "test-reset");
        state.setAppBackgroundResumeHandoffEligible(false);
        state.setResumeHandoffHost(null);
        state.setVisibleOuterTabIndex(1);
        state.setPendingPostW2SurfaceRebind(false);
        state.setInBackground(false);
        state.setActivityPaused(false);
        state.setResumeFeedSnapshot(null);
        state.setResumeFeedRestoreInProgress(false);
        state.setResumeFeedRestorePlayer(null);
        state.setResumeHandoffCount(-1);
        state.setResumeHandoffIndex(-1);
        state.setResumeHandoffId(null);
        state.setLiveEngineIndex(-1);
        state.setTrackedIndex(-1);
        state.setBackgroundEngineIndex(-1);
        state.setPlayingStoryKey(null);
        state.setActiveStoryKey(null);
        state.setPostResumeFeedGuardUntilMs(0L);
        state.setForegroundPlaybackRecoveryGeneration(0);
        state.setOuterTabRenderRecoveryGeneration(0);
        state.setActiveStoryPlayer(null);
        state.setMainFeedStoryPlayer(null);
        state.setUpSpaceStoryPlayer(null);
        state.getPlayerHostByInstance().clear();
    }

    @Test public void readsRealDexSpaceType() throws Exception {
        assertEquals(4, call("relatedSpaceType", new StorySpaceFragment(4, null)));
    }

    private com.bilibili.video.story.player.StoryPagerPlayer pausedPlayer(int stateCode) {
        com.bilibili.video.story.player.StoryPagerPlayer player =
            new com.bilibili.video.story.player.StoryPagerPlayer();
        player.F = new StorySpaceFragment(1, new StoryDetail("BVpaused", 27));
        player.adapter.current = player.F.d;
        player.story.state = stateCode;
        return player;
    }

    @Test public void capturesPauseBeforeLifecycleAndKeepsItForSameClip() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        call("capturePausedResumeIntent", player);
        assertEquals(true, call("shouldRetainPausedResume", player));
        assertEquals("BVpaused/27", state.getPausedResumeIdentity());
        player.story.state = 4; // A synchronous rebind tried to resume the core.
        call("pauseRetainedResumeIfReady", player.story);
        assertEquals(5, player.story.state);
        player.adapter.current = new StoryDetail("BVnext", 28);
        assertEquals(false, call("shouldRetainPausedResume", player));
    }

    @Test public void playingBeforeScreenOffDoesNotBecomeAPauseIntent() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(4);
        call("capturePausedResumeIntent", player);
        assertNull(state.getPausedResumeIdentity());
        assertEquals(false, call("shouldRetainPausedResume", player));
        assertEquals(4, player.story.state);
    }

    @Test public void preparedCallbackCanPreservePauseAndExplicitPlayCanClearIt() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        call("capturePausedResumeIntent", player);
        player.story.state = 3;
        call("setPausedPrepareFlag", player.story, true);
        assertTrue(player.story.p0);
        call("pauseRetainedResumeIfReady", player.story);
        assertEquals(5, player.story.state);
        call("clearPausedResumeIntent", "explicit-play");
        assertFalse(player.story.p0);
        assertEquals(false, call("shouldRetainPausedResume", player));
    }

    @Test public void activePagerWithEmptyListCannotReportSurfaceRestored() throws Exception {
        assertEquals(false, call("rebindOfficialSurfaceAtD1",
            new com.bilibili.video.story.player.StoryPagerPlayer(), "test-empty"));
    }

    @Test public void detachedCardIsNotEvidenceOfBoundSurface() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player =
            new com.bilibili.video.story.player.StoryPagerPlayer();
        player.adapter.current = new StoryDetail("BVcurrent", 9);
        player.adapter.i = player.adapter.current;
        assertEquals(false, call("officialAdapterPlayingMarkerBlocksX2AtD1", player));
        player.adapter.h = player.adapter.current;
        assertEquals(true, call("officialAdapterPlayingMarkerBlocksX2AtD1", player));
    }

    @Test public void pageTotalCannotLeakIntoAnotherUpOrFragment() throws Exception {
        StorySpaceFragment first = new StorySpaceFragment(1, new StoryDetail("BVfirst", 1));
        first.source.k(new j.Page(317));
        call("captureSpacePageSnapshotIfNeeded", first, "test");
        StorySpaceFragment other = new StorySpaceFragment(1, new StoryDetail("BVother", 2));
        assertEquals(false, call("restoreSpacePageSnapshotIfNeeded", other));
        assertNull(other.source.f());
        first.source.k(null);
        assertEquals(true, call("restoreSpacePageSnapshotIfNeeded", first));
        assertEquals(317, first.source.f().getTotal());
        first.d = other.d;
        first.source.k(null);
        assertEquals(false, call("restoreSpacePageSnapshotIfNeeded", first));
        assertNull(first.source.f());
    }

    @Test public void spaceAdapterInheritsPlaybackMarkerAndDetachMethod() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player =
            new com.bilibili.video.story.player.StoryPagerPlayer();
        player.adapter = new com.bilibili.video.story.space.StorySpaceVideoAdapter();
        player.adapter.current = new StoryDetail("BVspace", 10);
        player.adapter.h = player.adapter.current;
        assertEquals(true, call("officialAdapterPlayingMarkerBlocksX2AtD1", player));
        call("clearOfficialAdapterPlayingMarkerIfNeeded", player);
        assertNull(player.adapter.h);
        assertSame(player.adapter.current, player.adapter.i);
        assertEquals(false, call("officialAdapterPlayingMarkerBlocksX2AtD1", player));
    }

    @Test public void repairsRelatedZeroCountWithVoidSetter() throws Exception {
        com.bilibili.video.story.helper.y data =
            new com.bilibili.video.story.helper.y(Arrays.asList("one", "two", "three"));
        assertEquals(true, call("fixRelatedVideoMetaCount", data));
        assertEquals(3, data.b().getVideoNum());
        assertEquals(false, call("fixRelatedVideoMetaCount", data));
    }

    @Test public void rebuildsMissingNavigationContextBeforeFeedRecovery() throws Exception {
        StoryDetail anchor = new StoryDetail("BVpaused", 7);
        StorySpaceFragment original = new StorySpaceFragment(4, anchor);
        call("captureSpaceSnapshotAnchor", original);
        state.setSpaceSnapshotShareData(original);
        state.setResumeHandoffHost("com.bilibili.video.story.space.StorySpaceFragment");
        state.setAppBackgroundResumeHandoffEligible(true);
        StorySpaceFragment recreated = new StorySpaceFragment(1, null);
        call("restoreSpaceSessionContextIfNeeded", recreated);
        assertSame(anchor, recreated.d);
        assertEquals(4, recreated.y);
        assertEquals(true, call("ownsSpaceSnapshot", recreated));
    }

    @Test public void pendingSurfaceBindKeepsHandoffAndCancellationInvalidatesRetries() {
        state.setAppBackgroundResumeHandoffEligible(true);
        state.setPendingPostW2SurfaceRebind(true);
        state.finishAppBackgroundResumeSession();
        assertTrue(state.getAppBackgroundResumeHandoffEligible());
        int generation = state.getSurfaceRebindGeneration();
        state.cancelPendingSurfaceRebind("test-navigation");
        assertFalse(state.getPendingPostW2SurfaceRebind());
        assertEquals(generation + 1, state.getSurfaceRebindGeneration());
    }

    @Test public void restoresMissingTailEvenWhenCurrentVideoStillExists() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player =
            new com.bilibili.video.story.player.StoryPagerPlayer();
        player.F = new StorySpaceFragment(1, new StoryDetail("BVanchor", 1));
        java.util.List<Object> saved = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) saved.add(new StoryDetail("BV" + i, i + 1));
        for (int i = 0; i < 7; i++) player.adapter.items.add((StoryDetail) saved.get(i));
        player.index = 6;
        state.setResumeFeedSnapshot(saved);
        state.setResumeHandoffCount(16);
        state.setResumeHandoffId("unknown/7");
        state.setResumeHandoffHost(player.F.getClass().getName());
        assertEquals(true, call("restoreResumeFeedSnapshotIfNeeded", player));
        assertEquals(16, player.N1());
        assertEquals(6, player.D1());
        assertNotNull(player.V1(7));
    }

    @Test public void draggingPreviousReleasesSavedIndexAndAllowsOfficialPlayback() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player =
            new com.bilibili.video.story.player.StoryPagerPlayer();
        player.F = new StorySpaceFragment(1, new StoryDetail("BVanchor", 1));
        player.adapter.current = player.F.d;
        state.setAppBackgroundResumeHandoffEligible(true);
        state.setPendingPostW2SurfaceRebind(true);
        state.setResumeHandoffIndex(6);
        state.setResumeHandoffHost(player.F.getClass().getName());
        state.setPostResumeFeedGuardUntilMs(System.currentTimeMillis() + 12000);
        assertEquals(true, call("releaseForegroundResumeForUserDrag", player));
        assertFalse(state.getAppBackgroundResumeHandoffEligible());
        assertFalse(state.getPendingPostW2SurfaceRebind());
        assertEquals(-1, state.getResumeHandoffIndex());
        assertEquals(false, call("shouldBlockOfficialX2ForBgResume", player));
        assertEquals(true, call("alignBeforePostResumeX2IfNeeded", player, "test-user-swipe"));
    }

    @Test public void mainFeedSnapshotKeepsTheAdmittedCurrentCardWithoutReapplyingFilters() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player =
            new com.bilibili.video.story.player.StoryPagerPlayer();
        state.getPlayerHostByInstance().put(System.identityHashCode(player),
            "com.bilibili.video.story.StoryVideoFragment");
        for (int i = 0; i < 4; i++) player.adapter.items.add(new StoryDetail("BVrow" + i, i + 1));
        Object snapshot = call("captureResumeFeedSnapshot", player);
        assertEquals(4, ((java.util.List<?>) snapshot).size());
        assertSame(player.adapter.items.get(0), ((java.util.List<?>) snapshot).get(0));
    }

    @Test public void mainFeedRestoresTheExactOriginalOrderCurrentIndexAndMetadataObjects() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player =
            new com.bilibili.video.story.player.StoryPagerPlayer();
        state.getPlayerHostByInstance().put(System.identityHashCode(player),
            "com.bilibili.video.story.StoryVideoFragment");
        java.util.List<Object> original = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) original.add(new StoryDetail("BVoriginal" + i, i + 1));
        state.setResumeFeedSnapshot(original);
        state.setResumeHandoffHost("com.bilibili.video.story.StoryVideoFragment");
        state.setResumeHandoffId("unknown/3");
        state.setResumeHandoffIndex(2);
        state.setResumeHandoffCount(4);
        assertEquals(true, call("restoreResumeFeedSnapshotIfNeeded", player));
        assertEquals(original, player.adapter.items);
        assertEquals(2, player.D1());
        assertSame(original.get(2), player.V1(2));
        assertTrue(player.filterBypassObserved);
        assertFalse(state.isRestoringFeedFor(player));
        assertFalse(state.isRestoringFeedFor(new Object()));
    }

    @Test public void newPauseUsesCurrentEngineInsteadOfPreviousResumeAnchor() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        player.adapter.current = null;
        for (int i = 0; i < 4; i++) player.adapter.items.add(new StoryDetail("BVoriginal" + i, i + 1));
        player.index = 3;
        player.story.playing = player.V1(3);
        state.setResumeHandoffId("BVoriginal0/1");
        state.setResumeHandoffIndex(0);
        call("capturePausedResumeIntent", player);
        call("captureResumeHandoffIfNeeded", player, 3);
        assertEquals("BVoriginal3/4", state.getResumeHandoffId());
        assertEquals(state.getPausedResumeIdentity(), state.getResumeHandoffId());
        assertEquals(3, state.getResumeHandoffIndex());
        player.adapter.items.clear();
        assertEquals(true, call("restoreResumeFeedSnapshotIfNeeded", player));
        assertEquals(3, player.D1());
        assertEquals(true, call("shouldRetainPausedResume", player));
    }

    @Test public void newPauseAfterBackwardSwipeIgnoresStaleSessionIndices() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        player.adapter.current = null;
        for (int i = 0; i < 4; i++) player.adapter.items.add(new StoryDetail("BVoriginal" + i, i + 1));
        player.index = 1;
        player.story.playing = player.V1(1);
        state.setResumeHandoffId("BVoriginal3/4");
        state.setResumeHandoffIndex(3);
        state.setTrackedIndex(3);
        state.setLiveEngineIndex(3);
        state.setBackgroundEngineIndex(3);
        state.setPlayingStoryKey("BVoriginal3/4");
        call("captureResumeHandoffIfNeeded", player, 1);
        assertEquals("BVoriginal1/2", state.getResumeHandoffId());
        assertEquals(1, state.getResumeHandoffIndex());
    }

    @Test public void restoredAdapterRepublishesTheExactFeedToAnEmptyCoreWithoutAutoplay() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        player.adapter.current = null;
        for (int i = 0; i < 4; i++) player.adapter.items.add(new StoryDetail("BVoriginal" + i, i + 1));
        player.index = 2;
        call("capturePausedResumeIntent", player);
        assertEquals(true, call("ensureOfficialPlayableFeed", player, "test-empty-core"));
        assertEquals(player.adapter.items, player.story.handler.provider.feeds.get(player.tag));
        assertSame(player.V1(2), player.story.j(player.tag, 2));
        assertEquals(2, player.D1());
        assertEquals(5, player.getState());
        assertEquals(true, call("shouldRetainPausedResume", player));
        assertEquals(1, player.story.publishCalls);
    }

    @Test public void coreThatWasNotReadyDuringRestoreCanReceiveTheSavedFeedLater() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        player.story.G = false;
        assertEquals(false, call("ensureOfficialPlayableFeed", player, "test-before-ready"));
        assertEquals(0, player.story.publishCalls);
        player.story.G = true;
        assertEquals(true, call("ensureOfficialPlayableFeed", player, "test-after-ready"));
        assertSame(player.adapter.current, player.story.j(player.tag, 0));
    }

    @Test public void healthyCoreFeedIsNotResetAndAReleasedProviderCannotReportRecovery() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(4);
        player.story.R(player.tag, Arrays.asList(player.adapter.current));
        player.story.t(player.tag, true);
        assertEquals(true, call("ensureOfficialPlayableFeed", player, "test-healthy"));
        assertEquals(1, player.story.publishCalls);
        player.story.handler.provider.h = false;
        assertEquals(false, call("ensureOfficialPlayableFeed", player, "test-released"));
        assertEquals(1, player.story.publishCalls);
    }

    @Test public void returningFromSpaceActivatesTheOriginalMainBucketWithoutChangingEitherList() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(4);
        player.tag = "StoryVideoFragment";
        state.setVisibleOuterTabIndex(0);
        state.getPlayerHostByInstance().put(System.identityHashCode(player),
            "com.bilibili.video.story.StoryVideoFragment");
        player.F = null;
        StoryDetail space = new StoryDetail("BVspace", 77);
        player.story.R("StorySpaceFragment", Arrays.asList(space));
        player.story.R(player.tag, Arrays.asList(player.adapter.current));
        player.story.t("StorySpaceFragment", true);
        assertEquals(true, call("ensureOfficialPlayableFeed", player, "test-main-return"));
        assertEquals(player.tag, player.story.handler.provider.active);
        assertSame(space, player.story.j("StorySpaceFragment", 0));
        assertSame(player.adapter.current, player.story.j(player.tag, 0));
        assertEquals(2, player.story.publishCalls);
    }

    @Test public void unpreparedCoreReloadsOnlyTheCurrentResourceAndRetainsSavedPausePosition() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        player.story.position = 23000;
        call("capturePausedResumeIntent", player);
        assertEquals(23000L, state.getPausedResumePositionMs());
        StoryDetail original = player.adapter.current;
        player.story.state = 0; // Recreated director has rows but no native media source.
        assertEquals(true, call("reloadUnpreparedPlayableItem", player, 0, 23000L));
        assertEquals(1, player.story.handler.provider.reloadCalls);
        assertEquals(Long.valueOf(23000), player.story.handler.provider.reloadPosition);
        assertEquals(23000, player.t);
        assertSame(original, player.adapter.current);
        assertEquals(0, player.D1());
        assertEquals(true, call("shouldRetainPausedResume", player));
    }

    @Test public void healthyPausedAndPlayingCoresAreNotReloaded() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        assertEquals(false, call("reloadUnpreparedPlayableItem", player, 0, 23000L));
        player.story.state = 4;
        assertEquals(false, call("reloadUnpreparedPlayableItem", player, 0, 23000L));
        player.story.state = 2; // An ordinary in-flight resolve must finish without a forced reload.
        assertEquals(false, call("reloadUnpreparedPlayableItem", player, 0, 23000L));
        assertEquals(0, player.story.handler.provider.reloadCalls);
    }

    @Test public void officialDirectorRecoveryDoesNotAlsoForceAnotherResolve() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        player.story.position = 23000;
        call("capturePausedResumeIntent", player);
        player.story.state = 0;
        player.story.stateAfterDirector = 5;
        assertEquals(true, call("reloadUnpreparedPlayableItem", player, 0, 23000L));
        assertEquals(5, player.story.state);
        assertEquals(23000, player.t);
        assertEquals(0, player.story.handler.provider.reloadCalls);
        assertEquals(true, call("shouldRetainPausedResume", player));
    }

    @Test public void normalRebindAlsoSeedsTheSavedPausePositionAndCannotSeedAnotherClip() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        player.story.position = 7800;
        call("capturePausedResumeIntent", player);
        assertEquals(true, call("applyPausedResumePositionIfNeeded", player));
        assertEquals(7800, player.t);
        player.t = 0;
        player.adapter.current = new StoryDetail("BVdifferent", 999);
        assertEquals(false, call("applyPausedResumePositionIfNeeded", player));
        assertEquals(0, player.t);
    }

    @Test public void nativeSeekGuardRetainsPausePositionWhenTheOldCoreIsGone() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        player.story.position = 126212;
        call("capturePausedResumeIntent", player);
        player.story.state = 0;
        player.story.position = 0;
        player.t = 0;
        call("guardNativeStartPositionForPagerItem", player);
        assertEquals(126212, player.t);
    }

    @Test public void restoredFeedHidesOnlyItsOwnStaleEmptyPlaceholder() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(0);
        assertEquals(true, call("hideStaleEmptyFeedPlaceholder", player));
        assertEquals(8, player.F.view.empty.visibility);
        assertEquals(false, call("hideStaleEmptyFeedPlaceholder", player));
        player.F.view.empty.visibility = 0;
        player.adapter.current = null;
        assertEquals(false, call("hideStaleEmptyFeedPlaceholder", player));
        assertEquals(0, player.F.view.empty.visibility);
    }

    @Test public void hiddenOrBackgroundFeedCannotChangeTheEmptyPlaceholder() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(0);
        state.setVisibleOuterTabIndex(0);
        assertEquals(false, call("hideStaleEmptyFeedPlaceholder", player));
        state.setVisibleOuterTabIndex(1);
        state.setActivityPaused(true);
        assertEquals(false, call("hideStaleEmptyFeedPlaceholder", player));
        state.setActivityPaused(false);
        state.setInBackground(true);
        assertEquals(false, call("hideStaleEmptyFeedPlaceholder", player));
        assertEquals(0, player.F.view.empty.visibility);
    }

    @Test public void latePagingClearAndRefillCannotTruncateTheRestoredOriginalChain() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = armedPlayer();
        java.util.List<Object> original = Arrays.asList(new StoryDetail("BVfirst", 1),
            new StoryDetail("BVsecond", 2), new StoryDetail("BVanchor", 99), new StoryDetail("BVfourth", 4));
        state.setResumeFeedSnapshot(original);
        state.setResumeHandoffIndex(2);
        player.adapter.items.add(new StoryDetail("BVnew", 500));
        assertEquals(true, call("restoreGuardedResumeFeedBeforeX2", player));
        assertEquals(original, player.adapter.items);
        assertEquals(2, player.D1());
        state.setPostResumeFeedGuardUntilMs(0L);
        player.adapter.items.clear();
        player.adapter.items.add(new StoryDetail("BVuserSelected", 501));
        assertEquals(false, call("restoreGuardedResumeFeedBeforeX2", player));
        assertEquals(1, player.N1());
    }

    @Test public void recoveryCannotReloadAnotherPageOrABackgroundOrHiddenPlayer() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(0);
        assertEquals(false, call("reloadUnpreparedPlayableItem", player, 1, 23000L));
        state.setActivityPaused(true);
        assertEquals(false, call("reloadUnpreparedPlayableItem", player, 0, 23000L));
        state.setActivityPaused(false);
        state.setVisibleOuterTabIndex(0);
        assertEquals(false, call("reloadUnpreparedPlayableItem", player, 0, 23000L));
        assertEquals(0, player.story.handler.provider.reloadCalls);
    }

    @Test public void cardMarkerCannotHideARenderViewStillMountedInThePreviousActivityWindow() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(4);
        player.adapter.h = player.adapter.current;
        player.adapter.holder = new com.bilibili.video.story.StoryVideoAdapter.Holder();
        Object currentWindow = new com.bilibili.video.story.StoryVideoAdapter.RenderNode(null);
        player.adapter.holder.controller.parent = currentWindow;
        player.story.renderRoot = new com.bilibili.video.story.StoryVideoAdapter.RenderNode(
            new com.bilibili.video.story.StoryVideoAdapter.RenderNode(null));
        assertEquals(true, call("officialAdapterPlayingMarkerBlocksX2AtD1", player));
        assertEquals(false, call("isRenderAttachedToCurrentHolder", player));
        ((com.bilibili.video.story.StoryVideoAdapter.RenderNode) player.story.renderRoot).parent = currentWindow;
        assertEquals(true, call("isRenderAttachedToCurrentHolder", player));
    }

    @Test public void missedCoverNotificationRequiresARealFirstFrameForTheCurrentNativeItem() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(4);
        player.adapter.holder = new com.bilibili.video.story.StoryVideoAdapter.Holder();
        player.adapter.h = player.adapter.current;
        player.story.renderRoot = new com.bilibili.video.story.StoryVideoAdapter.RenderNode(player.adapter.holder.controller);
        assertEquals(false, call("restoreCurrentHolderRenderNotification", player));
        player.story.b1 = true;
        assertEquals(false, call("restoreCurrentHolderRenderNotification", player));
        player.story.handler.hasCurrentMedia = true;
        assertEquals(true, call("restoreCurrentHolderRenderNotification", player));
        assertEquals(1, player.adapter.holder.controller.renderCalls);
        assertTrue(player.adapter.coverHidden);
        player.adapter.coverHidden = true;
        assertEquals(false, call("restoreCurrentHolderRenderNotification", player));
        assertEquals(1, player.adapter.holder.controller.renderCalls);
    }

    @Test public void wrongHolderOrHiddenTabCannotReceiveTheRenderNotification() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(4);
        player.adapter.holder = new com.bilibili.video.story.StoryVideoAdapter.Holder();
        player.story.b1 = true;
        player.story.handler.hasCurrentMedia = true;
        player.story.renderRoot = new com.bilibili.video.story.StoryVideoAdapter.RenderNode(null);
        assertEquals(false, call("restoreCurrentHolderRenderNotification", player));
        ((com.bilibili.video.story.StoryVideoAdapter.RenderNode) player.story.renderRoot).parent = player.adapter.holder.controller;
        state.setVisibleOuterTabIndex(0);
        assertEquals(false, call("restoreCurrentHolderRenderNotification", player));
        assertEquals(0, player.adapter.holder.controller.renderCalls);
    }

    @Test public void aRealFrameStillRequiresTheCurrentCardToBeStartedBeforeHidingItsCover() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(4);
        player.adapter.holder = new com.bilibili.video.story.StoryVideoAdapter.Holder();
        player.story.renderRoot = new com.bilibili.video.story.StoryVideoAdapter.RenderNode(player.adapter.holder.controller);
        player.story.b1 = true;
        player.story.handler.hasCurrentMedia = true;
        assertEquals(false, call("restoreCurrentHolderRenderNotification", player));
        assertFalse(player.adapter.coverHidden);
        player.adapter.h = player.adapter.current;
        assertEquals(true, call("restoreCurrentHolderRenderNotification", player));
        assertTrue(player.adapter.coverHidden);
    }

    private com.bilibili.video.story.player.StoryPagerPlayer armedPlayer() {
        com.bilibili.video.story.player.StoryPagerPlayer player = pausedPlayer(5);
        player.adapter.current = null;
        state.setActiveStoryPlayer(player);
        state.setResumeHandoffHost(player.F.getClass().getName());
        state.setResumeHandoffId("unknown/99");
        state.setResumeHandoffIndex(0);
        state.setResumeHandoffCount(4);
        state.setAppBackgroundResumeHandoffEligible(true);
        state.setPostResumeFeedGuardUntilMs(System.currentTimeMillis() + 12000);
        state.setResumeFeedSnapshot(new java.util.ArrayList<Object>(Arrays.asList(
            new StoryDetail("BVother", 1), new StoryDetail("BVnext", 2))));
        return player;
    }

    @Test public void emptyAdapterAcceptsOriginalRefillAndCanAdvanceToTheOriginalNextItem() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = armedPlayer();
        java.util.List<StoryDetail> incoming = Arrays.asList(
            new StoryDetail("BVanchor", 99), new StoryDetail("BVnext", 100),
            new StoryDetail("BVthird", 101), new StoryDetail("BVfourth", 102));
        state.setResumeFeedSnapshot(new java.util.ArrayList<Object>(incoming));
        player.I = false;
        assertEquals(false, call("shouldBlockDeferredW2Replace", player, incoming));
        assertEquals(false, call("shouldBlockPostResumeW2Replace", player, incoming));
        assertEquals(false, call("shouldBlockAdapterFullReplace", player.adapter, incoming, 0));
        player.W2(incoming, null, 0);
        assertEquals("unknown/99", state.getResumeHandoffId());
        assertEquals(incoming, player.adapter.items);
        player.F2(1, false);
        assertSame(incoming.get(1), player.V1(player.D1()));
    }

    @Test public void emptyAdapterRejectsNewRecommendationsAndReorderedOriginalRows() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = armedPlayer();
        java.util.List<Object> original = Arrays.asList(new StoryDetail("BVanchor", 99),
            new StoryDetail("BVnext", 100), new StoryDetail("BVthird", 101), new StoryDetail("BVfourth", 102));
        state.setResumeFeedSnapshot(original);
        player.I = false;
        java.util.List<Object> refreshed = Arrays.asList(new StoryDetail("BVfresh", 1),
            new StoryDetail("BVfresh2", 2), new StoryDetail("BVfresh3", 3), new StoryDetail("BVfresh4", 4));
        assertEquals(true, call("shouldBlockDeferredW2Replace", player, refreshed));
        assertEquals(true, call("shouldBlockPostResumeW2Replace", player, refreshed));
        assertEquals(true, call("shouldBlockAdapterFullReplace", player.adapter, refreshed, 0));
        java.util.List<Object> reordered = new java.util.ArrayList<>(original);
        java.util.Collections.swap(reordered, 1, 2);
        assertEquals(true, call("shouldBlockAdapterFullReplace", player.adapter, reordered, 0));
        assertEquals(original, state.getResumeFeedSnapshot());
    }

    @Test public void emptyAdapterKeepsTheOfficialPendingBatchForResume() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = armedPlayer();
        player.w = new java.util.ArrayList<>(Arrays.asList(new StoryDetail("BVanchor", 99)));
        player.x = true;
        call("guardOfficialW2FeedFlushBeforeResume", player);
        assertNotNull(player.w);
        assertTrue(player.x);
    }

    @Test public void validExistingFeedStillRejectsDestructiveReplacement() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = armedPlayer();
        StoryDetail anchor = new StoryDetail("BVanchor", 99);
        player.adapter.items.add(anchor);
        player.adapter.items.add(new StoryDetail("BVnext", 100));
        java.util.List<StoryDetail> incoming = Arrays.asList(new StoryDetail("BVnew", 7));
        player.I = false;
        assertEquals(true, call("shouldBlockDeferredW2Replace", player, incoming));
        assertEquals(true, call("shouldBlockPostResumeW2Replace", player, incoming));
        assertEquals(true, call("shouldBlockAdapterFullReplace", player.adapter, incoming, 0));
    }

    @Test public void incompleteSnapshotMustNotSubstituteAnotherVideoOrChangePauseIntent() throws Exception {
        com.bilibili.video.story.player.StoryPagerPlayer player = armedPlayer();
        state.setPausedResumeIdentity("unknown/99");
        state.setPausedResumeHost(player.F.getClass().getName());
        assertEquals(false, call("restoreResumeFeedSnapshotIfNeeded", player));
        assertEquals(0, player.N1());
        assertEquals("unknown/99", state.getResumeHandoffId());
        assertEquals("unknown/99", state.getPausedResumeIdentity());
    }
}
