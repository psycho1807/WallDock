package app.walldock

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable)

object Apps {

    /** Toutes les applis lançables installées, sauf WallDock, triées par nom. */
    fun listLaunchable(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(intent, 0)
        return activities
            .filter { it.activityInfo.packageName != context.packageName }
            .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** Une appli installée, ou null si elle a été désinstallée. */
    fun load(context: Context, packageName: String): LaunchableApp? {
        val pm = context.packageManager
        return try {
            @Suppress("DEPRECATION")
            val info = pm.getApplicationInfo(packageName, 0)
            LaunchableApp(packageName, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}

object Dashboard {
    /** Ramène le tableau de bord WallDock au premier plan. */
    fun bringToFront(context: Context) {
        // 1) Ouverture directe : autorisée tant qu'une fenêtre WallDock est visible (bouton ⌂)
        try {
            val intent = Intent(context, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Bloqué par Android : le bouton Accueil virtuel ci-dessous prend le relais
        }
        // 2) Bouton Accueil virtuel (détection d'activité active) : ramène à WallDock s'il est l'écran d'accueil
        InteractionService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }
}

object Pin {
    /** Demande le code administrateur, puis lance [onSuccess] s'il est correct. */
    fun ask(activity: Activity, prefs: Prefs, onSuccess: () -> Unit) {
        if (prefs.pin.isBlank()) {
            onSuccess()
            return
        }
        val input = EditText(activity)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "Code administrateur"
        AlertDialog.Builder(activity)
            .setTitle("Code administrateur")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == prefs.pin) onSuccess() else activity.toast("Code incorrect")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
