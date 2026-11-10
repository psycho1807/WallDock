package app.walldock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Tourne pendant qu'une appli du dock est ouverte.
 *
 * - Affiche un bouton flottant ⌂ par-dessus l'appli pour revenir au tableau de bord.
 *   Ce bouton visible est aussi ce qui autorise WallDock (Android 15+) à revenir
 *   au premier plan tout seul.
 * - Ramène la tablette au tableau de bord après le délai d'inactivité
 *   (sauf pour les applis « Vidéo », délai = 0).
 * - Avec la détection d'activité (accessibilité), le délai repart à chaque geste ;
 *   sans elle, il est compté depuis l'ouverture de l'appli.
 */
class ReturnService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutMs = 0L
    private var startedAt = 0L
    private var overlay: View? = null

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

        // Obligatoire juste après startForegroundService()
        startInForeground(seconds)
        showOverlay()

        handler.removeCallbacks(tick)
        handler.postDelayed(tick, CHECK_EVERY_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hideOverlay()
        super.onDestroy()
    }

    /** Renvoie vrai s'il faut continuer à surveiller. */
    private fun check(): Boolean {
        if (AppState.dashboardVisible) {
            stopSelf()
            return false
        }
        if (timeoutMs <= 0) return true // appli « Vidéo » : pas de retour automatique

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
        Dashboard.bringToFront(this)
    }

    private fun showOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return

        val button = TextView(this)
        button.text = "⌂"
        button.textSize = 24f
        button.setTextColor(Color.WHITE)
        button.gravity = Gravity.CENTER
        button.alpha = 0.85f
        val background = GradientDrawable()
        background.shape = GradientDrawable.OVAL
        background.setColor(0xCC0A1634.toInt())
        background.setStroke(dp(1), 0x993FD4FF.toInt())
        button.background = background
        button.setOnClickListener {
            returnToDashboard()
            stopSelf()
        }

        val params = WindowManager.LayoutParams(
            dp(48),
            dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.END
        params.x = dp(12)
        params.y = dp(12)

        try {
            getSystemService(WindowManager::class.java)?.addView(button, params)
            overlay = button
        } catch (e: Exception) {
            overlay = null
        }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        try {
            getSystemService(WindowManager::class.java)?.removeView(view)
        } catch (e: Exception) {
            // déjà retiré
        }
        overlay = null
    }

    private fun startInForeground(seconds: Int) {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Retour automatique", NotificationManager.IMPORTANCE_LOW)
        )
        val text = if (seconds > 0) "Retour au tableau de bord après $seconds s d’inactivité"
        else "Touchez ⌂ pour revenir au tableau de bord"
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_walldock)
            .setContentTitle("WallDock")
            .setContentText(text)
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

        /** [seconds] = 0 : pas de retour automatique, seulement le bouton ⌂. */
        fun start(context: Context, packageName: String, seconds: Int) {
            val intent = Intent(context, ReturnService::class.java)
            intent.putExtra(EXTRA_PACKAGE, packageName)
            intent.putExtra(EXTRA_TIMEOUT, seconds)
            context.startForegroundService(intent)
        }
    }
}
