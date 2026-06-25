package me.custom.biliextras.hook

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import me.custom.biliextras.utils.Log
import java.util.concurrent.Executors

/** One row per blocked UP with add / delete actions. */
object StoryUpBlockListDialog {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, onChanged: () -> Unit = {}) {
        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(container)

        val dialog = AlertDialog.Builder(context)
            .setTitle("竖屏屏蔽 UP 列表")
            .setMessage("屏蔽以 UID 为准；昵称联网查询仅用于展示。")
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("完成") { _, _ -> onChanged() }
            .create()

        fun refreshList() {
            container.removeAllViews()
            val mids = StoryUpBlockPrefs.orderedBlockedMids()
            if (mids.isEmpty()) {
                container.addView(
                    TextView(context).apply {
                        text = "暂无屏蔽项，点击下方「添加」"
                        setTextColor(0xFF888888.toInt())
                    },
                )
                return
            }
            mids.forEach { mid ->
                container.addView(createRow(context, mid, ::refreshList, onChanged))
            }
        }

        fun reloadListAsync() {
            ioExecutor.execute {
                StoryUpCardApi.prefetchNames(StoryUpBlockPrefs.orderedBlockedMids())
                mainHandler.post {
                    refreshList()
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
                }
            }
        }

        dialog.setOnShowListener {
            container.removeAllViews()
            container.addView(
                TextView(context).apply {
                    text = "加载昵称中…"
                    setTextColor(0xFF888888.toInt())
                },
            )
            reloadListAsync()
        }

        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "添加") { _, _ ->
            showAddDialog(context) { added ->
                if (added) {
                    onChanged()
                    reloadListAsync()
                }
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
    }

    private fun createRow(
        context: Context,
        mid: Long,
        refreshList: () -> Unit,
        onChanged: () -> Unit,
    ): LinearLayout {
        val density = context.resources.displayMetrics.density
        val rowPadV = (8 * density).toInt()
        val rowPadH = (4 * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, rowPadV, 0, rowPadV)
            addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = StoryUpCardApi.formatDisplay(mid)
                    textSize = 15f
                },
            )
            addView(
                Button(context).apply {
                    text = "删除"
                    textSize = 13f
                    setTypeface(typeface, Typeface.NORMAL)
                    setPadding(rowPadH, rowPadV, rowPadH, rowPadV)
                    setOnClickListener {
                        AlertDialog.Builder(context)
                            .setTitle("删除屏蔽")
                            .setMessage("确定移除 ${StoryUpCardApi.formatDisplay(mid)} 吗？")
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                StoryUpBlockPrefs.removeMid(mid)
                                onChanged()
                                ioExecutor.execute {
                                    StoryUpCardApi.prefetchNames(StoryUpBlockPrefs.orderedBlockedMids())
                                    mainHandler.post { refreshList() }
                                }
                            }
                            .show()
                    }
                },
            )
        }
    }

    private fun showAddDialog(context: Context, onDone: (Boolean) -> Unit) {
        val editor = EditText(context).apply {
            hint = "UID 或昵称"
            setSingleLine(true)
        }
        AlertDialog.Builder(context)
            .setTitle("添加屏蔽 UP")
            .setMessage("输入 UID 或昵称，将解析为 UID 保存。")
            .setView(editor)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone(false) }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val input = editor.text?.toString().orEmpty().trim()
                if (input.isEmpty()) {
                    Log.toast("请输入 UID 或昵称")
                    onDone(false)
                    return@setPositiveButton
                }
                ioExecutor.execute {
                    val mid = StoryUpBlockPrefs.resolveInput(input)
                    mainHandler.post {
                        if (mid == null) {
                            Log.toast("无法解析：$input")
                            onDone(false)
                            return@post
                        }
                        if (StoryUpBlockPrefs.containsMid(mid)) {
                            Log.toast("已在列表：${StoryUpCardApi.formatDisplay(mid)}")
                            onDone(false)
                            return@post
                        }
                        StoryUpBlockPrefs.addMid(mid)
                        StoryUpCardApi.fetchNameByMid(mid)
                        Log.toast("已添加 ${StoryUpCardApi.formatDisplay(mid)}")
                        onDone(true)
                    }
                }
            }
            .show()
    }
}
