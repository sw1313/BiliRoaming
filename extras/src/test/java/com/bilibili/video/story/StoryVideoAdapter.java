package com.bilibili.video.story;

public class StoryVideoAdapter {
    public final java.util.List<StoryDetail> items = new java.util.ArrayList<>();
    public StoryDetail h;
    public StoryDetail i;
    public StoryDetail current;
    public void L1(StoryDetail next, int mode) { i = h; h = null; }
    public StoryDetail Z0(int index) {
        return items.isEmpty() ? current : index >= 0 && index < items.size() ? items.get(index) : null;
    }
}
