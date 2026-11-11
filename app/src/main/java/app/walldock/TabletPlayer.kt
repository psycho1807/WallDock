package app.walldock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import app.walldock.esphome.Entity

/**
 * Lecteur multimédia de la tablette, piloté par Home Assistant (entité media_player) :
 * musique, radio, annonces, pause, volume, muet. Tout se passe sur le fil principal.
 *
 * Quand Jarvis écoute ou parle, la musique est mise en pause puis reprend toute seule.
 */
class TabletPlayer(private val context: Context, private val onChange: (Entity.MediaState) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)

    private var music: MediaPlayer? = null
    private var musicState = Entity.MediaState.IDLE
    private var prepared = false
    private var announcer: MediaPlayer? = null
    private var muted = false
    /** Musique mise en pause par Jarvis ou par une annonce : elle reprendra ensuite. */
    private var pausedForInterruption = false
    private var interrupted = false

    val isPlaying: Boolean
        get() = musicState == Entity.MediaState.PLAYING

    fun play(url: String) {
        stopMusic()
        val player = newPlayer() ?: return
        try {
            player.setDataSource(url)
        } catch (e: Exception) {
            player.release()
            report()
            return
        }
        player.setOnPreparedListener {
            if (music !== it) return@setOnPreparedListener
            prepared = true
            applyMute(it)
            if (interrupted || musicState == Entity.MediaState.PAUSED) {
                if (interrupted) pausedForInterruption = true
                musicState = Entity.MediaState.PAUSED
            } else {
                it.start()
                musicState = Entity.MediaState.PLAYING
            }
            report()
        }
        player.setOnCompletionListener {
            if (music === it) stopMusic()
        }
        player.setOnErrorListener { p, _, _ ->
            if (music === p) stopMusic()
            true
        }
        music = player
        prepared = false
        musicState = Entity.MediaState.PLAYING
        player.prepareAsync()
        report()
    }

    fun pause() {
        val player = music ?: return
        pausedForInterruption = false
        if (musicState == Entity.MediaState.PLAYING) {
            if (prepared) try {
                player.pause()
            } catch (e: Exception) {
                // pas encore prêt
            }
            musicState = Entity.MediaState.PAUSED
            report()
        }
    }

    fun resume() {
        val player = music ?: return
        if (musicState == Entity.MediaState.PAUSED && !interrupted) {
            try {
                if (prepared) player.start()
                musicState = Entity.MediaState.PLAYING
            } catch (e: Exception) {
                stopMusic()
                return
            }
            report()
        }
    }

    fun toggle() = if (musicState == Entity.MediaState.PLAYING) pause() else resume()

    fun stop() = stopMusic()

    fun setMuted(value: Boolean) {
        muted = value
        music?.let { applyMute(it) }
        report()
    }

    /** Volume « médias » de la tablette (0–1), le même que Jarvis et les annonces. */
    fun setVolume(value: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(value.coerceIn(0f, 1f) * max), 0)
        } catch (e: Exception) {
            // mode Ne pas déranger : changement refusé
        }
        report()
    }

    fun volumeStep(up: Boolean) {
        setVolume((currentVolume() + if (up) 0.1f else -0.1f).coerceIn(0f, 1f))
    }

    /** Annonce par-dessus la musique : la musique s'arrête le temps de l'annonce. */
    fun announce(url: String) {
        announcer?.release()
        val player = newPlayer() ?: return
        try {
            player.setDataSource(url)
        } catch (e: Exception) {
            player.release()
            return
        }
        val done = {
            if (announcer === player) {
                announcer = null
                player.release()
                setInterrupted(false)
            }
        }
        player.setOnPreparedListener { it.start() }
        player.setOnCompletionListener { done() }
        player.setOnErrorListener { _, _, _ -> done(); true }
        announcer = player
        setInterrupted(true)
        player.prepareAsync()
    }

    /** Jarvis écoute ou parle (vrai) / a fini (faux). */
    fun setInterrupted(value: Boolean) {
        if (value == interrupted) {
            report()
            return
        }
        interrupted = value
        val player = music
        if (value) {
            if (player != null && musicState == Entity.MediaState.PLAYING) {
                if (prepared) try {
                    player.pause()
                } catch (e: Exception) {
                    // pas encore prêt
                }
                musicState = Entity.MediaState.PAUSED
                pausedForInterruption = true
            }
        } else if (pausedForInterruption && announcer == null && player != null) {
            pausedForInterruption = false
            // Petite pause pour laisser finir la voix de Jarvis
            main.postDelayed({ if (!interrupted) resume() }, 600)
        }
        report()
    }

    fun state(): Entity.MediaState = Entity.MediaState(
        if (announcer != null) Entity.MediaState.ANNOUNCING else musicState,
        currentVolume(),
        muted
    )

    fun report() = onChange(state())

    fun release() {
        main.removeCallbacksAndMessages(null)
        announcer?.release()
        announcer = null
        music?.release()
        music = null
    }

    private fun stopMusic() {
        music?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // pas encore prêt
            }
            it.release()
        }
        music = null
        prepared = false
        pausedForInterruption = false
        musicState = Entity.MediaState.IDLE
        report()
    }

    private fun newPlayer(): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        }
    } catch (e: Exception) {
        null
    }

    private fun applyMute(player: MediaPlayer) {
        val level = if (muted) 0f else 1f
        try {
            player.setVolume(level, level)
        } catch (e: Exception) {
            // lecteur libéré
        }
    }

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }
}
