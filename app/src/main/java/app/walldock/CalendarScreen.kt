package app.walldock

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Calendriers de Home Assistant en plein écran : vues Jour, Semaine, Mois et Agenda, une
 * couleur par calendrier. Les événements sont lus par la session du tableau de bord (déjà
 * connectée à HA) : aucun jeton à saisir.
 */
class CalendarScreen(private val activity: Activity, private val container: FrameLayout, private val web: WebView) {

    enum class Mode { DAY, WEEK, MONTH, AGENDA }

    private class Event(val calendar: Int, val title: String, val start: Long, val end: Long, val allDay: Boolean, val location: String)

    private var root: FrameLayout? = null
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private val tabs = HashMap<Mode, TextView>()
    private var mode = Mode.WEEK
    /** Jour de référence (minuit). */
    private var anchor = startOfDay(System.currentTimeMillis())
    private var events: List<Event> = emptyList()
    private var calendars: List<String> = emptyList()
    private var loadedFrom = 0L
    private var loadedTo = 0L

    val isOpen: Boolean get() = root != null

    init {
        web.addJavascriptInterface(Bridge(), "WallDockCalendar")
    }

    fun open(startMode: Mode = Mode.WEEK) {
        if (root == null) build()
        mode = startMode
        anchor = startOfDay(System.currentTimeMillis())
        render()
    }

    fun close() {
        root?.let { container.removeView(it) }
        root = null
    }

    // ---- Chargement par la session HA du tableau de bord ----

    private inner class Bridge {
        @JavascriptInterface
        fun onEvents(json: String) {
            activity.runOnUiThread { receive(json) }
        }
    }

