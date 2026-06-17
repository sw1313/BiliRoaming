package me.custom.biliextras.playback

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import me.custom.biliextras.hook.ForegroundAutoNextHook
import me.custom.biliextras.hook.ForegroundAutoNextPrefs
import me.custom.biliextras.sponsorblock.SponsorBlockMenuUiFactory
import me.custom.biliextras.sponsorblock.SponsorBlockSubMenu
import me.custom.biliextras.sponsorblock.SponsorBlockVideoSettingDialog
import me.custom.biliextras.utils.Log
import me.custom.biliextras.utils.ePrefs
import java.util.ArrayList

object PlaybackSubMenu {

    fun showForegroundAutoNext(context: Context) {
        val activity = findActivity(context) ?: return
        runCatching {
            buildAndShowForegroundAutoNext(activity, context)
        }.onFailure {
            Log.w { "Playback submenu failed: ${it.message}" }
        }
    }

    fun showForegroundScopes(context: Context) {
        val activity = findActivity(context) ?: return
        val entries = ForegroundAutoNextPrefs.allEntries
        val titles = entries.map { it.title }.toTypedArray()
        val checked = entries.map { ForegroundAutoNextPrefs.isEnabled(it.key) }.toBooleanArray()
        AlertDialog.Builder(context)
            .setTitle("适用范围")
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
                showForegroundAutoNext(context)
            }
            .show()
    }

    fun showForegroundPrefs(context: Context) {
        val activity = findActivity(context) ?: return
        runCatching {
            buildAndShowForegroundPrefs(activity, context)
        }.onFailure {
            Log.w { "Playback prefs submenu failed: ${it.message}" }
        }
    }

    fun showForegroundOrientationPicker(context: Context) {
        val entries = ForegroundAutoNextPrefs.orientationEntries
        val titles = entries.map { it.title }.toTypedArray()
        val current = ForegroundAutoNextPrefs.orientation()
        AlertDialog.Builder(context)
            .setTitle("方向")
            .setSingleChoiceItems(titles, entries.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                ePrefs.edit()
                    .putString(ForegroundAutoNextPrefs.KEY_ORIENTATION, entries[which].value)
                    .commit()
                dialog.dismiss()
                showForegroundPrefs(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showForegroundUpPicker(context: Context) {
        val entries = ForegroundAutoNextPrefs.upEntries
        val titles = entries.map { it.title }.toTypedArray()
        val current = ForegroundAutoNextPrefs.upPref()
        AlertDialog.Builder(context)
            .setTitle("up")
            .setSingleChoiceItems(titles, entries.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                ePrefs.edit()
                    .putString(ForegroundAutoNextPrefs.KEY_UP, entries[which].value)
                    .commit()
                dialog.dismiss()
                showForegroundPrefs(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showForegroundTagPicker(context: Context) {
        val entries = ForegroundAutoNextPrefs.tagEntries
        val titles = entries.map { it.title }.toTypedArray()
        val current = ForegroundAutoNextPrefs.tagPref()
        AlertDialog.Builder(context)
            .setTitle("tag")
            .setSingleChoiceItems(titles, entries.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                ePrefs.edit()
                    .putString(ForegroundAutoNextPrefs.KEY_TAG, entries[which].value)
                    .commit()
                dialog.dismiss()
                showForegroundPrefs(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildAndShowForegroundAutoNext(activity: Activity, context: Context) {
        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)
        val rows = ArrayList<Any>()
        val itemCount = 4
        var i = 0
        fun nextType() = factory.videoSettingTypeForIndex(i++, itemCount)

        rows.add(
            factory.createPlaybackSwitchRow(
                title = "前台自动连播",
                icon = PlaybackMenuIcons.AUTO_NEXT,
                enabled = ePrefs.getBoolean(ForegroundAutoNextHook.PREF_KEY, false),
                toggleActionId = PlaybackMenuActionHandler.TOGGLE_FOREGROUND_AUTO_NEXT,
                videoSettingType = nextType(),
            ),
        )
        rows.add(
            factory.createPlaybackActionRow(
                title = "适用范围",
                icon = PlaybackMenuIcons.SCOPES,
                subtitle = ForegroundAutoNextScopeSummary.scopesSubtitle(),
                withArrow = true,
                videoSettingType = nextType(),
                actionId = PlaybackMenuActionHandler.OPEN_FOREGROUND_SCOPES,
                context = context,
            ),
        )
        rows.add(
            factory.createPlaybackActionRow(
                title = "偏好",
                icon = PlaybackMenuIcons.PREFS,
                subtitle = ForegroundAutoNextScopeSummary.prefsSubtitle(),
                withArrow = true,
                videoSettingType = nextType(),
                actionId = PlaybackMenuActionHandler.OPEN_FOREGROUND_PREFS,
                context = context,
            ),
        )
        rows.add(factory.createSpacer(16))
        showDialog(activity, rows)
    }

    private fun buildAndShowForegroundPrefs(activity: Activity, context: Context) {
        SponsorBlockSubMenu.dismissActive()
        val factory = SponsorBlockMenuUiFactory.forClassLoader(activity.classLoader)
        val rows = ArrayList<Any>()
        val itemCount = 4
        var i = 0
        fun nextType() = factory.videoSettingTypeForIndex(i++, itemCount)

        rows.add(
            factory.createPlaybackActionRow(
                title = "方向",
                icon = PlaybackMenuIcons.ORIENTATION,
                subtitle = ForegroundAutoNextPrefs.orientation().title,
                withArrow = true,
                videoSettingType = nextType(),
                actionId = PlaybackMenuActionHandler.OPEN_FOREGROUND_ORIENTATION,
                context = context,
            ),
        )
        rows.add(
            factory.createPlaybackActionRow(
                title = "up",
                icon = PlaybackMenuIcons.UP,
                subtitle = ForegroundAutoNextPrefs.upPref().title,
                withArrow = true,
                videoSettingType = nextType(),
                actionId = PlaybackMenuActionHandler.OPEN_FOREGROUND_UP,
                context = context,
            ),
        )
        rows.add(
            factory.createPlaybackActionRow(
                title = "tag",
                icon = PlaybackMenuIcons.TAG,
                subtitle = ForegroundAutoNextPrefs.tagPref().title,
                withArrow = true,
                videoSettingType = nextType(),
                actionId = PlaybackMenuActionHandler.OPEN_FOREGROUND_TAG,
                context = context,
            ),
        )
        rows.add(factory.createSpacer(16))
        showDialog(activity, rows)
    }

    private fun showDialog(activity: Activity, rows: List<Any>) {
        SponsorBlockSubMenu.dismissActive()
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
