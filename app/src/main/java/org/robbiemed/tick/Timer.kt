package org.robbiemed.tick

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.robbiemed.clock.core.Palette
import org.robbiemed.clock.core.store
import org.robbiemed.clock.ring.Ring
import org.robbiemed.clock.ring.Ringer

class TickApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Ringer.onFired = { c, _ -> Timer.unpin(c) }
    }
}

/** The single timer: either idle, running (a scheduled Ring) or paused (milliseconds left, in prefs). */
object Timer {
    private const val ID = 1
    private const val PIN = 3

    private fun p(c: Context) = c.store("tick")

    fun running(c: Context): Ring? = Ringer.get(c, ID)?.takeIf { it.at > System.currentTimeMillis() - 1000 }
    fun pausedLeft(c: Context) = p(c).getLong("paused", 0L)
    fun span(c: Context) = p(c).getLong("span", 0L)
    fun last(c: Context) = p(c).getLong("last", 5 * 60_000L)

    fun recents(c: Context): List<Long> =
        (p(c).getString("recents", null) ?: "60000,180000,300000,600000,1500000")
            .split(',').mapNotNull { it.toLongOrNull() }

    private fun words(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return listOfNotNull(
            if (h > 0) "$h h" else null, if (m > 0) "$m min" else null, if (r > 0) "$r s" else null
        ).joinToString(" ").ifEmpty { "0 s" }
    }

    fun start(c: Context, ms: Long, span: Long = ms, remember: Boolean = true) {
        val at = System.currentTimeMillis() + ms
        Ringer.schedule(c, Ring(ID, at, "Timer", words(span), 1, "+1 min", ID, 2, false, span))
        val e = p(c).edit().putLong("paused", 0).putLong("span", span)
        if (remember) {
            e.putLong("last", span)
            val r = (listOf(span) + recents(c).filter { it != span }).take(5).sorted()
            e.putString("recents", r.joinToString(","))
        }
        e.apply()
        pin(c)
    }

    fun pause(c: Context) {
        val r = running(c) ?: return
        Ringer.cancel(c, ID)
        p(c).edit().putLong("paused", (r.at - System.currentTimeMillis()).coerceAtLeast(1000)).putLong("span", r.span).apply()
        unpin(c)
    }

    fun resume(c: Context) {
        val left = pausedLeft(c)
        if (left > 0) start(c, left, span(c), remember = false)
    }

    fun add(c: Context, ms: Long) {
        val r = running(c)
        if (r != null) {
            Ringer.schedule(c, r.copy(at = r.at + ms, span = r.span + ms)); pin(c)
        } else if (pausedLeft(c) > 0) {
            p(c).edit().putLong("paused", pausedLeft(c) + ms).putLong("span", span(c) + ms).apply()
        }
    }

    fun reset(c: Context) {
        Ringer.cancel(c, ID)
        p(c).edit().putLong("paused", 0).apply()
        unpin(c)
    }

    /** Ongoing countdown notification; on Android 16+ it is promoted to a live status-bar chip. */
    fun pin(c: Context) {
        val r = running(c) ?: return unpin(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("count", "Running timer", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, "count")
            .setSmallIcon(org.robbiemed.clock.ring.R.drawable.clock_ring)
            .setColor(Palette(c, 2).accent)
            .setContentTitle("Timer · " + words(r.span))
            .setContentText("Ends at " + android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(r.at)))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(r.at)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(open)
        b.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        runCatching { nm.notify(PIN, b.build()) }
    }

    fun unpin(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(PIN)
}
