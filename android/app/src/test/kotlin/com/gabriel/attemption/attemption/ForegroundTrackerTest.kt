package com.gabriel.attemption.attemption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {

    private val ig = "com.instagram.android"
    private val launcher = "com.hihonor.android.launcher"
    private fun tracker() = ForegroundTracker(setOf(ig, "com.zhiliaoapp.musically"))
    private fun resumed(t: Long, pkg: String) = AppEvent(t, AppEvent.Kind.RESUMED, pkg)
    private fun paused(t: Long, pkg: String) = AppEvent(t, AppEvent.Kind.PAUSED, pkg)

    @Test
    fun `cuenta el tiempo exacto entre abrir y salir de Instagram`() {
        val t = tracker()
        val used = t.advance(0, 100_000, listOf(resumed(10_000, ig), paused(97_000, ig), resumed(97_000, launcher)))
        assertEquals(87_000, used)
        assertEquals(launcher, t.currentApp)
        assertEquals(97_000, t.lastTargetAt)
    }

    @Test
    fun `sin eventos nuevos sigue contando mientras Instagram esta al frente`() {
        // El caso que fallaba antes: al hacer scroll Instagram no genera eventos.
        val t = tracker()
        t.advance(0, 5_000, listOf(resumed(1_000, ig)))
        val used = t.advance(5_000, 65_000, emptyList())
        assertEquals(60_000, used)
        assertTrue(t.inTarget)
    }

    @Test
    fun `los cambios de pantalla dentro de Instagram no cortan el conteo`() {
        // Secuencia real registrada en un Honor: Main -> Modal en el mismo milisegundo.
        val t = tracker()
        val events = listOf(
            resumed(1_000, ig),
            paused(5_000, ig),
            resumed(5_000, ig),
            paused(9_000, ig),
            resumed(9_001, ig),
        )
        assertEquals(19_000 - 1, t.advance(0, 20_000, events))
    }

    @Test
    fun `apagar la pantalla detiene el conteo`() {
        val t = tracker()
        val used = t.advance(0, 60_000, listOf(resumed(0, ig), AppEvent(30_000, AppEvent.Kind.SCREEN_OFF, "android")))
        assertEquals(30_000, used)
        assertFalse(t.inTarget)
    }

    @Test
    fun `al reanudar tras un cierre recupera el uso ocurrido mientras estaba cerrado`() {
        val t = tracker()
        t.restore(ig, 50_000) // al cerrarse, Instagram estaba al frente
        val used = t.advance(50_000, 500_000, listOf(paused(410_000, ig), resumed(410_000, launcher)))
        assertEquals(360_000, used)
        assertFalse(t.inTarget)
    }

    @Test
    fun `ignora eventos fuera del intervalo`() {
        val t = tracker()
        val used = t.advance(10_000, 20_000, listOf(resumed(5_000, ig), resumed(25_000, ig)))
        assertEquals(0, used)
    }
}
