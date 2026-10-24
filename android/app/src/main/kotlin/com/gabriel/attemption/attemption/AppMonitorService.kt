package com.gabriel.attemption.attemption

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Servicio de monitoreo. Corre en su propio proceso (":monitor"), sin el motor de Flutter,
 * para ocupar poca memoria y que el sistema no lo cierre cuando una app pesada pide memoria.
 *
 * - El uso se mide con las marcas de hora de los eventos ([ForegroundTracker]); si el sistema
 *   cierra el servicio, al volver recupera lo que se usó mientras estaba cerrado.
 * - No revisa nada con la pantalla apagada, y el intervalo se adapta a lo que pasa.
 */
class AppMonitorService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var store: UsageStore

    private val limits = UsageLimits()
    private val limiter = UsageLimiter(limits)
    private val tracker = ForegroundTracker(TARGET_APPS)
    private val handler = Handler(Looper.getMainLooper())

    private var state = UsageState()
    private var processedUntil = 0L
    private var screenOn = true
    private var lastSavedAt = 0L
    private var lastDecisionKind = ""
    private var lastNotificationText = ""
    private var lastPublished: Map<String, Any>? = null

    private var overlayView: TextView? = null
    private var overlayShownAt = 0L

    private val checkRunnable = Runnable { runCheck() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    runCheck() // contabiliza hasta ahora y guarda; no programa más revisiones
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    runCheck()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        store = UsageStore(this)
        state = store.load()
        restoreTracker(System.currentTimeMillis())
        isRunning = true
        Log.i(TAG, "Servicio creado. Estado guardado: $state, eventos procesados hasta $processedUntil")

        createNotificationChannel()
        startInForeground()

        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // ya no estaba registrado
        }
        hideOverlay()
        save()
        isRunning = false
        liveStatus = null
        liveState = null
        notifyStatusChanged()
        super.onDestroy()
    }

    /**
     * Si hay un punto guardado reciente, se continúa desde ahí y se cuenta el uso ocurrido
     * mientras el servicio estuvo cerrado. Si no (primera vez o protección recién activada),
     * solo se averigua qué app está al frente, sin contar uso anterior.
     */
    private fun restoreTracker(now: Long) {
        val saved = store.loadTracker()
        if (saved.processedUntil > 0 && now - saved.processedUntil <= MAX_CATCH_UP_MS) {
            tracker.restore(saved.currentApp, state.lastSeenInTargetAt)
            processedUntil = saved.processedUntil
        } else {
            val to = now - SETTLE_MS
            tracker.restore("", state.lastSeenInTargetAt)
            tracker.advance(now - WARM_UP_LOOKBACK_MS, to, readEvents(now - WARM_UP_LOOKBACK_MS, to))
            processedUntil = to
        }
    }

    private fun runCheck() {
        handler.removeCallbacks(checkRunnable)
        val decision = try {
            checkUsage()
        } catch (e: Exception) {
            Log.e(TAG, "Error en la revisión", e)
            null
        }
        if (screenOn) handler.postDelayed(checkRunnable, nextDelay(decision))
    }

    private fun checkUsage(): Decision {
        val now = System.currentTimeMillis()
        // Los eventos del último segundo pueden no estar registrados aún: se procesan en la próxima revisión.
        val to = now - SETTLE_MS
        val used = if (to > processedUntil) tracker.advance(processedUntil, to, readEvents(processedUntil, to)) else 0L
        processedUntil = maxOf(processedUntil, to)

        val (newState, decision) = limiter.tick(
            state, now, used, tracker.inTarget, tracker.lastTargetAt, UsageStore.dayKey(now),
        )
        state = newState
        liveState = newState

        val kind = decision::class.simpleName ?: ""
        if (kind != lastDecisionKind || used > CATCH_UP_LOG_MS) {
            Log.d(TAG, "app='${tracker.currentApp}' decisión=$kind usado=$used hoy=${state.dailyUsedMs} sesión=${state.sessionUsedMs}")
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

        // Se guarda al cambiar de situación, al apagar la pantalla o cada 30 s.
        // Si el sistema cierra el servicio entre medio, el uso se recupera desde los eventos.
        if (kind != lastDecisionKind || !screenOn || now - lastSavedAt >= SAVE_INTERVAL_MS) save()
        lastDecisionKind = kind

        publishStatus(now)
        updateNotification(now)
        return decision
    }

    /**
     * Cada cuánto revisar: lo justo para cortar a tiempo dentro de una app limitada,
     * y más espaciado fuera de ellas. Con la pantalla apagada no se revisa (ver screenReceiver).
     */
    private fun nextDelay(decision: Decision?): Long = when (decision) {
        is Decision.Allow -> {
            val untilLimit = minOf(decision.sessionLeftMs, decision.dailyLeftMs) + SETTLE_MS + 100
            untilLimit.coerceIn(500L, ACTIVE_INTERVAL_MS)
        }
        is Decision.SessionEnded, is Decision.Resting, is Decision.DailyLimitReached -> BLOCKED_INTERVAL_MS
        else -> IDLE_INTERVAL_MS
    }

    private fun readEvents(from: Long, to: Long): List<AppEvent> {
        val result = mutableListOf<AppEvent>()
        val events = usageStatsManager.queryEvents(from, to) ?: return result
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> AppEvent.Kind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> AppEvent.Kind.PAUSED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> AppEvent.Kind.SCREEN_OFF
                else -> null
            } ?: continue
            result += AppEvent(event.timeStamp, kind, event.packageName ?: "")
        }
        return result
    }

    private fun save() {
        store.save(state, TrackerState(processedUntil, tracker.currentApp))
        lastSavedAt = System.currentTimeMillis()
    }

    /** Publica el estado para la pantalla solo si cambió. */
    private fun publishStatus(now: Long) {
        val status = UsageStatus.toMap(state, limits, now, UsageStore.dayKey(now), isRunning, store.protectionEnabled)
        liveStatus = status
        if (status == lastPublished) return
        lastPublished = status
        notifyStatusChanged()
    }

    private fun notifyStatusChanged() {
        contentResolver.notifyChange(MonitorProvider.statusUri(packageName), null)
    }

    /** Actualiza la notificación fija solo cuando cambia su texto (como máximo una vez por minuto). */
    private fun updateNotification(now: Long) {
        val text = UsageStatus.notificationText(state, limits, now, UsageStore.dayKey(now))
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text))
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

        /** Margen para que los eventos más recientes ya estén registrados por el sistema. */
        private const val SETTLE_MS = 1_000L
        private const val ACTIVE_INTERVAL_MS = 5_000L // dentro de una app limitada
        private const val IDLE_INTERVAL_MS = 3_000L // fuera de ellas: solo detectar que se abrió una
        private const val BLOCKED_INTERVAL_MS = 2_000L // durante un bloqueo: volver a sacar al usuario rápido
        private const val SAVE_INTERVAL_MS = 30_000L
        private const val WARM_UP_LOOKBACK_MS = 60 * 60_000L
        private const val MAX_CATCH_UP_MS = 6 * 60 * 60_000L
        private const val CATCH_UP_LOG_MS = 10_000L
        private const val OVERLAY_MIN_VISIBLE_MS = 5_000L

        val TARGET_APPS = setOf("com.instagram.android", "com.zhiliaoapp.musically")

        /** `true` mientras el servicio está creado en este proceso (":monitor"). */
        @Volatile
        var isRunning = false
            private set

        /** Último estado calculado por el servicio; lo lee [MonitorController] en este mismo proceso. */
        @Volatile
        var liveStatus: Map<String, Any>? = null
            private set

        @Volatile
        var liveState: UsageState? = null
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
