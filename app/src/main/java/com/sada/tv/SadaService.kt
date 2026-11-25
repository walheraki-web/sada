package com.sada.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import com.sada.tv.audio.StationPlayer
import com.sada.tv.capture.MicSource
import com.sada.tv.capture.PlaybackCaptureSource
import com.sada.tv.capture.ProjectionActivity
import com.sada.tv.capture.TvAudioSource
import com.sada.tv.receiver.ReceiverControl
import com.sada.tv.sync.FeatureTrack
import com.sada.tv.sync.SyncEngine
import com.sada.tv.sync.SyncPolicy
import com.sada.tv.util.Prefs
import com.sada.tv.util.SLog
import com.sada.tv.util.Station
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The always-on part of Sada: plays the station, captures the channel's sound,
 * keeps them in sync (with the receiver's own timeshift), mutes the channel and
 * recovers from network drops. Bound by the UI; a foreground service so it keeps
 * running over the live channel.
 */
@UnstableApi
class SadaService : Service() {

    companion object {
        const val ACTION_PROJECTION = "com.sada.tv.PROJECTION"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 1
        private const val CHECK_EVERY_MS = 3 * 60_000L
    }

    inner class LocalBinder : android.os.Binder() { val service get() = this@SadaService }

    interface Listener {
        fun onStatus(text: String) {}
        fun onResult(text: String, ok: Boolean) {}
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())

    lateinit var prefs: Prefs; private set
    lateinit var receiver: ReceiverControl; private set
    private var player: StationPlayer? = null
    private val tvTrack = FeatureTrack()
    private val stationTrack = FeatureTrack()
    private lateinit var engine: SyncEngine

    private var projection: MediaProjection? = null
    private var source: TvAudioSource? = null
    @Volatile private var capturing = false

    @Volatile var currentStation: Station? = null; private set
    @Volatile var playing = false; private set
    @Volatile var lastResult = "لم تُجرَ مزامنة بعد"; private set
    @Volatile var lastStatus = "—"; private set
    var listener: Listener? = null

    private var fgTypes = 0

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        SLog.init(this)
        prefs = Prefs(this)
        receiver = ReceiverControl(this, prefs)
        // A previous run may have left the channel muted.
        if (prefs.savedMusicVolume > 0) runCatching { receiver.muteTv(false) }
        engine = SyncEngine(EngineHost(), tvTrack, stationTrack)
        try {
            player = StationPlayer(
                context = this,
                onAlarmStream = prefs.stationOnAlarmStream,
                onTap = { pcm, n, ch, rate, t -> stationTrack.push(pcm, n, ch, rate, t) },
                onStall = { ms -> engine.onStationStall(ms) },
                onRecovered = { if (playing && prefs.resyncAfterDrop) { result("عاد البث • إعادة مزامنة", false); engine.syncNow() } },
                onStatus = { status(it) },
            )
        } catch (t: Throwable) {
            SLog.e("player init", t)
            lastResult = "تعذّر تهيئة المشغّل: ${t.message}"
        }
        createChannel()
        startFg(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROJECTION) {
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            @Suppress("DEPRECATION")
            val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
            if (data != null) onProjectionGranted(code, data)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopPlayback()
        projection?.runCatching { stop() }
        player?.runCatching { release() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ capture

    /** True when the channel's sound is being captured. */
    val captureActive get() = capturing

    val captureLabel get() = source?.label ?: "غير مفعّل"

    /** Asks for the one-time capture consent (opens the system dialog). */
    fun requestChannelCapture() {
        prefs.captureMode = "playback"
        if (projection != null) { startCapture(); return }
        runCatching {
            startActivity(Intent(this, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            SLog.e("start projection activity", it)
            status("افتح صدى ثم اضغط «التقاط صوت القناة»")
        }
    }

    fun useMicrophone() {
        prefs.captureMode = "mic"
        source?.stop()
        source = MicSource()
        if (Build.VERSION.SDK_INT >= 30) {
            startFg(fgTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }
        startCapture()
    }

    private fun onProjectionGranted(code: Int, data: Intent) {
        // Android 14: the media-projection service type must be in force before
        // the projection is created.
        startFg(fgTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = runCatching { mpm.getMediaProjection(code, data) }
            .onFailure { SLog.e("getMediaProjection", it) }.getOrNull()
        if (p == null) { status("تعذّر بدء التقاط صوت القناة"); return }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                SLog.i("projection stopped")
                main.post {
                    if (projection === p) {
                        projection = null
                        if (source is PlaybackCaptureSource) { stopCapture(); source = null }
                        status("توقّف التقاط صوت القناة؛ اضغط «التقاط صوت القناة» لإعادته")
                    }
                }
            }
        }, main)
        projection?.runCatching { stop() }
        projection = p
        source?.stop()
        source = PlaybackCaptureSource(p)
        status("التقاط صوت القناة مفعّل")
        startCapture()
        if (playing && !engine.synced) main.postDelayed({ if (playing) engine.syncNow() }, 2500)
    }

    private fun startCapture() {
        stopCapture()
        val src = source ?: return
        runCatching {
            src.start({ pcm, n, ch, rate, t -> tvTrack.push(pcm, n, ch, rate, t.toDouble()) }) { err ->
                capturing = false
                main.postDelayed({ if (source === src) { status("انقطع التقاط صوت القناة، يعيد…"); startCapture() } }, 3000)
                SLog.e("capture died", err)
            }
            capturing = true
            SLog.i("capture: ${src.label}")
        }.onFailure {
            capturing = false
            SLog.e("capture start", it)
            status("تعذّر التقاط صوت القناة: ${it.message}")
        }
    }

    private fun stopCapture() {
        capturing = false
        source?.runCatching { stop() }
    }

    // ------------------------------------------------------------------ playback

    fun play(station: Station) {
        val p = player ?: run { status("المشغّل غير جاهز"); return }
        engine.cancel()
        engine.forgetSync()
        currentStation = station
        prefs.lastStationUrl = station.url
        playing = true
        receiver.ensureStationStreamAudible()
        p.setVolume(prefs.stationVolume(station.url))
        p.audioDelayMs = prefs.stationDelay(station.url)
        p.play(station.url)
        status("يشغّل ${station.name}")
        if (source == null && prefs.captureMode == "mic") useMicrophone()
        if (source == null) requestChannelCapture() else startCapture()
        // Sync automatically once both sounds are flowing.
        main.postDelayed({ if (playing && currentStation == station && capturing) engine.syncNow() }, 4000)
        scheduleChecks()
    }

    fun stopPlayback() {
        playing = false
        engine.cancel()
        engine.forgetSync()
        main.removeCallbacks(checkTick)
        player?.stop()
        stopCapture()
        if (receiver.tvMuted) receiver.muteTv(false)
        status("توقف")
    }

    fun syncNow() {
        if (!playing) { status("شغّل محطة أولًا"); return }
        if (!capturing) { requestChannelCapture(); status("فعّل التقاط صوت القناة أولًا"); return }
        engine.syncNow()
    }

    fun cancelSync() { engine.cancel(); status("أُلغيت المزامنة") }

    private val checkTick = object : Runnable {
        override fun run() {
            if (!playing) return
            if (prefs.monitor && capturing) engine.check()
            main.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    private fun scheduleChecks() {
        main.removeCallbacks(checkTick)
        main.postDelayed(checkTick, CHECK_EVERY_MS)
    }

    // ------------------------------------------------------------------ manual controls

    val audioDelayMs get() = player?.audioDelayMs ?: 0

    fun nudgeAudioDelay(deltaMs: Int) {
        val p = player ?: return
        val next = (p.audioDelayMs + deltaMs).coerceIn(0, SyncPolicy.MAX_AUDIO_DELAY_MS)
        p.audioDelayMs = next
        currentStation?.let { prefs.setStationDelay(it.url, next) }
        status("تأخير المحطة: %.2f ث".format(next / 1000.0))
    }

    /** Holds the picture back by [ms] using the receiver's timeshift. */
    fun delayPicture(ms: Long) {
        Thread {
            val r = receiver.pauseFor(ms)
            if (r.ok) prefs.timeshiftMs = prefs.timeshiftMs + ms.toInt()
            main.post { status(if (r.ok) "أُخّرت الصورة %.1f ث".format(ms / 1000.0) else "فشل: ${r.error}") }
        }.apply { isDaemon = true }.start()
    }

    /** Pauses 5 s so the user can see whether the pause key works on this box. */
    fun testPause() {
        status("اختبار: ستتجمّد الصورة 5 ثوانٍ إن كان زر الإيقاف صحيحًا")
        delayPicture(5000)
    }

    /** Cycles the pause/resume key pair. */
    fun cyclePauseKeys(): String {
        val pairs = listOf(85 to 85, 127 to 126, 126 to 126)
        val i = pairs.indexOf(prefs.pauseKey to prefs.resumeKey)
        val (pk, rk) = pairs[(i + 1) % pairs.size]
        prefs.pauseKey = pk; prefs.resumeKey = rk
        return pauseKeysLabel()
    }

    fun pauseKeysLabel(): String = when (prefs.pauseKey to prefs.resumeKey) {
        85 to 85 -> "تشغيل/إيقاف (85)"
        127 to 126 -> "إيقاف (127) ثم تشغيل (126)"
        else -> "${prefs.pauseKey} ثم ${prefs.resumeKey}"
    }

    fun nudgeCalib(deltaMs: Int) {
        prefs.calibMs = (prefs.calibMs + deltaMs).coerceIn(-1000, 1000)
        // Apply immediately: a positive calibration means the station is heard later.
        nudgeAudioDelay(-deltaMs)
        status("معايرة: %+d م.ث".format(prefs.calibMs))
    }

    fun toggleMuteTv(): Boolean {
        val want = !receiver.tvMuted
        val ok = receiver.muteTv(want)
        status(
            when {
                !ok && !prefs.stationOnAlarmStream -> "الكتم يحتاج تشغيل المحطة على مسار المنبّه (من الإعدادات)"
                !ok -> "تعذّر كتم القناة على هذا الجهاز (مستوى الصوت ثابت)"
                want -> "القناة مكتومة • تسمع المحطة فقط"
                else -> "صوت القناة مسموع"
            },
        )
        return receiver.tvMuted
    }

    fun setStationVolume(v: Float) {
        player?.setVolume(v)
        currentStation?.let { prefs.setStationVolume(it.url, v) }
    }

    // ------------------------------------------------------------------ engine host

    private inner class EngineHost : SyncEngine.Host {
        override val stationUrl get() = currentStation?.url
        override var audioDelayMs: Int
            get() = player?.audioDelayMs ?: 0
            set(v) { player?.audioDelayMs = v }
        override val calibMs get() = prefs.calibMs
        override val sourceHearsStation get() = source?.hearsStation == true
        override val tvMuted get() = receiver.tvMuted
        override fun setTapping(on: Boolean) { player?.setTapping(on) }
        override fun setStationVolume(v: Float) { onMain { player?.setVolume(v) } }
        override fun stationVolume(): Float = onMainGet { player?.volume ?: 1f }
        override fun muteTv(mute: Boolean) = receiver.muteTv(mute)
        override fun pauseTv(ms: Long) = receiver.pauseFor(ms).ok
        override fun rememberDelay(ms: Int) { currentStation?.let { prefs.setStationDelay(it.url, ms) } }
        override fun addTimeshift(ms: Long) { prefs.timeshiftMs = prefs.timeshiftMs + ms.toInt() }
        override val muteAfterSync get() = prefs.muteTvAfterSync
        override fun status(text: String) = this@SadaService.status(text)
        override fun result(text: String, ok: Boolean) = this@SadaService.result(text, ok)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun <T> onMainGet(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var value: T? = null
        onMain { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    // ------------------------------------------------------------------ status & notification

    private fun status(text: String) {
        SLog.i("status: $text")
        lastStatus = text
        main.post { listener?.onStatus(text) }
    }

    private fun result(text: String, ok: Boolean) {
        SLog.i("result: $text")
        lastResult = text
        updateNotification(text)
        main.post { listener?.onResult(text, ok) }
    }

    private fun startFg(types: Int) {
        runCatching {
            startForeground(NOTIF_ID, buildNotification(lastResult), types)
            fgTypes = types
        }.onFailure { SLog.e("startForeground($types)", it) }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("sada", "Sada", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, "sada")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Sada")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) =
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text)) }

    /** True if Sada may open its consent dialog from the background. */
    val canDrawOverlays get() = Settings.canDrawOverlays(this)
}
