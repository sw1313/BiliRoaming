package com.bilibili.video.story.space;

public final class j {
    private Page page;
    private Object meta;
    public Page f() { return page; }
    public Object e() { return meta; }
    public void k(Page value) { page = value; }
    public void j(Object value) { meta = value; }

    public static final class Page {
        private final int total;
        public Page(int total) { this.total = total; }
        public int getTotal() { return total; }
    }
}
