package dubrowgn.microtimer

import android.Manifest.permission
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import androidx.core.view.children
import androidx.room.Room
import dubrowgn.microtimer.db.Alarm
import dubrowgn.microtimer.db.AlarmDao
import dubrowgn.microtimer.db.Database

const val alarmNoteChannel = "micro-timer.alarm"

class MainActivity : Activity() {
    private lateinit var alarmDao: AlarmDao

    private var duration: Dec6Duration = Dec6Duration()
    private val tickHandler: Handler = Handler(Looper.getMainLooper())
    private var expiredCount: Int = 0

    private lateinit var layoutAlarms: LinearLayout
    private lateinit var lblValue: RoTimeControl
    private lateinit var stopwatch: StopwatchControl

    private val alarmMgr
        get() = getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val noteMgr
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val powerMgr
        get() = getSystemService(Context.POWER_SERVICE) as PowerManager

    private fun debug(msg: String) {
        Log.d(this::class.java.name, msg)
    }
    private fun error(msg: String) {
        Log.e(this::class.java.name, msg)
    }

    // apply the in-app light/dark choice before any resources are loaded
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePref.wrap(newBase))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // keep a half-typed time when the activity is recreated (theme switch)
        outState.putInt("input_digits", duration.digits.toInt())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        debug("onCreate()")

        super.onCreate(savedInstanceState)

        installCrashLogger()

        initDb()
        initPerms()
        initNotes()
        initUi()

        savedInstanceState?.getInt("input_digits", 0)?.let { digits ->
            if (digits > 0) {
                duration = Dec6Duration(digits.toUInt())
                lblValue.setValue(duration)
            }
        }

        allowFullscreen()

        // if the last run crashed, show the error instead of loading saved
        // timers (which may be what crashes)
        if (!showLastCrash())
            loadAlarms()
    }

    // Write any uncaught exception to a file so it can be shown on next launch.
    private fun installCrashLogger() {
        val crashFile = java.io.File(filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val sw = java.io.StringWriter()
                ex.printStackTrace(java.io.PrintWriter(sw))
                crashFile.writeText(
                    "Micro Timer+ ${packageManager.getPackageInfo(packageName, 0).versionName}\n" +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
                    sw.toString()
                )
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, ex)
        }
    }

    private fun showLastCrash(): Boolean {
        val crashFile = java.io.File(filesDir, "last-crash.txt")
        if (!crashFile.exists())
            return false

        val text = crashFile.readText()
        crashFile.delete()

        AlertDialog.Builder(this)
            .setTitle("Last crash")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("crash", text))
            }
            .setNegativeButton("Close", null)
            .show()

        return true
    }

    private fun showInfo() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName

        val text = "Micro Timer+ $version\n" +
            "Created by nem\n" +
            "https://github.com/nem8/micro-timer-restart\n\n" +
            "Based on Micro Timer by Dustin Brown\n" +
            "https://github.com/dubrowgn/micro-timer\n\n" +
            "Released under the MIT License."

        val dialog = AlertDialog.Builder(this)
            .setTitle("About")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNegativeButton("License") { _, _ -> showLicense() }
            .setNeutralButton("Theme: ${ThemePref.label(this)}") { _, _ ->
                Toast.makeText(this, ThemePref.cycle(this), Toast.LENGTH_SHORT).show()
                recreate()
            }
            .create()
        dialog.show()

        // make the URLs tappable
        dialog.findViewById<TextView>(android.R.id.message)?.let { tv ->
            Linkify.addLinks(tv, Linkify.WEB_URLS)
            tv.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun showLicense() {
        val text = resources.openRawResource(R.raw.license)
            .bufferedReader().use { it.readText() }

        AlertDialog.Builder(this)
            .setTitle("MIT License")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        debug("onDestroy()")

        super.onDestroy()
    }

    private fun initDb() {
        alarmDao = Room.databaseBuilder(applicationContext, Database::class.java, "app-data")
            .allowMainThreadQueries()
            .addMigrations(Database.MIGRATION_1_2)
            .build()
            .AlarmDao()
    }

    private fun allowFullscreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val keyguardMgr = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        keyguardMgr.requestDismissKeyguard(this, null)
    }

    private fun initNotes() {
        noteMgr.createNotificationChannel(
            NotificationChannel(
                alarmNoteChannel,
                "Alarm Expired",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Sound and visual indicator"
            }
        )
    }

    private fun initPerms() {
        val pkgUri = Uri.parse("package:$packageName")

        if(Build.VERSION.SDK_INT < 33) {
            debug("not applicable: POST_NOTIFICATIONS")
        } else if (checkSelfPermission(permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            debug("granted: POST_NOTIFICATIONS")
        } else {
            debug("requesting: POST_NOTIFICATIONS")
            requestPermissions(arrayOf(permission.POST_NOTIFICATIONS), 0)
        }

        if (!powerMgr.isIgnoringBatteryOptimizations(packageName)) {
            debug("requesting: IGNORE_BATTERY_OPTIMIZATIONS")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
        } else {
            debug("granted: IGNORE_BATTERY_OPTIMIZATIONS")
        }

        if (Build.VERSION.SDK_INT < 31) {
            debug("not applicable: SCHEDULE_EXACT_ALARM")
        } else if (alarmMgr.canScheduleExactAlarms()) {
            debug("granted: SCHEDULE_EXACT_ALARM")
        } else {
            debug("requesting: SCHEDULE_EXACT_ALARM")
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
        }

        if (Build.VERSION.SDK_INT < 34) {
            debug("not applicable: USE_FULL_SCREEN_INTENT")
        } else if (noteMgr.canUseFullScreenIntent()) {
            debug("granted: USE_FULL_SCREEN_INTENT")
        } else {
            debug("requesting:  USE_FULL_SCREEN_INTENT")
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri))
        }
    }

    private fun initUi() {
        setContentView(R.layout.activity_main)

        layoutAlarms = findViewById(R.id.layoutAlarms)
        lblValue = findViewById(R.id.lblInput)
        lblValue.setTextSizeSp(32f)

        stopwatch = StopwatchControl(this)
        stopwatch.onConvert = { dur -> createTimer(dur) }
        stopwatch.onInfo = { showInfo() }
        findViewById<LinearLayout>(R.id.layoutStopwatch).addView(stopwatch)

        findViewById<Button>(R.id.btn0).setOnClickListener { onDigit(0u) }
        findViewById<Button>(R.id.btn1).setOnClickListener { onDigit(1u) }
        findViewById<Button>(R.id.btn2).setOnClickListener { onDigit(2u) }
        findViewById<Button>(R.id.btn3).setOnClickListener { onDigit(3u) }
        findViewById<Button>(R.id.btn4).setOnClickListener { onDigit(4u) }
        findViewById<Button>(R.id.btn5).setOnClickListener { onDigit(5u) }
        findViewById<Button>(R.id.btn6).setOnClickListener { onDigit(6u) }
        findViewById<Button>(R.id.btn7).setOnClickListener { onDigit(7u) }
        findViewById<Button>(R.id.btn8).setOnClickListener { onDigit(8u) }
        findViewById<Button>(R.id.btn9).setOnClickListener { onDigit(9u) }
        findViewById<Button>(R.id.btn00).setOnClickListener { onDigit(0u); onDigit(0u) }
        findViewById<Button>(R.id.btnBackspace).setOnClickListener { onBackspace() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { onClear() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { createTimerFromInput() }
    }

    private fun loadAlarms() {
        debug("loadAlarms()")
        alarmDao
            .readAll()
            .map(::attachAlarm)
    }

    private fun attachAlarm(alarm: Alarm): TimerControl {
        debug("attachAlarm(${alarm.id})")

        val tc = TimerControl(this, alarm)

        tc.onDelete = {
            debug("tc.onDelete(${tc.alarm.id}); expired:${alarm.expired}, expiredCount:$expiredCount")

            tickHandler.removeCallbacksAndMessages(alarm.id)
            layoutAlarms.removeView(tc)

            if (alarm.expired) {
                expiredCount--
                if (expiredCount == 0)
                    Vibration.stop(this)
            }

            noteMgr.cancel(alarm.id!!.toInt())

            alarmDao.delete(alarm)
        }
        tc.onPause = {
            debug("tc.onPause(${tc.alarm.id})")

            alarm.pause()
            alarmDao.update(alarm)
            tc.update()
            tickHandler.removeCallbacksAndMessages(alarm.id)
        }
        tc.onResume = {
            debug("tc.onResume(${tc.alarm.id})")

            alarm.unpause()
            alarmDao.update(alarm)
            tc.update()
            alarm.msToNextTick()?.let { delayMs ->
                tickHandler.postDelayed({ tick(tc) }, alarm.id, delayMs)
            }
        }

        tc.onRename = { name ->
            debug("tc.onRename(${tc.alarm.id})")

            alarm.name = name
            alarmDao.update(alarm)
            tc.update()
        }

        tc.onRestart = {
            debug("tc.onRestart(${tc.alarm.id}); expired:${alarm.expired}, expiredCount:$expiredCount")

            tickHandler.removeCallbacksAndMessages(alarm.id)

            if (alarm.expired) {
                expiredCount--
                if (expiredCount == 0)
                    Vibration.stop(this)
                noteMgr.cancel(alarm.id!!.toInt())
            }

            alarm.restart()
            alarmDao.update(alarm)
            tick(tc)
        }

        layoutAlarms.addView(tc)

        return tc
    }

    // allowVibrate is false for the catch-up tick when the app is (re)opened,
    // so opening the app to an already-expired timer never vibrates.
    private fun tick(tc: TimerControl, allowVibrate: Boolean = true) {
        val wasExpired = tc.alarm.expired
        val deltaMs = tc.alarm.update()
        tc.update()

        debug("tick(${tc.alarm.id}); remaining:${tc.alarm.remaining.totalSeconds}s")

        if (tc.alarm.expired) {
            expiredCount++

            debug("alarm ${tc.alarm.id} expired; expiredCount:$expiredCount")

            // Vibrate only when the timer has just run out. Reopening the app
            // to a timer that expired a while ago (and already vibrated via
            // the background alarm) should just show it, not buzz again.
            if (allowVibrate && !wasExpired && tc.alarm.justExpired())
                Vibration.start(this)

            return
        }

        if (deltaMs == null)
            return

        tickHandler.postDelayed({ tick(tc) }, tc.alarm.id, deltaMs)
    }

    override fun onResume() {
        debug("onResume()")

        super.onResume()

        // Opening the app counts as having noticed: stop any vibration the
        // background alarm started. Only timers that run out while the app is
        // open will vibrate from here on.
        Vibration.stop(this)
        stopwatch.resume()

        expiredCount = 0
        layoutAlarms.children.forEach { v ->
            val tc = v as TimerControl
            cancelAlarmTimeout(tc.alarm)
            tick(tc, allowVibrate = false)
        }
    }

    override fun onPause() {
        debug("onPause()")

        super.onPause()

        stopwatch.pause()

        layoutAlarms.children.forEach { v ->
            val tc = v as TimerControl
            tickHandler.removeCallbacksAndMessages(tc.alarm.id)
            if (!tc.alarm.expired && !tc.alarm.paused)
                startAlarmTimeout(tc.alarm)
        }

        if (expiredCount > 0)
            Vibration.stop(this)
    }

    private fun onBackspace() {
        duration.popDigit()
        lblValue.setValue(if (duration.isZero) null else duration)
    }

    private fun onClear() {
        duration.zero()
        lblValue.setValue(null)
    }

    private fun onDigit(digit: UInt) {
        duration.pushDigit(digit)
        lblValue.setValue(duration)
    }

    private fun createAlarmIntent(alarm: Alarm): PendingIntent {
        return PendingIntent.getBroadcast(
            this,
            alarm.id!!.toInt(),
            Intent(this, AlarmReceiver::class.java).apply {
                action = "dubrowgn.microtimer.ALARM_EXPIRED"
                putExtra("id", alarm.id)
                putExtra("name", alarm.duration.toString())
                putExtra("label", alarm.name)
                flags = 0
            },
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startAlarmTimeout(alarm: Alarm) {
        debug("startAlarmTimeout()")

        val remainingMs = alarm.remaining.totalSeconds.toLong() * 1000
        try {
            alarmMgr.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + remainingMs,
                createAlarmIntent(alarm)
            )
        } catch (se: SecurityException) {
            error("$se.message")
        }
    }

    private fun cancelAlarmTimeout(alarm: Alarm) {
        alarmMgr.cancel(createAlarmIntent(alarm))
    }

    private fun createTimerFromInput() {
        debug("createTimerFromInput()")

        if (duration.isZero)
            return

        createTimer(duration.clone())
        onClear()
    }

    private fun createTimer(dur: Dec6Duration) {
        debug("createTimer($dur)")

        if (dur.isZero)
            return

        val alarm = alarmDao.create(dur)
        val tc = attachAlarm(alarm)
        tick(tc)
    }
}
