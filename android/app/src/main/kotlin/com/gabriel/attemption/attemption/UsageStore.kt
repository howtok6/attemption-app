package com.gabriel.attemption.attemption

import android.content.Context
import java.util.Calendar

/** Persistencia del estado de uso y de si el usuario activó la protección. */
class UsageStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var protectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    fun load(): UsageState = UsageState(
        dayKey = prefs.getString(KEY_DAY, "") ?: "",
        dailyUsedMs = prefs.getLong(KEY_DAILY_USED, 0L),
        sessionUsedMs = prefs.getLong(KEY_SESSION_USED, 0L),
        restEndsAt = prefs.getLong(KEY_REST_ENDS_AT, 0L),
        lastSeenInTargetAt = prefs.getLong(KEY_LAST_SEEN, 0L),
    )

    fun save(state: UsageState) {
        prefs.edit()
            .putString(KEY_DAY, state.dayKey)
            .putLong(KEY_DAILY_USED, state.dailyUsedMs)
            .putLong(KEY_SESSION_USED, state.sessionUsedMs)
            .putLong(KEY_REST_ENDS_AT, state.restEndsAt)
            .putLong(KEY_LAST_SEEN, state.lastSeenInTargetAt)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "attemption_usage"
        private const val KEY_ENABLED = "protection_enabled"
        private const val KEY_DAY = "day"
        private const val KEY_DAILY_USED = "daily_used_ms"
        private const val KEY_SESSION_USED = "session_used_ms"
        private const val KEY_REST_ENDS_AT = "rest_ends_at"
        private const val KEY_LAST_SEEN = "last_seen_in_target_at"

        /** Día local (año + día del año). Cambia a medianoche en la zona horaria del teléfono. */
        fun dayKey(timeMs: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = timeMs }
            return "%04d-%03d".format(c.get(Calendar.YEAR), c.get(Calendar.DAY_OF_YEAR))
        }
    }
}
