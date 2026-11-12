package app.walldock

import android.content.Context

/** Réglages de WallDock, stockés localement sur la tablette. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("walldock", Context.MODE_PRIVATE)

    /** Adresse du tableau de bord Home Assistant, ex. http://192.168.1.10:8123 */
    var haUrl: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_URL, value).apply()

    /** Code administrateur demandé pour ouvrir les réglages. */
    var pin: String
        get() = sp.getString(KEY_PIN, "") ?: ""
        set(value) = sp.edit().putString(KEY_PIN, value).apply()

    /** Délai d'inactivité (secondes) avant le retour automatique au tableau de bord. */
    var returnSeconds: Int
        get() = sp.getInt(KEY_DELAY, 120)
        set(value) = sp.edit().putInt(KEY_DELAY, value).apply()

    /** Applis du dock, dans l'ordre d'affichage (noms de paquets). */
    var dockApps: List<String>
        get() = (sp.getString(KEY_DOCK, "") ?: "").split(",").filter { it.isNotBlank() }
        set(value) = sp.edit().putString(KEY_DOCK, value.joinToString(",")).apply()

    /** Applis « vidéo » : pas de retour automatique tant qu'elles sont ouvertes. */
    var videoApps: Set<String>
        get() = sp.getStringSet(KEY_VIDEO, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_VIDEO, value).apply()

    /** Position de la poignée qui ouvre le dock (voir HANDLE_*). */
    var handlePosition: String
        get() = sp.getString(KEY_HANDLE, HANDLE_BOTTOM_RIGHT) ?: HANDLE_BOTTOM_RIGHT
        set(value) = sp.edit().putString(KEY_HANDLE, value).apply()

    /** Écran de veille (horloge) sur le tableau de bord après une période sans toucher l'écran. */
    var screensaverEnabled: Boolean
        get() = sp.getBoolean(KEY_SAVER, false)
        set(value) = sp.edit().putBoolean(KEY_SAVER, value).apply()

    /** Délai avant l'écran de veille, en minutes. */
    var screensaverMinutes: Int
        get() = sp.getInt(KEY_SAVER_MINUTES, 5)
        set(value) = sp.edit().putInt(KEY_SAVER_MINUTES, value).apply()

    /** Installer automatiquement les nouvelles versions publiées sur GitHub. */
    var autoUpdate: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    /** Jarvis écoute « Hey Jarvis » en permanence (sinon seulement via le bouton). */
    var jarvisWakeWord: Boolean
        get() = sp.getBoolean(KEY_JARVIS_WAKE, true)
        set(value) = sp.edit().putBoolean(KEY_JARVIS_WAKE, value).apply()

    /** « Hey Jarvis » reconnu par la tablette elle-même (sinon par openWakeWord dans HA). */
    /** Clé de chiffrement de l'API (32 octets), créée par Home Assistant ; null : pas de chiffrement. */
    var noiseKey: ByteArray?
        get() = sp.getString(KEY_NOISE, null)?.let {
            try {
                android.util.Base64.decode(it, android.util.Base64.NO_WRAP).takeIf { k -> k.size == 32 }
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        set(value) {
            val edit = sp.edit()
            if (value == null) edit.remove(KEY_NOISE)
            else edit.putString(KEY_NOISE, android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP))
            edit.commit()
        }

    /** Détection de mouvement avec la caméra frontale. */
    var motionEnabled: Boolean
        get() = sp.getBoolean(KEY_MOTION, false)
        set(value) = sp.edit().putBoolean(KEY_MOTION, value).apply()

    /** Sensibilité du mouvement : 0 faible, 1 moyenne, 2 élevée. */
    var motionSensitivity: Int
        get() = sp.getInt(KEY_MOTION_SENSITIVITY, 1)
        set(value) = sp.edit().putInt(KEY_MOTION_SENSITIVITY, value).apply()

    /** Allumer l'écran (et quitter l'écran de veille) quand quelqu'un passe. */
    var motionWakesScreen: Boolean
        get() = sp.getBoolean(KEY_MOTION_WAKE, true)
        set(value) = sp.edit().putBoolean(KEY_MOTION_WAKE, value).apply()

    /** Sensibilité de « Hey Jarvis » : 0 normale, 1 élevée, 2 très élevée. */
    var jarvisSensitivity: Int
        get() = sp.getInt(KEY_JARVIS_SENSITIVITY, 0)
        set(value) = sp.edit().putInt(KEY_JARVIS_SENSITIVITY, value).apply()

    /** Relancer WallDock au démarrage de la tablette. */
    var startOnBoot: Boolean
        get() = sp.getBoolean(KEY_START_ON_BOOT, true)
        set(value) = sp.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    /** Page affichée comme écran de veille ("" = horloge de WallDock). */
    var screensaverUrl: String
        get() = sp.getString(KEY_SAVER_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_SAVER_URL, value).apply()

    /** Régler la luminosité du système (autorisation « modifier les paramètres système »). */
    var systemBrightness: Boolean
        get() = sp.getBoolean(KEY_SYSTEM_BRIGHTNESS, false)
        set(value) = sp.edit().putBoolean(KEY_SYSTEM_BRIGHTNESS, value).apply()

    /** Délai (s) sans toucher l'écran avant que le capteur « Activité » passe à « non ». */
    var activitySeconds: Int
        get() = sp.getInt(KEY_ACTIVITY_SECONDS, 60)
        set(value) = sp.edit().putInt(KEY_ACTIVITY_SECONDS, value).apply()

    /** Planification : écran éteint chaque nuit entre [sleepTime] et [wakeTime] (« HH:mm »). */
    var scheduleEnabled: Boolean
        get() = sp.getBoolean(KEY_SCHEDULE, false)
        set(value) = sp.edit().putBoolean(KEY_SCHEDULE, value).apply()

    var sleepTime: String
        get() = sp.getString(KEY_SLEEP_TIME, "23:00") ?: "23:00"
        set(value) = sp.edit().putString(KEY_SLEEP_TIME, value).apply()

    var wakeTime: String
        get() = sp.getString(KEY_WAKE_TIME, "07:00") ?: "07:00"
        set(value) = sp.edit().putString(KEY_WAKE_TIME, value).apply()

    /**
     * Adresses autorisées dans le tableau de bord (une par ligne ou séparées par des virgules :
     * nom de domaine ou début d'adresse). Vide = tout est autorisé. HA est toujours autorisé.
     */
    var allowedUrls: String
        get() = sp.getString(KEY_ALLOWED_URLS, "") ?: ""
        set(value) = sp.edit().putString(KEY_ALLOWED_URLS, value).apply()

    /** Vrai si l'adresse peut être ouverte (liste blanche). */
    fun isUrlAllowed(url: String): Boolean {
        val rules = allowedUrls.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (rules.isEmpty()) return true
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("javascript:")) return true
        val uri = android.net.Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        val haHost = android.net.Uri.parse(haUrl).host?.lowercase()
        if (host == haHost) return true
        return rules.any { rule ->
            val r = rule.lowercase()
            if (r.contains("://")) url.lowercase().startsWith(r)
            else host == r.removePrefix("*.") || host.endsWith("." + r.removePrefix("*."))
        }
    }

    var jarvisOnDevice: Boolean
        get() = sp.getBoolean(KEY_JARVIS_ON_DEVICE, true)
        set(value) = sp.edit().putBoolean(KEY_JARVIS_ON_DEVICE, value).apply()

    /** Adresse complète d'un son ou d'une page : les chemins (/local/…) sont rattachés à HA. */
    fun absoluteUrl(address: String): String {
        val trimmed = address.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val base = haUrl
        val origin = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore("/")
        return origin + "/" + trimmed.trimStart('/')
    }

    val isConfigured: Boolean
        get() = haUrl.isNotBlank() && pin.isNotBlank()

    companion object {
        const val HANDLE_BOTTOM_RIGHT = "bottom_right"
        const val HANDLE_BOTTOM_LEFT = "bottom_left"
        const val HANDLE_RIGHT_MIDDLE = "right_middle"
        const val HANDLE_HIDDEN = "hidden"

        private const val KEY_JARVIS_WAKE = "jarvis_wake_word"
        private const val KEY_JARVIS_ON_DEVICE = "jarvis_on_device"
        private const val KEY_JARVIS_SENSITIVITY = "jarvis_sensitivity"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_SCHEDULE = "schedule_enabled"
        private const val KEY_SLEEP_TIME = "schedule_sleep"
        private const val KEY_WAKE_TIME = "schedule_wake"
        private const val KEY_ALLOWED_URLS = "allowed_urls"
        private const val KEY_SAVER_URL = "screensaver_url"
        private const val KEY_SYSTEM_BRIGHTNESS = "system_brightness"
        private const val KEY_ACTIVITY_SECONDS = "activity_seconds"
        private const val KEY_NOISE = "api_noise_key"
        private const val KEY_MOTION = "motion_enabled"
        private const val KEY_MOTION_SENSITIVITY = "motion_sensitivity"
        private const val KEY_MOTION_WAKE = "motion_wake_screen"
        private const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_HANDLE = "handle_position"
        private const val KEY_SAVER = "screensaver_enabled"
        private const val KEY_SAVER_MINUTES = "screensaver_minutes"
        private const val KEY_URL = "ha_url"
        private const val KEY_PIN = "pin"
        private const val KEY_DELAY = "return_seconds"
        private const val KEY_DOCK = "dock_apps"
        private const val KEY_VIDEO = "video_apps"
    }
}

