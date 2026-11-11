package app.walldock

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * Minuteurs vocaux (« Hey Jarvis, mets un minuteur de 10 minutes ») :
 * un bandeau en haut de l'écran avec le compte à rebours, puis une sonnerie plein écran à la fin.
 * Tout se passe sur le fil principal.
 */
class TimerOverlay(private val context: Context, private val wakeUp: () -> Unit) {

    private class Timer(val name: String, var secondsLeft: Int, var active: Boolean, var stamp: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val timers = linkedMapOf<String, Timer>()

    private var bar: LinearLayout? = null
    private var ringView: View? = null
    private var ringText: TextView? = null
    private var player: MediaPlayer? = null
    private val ringing = mutableListOf<String>()

    /** Vrai pendant la sonnerie (affiché dans HA). */
    val isRinging: Boolean
        get() = ringView != null

    var onRingingChanged: ((Boolean) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            refreshBar()
            if (timers.values.any { it.active }) handler.postDelayed(this, 1_000)
        }
    }
    private val autoStop = Runnable { stopRinging() }

    // Événements de HA (VoiceAssistantTimerEvent)
    fun onEvent(type: Int, id: String, name: String, totalSeconds: Int, secondsLeft: Int, active: Boolean) {
        when (type) {
            EVENT_STARTED, EVENT_UPDATED -> {
                timers[id] = Timer(name.ifBlank { formatName(totalSeconds) }, secondsLeft, active, SystemClock.elapsedRealtime())
                refreshBar()
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 1_000)
            }
            EVENT_CANCELLED -> {
                timers.remove(id)
                refreshBar()
            }
            EVENT_FINISHED -> {
                val timer = timers.remove(id)
                refreshBar()
                ring(timer?.name ?: name.ifBlank { formatName(totalSeconds) })
            }
        }
    }

    /** Arrête la sonnerie (toucher l'écran, bouton HA ou nouvelle commande vocale). */
    fun stopRinging() {
        handler.removeCallbacks(autoStop)
        ringing.clear()
        player?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // déjà arrêté
            }
            it.release()
        }
        player = null
        ringView?.let { remove(it) }
        val was = ringView != null
        ringView = null
        ringText = null
        if (was) onRingingChanged?.invoke(false)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        stopRinging()
        timers.clear()
        bar?.let { remove(it) }
        bar = null
    }

    // ---- Bandeau des minuteurs en cours ----

    private fun refreshBar() {
        if (timers.isEmpty()) {
            bar?.let { remove(it) }
            bar = null
            return
        }
        val view = bar ?: createBar() ?: return
        view.removeAllViews()
        val now = SystemClock.elapsedRealtime()
        for (timer in timers.values) {
            val left = if (timer.active) {
                (timer.secondsLeft - ((now - timer.stamp) / 1000).toInt()).coerceAtLeast(0)
            } else {
                timer.secondsLeft
            }
            val chip = TextView(context)
            chip.text = "⏱  ${timer.name}   ${formatTime(left)}" + if (timer.active) "" else "  (pause)"
            chip.setTextColor(Color.WHITE)
            chip.textSize = 18f
            chip.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            chip.setPadding(context.dp(18), context.dp(8), context.dp(18), context.dp(8))
            val background = GradientDrawable()
            background.setColor(0xE60A1634.toInt())
            background.cornerRadius = context.dp(20).toFloat()
            background.setStroke(context.dp(1), 0xCC3FD4FF.toInt())
            chip.background = background
            val params = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            params.leftMargin = context.dp(6)
            params.rightMargin = context.dp(6)
            view.addView(chip, params)
        }
    }

    private fun createBar(): LinearLayout? {
        if (!Settings.canDrawOverlays(context)) return null
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val params = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = context.dp(14)
        return try {
            windowManager.addView(row, params)
            bar = row
            row
        } catch (e: Exception) {
            null
        }
    }

    // ---- Sonnerie de fin ----

    private fun ring(name: String) {
        wakeUp()
        ringing.add(name)
        if (ringView == null) showRingView()
        ringText?.text = ringing.joinToString("\n")
        if (player == null) startSound()
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 2 * 60_000L)
    }

    private fun showRingView() {
        if (!Settings.canDrawOverlays(context)) return
        val frame = FrameLayout(context)
        frame.setBackgroundColor(0xEB02060C.toInt())
        frame.setOnClickListener { stopRinging() }
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        val icon = TextView(context)
        icon.text = "⏰"
        icon.textSize = 72f
        icon.gravity = Gravity.CENTER
        column.addView(icon)
        val title = TextView(context)
        title.text = "MINUTEUR TERMINÉ"
        title.textSize = 26f
        title.letterSpacing = 0.2f
        title.setTextColor(0xFFFFB347.toInt())
        title.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.setPadding(0, context.dp(12), 0, context.dp(8))
        column.addView(title)
        val names = TextView(context)
        names.textSize = 22f
        names.setTextColor(Color.WHITE)
        names.gravity = Gravity.CENTER
        column.addView(names)
        val stop = Button(context)
        stop.text = "Arrêter"
        stop.isAllCaps = false
        stop.textSize = 20f
        stop.setOnClickListener { stopRinging() }
        val stopParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        stopParams.topMargin = context.dp(32)
        column.addView(stop, stopParams)
        frame.addView(column, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        val params = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(frame, params)
            ringView = frame
            ringText = names
            onRingingChanged?.invoke(true)
        } catch (e: Exception) {
            // pas d'affichage possible : la sonnerie suffit
        }
    }

    private fun startSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        val media = MediaPlayer()
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            media.setDataSource(context, uri)
            media.isLooping = true
            media.setOnPreparedListener { it.start() }
            media.prepareAsync()
            player = media
        } catch (e: Exception) {
            media.release()
        }
    }

    private fun remove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // déjà retirée
        }
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun formatName(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        return when {
            totalSeconds < 60 -> "$totalSeconds s"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60}"
        }
    }

    companion object {
        const val EVENT_STARTED = 0
        const val EVENT_UPDATED = 1
        const val EVENT_CANCELLED = 2
        const val EVENT_FINISHED = 3
    }
}
