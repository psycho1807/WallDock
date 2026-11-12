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
import android.media.AudioAttributes
import android.media.MediaPlayer
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
    private var mediaPlayer: TabletPlayer? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    lateinit var deviceName: String
        private set
    lateinit var friendlyName: String
        private set

    private val battery = Entity(Entity.Kind.SENSOR, "batterie", tr(R.string.entity_batterie))
        .unit("%").deviceClass("battery").measurement().category(Entity.CATEGORY_DIAGNOSTIC)
    private val charging = Entity(Entity.Kind.BINARY_SENSOR, "en_charge", tr(R.string.entity_en_charge))
        .deviceClass("battery_charging").category(Entity.CATEGORY_DIAGNOSTIC)
    private val screen = Entity(Entity.Kind.SWITCH, "ecran", tr(R.string.entity_ecran)).icon("mdi:tablet-dashboard")
    private val brightness = Entity(Entity.Kind.NUMBER, "luminosite", tr(R.string.entity_luminosite))
        .unit("%").range(1f, 100f, 1f).icon("mdi:brightness-6")
    private val currentApp = Entity(Entity.Kind.TEXT_SENSOR, "appli_ouverte", tr(R.string.entity_appli_ouverte)).icon("mdi:application")
    private val backToDashboard = Entity(Entity.Kind.BUTTON, "retour_tableau_de_bord", tr(R.string.entity_retour_tableau_de_bord))
        .icon("mdi:view-dashboard")
    private val reload = Entity(Entity.Kind.BUTTON, "recharger", tr(R.string.entity_recharger))
        .icon("mdi:refresh").category(Entity.CATEGORY_CONFIG)

    private val openSelect = Entity(Entity.Kind.SELECT, "ouvrir", tr(R.string.entity_ouvrir)).icon("mdi:apps")
    private val alertShown = Entity(Entity.Kind.BINARY_SENSOR, "alerte_affichee", tr(R.string.entity_alerte_affichee))
        .icon("mdi:alert-octagon")

    private val updateStatus = Entity(Entity.Kind.TEXT_SENSOR, "mise_a_jour", tr(R.string.entity_mise_a_jour))
        .icon("mdi:update").category(Entity.CATEGORY_DIAGNOSTIC)
    private val installUpdate = Entity(Entity.Kind.BUTTON, "installer_mise_a_jour", tr(R.string.entity_installer_mise_a_jour))
        .icon("mdi:download").category(Entity.CATEGORY_CONFIG)

    private val jarvisState = Entity(Entity.Kind.TEXT_SENSOR, "jarvis", "Jarvis").icon("mdi:account-voice")
    private val talkToJarvis = Entity(Entity.Kind.BUTTON, "parler_a_jarvis", tr(R.string.entity_parler_a_jarvis)).icon("mdi:microphone")

    private val micLevel = Entity(Entity.Kind.SENSOR, "niveau_micro", tr(R.string.entity_niveau_micro))
        .unit("%").measurement().icon("mdi:microphone-outline").category(Entity.CATEGORY_DIAGNOSTIC)
    private val audioSent = Entity(Entity.Kind.SENSOR, "son_envoye", tr(R.string.entity_son_envoye))
        .unit("s").icon("mdi:upload-network").category(Entity.CATEGORY_DIAGNOSTIC)

    private val volume = Entity(Entity.Kind.NUMBER, "volume", "Volume")
        .unit("%").range(0f, 100f, 1f).icon("mdi:volume-high")
    private val stopRinging = Entity(Entity.Kind.BUTTON, "arreter_la_sonnerie", tr(R.string.entity_arreter_la_sonnerie))
        .icon("mdi:alarm-off")
    private val detectionMode = Entity(Entity.Kind.TEXT_SENSOR, "detection_hey_jarvis", tr(R.string.entity_detection_hey_jarvis))
        .icon("mdi:ear-hearing").category(Entity.CATEGORY_DIAGNOSTIC)
    private val wakeScore = Entity(Entity.Kind.SENSOR, "score_hey_jarvis", tr(R.string.entity_score_hey_jarvis))
        .unit("%").measurement().icon("mdi:waveform").category(Entity.CATEGORY_DIAGNOSTIC)

    private val screenCamera = Entity(Entity.Kind.CAMERA, "capture_ecran", tr(R.string.entity_capture_ecran))
        .icon("mdi:monitor-screenshot").category(Entity.CATEGORY_DIAGNOSTIC)
    // ---- Réglages de WallDock modifiables depuis HA (catégorie « Configuration ») ----
    private val setListen = Entity(Entity.Kind.SWITCH, "reglage_ecoute_jarvis", tr(R.string.entity_set_listen))
        .icon("mdi:ear-hearing").category(Entity.CATEGORY_CONFIG)
    private val setOnDevice = Entity(Entity.Kind.SWITCH, "reglage_jarvis_sur_tablette", tr(R.string.entity_set_on_device))
        .icon("mdi:tablet").category(Entity.CATEGORY_CONFIG)
    private val setSensitivity = Entity(Entity.Kind.SELECT, "reglage_sensibilite_jarvis", tr(R.string.entity_set_sensitivity))
        .icon("mdi:tune-vertical").category(Entity.CATEGORY_CONFIG)
    private val setScreensaver = Entity(Entity.Kind.SWITCH, "reglage_ecran_de_veille", tr(R.string.entity_set_screensaver))
        .icon("mdi:clock-outline").category(Entity.CATEGORY_CONFIG)
    private val setScreensaverMinutes = Entity(Entity.Kind.NUMBER, "reglage_delai_veille", tr(R.string.entity_set_screensaver_minutes))
        .unit("min").range(1f, 240f, 1f).icon("mdi:timer-outline").category(Entity.CATEGORY_CONFIG)
        .also { it.numberMode = Entity.NUMBER_MODE_BOX }
    private val setReturnSeconds = Entity(Entity.Kind.NUMBER, "reglage_retour_tableau", tr(R.string.entity_set_return))
        .unit("s").range(10f, 3600f, 10f).icon("mdi:keyboard-return").category(Entity.CATEGORY_CONFIG)
        .also { it.numberMode = Entity.NUMBER_MODE_BOX }
    private val setAutoUpdate = Entity(Entity.Kind.SWITCH, "reglage_mises_a_jour_auto", tr(R.string.entity_set_auto_update))
        .icon("mdi:update").category(Entity.CATEGORY_CONFIG)
    private val setHandle = Entity(Entity.Kind.SELECT, "reglage_poignee_dock", tr(R.string.entity_set_handle))
        .icon("mdi:gesture-tap").category(Entity.CATEGORY_CONFIG)
    private val setUrl = Entity(Entity.Kind.TEXT, "reglage_adresse_tableau", tr(R.string.entity_set_url))
        .icon("mdi:web").category(Entity.CATEGORY_CONFIG)

    private val sensitivityLabels get() = listOf(
        tr(R.string.sens_short_normal), tr(R.string.sens_short_high), tr(R.string.sens_short_very_high)
    )
    private val handleLabels get() = linkedMapOf(
        Prefs.HANDLE_BOTTOM_RIGHT to tr(R.string.handle_bottom_right),
        Prefs.HANDLE_BOTTOM_LEFT to tr(R.string.handle_bottom_left),
        Prefs.HANDLE_RIGHT_MIDDLE to tr(R.string.handle_right_middle),
        Prefs.HANDLE_HIDDEN to tr(R.string.handle_hidden_short)
    )

    private val mediaEntity = Entity(Entity.Kind.MEDIA_PLAYER, "lecteur", tr(R.string.entity_lecteur)).icon("mdi:speaker")

    private val entities = listOf(
        mediaEntity, screenCamera, jarvisState, talkToJarvis, volume, stopRinging, detectionMode, wakeScore, micLevel, audioSent,
        battery, charging, screen, brightness, currentApp, openSelect, alertShown, backToDashboard, reload,
        updateStatus, installUpdate,
        setListen, setOnDevice, setSensitivity, setScreensaver, setScreensaverMinutes, setReturnSeconds,
        setAutoUpdate, setHandle, setUrl
    )

    /** Diagnostic du micro toutes les 5 s : niveau (crête) et secondes de son envoyées à HA. */
    private var peakLevel = 0f
    private val micReport = object : Runnable {
        override fun run() {
            val assistant = jarvis
            if (assistant != null) {
                val level = if (assistant.isRecording) assistant.micLevel else 0f
                peakLevel = maxOf(peakLevel * 0.5f, level)
                server?.setState(micLevel, (peakLevel * 100f).toInt().toFloat())
                server?.setState(audioSent, (assistant.chunksSent * 64 / 1000).toFloat())
                val score = if (assistant.detectsOnDevice) assistant.wakeScorePeak else 0f
                assistant.wakeScorePeak = 0f
                server?.setState(wakeScore, (score * 100f).toInt().toFloat())
                server?.setState(detectionMode, assistant.detectionStatus)
            }
            reportVolume()
            main.postDelayed(this, 5_000)
        }
    }

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
    private val playSoundService = UserService("jouer_son").arg("adresse", UserService.ARG_STRING)
    private val showCameraService = UserService("afficher_camera")
        .arg("camera", UserService.ARG_STRING)
        .arg("titre", UserService.ARG_STRING)
        .arg("secondes", UserService.ARG_INT)
    private val services = listOf(
        speakService, openPageService, openAppService, showAlertService, closeAlertService, playSoundService,
        showCameraService
    )
    private lateinit var cameraOverlay: CameraOverlay
    /** Caméra HA demandée (entity_id), en attente de son adresse : (titre, secondes). */
    private var pendingCamera: Triple<String, String, Int>? = null
    private var player: MediaPlayer? = null

    private lateinit var alertOverlay: AlertOverlay

    /** Assistant vocal Jarvis (satellite vocal de HA). */
    var jarvis: Jarvis? = null
        private set

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
        info.voiceFeatures = Jarvis.FEATURES

        refreshOpenTargets()
        val srv = EsphomeServer(info, entities, services, this, EsphomeServer.DEFAULT_PORT)
        server = srv
        // Chiffrement de l'API (Noise) : HA crée et envoie la clé tout seul, elle est gardée ici
        srv.setKeyStore(object : EsphomeServer.KeyStore {
            override fun loadKey(): ByteArray? = Prefs(this@ApiService).noiseKey
            override fun saveKey(key: ByteArray?) {
                Prefs(this@ApiService).noiseKey = key
                main.post {
                    unregisterMdns()
                    registerMdns(mac)
                }
            }
        })
        val assistant = Jarvis(this, srv) { wakeUp() }
        val tabletPlayer = TabletPlayer(this) { server?.setState(mediaEntity, it) }
        mediaPlayer = tabletPlayer
        assistant.onStateChanged = {
            server?.setState(jarvisState, JARVIS_LABELS[it] ?: "")
            // La musique se met en pause pendant que Jarvis écoute ou parle
            tabletPlayer.setInterrupted(it == Jarvis.State.LISTENING || it == Jarvis.State.THINKING || it == Jarvis.State.SPEAKING)
        }
        jarvis = assistant
        srv.setVoiceHandler(assistant)
        srv.setState(jarvisState, JARVIS_LABELS[Jarvis.State.OFF] ?: "")
        srv.setState(mediaEntity, tabletPlayer.state())
        setSensitivity.options = sensitivityLabels
        setHandle.options = handleLabels.values.toList()
        reportSettings()
        srv.setState(screen, AppState.screenOn)
        srv.setState(brightness, if (AppState.brightness < 0) 100f else AppState.brightness)
        srv.setState(currentApp, AppState.currentApp)
        srv.setState(openSelect, DASHBOARD_OPTION)
        srv.setState(alertShown, false)
        srv.setState(updateStatus, Updater.status)
        Updater.addListener(updateListener)
        main.postDelayed(updateCheck, 60_000)
        main.postDelayed(micReport, 5_000)
        alertOverlay = AlertOverlay(this) { server?.setState(alertShown, false) }
        cameraOverlay = CameraOverlay(this) { server?.setState(alertShown, alertOverlay.isShowing) }

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
                AppState.apiError = tr(R.string.port_in_use, EsphomeServer.DEFAULT_PORT)
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
        jarvis?.release()
        jarvis = null
        mediaPlayer?.release()
        mediaPlayer = null
        main.removeCallbacks(updateCheck)
        main.removeCallbacks(micReport)
        Updater.removeListener(updateListener)
        alertOverlay.close(notify = false)
        cameraOverlay.close(notify = false)
        player?.release()
        player = null
        tts?.shutdown()
        tts = null
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // déjà désinscrit
        }
        unregisterMdns()
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
            val prefs = Prefs(this)
            when {
                entity === screen -> setScreen(state)
                entity === setListen -> {
                    prefs.jarvisWakeWord = state
                    jarvis?.onSettingsChanged()
                }
                entity === setOnDevice -> {
                    if (state) WakeWordDetector.clearCrash(this)
                    prefs.jarvisOnDevice = state
                    jarvis?.onSettingsChanged()
                }
                entity === setScreensaver -> prefs.screensaverEnabled = state
                entity === setAutoUpdate -> prefs.autoUpdate = state
            }
            reportSettings()
        }
    }

    override fun onText(entity: Entity, value: String) {
        main.post {
            if (entity === setUrl) {
                val url = value.trim().trimEnd('/')
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Prefs(this).haUrl = url
                    AppState.controller?.applySettings()
                }
            }
            reportSettings()
        }
    }

    /** Pousse vers HA les réglages actuels (au démarrage et après chaque changement, ici ou dans les réglages). */
    fun reportSettings() {
        val prefs = Prefs(this)
        val srv = server ?: return
        srv.setState(setListen, prefs.jarvisWakeWord)
        srv.setState(setOnDevice, prefs.jarvisOnDevice)
        srv.setState(setSensitivity, sensitivityLabels.getOrElse(prefs.jarvisSensitivity) { sensitivityLabels[0] })
        srv.setState(setScreensaver, prefs.screensaverEnabled)
        srv.setState(setScreensaverMinutes, prefs.screensaverMinutes.toFloat())
        srv.setState(setReturnSeconds, prefs.returnSeconds.toFloat())
        srv.setState(setAutoUpdate, prefs.autoUpdate)
        srv.setState(setHandle, handleLabels[prefs.handlePosition] ?: handleLabels.values.first())
        srv.setState(setUrl, prefs.haUrl)
    }

    /** Volume des sons (Jarvis, annonces, sonnette) = volume « médias » de la tablette, en %. */
    private fun reportVolume() {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        server?.setState(volume, (current * 100f / max).toInt().toFloat())
        mediaPlayer?.report()
    }

    /**
     * Caméra en plein écran : soit une entité HA (camera.sonnette), dont on demande l'adresse
     * du flux à HA, soit directement une adresse d'image ou de flux MJPEG.
     */
    private fun showCamera(camera: String, title: String, seconds: Int) {
        val target = camera.trim()
        if (target.isEmpty()) return
        if (target.startsWith("camera.")) {
            pendingCamera = Triple(target, title, seconds)
            if (server?.requestHomeAssistantState(target, "entity_picture") != true) pendingCamera = null
        } else if (isImageAddress(target)) {
            displayCamera(Prefs(this).absoluteUrl(target), title, seconds)
        } else {
            // Une page du tableau de bord (ex. /sonnette-ring/live) : la vidéo en direct
            // passe par l'interface de HA (WebRTC/HLS), comme sur le tableau de bord
            val url = resolvePageUrl(target) ?: return
            wakeUp()
            if (!AppState.screenOn) setScreen(true)
            if (cameraOverlay.showPage(url, title, seconds)) server?.setState(alertShown, true)
        }
    }

    private fun isImageAddress(address: String): Boolean {
        val path = address.substringBefore('?').lowercase()
        return address.contains("/api/camera_proxy") || address.contains("/api/image") ||
            listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".mjpg", ".mjpeg", ".cgi").any { path.endsWith(it) }
    }

    private fun displayCamera(url: String, title: String, seconds: Int) {
        wakeUp()
        if (!AppState.screenOn) setScreen(true)
        if (cameraOverlay.show(url, title, seconds)) server?.setState(alertShown, true)
    }

    override fun onHomeAssistantState(entityId: String, attribute: String, state: String) {
        main.post {
            val pending = pendingCamera ?: return@post
            if (entityId != pending.first || attribute != "entity_picture") return@post
            pendingCamera = null
            if (state.isBlank() || state == "None") return@post
            // Image fixe → flux vidéo MJPEG (même jeton d'accès)
            val stream = state.replace("/api/camera_proxy/", "/api/camera_proxy_stream/")
            displayCamera(Prefs(this).absoluteUrl(stream), pending.second, pending.third)
        }
    }

    /** HA demande une image de l'entité « Capture d'écran ». */
    override fun onCameraRequest(entity: Entity) {
        main.post {
            if (capturing) return@post
            capturing = true
            val controller = AppState.controller
            val deliver: (android.graphics.Bitmap?) -> Unit = { bitmap ->
                Thread({
                    val jpeg = encodeScreenshot(bitmap)
                    server?.sendCameraImage(entity, jpeg)
                    main.post { capturing = false }
                }, "walldock-capture").start()
            }
            if (controller == null) deliver(null) else controller.captureScreen(deliver)
        }
    }

    private var capturing = false

    private fun encodeScreenshot(source: android.graphics.Bitmap?): ByteArray {
        val bitmap = if (source != null) {
            val scale = minOf(1f, 1280f / source.width)
            if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
            } else {
                source
            }
        } else {
            // WallDock n'est pas affiché (autre appli ouverte, écran éteint…) : image explicative
            val image = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(image)
            canvas.drawColor(android.graphics.Color.BLACK)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 26f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText(tr(R.string.capture_unavailable), 320f, 170f, paint)
            paint.textSize = 20f
            paint.color = 0xFF8FA8C8.toInt()
            canvas.drawText(AppState.currentApp, 320f, 210f, paint)
            image
        }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    override fun onMediaPlayer(entity: Entity, command: Int?, volume: Float?, url: String?, announcement: Boolean) {
        main.post {
            val p = mediaPlayer ?: return@post
            if (volume != null) p.setVolume(volume)
            if (url != null) {
                if (announcement) p.announce(url) else p.play(url)
            }
            when (command) {
                EsphomeServer.MEDIA_COMMAND_PLAY -> p.resume()
                EsphomeServer.MEDIA_COMMAND_PAUSE -> p.pause()
                EsphomeServer.MEDIA_COMMAND_STOP -> p.stop()
                EsphomeServer.MEDIA_COMMAND_MUTE -> p.setMuted(true)
                EsphomeServer.MEDIA_COMMAND_UNMUTE -> p.setMuted(false)
                EsphomeServer.MEDIA_COMMAND_TOGGLE -> p.toggle()
                EsphomeServer.MEDIA_COMMAND_VOLUME_UP -> p.volumeStep(up = true)
                EsphomeServer.MEDIA_COMMAND_VOLUME_DOWN -> p.volumeStep(up = false)
            }
            reportVolume()
        }
    }

    private fun setVolume(percent: Float) {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val index = Math.round(percent.coerceIn(0f, 100f) / 100f * max)
        try {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, index, 0)
        } catch (e: Exception) {
            // mode Ne pas déranger : changement refusé
        }
        reportVolume()
    }

    override fun onNumber(entity: Entity, value: Float) {
        main.post {
            if (entity === brightness) {
                AppState.brightness = value.coerceIn(1f, 100f)
                AppState.controller?.applyScreen()
                server?.setState(brightness, AppState.brightness)
            } else if (entity === volume) {
                setVolume(value)
            } else if (entity === setScreensaverMinutes) {
                Prefs(this).screensaverMinutes = value.toInt().coerceIn(1, 240)
                reportSettings()
            } else if (entity === setReturnSeconds) {
                Prefs(this).returnSeconds = value.toInt().coerceIn(10, 3600)
                reportSettings()
            }
        }
    }

    override fun onButton(entity: Entity) {
        main.post {
            when {
                entity === backToDashboard -> showDashboard(home = true)
                entity === reload -> AppState.controller?.reloadDashboard()
                entity === installUpdate -> Updater.check(this, install = true)
                entity === talkToJarvis -> jarvis?.talk()
                entity === stopRinging -> jarvis?.timers?.stopRinging()
            }
        }
    }

    override fun onSelect(entity: Entity, option: String) {
        main.post {
            if (entity === setSensitivity) {
                val index = sensitivityLabels.indexOf(option)
                if (index >= 0) {
                    Prefs(this).jarvisSensitivity = index
                    jarvis?.reloadVoiceSettings()
                }
                reportSettings()
                return@post
            }
            if (entity === setHandle) {
                handleLabels.entries.firstOrNull { it.value == option }?.let {
                    Prefs(this).handlePosition = it.key
                    AppState.controller?.applySettings()
                }
                reportSettings()
                return@post
            }
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
                service === playSoundService -> playSound(args["adresse"] as? String ?: "")
                service === openPageService -> openPage(args["adresse"] as? String ?: "")
                service === openAppService -> openAppByName(args["nom"] as? String ?: "")
                service === showAlertService -> showAlert(
                    args["titre"] as? String ?: "",
                    args["message"] as? String ?: "",
                    args["secondes"] as? Int ?: 0
                )
                service === closeAlertService -> {
                    alertOverlay.close(notify = false)
                    cameraOverlay.close(notify = false)
                    pendingCamera = null
                    server?.setState(alertShown, false)
                }
                service === showCameraService -> showCamera(
                    args["camera"] as? String ?: "",
                    args["titre"] as? String ?: "",
                    args["secondes"] as? Int ?: 0
                )
            }
        }
    }

    private fun showAlert(title: String, message: String, seconds: Int) {
        if (!AppState.screenOn) setScreen(true) else wakeUp()
        val shown = alertOverlay.show(title.ifBlank { tr(R.string.alert_default_title) }, message, seconds)
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

    /** Joue un son (mp3, ogg…) : adresse complète ou chemin HA comme /local/sonnette.mp3. */
    private fun playSound(address: String) {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        val url = Prefs(this).absoluteUrl(trimmed)
        wakeUp()
        player?.release()
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            media.setDataSource(url)
            media.setOnPreparedListener { it.start() }
            media.setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            media.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (player === mp) player = null
                true
            }
            media.prepareAsync()
        } catch (e: Exception) {
            media.release()
            player = null
        }
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
    fun wakeUp() {
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
        // Annonce du chiffrement, comme un appareil ESPHome
        if (server?.isEncrypted == true) {
            service.setAttribute("api_encryption", EsphomeServer.NOISE_PROTOCOL)
        } else {
            service.setAttribute("api_encryption_supported", EsphomeServer.NOISE_PROTOCOL)
        }
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

    private fun unregisterMdns() {
        nsdListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (e: Exception) {
                // déjà retiré
            }
        }
        nsdListener = null
    }

    /** Vrai si la connexion avec Home Assistant est chiffrée (clé en place). */
    val isEncrypted: Boolean
        get() = server?.isEncrypted == true

    /** Retire la clé : HA demandera de confirmer le passage sans chiffrement (ou en recréera une). */
    fun resetEncryption() {
        server?.changeKey(null)
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
            .setContentText(tr(R.string.notif_available))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            // Micro autorisé en arrière-plan (Jarvis) seulement si la permission est accordée
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, types)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** À appeler quand l'écran WallDock est au premier plan et que le micro vient d'être autorisé. */
    fun onMicrophoneChanged() {
        startInForeground()
        jarvis?.onSettingsChanged()
    }

    companion object {
        val DASHBOARD_OPTION: String get() = tr(R.string.dashboard)
        private const val CHANNEL_ID = "walldock_api"
        private const val NOTIFICATION_ID = 2
        private const val UPDATE_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val UPDATE_RETRY_MS = 30 * 60 * 1000L
        private val JARVIS_LABELS get() = mapOf(
            Jarvis.State.OFF to tr(R.string.jarvis_state_off),
            Jarvis.State.WAITING to tr(R.string.jarvis_state_waiting),
            Jarvis.State.LISTENING to tr(R.string.jarvis_state_listening),
            Jarvis.State.THINKING to tr(R.string.jarvis_state_thinking),
            Jarvis.State.SPEAKING to tr(R.string.jarvis_state_speaking)
        )

        @Volatile
        var instance: ApiService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ApiService::class.java))
        }
    }
}
