package app.walldock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Alerte plein écran affichée par-dessus n'importe quelle appli (demandée par Home Assistant),
 * avec compte à rebours facultatif et bouton « OK, j'ai vu ».
 */
class AlertOverlay(private val context: Context, private val onDismissed: () -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var timerText: TextView? = null
    private var pulse: ValueAnimator? = null
    private var remaining = 0

    private val tick = object : Runnable {
        override fun run() {
            if (remaining <= 0) return
            remaining -= 1
            timerText?.text = formatDuration(remaining)
            handler.postDelayed(this, 1_000)
        }
    }

    val isShowing: Boolean
        get() = root != null

    /** Renvoie faux si Android n'autorise pas l'affichage par-dessus les autres applis. */
    fun show(title: String, message: String, seconds: Int): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        close(notify = false)

        val red = 0xFFFF3B4E.toInt()
        val frame = FrameLayout(context)
        frame.setBackgroundColor(0xF204070B.toInt())

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(context.dp(26), context.dp(36), context.dp(26), context.dp(28))
        val cardBackground = GradientDrawable()
        cardBackground.setColor(0xFF14050A.toInt())
        cardBackground.cornerRadius = context.dp(20).toFloat()
        cardBackground.setStroke(context.dp(2), red)
        card.background = cardBackground

        card.addView(text("⚠️", 44f, Color.WHITE))
        val titleView = text(title.uppercase(), 24f, red, bold = true)
        titleView.letterSpacing = 0.12f
        titleView.setPadding(0, context.dp(12), 0, context.dp(6))
        card.addView(titleView)
        if (message.isNotBlank()) card.addView(text(message, 15f, 0xFF93A9BB.toInt()))

        timerText = null
        if (seconds > 0) {
            remaining = seconds
            val timer = text(formatDuration(seconds), 56f, Color.WHITE, bold = true)
            timer.setPadding(0, context.dp(22), 0, 0)
            card.addView(timer)
            val label = text("TEMPS RESTANT", 11f, 0xFFFFB347.toInt())
            label.letterSpacing = 0.3f
            card.addView(label)
            timerText = timer
            handler.postDelayed(tick, 1_000)
        }

        val ok = Button(context)
        ok.text = "OK, j’ai vu"
        ok.isAllCaps = false
        ok.textSize = 16f
        ok.setOnClickListener { close(notify = true) }
        val okParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        okParams.topMargin = context.dp(28)
        card.addView(ok, okParams)

        frame.addView(card, FrameLayout.LayoutParams(context.dp(440), WRAP_CONTENT, Gravity.CENTER))

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            MATCH_PARENT,
            MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        return try {
            context.getSystemService(WindowManager::class.java)?.addView(frame, params)
            root = frame
            // Contour rouge qui pulse
            val animator = ValueAnimator.ofInt(0x50, 0xFF)
            animator.duration = 900
            animator.repeatMode = ValueAnimator.REVERSE
            animator.repeatCount = ValueAnimator.INFINITE
            animator.addUpdateListener {
                val alpha = it.animatedValue as Int
                cardBackground.setStroke(context.dp(2), Color.argb(alpha, 0xFF, 0x3B, 0x4E))
            }
            animator.start()
            pulse = animator
            true
        } catch (e: Exception) {
            root = null
            false
        }
    }

    /** Ferme l'alerte ; [notify] = prévenir que l'utilisateur l'a fermée lui-même. */
    fun close(notify: Boolean) {
        handler.removeCallbacks(tick)
        pulse?.cancel()
        pulse = null
        val view = root ?: return
        root = null
        try {
            context.getSystemService(WindowManager::class.java)?.removeView(view)
        } catch (e: Exception) {
            // déjà retirée
        }
        if (notify) onDismissed()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val view = TextView(context)
        view.text = value
        view.textSize = size
        view.setTextColor(color)
        view.gravity = Gravity.CENTER
        if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
        return view
    }

    private fun formatDuration(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
