package app.walldock

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * Écran principal : le tableau de bord Home Assistant en plein écran,
 * avec un dock d'applis qui s'ouvre depuis la poignée en bas à droite.
 */
class MainActivity : Activity(), DashboardController {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var dockPanel: HorizontalScrollView
    private lateinit var dockRow: LinearLayout
    private lateinit var handle: TextView
    private lateinit var edgeStrip: View
    private lateinit var overlayWarning: TextView
    private lateinit var blackCover: View
    private lateinit var screensaver: FrameLayout
    private lateinit var screensaverContent: View
    private var lastTouch = 0L
    private var swallowGesture = false
    private val screensaverCheck = object : Runnable {
        override fun run() {
            checkScreensaver()
            handler.postDelayed(this, 15_000)
        }
    }

    private var loadedUrl = ""
    private val handler = Handler(Looper.getMainLooper())
    private val autoHideDock = Runnable { setDockVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // Tableau de bord Home Assistant
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Dock
        dockRow = LinearLayout(this)
        dockRow.orientation = LinearLayout.HORIZONTAL
        dockRow.gravity = Gravity.CENTER_VERTICAL
        dockRow.setPadding(dp(12), dp(10), dp(12), dp(10))

        dockPanel = HorizontalScrollView(this)
        dockPanel.isHorizontalScrollBarEnabled = false
        dockPanel.background = panelBackground(0xE60A1634.toInt(), 24)
        dockPanel.addView(dockRow)
        dockPanel.visibility = View.GONE
        val dockParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dockParams.bottomMargin = dp(20)
        root.addView(dockPanel, dockParams)

        // Poignée qui ouvre et ferme le dock (position choisie dans les réglages)
        handle = TextView(this)
        handle.text = "▲"
        handle.textSize = 14f
        handle.setTextColor(Color.WHITE)
        handle.gravity = Gravity.CENTER
        handle.alpha = 0.3f
        handle.background = panelBackground(0xCC0A1634.toInt(), 18)
        handle.setOnClickListener { setDockVisible(dockPanel.visibility != View.VISIBLE) }
        root.addView(handle, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END))

