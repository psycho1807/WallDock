package app.walldock

import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var sensitivityGroup: RadioGroup
    private lateinit var haStatusText: TextView
    private lateinit var saverCheck: CheckBox
    private lateinit var saverField: EditText
    private lateinit var saverUrlField: EditText
    private lateinit var zoomField: EditText
    private lateinit var systemBrightnessCheck: CheckBox
    private lateinit var bootCheck: CheckBox
    private lateinit var scheduleCheck: CheckBox
    private lateinit var sleepField: EditText
    private lateinit var wakeField: EditText
    private lateinit var allowedField: EditText
    private lateinit var updateCheck: CheckBox
    private lateinit var jarvisCheck: CheckBox
    private lateinit var onDeviceCheck: CheckBox
    private lateinit var motionCheck: CheckBox
    private lateinit var jarvisText: TextView
    private lateinit var updateText: TextView
    private val updateListener: (String) -> Unit = { updateText.text = updateLine(it) }

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

        column.addView(heading(tr(R.string.settings_title), 22f))

        column.addView(label(tr(R.string.settings_ha_address)))
        urlField = field(prefs.haUrl, "http://homeassistant.local:8123", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        column.addView(urlField)

        column.addView(label(tr(R.string.settings_pin)))
        pinField = field(prefs.pin, "1234", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        column.addView(pinField)

        column.addView(label(tr(R.string.settings_return_delay)))
        delayField = field(prefs.returnSeconds.toString(), "120", InputType.TYPE_CLASS_NUMBER)
        column.addView(delayField)

        column.addView(label(tr(R.string.settings_screensaver)))
        saverCheck = CheckBox(this)
        saverCheck.text = tr(R.string.settings_screensaver_check)
        saverCheck.setTextColor(Color.WHITE)
        saverCheck.isChecked = prefs.screensaverEnabled
        column.addView(saverCheck)
        column.addView(label(tr(R.string.settings_screensaver_delay)))
        saverField = field(prefs.screensaverMinutes.toString(), "5", InputType.TYPE_CLASS_NUMBER)
        column.addView(saverField)
        column.addView(label(tr(R.string.settings_zoom)))
        zoomField = field(prefs.zoom.toString(), "100", InputType.TYPE_CLASS_NUMBER)
        column.addView(zoomField)
        column.addView(label(tr(R.string.settings_saver_url)))
        saverUrlField = field(prefs.screensaverUrl, tr(R.string.settings_saver_url_hint), InputType.TYPE_TEXT_VARIATION_URI)
        column.addView(saverUrlField)
        systemBrightnessCheck = CheckBox(this)
        systemBrightnessCheck.text = tr(R.string.settings_system_brightness)
        systemBrightnessCheck.setTextColor(Color.WHITE)
        systemBrightnessCheck.isChecked = prefs.systemBrightness
        column.addView(systemBrightnessCheck)
        column.addView(label(tr(R.string.settings_system_brightness_help)))
        bootCheck = CheckBox(this)
        bootCheck.text = tr(R.string.settings_start_on_boot)
        bootCheck.setTextColor(Color.WHITE)
        bootCheck.isChecked = prefs.startOnBoot
        column.addView(bootCheck)
        scheduleCheck = CheckBox(this)
        scheduleCheck.text = tr(R.string.settings_schedule)
        scheduleCheck.setTextColor(Color.WHITE)
        scheduleCheck.isChecked = prefs.scheduleEnabled
        column.addView(scheduleCheck)
        val times = LinearLayout(this)
        times.orientation = LinearLayout.HORIZONTAL
        sleepField = field(prefs.sleepTime, "23:00", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        wakeField = field(prefs.wakeTime, "07:00", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        times.addView(label(tr(R.string.settings_schedule_off)))
        times.addView(sleepField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        times.addView(label(tr(R.string.settings_schedule_on)))
        times.addView(wakeField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        column.addView(times)
        column.addView(label(tr(R.string.settings_allowed_urls)))
        allowedField = field(prefs.allowedUrls, tr(R.string.settings_allowed_urls_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        column.addView(allowedField)

        column.addView(label(tr(R.string.settings_handle)))
        handleGroup = RadioGroup(this)
        val handleChoices = listOf(
            Prefs.HANDLE_BOTTOM_RIGHT to tr(R.string.handle_bottom_right),
            Prefs.HANDLE_BOTTOM_LEFT to tr(R.string.handle_bottom_left),
            Prefs.HANDLE_RIGHT_MIDDLE to tr(R.string.handle_right_middle),
            Prefs.HANDLE_HIDDEN to tr(R.string.handle_hidden)
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

        column.addView(heading(tr(R.string.settings_permissions), 18f))
        statusText = TextView(this)
        statusText.setTextColor(0xFFB0C4E0.toInt())
        statusText.textSize = 14f
        statusText.setLineSpacing(0f, 1.3f)
        column.addView(statusText)
        column.addView(button(tr(R.string.settings_set_home)) {
            open(Intent(Settings.ACTION_HOME_SETTINGS))
        })
        column.addView(button(tr(R.string.settings_allow_overlay)) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        // Version Google Play : pas de service d'accessibilité
        if (!BuildConfig.PLAY_STORE) column.addView(button(tr(R.string.settings_enable_activity)) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(button(tr(R.string.settings_exit_kiosk)) {
            toast(tr(R.string.settings_choose_home))
            open(Intent(Settings.ACTION_HOME_SETTINGS))
        })

        column.addView(heading("Home Assistant", 18f))
        haStatusText = TextView(this)
        haStatusText.setTextColor(0xFFB0C4E0.toInt())
        haStatusText.textSize = 14f
        haStatusText.setLineSpacing(0f, 1.3f)
        column.addView(haStatusText)
        column.addView(button(tr(R.string.encryption_reset)) {
            AlertDialog.Builder(this)
                .setMessage(tr(R.string.encryption_reset_confirm))
                .setPositiveButton(tr(R.string.encryption_reset)) { _, _ ->
                    ApiService.instance?.resetEncryption()
                    updateHaStatus()
                }
                .setNegativeButton(tr(R.string.cancel), null)
                .show()
        })

        column.addView(heading("Jarvis", 18f))
        jarvisText = TextView(this)
        jarvisText.setTextColor(0xFFB0C4E0.toInt())
        jarvisText.textSize = 14f
        jarvisText.setLineSpacing(0f, 1.3f)
        column.addView(jarvisText)
        jarvisCheck = CheckBox(this)
        jarvisCheck.text = tr(R.string.settings_jarvis_listen)
        jarvisCheck.setTextColor(Color.WHITE)
        jarvisCheck.isChecked = prefs.jarvisWakeWord
        column.addView(jarvisCheck)
        onDeviceCheck = CheckBox(this)
        onDeviceCheck.text = tr(R.string.settings_jarvis_on_device)
        onDeviceCheck.setTextColor(Color.WHITE)
        onDeviceCheck.isChecked = prefs.jarvisOnDevice
        column.addView(onDeviceCheck)
        motionCheck = CheckBox(this)
        motionCheck.text = tr(R.string.settings_motion)
        motionCheck.setTextColor(Color.WHITE)
        motionCheck.isChecked = prefs.motionEnabled
        column.addView(motionCheck)
        column.addView(label(tr(R.string.settings_motion_help)))
        column.addView(label(tr(R.string.settings_sensitivity)))
        sensitivityGroup = RadioGroup(this)
        val levels = listOf(
            tr(R.string.sensitivity_normal), tr(R.string.sensitivity_high), tr(R.string.sensitivity_very_high),
            tr(R.string.sensitivity_low)
        )
        Jarvis.SENSITIVITY_ORDER.forEach { index ->
            val text = levels[index]
            val choice = RadioButton(this)
            choice.id = View.generateViewId()
            choice.text = text
            choice.tag = index
            choice.setTextColor(Color.WHITE)
            sensitivityGroup.addView(choice)
            if (index == prefs.jarvisSensitivity) sensitivityGroup.check(choice.id)
        }
        column.addView(sensitivityGroup)
        column.addView(label(tr(R.string.voice_help)))
        column.addView(button(tr(R.string.voice_learn)) { VoiceTraining(this).start() })
        column.addView(button(tr(R.string.voice_forget)) {
            VoiceVerifier.forget(this)
            ApiService.instance?.jarvis?.reloadVoiceSettings()
            updateJarvisStatus()
            toast(tr(R.string.voice_forgotten))
        })
        column.addView(button(tr(R.string.settings_allow_mic)) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                toast(tr(R.string.settings_mic_already))
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MainActivity.REQUEST_MIC)
            }
        })
        column.addView(button(tr(R.string.settings_test_jarvis)) { ApiService.instance?.jarvis?.talk() })

        column.addView(heading(tr(R.string.settings_updates), 18f))
        if (BuildConfig.PLAY_STORE) column.addView(label(tr(R.string.updates_play)))
        updateText = TextView(this)
        updateText.setTextColor(0xFFB0C4E0.toInt())
        updateText.textSize = 14f
        updateText.setLineSpacing(0f, 1.3f)
        column.addView(updateText)
        updateCheck = CheckBox(this)
        updateCheck.text = tr(R.string.settings_auto_update)
        updateCheck.setTextColor(Color.WHITE)
        updateCheck.isChecked = prefs.autoUpdate
        // Version Google Play : les mises à jour passent par le Play Store
        if (!BuildConfig.PLAY_STORE) {
            column.addView(updateCheck)
            column.addView(button(tr(R.string.settings_allow_install)) {
                open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            })
            column.addView(button(tr(R.string.settings_check_now)) { Updater.check(this, install = true) })
        }

        column.addView(heading(tr(R.string.backup_title), 18f))
        column.addView(label(tr(R.string.backup_help)))
        column.addView(button(tr(R.string.backup_export)) { SettingsBackup.startExport(this) })
        column.addView(button(tr(R.string.backup_import)) { SettingsBackup.startImport(this) })

        column.addView(heading(tr(R.string.settings_dock_apps), 18f))
        column.addView(label(tr(R.string.settings_dock_help)))
        for (app in Apps.listLaunchable(this)) column.addView(appRow(app))

        column.addView(button(tr(R.string.settings_save)) { save() })

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF050C1E.toInt())
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateHaStatus()
        updateText.text = updateLine(Updater.status)
        updateJarvisStatus()
        Updater.addListener(updateListener)
    }

    override fun onPause() {
        Updater.removeListener(updateListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MainActivity.REQUEST_CAMERA) ApiService.instance?.applyMotionSettings()
        if (requestCode == MainActivity.REQUEST_MIC) {
            ApiService.instance?.onMicrophoneChanged()
            updateJarvisStatus()
        }
    }

    fun updateJarvisStatus() {
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val state = ApiService.instance?.jarvis?.state
        jarvisText.text = listOf(
            if (mic) tr(R.string.jarvis_mic_ok) else tr(R.string.jarvis_mic_missing),
            when (state) {
                Jarvis.State.WAITING -> tr(R.string.jarvis_status_waiting)
                Jarvis.State.OFF, null -> tr(R.string.jarvis_status_off)
                else -> tr(R.string.jarvis_status_busy)
            },
            if (ApiService.instance?.jarvis?.detectsOnDevice == true) {
                tr(R.string.jarvis_detect_tablet)
            } else {
                tr(R.string.jarvis_detect_ha)
            },
            (ApiService.instance?.jarvis?.learnedVoices ?: 0).let {
                if (it > 0) tr(R.string.voice_count, it) else tr(R.string.voice_none)
            }
        ).joinToString("\n")
    }

    private fun updateLine(status: String): String {
        val allowed = packageManager.canRequestPackageInstalls()
        return tr(R.string.update_installed_version, BuildConfig.VERSION_NAME) + "\n" +
            (if (allowed) tr(R.string.update_install_allowed) else tr(R.string.update_install_denied)) +
            "\n$status"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            SettingsBackup.REQUEST_EXPORT ->
                toast(tr(if (SettingsBackup.write(this, uri)) R.string.backup_saved else R.string.backup_failed))
            SettingsBackup.REQUEST_IMPORT -> {
                if (SettingsBackup.read(this, uri)) {
                    toast(tr(R.string.backup_restored))
                    window.decorView.postDelayed({ SettingsBackup.restartApp(this) }, 1_500)
                } else {
                    toast(tr(R.string.backup_invalid))
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!prefs.isConfigured) {
            toast(tr(R.string.settings_save_first))
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
            saverCheck.isChecked && saverMinutes < 1 -> toast(tr(R.string.error_saver_delay))
            !(url.startsWith("http://") || url.startsWith("https://")) ->
                toast(tr(R.string.error_url))
            pin.length < 4 -> toast(tr(R.string.error_pin))
            delay < 10 -> toast(tr(R.string.error_delay))
            else -> {
                prefs.haUrl = url
                prefs.pin = pin
                prefs.returnSeconds = delay
                prefs.screensaverEnabled = saverCheck.isChecked
                prefs.autoUpdate = updateCheck.isChecked
                prefs.screensaverUrl = saverUrlField.text.toString().trim()
                zoomField.text.toString().toIntOrNull()?.let { prefs.zoom = it }
                prefs.startOnBoot = bootCheck.isChecked
                prefs.scheduleEnabled = scheduleCheck.isChecked
                sleepField.text.toString().trim().let { if (Regex("^\\d{1,2}[:hH]\\d{0,2}$").matches(it)) prefs.sleepTime = it }
                wakeField.text.toString().trim().let { if (Regex("^\\d{1,2}[:hH]\\d{0,2}$").matches(it)) prefs.wakeTime = it }
                prefs.allowedUrls = allowedField.text.toString().trim()
                prefs.systemBrightness = systemBrightnessCheck.isChecked
                if (systemBrightnessCheck.isChecked && !Settings.System.canWrite(this)) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        // écran indisponible
                    }
                }
                AppState.controller?.applyScreen()
                val wakeChanged = prefs.jarvisWakeWord != jarvisCheck.isChecked
                prefs.jarvisWakeWord = jarvisCheck.isChecked
                val modeChanged = prefs.jarvisOnDevice != onDeviceCheck.isChecked
                if (modeChanged && onDeviceCheck.isChecked) WakeWordDetector.clearCrash(this)
                prefs.jarvisOnDevice = onDeviceCheck.isChecked
                prefs.motionEnabled = motionCheck.isChecked
                if (motionCheck.isChecked &&
                    checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.CAMERA), MainActivity.REQUEST_CAMERA)
                }
                ApiService.instance?.applyMotionSettings()
                val level = sensitivityGroup.findViewById<RadioButton>(sensitivityGroup.checkedRadioButtonId)
                prefs.jarvisSensitivity = (level?.tag as? Int) ?: 0
                ApiService.instance?.jarvis?.reloadVoiceSettings()
                if (wakeChanged || modeChanged) ApiService.instance?.jarvis?.onSettingsChanged()
                if (saverMinutes >= 1) prefs.screensaverMinutes = saverMinutes
                prefs.dockApps = dockSelection.toList()
                prefs.videoApps = videoSelection.toSet()
                val checked = handleGroup.findViewById<RadioButton>(handleGroup.checkedRadioButtonId)
                prefs.handlePosition = (checked?.tag as? String) ?: Prefs.HANDLE_BOTTOM_RIGHT
                ApiService.instance?.onDockChanged()
                ApiService.instance?.reportSettings()
                toast(tr(R.string.settings_saved))
                finish()
            }
        }
    }

    private fun updateStatus() {
        val home = isDefaultHome()
        val overlay = Settings.canDrawOverlays(this)
        val activity = AppState.accessibilityActive
        statusText.text = listOf(
            (if (home) "✅ " else "⚠️ ") + tr(R.string.status_home) + (if (home) "" else "\n   " + tr(R.string.status_home_warning)),
            (if (overlay) tr(R.string.status_overlay_ok) else tr(R.string.status_overlay_missing)),
            if (BuildConfig.PLAY_STORE) null
            else if (activity) tr(R.string.status_activity_ok) else tr(R.string.status_activity_missing)
        ).filterNotNull().joinToString("\n")
    }

    private fun updateHaStatus() {
        val service = ApiService.instance
        val error = AppState.apiError
        val ip = localIpAddress() ?: tr(R.string.unknown_address)
        haStatusText.text = when {
            error != null -> "⚠️ $error"
            service == null -> tr(R.string.ha_service_stopped)
            service.connectedClients() > 0 -> tr(R.string.ha_connected, service.friendlyName, service.deviceName)
            else -> tr(R.string.ha_waiting, service.friendlyName, ip)
        } + if (service != null && error == null) {
            "\n" + tr(if (service.isEncrypted) R.string.encryption_on else R.string.encryption_off)
        } else {
            ""
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
            toast(tr(R.string.setting_missing))
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
        video.text = tr(R.string.dock_video)
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
