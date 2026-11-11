package app.walldock

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address

/** Réglages : adresse HA, code, délai, autorisations et choix des applis du dock. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var urlField: EditText
    private lateinit var pinField: EditText
    private lateinit var delayField: EditText
    private lateinit var statusText: TextView
    private lateinit var handleGroup: RadioGroup
    private lateinit var haStatusText: TextView
    private lateinit var saverCheck: CheckBox
    private lateinit var saverField: EditText

    private val dockSelection = mutableListOf<String>()
    private val videoSelection = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        dockSelection.addAll(prefs.dockApps)
        videoSelection.addAll(prefs.videoApps)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(24), dp(24), dp(24), dp(24))

        column.addView(heading("WallDock — réglages", 22f))

        column.addView(label("Adresse de Home Assistant"))
        urlField = field(prefs.haUrl, "http://homeassistant.local:8123", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        column.addView(urlField)

        column.addView(label("Code administrateur (4 chiffres minimum)"))
        pinField = field(prefs.pin, "1234", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        column.addView(pinField)

        column.addView(label("Retour au tableau de bord après (secondes d’inactivité)"))
        delayField = field(prefs.returnSeconds.toString(), "120", InputType.TYPE_CLASS_NUMBER)
        column.addView(delayField)

        column.addView(label("Écran de veille"))
        saverCheck = CheckBox(this)
        saverCheck.text = "Afficher une horloge sur fond noir après une période sans toucher l’écran"
        saverCheck.setTextColor(Color.WHITE)
        saverCheck.isChecked = prefs.screensaverEnabled
        column.addView(saverCheck)
        column.addView(label("Délai avant l’écran de veille (minutes)"))
        saverField = field(prefs.screensaverMinutes.toString(), "5", InputType.TYPE_CLASS_NUMBER)
        column.addView(saverField)

        column.addView(label("Poignée du dock"))
        handleGroup = RadioGroup(this)
        val handleChoices = listOf(
            Prefs.HANDLE_BOTTOM_RIGHT to "En bas à droite",
            Prefs.HANDLE_BOTTOM_LEFT to "En bas à gauche",
            Prefs.HANDLE_RIGHT_MIDDLE to "Au milieu du bord droit",
            Prefs.HANDLE_HIDDEN to "Cachée : glisser vers la gauche depuis le milieu du bord droit"
        )
        for ((value, text) in handleChoices) {
            val choice = RadioButton(this)
            choice.id = View.generateViewId()
            choice.text = text
            choice.tag = value
            choice.setTextColor(Color.WHITE)
            handleGroup.addView(choice)
            if (value == prefs.handlePosition) handleGroup.check(choice.id)
        }
        column.addView(handleGroup)

        column.addView(heading("Autorisations", 18f))
        statusText = TextView(this)
        statusText.setTextColor(0xFFB0C4E0.toInt())
        statusText.textSize = 14f
        statusText.setLineSpacing(0f, 1.3f)
        column.addView(statusText)
        column.addView(button("Définir WallDock comme écran d’accueil") {
            open(Intent(Settings.ACTION_HOME_SETTINGS))
        })
        column.addView(button("Autoriser l’affichage par-dessus les autres applis") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        column.addView(button("Activer la détection d’activité (accessibilité)") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(button("Quitter le mode kiosque") {
            toast("Choisis l’écran d’accueil habituel de la tablette")
            open(Intent(Settings.ACTION_HOME_SETTINGS))
        })

        column.addView(heading("Home Assistant", 18f))
        haStatusText = TextView(this)
        haStatusText.setTextColor(0xFFB0C4E0.toInt())
        haStatusText.textSize = 14f
        haStatusText.setLineSpacing(0f, 1.3f)
        column.addView(haStatusText)

        column.addView(heading("Applis du dock", 18f))
        column.addView(label("« Dock » : l’appli apparaît dans le dock. « Vidéo » : pas de retour automatique tant qu’elle est ouverte."))
        for (app in Apps.listLaunchable(this)) column.addView(appRow(app))

        column.addView(button("Enregistrer") { save() })

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF050C1E.toInt())
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateHaStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!prefs.isConfigured) {
            toast("Enregistre d’abord les réglages")
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun save() {
        val url = urlField.text.toString().trim().trimEnd('/')
        val pin = pinField.text.toString().trim()
        val delay = delayField.text.toString().trim().toIntOrNull() ?: 0
        val saverMinutes = saverField.text.toString().trim().toIntOrNull() ?: 0
        when {
            saverCheck.isChecked && saverMinutes < 1 -> toast("Le délai de l’écran de veille doit être d’au moins 1 minute")
            !(url.startsWith("http://") || url.startsWith("https://")) ->
                toast("L’adresse doit commencer par http:// ou https://")
            pin.length < 4 -> toast("Le code doit faire au moins 4 chiffres")
            delay < 10 -> toast("Le délai doit être d’au moins 10 secondes")
            else -> {
                prefs.haUrl = url
                prefs.pin = pin
                prefs.returnSeconds = delay
                prefs.screensaverEnabled = saverCheck.isChecked
                if (saverMinutes >= 1) prefs.screensaverMinutes = saverMinutes
                prefs.dockApps = dockSelection.toList()
                prefs.videoApps = videoSelection.toSet()
                val checked = handleGroup.findViewById<RadioButton>(handleGroup.checkedRadioButtonId)
                prefs.handlePosition = (checked?.tag as? String) ?: Prefs.HANDLE_BOTTOM_RIGHT
                ApiService.instance?.onDockChanged()
                toast("Réglages enregistrés")
                finish()
            }
        }
    }

    private fun updateStatus() {
        val home = isDefaultHome()
        val overlay = Settings.canDrawOverlays(this)
        val activity = AppState.accessibilityActive
        statusText.text = listOf(
            (if (home) "✅ " else "⚠️ ") + "Écran d’accueil par défaut",
            (if (overlay) "✅ Affichage par-dessus les autres applis" else "⚠️ Affichage par-dessus les autres applis (indispensable au retour automatique)"),
            (if (activity) "✅ Détection d’activité" else "➖ Détection d’activité (sans elle : retour après un délai fixe)")
        ).joinToString("\n")
    }

    private fun updateHaStatus() {
        val service = ApiService.instance
        val error = AppState.apiError
        val ip = localIpAddress() ?: "adresse inconnue"
        haStatusText.text = when {
            error != null -> "⚠️ $error"
            service == null -> "➖ Service non démarré"
            service.connectedClients() > 0 -> "✅ Connecté à Home Assistant\nAppareil : ${service.friendlyName} (${service.deviceName})"
            else -> "⏳ En attente de Home Assistant\n" +
                "Home Assistant devrait proposer « ${service.friendlyName} » dans Paramètres → Appareils et services.\n" +
                "Sinon : Ajouter une intégration → ESPHome → hôte $ip, port 6053."
        }
    }

    private fun localIpAddress(): String? {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return null
        val properties = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
        return properties.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Ce réglage n’existe pas sur cette tablette")
        }
    }

    private fun appRow(app: LaunchableApp): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))

        val icon = ImageView(this)
        icon.setImageDrawable(app.icon)
        row.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))

        val name = TextView(this)
        name.text = app.label
        name.setTextColor(Color.WHITE)
        name.textSize = 15f
        name.setPadding(dp(12), 0, 0, 0)
        row.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val inDock = CheckBox(this)
        inDock.text = "Dock"
        inDock.setTextColor(Color.WHITE)
        inDock.isChecked = app.packageName in dockSelection
        inDock.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (app.packageName !in dockSelection) dockSelection.add(app.packageName)
            } else {
                dockSelection.remove(app.packageName)
            }
        }
        row.addView(inDock)

        val video = CheckBox(this)
        video.text = "Vidéo"
        video.setTextColor(Color.WHITE)
        video.isChecked = app.packageName in videoSelection
        video.setOnCheckedChangeListener { _, checked ->
            if (checked) videoSelection.add(app.packageName) else videoSelection.remove(app.packageName)
        }
        row.addView(video)
        return row
    }

    private fun heading(s: String, size: Float): TextView {
        val view = TextView(this)
        view.text = s
        view.textSize = size
        view.setTextColor(0xFF3FD4FF.toInt())
        view.setPadding(0, dp(20), 0, dp(8))
        return view
    }

    private fun label(s: String): TextView {
        val view = TextView(this)
        view.text = s
        view.textSize = 14f
        view.setTextColor(0xFFB0C4E0.toInt())
        view.setPadding(0, dp(12), 0, dp(4))
        return view
    }

    private fun field(value: String, hintText: String, type: Int): EditText {
        val view = EditText(this)
        view.setText(value)
        view.hint = hintText
        view.inputType = type
        view.isSingleLine = true
        view.setTextColor(Color.WHITE)
        view.setHintTextColor(0xFF7F9BC4.toInt())
        return view
    }

    private fun button(s: String, action: () -> Unit): Button {
        val view = Button(this)
        view.text = s
        view.isAllCaps = false
        view.setOnClickListener { action() }
        return view
    }
}
