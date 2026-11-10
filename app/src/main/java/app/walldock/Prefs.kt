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

    val isConfigured: Boolean
        get() = haUrl.isNotBlank() && pin.isNotBlank()

    private companion object {
        const val KEY_URL = "ha_url"
        const val KEY_PIN = "pin"
        const val KEY_DELAY = "return_seconds"
        const val KEY_DOCK = "dock_apps"
        const val KEY_VIDEO = "video_apps"
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
}
