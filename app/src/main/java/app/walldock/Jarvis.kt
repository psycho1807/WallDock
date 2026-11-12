package app.walldock

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import app.walldock.esphome.EsphomeServer
import kotlin.math.sqrt

/**
 * Jarvis : la tablette devient un satellite vocal de Home Assistant.
 * Le micro est envoyé à HA (16 kHz, 16 bits, mono) ; HA détecte « Hey Jarvis » (openWakeWord),
 * reconnaît la phrase, répond, et la tablette lit la réponse.
 */
class Jarvis(
    private val context: Context,
    private val server: EsphomeServer,
    private val wakeUp: () -> Unit
) : EsphomeServer.VoiceHandler {

    enum class State { OFF, WAITING, LISTENING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private val overlay = JarvisOverlay(context) { main.post { cancel() } }

    /** Minuteurs vocaux (bandeau + sonnerie). */
    val timers = TimerOverlay(context) { wakeUp() }

    /** Détection du mot « Stop » (pendant que Jarvis parle ou qu'un minuteur sonne). */
    @Volatile
    private var stopDetector: app.walldock.wakeword.StopWordDetector? = null
    private var stopListening = false

    /** Voix apprises (null : modèle seul) et seuils selon la sensibilité choisie. */
    @Volatile
    private var verifier: VoiceVerifier? = null
    @Volatile
    private var wakeThreshold = 0.5f
    @Volatile
    private var wakePatience = 2
    /** Nombre de prédictions consécutives au-dessus du seuil (80 ms chacune). */
    private var wakeStreak = 0

    /** Reçoit (score, empreintes) pendant l'apprentissage d'une voix ; rien n'est déclenché. */
    @Volatile
    var trainingSink: ((Float, FloatArray) -> Unit)? = null

    /** Relit la sensibilité et les voix apprises (réglages modifiés ou nouvel apprentissage). */
    fun reloadVoiceSettings() {
        val sensitivity = Prefs(context).jarvisSensitivity
        val level = sensitivity.coerceIn(0, WAKE_THRESHOLDS.size - 1)
        wakeThreshold = WAKE_THRESHOLDS[level]
        wakePatience = WAKE_PATIENCE[level]
        verifier = VoiceVerifier.load(context)
    }

    /** Nombre de voix apprises. */
    val learnedVoices: Int
        get() = verifier?.voices ?: 0

    /** Dernier score du mot « Stop » (0–1), pour le diagnostic. */
    val stopScore: Float
        get() = stopDetector?.lastScore ?: 0f

    init {
        reloadVoiceSettings()
        Thread({ stopDetector = app.walldock.wakeword.StopWordDetector.load(context) }, "jarvis-stop").start()
    }

    /** « Stop » entendu (fil principal) : on coupe la sonnerie et/ou la voix de Jarvis. */
    private fun onStopWord() {
        if (timers.isSoundPlaying) timers.stopRinging()
        if (state == State.SPEAKING) cancel()
    }

    /** Détecteur « Hey Jarvis » sur la tablette (null = détection par HA). */
    @Volatile
    private var detector: WakeWordDetector? = null
    @Volatile
    private var resetDetector = false
    @Volatile
    private var lastDetection = 0L
    @Volatile
    private var loadingDetector = false

    /** Où est reconnu « Hey Jarvis » (affiché dans HA). */
    val detectionStatus: String
        get() = when {
            !onDeviceEnabled -> tr(R.string.detect_ha_chosen)
            detector != null && learnedVoices > 0 -> tr(R.string.detect_tablet_voices, learnedVoices)
            detector != null -> tr(R.string.detect_tablet)
            loadingDetector -> tr(R.string.detect_preparing)
            else -> tr(R.string.detect_ha_reason, WakeWordDetector.lastError ?: tr(R.string.models_missing))
        }

    /** Meilleur score « Hey Jarvis » depuis la dernière lecture (diagnostic). */
    @Volatile
    var wakeScorePeak = 0f

    /** Réglage « détection sur la tablette », relu à chaque mise en veille. */
    @Volatile
    private var onDeviceEnabled = Prefs(context).jarvisOnDevice

    /** Vrai quand « Hey Jarvis » est reconnu par la tablette. */
    val detectsOnDevice: Boolean
        get() = onDeviceEnabled && detector != null

    @Volatile
    var state = State.OFF
        private set

    /** Vrai pendant que le son du micro part vers HA. */
    @Volatile
    private var streaming = false

    @Volatile
    private var recording = false

    /** Niveau sonore actuel du micro (0–1) et nombre de blocs envoyés à HA, pour le diagnostic. */
    @Volatile
    var micLevel = 0f
        private set
    @Volatile
    var chunksSent = 0L
        private set
    val isRecording: Boolean
        get() = recording
    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null

    private var player: MediaPlayer? = null
    private var conversationId = ""
    private var continueConversation = false
    // Tour de parole enchaîné sans « Hey Jarvis » : un silence n'est pas une erreur
    private var followUpTurn = false
    // Micro coupé (silence envoyé) jusqu'à cette heure : évite d'envoyer le bip à HA
    @Volatile
    private var muteUntil = 0L
    private var runEnded = false
    private var speechPending = false
    private var errorBackoffMs = 1_000L
    /** Une relance après erreur est déjà programmée : la fin du pipeline ne doit pas relancer tout de suite. */
    private var restartScheduled = false
    private val restartRunnable = Runnable { restartWaiting() }

    /** Un pipeline tourne côté HA (de la demande de démarrage jusqu'à RUN_END). */
    private var runActive = false
    /** Action à lancer une fois le pipeline en cours vraiment terminé (événements périmés ignorés d'ici là). */
    private var afterStop: (() -> Unit)? = null
    private val afterStopTimeout = Runnable { runAfterStop() }

    /** Prévenu (fil principal) quand l'état change, pour l'afficher dans HA. */
    var onStateChanged: ((State) -> Unit)? = null

    val micGranted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val wakeWordWanted: Boolean
        get() = Prefs(context).jarvisWakeWord && micGranted

    // ---- Commandes ----

    /** Parler à Jarvis sans dire « Hey Jarvis » (bouton du dock ou de HA). */
    fun talk() = main.post {
        if (!server.isVoiceSubscribed) {
            context.toast(tr(R.string.jarvis_not_connected))
            return@post
        }
        if (!micGranted) {
            context.toast(tr(R.string.jarvis_allow_mic))
            return@post
        }
        timers.stopRinging()
        stopPlayback()
        stopThen { startConversationTurn(conversationId) }
    }

    /** Toucher l'écran de Jarvis : on arrête tout et on revient en veille. */
    fun cancel() {
        stopPlayback()
        continueConversation = false
        conversationId = ""
        overlay.hide()
        stopThen { restartWaiting() }
    }

    /** Réglage ou autorisation du micro modifiés. */
    fun onSettingsChanged() = main.post {
        if (state == State.WAITING || state == State.OFF) stopThen { restartWaiting() }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        timers.release()
        afterStop = null
        runActive = false
        streaming = false
        stopMic()
        stopPlayback()
        overlay.hide()
        setState(State.OFF)
        detector?.close()
        detector = null
        stopDetector?.close()
        stopDetector = null
    }

    // ---- Événements de Home Assistant (fil réseau → fil principal) ----

    override fun onVoiceSubscribed(subscribed: Boolean) {
        main.post {
            if (subscribed) {
                errorBackoffMs = 1_000L
                main.removeCallbacks(restartRunnable)
                main.postDelayed(restartRunnable, 1_000)
            } else {
                main.removeCallbacks(restartRunnable)
                main.removeCallbacks(afterStopTimeout)
                afterStop = null
                runActive = false
                streaming = false
                stopMic()
                stopPlayback()
                overlay.hide()
                setState(State.OFF)
            }
        }
    }

    override fun onVoiceResponse(error: Boolean) {
        if (!error) return
        main.post {
            streaming = false
            if (state != State.WAITING) overlay.showError(tr(R.string.jarvis_unavailable))
            scheduleRestartAfterError()
        }
    }

    override fun onTimerEvent(eventType: Int, timerId: String, name: String, totalSeconds: Int, secondsLeft: Int, active: Boolean) {
        main.post { timers.onEvent(eventType, timerId, name, totalSeconds, secondsLeft, active) }
    }

    override fun onVoiceStopRequested() {
        streaming = false
    }

    override fun onVoiceEvent(eventType: Int, data: Map<String, String>) {
        main.post { handleEvent(eventType, data) }
    }

    override fun onAnnounce(mediaId: String, text: String, preannounceMediaId: String, startConversation: Boolean) {
        main.post { stopThen { announce(mediaId, text, preannounceMediaId, startConversation) } }
    }

    private fun announce(mediaId: String, text: String, preannounceMediaId: String, startConversation: Boolean) {
        run {
            stopPlayback()
            wakeUp()
            setState(State.SPEAKING)
            overlay.show(JarvisOverlay.Mode.SPEAKING)
            overlay.setQuestion("")
            overlay.setAnswer(text)
            val afterAnnounce = {
                server.voiceAnnounceFinished(true)
                if (startConversation) {
                    startConversationTurn("", followUp = true)
                } else {
                    overlay.hideAfter(1_500)
                    restartWaiting()
                }
            }
            val playMain = { play(mediaId, afterAnnounce) }
            if (preannounceMediaId.isNotBlank()) play(preannounceMediaId, playMain) else playMain()
        }
    }

    /**
     * Arrête proprement le pipeline en cours puis lance [action].
     * HA renvoie encore des événements de l'ancien pipeline (erreur, RUN_END) : on les ignore
     * jusqu'à son RUN_END, sinon ils interrompraient le nouveau.
     */
    private fun stopThen(action: () -> Unit) {
        main.removeCallbacks(restartRunnable)
        restartScheduled = false
        if (!runActive) {
            action()
            return
        }
        streaming = false
        afterStop = action
        server.voiceStop()
        main.removeCallbacks(afterStopTimeout)
        main.postDelayed(afterStopTimeout, 2_000)
    }

    private fun runAfterStop() {
        main.removeCallbacks(afterStopTimeout)
        runActive = false
        val action = afterStop ?: return
        afterStop = null
        action()
    }

    private fun handleEvent(type: Int, data: Map<String, String>) {
        if (afterStop != null) {
            if (type == EsphomeServer.VOICE_EVENT_RUN_END) runAfterStop()
            return
        }
        when (type) {
            EsphomeServer.VOICE_EVENT_WAKE_WORD_END -> {
                // « Hey Jarvis » entendu : HA enchaîne sur la reconnaissance de la phrase
                wakeUp()
                beep()
                setState(State.LISTENING)
                overlay.show(JarvisOverlay.Mode.LISTENING)
            }
            EsphomeServer.VOICE_EVENT_STT_VAD_END -> {
                streaming = false
                setState(State.THINKING)
                overlay.show(JarvisOverlay.Mode.THINKING)
            }
            EsphomeServer.VOICE_EVENT_STT_END -> {
                streaming = false
                setState(State.THINKING)
                overlay.show(JarvisOverlay.Mode.THINKING)
                overlay.setQuestion(data["text"] ?: "")
            }
            EsphomeServer.VOICE_EVENT_INTENT_END -> {
                conversationId = data["conversation_id"] ?: conversationId
                continueConversation = data["continue_conversation"] == "1"
            }
            EsphomeServer.VOICE_EVENT_TTS_START -> overlay.setAnswer(data["text"] ?: "")
            EsphomeServer.VOICE_EVENT_TTS_END -> {
                val url = data["url"] ?: ""
                if (url.isNotBlank()) {
                    setState(State.SPEAKING)
                    overlay.show(JarvisOverlay.Mode.SPEAKING)
                    play(url) {
                        speechPending = false
                        if (runEnded) finishRun()
                    }
                    // Après play() : il remet speechPending à faux en coupant le son précédent
                    speechPending = true
                }
            }
            EsphomeServer.VOICE_EVENT_RUN_END -> {
                streaming = false
                runActive = false
                runEnded = true
                if (!speechPending) finishRun()
            }
            EsphomeServer.VOICE_EVENT_ERROR -> {
                streaming = false
                val code = data["code"] ?: ""
                if (state == State.WAITING) {
                    // Pas de mot d'activation, moteur indisponible… : on relance l'écoute
                    if (code != "no_wake_word") scheduleRestartAfterError()
                } else if (code == "duplicate_wake_up_detected" ||
                    (followUpTurn && (code == "stt-no-text-recognized" || code == "stt-timeout"))
                ) {
                    // Une autre tablette a déjà répondu au même « Hey Jarvis »,
                    // ou personne n'a rien dit pour continuer la conversation : on se tait
                    continueConversation = false
                    overlay.hide()
                } else {
                    val message = when (code) {
                        "stt-no-text-recognized", "stt-stream-failed" -> tr(R.string.jarvis_not_understood)
                        "stt-timeout" -> tr(R.string.jarvis_heard_nothing)
                        else -> data["message"]?.takeIf { it.isNotBlank() } ?: tr(R.string.jarvis_error)
                    }
                    overlay.showError(message)
                    continueConversation = false
                }
            }
        }
    }

    // ---- Déroulé d'une conversation ----

    /** Fin d'un échange : on continue la conversation si HA le demande, sinon retour en veille. */
    private fun finishRun() {
        runEnded = false
        if (restartScheduled) return
        if (continueConversation && state != State.OFF) {
            continueConversation = false
            startConversationTurn(conversationId, followUp = true)
            return
        }
        conversationId = ""
        if (overlay.isShowing) overlay.hideAfter(if (state == State.SPEAKING) 1_200 else 2_500)
        restartWaiting()
    }

    /** Lance un tour de parole sans mot d'activation (écoute immédiate). */
    private fun startConversationTurn(conversation: String, followUp: Boolean = false) {
        if (!ensureMic()) return
        wakeUp()
        beep()
        setState(State.LISTENING)
        overlay.show(JarvisOverlay.Mode.LISTENING)
        startRun(conversation, EsphomeServer.VOICE_FLAG_USE_VAD, followUp = followUp)
    }

    /** Revient à l'écoute de « Hey Jarvis » (ou coupe le micro si l'option est désactivée). */
    private fun restartWaiting() {
        main.removeCallbacks(restartRunnable)
        restartScheduled = false
        if (!server.isVoiceSubscribed) {
            setState(State.OFF)
            return
        }
        if (!wakeWordWanted) {
            streaming = false
            stopMic()
            setState(State.OFF)
            return
        }
        if (!ensureMic()) {
            setState(State.OFF)
            return
        }
        onDeviceEnabled = Prefs(context).jarvisOnDevice
        prepareDetector()
        setState(State.WAITING)
        if (detectsOnDevice) {
            // « Hey Jarvis » écouté par la tablette : rien n'est envoyé à HA tant qu'il n'est pas dit
            streaming = false
            resetDetector = true
        } else {
            startRun("", EsphomeServer.VOICE_FLAG_USE_WAKE_WORD or EsphomeServer.VOICE_FLAG_USE_VAD)
        }
    }

    /** Charge (et télécharge la première fois) les modèles « Hey Jarvis » en arrière-plan. */
    private fun prepareDetector() {
        if (!onDeviceEnabled || detector != null || loadingDetector) return
        loadingDetector = true
        Thread({
            val ok = WakeWordDetector.modelsPresent(context) || WakeWordDetector.downloadModels(context)
            val loaded = if (ok) WakeWordDetector.load(context) else null
            main.post {
                loadingDetector = false
                if (loaded != null) {
                    detector = loaded
                    // Bascule vers la détection locale si on attendait « Hey Jarvis » via HA
                    if (state == State.WAITING) stopThen { restartWaiting() }
                }
            }
        }, "jarvis-models").start()
    }

    /** « Hey Jarvis » reconnu par la tablette (fil principal). */
    private fun onLocalWakeWord() {
        if (state != State.WAITING || !server.isVoiceSubscribed) return
        wakeUp()
        beep()
        setState(State.LISTENING)
        overlay.show(JarvisOverlay.Mode.LISTENING)
        startRun("", EsphomeServer.VOICE_FLAG_USE_VAD, "Hey Jarvis")
    }

    private fun startRun(conversation: String, flags: Int, wakeWordPhrase: String = "", followUp: Boolean = false) {
        followUpTurn = followUp
        muteUntil = android.os.SystemClock.elapsedRealtime() + BEEP_MUTE_MS
        runActive = true
        runEnded = false
        speechPending = false
        continueConversation = false
        streaming = true
        server.voiceStart(conversation, flags, wakeWordPhrase)
    }

    private fun scheduleRestartAfterError() {
        restartScheduled = true
        main.removeCallbacks(restartRunnable)
        main.postDelayed(restartRunnable, errorBackoffMs)
        errorBackoffMs = (errorBackoffMs * 2).coerceAtMost(60_000L)
    }

    private fun setState(newState: State) {
        if (newState == State.LISTENING || newState == State.SPEAKING) errorBackoffMs = 1_000L
        if (state == newState) return
        state = newState
        onStateChanged?.invoke(newState)
    }

    // ---- Micro ----

    @SuppressLint("MissingPermission")
    private fun ensureMic(): Boolean {
        if (recording) return true
        if (!micGranted) return false
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minSize, CHUNK_BYTES * 4)
            )
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return false
        }
        enableAudioEffects(record.audioSessionId)
        recorder = record
        recording = true
        val thread = Thread({ recordLoop(record) }, "jarvis-mic")
        thread.priority = Thread.MAX_PRIORITY
        recordThread = thread
        thread.start()
        return true
    }

    private fun recordLoop(record: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (recording) {
            val n = record.read(buffer, 0, buffer.size)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            val level = rms(buffer, n)
            micLevel = level
            if (overlay.isShowing) overlay.level = level
            if (streaming) chunksSent++
            val wake = detector
            if (wake != null && state == State.WAITING && !streaming && onDeviceEnabled) {
                if (resetDetector) {
                    resetDetector = false
                    wake.reset()
                }
                val score = try {
                    wake.process(buffer, n)
                } catch (e: Exception) {
                    -1f
                }
                if (score > wakeScorePeak) wakeScorePeak = score
                val sink = trainingSink
                if (sink != null) {
                    // Apprentissage d'une voix en cours : on transmet, on ne déclenche rien
                    if (score >= 0f) sink(score, wake.lastFeatures())
                } else if (score >= 0f) {
                    // Le mot doit être entendu sur plusieurs prédictions d'affilée (un bruit bref,
                    // une syllabe de la télé ne suffisent plus), puis, si des voix ont été apprises,
                    // ressembler à l'une d'elles. Les voix apprises ne baissent jamais le seuil.
                    wakeStreak = if (score >= wakeThreshold) wakeStreak + 1 else 0
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (wakeStreak >= wakePatience && now - lastDetection > 2_000) {
                        val voice = verifier
                        if (voice == null || voice.predict(wake.lastFeatures()) >= VERIFIER_CUTOFF) {
                            lastDetection = now
                            wakeStreak = 0
                            main.post { onLocalWakeWord() }
                        }
                    }
                }
            }
            // Traitements du son (bruit, gain, écho) seulement pour la phrase envoyée à HA :
            // les modèles « Hey Jarvis » et « Stop » ont été entraînés sur du son brut
            setAudioEffectsEnabled(streaming && state != State.WAITING)
            val stop = stopDetector
            if (stop != null && (state == State.SPEAKING || timers.isSoundPlaying)) {
                if (!stopListening) {
                    stopListening = true
                    stop.reset()
                }
                val heard = try {
                    stop.process(buffer, n)
                } catch (e: Exception) {
                    false
                }
                if (heard) main.post { onStopWord() }
            } else {
                stopListening = false
            }
            if (streaming) {
                if (android.os.SystemClock.elapsedRealtime() < muteUntil) java.util.Arrays.fill(buffer, 0, n, 0)
                server.voiceAudio(buffer, n)
            }
        }
    }

    // Home Assistant préfère recevoir un son déjà nettoyé (bruit réduit, volume régulé) :
    // on active les traitements de la tablette quand elle en a.
    private val audioEffects = mutableListOf<android.media.audiofx.AudioEffect>()

    private fun enableAudioEffects(session: Int) {
        releaseAudioEffects()
        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor.create(session)?.let { it.enabled = false; audioEffects.add(it) }
            }
        } catch (e: Exception) {
            // pas de réduction de bruit sur cette tablette
        }
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                android.media.audiofx.AutomaticGainControl.create(session)?.let { it.enabled = false; audioEffects.add(it) }
            }
        } catch (e: Exception) {
            // pas de réglage automatique du volume
        }
        try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler.create(session)?.let { it.enabled = false; audioEffects.add(it) }
            }
        } catch (e: Exception) {
            // pas d'annulation d'écho
        }
    }

    @Volatile
    private var effectsEnabled = false

    private fun setAudioEffectsEnabled(enabled: Boolean) {
        if (enabled == effectsEnabled) return
        effectsEnabled = enabled
        for (effect in audioEffects) {
            try {
                effect.enabled = enabled
            } catch (e: Exception) {
                // effet indisponible
            }
        }
    }

    private fun releaseAudioEffects() {
        for (effect in audioEffects) {
            try {
                effect.release()
            } catch (e: Exception) {
                // déjà libéré
            }
        }
        audioEffects.clear()
        effectsEnabled = false
    }

    private fun stopMic() {
        releaseAudioEffects()
        recording = false
        recordThread?.join(500)
        recordThread = null
        recorder?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // déjà arrêté
            }
            it.release()
        }
        recorder = null
    }

    private fun rms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / 6000.0).toFloat().coerceIn(0f, 1f)
    }

    // ---- Lecture des réponses ----

    private fun play(address: String, onDone: () -> Unit) {
        stopPlayback()
        val url = Prefs(context).absoluteUrl(address)
        val media = MediaPlayer()
        player = media
        var finished = false
        val done = {
            if (!finished) {
                finished = true
                media.release()
                if (player === media) player = null
                onDone()
            }
        }
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            media.setDataSource(url)
            media.setOnPreparedListener { it.start() }
            media.setOnCompletionListener { done() }
            media.setOnErrorListener { _, _, _ ->
                done()
                true
            }
            media.prepareAsync()
        } catch (e: Exception) {
            main.post { done() }
        }
    }

    private fun stopPlayback() {
        player?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // pas encore prêt
            }
            it.release()
        }
        player = null
        speechPending = false
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            main.postDelayed({ tone.release() }, 400)
        } catch (e: Exception) {
            // pas de son disponible
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        /** 1 024 échantillons = 64 ms de son par envoi. */
        private const val CHUNK_BYTES = 2_048
        /** Seuil de détection de « Hey Jarvis » (comme l'appli openWakeWord de HA). */
        /** Index : 0 normale, 1 élevée, 2 très élevée, 3 basse. */
        private val WAKE_THRESHOLDS = floatArrayOf(0.5f, 0.4f, 0.3f, 0.65f)
        private val WAKE_PATIENCE = intArrayOf(2, 2, 2, 3)
        /** Avec des voix apprises : en plus du seuil, la voix doit ressembler à l'une d'elles. */
        private const val VERIFIER_CUTOFF = 0.7f

        /** Ordre d'affichage des sensibilités (de la plus stricte à la plus large). */
        val SENSITIVITY_ORDER = intArrayOf(3, 0, 1, 2)
        /** Durée du bip de début d'écoute, remplacée par du silence dans le son envoyé à HA. */
        private const val BEEP_MUTE_MS = 300L

        const val FEATURES = EsphomeServer.VOICE_FEATURE_VOICE_ASSISTANT or
            EsphomeServer.VOICE_FEATURE_API_AUDIO or
            EsphomeServer.VOICE_FEATURE_ANNOUNCE or
            EsphomeServer.VOICE_FEATURE_START_CONVERSATION or
            EsphomeServer.VOICE_FEATURE_TIMERS
    }
}
