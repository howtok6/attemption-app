package com.gabriel.attemption.attemption

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Vuelve a iniciar la protección al encender el teléfono o tras actualizar la app. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        // Solo si el usuario activó la protección y los permisos siguen concedidos;
        // sin el permiso de superposición el servicio no podría bloquear.
        if (!UsageStore(context).protectionEnabled) return
        if (!Permissions.allGranted(context)) return
        AppMonitorService.start(context)
    }

    companion object {
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
