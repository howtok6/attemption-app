package com.gabriel.attemption.attemption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLimiterTest {

    private val limiter = UsageLimiter()
    private val tickMs = 2_000L
    private val day = "2026-296"
    private val minute = 60_000L

    private data class Run(val state: UsageState, val now: Long, val decisions: List<Decision>)

    /** Simula revisiones cada 2 s durante [durationMs], dentro o fuera de una app limitada. */
    private fun run(state: UsageState, start: Long, durationMs: Long, inTarget: Boolean, dayKey: String = day): Run {
        var s = state
        var now = start
        val decisions = mutableListOf<Decision>()
        while (now < start + durationMs) {
            now += tickMs
            val used = if (inTarget) tickMs else 0L
            val lastTarget = if (inTarget) now else s.lastSeenInTargetAt
            val (next, decision) = limiter.tick(s, now, used, inTarget, lastTarget, dayKey)
            s = next
            decisions += decision
        }
        return Run(s, now, decisions)
    }

    @Test
    fun `la sesion termina tras 5 minutos de uso continuo`() {
        val r = run(UsageState(), 0L, 5 * minute, inTarget = true)
        assertEquals(Decision.SessionEnded, r.decisions.last())
        assertEquals(1, r.decisions.count { it == Decision.SessionEnded })
    }

    @Test
    fun `salir un momento y volver no reinicia la sesion`() {
        val a = run(UsageState(), 0L, 4 * minute, inTarget = true)
        val b = run(a.state, a.now, 1 * minute, inTarget = false)
        val c = run(b.state, b.now, 1 * minute, inTarget = true)
        assertTrue(c.decisions.contains(Decision.SessionEnded))
    }

    @Test
    fun `estar fuera el tiempo de descanso si reinicia la sesion`() {
        val a = run(UsageState(), 0L, 4 * minute, inTarget = true)
        val b = run(a.state, a.now, 10 * minute, inTarget = false)
        assertEquals(0L, b.state.sessionUsedMs)
        val c = run(b.state, b.now, 4 * minute, inTarget = true)
        assertTrue(c.decisions.all { it is Decision.Allow })
    }

    @Test
    fun `durante el descanso la app queda bloqueada y el tiempo no cuenta`() {
        val a = run(UsageState(), 0L, 5 * minute, inTarget = true)
        val dailyAfterSession = a.state.dailyUsedMs
        val b = run(a.state, a.now, 9 * minute, inTarget = true)
        assertTrue(b.decisions.all { it is Decision.Resting })
        assertEquals(dailyAfterSession, b.state.dailyUsedMs)
        val c = run(b.state, b.now, 2 * minute, inTarget = true)
        assertTrue(c.decisions.last() is Decision.Allow)
    }

    @Test
    fun `el limite diario es combinado y se alcanza a la hora`() {
        var s = UsageState()
        var now = 0L
        // 12 ciclos de 5 min de uso + 10 min de descanso = 60 min de uso
        repeat(12) {
            val use = run(s, now, 5 * minute, inTarget = true)
            val rest = run(use.state, use.now, 10 * minute, inTarget = false)
            s = rest.state
            now = rest.now
        }
        val after = run(s, now, 1 * minute, inTarget = true)
        assertTrue(after.decisions.all { it == Decision.DailyLimitReached })
        assertTrue(s.dailyUsedMs >= 60 * minute)
    }

    @Test
    fun `un dia nuevo reinicia el uso diario`() {
        val full = UsageState(dayKey = day, dailyUsedMs = 60 * minute)
        val sameDay = limiter.tick(full, 1_000L, tickMs, true, 1_000L, day)
        assertEquals(Decision.DailyLimitReached, sameDay.second)
        val nextDay = limiter.tick(full, 1_000L, tickMs, true, 1_000L, "2026-297")
        assertTrue(nextDay.second is Decision.Allow)
        assertEquals(tickMs, nextDay.first.dailyUsedMs)
    }

    @Test
    fun `el uso recuperado tras un cierre se cuenta aunque ya no este en la app`() {
        // El sistema cerró el servicio; al volver, los eventos muestran 6 min de Instagram.
        val (state, decision) = limiter.tick(UsageState(dayKey = day), 10 * minute, 6 * minute, false, 9 * minute, day)
        assertEquals(Decision.Idle, decision)
        assertEquals(6 * minute, state.dailyUsedMs)
        assertEquals(10 * minute + 10 * minute, state.restEndsAt) // empieza el descanso
        // Si vuelve a abrir Instagram, queda en descanso.
        val (_, next) = limiter.tick(state, 11 * minute, 0L, true, 11 * minute, day)
        assertTrue(next is Decision.Resting)
    }

    @Test
    fun `una sola revision larga dentro de la app corta la sesion igual`() {
        // Revisiones espaciadas: una sola trae 5 min de uso.
        val (_, decision) = limiter.tick(UsageState(dayKey = day), 5 * minute, 5 * minute, true, 5 * minute, day)
        assertEquals(Decision.SessionEnded, decision)
    }
}
