package app.walldock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Le « visage » de Jarvis : un anneau lumineux plein écran, par-dessus n'importe quelle appli,
 * avec la question entendue et la réponse. Un toucher l'interrompt.
 */
class JarvisOverlay(private val context: Context, private val onTouched: () -> Unit) {

    enum class Mode { LISTENING, THINKING, SPEAKING, ERROR }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private lateinit var orb: Orb
    private lateinit var status: TextView
    private lateinit var question: TextView
    private lateinit var answer: TextView
    private val hideRunnable = Runnable { hide() }

    val isShowing: Boolean
        get() = root != null

    /** Niveau sonore du micro (0–1), pour faire réagir l'anneau pendant l'écoute. */
    @Volatile
    var level = 0f

    fun show(mode: Mode) {
        handler.removeCallbacks(hideRunnable)
        if (root == null && !create()) return
        orb.mode = mode
        status.text = when (mode) {
            Mode.LISTENING -> "Je vous écoute…"
            Mode.THINKING -> "Je réfléchis…"
            Mode.SPEAKING -> ""
            Mode.ERROR -> status.text
        }
        if (mode == Mode.LISTENING) {
            question.text = ""
            answer.text = ""
        }
    }

    fun setQuestion(text: String) {
        if (root == null) return
        question.text = if (text.isBlank()) "" else "« ${text.trim()} »"
    }

    fun setAnswer(text: String) {
        if (root == null) return
        answer.text = text.trim()
    }

    fun showError(message: String) {
        show(Mode.ERROR)
        if (root == null) return
        status.text = message
    }

    fun hideAfter(delayMs: Long) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delayMs)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        val view = root ?: return
        root = null
        orb.running = false
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // déjà retirée
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        val frame = FrameLayout(context)
        frame.setBackgroundColor(0xEB02060C.toInt())
        frame.setOnClickListener { onTouched() }

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL

        val title = text("J.A.R.V.I.S.", 13f, 0xFF3FD4FF.toInt())
        title.letterSpacing = 0.5f
        title.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        column.addView(title)

        orb = Orb(context)
        orb.levelSource = { level }
        val size = context.dp(240)
        val orbParams = LinearLayout.LayoutParams(size, size)
        orbParams.topMargin = context.dp(16)
        orbParams.bottomMargin = context.dp(16)
        column.addView(orb, orbParams)

        status = text("", 15f, 0xFF7F9BC4.toInt())
        status.letterSpacing = 0.15f
        column.addView(status)

        question = text("", 18f, 0xFFB0C4E0.toInt())
        question.setTypeface(question.typeface, Typeface.ITALIC)
        question.setPadding(0, context.dp(16), 0, 0)
        column.addView(question)

        answer = text("", 22f, Color.WHITE)
        answer.setPadding(0, context.dp(12), 0, 0)
        answer.setShadowLayer(context.dp(10).toFloat(), 0f, 0f, 0x803FD4FF.toInt())
        column.addView(answer)

        val columnParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER)
        columnParams.leftMargin = context.dp(40)
        columnParams.rightMargin = context.dp(40)
        frame.addView(column, columnParams)

        val params = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            windowManager.addView(frame, params)
            root = frame
            orb.running = true
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun text(value: String, size: Float, color: Int): TextView {
        val view = TextView(context)
        view.text = value
        view.textSize = size
        view.setTextColor(color)
        view.gravity = Gravity.CENTER
        view.setLineSpacing(0f, 1.15f)
        return view
    }

    /** Anneau animé : arcs qui tournent, cœur qui respire, réagit à la voix. */
    private class Orb(context: Context) : View(context) {
        var mode = Mode.LISTENING
        var levelSource: () -> Float = { 0f }
        var running = false
            set(value) {
                field = value
                if (value) postInvalidateOnAnimation()
            }

        private val start = SystemClock.uptimeMillis()
        private var smoothLevel = 0f
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f
            val color = when (mode) {
                Mode.LISTENING -> 0xFF3FD4FF.toInt()
                Mode.THINKING -> 0xFFFFB347.toInt()
                Mode.SPEAKING -> 0xFF4DD9FF.toInt()
                Mode.ERROR -> 0xFFFF3B4E.toInt()
            }
            smoothLevel += (levelSource().coerceIn(0f, 1f) - smoothLevel) * 0.3f
            val energy = when (mode) {
                Mode.LISTENING -> 0.25f + smoothLevel * 0.75f
                Mode.THINKING -> 0.35f + 0.15f * sin(t * 6f)
                Mode.SPEAKING -> 0.45f + 0.35f * ((sin(t * 9f) + sin(t * 13.7f)) / 2f).let { it * it }
                Mode.ERROR -> 0.3f
            }

            // Halo et cœur lumineux
            val core = radius * (0.30f + 0.12f * energy)
            corePaint.shader = RadialGradient(
                cx, cy, max(core * 2.2f, 1f),
                intArrayOf(withAlpha(color, 230), withAlpha(color, 70), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, core * 2.2f, corePaint)

            // Anneaux fins
            ringPaint.color = withAlpha(color, 90)
            ringPaint.strokeWidth = radius * 0.01f
            canvas.drawCircle(cx, cy, radius * 0.62f, ringPaint)
            canvas.drawCircle(cx, cy, radius * 0.95f, ringPaint)

            // Arcs qui tournent (plus vite quand il réfléchit)
            val speed = when (mode) {
                Mode.THINKING -> 220f
                Mode.LISTENING -> 60f + 160f * smoothLevel
                Mode.SPEAKING -> 90f
                Mode.ERROR -> 15f
            }
            arcPaint.color = color
            for (i in 0 until 3) {
                val r = radius * (0.72f + i * 0.1f)
                rect.set(cx - r, cy - r, cx + r, cy + r)
                arcPaint.strokeWidth = radius * (0.035f - i * 0.008f)
                arcPaint.alpha = 255 - i * 60
                val direction = if (i % 2 == 0) 1f else -1f
                val angle = (t * speed * direction * (1f + i * 0.3f)) % 360f
                val sweep = 50f + 40f * energy + i * 10f
                for (k in 0 until 3) canvas.drawArc(rect, angle + k * 120f, sweep, false, arcPaint)
            }

            // Graduations façon réacteur
            arcPaint.strokeWidth = radius * 0.012f
            arcPaint.alpha = 140
            val ticks = 48
            for (i in 0 until ticks) {
                val a = (i * 2 * PI / ticks + t * 0.2).toFloat()
                val inner = radius * 0.97f
                val outer = radius * (0.99f + if (i % 4 == 0) 0.01f else 0f)
                canvas.drawLine(
                    cx + inner * kotlin.math.cos(a), cy + inner * sin(a),
                    cx + outer * kotlin.math.cos(a), cy + outer * sin(a), arcPaint
                )
            }
            if (running) postInvalidateOnAnimation()
        }

        private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
