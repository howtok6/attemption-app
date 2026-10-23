package com.gabriel.attemption.attemption

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Calendar

class AppMonitorService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private val targetApps = listOf("com.instagram.android", "com.zhiliaoapp.musically")
    
    // Limits
    private val MAX_DAILY_TIME_MS = 60 * 60 * 1000L // 1 hour
    private val SESSION_MAX_TIME_MS = 5 * 60 * 1000L // 5 mins
    private val REST_TIME_MS = 10 * 60 * 1000L // 10 mins

    private var sessionStartTime = 0L
    private var blockEndTime = 0L
    private var isOverlayShowing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())
        
        handler.post(monitorRunnable)
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            checkUsage()
            handler.postDelayed(this, 2000) // Check every 2 seconds
        }
    }

    private fun checkUsage() {
        val foregroundApp = getForegroundApp()
        val isTargetApp = targetApps.contains(foregroundApp)
        val now = System.currentTimeMillis()

        // 1. Check Daily Total
        var dailyTotal = 0L
        if (isTargetApp) {
            dailyTotal = getDailyUsage(foregroundApp)
        }

        if (isTargetApp && dailyTotal >= MAX_DAILY_TIME_MS) {
            // Block completely
            showOverlay("Has alcanzado tu límite diario de 1 hora.")
            return
        }

        // 2. Check Session
        if (isTargetApp) {
            if (now < blockEndTime) {
                // Still in rest period!
                showOverlay("Tiempo de descanso. Faltan ${(blockEndTime - now) / 1000 / 60 + 1} minutos.")
                return
            }

            if (sessionStartTime == 0L) {
                sessionStartTime = now
            }
            
            val currentSessionTime = now - sessionStartTime
            if (currentSessionTime >= SESSION_MAX_TIME_MS) {
                // Start rest period
                blockEndTime = now + REST_TIME_MS
                sessionStartTime = 0L
                showOverlay("Sesión de 5 min terminada. ¡Descansa 10 min!")
            } else {
                hideOverlay()
            }
        } else {
            // Not in target app
            if (now >= blockEndTime) {
                // Rest period finished
                sessionStartTime = 0L
            }
            hideOverlay()
        }
    }

    private fun getForegroundApp(): String {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(time - 1000 * 60, time)
        val event = UsageEvents.Event()
        var currentApp = ""
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentApp = event.packageName
            }
        }
        return currentApp
    }

    private fun getDailyUsage(packageName: String): Long {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
        return stats[packageName]?.totalTimeInForeground ?: 0L
    }

    private fun showOverlay(message: String) {
        if (isOverlayShowing) return
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val context = this
        val textView = TextView(context).apply {
            text = "Attemption\n\n$message"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E6673AB7")) // Deep purple semi-transparent
            setPadding(32, 32, 32, 32)
        }
        
        overlayView = textView
        windowManager.addView(overlayView, params)
        isOverlayShowing = true
    }

    private fun hideOverlay() {
        if (!isOverlayShowing || overlayView == null) return
        windowManager.removeView(overlayView)
        overlayView = null
        isOverlayShowing = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "AttemptionServiceChannel",
                "Attemption Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "AttemptionServiceChannel")
            .setContentTitle("Attemption está activo")
            .setContentText("Monitoreando el uso de redes sociales.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(monitorRunnable)
        hideOverlay()
    }
}
