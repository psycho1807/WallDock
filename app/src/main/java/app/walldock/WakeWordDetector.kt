package app.walldock

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Détection de « Hey Jarvis » directement sur la tablette, avec les modèles openWakeWord
 * (spectrogramme mel → empreinte vocale → modèle « hey jarvis »), comme le fait l'appli
 * openWakeWord de Home Assistant.
 *
 * Les modèles ne sont pas inclus dans l'appli (licence) : ils sont téléchargés une fois
 * depuis le projet openWakeWord, puis gardés sur la tablette.
 */
class WakeWordDetector private constructor(dir: File) {

    private val options = Interpreter.Options().setNumThreads(1).setUseXNNPACK(false)
    private val mel = Interpreter(File(dir, MEL_MODEL), options).also {
        it.resizeInput(0, intArrayOf(1, WINDOW_SAMPLES))
        it.allocateTensors()
    }
    private val embedding = Interpreter(File(dir, EMBEDDING_MODEL), options)
    private val wakeWord = Interpreter(File(dir, WAKE_MODEL), options)

    // Tampons réutilisés (pas d'allocation toutes les 80 ms)
    private val raw = FloatArray(WINDOW_SAMPLES)
    private val melInput = arrayOf(raw)
    private val melOutput = Array(1) { Array(1) { Array(MEL_FRAMES_PER_CHUNK) { FloatArray(MEL_BINS) } } }
    private val melBuffer = Array(EMBEDDING_WINDOW) { FloatArray(MEL_BINS) { 1f } }
    private val embeddingInput = Array(1) { Array(EMBEDDING_WINDOW) { Array(MEL_BINS) { FloatArray(1) } } }
    private val embeddingOutput = Array(1) { Array(1) { Array(1) { FloatArray(EMBEDDING_SIZE) } } }
    private val features = ArrayDeque<FloatArray>()
    private val wakeInput = Array(1) { Array(FEATURE_FRAMES) { FloatArray(EMBEDDING_SIZE) } }
    private val wakeOutput = Array(1) { FloatArray(1) }

    private val pending = FloatArray(CHUNK_SAMPLES)
    private var pendingCount = 0
    private var predictions = 0

    /** Repart de zéro (après une conversation, pour ne pas réutiliser du vieux son). */
    fun reset() {
        raw.fill(0f)
        for (row in melBuffer) row.fill(1f)
        features.clear()
        pendingCount = 0
        predictions = 0
    }

