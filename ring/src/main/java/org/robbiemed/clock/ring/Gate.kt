package org.robbiemed.clock.ring

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** The two things a sideloaded ringing app needs from the user. Returns a banner line, or null if all good. */
object Gate {
    fun missing(a: Activity): String? = when {
        a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> "Allow notifications so it can ring"
        !a.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() -> "Allow full-screen ringing"
        else -> null
    }

    fun ask(a: Activity) {
        if (a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else if (!a.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            runCatching {
                a.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + a.packageName)))
            }
        }
    }
}
