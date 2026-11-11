package app.walldock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Caméra en plein écran par-dessus tout (action afficher_camera) : par exemple la sonnette
 * quand quelqu'un sonne. Le flux MJPEG de Home Assistant est affiché dans une petite page web ;
 * toucher « Fermer » ou attendre la fin du délai ramène l'écran d'avant.
 */
class CameraOverlay(private val context: Context, private val onClosed: () -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var web: WebView? = null
    private val autoClose = Runnable { close() }

    val isShowing: Boolean
        get() = root != null

    @SuppressLint("SetJavaScriptEnabled")
    fun show(url: String, title: String, seconds: Int): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        close(notify = false)

        val frame = FrameLayout(context)
        frame.setBackgroundColor(Color.BLACK)

        val view = WebView(context)
        view.setBackgroundColor(Color.BLACK)
        view.settings.loadWithOverviewMode = true
        view.settings.useWideViewPort = true
        val safeUrl = Html.escapeHtml(url)
        val page = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;background:#000;height:100vh;display:flex;align-items:center;justify-content:center">
            <img src="$safeUrl" style="max-width:100%;max-height:100%;object-fit:contain">
            </body></html>
        """.trimIndent()
        val base = Prefs(context).absoluteUrl("/")
        view.loadDataWithBaseURL(base, page, "text/html", "utf-8", null)
        frame.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(context.dp(20), context.dp(12), context.dp(12), context.dp(12))
        val barBackground = GradientDrawable()
        barBackground.setColor(0xB3000000.toInt())
        bar.background = barBackground
        val label = TextView(context)
        label.text = title.ifBlank { tr(R.string.camera_default_title) }
        label.setTextColor(Color.WHITE)
        label.textSize = 22f
        label.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        bar.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val close = Button(context)
        close.text = tr(R.string.camera_close)
        close.isAllCaps = false
        close.textSize = 18f
        close.setOnClickListener { close() }
        bar.addView(close)
        frame.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val params = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.OPAQUE
        )
        return try {
            windowManager.addView(frame, params)
            root = frame
            web = view
            handler.removeCallbacks(autoClose)
            handler.postDelayed(autoClose, (if (seconds > 0) seconds else DEFAULT_SECONDS) * 1_000L)
            true
        } catch (e: Exception) {
            view.destroy()
            false
        }
    }

    fun close(notify: Boolean = true) {
        handler.removeCallbacks(autoClose)
        web?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            it.destroy()
        }
        web = null
        val was = root != null
        root?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // déjà retirée
            }
        }
        root = null
        if (was && notify) onClosed()
    }

    companion object {
        private const val DEFAULT_SECONDS = 60
    }
}
