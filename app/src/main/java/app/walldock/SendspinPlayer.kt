package app.walldock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.PowerManager
import app.walldock.sendspin.SendspinClient
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * Sortie audio du lecteur Music Assistant (Sendspin) : chaque morceau PCM est joué à l'heure
 * indiquée par le serveur. L'avance ou le retard de la carte son est mesuré avec
 * [AudioTimestamp] et corrigé en retirant ou en doublant quelques échantillons.
 */
class SendspinPlayer(private val context: Context) : SendspinClient.Player {

    private class Chunk(val timeUs: Long, val pcm: ByteArray)

    private val queue = LinkedBlockingDeque<Chunk>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var generation = 0
    @Volatile private var sampleRate = 48000
    @Volatile private var frameBytes = 4
    @Volatile private var volume = 50
    @Volatile private var muted = false
    @Volatile private var interrupted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Appelé quand la lecture démarre ou s'arrête. */
    var onPlaying: ((Boolean) -> Unit)? = null

    override fun start(sampleRate: Int, channels: Int, bitDepth: Int) {
        synchronized(this) {
            stopTrack()
            this.sampleRate = sampleRate
            frameBytes = channels * bitDepth / 8
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val encoding = when (bitDepth) {
                24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                32 -> AudioFormat.ENCODING_PCM_32BIT
                else -> AudioFormat.ENCODING_PCM_16BIT
            }
            val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelMask).setEncoding(encoding).build()
                    )
                    .setBufferSizeInBytes(maxOf(min * 2, sampleRate * frameBytes / 5))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                return
            }
            track = t
            applyVolume()
            t.play()
            val gen = ++generation
            val th = Thread({ writeLoop(t, gen) }, "sendspin-audio")
            th.priority = Thread.MAX_PRIORITY
            thread = th
            th.start()
            holdLocks(true)
        }
        onPlaying?.invoke(true)
    }

    override fun chunk(localTimeUs: Long, pcm: ByteArray, offset: Int, length: Int) {
        if (track == null) return
        queue.offer(Chunk(localTimeUs, pcm.copyOfRange(offset, offset + length)))
        // Sécurité : pas plus d'environ 30 s en attente
        while (queue.size > 3000) queue.poll()
    }

    override fun clear(fromLocalUs: Long) {
        synchronized(this) {
            if (fromLocalUs < 0) queue.clear() else queue.removeIf { it.timeUs >= fromLocalUs }
            val t = track ?: return
            // Ce qui est déjà dans la carte son ne peut pas être retiré en partie : on vide tout
            generation++
            try {
                t.pause()
                t.flush()
                t.play()
            } catch (e: Exception) {
                // piste fermée
            }
            val gen = generation
            val th = Thread({ writeLoop(t, gen) }, "sendspin-audio")
            th.priority = Thread.MAX_PRIORITY
            thread = th
            th.start()
        }
    }

    override fun end() {
        val was = track != null
        synchronized(this) {
            stopTrack()
            queue.clear()
            holdLocks(false)
        }
        if (was) onPlaying?.invoke(false)
    }

    override fun setVolume(volume: Int, muted: Boolean) {
        this.volume = volume
        this.muted = muted
        applyVolume()
    }

    /** Coupe le son pendant que Jarvis écoute ou parle. */
    fun setInterrupted(value: Boolean) {
        interrupted = value
        applyVolume()
    }

    val isPlaying: Boolean
        get() = track != null

    private fun applyVolume() {
        val gain = if (muted || interrupted) 0f else (volume / 100f).toDouble().pow(1.5).toFloat()
        try {
            track?.setVolume(gain)
        } catch (e: Exception) {
            // piste fermée
        }
    }

    private fun stopTrack() {
        generation++
        val t = track ?: return
        track = null
        thread = null
        try {
            t.pause()
            t.flush()
            t.release()
        } catch (e: Exception) {
            // déjà libérée
        }
    }

    private fun holdLocks(hold: Boolean) {
        try {
            if (hold) {
                if (wakeLock == null) {
                    wakeLock = context.getSystemService(PowerManager::class.java)
                        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walldock:sendspin")?.apply { setReferenceCounted(false) }
                }
                if (wifiLock == null) {
                    @Suppress("DEPRECATION")
                    wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "walldock:sendspin")?.apply { setReferenceCounted(false) }
                }
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
                wifiLock?.acquire()
            } else {
                wakeLock?.let { if (it.isHeld) it.release() }
                wifiLock?.let { if (it.isHeld) it.release() }
            }
        } catch (e: Exception) {
            // verrous indisponibles
        }
    }

    // ---- Écriture synchronisée ----

    private fun writeLoop(t: AudioTrack, gen: Int) {
        var framesWritten = 0L
        val ts = AudioTimestamp()
        var started = false
        while (gen == generation) {
            val chunk = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            } ?: continue
            if (gen != generation) return
            val rate = sampleRate
            val fb = frameBytes
            var data = chunk.pcm
            val frames = data.size / fb
            val now = SendspinClient.nowUs()
            // Heure à laquelle le prochain échantillon écrit sortira du haut-parleur
            val playAt: Long = if (t.getTimestamp(ts) && ts.framePosition > 0) {
                ts.nanoTime / 1000 + (framesWritten - ts.framePosition) * 1_000_000L / rate
            } else {
                val buffered = framesWritten - t.playbackHeadPosition.toLong()
                now + LATENCY_GUESS_US + maxOf(0L, buffered) * 1_000_000L / rate
            }
            val chunkEnd = chunk.timeUs + frames * 1_000_000L / rate
            if (chunkEnd < playAt) continue // trop tard : morceau abandonné
            var error = playAt - chunk.timeUs // > 0 : en retard, < 0 : en avance
            if (!started || abs(error) > HARD_US) {
                // Gros écart : silence ajouté ou début du morceau coupé
                if (error < 0) {
                    val silence = (-error * rate / 1_000_000L).toInt()
                    framesWritten += writeSilence(t, silence, fb, gen)
                } else {
                    val skip = (error * rate / 1_000_000L).toInt().coerceAtMost(frames)
                    data = data.copyOfRange(skip * fb, data.size)
                }
                error = 0
                started = true
            } else if (abs(error) > SOFT_US) {
                // Petit écart : on retire ou double un échantillon toutes les 100 au plus
                val correct = (abs(error) * rate / 1_000_000L).toInt().coerceAtMost(frames / 100).coerceAtLeast(1)
                data = if (error > 0) dropFrames(data, fb, correct) else duplicateFrames(data, fb, correct)
            }
            var off = 0
            while (off < data.size && gen == generation) {
                val n = try {
                    t.write(data, off, data.size - off)
                } catch (e: Exception) {
                    return
                }
                if (n <= 0) return
                off += n
            }
            framesWritten += data.size / fb
        }
    }

    private fun writeSilence(t: AudioTrack, frames: Int, fb: Int, gen: Int): Int {
        var left = frames.coerceAtMost(sampleRate * 5)
        val written = left
        val zero = ByteArray(minOf(left, 4800) * fb)
        while (left > 0 && gen == generation) {
            val n = minOf(left, zero.size / fb)
            val w = try {
                t.write(zero, 0, n * fb)
            } catch (e: Exception) {
                return written - left
            }
            if (w <= 0) break
            left -= w / fb
        }
        return written - left
    }

    private fun dropFrames(data: ByteArray, fb: Int, count: Int): ByteArray {
        val frames = data.size / fb
        if (count <= 0 || frames <= count) return data
        val step = frames / (count + 1)
        val out = ByteArray(data.size - count * fb)
        var o = 0
        var dropped = 0
        for (f in 0 until frames) {
            if (dropped < count && f > 0 && f % step == 0) {
                dropped++
                continue
            }
            System.arraycopy(data, f * fb, out, o, fb)
            o += fb
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    private fun duplicateFrames(data: ByteArray, fb: Int, count: Int): ByteArray {
        val frames = data.size / fb
        if (count <= 0 || frames == 0) return data
        val step = maxOf(1, frames / (count + 1))
        val out = ByteArray(data.size + count * fb)
        var o = 0
        var added = 0
        for (f in 0 until frames) {
            System.arraycopy(data, f * fb, out, o, fb)
            o += fb
            if (added < count && f > 0 && f % step == 0) {
                System.arraycopy(data, f * fb, out, o, fb)
                o += fb
                added++
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    companion object {
        private const val LATENCY_GUESS_US = 80_000L
        private const val SOFT_US = 2_000L
        private const val HARD_US = 50_000L
    }
}
