package com.gabriel.attemption.attemption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLimiterTest {

    private val limiter = UsageLimiter()
    private val tickMs = 2_000L
    private val day = "2026-296"
    private val minute = 60_000L

    /** Simula ticks de 2 s durante [durationMs] y devuelve el estado, la hora final y la última decisión. */
    private data class Run(val state: UsageState, val now: Long, val last: Decision, val decisions: List<Decision>)

    private fun run(
        state: UsageState,
        start: Long,
        durationMs: Long,
        inTarget: Boolean,
        dayKey: String = day,
    ): Run {
        var s = state
        var now = start
        val decisions = mutableListOf<Decision>()
        var last: Decision = Decision.Idle
        while (now < start + durationMs) {
            now += tickMs
            val (next, decision) = limiter.tick(s, now, tickMs, inTarget, dayKey)
            s = next
            last = decision
            decisions += decision
        }
        return Run(s, now, last, decisions)
    }

    @Test
    fun `la sesion termina tras 5 minutos de uso continuo`() {
        val r = run(UsageState(), 0L, 5 * minute, inTarget = true)
        assertEquals(Decision.SessionEnded, r.last)
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
        var reached = false
        // 12 ciclos de 5 min de uso + 10 min de descanso = 60 min de uso
        repeat(12) {
            val use = run(s, now, 5 * minute, inTarget = true)
            val rest = run(use.state, use.now, 10 * minute, inTarget = false)
            s = rest.state
            now = rest.now
        }
        val after = run(s, now, 1 * minute, inTarget = true)
        reached = after.decisions.all { it == Decision.DailyLimitReached }
        assertTrue(reached)
        assertTrue(s.dailyUsedMs >= 60 * minute)
    }

    @Test
    fun `un dia nuevo reinicia el uso diario`() {
        val full = UsageState(dayKey = day, dailyUsedMs = 60 * minute)
        val sameDay = limiter.tick(full, 1_000L, tickMs, true, day)
        assertEquals(Decision.DailyLimitReached, sameDay.second)
        val nextDay = limiter.tick(full, 1_000L, tickMs, true, "2026-297")
        assertTrue(nextDay.second is Decision.Allow)
        assertEquals(tickMs, nextDay.first.dailyUsedMs)
    }
}
