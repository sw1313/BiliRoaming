package me.custom.biliextras.sponsorblock



import android.app.AlertDialog

import android.app.Activity

import android.content.Context

import android.content.ContextWrapper

import android.os.Handler
import android.os.Looper

import me.custom.biliextras.utils.Log
import java.util.ArrayList

object SponsorBlockSubMenu {

    private var segmentSnapshot: List<SponsorBlockState.SegmentView> = emptyList()



    fun showMain(context: Context) {

        val activity = findActivity(context) ?: run {

            Log.toast("无法打开菜单")

            return

        }

        runCatching {

            buildAndShowMain(activity, context)

        }.onFailure {

            Log.w("SponsorBlock submenu failed: ${it.message}")

            Log.toast("无法打开空降菜单")

        }

    }



    private fun buildAndShowMain(activity: Activity, context: Context) {
        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)
        val rows = ArrayList<Any>()
        val total = 5 + SponsorBlockSettingsSubMenu.rowCount()
        var i = 0
        fun nextType() = factory.videoSettingTypeForIndex(i++, total)

        val segmentCount = SponsorBlockController.segments.size

        rows.add(
            factory.createInfoRow(
                "片段信息",
                SponsorBlockMenuUiFactory.submenuInfoText(),
                "playdata-square-line@500",
                nextType(),
            ),
        )
        rows.add(factory.createSwitchRow("空降助手", "skip-beginning-end-line@500", nextType()))
        rows.add(
            factory.createActionRow(
                "提交片段",
                "pen-write-square-line@500",
                "标记并提交跳过段",
                false,
                nextType(),
                SponsorBlockMenuActionHandler.ACTION_SHOW_SUBMIT,
                context,
            ),
        )
        rows.add(
            factory.createActionRow(
                "手动跳过",
                "arrow-play-next-line@500",
                if (segmentCount > 0) {
                    "共 $segmentCount 个片段 · 点击选择并跳到末尾"
                } else {
                    "当前无片段"
                },
                segmentCount > 0,
                nextType(),
                SponsorBlockMenuActionHandler.ACTION_MANUAL_SKIP_CURRENT,
                context,
            ),
        )
        rows.add(
            factory.createActionRow(
                "刷新片段",
                "arrow-refresh-line@500",
                "重新拉取当前视频片段",
                false,
                nextType(),
                SponsorBlockMenuActionHandler.ACTION_REFETCH_WITH_TOAST,
                context,
            ),
        )
        rows.add(factory.createSpacer(16))
        SponsorBlockSettingsSubMenu.appendRows(rows, factory, context, ::nextType)
        rows.add(factory.createSpacer(16))
        showDialog(activity, rows)
    }



    fun showManualSkipList(context: Context) {

        val activity = findActivity(context) ?: return

        segmentSnapshot = SponsorBlockController.segments

        if (segmentSnapshot.isEmpty()) {

            Log.toast("当前无片段")

            return

        }

        dismissActive()

        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)

        val rows = ArrayList<Any>()

        val total = segmentSnapshot.size + 1

        segmentSnapshot.forEachIndexed { index, segment ->

            rows.add(

                factory.createActionRow(

                    segmentLabel(segment, index),

                    "arrow-play-next-line@500",

                    segmentSummary(segment),

                    false,

                    factory.videoSettingTypeForIndex(index, total),

                    SponsorBlockMenuActionHandler.ACTION_MANUAL_SKIP_SEGMENT,

                    context,

                    segmentIndex = index,

                ),

            )

        }

        rows.add(factory.createSpacer(16))

        showDialog(activity, rows)

    }



    fun showSegmentList(context: Context) {

        val activity = findActivity(context) ?: return

        segmentSnapshot = SponsorBlockController.segments

        if (segmentSnapshot.isEmpty()) {

            Log.toast("当前无片段")

            return

        }

        dismissActive()

        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)

        val rows = ArrayList<Any>()

        val total = segmentSnapshot.size + 1

        segmentSnapshot.forEachIndexed { index, segment ->

            val voteHint = SponsorBlockState.userVoteLabel(segment.uuid)?.let { " · $it" } ?: ""

            rows.add(

                factory.createActionRow(

                    segmentLabel(segment, index),

                    "time-line@500",

                    segmentSummary(segment) + voteHint,

                    true,

                    factory.videoSettingTypeForIndex(index, total),

                    SponsorBlockMenuActionHandler.ACTION_OPEN_SEGMENT_ACTIONS,

                    context,

                    segmentIndex = index,

                ),

            )

        }

        rows.add(factory.createSpacer(16))

        showDialog(activity, rows)

    }



    fun showSegmentActions(context: Context, index: Int) {

        val activity = findActivity(context) ?: return

        val segment = segmentSnapshot.getOrNull(index) ?: return

        dismissActive()

        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)

        val rows = ArrayList<Any>()

        val actions = listOf(

            Triple("手动跳过", "arrow-play-next-line@500", SponsorBlockMenuActionHandler.ACTION_MANUAL_SKIP_SEGMENT),

            Triple("跳转到起点", "play-line@500", SponsorBlockMenuActionHandler.ACTION_SEEK_SEGMENT),

            Triple("赞成", "thumb-up-line@500", SponsorBlockMenuActionHandler.ACTION_VOTE_SEGMENT to 1),

            Triple("反对", "thumb-down-line@500", SponsorBlockMenuActionHandler.ACTION_VOTE_SEGMENT to 0),

            Triple("撤销投票", "arrow-go-back-line@500", SponsorBlockMenuActionHandler.ACTION_VOTE_SEGMENT to 20),

            Triple("更改类别", "pen-write-line@500", SponsorBlockMenuActionHandler.ACTION_CHANGE_CATEGORY),

        )

        val total = actions.size + 2

        val voteLabel = SponsorBlockState.userVoteLabel(segment.uuid)

        rows.add(

            factory.createInfoRow(

                SponsorBlockCategory.titleOf(segment.category),

                segmentSummary(segment) + (voteLabel?.let { " · $it" } ?: ""),

                "arrow-play-next-line@500",

                factory.videoSettingTypeForIndex(0, total),

            ),

        )

        actions.forEachIndexed { actionIndex, (title, icon, action) ->

            when (action) {

                is Int -> rows.add(

                    factory.createActionRow(

                        title,

                        icon,

                        segmentSummary(segment),

                        false,

                        factory.videoSettingTypeForIndex(actionIndex + 1, total),

                        action,

                        context,

                        segmentIndex = index,

                    ),

                )

                is Pair<*, *> -> {

                    val voteType = action.second as Int

                    rows.add(

                        factory.createActionRow(

                            title,

                            icon,

                            SponsorBlockState.voteActionSubtitle(segment.uuid, voteType),

                            false,

                            factory.videoSettingTypeForIndex(actionIndex + 1, total),

                            action.first as Int,

                            context,

                            segmentIndex = index,

                            voteType = voteType,

                        ),

                    )

                }

            }

        }

        rows.add(factory.createSpacer(16))

        showDialog(activity, rows)

    }



    fun showCurrentSegmentActions(context: Context) {

        val segment = currentSegmentAtPlayhead() ?: run {

            Log.toast("播放头不在片段内")

            return

        }

        segmentSnapshot = SponsorBlockController.segments

        val index = segmentSnapshot.indexOfFirst { it.uuid == segment.uuid && it.startMs == segment.startMs }

            .takeIf { it >= 0 } ?: segmentSnapshot.indexOf(segment)

        if (index >= 0) {

            showSegmentActions(context, index)

        } else {

            segmentSnapshot = listOf(segment)

            showSegmentActions(context, 0)

        }

    }



    fun manualSkipAtPlayhead(context: Context) {

        val segment = currentSegmentAtPlayhead()

        if (segment == null) {

            Log.toast("播放头不在可跳过片段内")

            return

        }

        SponsorBlockController.manualSkipSegment(segment)

        Log.toast("已跳过：${SponsorBlockCategory.titleOf(segment.category)}")

    }



    fun manualSkipSegmentAt(context: Context, index: Int) {

        val segment = segmentSnapshot.getOrNull(index) ?: return

        dismissActive()

        Handler(Looper.getMainLooper()).post {

            SponsorBlockController.manualSkipSegment(segment)

            Log.toast(

                "已跳到片段末尾：${SponsorBlockCategory.titleOf(segment.category)} · " +

                    SponsorBlockTimeFormat.formatMs(segment.endMs),

            )

        }

    }



    fun seekToSegmentAt(context: Context, index: Int) {

        val segment = segmentSnapshot.getOrNull(index) ?: return

        SponsorBlockController.seekToSegment(segment)

    }



    fun voteSegmentAt(context: Context, index: Int, voteType: Int, category: String?) {

        val segment = segmentSnapshot.getOrNull(index) ?: return

        if (segment.uuid.isBlank()) {

            Log.toast("该片段无 UUID，无法投票")

            return

        }

        SponsorBlockController.vote(segment.uuid, voteType, category) { ok ->

            val msg = when (voteType) {

                1 -> if (ok) "已赞成" else "赞成失败"

                0 -> if (ok) "已反对" else "反对失败"

                20 -> if (ok) "已撤销投票" else "撤销失败"

                else -> if (ok) "操作成功" else "操作失败"

            }

            Log.toast(msg)

            if (ok) showSegmentActions(context, index)

        }

    }



    fun showCategoryPicker(context: Context, index: Int) {

        val segment = segmentSnapshot.getOrNull(index) ?: return

        if (segment.uuid.isBlank()) {

            Log.toast("该片段无 UUID，无法更改类别")

            return

        }

        val categories = SponsorBlockCategory.all

        AlertDialog.Builder(context)

            .setTitle("更改类别")

            .setItems(categories.map { it.title }.toTypedArray()) { _, which ->

                voteSegmentAt(context, index, 1, categories[which].id)

            }

            .show()

    }



    fun dismissActive() {
        SponsorBlockVideoSettingDialog.dismiss()
    }



    private fun currentSegmentAtPlayhead(): SponsorBlockState.SegmentView? {

        val pos = SponsorBlockController.readPlaybackPositionMs()

            ?: SponsorBlockController.playbackPositionMs

        return SponsorBlockController.findSegmentAtTimeMs(pos)

    }



    private fun segmentLabel(segment: SponsorBlockState.SegmentView, index: Int): String =

        "${index + 1}. ${SponsorBlockCategory.titleOf(segment.category)}"



    private fun segmentSummary(segment: SponsorBlockState.SegmentView): String =

        "${SponsorBlockTimeFormat.formatMs(segment.startMs)} → ${SponsorBlockTimeFormat.formatMs(segment.endMs)}"



    private fun showDialog(activity: Activity, rows: List<Any>) {
        dismissActive()
        SponsorBlockVideoSettingDialog.show(activity, rows)
    }



    private fun findActivity(context: Context): Activity? {

        var ctx: Context? = context

        while (ctx != null) {

            if (ctx is Activity) return ctx

            ctx = (ctx as? ContextWrapper)?.baseContext

        }

        return null

    }

}


