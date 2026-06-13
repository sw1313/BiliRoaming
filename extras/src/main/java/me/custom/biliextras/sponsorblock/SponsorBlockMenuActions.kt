package me.custom.biliextras.sponsorblock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import me.custom.biliextras.utils.Log

object SponsorBlockMenuActions {

    @JvmStatic
    fun openMainSubMenu(context: Context) {
        SponsorBlockMenuHost.hidePlayerSettingWidgetIfNeeded()
        Handler(Looper.getMainLooper()).post { SponsorBlockSubMenu.showMain(context) }
    }

    @JvmStatic
    fun openSegmentList(context: Context) {
        SponsorBlockSubMenu.showSegmentList(context)
    }

    @JvmStatic
    fun openSegmentActions(context: Context, index: Int) {
        SponsorBlockSubMenu.showSegmentActions(context, index)
    }

    @JvmStatic
    fun toggleEnabled(checkedFlow: Any?) {
        SponsorBlockController.setEnabled(!SponsorBlockPrefs.enabled)
        SponsorBlockMenuUiFactory.updateBooleanFlow(checkedFlow, SponsorBlockPrefs.enabled)
    }

    @JvmStatic
    fun toggleShowToast(checkedFlow: Any?) {
        SponsorBlockPrefs.setShowToast(!SponsorBlockPrefs.showToast)
        SponsorBlockMenuUiFactory.updateBooleanFlow(checkedFlow, SponsorBlockPrefs.showToast)
    }

    @JvmStatic
    fun toggleShowProgress(checkedFlow: Any?) {
        SponsorBlockPrefs.setShowProgress(!SponsorBlockPrefs.showProgress)
        SponsorBlockMenuUiFactory.updateBooleanFlow(checkedFlow, SponsorBlockPrefs.showProgress)
    }

    @JvmStatic
    fun toggleTrackStats(checkedFlow: Any?) {
        SponsorBlockPrefs.setTrackStats(!SponsorBlockPrefs.trackStats)
        SponsorBlockMenuUiFactory.updateBooleanFlow(checkedFlow, SponsorBlockPrefs.trackStats)
    }

    @JvmStatic
    fun editBlockLimit(context: Context) {
        SponsorBlockSubMenu.dismissActive()
        SponsorBlockSettingsDialogs.showBlockLimit(context) {
            SponsorBlockSubMenu.showMain(context)
        }
    }

    @JvmStatic
    fun editUserId(context: Context) {
        SponsorBlockSubMenu.dismissActive()
        SponsorBlockSettingsDialogs.showUserId(context) {
            SponsorBlockSubMenu.showMain(context)
        }
    }

    @JvmStatic
    fun editServer(context: Context) {
        SponsorBlockSubMenu.dismissActive()
        SponsorBlockSettingsDialogs.showServer(context) {
            SponsorBlockSubMenu.showMain(context)
        }
    }

    @JvmStatic
    fun checkService(context: Context) {
        SponsorBlockSettingsDialogs.checkService {
            Handler(Looper.getMainLooper()).post { SponsorBlockSubMenu.showMain(context) }
        }
    }

    @JvmStatic
    fun openCategory(context: Context, categoryIndex: Int) {
        SponsorBlockSubMenu.dismissActive()
        val index = categoryIndex.coerceIn(0, SponsorBlockCategory.all.lastIndex)
        val category = SponsorBlockCategory.all[index]
        SponsorBlockSettingsDialogs.showCategory(context, category) {
            SponsorBlockSubMenu.showMain(context)
        }
    }

    @JvmStatic
    fun showSubmitDialog(context: Context) {
        SponsorBlockSubMenu.dismissActive()
        Handler(Looper.getMainLooper()).post {
            runCatching {
                SponsorBlockSubmitDialog.show(context)
            }.onFailure {
                Log.w("SponsorBlock submit dialog failed: ${it.message}")
                Log.toast("无法打开提交面板", false, Toast.LENGTH_SHORT)
            }
        }
    }

    @JvmStatic
    fun manualSkipCurrent(context: Context) {
        SponsorBlockSubMenu.showManualSkipList(context)
    }

    @JvmStatic
    fun manualSkipSegment(context: Context, index: Int) {
        SponsorBlockSubMenu.dismissActive()
        SponsorBlockSubMenu.manualSkipSegmentAt(context, index)
    }

    @JvmStatic
    fun seekToSegment(context: Context, index: Int) {
        SponsorBlockSubMenu.dismissActive()
        SponsorBlockSubMenu.seekToSegmentAt(context, index)
    }

    @JvmStatic
    fun voteSegment(context: Context, index: Int, voteType: Int) {
        SponsorBlockSubMenu.voteSegmentAt(context, index, voteType, null)
    }

    @JvmStatic
    fun changeSegmentCategory(context: Context, index: Int) {
        SponsorBlockSubMenu.dismissActive()
        SponsorBlockSubMenu.showCategoryPicker(context, index)
    }

    @JvmStatic
    fun refetchSegments() {
        SponsorBlockController.refetchCurrent(false)
    }

    @JvmStatic
    fun refetchSegmentsWithToast() {
        SponsorBlockController.refetchCurrent(true)
        Log.toast("正在刷新片段…", false, Toast.LENGTH_SHORT)
    }

    @JvmStatic
    fun showCurrentSegmentActions(context: Context) {
        SponsorBlockSubMenu.dismissActive()
        SponsorBlockSubMenu.showCurrentSegmentActions(context)
    }
}
