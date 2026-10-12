package org.robbiemed.clock.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import org.robbiemed.clock.core.Palette

/** Plays the alarm, vibrates, and keeps the ring alive (up to 10 minutes) as a foreground service. */
class RingService : Service() {
    companion object {
        @Volatile var current: Ring? = null; private set
        var startedAt = 0L; private set
        internal var listener: (() -> Unit)? = null
        private var instance: RingService? = null

        fun intent(c: Context, r: Ring) = Intent(c, RingService::class.java).putExtra("ring", r.encode())
        fun stop() { instance?.finish(false) }
        fun again() { instance?.finish(true) }
        fun mute() { instance?.hush() }

        private const val GIVE_UP_MS = 10 * 60_000L
    }

    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var wake: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private val alarmAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        instance = this
        when (i?.action) {
            "stop" -> { finish(false); return START_NOT_STICKY }
            "again" -> { finish(true); return START_NOT_STICKY }
        }
        val r = Ring.decode(i?.getStringExtra("ring")) ?: run { stopSelf(); return START_NOT_STICKY }
        silence()
        current = r; startedAt = SystemClock.uptimeMillis()
        goForeground(r)
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clock:ring").apply { acquire(GIVE_UP_MS + 5_000) }
        sound(r); buzz(r)
        h.postDelayed({ finish(false) }, GIVE_UP_MS)
        if (Ringer.foreground) runCatching { startActivity(ringScreen()) }
        listener?.invoke()
        return START_NOT_STICKY
    }

    private fun ringScreen() = Intent(this, RingActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    private fun goForeground(r: Ring) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ring", "Ringing", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        // When the app is already on screen the ring screen opens directly; a quiet channel avoids a
        // heads-up banner sitting on top of it.
        nm.createNotificationChannel(NotificationChannel("ring_open", "Ringing (app open)", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        val direct = Ringer.foreground
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val full = PendingIntent.getActivity(this, 1, ringScreen(), flags)
        fun act(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, RingService::class.java).setAction(a), flags)
        val b = Notification.Builder(this, if (direct) "ring_open" else "ring")
            .setSmallIcon(R.drawable.clock_ring)
            .setColor(Palette(this, family(this)).accent)
            .setContentTitle(r.title)
            .setContentText(r.sub.ifEmpty { null })
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(full)
        if (!direct) b.setFullScreenIntent(full, true)
        b
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", act("stop")).build())
        if (r.againMin > 0) b.addAction(Notification.Action.Builder(null, r.againLabel, act("again")).build())
        val n = b.build()
        try {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } catch (e: Exception) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }
    }

    private fun sound(r: Ring) {
        val am = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(alarmAttrs).build()
            .also { am.requestAudioFocus(it) }
        val candidates = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            Settings.System.DEFAULT_RINGTONE_URI,
        )
        for (u in candidates) {
            player = runCatching { play(u) }.getOrNull()
            if (player != null) break
        }
        if (player == null) beep()
        // Gentle wake: volume swells over rampSec (quadratic, so the first seconds are a whisper).
        val ramp = r.rampSec * 1000L
        val t0 = SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val p = player ?: return
                val t = if (ramp <= 0) 1f else ((SystemClock.uptimeMillis() - t0).toFloat() / ramp).coerceIn(0f, 1f)
                val v = 0.04f + 0.96f * t * t
                runCatching { p.setVolume(v, v) }
                if (t < 1f) h.postDelayed(this, 150)
            }
        }
        step.run()
    }

    private fun play(u: Uri) = MediaPlayer().apply {
        setAudioAttributes(alarmAttrs)
        setDataSource(this@RingService, u)
        isLooping = true
        prepare()
        start()
    }

    private val beeper = object : Runnable {
        override fun run() {
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
            h.postDelayed(this, 1100)
        }
    }

    private fun beep() {
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
        h.post(beeper)
    }

    private fun buzz(r: Ring) {
        val pattern = if (r.clock) longArrayOf(0, 600, 900) else longArrayOf(0, 120, 120, 120, 900)
        val amps = if (r.clock) intArrayOf(0, 180, 0) else intArrayOf(0, 255, 0, 255, 0)
        getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
            VibrationEffect.createWaveform(pattern, amps, 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        )
    }

    private fun silence() {
        h.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }; player = null
        tone?.release(); tone = null
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
        wake?.takeIf { it.isHeld }?.release(); wake = null
    }

    /** Mute the sound but keep the ring screen (volume keys). */
    fun hush() {
        player?.runCatching { setVolume(0f, 0f) }
        h.removeCallbacks(beeper)
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
    }

    internal fun finish(again: Boolean) {
        val r = current
        if (again && r != null && r.againMin > 0) {
            Ringer.schedule(this, r.copy(
                id = r.againId, at = System.currentTimeMillis() + r.againMin * 60_000L,
                span = r.againMin * 60_000L,
            ))
        }
        silence()
        current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        listener?.invoke()
    }

    override fun onDestroy() {
        silence(); current = null; instance = null
        listener?.invoke()
        super.onDestroy()
    }

}
