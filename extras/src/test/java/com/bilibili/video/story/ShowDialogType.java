package com.bilibili.video.story;

public enum ShowDialogType {
    NoDialog, ShowCollectionDialogOnStart, ShowIntroDialogOnStart;

    // The host's public field is stable even when its enum name differs.
    public static final ShowDialogType None = NoDialog;
}