        // Bande invisible sur le bord droit : glisser vers la gauche ouvre le dock (poignée cachée)
        edgeStrip = View(this)
        var downX = 0f
        var opened = false
        edgeStrip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    opened = false
                }
                MotionEvent.ACTION_MOVE -> if (!opened && downX - event.rawX > dp(40)) {
                    opened = true
                    setDockVisible(true)
                }
            }
            true
        }
        edgeStrip.visibility = View.GONE
        // Zone de 200 dp au milieu du bord droit : c'est le maximum qu'Android accepte
        // de soustraire à son geste « Retour » (glissement depuis le bord).
        edgeStrip.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.systemGestureExclusionRects = listOf(Rect(0, 0, view.width, view.height))
            }
        }
        root.addView(edgeStrip, FrameLayout.LayoutParams(dp(28), dp(200), Gravity.END or Gravity.CENTER_VERTICAL))

        // Bandeau d'alerte si l'autorisation « affichage par-dessus » manque
        overlayWarning = TextView(this)
        overlayWarning.text = "⚠️ Autorisation « affichage par-dessus les autres applis » manquante : " +
            "le retour automatique ne fonctionnera pas. Touchez ici pour la donner."
        overlayWarning.setTextColor(Color.WHITE)
        overlayWarning.textSize = 14f
        overlayWarning.setPadding(dp(16), dp(10), dp(16), dp(10))
        overlayWarning.setBackgroundColor(0xE6B23A48.toInt())
        overlayWarning.visibility = View.GONE
        overlayWarning.setOnClickListener {
            Pin.ask(this, prefs) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(overlayWarning, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // Écran noir quand Home Assistant « éteint » l'écran ; un toucher le rallume
        blackCover = View(this)
        blackCover.setBackgroundColor(Color.BLACK)
        blackCover.visibility = View.GONE
        blackCover.setOnClickListener {
            AppState.screenOn = true
            applyScreen()
            ApiService.instance?.reportScreen()
        }
        root.addView(blackCover, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Écran de veille : horloge et date sur fond noir
        screensaver = FrameLayout(this)
        screensaver.setBackgroundColor(Color.BLACK)
        screensaver.visibility = View.GONE
        val clockColumn = LinearLayout(this)
        clockColumn.orientation = LinearLayout.VERTICAL
        clockColumn.gravity = Gravity.CENTER_HORIZONTAL
        val clock = TextClock(this)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.textSize = 120f
        clock.setTextColor(0xFFDBE9FF.toInt())
        clock.setShadowLayer(dp(18).toFloat(), 0f, 0f, 0x993FD4FF.toInt())
        clockColumn.addView(clock)
        val date = TextClock(this)
        date.format24Hour = "EEEE d MMMM"
        date.format12Hour = "EEEE d MMMM"
        date.textSize = 24f
        date.setTextColor(0xFF7F9BC4.toInt())
        clockColumn.addView(date)
        screensaverContent = clockColumn
        screensaver.addView(clockColumn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(screensaver, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setContentView(root)
        ApiService.start(this)
    }

    override fun onResume() {
        super.onResume()
        AppState.dashboardVisible = true
        AppState.controller = this
        AppState.currentApp = "Tableau de bord"
        ApiService.instance?.reportCurrentApp()
        stopService(Intent(this, ReturnService::class.java))
        enterImmersive()
        applyScreen()

        if (!prefs.isConfigured) {
            // Premier lancement : pas encore de code, on ouvre directement les réglages
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (prefs.haUrl != loadedUrl) {
            loadedUrl = prefs.haUrl
            web.loadUrl(loadedUrl)
        }
        web.onResume()
        rebuildDock()
        applyHandlePosition()
        overlayWarning.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        handlePendingRequests()
        lastTouch = SystemClock.elapsedRealtime()
        handler.removeCallbacks(screensaverCheck)
        handler.postDelayed(screensaverCheck, 15_000)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        if (screensaver.visibility == View.VISIBLE) {
            // Un toucher réveille l'écran de veille sans cliquer sur le tableau de bord en dessous
            screensaver.visibility = View.GONE
            swallowGesture = true
        }
        if (swallowGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowGesture = false
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun checkScreensaver() {
        if (!prefs.screensaverEnabled || !AppState.dashboardVisible || !AppState.screenOn) {
            screensaver.visibility = View.GONE
            return
        }
        val idleMs = SystemClock.elapsedRealtime() - lastTouch
        if (idleMs >= prefs.screensaverMinutes * 60_000L) {
            if (screensaver.visibility != View.VISIBLE) {
                setDockVisible(false)
                screensaver.visibility = View.VISIBLE
            }
            // Déplacer légèrement l'horloge pour ne pas marquer l'écran
            val range = dp(60)
            screensaverContent.translationX = (Math.random() * 2 * range - range).toFloat()
            screensaverContent.translationY = (Math.random() * 2 * range - range).toFloat()
        }
    }

    private fun applyHandlePosition() {
        val mode = prefs.handlePosition
        edgeStrip.visibility = if (mode == Prefs.HANDLE_HIDDEN) View.VISIBLE else View.GONE
        if (mode == Prefs.HANDLE_HIDDEN) {
            handle.visibility = View.GONE
            return
        }
        handle.visibility = View.VISIBLE
        val params = FrameLayout.LayoutParams(dp(36), dp(36))
        when (mode) {
            Prefs.HANDLE_BOTTOM_LEFT -> {
                params.gravity = Gravity.BOTTOM or Gravity.START
                params.setMargins(dp(10), 0, 0, dp(10))
            }
            Prefs.HANDLE_RIGHT_MIDDLE -> {
                params.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                params.setMargins(0, 0, dp(4), 0)
            }
            else -> {
                params.gravity = Gravity.BOTTOM or Gravity.END
                params.setMargins(0, 0, dp(10), dp(10))
            }
        }
        handle.layoutParams = params
    }

    override fun onPause() {
        handler.removeCallbacks(screensaverCheck)
        screensaver.visibility = View.GONE
        AppState.dashboardVisible = false
        setDockVisible(false)
        web.onPause()
        super.onPause()
    }

    /** Applique l'état d'écran et la luminosité demandés par Home Assistant. */
    override fun applyScreen() {
        val params = window.attributes
        if (AppState.screenOn) {
            blackCover.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            params.screenBrightness = if (AppState.brightness < 0) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                (AppState.brightness / 100f).coerceIn(0.01f, 1f)
            }
        } else {
            // Écran noir au minimum de luminosité ; sans « garder allumé », Android peut le mettre en veille
            blackCover.visibility = View.VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            params.screenBrightness = 0.01f
        }
        window.attributes = params
    }

    override fun reloadDashboard() {
        web.reload()
    }

    override fun showHome() {
        if (prefs.haUrl.isNotBlank() && web.url != prefs.haUrl) web.loadUrl(prefs.haUrl)
    }

    override fun openApp(packageName: String) {
        launchApp(packageName)
    }

    override fun openUrl(url: String) {
        setDockVisible(false)
        web.loadUrl(url)
    }

    /** Traite les demandes de Home Assistant reçues pendant qu'une autre appli était ouverte. */
    private fun handlePendingRequests() {
        if (AppState.pendingHome) {
            AppState.pendingHome = false
            showHome()
        }
        AppState.pendingUrl?.let {
            AppState.pendingUrl = null
            openUrl(it)
        }
        AppState.pendingApp?.let {
            AppState.pendingApp = null
            // Laisser l'écran se poser avant de lancer l'appli
            handler.postDelayed({ launchApp(it) }, 300)
        }
    }

    override fun onDestroy() {
        if (AppState.controller === this) AppState.controller = null
        handler.removeCallbacks(autoHideDock)
        web.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Mode kiosque : le bouton Retour ne quitte jamais l'appli
        when {
            dockPanel.visibility == View.VISIBLE -> setDockVisible(false)
            web.canGoBack() -> web.goBack()
        }
    }

    private fun rebuildDock() {
        dockRow.removeAllViews()
        for (packageName in prefs.dockApps) {
            val app = Apps.load(this, packageName) ?: continue
            val icon = ImageView(this)
            icon.setImageDrawable(app.icon)
            dockRow.addView(dockItem(app.label, icon) { launchApp(app.packageName) })
        }
        dockRow.addView(dockItem("Recharger", glyph("⟳")) {
            web.reload()
            setDockVisible(false)
        })
        dockRow.addView(dockItem("Réglages", glyph("⚙")) {
            setDockVisible(false)
            Pin.ask(this, prefs) { startActivity(Intent(this, SettingsActivity::class.java)) }
        })
    }

    private fun launchApp(packageName: String) {
        setDockVisible(false)
        if (!Apps.launch(this, packageName)) {
            toast("Impossible d’ouvrir cette appli")
            return
        }
        val seconds = if (packageName in prefs.videoApps) 0 else prefs.returnSeconds
        ReturnService.start(this, packageName, seconds)
        AppState.currentApp = Apps.load(this, packageName)?.label ?: packageName
        ApiService.instance?.reportCurrentApp()
    }

    private fun setDockVisible(visible: Boolean) {
        dockPanel.visibility = if (visible) View.VISIBLE else View.GONE
        handle.text = if (visible) "▼" else "▲"
        handler.removeCallbacks(autoHideDock)
        if (visible) handler.postDelayed(autoHideDock, 10_000)
    }

    private fun dockItem(label: String, iconView: View, onClick: () -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(8), dp(4), dp(8), dp(4))
        box.setOnClickListener { onClick() }
        box.addView(iconView, LinearLayout.LayoutParams(dp(52), dp(52)))

        val text = TextView(this)
        text.text = label
        text.textSize = 11f
        text.setTextColor(0xFFDBE9FF.toInt())
        text.maxLines = 1
        text.ellipsize = TextUtils.TruncateAt.END
        text.gravity = Gravity.CENTER
        box.addView(text, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))
        return box
    }

    private fun glyph(symbol: String): TextView {
        val view = TextView(this)
        view.text = symbol
        view.textSize = 26f
        view.setTextColor(0xFF3FD4FF.toInt())
        view.gravity = Gravity.CENTER
        return view
    }

    private fun panelBackground(color: Int, radiusDp: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(color)
        drawable.cornerRadius = dp(radiusDp).toFloat()
        drawable.setStroke(dp(1), 0x803FD4FF.toInt())
        return drawable
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}
