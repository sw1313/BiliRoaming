package com.bilibili.video.story.space;

import com.bilibili.video.story.StoryDetail;

public final class StorySpaceFragment {
    // Actual DEX field names, rather than JADX's disambiguated display names.
    public int y;
    public StoryDetail d;
    public final j source = new j();
    public com.bilibili.video.story.helper.y related;
    public FeedView view = new FeedView();
    public FeedView getView() { return view; }

    public static final class FeedView {
        public final EmptyView empty = new EmptyView();
        public final Resources resources = new Resources();
        public Resources getResources() { return resources; }
        public EmptyView findViewById(int id) { return id == 123 ? empty : null; }
    }
    public static final class Resources {
        public int getIdentifier(String name, String type, String pkg) {
            return "emptyView".equals(name) && "id".equals(type) && "tv.danmaku.bili".equals(pkg) ? 123 : 0;
        }
    }
    public static final class EmptyView {
        public int visibility;
        public int getVisibility() { return visibility; }
        public void setVisibility(int value) { visibility = value; }
    }

    public StorySpaceFragment(int type, StoryDetail anchor) { y = type; d = anchor; }
    private void oq(StorySpaceFragment saved) { y = saved.y; d = saved.d; }
}
