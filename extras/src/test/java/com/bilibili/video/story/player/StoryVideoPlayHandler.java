package com.bilibili.video.story.player;

import com.bilibili.video.story.StoryDetail;
import java.util.*;

public final class StoryVideoPlayHandler {
    public boolean hasCurrentMedia;
    public boolean G() { return hasCurrentMedia; }
    public final Provider provider = new Provider();
    public Provider y() { return provider; }
    public static final class Provider {
        public boolean h = true;
        public String active;
        public int reloadCalls;
        public Long reloadPosition;
        public void T(Integer quality, Long position, Object callback) { reloadCalls++; reloadPosition = position; }
        public final Map<String, List<StoryDetail>> feeds = new HashMap<>();
        public int z(String tag) { return feeds.getOrDefault(tag, Collections.emptyList()).size(); }
        public String u() { return active; }
    }
}