    /**
     * Ajoute du son (PCM 16 bits, little-endian) et renvoie le meilleur score obtenu (0–1),
     * ou -1 si aucun bloc complet de 80 ms n'a encore été analysé.
     */
    fun process(pcm: ByteArray, length: Int): Float {
        var best = -1f
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            pending[pendingCount++] = sample.toFloat()
            if (pendingCount == CHUNK_SAMPLES) {
                pendingCount = 0
                val score = processChunk()
                if (score > best) best = score
            }
            i += 2
        }
        return best
    }

    private fun processChunk(): Float {
        // Fenêtre glissante : 1 280 nouveaux échantillons + 480 du bloc précédent
        System.arraycopy(raw, CHUNK_SAMPLES, raw, 0, WINDOW_SAMPLES - CHUNK_SAMPLES)
        System.arraycopy(pending, 0, raw, WINDOW_SAMPLES - CHUNK_SAMPLES, CHUNK_SAMPLES)
        mel.run(melInput, melOutput)

        // 8 nouvelles trames mel (transformation x/10 + 2 d'openWakeWord), on garde les 76 dernières
        val frames = melOutput[0][0]
        val shift = frames.size
        for (r in 0 until EMBEDDING_WINDOW - shift) System.arraycopy(melBuffer[r + shift], 0, melBuffer[r], 0, MEL_BINS)
        for (f in 0 until shift) {
            val target = melBuffer[EMBEDDING_WINDOW - shift + f]
            for (b in 0 until MEL_BINS) target[b] = frames[f][b] / 10f + 2f
        }

        for (r in 0 until EMBEDDING_WINDOW) for (b in 0 until MEL_BINS) embeddingInput[0][r][b][0] = melBuffer[r][b]
        embedding.run(embeddingInput, embeddingOutput)
        features.addLast(embeddingOutput[0][0][0].copyOf())
        while (features.size > FEATURE_FRAMES) features.removeFirst()
        if (features.size < FEATURE_FRAMES) return 0f

        for (r in 0 until FEATURE_FRAMES) System.arraycopy(features[r], 0, wakeInput[0][r], 0, EMBEDDING_SIZE)
        wakeWord.run(wakeInput, wakeOutput)
        predictions++
        // Comme openWakeWord : on ignore les toutes premières prédictions
        return if (predictions <= WARMUP_PREDICTIONS) 0f else wakeOutput[0][0]
    }

    fun close() {
        mel.close()
        embedding.close()
        wakeWord.close()
    }

    companion object {
        private const val CHUNK_SAMPLES = 1_280       // 80 ms à 16 kHz
        private const val WINDOW_SAMPLES = 1_760      // + 3 × 160 échantillons de recouvrement
        private const val MEL_FRAMES_PER_CHUNK = 8
        private const val MEL_BINS = 32
        private const val EMBEDDING_WINDOW = 76
        private const val EMBEDDING_SIZE = 96
        private const val FEATURE_FRAMES = 16
        private const val WARMUP_PREDICTIONS = 5

        private const val MEL_MODEL = "melspectrogram.tflite"
        private const val EMBEDDING_MODEL = "embedding_model.tflite"
        private const val WAKE_MODEL = "hey_jarvis_v0.1.tflite"
        private const val BASE_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/"
        private val MODELS = listOf(MEL_MODEL, EMBEDDING_MODEL, WAKE_MODEL)

        /** Dernière erreur de téléchargement ou de chargement (diagnostic). */
        @Volatile
        var lastError: String? = null

        private fun modelDir(context: Context) = File(context.filesDir, "wakeword")

        fun modelsPresent(context: Context): Boolean =
            MODELS.all { File(modelDir(context), it).let { f -> f.isFile && f.length() > 100_000 } }

        /** Télécharge les modèles s'il le faut (à appeler hors du fil principal). */
        fun downloadModels(context: Context): Boolean {
            val dir = modelDir(context)
            dir.mkdirs()
            for (name in MODELS) {
                val target = File(dir, name)
                if (target.isFile && target.length() > 100_000) continue
                val temp = File(dir, "$name.part")
                try {
                    val connection = URL(BASE_URL + name).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 60_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "WallDock/${BuildConfig.VERSION_NAME}")
                    try {
                        if (connection.responseCode != 200) {
                            lastError = "Téléchargement de $name : HTTP ${connection.responseCode}"
                            return false
                        }
                        connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                    } finally {
                        connection.disconnect()
                    }
                    if (!temp.renameTo(target)) {
                        lastError = "Impossible d'enregistrer $name"
                        return false
                    }
                } catch (e: Exception) {
                    temp.delete()
                    lastError = "Téléchargement de $name : ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
                    return false
                }
            }
            return true
        }

        private const val PREFS = "walldock"
        private const val KEY_IN_PROGRESS = "wakeword_load_in_progress"
        private const val KEY_CRASHED = "wakeword_crashed"

        /**
         * Vrai si un essai précédent a fait planter l'appli (plantage natif impossible à intercepter) :
         * on n'insiste pas et on reste sur la détection par HA.
         */
        fun crashedBefore(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (sp.getBoolean(KEY_IN_PROGRESS, false)) {
                sp.edit().putBoolean(KEY_IN_PROGRESS, false).putBoolean(KEY_CRASHED, true).commit()
            }
            return sp.getBoolean(KEY_CRASHED, false)
        }

        /** Nouvel essai demandé (réglage décoché puis recoché). */
        fun clearCrash(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CRASHED, false).apply()
        }

        // Le modèle mel v0.5.1 déclare une entrée par défaut [1, 1] : TFLite Android alloue les tenseurs
        // dès la création et échoue (« BytesRequired number of elements overflowed »).
        // On fixe la taille par défaut à [1, 1760] directement dans le fichier (même calcul ensuite).
        private const val MEL_FILE_SIZE = 1_092_516L
        private const val MEL_SHAPE_OFFSET = 1_092_456L

        private fun fixMelInputShape(file: File) {
            if (file.length() != MEL_FILE_SIZE) return
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val shape = ByteArray(8)
                raf.seek(MEL_SHAPE_OFFSET)
                raf.readFully(shape)
                val first = java.nio.ByteBuffer.wrap(shape).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                val second = java.nio.ByteBuffer.wrap(shape, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                if (first == 1 && second == 1) {
                    raf.seek(MEL_SHAPE_OFFSET + 4)
                    raf.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(WINDOW_SAMPLES).array())
                }
            }
        }

        /** Charge le détecteur et le teste sur du silence, ou null si les modèles manquent ou sont illisibles. */
        fun load(context: Context): WakeWordDetector? {
            if (!modelsPresent(context)) return null
            if (crashedBefore(context)) {
                lastError = "le moteur a planté sur cette tablette"
                return null
            }
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putBoolean(KEY_IN_PROGRESS, true).commit()
            return try {
                fixMelInputShape(File(modelDir(context), MEL_MODEL))
                val detector = WakeWordDetector(modelDir(context))
                // Essai complet (3 s de silence) avant de l'utiliser pour de vrai
                val silence = ByteArray(2_048)
                repeat(24) { detector.process(silence, silence.size) }
                detector.reset()
                detector
            } catch (e: Throwable) {
                lastError = "Chargement des modèles : ${e.javaClass.simpleName} ${e.message ?: ""}".trim().take(200)
                null
            } finally {
                sp.edit().putBoolean(KEY_IN_PROGRESS, false).commit()
            }
        }
    }
}