    private fun load(from: Long, to: Long) {
        loadedFrom = from
        loadedTo = to
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val js = """
            (async () => {
              try {
                const ha = document.querySelector('home-assistant');
                const hass = ha && ha.hass;
                if (!hass) { WallDockCalendar.onEvents(JSON.stringify({error: 'nohass'})); return; }
                const ids = Object.keys(hass.states).filter(k => k.startsWith('calendar.')).sort();
                const out = {calendars: [], events: []};
                for (const id of ids) {
                  out.calendars.push(hass.states[id].attributes.friendly_name || id);
                  try {
                    const list = await hass.callApi('GET', 'calendars/' + id + '?start=' + encodeURIComponent('${iso.format(Date(from))}') + '&end=' + encodeURIComponent('${iso.format(Date(to))}'));
                    for (const e of list) out.events.push({c: out.calendars.length - 1, s: e.summary || '', a: e.start, b: e.end, l: e.location || ''});
                  } catch (err) {}
                }
                WallDockCalendar.onEvents(JSON.stringify(out));
              } catch (err) { WallDockCalendar.onEvents(JSON.stringify({error: String(err)})); }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun receive(json: String) {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        if (o.has("error")) {
            activity.toast(tr(R.string.calendar_unavailable))
            return
        }
        val names = o.optJSONArray("calendars") ?: JSONArray()
        calendars = List(names.length()) { names.getString(it) }
        val list = o.optJSONArray("events") ?: JSONArray()
        events = (0 until list.length()).mapNotNull { i ->
            val e = list.getJSONObject(i)
            val (start, allDay) = parseTime(e.opt("a")) ?: return@mapNotNull null
            val end = parseTime(e.opt("b"))?.first ?: start
            Event(e.optInt("c"), e.optString("s"), start, end, allDay, e.optString("l"))
        }.sortedWith(compareBy({ !it.allDay }, { it.start }))
        if (root != null) render(reload = false)
    }

    /** Heure HA : {dateTime: "..."} ou {date: "2026-11-12"} (journée entière), ou chaîne. */
    private fun parseTime(value: Any?): Pair<Long, Boolean>? {
        val text: String
        val allDay: Boolean
        when (value) {
            is JSONObject -> {
                if (value.has("dateTime")) { text = value.getString("dateTime"); allDay = false }
                else if (value.has("date")) { text = value.getString("date"); allDay = true }
                else return null
            }
            is String -> { text = value; allDay = value.length == 10 }
            else -> return null
        }
        return try {
            val time = if (allDay) SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(text)!!.time
            else parseIso(text)
            time to allDay
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIso(text: String): Long {
        val clean = text.replace(Regex("\\.\\d+"), "")
        val formats = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss")
        for (f in formats) {
            try {
                return SimpleDateFormat(f, Locale.US).parse(clean)!!.time
            } catch (e: Exception) {
                // format suivant
            }
        }
        throw IllegalArgumentException(text)
    }

    // ---- Affichage ----

    private fun build() {
        val overlay = FrameLayout(activity)
        overlay.setBackgroundColor(0xF20A0F18.toInt())
        overlay.isClickable = true
        val column = LinearLayout(activity)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))

        // Barre : ◀ titre ▶ · onglets · Aujourd'hui · ✕
        val bar = LinearLayout(activity)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(barButton("◀") { shift(-1) })
        titleView = TextView(activity)
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 22f
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.setPadding(activity.dp(8), 0, activity.dp(8), 0)
        bar.addView(titleView)
        bar.addView(barButton("▶") { shift(1) })
        bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        for ((m, label) in listOf(Mode.DAY to R.string.calendar_day, Mode.WEEK to R.string.calendar_week, Mode.MONTH to R.string.calendar_month, Mode.AGENDA to R.string.calendar_agenda)) {
            val tab = barButton(tr(label)) { mode = m; render() }
            tabs[m] = tab
            bar.addView(tab)
        }
        bar.addView(barButton(tr(R.string.calendar_today)) { anchor = startOfDay(System.currentTimeMillis()); render() })
        bar.addView(barButton("✕") { close() })
        column.addView(bar)

        content = FrameLayout(activity)
        val contentParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        contentParams.topMargin = activity.dp(10)
        column.addView(content, contentParams)
        overlay.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        container.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root = overlay
    }

    private fun barButton(text: String, action: () -> Unit) = TextView(activity).also {
        it.text = text
        it.textSize = 17f
        it.setTextColor(Color.WHITE)
        it.setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
        it.setOnClickListener { action() }
    }

    private fun shift(direction: Int) {
        val c = Calendar.getInstance()
        c.timeInMillis = anchor
        when (mode) {
            Mode.DAY -> c.add(Calendar.DAY_OF_MONTH, direction)
            Mode.WEEK -> c.add(Calendar.DAY_OF_MONTH, 7 * direction)
            Mode.MONTH -> c.add(Calendar.MONTH, direction)
            Mode.AGENDA -> c.add(Calendar.DAY_OF_MONTH, 14 * direction)
        }
        anchor = startOfDay(c.timeInMillis)
        render()
    }

    /** Période affichée [début, fin). */
    private fun range(): Pair<Long, Long> {
        val c = Calendar.getInstance()
        c.timeInMillis = anchor
        return when (mode) {
            Mode.DAY -> anchor to anchor + DAY
            Mode.WEEK -> {
                c.firstDayOfWeek = Calendar.MONDAY
                c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val start = startOfDay(c.timeInMillis).let { if (it > anchor) startOfDay(it - 6 * DAY) else it }
                start to startOfDay(start + 7 * DAY + 3 * 3600_000L)
            }
            Mode.MONTH -> {
                c.set(Calendar.DAY_OF_MONTH, 1)
                val start = startOfDay(c.timeInMillis)
                c.add(Calendar.MONTH, 1)
                start to startOfDay(c.timeInMillis)
            }
            Mode.AGENDA -> anchor to anchor + 14 * DAY
        }
    }

    private fun render(reload: Boolean = true) {
        val (from, to) = range()
        if (reload && (from < loadedFrom || to > loadedTo || loadedFrom == 0L)) {
            // On charge un peu plus large pour naviguer sans attendre
            load(from - 7 * DAY, to + 35 * DAY)
        }
        tabs.forEach { (m, v) -> v.setTextColor(if (m == mode) ACCENT else Color.WHITE) }
        titleView.text = when (mode) {
            Mode.DAY -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(from))
            Mode.MONTH -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(from))
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(from)) + " – " +
                SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(to - 1))
        }
        content.removeAllViews()
        when (mode) {
            Mode.MONTH -> content.addView(monthGrid(from, to))
            Mode.WEEK -> content.addView(weekColumns(from))
            else -> content.addView(dayList(from, to))
        }
    }

    private fun eventsOn(day: Long): List<Event> =
        events.filter { it.start < day + DAY && (if (it.allDay) it.end > day else it.end > day || it.start >= day) }

    private fun eventChip(e: Event, compact: Boolean): TextView {
        val chip = TextView(activity)
        val time = if (e.allDay) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.start)) + "  "
        chip.text = if (compact) time + e.title else time + e.title + (if (e.location.isNotBlank()) "\n📍 " + e.location else "")
        chip.setTextColor(Color.WHITE)
        chip.textSize = if (compact) 13f else 16f
        chip.maxLines = if (compact) 2 else 4
        val shape = GradientDrawable()
        shape.setColor(color(e.calendar))
        shape.cornerRadius = activity.dp(6).toFloat()
        chip.background = shape
        chip.setPadding(activity.dp(6), activity.dp(3), activity.dp(6), activity.dp(3))
        return chip
    }

    /** Jour et Agenda : liste par jour. */
    private fun dayList(from: Long, to: Long): View {
        val scroll = ScrollView(activity)
        val list = LinearLayout(activity)
        list.orientation = LinearLayout.VERTICAL
        var day = from
        var any = false
        while (day < to) {
            val todays = eventsOn(day)
            if (todays.isNotEmpty() || mode == Mode.DAY) {
                list.addView(dayHeader(day))
                if (todays.isEmpty()) list.addView(muted(tr(R.string.calendar_empty)))
                for (e in todays) {
                    val chip = eventChip(e, compact = false)
                    val p = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    p.bottomMargin = activity.dp(6)
                    list.addView(chip, p)
                    any = true
                }
            }
            day = startOfDay(day + DAY + 3 * 3600_000L)
        }
        if (!any && mode == Mode.AGENDA) list.addView(muted(tr(R.string.calendar_empty)))
        list.addView(legend())
        scroll.addView(list)
        return scroll
    }

    /** Semaine : 7 colonnes. */
    private fun weekColumns(from: Long): View {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until 7) {
            val day = startOfDay(from + i * DAY + 3 * 3600_000L)
            val col = LinearLayout(activity)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(activity.dp(3), 0, activity.dp(3), 0)
            col.addView(dayHeader(day, short = true))
            for (e in eventsOn(day)) {
                val p = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                p.bottomMargin = activity.dp(4)
                col.addView(eventChip(e, compact = true), p)
            }
            val scroll = ScrollView(activity)
            scroll.addView(col)
            row.addView(scroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        return row
    }

    /** Mois : grille de 6 semaines, 3 événements au plus par case. */
    private fun monthGrid(from: Long, to: Long): View {
        val grid = GridLayout(activity)
        grid.columnCount = 7
        val c = Calendar.getInstance()
        c.timeInMillis = from
        val offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7  // lundi = 0
        val start = from - offset * DAY
        val cellWidth = (container.width.coerceAtLeast(700) - activity.dp(40)) / 7
        val cellHeight = (container.height.coerceAtLeast(500) - activity.dp(110)) / 6
        val today = startOfDay(System.currentTimeMillis())
        for (i in 0 until 42) {
            val day = startOfDay(start + i * DAY + 3 * 3600_000L)
            val cell = LinearLayout(activity)
            cell.orientation = LinearLayout.VERTICAL
            cell.setPadding(activity.dp(3), activity.dp(2), activity.dp(3), activity.dp(2))
            val bg = GradientDrawable()
            bg.setColor(if (day == today) 0x3300E5FF else 0x14FFFFFF)
            bg.cornerRadius = activity.dp(6).toFloat()
            cell.background = bg
            val number = TextView(activity)
            number.text = SimpleDateFormat("d", Locale.getDefault()).format(Date(day))
            number.setTextColor(if (day in from until to) Color.WHITE else 0x66FFFFFF)
            number.textSize = 13f
            cell.addView(number)
            val todays = eventsOn(day)
            for (e in todays.take(3)) {
                val chip = eventChip(e, compact = true)
                chip.maxLines = 1
                chip.textSize = 11f
                val p = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                p.topMargin = activity.dp(2)
                cell.addView(chip, p)
            }
            if (todays.size > 3) cell.addView(muted("+${todays.size - 3}"))
            cell.setOnClickListener { anchor = day; mode = Mode.DAY; render() }
            val p = GridLayout.LayoutParams()
            p.width = cellWidth - activity.dp(4)
            p.height = cellHeight - activity.dp(4)
            p.setMargins(activity.dp(2), activity.dp(2), activity.dp(2), activity.dp(2))
            grid.addView(cell, p)
        }
        return grid
    }

    private fun dayHeader(day: Long, short: Boolean = false): TextView {
        val t = TextView(activity)
        t.text = SimpleDateFormat(if (short) "EEE d" else "EEEE d MMMM", Locale.getDefault()).format(Date(day))
        t.setTextColor(if (day == startOfDay(System.currentTimeMillis())) ACCENT else 0xFFB0C4E0.toInt())
        t.textSize = if (short) 15f else 17f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, activity.dp(10), 0, activity.dp(6))
        return t
    }

    private fun muted(text: String) = TextView(activity).also {
        it.text = text
        it.setTextColor(0x99FFFFFF.toInt())
        it.textSize = 14f
    }

    private fun legend(): View {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, activity.dp(16), 0, 0)
        calendars.forEachIndexed { i, name ->
            val t = TextView(activity)
            t.text = "●  $name"
            t.setTextColor(color(i))
            t.textSize = 14f
            row.addView(t)
        }
        return row
    }

    private fun color(index: Int) = PALETTE[index % PALETTE.size]

    private fun startOfDay(time: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = time
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    companion object {
        private const val DAY = 24L * 3600 * 1000
        private const val ACCENT = 0xFF00E5FF.toInt()
        private val PALETTE = intArrayOf(
            0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt()
        )
    }
}
