package app.walldock

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Mises à jour automatiques depuis les releases GitHub publiques du projet.
 * Aucun compte ni jeton : l'API publique suffit.
 */
object Updater {

    private const val LATEST_RELEASE = "https://api.github.com/repos/psycho1807/WallDock/releases/latest"
    const val ACTION_INSTALL_STATUS = "app.walldock.INSTALL_STATUS"

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "walldock-updater") }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var status = tr(R.string.upd_not_checked)
        private set

    /** Prévenu (sur le fil principal) à chaque changement d'état. */
    private val listeners = mutableSetOf<(String) -> Unit>()

    @Volatile
    private var busy = false

    fun addListener(listener: (String) -> Unit) = main.post { listeners.add(listener) }
    fun removeListener(listener: (String) -> Unit) = main.post { listeners.remove(listener) }

    fun setStatus(text: String) {
        status = text
        main.post { listeners.toList().forEach { it(text) } }
    }

    /** Vérifie la dernière version ; si [install], la télécharge et l'installe. */
    fun check(context: Context, install: Boolean) {
        // Version Google Play : c'est le Play Store qui met l'appli à jour
        if (BuildConfig.PLAY_STORE) {
            setStatus(tr(R.string.updates_play_status, BuildConfig.VERSION_NAME))
            return
        }
        val app = context.applicationContext
        executor.execute {
            if (busy) return@execute
            busy = true
            try {
                run(app, install)
            } catch (e: Exception) {
                setStatus(tr(R.string.upd_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    private fun run(context: Context, install: Boolean) {
        setStatus(tr(R.string.upd_checking))
        val release = JSONObject(httpGet(LATEST_RELEASE))
        val latest = release.optString("tag_name").removePrefix("v")
        val assets = release.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) apkUrl = asset.optString("browser_download_url")
            }
        }
        val current = BuildConfig.VERSION_NAME
        if (latest.isBlank() || apkUrl == null || compareVersions(latest, current) <= 0) {
            setStatus(tr(R.string.upd_up_to_date, current))
            return
        }
        if (!install) {
            setStatus(tr(R.string.upd_available, latest))
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            setStatus(tr(R.string.upd_available_allow, latest))
            return
        }
        setStatus(tr(R.string.upd_downloading, latest))
        val file = File(context.cacheDir, "walldock-update.apk")
        download(apkUrl, file)
        val installing = tr(R.string.upd_installing, latest)
        setStatus(installing)
        installApk(context, file)
        // Sans réponse d'Android au bout d'une minute : il attend sûrement une confirmation à l'écran
        main.postDelayed({ if (status == installing) setStatus(tr(R.string.upd_waiting_screen, latest)) }, 60_000)
    }

    private fun installApk(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Sans confirmation quand WallDock s'est lui-même installé la fois précédente
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("walldock.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, 0, intent, flags).intentSender)
        }
    }

    private fun httpGet(address: String): String {
        val connection = open(address)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode == 404) throw IllegalStateException(tr(R.string.upd_no_release))
            if (connection.responseCode != 200) throw IllegalStateException(tr(R.string.upd_github_code, connection.responseCode))
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(address: String, target: File) {
        val connection = open(address)
        try {
            if (connection.responseCode != 200) throw IllegalStateException(tr(R.string.upd_download_refused, connection.responseCode))
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "WallDock/${BuildConfig.VERSION_NAME}")
        return connection
    }

    /** Compare « 0.10.1 » et « 0.9.3 » chiffre par chiffre. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}

/** Résultat de l'installation, et redémarrage de WallDock juste après une mise à jour. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Updater.ACTION_INSTALL_STATUS -> {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // Première mise à jour (ou tablette ancienne) : Android demande une confirmation
                        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(confirm)
                                Updater.setStatus(tr(R.string.upd_confirm))
                            } catch (e: Exception) {
                                Updater.setStatus(tr(R.string.upd_confirm_failed))
                            }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> Updater.setStatus(tr(R.string.upd_installed))
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: tr(R.string.unknown_error)
                        Updater.setStatus(tr(R.string.upd_install_failed, message))
                    }
                }
            }
        }
    }
}
