package dubrowgn.microtimer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dubrowgn.microtimer.ui.ext.*

// A single stopwatch row shown above the input area.
//  - tap the time: start counting up
//  - tap again: turn the counted time into a new timer (onConvert) and reset
//  - delete button: stop and reset without making a timer
// The start time is kept in SharedPreferences so it survives the app being
// closed or the screen rotating/recreating.
class StopwatchControl(context: Context) : LinearLayout(context) {
    var onConvert: ((Dec6Duration) -> Unit)? = null
    var onInfo: (() -> Unit)? = null

    private val prefs = context.getSharedPreferences("stopwatch", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false

    private val lblHint: TextView
    private val lblValue: RoTimeControl
    private val btnStop: Button

    private val delColor: Int
    private val disabledColor: Int

    // elapsedRealtime() when the stopwatch was started, or -1 when idle
    private var startMs: Long
        get() = prefs.getLong("start_ms", -1L)
        set(v) {
            prefs.edit().putLong("start_ms", v).apply()
        }

    private val running: Boolean
        get() = startMs >= 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            refresh()
            scheduleTick()
        }
    }

    init {
        val matchParent = LayoutParams.MATCH_PARENT
        val wrapContent = LayoutParams.WRAP_CONTENT

        val btnSizePx = resources.getDimension(R.dimen.icon_button_size).toInt()
        val btnMarginPx = resources.getDimension(R.dimen.button_margin).toInt()
        val rowMarginPx = btnMarginPx * 2

        layoutParams = LayoutParams(matchParent, wrapContent)
            .withMargins(rowMarginPx, rowMarginPx / 2)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val fgColor = context.getColor(R.color.text_on_primary)
        delColor = context.getColor(R.color.delete)
        disabledColor = context.getColor(R.color.disabled)

        val lp = LayoutParams(btnSizePx, btnSizePx)
            .withMargins(btnMarginPx)

        // same look as the timers' delete button
        btnStop = Button(context)
            .withLayout(lp)
            .withBg(context.getDrawable(R.drawable.btn_selector))
            .withBgTint(delColor)
            .withFgTint(fgColor)
            .withFg(context.getDrawable(R.drawable.ico_delete))
            .withFgGravity(Gravity.CENTER)
            .withOnClick { reset() }
        btnStop.contentDescription = "Stop stopwatch"
        btnStop.stateListAnimator = null
        addView(btnStop)

        val center = LinearLayout(context)
            .withLayout(0, wrapContent, 1f)
        center.orientation = VERTICAL
        center.gravity = Gravity.CENTER
        center.setOnClickListener { onTap() }

        lblHint = TextView(context)
            .withLayout(matchParent, wrapContent)
            .withTextSize(12f)
            .withTextAlign(View.TEXT_ALIGNMENT_CENTER)
        lblHint.alpha = 0.6f
        lblHint.maxLines = 1
        lblHint.ellipsize = TextUtils.TruncateAt.END
        center.addView(lblHint)

        lblValue = RoTimeControl(context)
        lblValue.layoutParams = LayoutParams(matchParent, wrapContent)
        lblValue.setTextSizeSp(32f)
        center.addView(lblValue)

        addView(center)

        // info button, same size as the delete button so the time is centred
        // exactly above the input display; styled like the clear/backspace keys
        val btnInfo = Button(context)
            .withLayout(LayoutParams(lp))
            .withBg(context.getDrawable(R.drawable.btn_selector))
            .withBgTint(context.getColor(R.color.background))
            .withFgTint(context.getColor(R.color.foreground))
            .withFg(context.getDrawable(R.drawable.ico_info))
            .withFgGravity(Gravity.CENTER)
            .withOnClick { onInfo?.invoke() }
        btnInfo.contentDescription = "About and settings"
        btnInfo.stateListAnimator = null
        btnInfo.alpha = 0.6f
        addView(btnInfo)

        // a start time from before a reboot is meaningless; drop it
        if (startMs > SystemClock.elapsedRealtime())
            startMs = -1L

        refresh()
    }

    private fun elapsedSeconds(): Long {
        val start = startMs
        if (start < 0)
            return 0
        val secs = (SystemClock.elapsedRealtime() - start) / 1000
        return secs.coerceIn(0L, maxSeconds)
    }

    private fun toDuration(secs: Long): Dec6Duration {
        val h = secs / 3600
        val m = secs % 3600 / 60
        val s = secs % 60
        return Dec6Duration((h * 10000 + m * 100 + s).toUInt())
    }

    private fun onTap() {
        if (!running) {
            startMs = SystemClock.elapsedRealtime()
            refresh()
            scheduleTick()
            return
        }

        val secs = elapsedSeconds()
        reset()
        if (secs > 0)
            onConvert?.invoke(toDuration(secs))
    }

    private fun reset() {
        startMs = -1L
        handler.removeCallbacks(tickRunnable)
        refresh()
    }

    private fun refresh() {
        lblValue.setValue(toDuration(elapsedSeconds()))

        if (running) {
            lblHint.text = "Tap to turn into a timer"
            lblValue.alpha = 1f
            btnStop
                .withEnabled(true)
                .withBgTint(delColor)
        } else {
            lblHint.text = "Stopwatch · tap to start"
            lblValue.alpha = 0.4f
            btnStop
                .withEnabled(false)
                .withBgTint(disabledColor)
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tickRunnable)
        val start = startMs
        if (!active || start < 0)
            return
        val elapsedMs = SystemClock.elapsedRealtime() - start
        handler.postDelayed(tickRunnable, 1000 - elapsedMs % 1000)
    }

    // called from the activity's onResume / onPause
    fun resume() {
        active = true
        refresh()
        scheduleTick()
    }

    fun pause() {
        active = false
        handler.removeCallbacks(tickRunnable)
    }

    companion object {
        // 99:59:59, the largest value a timer can hold
        private const val maxSeconds = 99L * 3600 + 59 * 60 + 59
    }
}
