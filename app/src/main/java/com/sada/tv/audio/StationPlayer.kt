package com.sada.tv.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.sada.tv.util.SLog

/**
 * Plays the internet station, keeps it alive across network drops and reports
 * anything that shifts its timeline:
 *  - live HLS speed adjustment is disabled (speed is pinned to 1.0), so the
 *    station never drifts against the picture by itself;
 *  - a rebuffering stall is reported through [onStall] with its length, so the
 *    sync engine can compensate it immediately;
 *  - after a reconnect, [onRecovered] asks for a fresh sync.
 *
 * Call everything on the main thread except [audioDelayMs] and [setTapping].
 */
@UnstableApi
class StationPlayer(
    context: Context,
    onAlarmStream: Boolean,
    onTap: (ShortArray, Int, Int, Int, Double) -> Unit,
    private val onStall: (Long) -> Unit,
    private val onRecovered: () -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val line = DelayLine()
    private val processor = DelayAudioProcessor(line)
    private lateinit var tap: TappingAudioSink
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    val player: ExoPlayer

    private var url: String? = null
    @Volatile private var wantPlaying = false
    private var reconnectAttempt = 0
    private var interrupted = false
    private var bufferingSince = 0L
    private var wasReady = false

    /** Station delay (ms). Changes are applied smoothly by the delay line. */
    var audioDelayMs: Int
        get() = line.targetMs
        set(v) { line.targetMs = v.coerceIn(0, MAX_DELAY_MS) }

    /** Delay actually in force right now (ms). */
    val currentDelayMs: Double get() = line.currentMs

    init {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val base = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // keep 16-bit PCM for the delay line
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                    .build()
                tap = TappingAudioSink(base, line, onTap)
                return tap
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        player = ExoPlayer.Builder(context, renderers)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (onAlarmStream) C.USAGE_ALARM else C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false, // never take audio focus: the TV app must keep playing
        )

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error)

            override fun onPlaybackStateChanged(state: Int) {
                val now = SystemClock.elapsedRealtime()
                when (state) {
                    Player.STATE_BUFFERING -> if (wasReady && !interrupted) bufferingSince = now
                    Player.STATE_READY -> {
                        if (bufferingSince > 0 && !interrupted) {
                            val stall = now - bufferingSince
                            SLog.i("station stalled $stall ms")
                            if (stall >= 80) onStall(stall)
                        }
                        bufferingSince = 0
                        wasReady = true
                        if (interrupted) main.postDelayed(::checkRecovered, 5000)
                        else reconnectAttempt = 0
                    }
                    else -> bufferingSince = 0
                }
            }
        })
        registerNetworkCallback()
    }

    fun setTapping(on: Boolean) { if (::tap.isInitialized) tap.tapping = on }

    fun play(url: String) {
        this.url = url
        wantPlaying = true
        reconnectAttempt = 0
        interrupted = false
        wasReady = false
        bufferingSince = 0
        prepare(url)
    }

    private fun prepare(u: String) {
        val item = MediaItem.Builder()
            .setUri(u)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        wantPlaying = false
        interrupted = false
        main.removeCallbacksAndMessages(null)
        player.stop()
        player.clearMediaItems()
    }

    fun setVolume(v: Float) { player.volume = v.coerceIn(0f, 1f) }
    val volume: Float get() = player.volume

    fun release() {
        unregisterNetworkCallback()
        main.removeCallbacksAndMessages(null)
        player.release()
    }

    private fun onError(error: PlaybackException) {
        if (!wantPlaying) return
        interrupted = true
        wasReady = false
        val u = url ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            SLog.i("behind live window; jumping to live")
            onStatus("انقطع البث لحظة؛ عودة للبث المباشر")
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        reconnectAttempt++
        val backoff = minOf(30_000L, 1_000L * reconnectAttempt)
        SLog.i("stream error ${error.errorCodeName}; reconnect #$reconnectAttempt in ${backoff}ms")
        onStatus("انقطع البث • إعادة الاتصال رقم $reconnectAttempt")
        main.postDelayed({ if (wantPlaying) prepare(u) }, backoff)
    }

    private fun checkRecovered() {
        if (!interrupted || !wantPlaying) return
        if (player.playbackState != Player.STATE_READY) {
            main.postDelayed(::checkRecovered, 2000)
            return
        }
        interrupted = false
        reconnectAttempt = 0
        SLog.i("stream recovered")
        onRecovered()
    }

    private fun registerNetworkCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (wantPlaying && interrupted && player.playbackState == Player.STATE_IDLE) {
                        SLog.i("network back; retrying now")
                        url?.let { prepare(it) }
                    }
                }
            }
        }
        netCallback = cb
        runCatching {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                cb,
            )
        }
    }

    private fun unregisterNetworkCallback() {
        netCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        netCallback = null
    }

    companion object {
        const val MAX_DELAY_MS = 120_000
    }
}
