package dubrowgn.microtimer

import android.content.Context
import android.content.res.Configuration
import android.os.Build

// In-app light/dark choice. Android 9 has no system dark mode, so the app
// keeps its own setting and forces the matching resources (values-night).
object ThemePref {
    const val SYSTEM = 0
    const val DARK = 1
    const val LIGHT = 2

    // Android 10+ has a system-wide dark mode the app can follow
    private val systemHasDarkMode: Boolean
        get() = Build.VERSION.SDK_INT >= 29

    fun get(context: Context): Int {
        val mode = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("theme", SYSTEM)
        // without a system dark mode, "follow system" just means light
        return if (mode == SYSTEM && !systemHasDarkMode) LIGHT else mode
    }

    private fun set(context: Context, mode: Int) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putInt("theme", mode).apply()
    }

    // Android 10+: system -> dark -> light -> system
    // Android 9:   light <-> dark
    // Returns a short description of the new setting.
    fun cycle(context: Context): String {
        val next = if (systemHasDarkMode) {
            when (get(context)) {
                SYSTEM -> DARK
                DARK -> LIGHT
                else -> SYSTEM
            }
        } else {
            if (get(context) == DARK) LIGHT else DARK
        }
        set(context, next)
        return when (next) {
            DARK -> "Dark theme"
            LIGHT -> "Light theme"
            else -> "Theme follows system"
        }
    }

    // Wrap the activity's base context so its resources use the chosen mode.
    fun wrap(base: Context): Context {
        val night = when (get(base)) {
            DARK -> Configuration.UI_MODE_NIGHT_YES
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            else -> return base
        }
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }
}
