package com.bilibili.video.story.player;

import com.bilibili.video.story.StoryDetail;
import com.bilibili.video.story.StoryVideoAdapter;

public final class StoryPagerPlayer {
    public int Q = 3;
    public int R;
    public int s = -1;
    public boolean I = true;
    public int index;
    public int t;
    public java.util.ArrayList<StoryDetail> w;
    public boolean x;
    public boolean filterBypassObserved;
    public com.bilibili.video.story.space.StorySpaceFragment F;
    public String tag = "StorySpaceFragment";
    public StoryPlayer story = new StoryPlayer();
    public StoryVideoAdapter adapter = new StoryVideoAdapter();
    public final f metadata = new f();
    public int playCalls;
    private void x2(boolean smooth) { playCalls++; story.playing = V1(index); adapter.h = story.playing; }
    public int D1() { return index; }
    public void F2(int target, boolean smooth) { if (target >= 0 && target < N1()) index = target; }
    public int getState() { return story.getState(); }
    public int N1() { return adapter.items.isEmpty() ? adapter.current == null ? 0 : 1 : adapter.items.size(); }
    public StoryDetail V1(int index) { return adapter.Z0(index); }
    public StoryDetail F1() { return V1(index); }
    private java.util.List<StoryDetail> l3(java.util.List<StoryDetail> rows) { return new java.util.ArrayList<>(rows); }
    public void W2(java.util.List<StoryDetail> items, java.util.List<?> ignored, int index) {
        filterBypassObserved = me.custom.biliextras.hook.StoryBackgroundAutoNextHook.Companion.isRestoringFeedFor(this);
        adapter.items.clear();
        adapter.items.addAll(items);
        this.index = index;
    }
    public void h1(java.util.List<StoryDetail> items) { adapter.items.addAll(items); }
}
