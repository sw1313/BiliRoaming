package com.bilibili.video.story;

public class StoryVideoAdapter {
    public boolean coverHidden;
    public Holder holder;
    public Holder Y0(int index) { return holder; }
    public boolean d1(int index) { return coverHidden; }
    public void K1() { if (holder != null) { coverHidden = true; holder.controller.onRender(); } }
    public static final class Holder {
        public final RenderNode controller = new RenderNode(null);
        public int dataChanges;
        public RenderNode x1() { return controller; }
        public void N1(StoryDetail data) { controller.data = data; dataChanges++; }
    }
    public static final class RenderNode {
        public Object parent;
        public int renderCalls;
        public boolean attached = true;
        public StoryDetail data;
        public StoryDetail getMData() { return data; }
        public RenderNode(Object parent) { this.parent = parent; }
        public Object getParent() { return parent; }
        public boolean isAttachedToWindow() { return attached; }
        public Object getRootView() {
            RenderNode root = this;
            for (int i = 0; i < 32 && root.parent instanceof RenderNode; i++) root = (RenderNode) root.parent;
            return root;
        }
        public void onRender() { renderCalls++; }
    }
    public final java.util.List<StoryDetail> items = new java.util.ArrayList<>();
    public StoryDetail h;
    public StoryDetail i;
    public StoryDetail current;
    public int getItemCount() { return items.isEmpty() ? current == null ? 0 : 1 : items.size(); }
    public void L1(StoryDetail next, int mode) { i = h; h = null; }
    public StoryDetail Z0(int index) {
        return items.isEmpty() ? current : index >= 0 && index < items.size() ? items.get(index) : null;
    }
}
