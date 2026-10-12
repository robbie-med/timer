package org.robbiemed.clock.core

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import java.io.File
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Roboto Flex, the variable font every Pixel ships. Weight, width and optical size are live axes,
 * so the UI can breathe (state changes animate the letterforms, not just colours).
 */
object Flex {
    private val file = File("/system/fonts/RobotoFlex-Regular.ttf")
    private val cache = HashMap<String, Typeface>()
    private val ok by lazy { file.canRead() }

    fun face(wght: Float, wdth: Float = 100f, opsz: Float = 144f): Typeface {
        val w = (wght.coerceIn(100f, 1000f) / 10f).roundToInt() * 10
        val d = wdth.coerceIn(25f, 151f).roundToInt()
        val o = opsz.coerceIn(8f, 144f).roundToInt()
        val key = "'wght' $w, 'wdth' $d, 'opsz' $o"
        return cache.getOrPut(key) {
            val flex = if (ok) runCatching { Typeface.Builder(file).setFontVariationSettings(key).build() }.getOrNull() else null
            flex ?: Typeface.create(Typeface.DEFAULT, w, false)
        }
    }

    fun paint(sizePx: Float, wght: Float, wdth: Float = 100f, color: Int = Color.WHITE) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = sizePx; this.color = color
            typeface = face(wght, wdth, (sizePx / 3f))
            fontFeatureSettings = "'tnum'"
        }

    fun Paint.axes(wght: Float, wdth: Float = 100f) {
        typeface = face(wght, wdth, textSize / 3f)
    }
}

/**
 * Selectable looks. "You" follows the wallpaper (Material You); "Night" is AMOLED black with red only,
 * for dark rooms; "Auto" is You by day and Night from 21:00 to 07:00.
 */
object Themes {
    val names = listOf("You", "Night", "Auto", "Mono", "Ember", "Glacier", "Paper")
    private fun p(c: Context) = c.store("look")
    fun chosen(c: Context): String = p(c).getString("theme", "You") ?: "You"
    fun next(c: Context): String {
        val n = names[(names.indexOf(chosen(c)) + 1) % names.size]
        p(c).edit().putString("theme", n).apply()
        return n
    }
    fun resolve(c: Context): String = when (val n = chosen(c)) {
        "Auto" -> java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { if (it >= 21 || it < 7) "Night" else "You" }
        else -> n
    }
}

class Palette(
    val light: Boolean, val bg: Int, val ink: Int, val dim: Int, val faint: Int, val surface: Int, val raised: Int,
    val soft: Int, val accent: Int, val strong: Int, val deep: Int,
) {
    companion object {
        operator fun invoke(c: Context, family: Int): Palette = of(c, Themes.resolve(c), family)

        private fun hex(vararg v: Long) = v.map { it.toInt() }

        fun of(c: Context, theme: String, family: Int): Palette {
            fun col(id: Int) = c.getColor(id)
            val k = when (theme) {
                "Night" -> hex(0xFF000000, 0xFFE0261C, 0xFF8C1710, 0xFF4A0C08, 0xFF120202, 0xFF210404, 0xFFFF5A4F, 0xFFFF2A1F, 0xFFB8160E, 0xFF2E0402)
                "Mono" -> hex(0xFF000000, 0xFFFFFFFF, 0xFF8E8E93, 0xFF3A3A3C, 0xFF111111, 0xFF1F1F1F, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFCCCCCC, 0xFF000000)
                "Ember" -> hex(0xFF000000, 0xFFFFEBDC, 0xFFA88B74, 0xFF4D3B2E, 0xFF16100B, 0xFF241A12, 0xFFFFC48A, 0xFFFF9F43, 0xFFE07A1F, 0xFF3D2410)
                "Glacier" -> hex(0xFF03060B, 0xFFEAF4FF, 0xFF7D93AB, 0xFF2A3646, 0xFF0C131D, 0xFF17212E, 0xFFBDE6FF, 0xFF7FD1FF, 0xFF3FA9E8, 0xFF0B2A40)
                "Paper" -> hex(0xFFF3EFE6, 0xFF151412, 0xFF6B665D, 0xFFB9B3A6, 0xFFE6E0D3, 0xFFD9D2C3, 0xFFF08A63, 0xFFE4572E, 0xFFC2421D, 0xFFFFF3EC)
                else -> {
                    val a = when (family) {
                        2 -> intArrayOf(android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_400, android.R.color.system_accent2_800)
                        3 -> intArrayOf(android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_400, android.R.color.system_accent3_800)
                        else -> intArrayOf(android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_400, android.R.color.system_accent1_800)
                    }
                    listOf(Color.BLACK, col(android.R.color.system_neutral1_10), col(android.R.color.system_neutral2_400),
                        col(android.R.color.system_neutral2_700), col(android.R.color.system_neutral1_900), col(android.R.color.system_neutral1_800),
                        col(a[0]), col(a[1]), col(a[2]), col(a[3]))
                }
            }
            return Palette(theme == "Paper", k[0], k[1], k[2], k[3], k[4], k[5], k[6], k[7], k[8], k[9])
        }
    }
}

fun Context.dp(v: Float) = v * resources.displayMetrics.density

