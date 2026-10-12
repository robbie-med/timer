package org.robbiemed.tick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.provider.AlarmClock
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.MotionEvent
import org.robbiemed.clock.core.Flex
import org.robbiemed.clock.core.Flex.axes
import org.robbiemed.clock.core.Stage
import org.robbiemed.clock.core.alpha
import org.robbiemed.clock.core.edgeToEdge
import org.robbiemed.clock.core.no
import org.robbiemed.clock.core.tick
import org.robbiemed.clock.core.tock
import org.robbiemed.clock.core.yes
import org.robbiemed.clock.ring.Gate
import org.robbiemed.clock.ring.Ringer
import java.util.Date
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

class MainActivity : Activity() {
    private lateinit var v: TickView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        edgeToEdge()
        v = TickView(this)
        setContentView(v)
        if (b == null) Gate.ask(this)
        handle(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }

    private fun handle(i: Intent?) {
        if (i?.action != AlarmClock.ACTION_SET_TIMER) return
        val s = i.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
        if (s > 0) { Timer.start(this, s * 1000L); if (i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) finish() }
    }

    override fun onResume() {
        super.onResume()
        Ringer.foreground = true
        Timer.pin(this)
        v.restyle()
        v.banner = Gate.missing(this)
        v.invalidate()
    }

