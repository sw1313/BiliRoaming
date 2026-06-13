package me.custom.biliextras.sponsorblock

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import me.custom.biliextras.utils.Log
import java.util.ArrayList
import kotlin.math.max

object SponsorBlockSubmitSubMenu {
    data class DraftSegment(
        var startMs: Long,
        var endMs: Long,
        var categoryId: String,
    )

    private val drafts = mutableListOf<DraftSegment>()
    /** Pending edits for existing segments keyed by index in {@link SponsorBlockController#segments}. */
    private val existingEdits = mutableMapOf<Int, DraftSegment>()

    fun show(context: Context) {
        val activity = findActivity(context) ?: run {
            Log.toast("无法打开提交面板")
            return
        }
        if (SponsorBlockController.currentVideo == null) {
            Log.toast("当前无视频")
            return
        }
        drafts.clear()
        existingEdits.clear()
        ensureDefaultDraft()
        SponsorBlockMenuHost.hidePlayerSettingWidgetIfNeeded()
        Handler(Looper.getMainLooper()).post { showMain(context) }
    }

    private fun ensureDefaultDraft() {
        if (drafts.isNotEmpty()) return
        val pos = SponsorBlockController.readPlaybackPositionMs()
            ?: SponsorBlockController.playbackPositionMs
        val end = (pos + 10_000).coerceAtMost(durationMs())
        drafts.add(DraftSegment(pos, end, "sponsor"))
    }

