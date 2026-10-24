package dubrowgn.microtimer

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

object Vibration {
    // one "buzz-buzz ... pause" cycle, in ms: on, off, on, off
    private val cycle = longArrayOf(120, 120, 120, 360)

    // how long to keep vibrating after a timer expires
    private const val durationMs = 5000L

    private fun debug(msg: String) {
        Log.d(this::class.java.name, msg)
    }

    private fun vibrator(context: Context): Vibrator {
        return context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // Repeat the cycle until ~durationMs has passed, then end on its own.
    // A finite waveform (no repeat index) stops even if the app process is
    // gone, e.g. when started from the background alarm receiver.
    private fun pattern(): LongArray {
        val cycles = maxOf(1, (durationMs / cycle.sum()).toInt())
        val timings = ArrayList<Long>()
        timings.add(0) // start immediately
        repeat(cycles) { timings.addAll(cycle.toList()) }
        timings.removeAt(timings.size - 1) // drop trailing pause
        return timings.toLongArray()
    }

    fun start(context: Context) {
        debug("start()")

        vibrator(context).vibrate(
            VibrationEffect.createWaveform(pattern(), -1),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
    }

    fun stop(context: Context) {
        debug("stop()")

        vibrator(context).cancel()
    }
}
