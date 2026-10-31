package com.bilibili.video.story.player;

import com.bilibili.video.story.StoryDetail;
import com.bilibili.video.story.StoryVideoAdapter;

public final class StoryPagerPlayer {
    public int Q = 3;
    public boolean I = true;
    public int index;
    public com.bilibili.video.story.space.StorySpaceFragment F;
    public String tag = "StorySpaceFragment";
    public final StoryPlayer story = new StoryPlayer();
    public StoryVideoAdapter adapter = new StoryVideoAdapter();
    public int D1() { return index; }
    public int getState() { return story.getState(); }
    public int N1() { return adapter.items.isEmpty() ? adapter.current == null ? 0 : 1 : adapter.items.size(); }
    public StoryDetail V1(int index) { return adapter.Z0(index); }
    public void W2(java.util.List<StoryDetail> items, java.util.List<?> ignored, int index) {
        adapter.items.clear();
        adapter.items.addAll(items);
        this.index = index;
    }
    public void h1(java.util.List<StoryDetail> items) { adapter.items.addAll(items); }
}