    fun showExistingEdit(context: Context, index: Int) {
        val activity = findActivity(context) ?: return
        val segment = SponsorBlockController.segments.getOrNull(index) ?: return
        val edit = existingEdits.getOrPut(index) {
            DraftSegment(segment.startMs, segment.endMs, segment.category)
        }
        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)
        val rows = ArrayList<Any>()
        val actions = listOf(
            Triple("开始时间", "time-line@500", 0),
            Triple("结束时间", "time-line@500", 1),
            Triple("类别", "flag-line@500", 2),
            Triple("保存修改", "check-line@500", 3),
            Triple("赞成", "thumb-up-line@500", 6),
            Triple("反对", "thumb-down-line@500", 4),
            Triple("撤销投票", "arrow-go-back-line@500", 5),
        )
        val total = actions.size + 1
        val voteLabel = SponsorBlockState.userVoteLabel(segment.uuid)
        rows.add(
            factory.createInfoRow(
                SponsorBlockCategory.titleOf(segment.category),
                timeRange(edit.startMs, edit.endMs) + (voteLabel?.let { " · $it" } ?: ""),
                "skip-forward-line@500",
                factory.videoSettingTypeForIndex(0, total),
            ),
        )
        actions.forEachIndexed { actionIndex, (title, icon, sub) ->
            val subtitle = when (sub) {
                0 -> SponsorBlockTimeFormat.formatMs(edit.startMs)
                1 -> SponsorBlockTimeFormat.formatMs(edit.endMs)
                2 -> SponsorBlockCategory.titleOf(edit.categoryId)
                3 -> if (hasExistingChanges(index, segment)) "提交新时间并标记旧片段" else "未修改"
                4 -> SponsorBlockState.voteActionSubtitle(segment.uuid, 0)
                5 -> SponsorBlockState.voteActionSubtitle(segment.uuid, 20)
                6 -> SponsorBlockState.voteActionSubtitle(segment.uuid, 1)
                else -> ""
            }
            rows.add(
                factory.createSubmitActionRow(
                    title,
                    icon,
                    subtitle,
                    sub in 0..2,
                    factory.videoSettingTypeForIndex(actionIndex + 1, total),
                    SponsorBlockSubmitActionHandler.EXISTING_ACTION,
                    context,
                    index,
                    sub,
                ),
            )
        }
        rows.add(factory.createSpacer(16))
        SponsorBlockVideoSettingDialog.show(activity, rows)
    }

    fun showDraftEdit(context: Context, index: Int) {
        val activity = findActivity(context) ?: return
        val draft = drafts.getOrNull(index) ?: return
        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)
        val rows = ArrayList<Any>()
        val actions = listOf(
            Triple("开始时间", "time-line@500", 0),
            Triple("结束时间", "time-line@500", 1),
            Triple("类别", "flag-line@500", 2),
            Triple("添加另一个片段", "add-line@500", 4),
            Triple("移除此片段", "delete-bin-line@500", 3),
        )
        val total = actions.size + 1
        rows.add(
            factory.createInfoRow(
                "新片段 ${index + 1}",
                timeRange(draft.startMs, draft.endMs),
                "add-line@500",
                factory.videoSettingTypeForIndex(0, total),
            ),
        )
        actions.forEachIndexed { actionIndex, (title, icon, sub) ->
            val subtitle = when (sub) {
                0 -> SponsorBlockTimeFormat.formatMs(draft.startMs)
                1 -> SponsorBlockTimeFormat.formatMs(draft.endMs)
                2 -> SponsorBlockCategory.titleOf(draft.categoryId)
                else -> ""
            }
            rows.add(
                factory.createSubmitActionRow(
                    title,
                    icon,
                    subtitle,
                    sub in 0..2,
                    factory.videoSettingTypeForIndex(actionIndex + 1, total),
                    SponsorBlockSubmitActionHandler.DRAFT_ACTION,
                    context,
                    index,
                    sub,
                ),
            )
        }
        rows.add(factory.createSpacer(16))
        SponsorBlockVideoSettingDialog.show(activity, rows)
    }

    fun showDraftTimeMenu(context: Context, draftIndex: Int, isStart: Boolean) {
        val activity = findActivity(context) ?: return
        val draft = drafts.getOrNull(draftIndex) ?: return
        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)
        val rows = ArrayList<Any>()
        val field = if (isStart) 0 else 1
        val currentMs = if (isStart) draft.startMs else draft.endMs
        val actions = listOf(
            "定位到当前播放位置" to 0,
            (if (isStart) "设为视频开头" else "设为视频结尾") to 1,
            "手动输入时间" to 2,
        )
        val total = actions.size + 1
        rows.add(
            factory.createInfoRow(
                if (isStart) "开始时间" else "结束时间",
                SponsorBlockTimeFormat.formatMs(currentMs),
                "time-line@500",
                factory.videoSettingTypeForIndex(0, total),
            ),
        )
        actions.forEachIndexed { actionIndex, (title, sub) ->
            rows.add(
                factory.createSubmitTimeActionRow(
                    title,
                    "time-line@500",
                    "",
                    false,
                    factory.videoSettingTypeForIndex(actionIndex + 1, total),
                    context,
                    draftIndex,
                    field,
                    sub,
                ),
            )
        }
        rows.add(factory.createSpacer(16))
        SponsorBlockVideoSettingDialog.show(activity, rows)
    }

    fun showExistingTimeMenu(context: Context, existingIndex: Int, isStart: Boolean) {
        val activity = findActivity(context) ?: return
        val segment = SponsorBlockController.segments.getOrNull(existingIndex) ?: return
        val edit = existingEdits.getOrPut(existingIndex) {
            DraftSegment(segment.startMs, segment.endMs, segment.category)
        }
        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)
        val rows = ArrayList<Any>()
        val field = if (isStart) 0 else 1
        val currentMs = if (isStart) edit.startMs else edit.endMs
        val actions = listOf(
            "定位到当前播放位置" to 0,
            (if (isStart) "设为视频开头" else "设为视频结尾") to 1,
            "手动输入时间" to 2,
        )
        val total = actions.size + 1
        rows.add(
            factory.createInfoRow(
                if (isStart) "开始时间" else "结束时间",
                SponsorBlockTimeFormat.formatMs(currentMs),
                "time-line@500",
                factory.videoSettingTypeForIndex(0, total),
            ),
        )
        actions.forEachIndexed { actionIndex, (title, sub) ->
            rows.add(
                factory.createSubmitTimeActionRowExisting(
                    title,
                    "time-line@500",
                    "",
                    false,
                    factory.videoSettingTypeForIndex(actionIndex + 1, total),
                    context,
                    existingIndex,
                    field,
                    sub,
                ),
            )
        }
        rows.add(factory.createSpacer(16))
        SponsorBlockVideoSettingDialog.show(activity, rows)
    }

    fun onDraftAction(context: Context, index: Int, subAction: Int) {
        when (subAction) {
            0 -> showDraftTimeMenu(context, index, isStart = true)
            1 -> showDraftTimeMenu(context, index, isStart = false)
            2 -> showCategoryPicker(context, onExisting = false, index)
            3 -> {
                drafts.removeAt(index)
                if (drafts.isEmpty()) ensureDefaultDraft()
                showMain(context)
            }
            4 -> addDraftAndRefresh(context)
        }
    }

    fun onExistingAction(context: Context, index: Int, subAction: Int) {
        val segment = SponsorBlockController.segments.getOrNull(index) ?: return
        when (subAction) {
            0 -> showExistingTimeMenu(context, index, isStart = true)
            1 -> showExistingTimeMenu(context, index, isStart = false)
            2 -> showCategoryPicker(context, onExisting = true, index)
            3 -> saveExistingEdit(context, index, segment)
            4 -> downvoteExisting(context, index, segment)
            5 -> unvoteExisting(context, index, segment)
            6 -> upvoteExisting(context, index, segment)
        }
    }

    fun onTimeAction(context: Context, draftIndex: Int, timeField: Int, subAction: Int) {
        val draft = drafts.getOrNull(draftIndex) ?: return
        val isStart = timeField == 0
        when (subAction) {
            0 -> {
                val pos = currentPositionMs()
                if (isStart) draft.startMs = pos else draft.endMs = pos
                showDraftEdit(context, draftIndex)
            }
            1 -> {
                val duration = durationMs()
                if (isStart) draft.startMs = 0L else draft.endMs = duration
                showDraftEdit(context, draftIndex)
            }
            2 -> showManualTimeInput(context, draftIndex, isStart, existingIndex = null)
        }
    }

    fun onExistingTimeAction(context: Context, existingIndex: Int, timeField: Int, subAction: Int) {
        val segment = SponsorBlockController.segments.getOrNull(existingIndex) ?: return
        val edit = existingEdits.getOrPut(existingIndex) {
            DraftSegment(segment.startMs, segment.endMs, segment.category)
        }
        val isStart = timeField == 0
        when (subAction) {
            0 -> {
                val pos = currentPositionMs()
                if (isStart) edit.startMs = pos else edit.endMs = pos
                showExistingEdit(context, existingIndex)
            }
            1 -> {
                val duration = durationMs()
                if (isStart) edit.startMs = 0L else edit.endMs = duration
                showExistingEdit(context, existingIndex)
            }
            2 -> showManualTimeInput(context, draftIndex = null, isStart, existingIndex)
        }
    }

    fun addDraftAndRefresh(context: Context) {
        val pos = currentPositionMs()
        val end = (pos + 10_000).coerceAtMost(durationMs())
        drafts.add(DraftSegment(pos, end, "sponsor"))
        showMain(context)
    }

    fun commitDrafts(context: Context) {
        if (drafts.isEmpty()) {
            Log.toast("请先添加片段")
            return
        }
        val segments = mutableListOf<SponsorBlockApi.SubmitSegment>()
        for ((index, draft) in drafts.withIndex()) {
            if (draft.endMs <= draft.startMs) {
                Log.toast("新片段 ${index + 1}：结束须晚于开始")
                return
            }
            segments.add(
                SponsorBlockApi.SubmitSegment(
                    draft.startMs / 1000.0,
                    draft.endMs / 1000.0,
                    draft.categoryId,
                ),
            )
        }
        val activity = findActivity(context) ?: return
        val summary = drafts.mapIndexed { index, draft ->
            "${index + 1}. ${SponsorBlockCategory.titleOf(draft.categoryId)}  " +
                timeRange(draft.startMs, draft.endMs)
        }.joinToString("\n")
        SponsorBlockVideoSettingDialog.dismiss()
        Handler(Looper.getMainLooper()).post {
            AlertDialog.Builder(activity)
                .setTitle("确认提交片段？")
                .setMessage(
                    "将上传 ${drafts.size} 个片段到 SponsorBlock：\n\n$summary\n\n" +
                        "提交后需社区审核，请确认时间无误。",
                )
                .setPositiveButton("确认提交") { _, _ ->
                    performCommit(segments)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun performCommit(segments: List<SponsorBlockApi.SubmitSegment>) {
        SponsorBlockController.submitSegments(segments) { ok, error ->
            Log.toast(if (ok) "已提交 ${segments.size} 个新片段" else "提交失败：${error ?: "未知错误"}")
            if (ok) {
                drafts.clear()
                SponsorBlockVideoSettingDialog.dismiss()
            }
        }
    }

    private fun showMain(context: Context) {
        val activity = findActivity(context) ?: return
        val existing = SponsorBlockController.segments
        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)
        val rows = ArrayList<Any>()
        val rowCount = existing.size + drafts.size + 2
        var rowIndex = 0
        fun nextType() = factory.videoSettingTypeForIndex(rowIndex++, max(rowCount, 1))

        rows.add(
            factory.createInfoRow(
                "提交空降片段",
                "已有 ${existing.size} 个 · 新增 ${drafts.size} 个",
                "pen-write-square-line@500",
                nextType(),
            ),
        )

        if (existing.isNotEmpty()) {
            existing.forEachIndexed { index, segment ->
                val edit = existingEdits[index]
                val startMs = edit?.startMs ?: segment.startMs
                val endMs = edit?.endMs ?: segment.endMs
                val categoryId = edit?.categoryId ?: segment.category
                val voteHint = SponsorBlockState.userVoteLabel(segment.uuid)?.let { " · $it" } ?: ""
                rows.add(
                    factory.createSubmitActionRow(
                        SponsorBlockCategory.titleOf(categoryId),
                        "time-line@500",
                        timeRange(startMs, endMs) + voteHint,
                        true,
                        nextType(),
                        SponsorBlockSubmitActionHandler.OPEN_EXISTING,
                        context,
                        index,
                    ),
                )
            }
        }

        drafts.forEachIndexed { index, draft ->
            rows.add(
                factory.createSubmitActionRow(
                    "新片段 ${index + 1}",
                    "add-line@500",
                    "${SponsorBlockCategory.titleOf(draft.categoryId)} · ${timeRange(draft.startMs, draft.endMs)}",
                    true,
                    nextType(),
                    SponsorBlockSubmitActionHandler.OPEN_DRAFT,
                    context,
                    index,
                ),
            )
        }

        rows.add(
            factory.createSubmitActionRow(
                "提交片段",
                "upload-cloud-2-line@500",
                "",
                false,
                nextType(),
                SponsorBlockSubmitActionHandler.COMMIT,
                context,
            ),
        )

        rows.add(factory.createSpacer(16))
        SponsorBlockVideoSettingDialog.show(activity, rows)
    }

    private fun saveExistingEdit(context: Context, index: Int, segment: SponsorBlockState.SegmentView) {
        val edit = existingEdits[index] ?: run {
            Log.toast("未修改")
            return
        }
        if (!hasExistingChanges(index, segment)) {
            Log.toast("未修改")
            return
        }
        if (edit.endMs <= edit.startMs) {
            Log.toast("结束须晚于开始")
            return
        }
        val newSeg = SponsorBlockApi.SubmitSegment(
            edit.startMs / 1000.0,
            edit.endMs / 1000.0,
            edit.categoryId,
        )
        SponsorBlockController.submitSegments(listOf(newSeg)) { ok, error ->
            if (!ok) {
                Log.toast("保存失败：${error ?: "未知错误"}")
                return@submitSegments
            }
            if (segment.uuid.isNotBlank()) {
                SponsorBlockController.vote(segment.uuid, 0) { voted ->
                    Log.toast(if (voted) "已保存修改" else "新片段已提交，旧片段标记失败")
                    existingEdits.remove(index)
                    SponsorBlockVideoSettingDialog.dismiss()
                }
            } else {
                Log.toast("已提交修改")
                existingEdits.remove(index)
                SponsorBlockVideoSettingDialog.dismiss()
            }
        }
    }

    private fun downvoteExisting(context: Context, index: Int, segment: SponsorBlockState.SegmentView) {
        if (segment.uuid.isBlank()) {
            Log.toast("该片段无 UUID，无法投票")
            return
        }
        SponsorBlockController.vote(segment.uuid, 0) { ok ->
            Log.toast(if (ok) "已反对" else "操作失败")
            if (ok) {
                existingEdits.remove(index)
                showExistingEdit(context, index)
            }
        }
    }

    private fun upvoteExisting(context: Context, index: Int, segment: SponsorBlockState.SegmentView) {
        if (segment.uuid.isBlank()) {
            Log.toast("该片段无 UUID，无法投票")
            return
        }
        SponsorBlockController.vote(segment.uuid, 1) { ok ->
            Log.toast(if (ok) "已赞成" else "赞成失败")
            if (ok) showExistingEdit(context, index)
        }
    }

    private fun unvoteExisting(context: Context, index: Int, segment: SponsorBlockState.SegmentView) {
        if (segment.uuid.isBlank()) {
            Log.toast("该片段无 UUID")
            return
        }
        SponsorBlockController.vote(segment.uuid, 20) { ok ->
            Log.toast(if (ok) "已撤销投票" else "操作失败")
            if (ok) showExistingEdit(context, index)
        }
    }

    private fun showCategoryPicker(context: Context, onExisting: Boolean, index: Int) {
        val categories = SponsorBlockCategory.all
        AlertDialog.Builder(context)
            .setTitle("选择类别")
            .setItems(categories.map { it.title }.toTypedArray()) { _, which ->
                val categoryId = categories[which].id
                if (onExisting) {
                    val segment = SponsorBlockController.segments.getOrNull(index) ?: return@setItems
                    val edit = existingEdits.getOrPut(index) {
                        DraftSegment(segment.startMs, segment.endMs, segment.category)
                    }
                    edit.categoryId = categoryId
                    if (segment.uuid.isNotBlank()) {
                        SponsorBlockController.vote(segment.uuid, 1, categoryId) { ok ->
                            Log.toast(if (ok) "已更改类别" else "更改失败")
                            if (ok) showExistingEdit(context, index)
                        }
                    } else {
                        showExistingEdit(context, index)
                    }
                } else {
                    drafts.getOrNull(index)?.categoryId = categoryId
                    showDraftEdit(context, index)
                }
            }
            .show()
    }

    private fun showManualTimeInput(
        context: Context,
        draftIndex: Int?,
        isStart: Boolean,
        existingIndex: Int?,
    ) {
        val activity = findActivity(context) ?: return
        val initial = when {
            draftIndex != null -> {
                val d = drafts.getOrNull(draftIndex) ?: return
                if (isStart) d.startMs else d.endMs
            }
            existingIndex != null -> {
                val seg = SponsorBlockController.segments.getOrNull(existingIndex) ?: return
                val edit = existingEdits.getOrPut(existingIndex) {
                    DraftSegment(seg.startMs, seg.endMs, seg.category)
                }
                if (isStart) edit.startMs else edit.endMs
            }
            else -> return
        }
        val edit = EditText(activity).apply {
            setText(SponsorBlockTimeFormat.formatMs(initial))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(activity)
            .setTitle(if (isStart) "开始时间" else "结束时间")
            .setMessage("支持 分:秒 或 时:分:秒")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val ms = SponsorBlockTimeFormat.parseToMs(edit.text.toString())
                if (ms == null) {
                    Log.toast("时间格式无效")
                    return@setPositiveButton
                }
                when {
                    draftIndex != null -> {
                        val d = drafts.getOrNull(draftIndex) ?: return@setPositiveButton
                        if (isStart) d.startMs = ms else d.endMs = ms
                        showDraftEdit(context, draftIndex)
                    }
                    existingIndex != null -> {
                        val idx = existingIndex
                        val e = existingEdits[idx]
                            ?: SponsorBlockController.segments.getOrNull(idx)?.let { seg ->
                                DraftSegment(seg.startMs, seg.endMs, seg.category).also {
                                    existingEdits[idx] = it
                                }
                            } ?: return@setPositiveButton
                        if (isStart) e.startMs = ms else e.endMs = ms
                        showExistingEdit(context, existingIndex)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hasExistingChanges(index: Int, segment: SponsorBlockState.SegmentView): Boolean {
        val edit = existingEdits[index] ?: return false
        return edit.startMs != segment.startMs ||
            edit.endMs != segment.endMs ||
            edit.categoryId != segment.category
    }

    private fun currentPositionMs(): Long =
        SponsorBlockController.readPlaybackPositionMs()
            ?: SponsorBlockController.playbackPositionMs

    private fun durationMs(): Long =
        SponsorBlockController.currentVideo?.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE

    private fun timeRange(startMs: Long, endMs: Long): String =
        "${SponsorBlockTimeFormat.formatMs(startMs)} → ${SponsorBlockTimeFormat.formatMs(endMs)}"

    private fun findActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx != null) {
            if (ctx is Activity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        return null
    }
}
