package app.walldock

import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Lit les données via la session Home Assistant déjà ouverte dans la WebView.
 * Pas de token stocké, pas de requête réseau supplémentaire depuis Android.
 */
class SmartFlowHomeAssistantData(private val web: WebView) {
    fun refresh(onResult: (weather: String, calendar: String) -> Unit) {
        val script = """
            (async function () {
              try {
                const element = document.querySelector('home-assistant');
                const hass = element && element.hass;
                if (!hass || !hass.states) return JSON.stringify({error: 'Home Assistant non connecté'});
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
                return JSON.stringify({weather, calendar});
              } catch (e) {
                return JSON.stringify({error: 'Données Home Assistant indisponibles'});
              }
            })();
        """.trimIndent()
        web.evaluateJavascript(script) { raw ->
            val result = try {
                val unwrapped = JSONTokener(raw).nextValue()
                JSONObject(unwrapped as? String ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }
            val error = result.optString("error")
            if (error.isNotBlank()) onResult(error, error)
            else onResult(
                result.optString("weather", "Météo indisponible"),
                result.optString("calendar", "Calendrier indisponible")
            )
        }
    }
}
