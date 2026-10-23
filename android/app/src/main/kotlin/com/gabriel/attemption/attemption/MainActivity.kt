package com.gabriel.attemption.attemption

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.gabriel.attemption/permissions"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
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
                "startMonitorService" -> {
                    if (!Permissions.allGranted(this)) {
                        result.error("MISSING_PERMISSIONS", "Faltan permisos para iniciar la protección", null)
                    } else {
                        UsageStore(this).protectionEnabled = true
                        AppMonitorService.start(this)
                        result.success(true)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
}