/** Device-protected storage: readable before first unlock, so alarms survive overnight reboots. */
fun Context.store(name: String): SharedPreferences =
    createDeviceProtectedStorageContext().getSharedPreferences(name, Context.MODE_PRIVATE)

fun Context.is24() = DateFormat.is24HourFormat(this)

/** "9:41" + "pm" (or "21:41" + ""), so the suffix can be typeset small. */
fun Context.clock(h: Int, m: Int): Pair<String, String> =
    if (is24()) "%d:%02d".format(h, m) to ""
    else "%d:%02d".format(if (h % 12 == 0) 12 else h % 12, m) to (if (h < 12) "am" else "pm")

fun View.tick() = performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
fun View.tock() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
fun View.yes() = performHapticFeedback(HapticFeedbackConstants.CONFIRM)
fun View.no() = performHapticFeedback(HapticFeedbackConstants.REJECT)

fun Activity.edgeToEdge() {
    window.decorView
    window.setDecorFitsSystemWindows(false)
    window.insetsController?.setSystemBarsAppearance(
        0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    )
}

/** Base canvas view: knows its system-bar insets and owns a frame clock for smooth animation. */
abstract class Stage(c: Context, private val family: Int) : View(c) {
    var pal = Palette(c, family); private set
    private val themeR = android.graphics.RectF()
    private var flashName = ""; private var flashAt = 0L
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashP by lazy { Flex.paint(sp(15f), 560f, 125f).apply { letterSpacing = 0.2f } }
    var insetTop = 0f; private set
    var insetBottom = 0f; private set
    private var lastFrame = 0L
    protected var dt = 0f; private set

    init { isHapticFeedbackEnabled = true }

    fun dp(v: Float) = v * resources.displayMetrics.density
    fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        insetTop = i.top.toFloat(); insetBottom = i.bottom.toFloat()
        invalidate()
        return insets
    }

    final override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        canvas.drawColor(pal.bg)
        var more = paint(canvas)
        more = drawThemeDot(canvas) || more
        if (more) postInvalidateOnAnimation() else lastFrame = 0L
    }

    /** Re-read the chosen theme (call from onResume; Auto may have flipped). */
    fun restyle() {
        pal = Palette(context, family)
        (context as? Activity)?.window?.insetsController?.setSystemBarsAppearance(
            if (pal.light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restyle() }

    /** Where the theme dot sits (top-right by default). */
    open fun themeDotCenter(): android.graphics.PointF = android.graphics.PointF(width - dp(30f), insetTop + dp(30f))

    protected open val themeDot = true

    private fun drawThemeDot(c: Canvas): Boolean {
        if (!themeDot) return false
        val p = themeDotCenter()
        val r = dp(9f)
        themeR.set(p.x - dp(24f), p.y - dp(24f), p.x + dp(24f), p.y + dp(24f))
        dotP.style = Paint.Style.FILL; dotP.color = pal.accent
        c.drawArc(p.x - r, p.y - r, p.x + r, p.y + r, 90f, 180f, true, dotP)
        dotP.color = pal.ink
        c.drawArc(p.x - r, p.y - r, p.x + r, p.y + r, -90f, 180f, true, dotP)
        dotP.style = Paint.Style.STROKE; dotP.strokeWidth = dp(1.5f); dotP.color = pal.faint
        c.drawCircle(p.x, p.y, r + dp(3f), dotP)
        val age = SystemClock.uptimeMillis() - flashAt
        if (age < 1600) {
            val a = if (age < 1100) 1f else 1f - (age - 1100) / 500f
            flashP.color = alpha(pal.accent, a)
            flashP.textAlign = Paint.Align.RIGHT
            c.drawText(flashName.uppercase(), p.x - dp(26f), p.y + flashP.textSize * 0.36f, flashP)
            return true
        }
        return false
    }

    /** Call first in onTouchEvent for ACTION_UP; returns true if the theme dot consumed the tap. */
    protected fun themeTap(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked != android.view.MotionEvent.ACTION_UP || !themeR.contains(e.x, e.y)) return false
        flashName = Themes.next(context).let { if (it == "Auto") "Auto · red at night" else it }
        flashAt = SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        restyle()
        onThemeChanged()
        return true
    }

    protected fun inThemeDot(x: Float, y: Float) = themeR.contains(x, y)

    open fun onThemeChanged() {}

    /** Draw a frame. Return true to keep animating. */
    abstract fun paint(c: Canvas): Boolean

    /** Exponential ease toward [target]; frame-rate independent. */
    protected fun ease(cur: Float, target: Float, speed: Float = 14f): Float {
        val k = 1f - exp(-speed * dt)
        val v = cur + (target - cur) * k
        return if (kotlin.math.abs(target - v) < 0.0005f) target else v
    }

    protected fun Canvas.text(s: String, x: Float, y: Float, p: Paint, align: Paint.Align = Paint.Align.LEFT) {
        p.textAlign = align; drawText(s, x, y, p)
    }
}

fun mix(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    return Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).roundToInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * u).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * u).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).roundToInt()
    )
}

fun alpha(c: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * Color.alpha(c)).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))

fun durationWords(ms: Long): String {
    val m = ((ms + 59_999) / 60_000).coerceAtLeast(0)
    val h = m / 60; val r = m % 60
    return when { h == 0L -> "$r min"; r == 0L -> "$h h"; else -> "$h h $r min" }
}
