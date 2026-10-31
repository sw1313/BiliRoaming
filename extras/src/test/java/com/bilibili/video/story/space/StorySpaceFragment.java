package com.bilibili.video.story.space;

import com.bilibili.video.story.StoryDetail;

public final class StorySpaceFragment {
    // Actual DEX field names, rather than JADX's disambiguated display names.
    public int y;
    public StoryDetail d;
    public final j source = new j();
    public com.bilibili.video.story.helper.y related;

    public StorySpaceFragment(int type, StoryDetail anchor) { y = type; d = anchor; }
    private void oq(StorySpaceFragment saved) { y = saved.y; d = saved.d; }
}
