package com.gabriel.attemption.attemption

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Canal en memoria entre el servicio y la pantalla (ambos viven en el mismo proceso).
 * El servicio publica su estado en cada revisión y MainActivity lo reenvía a Flutter.
 */
object StatusBus {
    private val listeners = CopyOnWriteArraySet<(Map<String, Any>) -> Unit>()

    fun publish(status: Map<String, Any>) {
        listeners.forEach { it(status) }
    }

    fun addListener(listener: (Map<String, Any>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Map<String, Any>) -> Unit) {
        listeners.remove(listener)
    }
}
