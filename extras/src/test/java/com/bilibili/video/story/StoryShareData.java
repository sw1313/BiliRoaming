package com.bilibili.video.story;

public final class StoryShareData {
    public final int type;
    public final StoryDetail anchor;
    private ShowDialogType dialog;

    public StoryShareData(int type, StoryDetail anchor, ShowDialogType dialog) {
        this.type = type;
        this.anchor = anchor;
        this.dialog = dialog;
    }

    public ShowDialogType getStorySpaceShowDialog() { return dialog; }
    public void setStorySpaceShowDialog(ShowDialogType value) { dialog = value; }
}
