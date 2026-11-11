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
import android.net.Uri
import android.speech.tts.TextToSpeech
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
import app.walldock.esphome.UserService
import java.security.MessageDigest
import java.util.Locale

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

    private val openSelect = Entity(Entity.Kind.SELECT, "ouvrir", "Ouvrir").icon("mdi:apps")
    private val alertShown = Entity(Entity.Kind.BINARY_SENSOR, "alerte_affichee", "Alerte affichée")
        .icon("mdi:alert-octagon")

    private val updateStatus = Entity(Entity.Kind.TEXT_SENSOR, "mise_a_jour", "Mise à jour")
        .icon("mdi:update").category(Entity.CATEGORY_DIAGNOSTIC)
    private val installUpdate = Entity(Entity.Kind.BUTTON, "installer_mise_a_jour", "Installer la mise à jour")
        .icon("mdi:download").category(Entity.CATEGORY_CONFIG)

    private val entities = listOf(
        battery, charging, screen, brightness, currentApp, openSelect, alertShown, backToDashboard, reload,
        updateStatus, installUpdate
    )

    private val updateListener: (String) -> Unit = { server?.setState(updateStatus, it) }

    /** Vérification des mises à jour : 1 min après le démarrage, puis toutes les 12 h. */
    private val updateCheck = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (!Prefs(this@ApiService).autoUpdate) {
                Updater.check(this@ApiService, install = false)
                main.postDelayed(this, UPDATE_INTERVAL_MS)
            } else if (!AppState.dashboardVisible || alertOverlay.isShowing) {
                // Pas d'installation par-dessus une appli ouverte ou une alerte : on réessaie plus tard
                main.postDelayed(this, UPDATE_RETRY_MS)
            } else {
                Updater.check(this@ApiService, install = true)
                main.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }

    // Actions appelables depuis HA : esphome.<appareil>_parler, _ouvrir_page, _ouvrir_appli
    private val speakService = UserService("parler").arg("message", UserService.ARG_STRING)
    private val openPageService = UserService("ouvrir_page").arg("adresse", UserService.ARG_STRING)
    private val openAppService = UserService("ouvrir_appli").arg("nom", UserService.ARG_STRING)
    private val showAlertService = UserService("afficher_alerte")
        .arg("titre", UserService.ARG_STRING)
        .arg("message", UserService.ARG_STRING)
        .arg("secondes", UserService.ARG_INT)
    private val closeAlertService = UserService("fermer_alerte")
    private val services = listOf(speakService, openPageService, openAppService, showAlertService, closeAlertService)

    private lateinit var alertOverlay: AlertOverlay

    /** Choix de la liste « Ouvrir » → nom de paquet (null pour le tableau de bord). */
    private var openTargets: Map<String, String?> = emptyMap()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<String>()

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

        refreshOpenTargets()
        val srv = EsphomeServer(info, entities, services, this, EsphomeServer.DEFAULT_PORT)
        server = srv
        srv.setState(screen, AppState.screenOn)
        srv.setState(brightness, if (AppState.brightness < 0) 100f else AppState.brightness)
        srv.setState(currentApp, AppState.currentApp)
        srv.setState(openSelect, DASHBOARD_OPTION)
        srv.setState(alertShown, false)
        srv.setState(updateStatus, Updater.status)
        Updater.addListener(updateListener)
        main.postDelayed(updateCheck, 60_000)
        alertOverlay = AlertOverlay(this) { server?.setState(alertShown, false) }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                ttsReady = true
                pendingSpeech.forEach { speak(it) }
                pendingSpeech.clear()
            }
        }

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
        main.removeCallbacks(updateCheck)
        Updater.removeListener(updateListener)
        alertOverlay.close(notify = false)
        tts?.shutdown()
        tts = null
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
        if (openTargets.containsKey(AppState.currentApp)) server?.setState(openSelect, AppState.currentApp)
    }

    /** À appeler quand les applis du dock changent : HA relit la liste « Ouvrir ». */
    fun onDockChanged() {
        refreshOpenTargets()
        server?.reconnectClients()
    }

    private fun refreshOpenTargets() {
        val targets = linkedMapOf<String, String?>(DASHBOARD_OPTION to null)
        for (packageName in Prefs(this).dockApps) {
            val app = Apps.load(this, packageName) ?: continue
            targets[app.label] = app.packageName
        }
        openTargets = targets
        openSelect.options = targets.keys.toList()
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
                entity === backToDashboard -> showDashboard(home = true)
                entity === reload -> AppState.controller?.reloadDashboard()
                entity === installUpdate -> Updater.check(this, install = true)
            }
        }
    }

    override fun onSelect(entity: Entity, option: String) {
        main.post {
            if (entity !== openSelect || !openTargets.containsKey(option)) return@post
            val packageName = openTargets[option]
            if (packageName == null) showDashboard(home = true) else openApp(packageName)
            server?.setState(openSelect, option)
        }
    }

    override fun onService(service: UserService, args: Map<String, Any>) {
        main.post {
            when {
                service === speakService -> speak(args["message"] as? String ?: "")
                service === openPageService -> openPage(args["adresse"] as? String ?: "")
                service === openAppService -> openAppByName(args["nom"] as? String ?: "")
                service === showAlertService -> showAlert(
                    args["titre"] as? String ?: "",
                    args["message"] as? String ?: "",
                    args["secondes"] as? Int ?: 0
                )
                service === closeAlertService -> {
                    alertOverlay.close(notify = false)
                    server?.setState(alertShown, false)
                }
            }
        }
    }

    private fun showAlert(title: String, message: String, seconds: Int) {
        if (!AppState.screenOn) setScreen(true) else wakeUp()
        val shown = alertOverlay.show(title.ifBlank { "Alerte" }, message, seconds)
        server?.setState(alertShown, shown)
        // Sans l'autorisation « affichage par-dessus », on se rabat sur la voix
        if (!shown) speak(listOf(title, message).filter { it.isNotBlank() }.joinToString(". "))
    }

    // ----- Actions -----

    /** Ramène le tableau de bord ; [home] = revenir aussi à la page d'accueil configurée. */
    private fun showDashboard(home: Boolean) {
        val controller = AppState.controller
        if (AppState.dashboardVisible && controller != null) {
            if (home) controller.showHome()
        } else {
            if (home) AppState.pendingHome = true
            Dashboard.bringToFront(this)
        }
    }

    private fun openApp(packageName: String) {
        val controller = AppState.controller
        if (AppState.dashboardVisible && controller != null) {
            controller.openApp(packageName)
        } else {
            // Android n'autorise le lancement d'une appli que depuis le premier plan :
            // on ramène d'abord WallDock, qui l'ouvrira à son tour.
            AppState.pendingApp = packageName
            Dashboard.bringToFront(this)
        }
    }

    private fun openAppByName(name: String) {
        val wanted = name.trim()
        if (wanted.isEmpty()) return
        if (wanted.equals(DASHBOARD_OPTION, ignoreCase = true)) {
            showDashboard(home = true)
            return
        }
        val apps = Apps.listLaunchable(this)
        val app = apps.firstOrNull { it.packageName.equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.contains(wanted, ignoreCase = true) }
        if (app != null) openApp(app.packageName)
    }

    private fun openPage(address: String) {
        val url = resolvePageUrl(address.trim()) ?: return
        val controller = AppState.controller
        if (AppState.dashboardVisible && controller != null) {
            controller.openUrl(url)
        } else {
            AppState.pendingUrl = url
            Dashboard.bringToFront(this)
        }
    }

    /**
     * « /dashboard-home/plan » → adresse complète sur le même Home Assistant ;
     * le mode kiosque (?kiosk) est conservé s'il est utilisé dans l'adresse configurée.
     */
    private fun resolvePageUrl(address: String): String? {
        if (address.isEmpty()) return null
        if (address.startsWith("http://") || address.startsWith("https://")) return address
        val base = Uri.parse(Prefs(this).haUrl)
        val scheme = base.scheme ?: return null
        val authority = base.encodedAuthority ?: return null
        val path = if (address.startsWith("/")) address else "/$address"
        var url = "$scheme://$authority$path"
        val kiosk = base.query?.split("&")?.any { it == "kiosk" || it.startsWith("kiosk=") } == true
        if (kiosk && !url.contains("kiosk")) url += if (url.contains("?")) "&kiosk" else "?kiosk"
        return url
    }

    private fun speak(message: String) {
        if (message.isBlank()) return
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeech.add(message)
            return
        }
        engine.speak(message, TextToSpeech.QUEUE_ADD, null, "walldock-${System.currentTimeMillis()}")
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
        const val DASHBOARD_OPTION = "Tableau de bord"
        private const val CHANNEL_ID = "walldock_api"
        private const val NOTIFICATION_ID = 2
        private const val UPDATE_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val UPDATE_RETRY_MS = 30 * 60 * 1000L

        @Volatile
        var instance: ApiService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ApiService::class.java))
        }
    }
}
