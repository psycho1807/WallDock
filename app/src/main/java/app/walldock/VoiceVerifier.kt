package app.walldock

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Filtre « voix apprises » au-dessus du modèle « Hey Jarvis » (méthode des « custom verifier
 * models » d'openWakeWord) : une régression logistique sur les empreintes vocales
 * (16 × 96 valeurs) que le modèle voit au moment du mot. Elle apprend la façon dont les
 * personnes de la maison disent « Hey Jarvis », ce qui permet d'être bien plus sensible
 * sans se déclencher sur une conversation ou la télé.
 *
 * Les exemples enregistrés restent sur la tablette (fichier dans les données de l'appli).
 */
class VoiceVerifier private constructor(
    private val mean: FloatArray,
    private val scale: FloatArray,
    private val weights: FloatArray,
    private val bias: Float,
    val voices: Int
) {

    /** Probabilité (0–1) que ce soit « Hey Jarvis » dit par une voix apprise. */
    fun predict(features: FloatArray): Float {
        var z = bias.toDouble()
        for (i in weights.indices) z += weights[i] * ((features[i] - mean[i]) / scale[i])
        return (1.0 / (1.0 + exp(-z))).toFloat()
    }

    companion object {
        const val SIZE = WakeWordDetector.FEATURE_SIZE
        private const val VERSION = 1

        private fun dataFile(context: Context) = File(context.filesDir, "wakeword/voices.bin")
        private fun modelFile(context: Context) = File(context.filesDir, "wakeword/verifier.bin")

        /** Exemples enregistrés : positifs (le mot) et négatifs (bruit, conversations). */
        class Samples(val positives: MutableList<FloatArray>, val negatives: MutableList<FloatArray>, var voices: Int)

        fun loadSamples(context: Context): Samples {
            val file = dataFile(context)
            val samples = Samples(mutableListOf(), mutableListOf(), 0)
            if (!file.isFile) return samples
            try {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    if (input.readInt() != VERSION || input.readInt() != SIZE) return samples
                    samples.voices = input.readInt()
                    repeat(input.readInt()) { samples.positives.add(readVector(input)) }
                    repeat(input.readInt()) { samples.negatives.add(readVector(input)) }
                }
            } catch (e: Exception) {
                return Samples(mutableListOf(), mutableListOf(), 0)
            }
            return samples
        }

        private fun saveSamples(context: Context, samples: Samples) {
            val file = dataFile(context)
            file.parentFile?.mkdirs()
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeInt(SIZE)
                out.writeInt(samples.voices)
                out.writeInt(samples.positives.size)
                samples.positives.forEach { writeVector(out, it) }
                out.writeInt(samples.negatives.size)
                samples.negatives.forEach { writeVector(out, it) }
            }
        }

        /** Le filtre appris, ou null si aucune voix n'a été apprise. */
        fun load(context: Context): VoiceVerifier? {
            val file = modelFile(context)
            if (!file.isFile) return null
            return try {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    if (input.readInt() != VERSION || input.readInt() != SIZE) return null
                    val voices = input.readInt()
                    val bias = input.readFloat()
                    VoiceVerifier(readVector(input), readVector(input), readVector(input), bias, voices)
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Oublie toutes les voix apprises. */
        fun forget(context: Context) {
            dataFile(context).delete()
            modelFile(context).delete()
        }

        /**
         * Ajoute une voix (ses exemples du mot, et les sons de la pièce) puis réapprend le filtre
         * avec toutes les voix enregistrées. À appeler hors du fil principal.
         */
        fun addVoice(context: Context, positives: List<FloatArray>, negatives: List<FloatArray>): VoiceVerifier {
            val samples = loadSamples(context)
            samples.positives.addAll(positives)
            samples.negatives.addAll(negatives)
            // On garde au plus 1 500 exemples de bruit (les plus récents)
            while (samples.negatives.size > 1_500) samples.negatives.removeAt(0)
            samples.voices += 1
            saveSamples(context, samples)
            val verifier = train(samples.positives, samples.negatives, samples.voices)
            val file = modelFile(context)
            file.parentFile?.mkdirs()
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeInt(SIZE)
                out.writeInt(verifier.voices)
                out.writeFloat(verifier.bias)
                writeVector(out, verifier.mean)
                writeVector(out, verifier.scale)
                writeVector(out, verifier.weights)
            }
            return verifier
        }

        /** Régression logistique avec régularisation L2 et classes équilibrées (descente de gradient). */
        fun train(positives: List<FloatArray>, negatives: List<FloatArray>, voices: Int): VoiceVerifier {
            val all = positives + negatives
            val n = all.size
            val mean = FloatArray(SIZE)
            val scale = FloatArray(SIZE)
            for (x in all) for (i in 0 until SIZE) mean[i] += x[i]
            for (i in 0 until SIZE) mean[i] /= n
            for (x in all) for (i in 0 until SIZE) { val d = x[i] - mean[i]; scale[i] += d * d }
            for (i in 0 until SIZE) scale[i] = sqrt(scale[i] / n).coerceAtLeast(1e-3f)

            val data = Array(n) { k -> FloatArray(SIZE) { i -> (all[k][i] - mean[i]) / scale[i] } }
            val labels = FloatArray(n) { k -> if (k < positives.size) 1f else 0f }
            // Classes équilibrées : chaque classe pèse autant au total
            val posWeight = 0.5f / positives.size
            val negWeight = 0.5f / negatives.size
            val sampleWeight = FloatArray(n) { k -> if (labels[k] > 0f) posWeight else negWeight }
            val lambda = 0.01f
            // Pas d'apprentissage sûr : 1 / (plus grande courbure), estimée par itération de puissance
            var v = FloatArray(SIZE) { 1f / sqrt(SIZE.toFloat()) }
            var largest = 1f
            repeat(20) {
                val next = FloatArray(SIZE)
                for (k in 0 until n) {
                    var dot = 0f
                    for (i in 0 until SIZE) dot += data[k][i] * v[i]
                    dot *= sampleWeight[k]
                    for (i in 0 until SIZE) next[i] += data[k][i] * dot
                }
                var norm = 0f
                for (x in next) norm += x * x
                largest = sqrt(norm).coerceAtLeast(1e-6f)
                v = FloatArray(SIZE) { next[it] / largest }
            }
            val rate = 1f / (0.25f * largest + lambda)
            val weights = FloatArray(SIZE)
            var bias = 0f
            val grad = FloatArray(SIZE)
            repeat(400) {
                java.util.Arrays.fill(grad, 0f)
                var gradBias = 0f
                for (k in 0 until n) {
                    val x = data[k]
                    var z = bias.toDouble()
                    for (i in 0 until SIZE) z += weights[i] * x[i]
                    val p = (1.0 / (1.0 + exp(-z))).toFloat()
                    val err = (p - labels[k]) * sampleWeight[k]
                    for (i in 0 until SIZE) grad[i] += err * x[i]
                    gradBias += err
                }
                for (i in 0 until SIZE) weights[i] -= rate * (grad[i] + lambda * weights[i])
                bias -= rate * gradBias
            }
            return VoiceVerifier(mean, scale, weights, bias, voices)
        }

        private fun readVector(input: DataInputStream): FloatArray = FloatArray(SIZE) { input.readFloat() }

        private fun writeVector(out: DataOutputStream, v: FloatArray) {
            for (x in v) out.writeFloat(x)
        }
    }
}
