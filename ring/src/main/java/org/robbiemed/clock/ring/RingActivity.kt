package org.robbiemed.clock.ring

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import org.robbiemed.clock.core.Flex
import org.robbiemed.clock.core.Flex.axes
import org.robbiemed.clock.core.Stage
import org.robbiemed.clock.core.alpha
import org.robbiemed.clock.core.clock
import org.robbiemed.clock.core.edgeToEdge
import org.robbiemed.clock.core.yes
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin

class RingActivity : Activity() {
    private lateinit var v: RingView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        edgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        v = RingView(this)
        setContentView(v)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { RingService.mute() }
        RingService.listener = { runOnUiThread { sync() } }
        if (RingService.current == null) v.postDelayed({ sync() }, 1500) else sync()
    }

    private fun sync() {
        val r = RingService.current
        if (r == null) { if (!isFinishing) finish(); return }
        v.ring = r
    }

    override fun onDestroy() {
        if (RingService.listener != null) RingService.listener = null
        super.onDestroy()
    }

    override fun onKeyDown(code: Int, e: KeyEvent?): Boolean {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) { RingService.mute(); return true }
        return super.onKeyDown(code, e)
    }

    private inner class RingView(c: Context) : Stage(c, family(c)) {
        var ring: Ring? = null
        private val big = Flex.paint(sp(104f), 250f, 120f, pal.ink)
        private val small = Flex.paint(sp(16f), 520f, 112f, pal.accent).apply { letterSpacing = 0.22f }
        private val sub = Flex.paint(sp(18f), 380f, 100f, pal.dim)
        private val btn = Flex.paint(sp(20f), 560f, 110f, pal.bg)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val stopR = RectF(); private val againR = RectF()
        private var press = 0; private var pressT = 0f
        private val t0 = SystemClock.uptimeMillis()

        override val themeDot = false

        override fun paint(c: Canvas): Boolean {
            val r = ring ?: return true
            big.color = pal.ink; small.color = pal.accent; sub.color = pal.dim
            val t = (SystemClock.uptimeMillis() - t0) / 1000f
            val cx = width / 2f; val cy = height * 0.42f

            // Ripples: three rings swelling outward, out of phase.
            val r0 = width * 0.40f; val maxR = height * 0.5f
            stroke.color = pal.accent
            for (k in 0 until 4) {
                val p = ((t / 3.2f) + k / 4f) % 1f
                stroke.strokeWidth = dp(1f) + dp(14f) * (1f - p)
                stroke.alpha = (110 * (1f - p) * (1f - p)).toInt()
                c.drawCircle(cx, cy, r0 + maxR * p, stroke)
            }
            stroke.strokeWidth = dp(2f); stroke.alpha = 255
            c.drawCircle(cx, cy, r0 + dp(4f) * sin(t * 2.4f), stroke)

            // Time, breathing in weight.
            val cal = Calendar.getInstance()
            val (hm, ap) = context.clock(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            big.axes(250f + 110f * (0.5f + 0.5f * sin(t * PI.toFloat() / 1.3f)), 120f)
            c.text(r.title.uppercase(), cx, cy - big.textSize * 0.62f - dp(18f), small, Paint.Align.CENTER)
            c.text(hm, cx, cy + big.textSize * 0.36f, big, Paint.Align.CENTER)
            if (ap.isNotEmpty()) c.text(ap, cx + big.measureText(hm) / 2f + dp(6f), cy + big.textSize * 0.36f, sub)
            var line = r.sub
            if (!r.clock) {
                val over = ((SystemClock.uptimeMillis() - RingService.startedAt) / 1000).coerceAtLeast(0)
                line = (if (line.isEmpty()) "" else "$line  ·  ") + "+%d:%02d".format(over / 60, over % 60)
            }
            if (line.isNotEmpty()) c.text(line, cx, cy + big.textSize * 0.36f + dp(44f), sub, Paint.Align.CENTER)

            // Buttons.
            pressT = ease(pressT, if (press != 0) 1f else 0f, 24f)
            val bh = dp(76f); val gap = dp(12f); val side = dp(24f)
            val bottom = height - insetBottom - dp(36f)
            val hasAgain = r.againMin > 0
            if (hasAgain) {
                val w = (width - 2 * side - gap) / 2f
                againR.set(side, bottom - bh, side + w, bottom)
                stopR.set(side + w + gap, bottom - bh, width - side, bottom)
            } else {
                stopR.set(side, bottom - bh, width - side, bottom); againR.setEmpty()
            }
            fun pill(rect: RectF, which: Int, filled: Boolean, label: String) {
                val s = if (press == which) 1f - 0.04f * pressT else 1f
                c.save(); c.scale(s, s, rect.centerX(), rect.centerY())
                if (filled) { fill.color = pal.accent; c.drawRoundRect(rect, bh / 2, bh / 2, fill); btn.color = pal.bg }
                else {
                    fill.color = pal.surface; c.drawRoundRect(rect, bh / 2, bh / 2, fill); btn.color = pal.ink
                }
                c.text(label, rect.centerX(), rect.centerY() + btn.textSize * 0.36f, btn, Paint.Align.CENTER)
                c.restore()
            }
            if (hasAgain) pill(againR, 2, false, r.againLabel)
            pill(stopR, 1, true, "Stop")
            return true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val hit = when {
                stopR.contains(e.x, e.y) -> 1
                againR.contains(e.x, e.y) -> 2
                else -> 0
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> press = hit
                MotionEvent.ACTION_MOVE -> if (hit != press) press = 0
                MotionEvent.ACTION_UP -> {
                    if (press != 0 && hit == press) {
                        yes()
                        if (press == 1) RingService.stop() else RingService.again()
                        finish()
                    }
                    press = 0
                }
                MotionEvent.ACTION_CANCEL -> press = 0
            }
            return true
        }
    }
}