/** État partagé entre l'écran principal et les services (même processus). */
object AppState {
    /** Vrai quand le tableau de bord est au premier plan. */
    @Volatile
    var dashboardVisible = false

    /** Dernière interaction détectée (horloge SystemClock.elapsedRealtime). */
    @Volatile
    var lastInteraction = 0L

    /** Dernier toucher sur l'écran de WallDock (elapsedRealtime). */
    @Volatile
    var lastTouch = android.os.SystemClock.elapsedRealtime()

    /** Secondes sans toucher l'écran (WallDock ou, avec la détection d'activité, une autre appli). */
    val idleSeconds: Long
        get() = (android.os.SystemClock.elapsedRealtime() - maxOf(lastTouch, lastInteraction)) / 1000

    /** Vrai quand le service d'accessibilité (détection d'activité) est actif. */
    @Volatile
    var accessibilityActive = false

    /** Écran « allumé » (faux = écran noir, la tablette peut se mettre en veille). Piloté par HA. */
    @Volatile
    var screenOn = true

    /** Luminosité demandée par HA, en % (1–100), ou -1 pour la luminosité système. */
    @Volatile
    var brightness = -1f

    /** Nom de l'appli au premier plan, remonté à HA. */
    @Volatile
    var currentApp = tr(R.string.dashboard)

    /** Message d'erreur du serveur Home Assistant, ou null si tout va bien. */
    @Volatile
    var apiError: String? = null

    /** Écran principal, quand il existe : appliquer l'écran et la luminosité, recharger la page. */
    @Volatile
    var controller: DashboardController? = null

    // Demandes de HA en attente, traitées dès que le tableau de bord revient au premier plan
    @Volatile
    var pendingHome = false

    @Volatile
    var pendingApp: String? = null

    @Volatile
    var pendingUrl: String? = null
}

interface DashboardController {
    fun applyScreen()
    fun reloadDashboard()
    /** Revenir à la page d'accueil configurée. */
    fun showHome()
    fun openApp(packageName: String)
    fun openUrl(url: String)
    /** Capture de l'écran de WallDock (null si WallDock n'est pas affiché). */
    fun captureScreen(done: (android.graphics.Bitmap?) -> Unit) = done(null)
    /** Réglages modifiés depuis Home Assistant : poignée, adresse du tableau de bord… */
    fun applySettings() {}
    /** Quelqu'un passe devant la tablette : quitter l'écran de veille. */
    fun userPresent() {}
    /** Demander l'autorisation de la caméra (détection de mouvement activée depuis HA). */
    fun requestCameraPermission() {}
}
