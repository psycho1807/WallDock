package app.walldock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import app.walldock.esphome.Entity
import app.walldock.esphome.EsphomeServer
import java.security.MessageDigest

/**
 * Fait apparaître la tablette dans Home Assistant comme un appareil ESPHome :
 * serveur de l'API native sur le port 6053 + annonce sur le réseau (mDNS).
 */
class ApiService : Service(), EsphomeServer.Handler {

    private val main = Handler(Looper.getMainLooper())
    private var server: EsphomeServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    lateinit var deviceName: String
        private set
    lateinit var friendlyName: String
        private set

    private val battery = Entity(Entity.Kind.SENSOR, "batterie", "Batterie")
        .unit("%").deviceClass("battery").measurement().category(Entity.CATEGORY_DIAGNOSTIC)
    private val charging = Entity(Entity.Kind.BINARY_SENSOR, "en_charge", "En charge")
        .deviceClass("battery_charging").category(Entity.CATEGORY_DIAGNOSTIC)
    private val screen = Entity(Entity.Kind.SWITCH, "ecran", "Écran").icon("mdi:tablet-dashboard")
    private val brightness = Entity(Entity.Kind.NUMBER, "luminosite", "Luminosité")
        .unit("%").range(1f, 100f, 1f).icon("mdi:brightness-6")
    private val currentApp = Entity(Entity.Kind.TEXT_SENSOR, "appli_ouverte", "Appli ouverte").icon("mdi:application")
    private val backToDashboard = Entity(Entity.Kind.BUTTON, "retour_tableau_de_bord", "Revenir au tableau de bord")
        .icon("mdi:view-dashboard")
    private val reload = Entity(Entity.Kind.BUTTON, "recharger", "Recharger le tableau de bord")
        .icon("mdi:refresh").category(Entity.CATEGORY_CONFIG)

    private val entities = listOf(battery, charging, screen, brightness, currentApp, backToDashboard, reload)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0 && scale > 0) server?.setState(battery, (level * 100f / scale).toInt().toFloat())
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            server?.setState(charging, isCharging)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()

        val mac = pseudoMac()
        deviceName = "walldock-" + mac.replace(":", "").takeLast(6).lowercase()
        friendlyName = "WallDock " + Build.MODEL

        val info = EsphomeServer.DeviceInfo()
        info.name = deviceName
        info.friendlyName = friendlyName
        info.macAddress = mac
        info.model = "${Build.MANUFACTURER} ${Build.MODEL}"
        info.manufacturer = "WallDock"
        info.projectVersion = BuildConfig.VERSION_NAME

        val srv = EsphomeServer(info, entities, this, EsphomeServer.DEFAULT_PORT)
        server = srv
        srv.setState(screen, AppState.screenOn)
        srv.setState(brightness, if (AppState.brightness < 0) 100f else AppState.brightness)
        srv.setState(currentApp, AppState.currentApp)

        Thread {
            try {
                srv.start()
                main.post { registerMdns(mac) }
            } catch (e: Exception) {
                AppState.apiError = "Port ${EsphomeServer.DEFAULT_PORT} déjà utilisé (une autre appli, comme Kiosk Satellite ?)"
            }
        }.start()

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // déjà désinscrit
        }
        nsdListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (e: Exception) {
                // déjà retiré
            }
        }
        server?.stop()
        server = null
        super.onDestroy()
    }

    /** Nombre de connexions Home Assistant en cours. */
    fun connectedClients(): Int = server?.clientCount() ?: 0

    /** À appeler quand l'appli au premier plan change (tableau de bord ou appli du dock). */
    fun reportCurrentApp() {
        server?.setState(currentApp, AppState.currentApp)
    }

    /** À appeler quand l'écran est rallumé localement (toucher sur l'écran noir). */
    fun reportScreen() {
        server?.setState(screen, AppState.screenOn)
    }

    // ----- Commandes reçues de Home Assistant (fil réseau → fil principal) -----

    override fun onSwitch(entity: Entity, state: Boolean) {
        main.post {
            if (entity === screen) setScreen(state)
        }
    }

    override fun onNumber(entity: Entity, value: Float) {
        main.post {
            if (entity === brightness) {
                AppState.brightness = value.coerceIn(1f, 100f)
                AppState.controller?.applyScreen()
                server?.setState(brightness, AppState.brightness)
            }
        }
    }

    override fun onButton(entity: Entity) {
        main.post {
            when {
                entity === backToDashboard -> Dashboard.bringToFront(this)
                entity === reload -> AppState.controller?.reloadDashboard()
            }
        }
    }

    private fun setScreen(on: Boolean) {
        AppState.screenOn = on
        if (on) wakeUp()
        if (!AppState.dashboardVisible) Dashboard.bringToFront(this)
        AppState.controller?.applyScreen()
        server?.setState(screen, on)
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun wakeUp() {
        val power = getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "walldock:wake"
        )
        lock.acquire(3_000)
    }

    // ----- Annonce sur le réseau pour la découverte automatique par Home Assistant -----

    private fun registerMdns(mac: String) {
        // Dans l'émulateur Android (réseau 10.0.2.x), l'adresse annoncée est injoignable depuis
        // Home Assistant et écraserait l'adresse saisie à la main : on ne s'annonce pas.
        if (isEmulatorNetwork()) return
        val manager = getSystemService(NsdManager::class.java) ?: return
        val service = NsdServiceInfo()
        service.serviceName = deviceName
        service.serviceType = "_esphomelib._tcp"
        service.port = EsphomeServer.DEFAULT_PORT
        service.setAttribute("mac", mac.replace(":", "").lowercase())
        service.setAttribute("version", "2025.10.0")
        service.setAttribute("friendly_name", friendlyName)
        service.setAttribute("platform", "android")
        service.setAttribute("network", "wifi")
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = manager
            nsdListener = listener
        } catch (e: Exception) {
            // L'ajout manuel par adresse IP reste possible
        }
    }

    private fun isEmulatorNetwork(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val properties = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return false
        return properties.linkAddresses.any { it.address.hostAddress?.startsWith("10.0.2.") == true }
    }

    /**
     * Adresse « MAC » stable propre à WallDock, dérivée de l'identifiant Android de l'appareil.
     * Home Assistant s'en sert d'identifiant unique.
     */
    @SuppressLint("HardwareIds")
    private fun pseudoMac(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "walldock"
        val digest = MessageDigest.getInstance("SHA-256").digest(("walldock:$androidId").toByteArray())
        val bytes = digest.copyOf(6)
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte() // adresse locale, unicast
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun startInForeground() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Home Assistant", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_walldock)
            .setContentTitle("WallDock")
            .setContentText("Disponible pour Home Assistant")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "walldock_api"
        private const val NOTIFICATION_ID = 2

        @Volatile
        var instance: ApiService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ApiService::class.java))
        }
    }
}
