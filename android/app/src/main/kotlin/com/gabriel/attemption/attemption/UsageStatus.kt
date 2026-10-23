package com.gabriel.attemption.attemption

/**
 * Traduce el estado guardado a lo que ve el usuario (pantalla de Flutter y notificación).
 * Kotlin puro, sin dependencias de Android, para poder probarlo con tests unitarios.
 */
object UsageStatus {
    const val INACTIVE = "inactive"
    const val ALLOWED = "allowed"
    const val RESTING = "resting"
    const val BLOCKED = "blocked"

    /** Uso del día; si el estado guardado es de otro día, cuenta como cero. */
    fun dailyUsed(state: UsageState, todayKey: String): Long =
        if (state.dayKey == todayKey) state.dailyUsedMs else 0L

    fun restLeft(state: UsageState, now: Long): Long = (state.restEndsAt - now).coerceAtLeast(0L)

    /** Estado de los límites, suponiendo que la protección está corriendo. */
    fun limitState(state: UsageState, limits: UsageLimits, now: Long, todayKey: String): String = when {
        dailyUsed(state, todayKey) >= limits.dailyMaxMs -> BLOCKED
        restLeft(state, now) > 0 -> RESTING
        else -> ALLOWED
    }

    /** Durante un descanso o con el límite diario cumplido no se permite desactivar la protección. */
    fun isLocked(state: UsageState, limits: UsageLimits, now: Long, todayKey: String): Boolean =
        limitState(state, limits, now, todayKey) != ALLOWED

    fun toMap(
        state: UsageState,
        limits: UsageLimits,
        now: Long,
        todayKey: String,
        running: Boolean,
        protectionEnabled: Boolean,
    ): Map<String, Any> = mapOf(
        "running" to running,
        "protectionEnabled" to protectionEnabled,
        "state" to if (running) limitState(state, limits, now, todayKey) else INACTIVE,
        "dailyUsedMs" to dailyUsed(state, todayKey),
        "dailyLimitMs" to limits.dailyMaxMs,
        "sessionUsedMs" to state.sessionUsedMs,
        "sessionLimitMs" to limits.sessionMaxMs,
        "restLeftMs" to restLeft(state, now),
        "restLimitMs" to limits.restMs,
    )

    /** Texto de la notificación fija. Cambia como máximo una vez por minuto. */
    fun notificationText(state: UsageState, limits: UsageLimits, now: Long, todayKey: String): String {
        val today = "Hoy: ${minutes(dailyUsed(state, todayKey))} de ${minutes(limits.dailyMaxMs)} min"
        return when (limitState(state, limits, now, todayKey)) {
            BLOCKED -> "Límite diario alcanzado. Vuelve mañana."
            RESTING -> "Descanso: faltan ${minutesCeil(restLeft(state, now))} min · $today"
            else -> "$today · Sesión: ${minutes(state.sessionUsedMs)} de ${minutes(limits.sessionMaxMs)} min"
        }
    }

    private fun minutes(ms: Long): Long = ms / 60_000L
    private fun minutesCeil(ms: Long): Long = (ms + 59_999L) / 60_000L
}
