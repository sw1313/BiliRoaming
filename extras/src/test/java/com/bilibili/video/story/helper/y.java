package com.bilibili.video.story.helper;

import java.util.List;

public final class y {
    private final List<?> items;
    private final Meta meta = new Meta();
    public y(List<?> items) { this.items = items; }
    public List<?> d() { return items; }
    public Meta b() { return meta; }
    public long a() { return 1L; }

    public static final class Meta {
        private int videoNum;
        public int getVideoNum() { return videoNum; }
        public void setVideoNum(int value) { videoNum = value; }
    }
}
