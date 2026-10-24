package com.gabriel.attemption.attemption

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private var statusObserver: ContentObserver? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(messenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "checkUsagePermission" -> result.success(Permissions.hasUsageAccess(this))
                "requestUsagePermission" -> {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    result.success(true)
                }
                "checkOverlayPermission" -> result.success(Permissions.canDrawOverlays(this))
                "requestOverlayPermission" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    }
                    result.success(true)
                }
                "checkNotificationPermission" ->
                    result.success(NotificationManagerCompat.from(this).areNotificationsEnabled())
                "requestNotificationPermission" -> {
                    requestNotificationPermission()
                    result.success(true)
                }
                "checkBatteryOptimization" -> result.success(isIgnoringBatteryOptimizations())
                "requestBatteryExemption" -> {
                    requestBatteryExemption()
                    result.success(true)
                }
                "getManufacturer" -> result.success(Build.MANUFACTURER ?: "")
                "openBatteryGuide" -> {
                    openUrl(batteryGuideUrl())
                    result.success(true)
                }
                "hasAppLaunchSettings" -> result.success(appLaunchSettingsIntent() != null)
                "openAppLaunchSettings" -> {
                    appLaunchSettingsIntent()?.let { openSafely(it) }
                    result.success(true)
                }
                "getStatus" -> result.success(currentStatus())
                "startMonitorService" -> {
                    if (callMonitor(MonitorProvider.METHOD_START) == MonitorController.StartResult.STARTED.name) {
                        result.success(true)
                    } else {
                        result.error("MISSING_PERMISSIONS", "Faltan permisos para iniciar la protección", null)
                    }
                }
                "stopMonitorService" -> {
                    if (callMonitor(MonitorProvider.METHOD_STOP) == MonitorController.StopResult.STOPPED.name) {
                        result.success(true)
                    } else {
                        result.error("LOCKED", "No se puede desactivar durante un descanso o con el límite diario cumplido", null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(messenger, STATUS_CHANNEL).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                removeStatusObserver()
                // El servicio avisa cada vez que su estado cambia (notifyChange en la URI de estado).
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        events.success(currentStatus())
                    }
                }
                statusObserver = observer
                contentResolver.registerContentObserver(MonitorProvider.statusUri(packageName), false, observer)
                events.success(currentStatus())
            }

            override fun onCancel(arguments: Any?) = removeStatusObserver()
        })
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        removeStatusObserver()
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun removeStatusObserver() {
        statusObserver?.let { contentResolver.unregisterContentObserver(it) }
        statusObserver = null
    }

    /** Llama al proceso ":monitor" (MonitorProvider) y devuelve su respuesta. */
    private fun callMonitorBundle(method: String): Bundle? = try {
        contentResolver.call(MonitorProvider.statusUri(packageName), method, null, null)
    } catch (e: Exception) {
        null
    }

    private fun callMonitor(method: String): String? = callMonitorBundle(method)?.getString(MonitorProvider.KEY_RESULT)

    /** Estado actual del monitor, convertido a un mapa para Flutter. */
    private fun currentStatus(): Map<String, Any?> {
        val bundle = callMonitorBundle(MonitorProvider.METHOD_STATUS) ?: return emptyMap()
        @Suppress("DEPRECATION")
        return bundle.keySet().associateWith { bundle.get(it) }
    }

    /**
     * Pantalla del fabricante donde se permite que la app arranque sola y siga en segundo plano
     * (Honor y Huawei la llaman "Inicio de aplicaciones"). `null` si el teléfono no la tiene.
     */
    private fun appLaunchSettingsIntent(): Intent? {
        val candidates = listOf(
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        return candidates
            .map { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .firstOrNull { it.resolveActivity(packageManager) != null }
    }

    private fun openSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // La pantalla existe pero el fabricante no permite abrirla desde otra app.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun requestNotificationPermission() {
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        val askedBefore = prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedBefore) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        // Si ya se pidió una vez, Android no vuelve a mostrar el diálogo: se abren los ajustes de la app.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = getSystemService(POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Página de dontkillmyapp.com con los pasos para la marca del teléfono. */
    private fun batteryGuideUrl(): String {
        val brand = (Build.MANUFACTURER ?: "").lowercase()
        val slug = when (brand) {
            "redmi", "poco" -> "xiaomi"
            "honor" -> "huawei"
            in GUIDE_BRANDS -> brand
            else -> ""
        }
        return "https://dontkillmyapp.com/$slug"
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // Sin navegador instalado: no hay nada que abrir.
        }
    }

    companion object {
        private const val METHOD_CHANNEL = "com.gabriel.attemption/permissions"
        private const val STATUS_CHANNEL = "com.gabriel.attemption/status"
        private const val UI_PREFS = "attemption_ui"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        private const val REQUEST_NOTIFICATIONS = 100
        private val GUIDE_BRANDS = setOf(
            "xiaomi", "samsung", "oneplus", "huawei", "oppo", "vivo", "realme", "motorola",
            "lenovo", "sony", "nokia", "asus", "meizu", "google", "htc", "tecno", "wiko",
        )
    }
}