    override fun onPause() { Ringer.foreground = false; super.onPause() }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        v.banner = Gate.missing(this); v.invalidate()
        if (v.banner != null) Gate.ask(this)
    }

    private class Chip(val label: String, val act: () -> Unit) { val r = RectF() }

    private inner class TickView(c: Context) : Stage(c, 2) {
        var banner: String? = null
        private var set = Timer.last(c)          // dial value, ms, when idle
        private var shown = 0f                   // eased arc fraction for idle dial (in hours)
        private val big = Flex.paint(sp(88f), 260f, 118f, pal.ink)
        private val hint = Flex.paint(sp(16f), 420f, 100f, pal.dim)
        private val chipT = Flex.paint(sp(16f), 560f, 105f, pal.ink)
        private val bannerT = Flex.paint(sp(14f), 520f, 100f, pal.bg)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val oval = RectF()
        private val bannerR = RectF()
        private var chips = listOf<Chip>()
        private var cx = 0f; private var cy = 0f; private var rad = 0f
        private var weight = 260f
        private var pressedChip: Chip? = null

        // Gesture state
        private var dialing = false; private var tapCenter = false
        private var lastAng = 0.0; private var acc = 0.0; private var base = 0L
        private var downX = 0f; private var downY = 0f

        private fun fmt(ms: Long, ceil: Boolean): String {
            val s = if (ceil) (ms + 999) / 1000 else ms / 1000
            val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
        }

        override fun paint(c: Canvas): Boolean {
            big.color = pal.ink; hint.color = pal.dim; chipT.color = pal.ink; bannerT.color = pal.bg
            val ctx = context
            val run = Timer.running(ctx)
            val paused = if (run == null) Timer.pausedLeft(ctx) else 0L
            val now = System.currentTimeMillis()
            val sweepStroke = dp(16f)
            cx = width / 2f; cy = height * 0.43f
            rad = min(width * 0.40f, height * 0.28f)
            oval.set(cx - rad, cy - rad, cx + rad, cy + rad)

            // Track
            track.strokeWidth = sweepStroke; track.color = pal.surface
            c.drawCircle(cx, cy, rad, track)

            val label: String; val sub: String; var wTarget: Float; var textAlpha = 1f
            when {
                run != null -> {
                    val left = (run.at - now).coerceAtLeast(0)
                    val frac = if (run.span > 0) left.toFloat() / run.span else 0f
                    drawArc(c, frac, pal.accent, sweepStroke, glow = true)
                    label = fmt(left, true)
                    sub = "ends " + DateFormat.getTimeFormat(ctx).format(Date(run.at))
                    wTarget = 200f + 160f * (1f - frac)   // letters thicken as time runs out
                    chips = listOf(
                        Chip("+1 min") { Timer.add(ctx, 60_000) },
                        Chip("+5 min") { Timer.add(ctx, 300_000) },
                        Chip("Reset") { Timer.reset(ctx); set = Timer.span(ctx) },
                    )
                }
                paused > 0 -> {
                    val span = Timer.span(ctx).coerceAtLeast(paused)
                    drawArc(c, paused.toFloat() / span, pal.faint, sweepStroke, glow = false)
                    label = fmt(paused, true)
                    sub = "paused · tap to resume"
                    wTarget = 200f
                    textAlpha = 0.55f + 0.45f * (0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 380f))
                    chips = listOf(
                        Chip("+1 min") { Timer.add(ctx, 60_000) },
                        Chip("Reset") { Timer.reset(ctx); set = Timer.span(ctx) },
                    )
                }
                else -> {
                    val hours = set / 3_600_000f
                    shown = ease(shown, hours, 22f)
                    val whole = shown.toInt()
                    // Each completed hour becomes a thin inner ring.
                    for (i in 0 until min(whole, 6)) {
                        track.strokeWidth = dp(3f); track.color = alpha(pal.accent, 0.6f)
                        c.drawCircle(cx, cy, rad - sweepStroke - dp(8f) - i * dp(9f), track)
                    }
                    drawArc(c, shown - whole, pal.accent, sweepStroke, glow = dialing)
                    // Handle
                    val a = (shown - whole) * 2 * PI - PI / 2
                    val hx = cx + rad * kotlin.math.cos(a).toFloat(); val hy = cy + rad * kotlin.math.sin(a).toFloat()
                    fill.color = pal.accent; c.drawCircle(hx, hy, dp(if (dialing) 20f else 15f), fill)
                    fill.color = pal.bg; c.drawCircle(hx, hy, dp(5f), fill)
                    label = fmt(set, false)
                    sub = if (set == 0L) "drag around the ring" else "tap to start"
                    wTarget = if (dialing) 420f else 260f
                    if (set == 0L) textAlpha = 0.4f
                    chips = Timer.recents(ctx).map { ms -> Chip(short(ms)) { set = ms; Timer.start(ctx, ms) } }
                }
            }

            weight = ease(weight, wTarget, 10f)
            big.axes(weight, 118f)
            big.alpha = (255 * textAlpha).toInt()
            c.text(label, cx, cy + big.textSize * 0.35f, big, Paint.Align.CENTER)
            c.text(sub, cx, cy + big.textSize * 0.35f + dp(40f), hint, Paint.Align.CENTER)

            drawChips(c)
            drawBanner(c)
            return true
        }

        private fun short(ms: Long): String {
            val s = ms / 1000
            return when { s % 3600 == 0L -> "${s / 3600} h"; s % 60 == 0L -> "${s / 60} min"; s < 60 -> "$s s"; else -> "%d:%02d".format(s / 60, s % 60) }
        }

        private fun drawArc(c: Canvas, frac: Float, color: Int, sw: Float, glow: Boolean) {
            val sweep = 360f * frac.coerceIn(0f, 1f)
            if (sweep <= 0.1f) return
            if (glow) {
                arc.strokeWidth = sw * 2.6f; arc.color = alpha(color, 0.12f)
                c.drawArc(oval, -90f, sweep, false, arc)
            }
            arc.strokeWidth = sw; arc.color = color
            c.drawArc(oval, -90f, sweep, false, arc)
        }

        private fun drawChips(c: Canvas) {
            val h = dp(52f); val gap = dp(10f); val padX = dp(20f)
            val y = height - insetBottom - dp(40f) - h
            val widths = chips.map { chipT.measureText(it.label) + 2 * padX }
            var total = widths.sum() + gap * (chips.size - 1)
            val scale = if (total > width - dp(32f)) (width - dp(32f)) / total else 1f
            total *= scale
            var x = (width - total) / 2f
            chips.forEachIndexed { i, ch ->
                val w = widths[i] * scale
                ch.r.set(x, y, x + w, y + h)
                fill.color = if (ch.label == pressedChip?.label) pal.raised else pal.surface
                c.drawRoundRect(ch.r, h / 2, h / 2, fill)
                c.text(ch.label, ch.r.centerX(), ch.r.centerY() + chipT.textSize * 0.36f, chipT, Paint.Align.CENTER)
                x += w + gap
            }
        }

        private fun drawBanner(c: Canvas) {
            val b = banner ?: return bannerR.setEmpty()
            val w = bannerT.measureText(b) + dp(40f); val h = dp(40f)
            bannerR.set((width - w) / 2, insetTop + dp(16f), (width + w) / 2, insetTop + dp(16f) + h)
            fill.color = pal.accent
            c.drawRoundRect(bannerR, h / 2, h / 2, fill)
            c.text(b, bannerR.centerX(), bannerR.centerY() + bannerT.textSize * 0.36f, bannerT, Paint.Align.CENTER)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (themeTap(e)) return true
            if (e.actionMasked == MotionEvent.ACTION_DOWN && inThemeDot(e.x, e.y)) return true
            val ctx = context
            val idle = Timer.running(ctx) == null && Timer.pausedLeft(ctx) == 0L
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    pressedChip = chips.firstOrNull { it.r.contains(e.x, e.y) }
                    if (pressedChip != null || bannerR.contains(e.x, e.y)) return true
                    val d = hypot(e.x - cx, e.y - cy)
                    tapCenter = d < rad * 0.62f
                    if (idle && d > rad * 0.5f && d < rad * 1.6f) {
                        dialing = true; acc = 0.0; base = set
                        lastAng = atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.x - downX, e.y - downY) > dp(12f)) { tapCenter = false; if (pressedChip?.r?.contains(e.x, e.y) == false) pressedChip = null }
                    if (dialing) {
                        val a = atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())
                        var d = a - lastAng
                        if (d > PI) d -= 2 * PI; if (d < -PI) d += 2 * PI
                        acc += d; lastAng = a
                        val raw = base + acc / (2 * PI) * 3_600_000.0
                        val next = ((raw / 60_000.0).roundToLong() * 60_000L).coerceIn(0L, 23 * 3_600_000L + 59 * 60_000L)
                        if (next != set) {
                            if ((next / 60_000) % 5 == 0L) tock() else tick()
                            if (next == 0L && set > 0) no()
                            set = next
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val chip = pressedChip
                    when {
                        chip != null && chip.r.contains(e.x, e.y) -> { yes(); chip.act() }
                        bannerR.contains(e.x, e.y) && bannerR.contains(downX, downY) -> Gate.ask(this@MainActivity)
                        tapCenter -> {
                            val run = Timer.running(ctx)
                            when {
                                run != null -> { tock(); Timer.pause(ctx) }
                                Timer.pausedLeft(ctx) > 0 -> { yes(); Timer.resume(ctx) }
                                set > 0 -> { yes(); Timer.start(ctx, set) }
                            }
                        }
                    }
                    dialing = false; tapCenter = false; pressedChip = null
                }
                MotionEvent.ACTION_CANCEL -> { dialing = false; tapCenter = false; pressedChip = null }
            }
            return true
        }
    }
}
