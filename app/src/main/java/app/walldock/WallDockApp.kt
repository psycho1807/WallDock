package app.walldock

import android.app.Application
import android.content.Context

/** Garde le contexte de l'appli pour traduire les textes partout (anglais par défaut, français dans values-fr). */
class WallDockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        context = this
        // Plantage : on garde la cause pour l'envoyer à Home Assistant au redémarrage
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val frames = error.stackTrace.take(12).joinToString("\n") { "  at $it" }
                var cause = error.cause
                val causes = StringBuilder()
                while (cause != null && causes.length < 1500) {
                    causes.append("\nCaused by: ").append(cause).append("\n")
                        .append(cause.stackTrace.take(6).joinToString("\n") { "  at $it" })
                    cause = cause.cause
                }
                getSharedPreferences("walldock_crash", MODE_PRIVATE).edit()
                    .putString("last", "${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date())} [${thread.name}] $error\n$frames$causes")
                    .commit()
            } catch (e: Throwable) {
                // rien de plus à faire
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        lateinit var context: Context
            private set
    }
}

/** Texte traduit selon la langue de la tablette. */
fun tr(id: Int, vararg args: Any): String =
    // Sans argument, pas de mise en forme : un « % » dans le texte ferait planter getString(id, …)
    if (args.isEmpty()) WallDockApp.context.getString(id) else WallDockApp.context.getString(id, *args)
