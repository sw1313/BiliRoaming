@file:Suppress("DEPRECATION")

package me.custom.biliextras

import android.app.AlertDialog
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceFragment
import android.widget.Toast
import me.custom.biliextras.hook.ExtrasSettingHook

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction().replace(android.R.id.content, PrefsFragment()).commit()
    }

    class PrefsFragment : PreferenceFragment(), Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {
        private lateinit var runningStatusPref: Preference

        @Deprecated("Deprecated in Java")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(R.xml.main_activity)
            runningStatusPref = findPreference("running_status")
            findPreference("hide_icon").onPreferenceChangeListener = this
            findPreference("version").summary = BuildConfig.VERSION_NAME
            findPreference("setting").onPreferenceClickListener = this
        }

        @Deprecated("Deprecated in Java")
        override fun onResume() {
            super.onResume()
            if (isModuleActive()) {
                runningStatusPref.setTitle(R.string.running_status_enable)
                runningStatusPref.setSummary(R.string.runtime_xposed)
            } else {
                runningStatusPref.setTitle(R.string.running_status_disable)
                runningStatusPref.setSummary(R.string.not_running_summary)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
            if (preference.key == "hide_icon") {
                val isShow = newValue as Boolean
                val aliasName = ComponentName(activity, MainActivity::class.java.name + "Alias")
                val status = if (isShow) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                }
                if (activity.packageManager.getComponentEnabledSetting(aliasName) != status) {
                    activity.packageManager.setComponentEnabledSetting(
                        aliasName,
                        status,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
            return true
        }

        @Deprecated("Deprecated in Java")
        override fun onPreferenceClick(preference: Preference?) = when (preference?.key) {
            "setting" -> onSettingClick()
            else -> false
        }

        private fun onSettingClick(): Boolean {
            val packages = Constant.BILIBILI_PACKAGE_NAMES.filter { isPackageInstalled(it) }
            when {
                packages.size == 1 -> startSetting(packages.first())
                packages.isEmpty() -> Toast.makeText(activity, "未检测到已安装的客户端", Toast.LENGTH_LONG).show()
                else -> {
                    AlertDialog.Builder(activity).run {
                        setItems(packages.toTypedArray()) { _, i -> startSetting(packages[i]) }
                        setTitle("请选择版本")
                        show()
                    }
                }
            }
            return true
        }

        private fun isPackageInstalled(packageName: String) = try {
            activity.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        private fun startSetting(packageName: String) {
            activity.packageManager.getLaunchIntentForPackage(packageName)?.run {
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(ExtrasSettingHook.START_SETTING_KEY, true)
                startActivity(this)
            }
        }
    }

    companion object {
        fun isModuleActive(): Boolean = false
    }
}
