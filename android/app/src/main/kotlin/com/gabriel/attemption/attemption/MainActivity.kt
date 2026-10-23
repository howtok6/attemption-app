package com.gabriel.attemption.attemption

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val store by lazy { UsageStore(this) }
    private val limits = UsageLimits()
    private var statusListener: ((Map<String, Any>) -> Unit)? = null

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
                "getStatus" -> result.success(currentStatus())
                "startMonitorService" -> {
                    if (!Permissions.allGranted(this)) {
                        result.error("MISSING_PERMISSIONS", "Faltan permisos para iniciar la protección", null)
                    } else {
                        store.protectionEnabled = true
                        AppMonitorService.start(this)
                        result.success(true)
                    }
                }
                "stopMonitorService" -> {
                    val now = System.currentTimeMillis()
                    val locked = AppMonitorService.isRunning &&
                        UsageStatus.isLocked(store.load(), limits, now, UsageStore.dayKey(now))
                    if (locked) {
                        result.error("LOCKED", "No se puede desactivar durante un descanso o con el límite diario cumplido", null)
                    } else {
                        store.protectionEnabled = false
                        AppMonitorService.stop(this)
                        result.success(true)
                    }
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(messenger, STATUS_CHANNEL).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                removeStatusListener()
                val listener: (Map<String, Any>) -> Unit = { events.success(it) }
                statusListener = listener
                StatusBus.addListener(listener)
                events.success(currentStatus())
            }

            override fun onCancel(arguments: Any?) = removeStatusListener()
        })
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        removeStatusListener()
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun removeStatusListener() {
        statusListener?.let { StatusBus.removeListener(it) }
        statusListener = null
    }

    /** Estado actual: si el servicio no corre, se calcula desde lo guardado en disco. */
    private fun currentStatus(): Map<String, Any> {
        val now = System.currentTimeMillis()
        return UsageStatus.toMap(
            store.load(),
            limits,
            now,
            UsageStore.dayKey(now),
            AppMonitorService.isRunning,
            store.protectionEnabled,
        )
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
