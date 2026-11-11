package app.walldock

import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView

/**
 * Apprentissage d'une voix pour « Hey Jarvis » (réglages) :
 * 8 fois « Hey Jarvis », puis 20 secondes de sons habituels de la pièce,
 * puis calcul du filtre sur la tablette.
 */
class VoiceTraining(private val activity: SettingsActivity) {

    private val main = Handler(Looper.getMainLooper())
    private val jarvis = ApiService.instance?.jarvis
    private val text = TextView(activity)
    private var dialog: AlertDialog? = null
    private var cancelled = false

    private val positives = mutableListOf<FloatArray>()
    private val negatives = mutableListOf<FloatArray>()

    // Meilleur passage de l'essai en cours
    @Volatile private var bestScore = -1f
    @Volatile private var bestFeatures: FloatArray? = null
    private var attempt = 0
    private var retries = 0

    fun start() {
        val assistant = jarvis
        if (assistant == null || !assistant.detectsOnDevice || assistant.state != Jarvis.State.WAITING) {
            activity.toast(tr(R.string.voice_needs_tablet))
            return
        }
        text.setTextColor(Color.WHITE)
        text.textSize = 22f
        text.gravity = Gravity.CENTER
        text.setPadding(activity.dp(24), activity.dp(32), activity.dp(24), activity.dp(32))
        dialog = AlertDialog.Builder(activity)
            .setTitle(tr(R.string.voice_learn))
            .setView(text)
            .setNegativeButton(tr(R.string.cancel)) { _, _ -> stop() }
            .setCancelable(false)
            .show()
        nextAttempt()
    }

    private fun nextAttempt() {
        if (cancelled) return
        if (attempt >= ATTEMPTS) {
            recordRoom()
            return
        }
        text.text = tr(R.string.voice_get_ready, attempt + 1, ATTEMPTS)
        main.postDelayed({
            if (cancelled) return@postDelayed
            text.text = tr(R.string.voice_say_now, attempt + 1, ATTEMPTS)
            bestScore = -1f
            bestFeatures = null
            jarvis?.trainingSink = { score, features ->
                if (score > bestScore) {
                    bestScore = score
                    bestFeatures = features
                }
            }
            main.postDelayed({ endAttempt() }, LISTEN_MS)
        }, 1_200)
    }

    private fun endAttempt() {
        jarvis?.trainingSink = null
        if (cancelled) return
        val features = bestFeatures
        if (features == null || bestScore < MIN_SCORE) {
            retries++
            if (retries > 6) {
                finishWith(tr(R.string.voice_not_heard_stop))
                return
            }
            text.text = tr(R.string.voice_not_heard)
            main.postDelayed({ nextAttempt() }, 1_500)
            return
        }
        positives.add(features)
        attempt++
        nextAttempt()
    }

    private fun recordRoom() {
        text.text = tr(R.string.voice_room)
        var count = 0
        jarvis?.trainingSink = { _, features ->
            // Un exemple sur deux suffit (≈ toutes les 160 ms)
            if (count++ % 2 == 0) synchronized(negatives) { negatives.add(features) }
        }
        main.postDelayed({
            jarvis?.trainingSink = null
            if (cancelled) return@postDelayed
            text.text = tr(R.string.voice_computing)
            val pos = positives.toList()
            val neg = synchronized(negatives) { negatives.toList() }
            Thread({
                val ok = try {
                    VoiceVerifier.addVoice(activity, pos, neg)
                    true
                } catch (e: Exception) {
                    false
                }
                main.post {
                    jarvis?.reloadVoiceSettings()
                    finishWith(tr(if (ok) R.string.voice_done else R.string.voice_failed))
                    activity.updateJarvisStatus()
                }
            }, "voice-training").start()
        }, ROOM_MS)
    }

    private fun finishWith(message: String) {
        jarvis?.trainingSink = null
        dialog?.dismiss()
        if (!activity.isFinishing) {
            AlertDialog.Builder(activity)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun stop() {
        cancelled = true
        main.removeCallbacksAndMessages(null)
        jarvis?.trainingSink = null
    }

    companion object {
        private const val ATTEMPTS = 8
        private const val LISTEN_MS = 3_000L
        private const val ROOM_MS = 20_000L
        /** En dessous, on considère que rien n'a été dit. */
        private const val MIN_SCORE = 0.03f
    }
}
