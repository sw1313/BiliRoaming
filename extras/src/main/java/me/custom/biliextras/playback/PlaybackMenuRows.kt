package me.custom.biliextras.playback

import android.content.Context
import me.custom.biliextras.hook.DisableChapterProgressHook
import me.custom.biliextras.hook.StoryBackgroundAutoNextHook
import me.custom.biliextras.sponsorblock.SponsorBlockMenuHost
import me.custom.biliextras.sponsorblock.SponsorBlockMenuUiFactory
import me.custom.biliextras.utils.ePrefs

enum class PlayerMenuKind {
    UGC,
    STORY
}

object PlaybackMenuRows {

    fun injectAfterSponsorBlock(
        factory: SponsorBlockMenuUiFactory,
        list: MutableList<Any>,
        context: Context,
        kind: PlayerMenuKind,
        startIndex: Int,
    ) {
        val fullscreenInline = SponsorBlockMenuHost.isFullscreenWidget
        val rows = when (kind) {
            PlayerMenuKind.UGC -> ugcRows(factory, context, fullscreenInline)
            PlayerMenuKind.STORY -> storyRows(factory, context, fullscreenInline)
        }
        rows.forEachIndexed { offset, row ->
            list.add(startIndex + offset, row)
        }
    }

    private fun ugcRows(
        factory: SponsorBlockMenuUiFactory,
        context: Context,
        fullscreenInline: Boolean,
    ): List<Any> {
        val count = 3
        var i = 0
        fun nextType() = factory.videoSettingTypeForIndex(i++, count)
        return listOf(
            factory.createPlaybackActionRow(
                title = "前台自动连播",
                icon = PlaybackMenuIcons.AUTO_NEXT,
                subtitle = PlaybackMenuActions.foregroundAutoNextStatusText(),
                withArrow = true,
                videoSettingType = nextType(),
                actionId = PlaybackMenuActionHandler.OPEN_FOREGROUND_AUTO_NEXT,
                context = context,
                fullscreenInline = fullscreenInline,
            ),
            factory.createPlaybackSwitchRow(
                title = "媒体按键控制",
                icon = PlaybackMenuIcons.MEDIA_BUTTON,
                enabled = ePrefs.getBoolean(StoryBackgroundAutoNextHook.MEDIA_BUTTON_KEY, false),
                toggleActionId = PlaybackMenuActionHandler.TOGGLE_MEDIA_BUTTON,
                videoSettingType = nextType(),
                fullscreenInline = fullscreenInline,
            ),
            factory.createPlaybackSwitchRow(
                title = "关闭章节分段",
                icon = PlaybackMenuIcons.CHAPTER_SEGMENT,
                enabled = ePrefs.getBoolean(DisableChapterProgressHook.KEY_DISABLE_CHAPTER_PROGRESS, true),
                toggleActionId = PlaybackMenuActionHandler.TOGGLE_DISABLE_CHAPTER,
                videoSettingType = nextType(),
                fullscreenInline = fullscreenInline,
            ),
        )
    }

    private fun storyRows(
        factory: SponsorBlockMenuUiFactory,
        context: Context,
        fullscreenInline: Boolean,
    ): List<Any> {
        val count = 4
        var i = 0
        fun nextType() = factory.videoSettingTypeForIndex(i++, count)
        return listOf(
            factory.createPlaybackActionRow(
                title = "屏蔽当前UP",
                icon = PlaybackMenuIcons.BLOCK_UP,
                subtitle = "",
                withArrow = false,
                videoSettingType = nextType(),
                actionId = PlaybackMenuActionHandler.BLOCK_STORY_CURRENT_UP,
                context = context,
                fullscreenInline = fullscreenInline,
            ),
            factory.createPlaybackSwitchRow(
                title = "后台自动连播",
                icon = PlaybackMenuIcons.AUTO_NEXT,
                enabled = ePrefs.getBoolean(StoryBackgroundAutoNextHook.PREF_KEY, false),
                toggleActionId = PlaybackMenuActionHandler.TOGGLE_STORY_BACKGROUND,
                videoSettingType = nextType(),
                fullscreenInline = fullscreenInline,
            ),
            factory.createPlaybackSwitchRow(
                title = "媒体按键控制",
                icon = PlaybackMenuIcons.MEDIA_BUTTON,
                enabled = ePrefs.getBoolean(StoryBackgroundAutoNextHook.MEDIA_BUTTON_KEY, false),
                toggleActionId = PlaybackMenuActionHandler.TOGGLE_MEDIA_BUTTON,
                videoSettingType = nextType(),
                fullscreenInline = fullscreenInline,
            ),
            factory.createPlaybackSwitchRow(
                title = "关闭章节分段",
                icon = PlaybackMenuIcons.CHAPTER_SEGMENT,
                enabled = ePrefs.getBoolean(DisableChapterProgressHook.KEY_DISABLE_CHAPTER_PROGRESS, true),
                toggleActionId = PlaybackMenuActionHandler.TOGGLE_DISABLE_CHAPTER,
                videoSettingType = nextType(),
                fullscreenInline = fullscreenInline,
            ),
        )
    }
}
