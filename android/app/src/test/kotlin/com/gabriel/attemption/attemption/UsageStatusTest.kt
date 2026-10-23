package com.gabriel.attemption.attemption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatusTest {

    private val limits = UsageLimits()
    private val minute = 60_000L
    private val day = "2026-296"

    @Test
    fun `el descanso bloquea la desactivacion y muestra los minutos que faltan`() {
        val state = UsageState(dayKey = day, dailyUsedMs = 5 * minute, restEndsAt = 10 * minute)
        val now = 3 * minute + 1_000L
        assertEquals(UsageStatus.RESTING, UsageStatus.limitState(state, limits, now, day))
        assertTrue(UsageStatus.isLocked(state, limits, now, day))
        assertEquals("Descanso: faltan 7 min · Hoy: 5 de 60 min", UsageStatus.notificationText(state, limits, now, day))
    }

    @Test
    fun `con el limite diario cumplido el estado es bloqueado`() {
        val state = UsageState(dayKey = day, dailyUsedMs = 60 * minute)
        assertEquals(UsageStatus.BLOCKED, UsageStatus.limitState(state, limits, 0L, day))
        assertTrue(UsageStatus.isLocked(state, limits, 0L, day))
    }

    @Test
    fun `el uso de un dia anterior no cuenta`() {
        val state = UsageState(dayKey = "2026-295", dailyUsedMs = 60 * minute, sessionUsedMs = 2 * minute)
        assertEquals(UsageStatus.ALLOWED, UsageStatus.limitState(state, limits, 0L, day))
        assertFalse(UsageStatus.isLocked(state, limits, 0L, day))
        assertEquals("Hoy: 0 de 60 min · Sesión: 2 de 5 min", UsageStatus.notificationText(state, limits, 0L, day))
    }

    @Test
    fun `con el servicio detenido el estado enviado a Flutter es inactivo`() {
        val state = UsageState(dayKey = day, dailyUsedMs = 60 * minute)
        val map = UsageStatus.toMap(state, limits, 0L, day, running = false, protectionEnabled = true)
        assertEquals(UsageStatus.INACTIVE, map["state"])
        assertEquals(true, map["protectionEnabled"])
        assertEquals(60 * minute, map["dailyUsedMs"])
    }
}
