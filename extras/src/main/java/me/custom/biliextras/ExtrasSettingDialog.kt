@file:Suppress("DEPRECATION")

package me.custom.biliextras

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.preference.*
import android.view.ViewGroup
import android.widget.TextView
import me.custom.biliextras.hook.StoryDiversionPrefs
import me.custom.biliextras.utils.addModuleAssets
import me.custom.biliextras.utils.callMethodOrNull
import me.custom.biliextras.utils.getObjectFieldOrNull
import me.custom.biliextras.utils.hookMethod
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
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
                "block_story_ad_dynamic",
                "hide_vip_center",
                "block_charging_video",
                "block_charging_video_log",
                "block_promoted_video",
                "show_info",
                Log.KEY_VERBOSE,
            ).forEach {
                findPreference(it)?.onPreferenceChangeListener = this
            }
            findPreference("story_diversion_settings")?.onPreferenceClickListener = this
            updateStoryDiversionSummary()
        }

        override fun onPreferenceChange(preference: Preference?, newValue: Any?): Boolean {
            val key = preference?.key ?: return true
            if (newValue is Boolean) {
                ePrefs.edit().putBoolean(key, newValue).commit()
                if (key == Log.KEY_VERBOSE) Log.refreshVerboseCache()
            }
            return true
        }

        override fun onPreferenceClick(preference: Preference?): Boolean {
            return when (preference?.key) {
                "story_diversion_settings" -> {
                    showStoryDiversionSettings()
                    true
                }
                else -> false
            }
        }

        private fun updateStoryDiversionSummary() {
            val blocked = StoryDiversionPrefs.blockedShortTitles()
                .joinToString("、")
                .ifBlank { "未屏蔽任何入口" }
            findPreference("story_diversion_settings")?.summary = "已屏蔽：$blocked"
        }

        private fun showStoryDiversionSettings() {
            val context = activity ?: return
            val entries = StoryDiversionPrefs.allEntries
            val titles = entries.map { it.title }.toTypedArray()
            val checked = entries.map { StoryDiversionPrefs.isBlocked(it.key) }.toBooleanArray()
            AlertDialog.Builder(context)
                .setTitle("屏蔽竖屏导流入口选项")
                .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val editor = ePrefs.edit()
                    entries.forEachIndexed { index, entry ->
                        editor.putBoolean(entry.key, checked[index])
                    }
                    editor.commit()
                    updateStoryDiversionSummary()
                }
                .show()
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
