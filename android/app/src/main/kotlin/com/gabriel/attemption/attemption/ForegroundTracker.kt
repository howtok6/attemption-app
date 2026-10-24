package com.gabriel.attemption.attemption

/** Evento de uso reducido a lo que importa para saber qué app está en primer plano. */
data class AppEvent(val time: Long, val kind: Kind, val packageName: String) {
    enum class Kind { RESUMED, PAUSED, SCREEN_OFF }
}

/**
 * Sigue qué app está en primer plano y cuánto tiempo pasan en pantalla las apps limitadas,
 * usando las marcas de hora de los eventos (no los ticks del servicio).
 *
 * Por eso el conteo es exacto aunque el servicio revise con poca frecuencia, y si el sistema
 * lo cierra, al volver recupera el tiempo de uso que ocurrió mientras estaba muerto.
 * Kotlin puro, sin dependencias de Android, para poder probarlo con tests unitarios.
 */
class ForegroundTracker(private val targets: Set<String>) {

    /** App en primer plano al final del último intervalo procesado ("" si ninguna). */
    var currentApp: String = ""
        private set

    /** Último instante en que una app limitada estuvo en primer plano. */
    var lastTargetAt: Long = 0L
        private set

    val inTarget: Boolean get() = currentApp in targets

    fun restore(currentApp: String, lastTargetAt: Long) {
        this.currentApp = currentApp
        this.lastTargetAt = lastTargetAt
    }

    /**
     * Procesa los eventos del intervalo [from, to) en orden cronológico y devuelve
     * cuántos ms estuvo una app limitada en primer plano dentro de ese intervalo.
     */
    fun advance(from: Long, to: Long, events: List<AppEvent>): Long {
        if (to <= from) return 0L
        var used = 0L
        var cursor = from
        for (event in events) {
            if (event.time < from || event.time >= to) continue
            if (inTarget) {
                used += event.time - cursor
                lastTargetAt = event.time
            }
            cursor = event.time
            when (event.kind) {
                AppEvent.Kind.RESUMED -> currentApp = event.packageName
                AppEvent.Kind.PAUSED -> if (event.packageName == currentApp) currentApp = ""
                AppEvent.Kind.SCREEN_OFF -> currentApp = ""
            }
        }
        if (inTarget) {
            used += to - cursor
            lastTargetAt = to
        }
        return used
    }
}
