package app.walldock

import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Lit les données via la session Home Assistant déjà ouverte dans la WebView.
 * Pas de token stocké, pas de requête réseau supplémentaire depuis Android.
 */
class SmartFlowHomeAssistantData(private val web: WebView) {
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ((String, String, String, String, String) -> Unit)? = null

    init {
        web.addJavascriptInterface(Bridge(), "WallDockSmartFlow")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onData(json: String) {
            handler.post {
                val result = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
                val error = result.optString("error")
                callback?.invoke(
                    if (error.isNotBlank()) error else result.optString("weather", "Météo indisponible"),
                    if (error.isNotBlank()) error else result.optString("calendar", "Calendrier indisponible"),
                    if (error.isNotBlank()) error else result.optString("traffic", "Trafic non configuré"),
                    if (error.isNotBlank()) error else result.optString("home", "Maison indisponible"),
                    if (error.isNotBlank()) error else result.optString("security", "Sécurité indisponible")
                )
                callback = null
            }
        }
    }

    fun refresh(trafficEntityId: String, onResult: (weather: String, calendar: String, traffic: String, home: String, security: String) -> Unit) {
        callback = onResult
        val sensorId = if (Regex("^sensor\\.[a-z0-9_]+$").matches(trafficEntityId)) trafficEntityId else ""
        val script = """
            (async function () {
              try {
                const element = document.querySelector('home-assistant');
                const hass = element && element.hass;
                if (!hass || !hass.states) { WallDockSmartFlow.onData(JSON.stringify({error: 'Home Assistant non connecté'})); return; }
                const states = hass.states;
                const weatherId = Object.keys(states).find(id => id.startsWith('weather.'));
                let weather = 'Aucune entité weather.* configurée';
                if (weatherId) {
                  const entity = states[weatherId];
                  const attrs = entity.attributes || {};
                  const temp = attrs.temperature == null ? '' : ' · ' + attrs.temperature + '°' + (attrs.temperature_unit || 'C');
                  weather = (attrs.friendly_name || 'Météo') + ' : ' + entity.state + temp;
                }
                let calendar = 'Aucun événement aujourd’hui';
                const ids = Object.keys(states).filter(id => id.startsWith('calendar.')).slice(0, 12);
                if (ids.length) {
                  const now = new Date();
                  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
                  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
                  const events = [];
                  for (const id of ids) {
                    try {
                      const path = 'calendars/' + id + '?start=' + encodeURIComponent(start.toISOString()) + '&end=' + encodeURIComponent(end.toISOString());
                      const list = await hass.callApi('GET', path);
                      for (const e of (Array.isArray(list) ? list : [])) {
                        const value = e.start && (e.start.dateTime || e.start.date || e.start);
                        const parsed = value ? new Date(value) : null;
                        events.push({title: e.summary || 'Événement', at: parsed && !isNaN(parsed.getTime()) ? parsed.getTime() : 0});
                      }
                    } catch (_) { /* calendrier non disponible : passer au suivant */ }
                  }
                  events.sort((a,b) => a.at - b.at);
                  if (events.length) calendar = events.slice(0, 3).map(e => e.title).join(' · ');
                }
                const trafficId = '__TRAFFIC_SENSOR__';
                let traffic = 'Capteur de trajet non configuré';
                if (trafficId) {
                  const travel = states[trafficId];
                  if (!travel || ['unknown', 'unavailable'].includes(travel.state)) {
                    traffic = 'Capteur de trajet indisponible';
                  } else {
                    const unit = (travel.attributes && travel.attributes.unit_of_measurement) || '';
                    traffic = travel.state + (unit ? ' ' + unit : '');
                  }
                }
                const lights = Object.keys(states).filter(id => id.startsWith('light.') && states[id].state === 'on').length;
                const switches = Object.keys(states).filter(id => id.startsWith('switch.') && states[id].state === 'on').length;
                const home = lights + ' lumière(s) allumée(s) · ' + switches + ' interrupteur(s) actif(s)';
                const alarms = Object.keys(states).filter(id => id.startsWith('alarm_control_panel.'));
                const security = alarms.length
                  ? alarms.slice(0, 2).map(id => (states[id].attributes.friendly_name || id) + ' : ' + states[id].state).join(' · ')
                  : 'Aucune alarme Home Assistant configurée';
                WallDockSmartFlow.onData(JSON.stringify({weather, calendar, traffic, home, security}));
              } catch (e) {
                WallDockSmartFlow.onData(JSON.stringify({error: 'Données Home Assistant indisponibles'}));
              }
            })();
        """.trimIndent()
        web.evaluateJavascript(script.replace("__TRAFFIC_SENSOR__", sensorId), null)
    }
}
