package me.custom.biliextras.sponsorblock

import android.content.Context
import android.os.Handler
import android.os.Looper

object SponsorBlockSubmitDialog {
    fun show(context: Context) {
        Handler(Looper.getMainLooper()).post {
            SponsorBlockSubMenu.dismissActive()
            SponsorBlockSubmitSubMenu.show(context)
        }
    }
}
