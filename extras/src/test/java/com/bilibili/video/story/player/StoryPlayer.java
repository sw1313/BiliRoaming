package com.bilibili.video.story.player;

public final class StoryPlayer {
    public int state = 4;
    public boolean p0;
    public int pauseCalls;
    public int getState() { return state; }
    public void pause() { state = 5; pauseCalls++; }
}
