package me.custom.biliextras.sponsorblock;

import android.content.Context;

/**
 * Menu click entry points invoked from host {@code Function0} proxies (Java {@code Runnable}).
 */
public final class SponsorBlockMenuActions {
    private SponsorBlockMenuActions() {
    }

    public static void openMainSubMenu(Context context) {
        SponsorBlockMenuHost.INSTANCE.hidePlayerSettingWidgetIfNeeded();
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> SponsorBlockSubMenu.INSTANCE.showMain(context));
    }

    public static void openSegmentList(Context context) {
        SponsorBlockSubMenu.INSTANCE.showSegmentList(context);
    }

    public static void openSegmentActions(Context context, int index) {
        SponsorBlockSubMenu.INSTANCE.showSegmentActions(context, index);
    }

    public static void toggleEnabled(Object checkedFlow) {
        SponsorBlockController.INSTANCE.setEnabled(!SponsorBlockPrefs.INSTANCE.getEnabled());
        SponsorBlockMenuUiFactory.updateBooleanFlow(checkedFlow, SponsorBlockPrefs.INSTANCE.getEnabled());
    }

    public static void showSubmitDialog(Context context) {
        SponsorBlockSubMenu.INSTANCE.dismissActive();
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> {
            try {
                SponsorBlockSubmitDialog.INSTANCE.show(context);
            } catch (Throwable t) {
                me.custom.biliextras.utils.Log.INSTANCE.w(
                        "SponsorBlock submit dialog failed: " + t.getMessage()
                );
                me.custom.biliextras.utils.Log.INSTANCE.toast("无法打开提交面板", false,
                        android.widget.Toast.LENGTH_SHORT);
            }
        });
    }

    public static void manualSkipCurrent(Context context) {
        SponsorBlockSubMenu.INSTANCE.showManualSkipList(context);
    }

    public static void manualSkipSegment(Context context, int index) {
        SponsorBlockSubMenu.INSTANCE.dismissActive();
        SponsorBlockSubMenu.INSTANCE.manualSkipSegmentAt(context, index);
    }

    public static void seekToSegment(Context context, int index) {
        SponsorBlockSubMenu.INSTANCE.dismissActive();
        SponsorBlockSubMenu.INSTANCE.seekToSegmentAt(context, index);
    }

    public static void voteSegment(Context context, int index, int voteType) {
        SponsorBlockSubMenu.INSTANCE.voteSegmentAt(context, index, voteType, null);
    }

    public static void changeSegmentCategory(Context context, int index) {
        SponsorBlockSubMenu.INSTANCE.dismissActive();
        SponsorBlockSubMenu.INSTANCE.showCategoryPicker(context, index);
    }

    public static void refetchSegments() {
        SponsorBlockController.INSTANCE.refetchCurrent(false);
    }

    public static void refetchSegmentsWithToast() {
        SponsorBlockController.INSTANCE.refetchCurrent(true);
        me.custom.biliextras.utils.Log.INSTANCE.toast("正在刷新片段…", false, android.widget.Toast.LENGTH_SHORT);
    }

    public static void showCurrentSegmentActions(Context context) {
        SponsorBlockSubMenu.INSTANCE.dismissActive();
        SponsorBlockSubMenu.INSTANCE.showCurrentSegmentActions(context);
    }
}
