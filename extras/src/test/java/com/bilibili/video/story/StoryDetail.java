package com.bilibili.video.story;

public final class StoryDetail {
    private final String bvid;
    private final long cid;

    public StoryDetail(String bvid, long cid) { this.bvid = bvid; this.cid = cid; }
    public String getBvid() { return bvid; }
    public long getCid() { return cid; }
}
