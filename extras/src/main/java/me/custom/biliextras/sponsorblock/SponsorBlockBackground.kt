package me.custom.biliextras.sponsorblock

import java.util.concurrent.Executors

/** 单线程后台任务，避免频繁创建短生命周期线程。 */
object SponsorBlockBackground {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BiliExtrasSponsorBlock").apply { isDaemon = true }
    }
    private val fetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BiliExtrasSponsorBlockFetch").apply { isDaemon = true }
    }

    fun submit(block: () -> Unit) {
        executor.execute(block)
    }

    /** Network fetch only — not blocked by vote/submit tasks on [executor]. */
    fun submitFetch(block: () -> Unit) {
        fetchExecutor.execute(block)
    }
}
