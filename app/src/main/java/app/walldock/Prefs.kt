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
    /** Sensibilité de « Hey Jarvis » : 0 normale, 1 élevée, 2 très élevée. */
    var jarvisSensitivity: Int
        get() = sp.getInt(KEY_JARVIS_SENSITIVITY, 0)
        set(value) = sp.edit().putInt(KEY_JARVIS_SENSITIVITY, value).apply()

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
}
