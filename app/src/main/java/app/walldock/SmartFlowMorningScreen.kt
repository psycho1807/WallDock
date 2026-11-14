package app.walldock

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView

/**
 * Morning Glass: couche native superposée à Home Assistant.
 * La WebView principale reste montée et n'est jamais rechargée par SmartFlow.
 * Les fournisseurs météo/calendrier/trafic seront branchés dans une étape suivante.
 */
class SmartFlowMorningScreen(private val activity: Activity, parent: FrameLayout) {
    var onDismiss: (() -> Unit)? = null
    val view = FrameLayout(activity).apply {
        setBackgroundColor(0xFF071322.toInt())
        visibility = View.GONE
        isClickable = true
    }
    private val weather = tile("☀", "Météo", "En attente des données Home Assistant")
    private val calendar = tile("▦", "Calendrier", "En attente des événements du jour")
    private val traffic = tile("➜", "Trafic", "Destination à configurer")
    private val clock = TextClock(activity).apply {
        format24Hour = "HH:mm"
        format12Hour = "HH:mm"
        textSize = 48f
        setTextColor(Color.WHITE)
    }

    init {
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        content.addView(text("WALLDOCK  /  SMARTFLOW DAILY", 12f, 0xFF60D8F7.toInt()))
        content.addView(text("Bonjour", 34f, Color.WHITE))
        content.addView(text("Morning Glass", 18f, 0xFFB7CCE2.toInt()))
        content.addView(clock)
        content.addView(weather)
        content.addView(calendar)
        content.addView(traffic)
        val returnButton = text("✕  Retour à Home Assistant (pause 1 min)", 16f, 0xFF60D8F7.toInt())
        returnButton.setPadding(dp(12), dp(16), dp(12), dp(16))
        returnButton.setOnClickListener { onDismiss?.invoke() }
        content.addView(returnButton)
        scroll.addView(content)
        view.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        parent.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    fun applyConfig(config: SmartFlowDailyConfig) {
        weather.visibility = if (config.weatherEnabled) View.VISIBLE else View.GONE
        calendar.visibility = if (config.calendarEnabled) View.VISIBLE else View.GONE
        clock.visibility = if (config.clockEnabled) View.VISIBLE else View.GONE
        traffic.visibility = if (config.trafficEnabled) View.VISIBLE else View.GONE
        val destination = config.trafficDestination
        (traffic.getChildAt(1) as? TextView)?.text =
            if (destination.isBlank()) "Destination à configurer"
            else "Vers $destination · données de trafic non connectées"
    }

    fun updateTraffic(value: String, destination: String) {
        val prefix = if (destination.isBlank()) "" else "Vers $destination · "
        (traffic.getChildAt(1) as? TextView)?.text = prefix + value
    }

    fun updateWeather(value: String) {
        (weather.getChildAt(1) as? TextView)?.text = value
    }

    fun updateCalendar(value: String) {
        (calendar.getChildAt(1) as? TextView)?.text = value
    }

    private fun tile(icon: String, title: String, detail: String): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(0xFF172A40.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF294861.toInt())
            }
        }
        card.addView(text("$icon  $title", 21f, Color.WHITE))
        card.addView(text(detail, 15f, 0xFFB7CCE2.toInt()))
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        return card
    }

    private fun text(value: String, size: Float, color: Int): TextView =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.START
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
