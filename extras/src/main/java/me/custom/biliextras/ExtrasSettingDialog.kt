@file:Suppress("DEPRECATION")

package me.custom.biliextras

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.preference.*
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import me.custom.biliextras.sponsorblock.SponsorBlockApi
import me.custom.biliextras.sponsorblock.SponsorBlockCategory
import me.custom.biliextras.sponsorblock.SponsorBlockPrefs
import me.custom.biliextras.utils.addModuleAssets
import me.custom.biliextras.utils.callMethodOrNull
import me.custom.biliextras.utils.getObjectFieldOrNull
import me.custom.biliextras.utils.hookMethod
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ExtrasSettingDialog(context: Context) : AlertDialog.Builder(context) {
    class PrefsFragment : PreferenceFragment(), Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {
        @Deprecated("Deprecated in Java")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            preferenceManager.sharedPreferencesName = Constant.PREFS_NAME
            addPreferencesFromResource(R.xml.prefs_extras)
            findPreference("version")?.summary = BuildConfig.VERSION_NAME
            listOf(
                "block_up_share_goods",
                "block_story_live",
                "block_story_goods",
                "story_background_auto_next",
                "foreground_auto_next",
                "media_button_control",
                "hide_vip_center",
                "disable_chapter_progress",
                SponsorBlockPrefs.KEY_ENABLED,
                "show_info",
            ).forEach {
                findPreference(it)?.onPreferenceChangeListener = this
            }
            findPreference("sponsorblock_settings")?.onPreferenceClickListener = this
            updateSponsorBlockSummary()
        }

        override fun onPreferenceChange(preference: Preference?, newValue: Any?): Boolean {
            val key = preference?.key ?: return true
            if (newValue is Boolean) {
                ePrefs.edit().putBoolean(key, newValue).commit()
                updateSponsorBlockSummary()
            }
            return true
        }

        override fun onPreferenceClick(preference: Preference?): Boolean {
            return when (preference?.key) {
                "sponsorblock_settings" -> {
                    showSponsorBlockSettings()
                    true
                }
                else -> false
            }
        }

        private fun updateSponsorBlockSummary() {
            val categories = SponsorBlockPrefs.autoSkipCategories.joinToString("、") {
                SponsorBlockCategory.shortTitleOf(it)
            }.ifBlank { "未启用自动跳过" }
            findPreference("sponsorblock_settings")?.summary =
                "服务状态：${SponsorBlockPrefs.status}；自动跳过：$categories"
        }

        private fun showSponsorBlockSettings() {
            val context = activity ?: return
            lateinit var infoView: TextView
            lateinit var adapter: ArrayAdapter<CharSequence>
            val refresh = {
                runCatching {
                    infoView.text = sponsorBlockInfo()
                    adapter.clear()
                    adapter.addAll(sponsorBlockActions().toList())
                    adapter.notifyDataSetChanged()
                    updateSponsorBlockSummary()
                }.onFailure {
                    Log.w("SponsorBlock settings refresh failed: ${it.message}")
                }
                Unit
            }
            val contentView = sponsorBlockView(context) { info, listAdapter ->
                infoView = info
                adapter = listAdapter
            }
            lateinit var dialog: AlertDialog
            dialog = AlertDialog.Builder(context)
                .setTitle("空降助手")
                .setView(contentView)
                .setNeutralButton("检测服务", null)
                .setNegativeButton("关于") { _, _ ->
                    AlertDialog.Builder(context)
                        .setTitle("关于空降助手")
                        .setMessage("https://github.com/hanydd/BilibiliSponsorBlock")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                .setPositiveButton(android.R.string.ok, null)
                .create()
            contentView.findViewById<ListView>(SPONSOR_BLOCK_LIST_ID).setOnItemClickListener { _, _, which, _ ->
                onSponsorBlockActionClick(which, refresh)
            }
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                thread(name = "BiliExtrasSponsorBlockStatus") {
                    val ok = SponsorBlockApi.checkStatus().getOrDefault(false)
                    SponsorBlockApi.userInfo()
                    Log.toast("空降助手服务${if (ok) "正常" else "异常"}", force = true)
                    activity?.runOnUiThread {
                        if (dialog.isShowing) {
                            refresh()
                            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                        }
                    }
                }
            }
        }

        private fun sponsorBlockView(
            context: Context,
            onCreated: (TextView, ArrayAdapter<CharSequence>) -> Unit,
        ): LinearLayout {
            val adapter = ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                sponsorBlockActions(),
            )
            val infoView = TextView(context).apply {
                text = sponsorBlockInfo()
                textSize = 16f
                setPadding(0, 0, 0, 12.dp(context))
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24.dp(context), 8.dp(context), 24.dp(context), 0)
                addView(infoView)
                addView(ListView(context).apply {
                    id = SPONSOR_BLOCK_LIST_ID
                    this.adapter = adapter
                    dividerHeight = 1
                })
                onCreated(infoView, adapter)
            }
        }

        private fun sponsorBlockInfo(): String {
            return buildString {
                appendLine("服务状态：${SponsorBlockPrefs.status}")
                appendLine("您的信息：${SponsorBlockPrefs.userInfo}")
                appendLine("跳过次数：${ePrefs.getInt(SponsorBlockPrefs.KEY_SKIP_COUNT, 0)} 次")
                appendLine("节省时间：${ePrefs.getLong(SponsorBlockPrefs.KEY_SAVED_SECONDS, 0L)} 秒")
                appendLine("最短片段时长：${SponsorBlockPrefs.blockLimit}s")
                appendLine("用户ID：${SponsorBlockPrefs.userId}")
                append("服务器地址：${SponsorBlockPrefs.server}")
            }
        }

        private fun sponsorBlockActions(): Array<CharSequence> {
            return buildList {
                add("显示跳过 Toast：${if (SponsorBlockPrefs.showToast) "开启" else "关闭"}")
                add("显示进度条片段：${if (SponsorBlockPrefs.showProgress) "开启" else "关闭"}")
                add("跳过次数统计跟踪：${if (SponsorBlockPrefs.trackStats) "开启" else "关闭"}")
                add("最短片段时长：${SponsorBlockPrefs.blockLimit}s")
                add("用户ID：${SponsorBlockPrefs.userId.take(8)}...")
                add("服务器地址：${SponsorBlockPrefs.server}")
                SponsorBlockCategory.all.forEach { category ->
                    add(
                        colorDotText(
                            "${category.title}：${SponsorBlockPrefs.modeOf(category.id).title}",
                            SponsorBlockPrefs.colorOf(category.id),
                        ),
                    )
                }
            }.toTypedArray()
        }

        private fun onSponsorBlockActionClick(which: Int, refresh: () -> Unit) {
            when (which) {
                0 -> {
                    SponsorBlockPrefs.setShowToast(!SponsorBlockPrefs.showToast)
                    refresh()
                }
                1 -> {
                    SponsorBlockPrefs.setShowProgress(!SponsorBlockPrefs.showProgress)
                    refresh()
                }
                2 -> {
                    SponsorBlockPrefs.setTrackStats(!SponsorBlockPrefs.trackStats)
                    refresh()
                }
                3 -> showBlockLimitDialog(refresh)
                4 -> showUserIdDialog(refresh)
                5 -> showServerDialog(refresh)
                else -> {
                    val category = SponsorBlockCategory.all.getOrNull(which - SPONSOR_BLOCK_CATEGORY_OFFSET) ?: return
                    showCategoryDialog(category, refresh)
                }
            }
        }

        private fun showCategoryDialog(category: SponsorBlockCategory.Category, refresh: () -> Unit) {
            val context = activity ?: return
            val items = arrayOf("跳过模式", "设置颜色", "重置颜色", category.description)
            AlertDialog.Builder(context)
                .setTitle(category.title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> showCategoryModeDialog(category, refresh)
                        1 -> showColorDialog(category, refresh)
                        2 -> {
                            SponsorBlockPrefs.resetColor(category.id)
                            refresh()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showCategoryModeDialog(category: SponsorBlockCategory.Category, refresh: () -> Unit) {
            val context = activity ?: return
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
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showColorDialog(category: SponsorBlockCategory.Category, refresh: () -> Unit) {
            val context = activity ?: return
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
                .setItems(colors.map { colorDotText("#${it.toHexColor()}", it) }.toTypedArray()) { dialog, which ->
                    SponsorBlockPrefs.setColor(category.id, colors[which])
                    dialog.dismiss()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showBlockLimitDialog(refresh: () -> Unit) {
            val context = activity ?: return
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
                    refresh()
                }
                .show()
        }

        private fun showUserIdDialog(refresh: () -> Unit) {
            val context = activity ?: return
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
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    SponsorBlockPrefs.setUserId(editText.text.toString())
                    refresh()
                }
                .show()
        }

        private fun showServerDialog(refresh: (() -> Unit)? = null) {
            val context = activity ?: return
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
                    refresh?.invoke() ?: updateSponsorBlockSummary()
                }
                .show()
        }

        private fun Int.dp(context: Context): Int =
            (this * context.resources.displayMetrics.density + 0.5f).toInt()

        private fun Int.toHexColor(): String =
            Integer.toHexString(this).padStart(8, '0').takeLast(6).uppercase()

        private fun colorDotText(text: String, color: Int): CharSequence {
            return SpannableString("● $text").apply {
                setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        companion object {
            private const val SPONSOR_BLOCK_LIST_ID = 0x24050001
            private const val SPONSOR_BLOCK_CATEGORY_OFFSET = 6
        }
    }

    init {
        val activity = context as Activity
        activity.addModuleAssets()

        val prefsFragment = PrefsFragment()
        activity.fragmentManager.beginTransaction().add(prefsFragment, "ExtrasSetting").commit()
        activity.fragmentManager.executePendingTransactions()
        prefsFragment.onActivityCreated(null)

        val unhook = Preference::class.java.hookMethod(
            "onCreateView", ViewGroup::class.java
        ) { chain ->
            val result = chain.proceed()
            if (PreferenceCategory::class.java.isInstance(chain.thisObject)
                && TextView::class.java.isInstance(result)
            ) {
                val textView = result as TextView
                if (textView.textColors.defaultColor == -13816531) {
                    textView.setTextColor(android.graphics.Color.GRAY)
                }
            }
            result
        }

        setView(prefsFragment.view)
        setTitle("漫游扩展")
        setNegativeButton("返回", null)
        setPositiveButton("确定并重启客户端") { _, _ ->
            prefsFragment.preferenceManager.forceSavePreference()
            restartApplication(activity)
        }
        setOnDismissListener {
            unhook?.unhook()
        }
    }

    companion object {
        @JvmStatic
        fun restartApplication(activity: Activity) {
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            activity.finishAffinity()
            activity.startActivity(intent)
            exitProcess(0)
        }

        @SuppressLint("CommitPrefEdits")
        @JvmStatic
        fun PreferenceManager.forceSavePreference() {
            sharedPreferences.let {
                val cm = (getObjectFieldOrNull("mEditor")
                    ?: it.edit()).callMethodOrNull("commitToMemory")
                val lock = it.getObjectFieldOrNull("mWritingToDiskLock") ?: return@let
                synchronized(lock) {
                    it.callMethodOrNull("writeToFile", cm, true)
                }
            }
        }

        fun show(context: Context) {
            try {
                ExtrasSettingDialog(context).show()
            } catch (e: Resources.NotFoundException) {
                Log.e(e)
                AlertDialog.Builder(context)
                    .setTitle("需要重启")
                    .setMessage("漫游扩展更新了")
                    .setPositiveButton("重启") { _, _ ->
                        restartApplication(context as Activity)
                    }.show()
            }
        }
    }
}
