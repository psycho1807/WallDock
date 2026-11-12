package app.walldock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * Sauvegarde et restauration des réglages de WallDock dans un fichier JSON choisi par
 * l'utilisateur (dossier Téléchargements, Drive…) : adresse, code, dock, Jarvis,
 * voix apprises. Pratique pour réinstaller la tablette ou en préparer une autre.
 */
object SettingsBackup {

    const val REQUEST_EXPORT = 51
    const val REQUEST_IMPORT = 52

    private const val PREFS = "walldock"
    private val FILES = listOf("wakeword/voices.bin", "wakeword/verifier.bin")
    /** États internes propres à la tablette, à ne pas recopier. */
    private val SKIPPED = setOf(
        "wakeword_load_in_progress", "wakeword_crashed", "stopword_load_in_progress", "stopword_crashed",
        // La clé de chiffrement est propre au couple tablette / Home Assistant
        "api_noise_key"
    )

    fun startExport(activity: Activity) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "walldock-reglages.json")
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, REQUEST_EXPORT)
    }

    fun startImport(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, REQUEST_IMPORT)
    }

    fun write(context: Context, uri: Uri): Boolean = try {
        val prefs = JSONObject()
        for ((key, value) in context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all) {
            if (key in SKIPPED || value == null) continue
            val entry = JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "set").put("v", org.json.JSONArray(value.map { it.toString() }))
                else -> continue
            }
            prefs.put(key, entry)
        }
        val files = JSONObject()
        for (name in FILES) {
            val file = File(context.filesDir, name)
            if (file.isFile) files.put(name, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }
        val root = JSONObject()
            .put("app", "WallDock")
            .put("format", 1)
            .put("version", BuildConfig.VERSION_NAME)
            .put("prefs", prefs)
            .put("files", files)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray()) } != null
    } catch (e: Exception) {
        false
    }

    fun read(context: Context, uri: Uri): Boolean = try {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("fichier illisible")
        val root = JSONObject(text)
        if (root.optString("app") != "WallDock") throw IllegalStateException("pas une sauvegarde WallDock")
        val prefs = root.getJSONObject("prefs")
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (key in prefs.keys()) {
            if (key in SKIPPED) continue
            val entry = prefs.getJSONObject(key)
            when (entry.getString("t")) {
                "b" -> editor.putBoolean(key, entry.getBoolean("v"))
                "i" -> editor.putInt(key, entry.getInt("v"))
                "l" -> editor.putLong(key, entry.getLong("v"))
                "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                "s" -> editor.putString(key, entry.getString("v"))
                "set" -> {
                    val array = entry.getJSONArray("v")
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
            }
        }
        editor.commit()
        val files = root.optJSONObject("files")
        for (name in FILES) {
            val file = File(context.filesDir, name)
            val data = files?.optString(name).orEmpty()
            if (data.isEmpty()) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeBytes(Base64.decode(data, Base64.NO_WRAP))
            }
        }
        true
    } catch (e: Exception) {
        false
    }

    /** Relance l'appli pour appliquer les réglages restaurés. */
    fun restartApp(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}
