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
        state.setResumeHandoffCount(-1);
        state.setResumeHandoffIndex(-1);
        state.setResumeHandoffId(null);
        state.setPostResumeFeedGuardUntilMs(0L);
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
}
