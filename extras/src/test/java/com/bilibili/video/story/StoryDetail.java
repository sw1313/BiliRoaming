package com.bilibili.video.story;

public final class StoryDetail {
    private final String bvid;
    private final long cid;
    private final long aid;

    public StoryDetail(String bvid, long cid) { this(bvid, cid, cid); }
    public StoryDetail(String bvid, long cid, long aid) { this.bvid = bvid; this.cid = cid; this.aid = aid; }
    public String getBvid() { return bvid; }
    public long getCid() { return cid; }
    public long getAid() { return aid; }
    public boolean isPlayableVideo() { return true; }
}
