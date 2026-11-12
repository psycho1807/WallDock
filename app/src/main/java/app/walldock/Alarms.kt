package app.walldock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Réveils de la tablette : ils sonnent sur la tablette elle-même, sans Home Assistant ni réseau.
 * Heure, jours de la semaine (aucun = une seule fois), nom, et option « lever de soleil »
 * (l'écran s'allume et s'éclaire peu à peu pendant les 10 minutes avant la sonnerie).
 *
 * Android réveille l'appli à l'heure (AlarmManager) ; le service vérifie aussi toutes les
 * 20 secondes, au cas où Android retarderait l'alarme. Un réveil ne sonne qu'une fois par minute prévue.
 */
object Alarms {

    class Alarm(
        val id: String,
        var hour: Int,
        var minute: Int,
        /** Jours : bit 0 = lundi … bit 6 = dimanche ; 0 = une seule fois. */
        var days: Int,
        var label: String,
        var enabled: Boolean,
        var sunrise: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("hour", hour).put("minute", minute).put("days", days)
            .put("label", label).put("enabled", enabled).put("sunrise", sunrise)

        val time: String get() = "%02d:%02d".format(hour, minute)

        companion object {
            fun fromJson(o: JSONObject) = Alarm(
                o.getString("id"), o.getInt("hour"), o.getInt("minute"), o.optInt("days"),
                o.optString("label"), o.optBoolean("enabled", true), o.optBoolean("sunrise")
            )
        }
    }

    const val SUNRISE_MINUTES = 10
    private const val ACTION_CHECK = "app.walldock.ALARM_CHECK"

    private fun sp(context: Context) = context.getSharedPreferences("walldock", Context.MODE_PRIVATE)

    fun list(context: Context): MutableList<Alarm> {
        val text = sp(context).getString("alarms", "[]") ?: "[]"
        return try {
            val array = JSONArray(text)
            MutableList(array.length()) { Alarm.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, alarms: List<Alarm>) {
        val array = JSONArray()
        alarms.forEach { array.put(it.toJson()) }
        sp(context).edit().putString("alarms", array.toString()).apply()
        schedule(context)
    }

    fun add(context: Context, hour: Int, minute: Int, days: Int, label: String, sunrise: Boolean): Alarm {
        val alarm = Alarm(java.util.UUID.randomUUID().toString().take(8), hour, minute, days, label, true, sunrise)
        val all = list(context)
        all.add(alarm)
        all.sortBy { it.hour * 60 + it.minute }
        save(context, all)
        return alarm
    }

    /** Prochaine sonnerie de ce réveil (millisecondes), ou null s'il est désactivé. */
    fun next(alarm: Alarm, from: Long = System.currentTimeMillis()): Long? {
        if (!alarm.enabled) return null
        val c = Calendar.getInstance()
        c.timeInMillis = from
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.set(Calendar.HOUR_OF_DAY, alarm.hour)
        c.set(Calendar.MINUTE, alarm.minute)
        for (i in 0..7) {
            if (c.timeInMillis > from) {
                if (alarm.days == 0) return c.timeInMillis
                // Calendar : dimanche = 1 … samedi = 7 → bit 0 = lundi … bit 6 = dimanche
                val bit = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
                if (alarm.days and (1 shl bit) != 0) return c.timeInMillis
            }
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    /** Prochain réveil, tous confondus : (réveil, heure). */
    fun upcoming(context: Context): Pair<Alarm, Long>? =
        list(context).mapNotNull { a -> next(a)?.let { a to it } }.minByOrNull { it.second }

    /** Programme le réveil d'Android pour la prochaine échéance (lever de soleil compris). */
    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.cancel(intent)
        val (alarm, at) = upcoming(context) ?: return
        val trigger = if (alarm.sunrise) at - SUNRISE_MINUTES * 60_000L else at
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (exact) {
                val show = PendingIntent.getActivity(
                    context, 1, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger.coerceAtLeast(System.currentTimeMillis() + 1_000), show), intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            }
        } catch (e: Exception) {
            // le contrôle toutes les 20 s du service prend le relais
        }
    }

    /**
     * Réveils à faire sonner maintenant (et lever de soleil à lancer), une seule fois par
     * échéance. Renvoie (réveils qui sonnent, réveil dont le lever de soleil commence).
     */
    fun due(context: Context): Pair<List<Alarm>, Alarm?> {
        val prefs = sp(context)
        val now = System.currentTimeMillis()
        val ringing = ArrayList<Alarm>()
        var sunrise: Alarm? = null
        val all = list(context)
        var changed = false
        for (alarm in all) {
            // Échéance de la minute en cours (ou de la précédente, si on a été réveillé en retard)
            val at = next(alarm, now - 2 * 60_000L) ?: continue
            val key = "alarm_fired_${alarm.id}"
            if (at <= now && prefs.getLong(key, 0L) != at) {
                prefs.edit().putLong(key, at).apply()
                ringing.add(alarm)
                if (alarm.days == 0) {
                    alarm.enabled = false
                    changed = true
                }
            }
            if (alarm.sunrise) {
                val upcoming = next(alarm, now) ?: continue
                val sunriseKey = "alarm_sunrise_${alarm.id}"
                if (upcoming - now <= SUNRISE_MINUTES * 60_000L && prefs.getLong(sunriseKey, 0L) != upcoming) {
                    prefs.edit().putLong(sunriseKey, upcoming).apply()
                    sunrise = alarm
                }
            }
        }
        if (changed) save(context, all) else if (ringing.isNotEmpty() || sunrise != null) schedule(context)
        return ringing to sunrise
    }

    /** Jours sous forme courte (« lun mar … », « tous les jours », « une fois »). */
    fun daysLabel(days: Int): String {
        if (days == 0) return tr(R.string.alarm_once)
        if (days == 0x7F) return tr(R.string.alarm_every_day)
        if (days == 0x1F) return tr(R.string.alarm_weekdays)
        val names = tr(R.string.alarm_day_names).split(",")
        return (0..6).filter { days and (1 shl it) != 0 }.joinToString(" ") { names.getOrElse(it) { "?" } }
    }

    /** Lit des jours écrits par HA : « lun,mar », « mon tue », « semaine », « tous », vide = une fois. */
    fun parseDays(text: String): Int {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t == "once" || t == "une fois") return 0
        if (t in listOf("tous", "all", "every day", "tous les jours", "daily")) return 0x7F
        if (t in listOf("semaine", "weekdays", "en semaine")) return 0x1F
        if (t in listOf("week-end", "weekend")) return 0x60
        val keys = listOf(listOf("lu", "mo"), listOf("ma", "tu"), listOf("me", "we"), listOf("je", "th"), listOf("ve", "fr"), listOf("sa"), listOf("di", "su"))
        var days = 0
        for (word in t.split(',', ' ', ';').filter { it.isNotBlank() }) {
            keys.forEachIndexed { i, k -> if (k.any { word.startsWith(it) }) days = days or (1 shl i) }
        }
        return days
    }
}

/** Réveil d'Android : on demande au service de vérifier les réveils à faire sonner. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = ApiService.instance
        if (service != null) {
            service.checkAlarms()
        } else {
            try {
                ApiService.start(context)
            } catch (e: Exception) {
                // le service vérifiera au démarrage
            }
        }
    }
}
