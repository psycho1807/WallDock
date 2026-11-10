package app.walldock

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Tourne pendant qu'une appli du dock est ouverte et ramène la tablette
 * au tableau de bord après le délai d'inactivité.
 *
 * - Avec la détection d'activité (accessibilité) : le délai repart à chaque geste.
 * - Sans elle : retour après le délai compté depuis l'ouverture de l'appli.
 */
class ReturnService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutMs = 0L
    private var startedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (check()) handler.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seconds = intent?.getIntExtra(EXTRA_TIMEOUT, 0) ?: 0
        timeoutMs = seconds * 1000L
        startedAt = SystemClock.elapsedRealtime()
        AppState.lastInteraction = startedAt

        // Obligatoire juste après startForegroundService(), même si on s'arrête aussitôt
        startInForeground(seconds)

        handler.removeCallbacks(tick)
        if (timeoutMs <= 0) {
            stopSelf()
        } else {
            handler.postDelayed(tick, CHECK_EVERY_MS)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    /** Renvoie vrai s'il faut continuer à surveiller. */
    private fun check(): Boolean {
        if (AppState.dashboardVisible) {
            stopSelf()
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val reference = if (AppState.accessibilityActive) maxOf(startedAt, AppState.lastInteraction) else startedAt
        if (now - reference >= timeoutMs) {
            returnToDashboard()
            stopSelf()
            return false
        }
        return true
    }

    private fun returnToDashboard() {
        // 1) Bouton Accueil virtuel : fonctionne toujours quand WallDock est l'écran d'accueil
        InteractionService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        // 2) Ouverture directe : autorisée par Android si "affichage par-dessus" est accordé
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        } catch (e: Exception) {
            // Bloqué par Android : le bouton Accueil virtuel ci-dessus a pris le relais
        }
    }

    private fun startInForeground(seconds: Int) {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Retour automatique", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_walldock)
            .setContentTitle("WallDock")
            .setContentText("Retour au tableau de bord après $seconds s d’inactivité")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_TIMEOUT = "timeout"
        private const val CHANNEL_ID = "walldock_return"
        private const val NOTIFICATION_ID = 1
        private const val CHECK_EVERY_MS = 2_000L

        fun start(context: Context, packageName: String, seconds: Int) {
            val intent = Intent(context, ReturnService::class.java)
            intent.putExtra(EXTRA_PACKAGE, packageName)
            intent.putExtra(EXTRA_TIMEOUT, seconds)
            context.startForegroundService(intent)
        }
    }
}
