package org.robbiemed.clock.ring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.robbiemed.clock.core.store

/**
 * One thing that will ring. Persisted in device-protected storage so it can be re-armed after a reboot,
 * even before the phone is unlocked.
 */
data class Ring(
    val id: Int,
    val at: Long,
    val title: String,
    val sub: String = "",
    val againMin: Int = 0,
    val againLabel: String = "",
    val againId: Int = id,
    val rampSec: Int = 0,
    val clock: Boolean = false,
    val span: Long = 0,
) {
    fun encode() = listOf(id, at, againMin, againId, rampSec, if (clock) 1 else 0, span, againLabel, title, sub)
        .joinToString("\u001f")

    companion object {
        fun decode(s: String?): Ring? = runCatching {
            val p = s!!.split('\u001f')
            Ring(p[0].toInt(), p[1].toLong(), p[8], p[9], p[2].toInt(), p[7], p[3].toInt(), p[4].toInt(), p[5] == "1", p[6].toLong())
        }.getOrNull()
    }
}

/** Accent family per app: World = 1, Timer = 2, Alarm = 3. */
fun family(c: Context) = when { c.packageName.endsWith("wake") -> 3; c.packageName.endsWith("tick") -> 2; else -> 1 }

object Ringer {
    /** Called (in the receiver) right after a ring fires. Apps use it to re-plan repeating alarms. */
    var onFired: ((Context, Ring) -> Unit)? = null
    /** Called after boot / clock / zone changes. Default re-arms every stored ring. */
    var onReschedule: ((Context) -> Unit)? = null
    /** True while one of the app's own screens is visible: the ring screen can then open directly. */
    @Volatile var foreground = false

    private fun prefs(c: Context) = c.store("rings")

    fun get(c: Context, id: Int): Ring? = Ring.decode(prefs(c).getString("r$id", null))
    fun all(c: Context): List<Ring> = prefs(c).all.values.mapNotNull { Ring.decode(it as? String) }

    fun schedule(c: Context, r: Ring) {
        prefs(c).edit().putString("r${r.id}", r.encode()).commit()
        arm(c, r)
    }

    fun cancel(c: Context, id: Int) {
        prefs(c).edit().remove("r$id").commit()
        c.getSystemService(AlarmManager::class.java).cancel(firePi(c, id))
    }

    internal fun take(c: Context, id: Int): Ring? = get(c, id)?.also { prefs(c).edit().remove("r$id").commit() }

    fun rearmAll(c: Context) {
        val now = System.currentTimeMillis()
        for (r in all(c)) if (r.at > now - 5 * 60_000) arm(c, r) else cancel(c, r.id)
    }

    private fun firePi(c: Context, id: Int) = PendingIntent.getBroadcast(
        c, id, Intent(c, FireReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun arm(c: Context, r: Ring) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = firePi(c, r.id)
        try {
            if (r.clock) {
                val open = c.packageManager.getLaunchIntentForPackage(c.packageName)
                val show = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(r.at, show), pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        }
    }
}

class FireReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val r = Ringer.take(c, i.getIntExtra("id", -1)) ?: return
        c.startForegroundService(RingService.intent(c, r))
        runCatching { Ringer.onFired?.invoke(c, r) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val hook = Ringer.onReschedule
        if (hook != null) hook(c) else Ringer.rearmAll(c)
    }
}
