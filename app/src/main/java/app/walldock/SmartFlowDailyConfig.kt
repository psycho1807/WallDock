package app.walldock

import android.content.Context
import java.time.LocalTime
import java.time.LocalDate

/**
 * SmartFlow Daily (v0.17.3): paramètres locaux du profil Morning Glass.
 * Désactivé par défaut tant que l'interface et les fournisseurs de données ne sont pas intégrés.
 * Aucun changement de WebView n'est effectué par cette classe.
 */
class SmartFlowDailyConfig(context: Context) {
    private val store = context.getSharedPreferences("walldock", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = store.getBoolean("smartflow_daily_enabled", false)
        set(value) = store.edit().putBoolean("smartflow_daily_enabled", value).apply()

    var startTime: String
        get() = store.getString("smartflow_morning_start", "06:00") ?: "06:00"
        set(value) {
            require(parseTime(value) != null) { "Expected HH:mm" }
            store.edit().putString("smartflow_morning_start", value).apply()
        }

    var endTime: String
        get() = store.getString("smartflow_morning_end", "10:00") ?: "10:00"
        set(value) {
            require(parseTime(value) != null) { "Expected HH:mm" }
            store.edit().putString("smartflow_morning_end", value).apply()
        }

    /** Jours actifs : 1 = lundi, 7 = dimanche. Tous les jours par défaut. */
    var activeDays: Set<Int>
        get() = (store.getString("smartflow_active_days", "1,2,3,4,5,6,7") ?: "")
            .split(",").mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 } }.toSet()
        set(value) {
            require(value.all { it in 1..7 })
            store.edit().putString("smartflow_active_days", value.sorted().joinToString(",")).apply()
        }

    enum class Profile { MORNING, DAY, EVENING, NIGHT }

    var dayEnabled: Boolean
        get() = store.getBoolean("smartflow_day_enabled", false)
        set(value) = store.edit().putBoolean("smartflow_day_enabled", value).apply()

    var dayStartTime: String
        get() = store.getString("smartflow_day_start", "10:00") ?: "10:00"
        set(value) {
            require(parseTime(value) != null)
            store.edit().putString("smartflow_day_start", value).apply()
        }

    var dayEndTime: String
        get() = store.getString("smartflow_day_end", "18:00") ?: "18:00"
        set(value) {
            require(parseTime(value) != null)
            store.edit().putString("smartflow_day_end", value).apply()
        }

    fun currentProfile(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Profile? {
        // En cas de chevauchement, priorité au matin, puis jour, soir et nuit.
        if (isMorningActive(now, today)) return Profile.MORNING
        if (isWithinPeriod(dayEnabled, dayStartTime, dayEndTime, now, today)) return Profile.DAY
        if (isWithinPeriod(eveningEnabled, eveningStartTime, eveningEndTime, now, today)) return Profile.EVENING
        if (isWithinPeriod(nightEnabled, nightStartTime, nightEndTime, now, today)) return Profile.NIGHT
        return null
    }

    var eveningEnabled: Boolean
        get() = store.getBoolean("smartflow_evening_enabled", false)
        set(value) = store.edit().putBoolean("smartflow_evening_enabled", value).apply()

    var eveningStartTime: String
        get() = store.getString("smartflow_evening_start", "18:00") ?: "18:00"
        set(value) {
            require(parseTime(value) != null)
            store.edit().putString("smartflow_evening_start", value).apply()
        }

    var eveningEndTime: String
        get() = store.getString("smartflow_evening_end", "22:00") ?: "22:00"
        set(value) {
            require(parseTime(value) != null)
            store.edit().putString("smartflow_evening_end", value).apply()
        }

    var nightEnabled: Boolean
        get() = store.getBoolean("smartflow_night_enabled", false)
        set(value) = store.edit().putBoolean("smartflow_night_enabled", value).apply()

    var nightStartTime: String
        get() = store.getString("smartflow_night_start", "22:00") ?: "22:00"
        set(value) {
            require(parseTime(value) != null)
            store.edit().putString("smartflow_night_start", value).apply()
        }

    var nightEndTime: String
        get() = store.getString("smartflow_night_end", "06:00") ?: "06:00"
        set(value) {
            require(parseTime(value) != null)
            store.edit().putString("smartflow_night_end", value).apply()
        }

    private fun isWithinPeriod(
        enabled: Boolean, startText: String, endText: String,
        now: LocalTime, today: LocalDate
    ): Boolean {
        if (!enabled) return false
        val start = parseTime(startText) ?: return false
        val end = parseTime(endText) ?: return false
        if (start == end) return false
        val referenceDay = if (start > end && now.isBefore(end)) today.minusDays(1) else today
        if (referenceDay.dayOfWeek.value !in activeDays) return false
        return if (start < end) !now.isBefore(start) && now.isBefore(end)
        else !now.isBefore(start) || now.isBefore(end)
    }

    var weatherEnabled: Boolean
        get() = store.getBoolean("smartflow_widget_weather", true)
        set(value) = store.edit().putBoolean("smartflow_widget_weather", value).apply()

    var calendarEnabled: Boolean
        get() = store.getBoolean("smartflow_widget_calendar", true)
        set(value) = store.edit().putBoolean("smartflow_widget_calendar", value).apply()

    var clockEnabled: Boolean
        get() = store.getBoolean("smartflow_widget_clock", true)
        set(value) = store.edit().putBoolean("smartflow_widget_clock", value).apply()

    var trafficEnabled: Boolean
        get() = store.getBoolean("smartflow_widget_traffic", true)
        set(value) = store.edit().putBoolean("smartflow_widget_traffic", value).apply()

    /** Capteur Home Assistant de durée du trajet, par ex. sensor.commute_time. */
    var trafficSensor: String
        get() = store.getString("smartflow_traffic_sensor", "") ?: ""
        set(value) = store.edit().putString("smartflow_traffic_sensor", value.trim()).apply()

    var trafficDestination: String
        get() = store.getString("smartflow_traffic_destination", "") ?: ""
        set(value) = store.edit().putString("smartflow_traffic_destination", value.trim()).apply()

    /** Fonctionne également si la période traverse minuit. */
    fun isMorningActive(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Boolean {
        if (!enabled) return false
        val start = parseTime(startTime) ?: return false
        val end = parseTime(endTime) ?: return false
        if (start == end) return false
        if (start < end) {
            return today.dayOfWeek.value in activeDays && !now.isBefore(start) && now.isBefore(end)
        }
        // Pour une plage nocturne, après minuit on utilise le jour de démarrage.
        val activeDay = if (now.isBefore(end)) today.minusDays(1) else today
        return activeDay.dayOfWeek.value in activeDays &&
            (!now.isBefore(start) || now.isBefore(end))
    }

    companion object {
        private fun parseTime(value: String): LocalTime? =
            try {
                if (!Regex("\\d{2}:\\d{2}").matches(value)) null
                else LocalTime.parse(value)
            } catch (_: Exception) {
                null
            }
    }
}
