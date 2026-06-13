package me.custom.biliextras.sponsorblock

import android.content.Context
import me.custom.biliextras.utils.ePrefs
import java.util.ArrayList

object SponsorBlockSettingsSubMenu {

    fun rowCount(): Int = 8 + SponsorBlockCategory.all.size

    fun appendRows(
        rows: MutableList<Any>,
        factory: SponsorBlockMenuUiFactory,
        context: Context,
        nextType: () -> Any,
    ) {
        rows.add(
            factory.createInfoRow(
                title = "服务信息",
                subtitle = serviceInfoText(),
                icon = "playdata-square-line@500",
                videoSettingType = nextType(),
            ),
        )
        rows.add(
            factory.createSettingsSwitchRow(
                title = "显示跳过 Toast",
                icon = "text-square-line@500",
                enabled = SponsorBlockPrefs.showToast,
                toggleActionId = SponsorBlockMenuActionHandler.ACTION_TOGGLE_SHOW_TOAST,
                videoSettingType = nextType(),
            ),
        )
        rows.add(
            factory.createSettingsSwitchRow(
                title = "显示进度条片段",
                icon = "playtime-square-line@500",
                enabled = SponsorBlockPrefs.showProgress,
                toggleActionId = SponsorBlockMenuActionHandler.ACTION_TOGGLE_SHOW_PROGRESS,
                videoSettingType = nextType(),
            ),
        )
        rows.add(
            factory.createSettingsSwitchRow(
                title = "跳过次数统计",
                icon = "ranking-square-line@500",
                enabled = SponsorBlockPrefs.trackStats,
                toggleActionId = SponsorBlockMenuActionHandler.ACTION_TOGGLE_TRACK_STATS,
                videoSettingType = nextType(),
            ),
        )
        rows.add(
            factory.createActionRow(
                title = "最短片段时长",
                icon = "clock-timeoff-line@500",
                subtitle = "${SponsorBlockPrefs.blockLimit}s",
                withArrow = true,
                videoSettingType = nextType(),
                actionId = SponsorBlockMenuActionHandler.ACTION_EDIT_BLOCK_LIMIT,
                context = context,
            ),
        )
        rows.add(
            factory.createActionRow(
                title = "用户ID",
                icon = "person-user-info-line@500",
                subtitle = SponsorBlockPrefs.userId.let { id ->
                    if (id.length > 12) id.take(12) + "…" else id
                },
                withArrow = true,
                videoSettingType = nextType(),
                actionId = SponsorBlockMenuActionHandler.ACTION_EDIT_USER_ID,
                context = context,
            ),
        )
        rows.add(
            factory.createActionRow(
                title = "服务器地址",
                icon = "arrow-download-down-line@500",
                subtitle = SponsorBlockPrefs.server,
                withArrow = true,
                videoSettingType = nextType(),
                actionId = SponsorBlockMenuActionHandler.ACTION_EDIT_SERVER,
                context = context,
            ),
        )
        rows.add(
            factory.createActionRow(
                title = "检测服务",
                icon = "arrow-refresh-line@500",
                subtitle = "当前：${SponsorBlockPrefs.status}",
                withArrow = false,
                videoSettingType = nextType(),
                actionId = SponsorBlockMenuActionHandler.ACTION_CHECK_SERVICE,
                context = context,
            ),
        )
        SponsorBlockCategory.all.forEachIndexed { index, category ->
            rows.add(
                factory.createActionRow(
                    title = category.title,
                    icon = "skip-beginning-end-line@500",
                    subtitle = SponsorBlockPrefs.modeOf(category.id).title,
                    withArrow = true,
                    videoSettingType = nextType(),
                    actionId = SponsorBlockMenuActionHandler.ACTION_OPEN_CATEGORY,
                    context = context,
                    segmentIndex = index,
                ),
            )
        }
    }

    fun serviceInfoText(): String = buildString {
        append("状态：${SponsorBlockPrefs.status}")
        append(" · 跳过 ${ePrefs.getInt(SponsorBlockPrefs.KEY_SKIP_COUNT, 0)} 次")
        append(" · 节省 ${ePrefs.getLong(SponsorBlockPrefs.KEY_SAVED_SECONDS, 0L)} 秒")
    }
}
