package app.walldock

import android.app.Application
import android.content.Context

/** Garde le contexte de l'appli pour traduire les textes partout (anglais par défaut, français dans values-fr). */
class WallDockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        context = this
    }

    companion object {
        lateinit var context: Context
            private set
    }
}

/** Texte traduit selon la langue de la tablette. */
fun tr(id: Int, vararg args: Any): String = WallDockApp.context.getString(id, *args)
