package app.walldock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Tablette redémarrée (coupure de courant, mise à jour d'Android…) : on relance WallDock,
 * même s'il n'est pas l'écran d'accueil. Android autorise une appli qui peut s'afficher
 * par-dessus les autres à ouvrir son écran depuis l'arrière-plan ; sinon, on démarre au moins
 * le service (Home Assistant retrouve la tablette, Jarvis et les commandes marchent).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.startOnBoot || !prefs.isConfigured) return
        try {
            ApiService.start(context)
        } catch (e: Exception) {
            // le service sera lancé par l'écran
        }
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            } catch (e: Exception) {
                // ouverture refusée par Android
            }
        }
    }
}
