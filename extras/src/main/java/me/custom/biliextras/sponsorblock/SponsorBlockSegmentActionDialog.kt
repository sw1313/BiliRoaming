package me.custom.biliextras.sponsorblock

import android.app.AlertDialog
import android.content.Context
import me.custom.biliextras.utils.Log

object SponsorBlockSegmentActionDialog {
    fun show(context: Context, segment: SponsorBlockState.SegmentView) {
        val categoryTitle = SponsorBlockCategory.titleOf(segment.category)
        val voteLabel = SponsorBlockState.userVoteLabel(segment.uuid)
        val titleSuffix = voteLabel?.let { " · $it" } ?: ""
        val items = arrayOf("赞成", "反对", "撤销投票", "更改类别")
        AlertDialog.Builder(context)
            .setTitle("片段：$categoryTitle$titleSuffix")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> vote(context, segment, 1)
                    1 -> vote(context, segment, 0)
                    2 -> vote(context, segment, 20)
                    3 -> showCategoryPicker(context, segment)
                }
            }
            .show()
    }

    private fun showCategoryPicker(context: Context, segment: SponsorBlockState.SegmentView) {
        val categories = SponsorBlockCategory.all
        val labels = categories.map { it.title }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("更改类别")
            .setItems(labels) { _, which ->
                val category = categories[which]
                SponsorBlockController.vote(segment.uuid, 1, category.id) { ok ->
                    Log.toast(if (ok) "已更新类别" else "操作失败")
                    if (ok) show(context, segment)
                }
            }
            .show()
    }

    private fun vote(context: Context, segment: SponsorBlockState.SegmentView, type: Int) {
        if (segment.uuid.isBlank()) {
            Log.toast("该片段无 UUID，无法投票")
            return
        }
        SponsorBlockController.vote(segment.uuid, type) { ok ->
            val msg = when (type) {
                1 -> if (ok) "已赞成" else "赞成失败"
                0 -> if (ok) "已反对" else "反对失败"
                20 -> if (ok) "已撤销" else "撤销失败"
                else -> if (ok) "操作成功" else "操作失败"
            }
            Log.toast(msg)
            if (ok) show(context, segment)
        }
    }
}
