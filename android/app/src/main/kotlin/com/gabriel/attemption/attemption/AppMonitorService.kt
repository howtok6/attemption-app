package com.gabriel.attemption.attemption

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AppMonitorService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var store: UsageStore

    private val limits = UsageLimits()
    private val limiter = UsageLimiter(limits)
    private val handler = Handler(Looper.getMainLooper())

    private var state = UsageState()
    private var currentApp = ""
    private var lastEventQueryAt = 0L
    private var lastTickAt = 0L

    private var overlayView: TextView? = null
    private var overlayShownAt = 0L
    private var lastNotificationText = ""

    private val monitorRunnable = object : Runnable {
        override fun run() {
            try {
                checkUsage()
            } catch (e: Exception) {
                Log.e(TAG, "Error en el ciclo de monitoreo", e)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        store = UsageStore(this)
        state = store.load()
        isRunning = true
        createNotificationChannel()
        startInForeground()
        handler.post(monitorRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(monitorRunnable)
        hideOverlay()
        store.save(state)
        isRunning = false
        publishStatus(System.currentTimeMillis())
        super.onDestroy()
    }

    private fun checkUsage() {
        val now = System.currentTimeMillis()
        // Acotar el salto evita sumar minutos de golpe si el teléfono estuvo dormido.
        val delta = if (lastTickAt == 0L) 0L else (now - lastTickAt).coerceIn(0L, MAX_TICK_DELTA_MS)
        lastTickAt = now

        val inTarget = updateForegroundApp(now) in TARGET_APPS
        val (newState, decision) = limiter.tick(state, now, delta, inTarget, UsageStore.dayKey(now))
        if (newState != state) {
            state = newState
            store.save(newState)
        }

        when (decision) {
            is Decision.Idle -> if (now - overlayShownAt >= OVERLAY_MIN_VISIBLE_MS) hideOverlay()
            is Decision.Allow -> hideOverlay()
            is Decision.SessionEnded -> block(
                now,
                "Sesión de ${minutes(limits.sessionMaxMs)} min terminada.\n¡Descansa ${minutes(limits.restMs)} min!",
            )
            is Decision.Resting -> block(now, "Tiempo de descanso.\nFaltan ${minutesCeil(decision.restLeftMs)} min.")
            is Decision.DailyLimitReached -> block(now, "Has alcanzado tu límite diario de ${minutes(limits.dailyMaxMs)} min.")
        }

        publishStatus(now)
        updateNotification(now)
    }

    private fun publishStatus(now: Long) {
        StatusBus.publish(
            UsageStatus.toMap(state, limits, now, UsageStore.dayKey(now), isRunning, store.protectionEnabled)
        )
    }

    /** Actualiza la notificación fija solo cuando cambia su texto (como máximo una vez por minuto). */
    private fun updateNotification(now: Long) {
        val text = UsageStatus.notificationText(state, limits, now, UsageStore.dayKey(now))
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text))
    }

    /**
     * Mantiene en memoria la app en primer plano y la actualiza solo con los eventos nuevos.
     * Antes se miraban únicamente los últimos 60 s: si Instagram no cambiaba de pantalla interna
     * (lo normal al hacer scroll) no había evento y el servicio creía que el usuario había salido.
     */
    private fun updateForegroundApp(now: Long): String {
        val from = if (lastEventQueryAt == 0L) now - INITIAL_LOOKBACK_MS else lastEventQueryAt - EVENT_OVERLAP_MS
        val events = usageStatsManager.queryEvents(from, now) ?: return currentApp
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> currentApp = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (event.packageName == currentApp) currentApp = ""
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> currentApp = ""
            }
        }
        lastEventQueryAt = now
        return currentApp
    }

    /** Muestra (o actualiza) el aviso y manda al usuario a la pantalla de inicio. */
    private fun block(now: Long, message: String) {
        overlayShownAt = now
        showOrUpdateOverlay(message)
        goHome()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(home)
        } catch (e: Exception) {
            // Si Android no permite abrir el inicio, la capa sigue bloqueando la app por encima.
            Log.w(TAG, "No se pudo volver a la pantalla de inicio", e)
        }
    }

    private fun showOrUpdateOverlay(message: String) {
        val label = "Attemption\n\n$message\n\nToca para cerrar"
        overlayView?.let {
            it.text = label
            return
        }
        if (!Permissions.canDrawOverlays(this)) {
            Log.w(TAG, "Sin permiso para mostrar sobre otras apps")
            return
        }
        val view = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#ECE6F2"))
            textSize = 24f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F2241538")) // morado muy oscuro, casi opaco
            setPadding(32, 32, 32, 32)
            setOnClickListener { hideOverlay() }
        }
        try {
            windowManager.addView(view, overlayParams())
            overlayView = view
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mostrar la capa de bloqueo", e)
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo quitar la capa de bloqueo", e)
        }
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
    }

    private fun startInForeground() {
        val now = System.currentTimeMillis()
        lastNotificationText = UsageStatus.notificationText(state, limits, now, UsageStore.dayKey(now))
        val notification = createNotification(lastNotificationText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Attemption Monitor", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Attemption está activo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "AttemptionMonitor"
        private const val CHANNEL_ID = "AttemptionServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 2_000L
        private const val MAX_TICK_DELTA_MS = 5_000L
        private const val INITIAL_LOOKBACK_MS = 60 * 60_000L
        private const val EVENT_OVERLAP_MS = 2_000L
        private const val OVERLAY_MIN_VISIBLE_MS = 5_000L

        val TARGET_APPS = setOf("com.instagram.android", "com.zhiliaoapp.musically")

        /** `true` mientras el servicio está creado en este proceso. */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AppMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppMonitorService::class.java))
        }

        private fun minutes(ms: Long): Long = ms / 60_000L
        private fun minutesCeil(ms: Long): Long = (ms + 59_999L) / 60_000L
    }
}
