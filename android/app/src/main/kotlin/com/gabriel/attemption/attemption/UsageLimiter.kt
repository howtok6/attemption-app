package com.gabriel.attemption.attemption

/** Límites de uso. Por defecto: 5 min de sesión, 10 min de descanso y 1 h diaria combinada. */
data class UsageLimits(
    val sessionMaxMs: Long = 5 * 60_000L,
    val restMs: Long = 10 * 60_000L,
    val dailyMaxMs: Long = 60 * 60_000L,
)

/** Estado del limitador. Se guarda en disco para sobrevivir a reinicios del servicio. */
data class UsageState(
    val dayKey: String = "",
    val dailyUsedMs: Long = 0L,
    val sessionUsedMs: Long = 0L,
    val restEndsAt: Long = 0L,
    val lastSeenInTargetAt: Long = 0L,
)

sealed class Decision {
    /** No hay una app limitada en primer plano. */
    object Idle : Decision()

    /** App limitada en uso y dentro de los límites. */
    data class Allow(val sessionLeftMs: Long, val dailyLeftMs: Long) : Decision()

    /** La sesión se acaba de cumplir: empieza el descanso. */
    object SessionEnded : Decision()

    /** El usuario intenta usar la app durante el descanso. */
    data class Resting(val restLeftMs: Long) : Decision()

    /** Se alcanzó el límite diario. */
    object DailyLimitReached : Decision()
}

/**
 * Lógica pura de límites (sin dependencias de Android) para poder probarla con tests unitarios.
 * El tiempo se cuenta con un contador propio y combinado entre todas las apps limitadas;
 * el tiempo bloqueado nunca se suma al uso diario.
 */
class UsageLimiter(private val limits: UsageLimits = UsageLimits()) {

    /**
     * Avanza el estado un tick.
     * @param deltaMs tiempo transcurrido desde el tick anterior (el llamador lo acota).
     * @param todayKey identificador del día actual; si cambia, el uso diario vuelve a cero.
     */
    fun tick(
        state: UsageState,
        now: Long,
        deltaMs: Long,
        inTargetApp: Boolean,
        todayKey: String,
    ): Pair<UsageState, Decision> {
        var s = if (state.dayKey != todayKey) state.copy(dayKey = todayKey, dailyUsedMs = 0L) else state

        if (!inTargetApp) {
            // La sesión solo se reinicia si el usuario estuvo fuera al menos el tiempo de descanso.
            // Salir y volver enseguida ya no regala otros 5 minutos.
            if (s.sessionUsedMs > 0 && now - s.lastSeenInTargetAt >= limits.restMs) {
                s = s.copy(sessionUsedMs = 0L)
            }
            return s to Decision.Idle
        }

        s = s.copy(lastSeenInTargetAt = now)

        if (s.dailyUsedMs >= limits.dailyMaxMs) return s to Decision.DailyLimitReached
        if (now < s.restEndsAt) return s to Decision.Resting(s.restEndsAt - now)

        val daily = s.dailyUsedMs + deltaMs
        val session = s.sessionUsedMs + deltaMs
        if (daily >= limits.dailyMaxMs) {
            return s.copy(dailyUsedMs = daily, sessionUsedMs = session) to Decision.DailyLimitReached
        }
        if (session >= limits.sessionMaxMs) {
            return s.copy(
                dailyUsedMs = daily,
                sessionUsedMs = 0L,
                restEndsAt = now + limits.restMs,
            ) to Decision.SessionEnded
        }
        return s.copy(dailyUsedMs = daily, sessionUsedMs = session) to
            Decision.Allow(limits.sessionMaxMs - session, limits.dailyMaxMs - daily)
    }
}
