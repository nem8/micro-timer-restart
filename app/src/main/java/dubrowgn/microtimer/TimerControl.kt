package dubrowgn.microtimer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dubrowgn.microtimer.db.Alarm
import dubrowgn.microtimer.ui.ext.*

class TimerControl(context: Context, val alarm: Alarm) : LinearLayout(context) {
    var onDelete: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onRestart: (() -> Unit)? = null
    var onRename: ((String?) -> Unit)? = null

    private var paused: Boolean = false
    private var showingExpired: Boolean = false

    private val actColor: Int
    private val disabledColor: Int
    private val expiredColor: Int

    private val lblValue: RoTimeControl
    private val lblName: TextView
    private val btnRestart: Button
    private val btnPause: Button
    private val btnResume: Button

    // soft red rounded background shown behind an expired row
    private val expiredBg: GradientDrawable

    // slow fade in/out of the time text while expired
    private var pulse: ObjectAnimator? = null

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

        val icoDelete = context.getDrawable(R.drawable.ico_delete)
        val icoRestart = context.getDrawable(R.drawable.ico_restart)
        val icoPlay = context.getDrawable(R.drawable.ico_play)
        val icoPause = context.getDrawable(R.drawable.ico_pause)

        val fgColor = context.getColor(R.color.text_on_primary)
        val delColor = context.getColor(R.color.delete)
        val rstColor = context.getColor(R.color.primary)
        actColor = context.getColor(R.color.action)
        disabledColor = context.getColor(R.color.disabled)
        expiredColor = context.getColor(R.color.expired)

        expiredBg = GradientDrawable().apply {
            setColor(context.getColor(R.color.expired_bg))
            cornerRadius = 16f * resources.displayMetrics.density
        }

        val lp = LayoutParams(btnSizePx, btnSizePx)
            .withMargins(btnMarginPx)

        val btnDelete = Button(context)
            .withLayout(lp)
            .withBg(context.getDrawable(R.drawable.btn_selector))
            .withBgTint(delColor)
            .withFgTint(fgColor)
            .withFg(icoDelete)
            .withFgGravity(Gravity.CENTER)
            .withOnClick { onDelete?.invoke() }
        btnDelete.contentDescription = "Delete"
        btnDelete.stateListAnimator = null
        addView(btnDelete)

        // name (small, only shown when set) stacked above the time;
        // tapping either opens the rename dialog
        val center = LinearLayout(context)
            .withLayout(0, wrapContent, 1f)
        center.orientation = VERTICAL
        center.gravity = Gravity.CENTER
        center.setOnClickListener { showRenameDialog() }

        lblName = TextView(context)
            .withLayout(matchParent, wrapContent)
            .withTextSize(13f)
            .withTextAlign(View.TEXT_ALIGNMENT_CENTER)
        lblName.alpha = 0.7f
        lblName.maxLines = 1
        lblName.ellipsize = TextUtils.TruncateAt.END
        lblName.visibility = View.GONE
        center.addView(lblName)

        lblValue = RoTimeControl(context)
        lblValue.layoutParams = LayoutParams(matchParent, wrapContent)
        center.addView(lblValue)

        addView(center)

        btnRestart = Button(context)
            .withLayout(LayoutParams(lp))
            .withBg(context.getDrawable(R.drawable.btn_selector))
            .withBgTint(rstColor)
            .withFgTint(fgColor)
            .withFg(icoRestart)
            .withFgGravity(Gravity.CENTER)
            .withOnClick { onRestart?.invoke() }
        btnRestart.contentDescription = "Restart"
        btnRestart.stateListAnimator = null
        addView(btnRestart)

        btnPause = Button(context)
            .withLayout(LayoutParams(lp))
            .withBg(context.getDrawable(R.drawable.btn_selector))
            .withBgTint(actColor)
            .withFgTint(fgColor)
            .withFg(icoPause)
            .withFgGravity(Gravity.CENTER)
            .withOnClick { onPause?.invoke() }
        btnPause.contentDescription = "Pause"
        btnPause.stateListAnimator = null
        addView(btnPause)

        btnResume = Button(context)
            .withLayout(LayoutParams(lp))
            .withBg(context.getDrawable(R.drawable.btn_selector))
            .withBgTint(actColor)
            .withFgTint(fgColor)
            .withFg(icoPlay)
            .withFgGravity(Gravity.CENTER)
            .withOnClick { onResume?.invoke() }
        btnResume.contentDescription = "Resume"
        btnResume.stateListAnimator = null
        // don't add; paused by default
    }

    private fun showExpired() {
        background = expiredBg
        lblValue.setColor(expiredColor)
        btnPause
            .withEnabled(false)
            .withBgTint(disabledColor)

        if (pulse == null) {
            pulse = ObjectAnimator.ofFloat(lblValue, View.ALPHA, 1f, 0.35f).apply {
                duration = 1100
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun clearExpired() {
        pulse?.cancel()
        pulse = null
        lblValue.alpha = 1f

        background = null
        lblValue.resetColor()
        btnPause
            .withEnabled(true)
            .withBgTint(actColor)
    }

    override fun onDetachedFromWindow() {
        // stop the pulse when the row is removed (deleted / activity closed)
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // resume the pulse if the row is shown again while still expired
        if (showingExpired && pulse == null) {
            showingExpired = false
            showExpired()
            showingExpired = true
        }
    }

    private fun showRenameDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()

        val input = EditText(context)
        input.setSingleLine()
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.filters = arrayOf(InputFilter.LengthFilter(30))
        input.hint = "e.g. Pasta"
        input.setText(alarm.name ?: "")
        input.setSelection(input.text.length)

        val wrap = FrameLayout(context)
        wrap.setPadding(pad, pad / 2, pad, 0)
        wrap.addView(input)

        val builder = AlertDialog.Builder(context)
            .setTitle("Timer name")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                onRename?.invoke(input.text.toString().trim().ifEmpty { null })
            }
            .setNegativeButton("Cancel", null)

        if (!alarm.name.isNullOrEmpty())
            builder.setNeutralButton("Remove") { _, _ -> onRename?.invoke(null) }

        val dialog = builder.create()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dialog.show()
        input.requestFocus()
    }

    fun update(): TimerControl {
        lblValue.setValue(alarm.remaining)

        val name = alarm.name
        if (name.isNullOrEmpty()) {
            lblName.visibility = View.GONE
        } else {
            lblName.text = name
            lblName.visibility = View.VISIBLE
        }

        val expired = alarm.remaining.digits == 0u
        if (expired && !showingExpired) {
            showExpired()
            showingExpired = true
        } else if (!expired && showingExpired) {
            // timer was restarted after expiring; undo the expired look
            clearExpired()
            showingExpired = false
        }

        if (paused == alarm.paused)
            return this

        // pause/resume is always the last view in the row
        if (alarm.paused) {
            removeView(btnPause)
            addView(btnResume)
        } else {
            removeView(btnResume)
            addView(btnPause)
        }
        paused = alarm.paused

        return this
    }
}
