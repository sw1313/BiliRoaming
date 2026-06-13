package me.custom.biliextras.sponsorblock

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import kotlin.concurrent.thread

object SponsorBlockSettingsDialogs {

    fun showBlockLimit(context: Context, onChanged: () -> Unit = {}) {
        val editText = EditText(context).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(SponsorBlockPrefs.blockLimit.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle("最短片段时长")
            .setMessage("忽略短于此时长的片段，单位秒。")
            .setView(editText)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SponsorBlockPrefs.setBlockLimit(editText.text.toString().toDoubleOrNull() ?: 0.0)
                onChanged()
            }
            .show()
    }

    fun showUserId(context: Context, onChanged: () -> Unit = {}) {
        val editText = EditText(context).apply {
            setSingleLine(false)
            setText(SponsorBlockPrefs.userId)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle("用户ID")
            .setView(editText)
            .setNeutralButton("随机") { _, _ ->
                SponsorBlockPrefs.resetUserId()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SponsorBlockPrefs.setUserId(editText.text.toString())
                onChanged()
            }
            .show()
    }

    fun showServer(context: Context, onChanged: () -> Unit = {}) {
        val editText = EditText(context).apply {
            setSingleLine(true)
            setText(SponsorBlockPrefs.server)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle("空降助手服务器")
            .setView(editText)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SponsorBlockPrefs.setServer(editText.text.toString())
                onChanged()
            }
            .show()
    }

    fun showCategory(context: Context, category: SponsorBlockCategory.Category, onChanged: () -> Unit = {}) {
        val items = arrayOf("跳过模式", "设置颜色", "重置颜色", category.description)
        AlertDialog.Builder(context)
            .setTitle(category.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showCategoryMode(context, category, onChanged)
                    1 -> showCategoryColor(context, category, onChanged)
                    2 -> {
                        SponsorBlockPrefs.resetColor(category.id)
                        onChanged()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun checkService(onFinished: () -> Unit = {}) {
        thread(name = "BiliExtrasSponsorBlockStatus") {
            SponsorBlockApi.checkStatus()
            SponsorBlockApi.userInfo()
            onFinished()
        }
    }

    private fun showCategoryMode(
        context: Context,
        category: SponsorBlockCategory.Category,
        onChanged: () -> Unit,
    ) {
        val modes = SponsorBlockCategory.SkipMode.entries.toTypedArray()
        val current = SponsorBlockPrefs.modeOf(category.id)
        AlertDialog.Builder(context)
            .setTitle(category.title)
            .setSingleChoiceItems(
                modes.map { it.title }.toTypedArray(),
                modes.indexOf(current),
            ) { dialog, which ->
                SponsorBlockPrefs.setMode(category.id, modes[which])
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCategoryColor(
        context: Context,
        category: SponsorBlockCategory.Category,
        onChanged: () -> Unit,
    ) {
        val colors = intArrayOf(
            category.defaultColor,
            0xFF00D400.toInt(),
            0xFFFFFF00.toInt(),
            0xFFCC00FF.toInt(),
            0xFF00FFFF.toInt(),
            0xFF008FD6.toInt(),
            0xFFFF1684.toInt(),
            0xFFFF9900.toInt(),
            0xFF7300FF.toInt(),
            0xFF222222.toInt(),
        ).distinct().toIntArray()
        AlertDialog.Builder(context)
            .setTitle("${category.shortTitle} 颜色")
            .setItems(colors.map { "#${it.toHexColor()}" }.toTypedArray()) { dialog, which ->
                SponsorBlockPrefs.setColor(category.id, colors[which])
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun Int.toHexColor(): String =
        Integer.toHexString(this).padStart(8, '0').takeLast(6).uppercase()
}
