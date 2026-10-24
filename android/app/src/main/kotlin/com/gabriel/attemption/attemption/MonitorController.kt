package com.gabriel.attemption.attemption

import android.content.Context
import android.os.Bundle

/**
 * Operaciones del monitor. Corre en el proceso ":monitor" y la pantalla las invoca
 * a través de [MonitorProvider].
 */
object MonitorController {

    private val limits = UsageLimits()

    enum class StartResult { STARTED, MISSING_PERMISSIONS }
    enum class StopResult { STOPPED, LOCKED }

    /** Estado actual: el del servicio en memoria si corre; si no, el guardado en disco. */
    fun status(context: Context): Map<String, Any> {
        AppMonitorService.liveStatus?.let { if (AppMonitorService.isRunning) return it }
        val store = UsageStore(context)
        val now = System.currentTimeMillis()
        return UsageStatus.toMap(
            store.load(), limits, now, UsageStore.dayKey(now),
            running = AppMonitorService.isRunning,
            protectionEnabled = store.protectionEnabled,
        )
    }

    fun start(context: Context): StartResult {
        if (!Permissions.allGranted(context)) return StartResult.MISSING_PERMISSIONS
        UsageStore(context).protectionEnabled = true
        AppMonitorService.start(context)
        return StartResult.STARTED
    }

    fun stop(context: Context): StopResult {
        val store = UsageStore(context)
        val now = System.currentTimeMillis()
        val state = AppMonitorService.liveState ?: store.load()
        if (AppMonitorService.isRunning && UsageStatus.isLocked(state, limits, now, UsageStore.dayKey(now))) {
            return StopResult.LOCKED
        }
        store.protectionEnabled = false
        AppMonitorService.stop(context)
        store.clearTracker()
        return StopResult.STOPPED
    }

    fun toBundle(map: Map<String, Any>): Bundle = Bundle().apply {
        map.forEach { (key, value) ->
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
                is String -> putString(key, value)
            }
        }
    }
}
