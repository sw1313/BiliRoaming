package com.bilibili.video.story.player;

public final class StoryPlayer {
    public boolean G = true;
    public boolean b1;
    public Object renderRoot;
    public Object G1() { return renderRoot; }
    public final StoryVideoPlayHandler handler = new StoryVideoPlayHandler();
    public final SessionClient sessionClient = new SessionClient();
    public static final class SessionClient {
        public final su1.g service = new su1.g();
        public su1.g getService() { return service; }
    }
    public int publishCalls;
    public com.bilibili.video.story.StoryDetail j(String tag, int index) {
        java.util.List<com.bilibili.video.story.StoryDetail> rows = handler.provider.feeds.get(tag);
        return !G || rows == null || index < 0 || index >= rows.size() ? null : rows.get(index);
    }
    public void R(String tag, java.util.List<com.bilibili.video.story.StoryDetail> rows) {
        if (!G || !handler.provider.h) return;
        publishCalls++;
        handler.provider.feeds.put(tag, new java.util.ArrayList<>(rows));
    }
    public void t(String tag, boolean active) { if (active) handler.provider.active = tag; }
    public int state = 4;
    public boolean p0;
    public int pauseCalls;
    public long position;
    public int stateAfterDirector = -1;
    public long getCurrentPosition() { return position; }
    public void M2(int index) { if (stateAfterDirector >= 0) state = stateAfterDirector; }
    public com.bilibili.video.story.StoryDetail playing;
    public com.bilibili.video.story.StoryDetail l() { return playing; }
    public int getState() { return state; }
    public void pause() { state = 5; pauseCalls++; }
}
