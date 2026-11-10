package app.walldock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Service d'accessibilité facultatif : note seulement l'heure du dernier geste
 * (clic, défilement, saisie) pour que le retour automatique attende une vraie inactivité.
 * Il ne lit aucun contenu et n'envoie rien.
 */
class InteractionService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppState.accessibilityActive = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> AppState.lastInteraction = SystemClock.elapsedRealtime()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AppState.accessibilityActive = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        AppState.accessibilityActive = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: InteractionService? = null
    }
}
