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
 * El uso llega ya medido por [ForegroundTracker] (combinado entre todas las apps limitadas);
 * el tiempo bloqueado nunca se suma al uso diario.
 */
class UsageLimiter(private val limits: UsageLimits = UsageLimits()) {

    /**
     * Avanza el estado con el uso medido desde la revisión anterior.
     * @param usedMs ms que una app limitada estuvo en primer plano desde la revisión anterior
     *   (puede incluir tiempo recuperado si el servicio estuvo cerrado).
     * @param inTargetNow si en este momento hay una app limitada en primer plano.
     * @param lastTargetAt último instante en que una app limitada estuvo en primer plano.
     * @param todayKey identificador del día actual; si cambia, el uso diario vuelve a cero.
     */
    fun tick(
        state: UsageState,
        now: Long,
        usedMs: Long,
        inTargetNow: Boolean,
        lastTargetAt: Long,
        todayKey: String,
    ): Pair<UsageState, Decision> {
        var s = if (state.dayKey != todayKey) state.copy(dayKey = todayKey, dailyUsedMs = 0L) else state
        if (lastTargetAt > s.lastSeenInTargetAt) s = s.copy(lastSeenInTargetAt = lastTargetAt)

        // Durante un descanso o con el límite diario cumplido el uso no se suma.
        val blockedBefore = s.dailyUsedMs >= limits.dailyMaxMs || now < s.restEndsAt
        var sessionEnded = false
        if (usedMs > 0 && !blockedBefore) {
            val session = s.sessionUsedMs + usedMs
            s = s.copy(dailyUsedMs = s.dailyUsedMs + usedMs, sessionUsedMs = session)
            if (session >= limits.sessionMaxMs) {
                s = s.copy(sessionUsedMs = 0L, restEndsAt = now + limits.restMs)
                sessionEnded = true
            }
        }

        if (!inTargetNow) {
            // La sesión solo se reinicia si el usuario estuvo fuera al menos el tiempo de descanso.
            // Salir y volver enseguida no regala otros 5 minutos.
            if (s.sessionUsedMs > 0 && now - s.lastSeenInTargetAt >= limits.restMs) {
                s = s.copy(sessionUsedMs = 0L)
            }
            return s to Decision.Idle
        }

        val decision = when {
            s.dailyUsedMs >= limits.dailyMaxMs -> Decision.DailyLimitReached
            sessionEnded -> Decision.SessionEnded
            now < s.restEndsAt -> Decision.Resting(s.restEndsAt - now)
            else -> Decision.Allow(limits.sessionMaxMs - s.sessionUsedMs, limits.dailyMaxMs - s.dailyUsedMs)
        }
        return s to decision
    }
}
