package com.sada.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import com.sada.tv.adb.ShellAccess
import com.sada.tv.audio.LevelHistory
import com.sada.tv.audio.StationPlayer
import com.sada.tv.capture.CaptureProtocol
import com.sada.tv.capture.HelperCaptureSource
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The always-on part of Sada: plays the station, captures the channel's sound,
 * keeps both locked together (the receiver's own timeshift or picture-delay
 * menu + a precise station delay), and offers the panel's actions. A foreground
 * service; the panel (an overlay drawn by [PanelKeyService]) talks to it
 * through [instance].
 */
@UnstableApi
class SadaService : Service() {

    companion object {
        const val ACTION_PROJECTION = "com.sada.tv.PROJECTION"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 15_000L
        private const val CHECK_EVERY_MS = 90_000L
        private const val RETRY_AFTER_FAIL_MS = 60_000L

        @Volatile var instance: SadaService? = null; private set
    }

    inner class LocalBinder : android.os.Binder() { val service get() = this@SadaService }

    interface Listener {
        fun onStatus(text: String) {}
        fun onResult(text: String, ok: Boolean) {}
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor { Thread(it, "sada-bg").apply { isDaemon = true } }

    lateinit var prefs: Prefs; private set
    lateinit var shell: ShellAccess; private set
    lateinit var receiver: ReceiverControl; private set
    private var player: StationPlayer? = null
    private val tvTrack = FeatureTrack()
    private val stationTrack = FeatureTrack()
    lateinit var engine: SyncEngine; private set
    val channelLevels = LevelHistory()
    val stationLevels = LevelHistory()

    private var projection: MediaProjection? = null
    @Volatile private var source: TvAudioSource? = null
    @Volatile var capturing = false; private set

    @Volatile var currentStation: Station? = null; private set
    @Volatile var playing = false; private set
    @Volatile var lastResult = "لم تُجرَ مزامنة بعد"; private set
    @Volatile var lastStatus = "—"; private set
    @Volatile var receiverState = "لم يُفحص بعد"; private set
    var listener: Listener? = null

    private var fgTypes = 0
    private var lastFailAt = 0L
    private var lastCheckAt = 0L

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        SLog.init(this)
        prefs = Prefs(this)
        shell = ShellAccess(this, prefs)
        receiver = ReceiverControl(this, prefs, shell)
        if (prefs.savedMusicVolume > 0) runCatching { receiver.muteTv(false) } // left muted by a previous run
        engine = SyncEngine(EngineHost(), tvTrack, stationTrack)
        try {
            player = StationPlayer(
                context = this,
                onAlarmStream = prefs.stationOnAlarmStream,
                onTap = { pcm, n, ch, rate, heardAt ->
                    stationTrack.push(pcm, n, ch, rate, heardAt)
                    stationLevels.push(pcm, n, heardAt.toLong())
                },
                onStall = { ms -> engine.onStationStall(ms) },
                onRecovered = {
                    if (playing && prefs.resyncAfterDrop) { engine.forgetSync(); action("عاد البث • إعادة مزامنة") }
                },
                onStatus = { status(it) },
            )
        } catch (t: Throwable) {
            SLog.e("player init", t)
            lastResult = "تعذّر تهيئة المشغّل: ${t.message}"
        }
        if (prefs.helperMode != null) receiverState = "جاهز (${CaptureProtocol.Mode.byId(prefs.helperMode)?.label})"
        createChannel()
        startFg(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        main.postDelayed(tick, TICK_MS)
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
        main.removeCallbacksAndMessages(null)
        stopPlayback()
        projection?.runCatching { stop() }
        player?.runCatching { release() }
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ continuous sync

    private val tick = object : Runnable {
        override fun run() {
            main.postDelayed(this, TICK_MS)
            if (!playing || !prefs.autoSync || !capturing || engine.busy) return
            val now = SystemClock.elapsedRealtime()
            if (!engine.synced) {
                if (now - lastFailAt > RETRY_AFTER_FAIL_MS) engine.syncNow()
            } else if (now - lastCheckAt > CHECK_EVERY_MS) {
                lastCheckAt = now
                engine.check()
            }
        }
    }

    // ------------------------------------------------------------------ capture

    val captureLabel get() = source?.label ?: "—"

    private fun buildSource(): TvAudioSource? = when (prefs.captureMode) {
        "helper" -> CaptureProtocol.Mode.byId(prefs.helperMode)?.let { HelperCaptureSource(this, shell, it) }
        "playback" -> projection?.let { PlaybackCaptureSource(it) }
        "mic" -> MicSource()
        else -> null
    }

    /** (Re)starts channel capture on the background thread. */
    fun startCapture() {
        bg.execute {
            stopCaptureNow()
            val src = buildSource()
            if (src == null) {
                when (prefs.captureMode) {
                    "helper" -> status("نفّذ «فحص الريسيفر» أولًا ليجد صدى صوت القناة")
                    "playback" -> requestPlaybackConsent()
                }
                return@execute
            }
            try {
                if (src is MicSource && Build.VERSION.SDK_INT >= 30) {
                    startFg(fgTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
                src.start({ pcm, n, ch, rate, t ->
                    tvTrack.push(pcm, n, ch, rate, t.toDouble())
                    channelLevels.push(pcm, n, t)
                    recorder?.add(pcm, n)
                }) { err ->
                    capturing = false
                    SLog.e("capture died", err)
                    main.postDelayed({ if (source === src && playing) startCapture() }, 3000)
                }
                source = src
                capturing = true
                SLog.i("capture: ${src.label}")
            } catch (t: Throwable) {
                capturing = false
                SLog.e("capture start", t)
                status("تعذّر التقاط صوت القناة: ${t.message}")
            }
        }
    }

    private fun stopCaptureNow() {
        capturing = false
        source?.runCatching { stop() }
        source = null
    }

    /**
     * Finds how to hear the channel on this receiver: connects to its adb (the TV
     * asks once to allow it), then tries each capture method while the channel
     * plays and keeps the first that carries sound.
     */
    fun scanReceiver() {
        bg.execute {
            receiverState = "جارٍ الفحص…"
            action("جارٍ فحص الريسيفر… إن ظهرت نافذة «السماح بالتصحيح» اختر «السماح دائمًا»")
            try {
                shell.connection()
            } catch (t: Throwable) {
                receiverState = "لا يوجد ADB"
                action(t.message ?: "تعذّر الاتصال بالريسيفر")
                return@execute
            }
            stopCaptureNow()
            val wasMuted = receiver.tvMuted
            if (wasMuted) receiver.muteTv(false)
            val vol = playerVolume()
            setPlayerVolume(0f)
            val report = StringBuilder()
            var chosen: CaptureProtocol.Mode? = null
            try {
                for (mode in CaptureProtocol.Mode.entries) {
                    val db = runCatching { HelperCaptureSource.probe(this, shell, mode, 3500) }
                    val line = db.fold({ "${mode.label}: %.0f dB".format(it) }, { "${mode.label}: ${it.message}" })
                    SLog.i("scan $line")
                    report.append(line).append('\n')
                    status("فحص: $line")
                    if ((db.getOrNull() ?: -200.0) > -60.0) { chosen = mode; break }
                }
            } finally {
                setPlayerVolume(vol)
                if (wasMuted) receiver.muteTv(true)
            }
            if (chosen != null) {
                prefs.captureMode = "helper"
                prefs.helperMode = chosen.id
                receiverState = "جاهز (${chosen.label})"
                action("✔ وُجد صوت القناة: ${chosen.label}")
                startCapture()
            } else {
                receiverState = "لا يصل صوت القناة"
                action("✘ لم يحمل أي أسلوب صوت القناة. تأكد أن القناة تعمل بصوت.\n$report")
            }
        }
    }

    /** Uses Android's playback capture instead (needs a one-time consent). */
    fun usePlaybackCapture() {
        prefs.captureMode = "playback"
        if (projection != null) startCapture() else requestPlaybackConsent()
    }

    fun useMicrophone() {
        prefs.captureMode = "mic"
        startCapture()
    }

    private fun requestPlaybackConsent() {
        runCatching {
            startActivity(Intent(this, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { SLog.e("projection activity", it); status("افتح تطبيق صدى للموافقة على الالتقاط") }
    }

    private fun onProjectionGranted(code: Int, data: Intent) {
        startFg(fgTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = runCatching { mpm.getMediaProjection(code, data) }.onFailure { SLog.e("getMediaProjection", it) }.getOrNull()
            ?: run { status("تعذّر بدء الالتقاط"); return }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    if (projection === p) {
                        projection = null
                        if (source is PlaybackCaptureSource) bg.execute { stopCaptureNow() }
                    }
                }
            }
        }, main)
        projection?.runCatching { stop() }
        projection = p
        prefs.captureMode = "playback"
        startCapture()
    }

    // ------------------------------------------------------------------ playback

    val stations get() = prefs.stations

    /** The station the panel's "source" row points at (the playing one, or the last). */
    fun selectedStation(): Station? =
        currentStation ?: prefs.stations.firstOrNull { it.url == prefs.lastStationUrl } ?: prefs.stations.firstOrNull()

    /** Steps the source by [delta]; switches playback if something is playing. */
    fun stepStation(delta: Int) {
        val list = prefs.stations
        if (list.isEmpty()) return
        val cur = list.indexOfFirst { it.url == selectedStation()?.url }.coerceAtLeast(0)
        val next = list[Math.floorMod(cur + delta, list.size)]
        prefs.lastStationUrl = next.url
        if (playing) play(next) else currentStation = null
    }

    fun togglePlay() {
        if (playing) stopPlayback() else selectedStation()?.let { play(it) } ?: status("أضف محطة من تطبيق صدى")
    }

    fun play(station: Station) {
        val p = player ?: run { status("المشغّل غير جاهز"); return }
        engine.cancel()
        engine.forgetSync()
        currentStation = station
        prefs.lastStationUrl = station.url
        playing = true
        receiver.ensureStationStreamAudible()
        p.audioDelayMs = prefs.stationDelay(station.url)
        p.setTapping(true)
        p.play(station.url)
        applyHearMode()
        action("يشغّل ${station.name}")
        if (!capturing) startCapture()
        lastFailAt = 0
        if (prefs.autoSync) main.postDelayed({ if (playing && capturing && !engine.busy) engine.syncNow() }, 5000)
    }

    fun stopPlayback() {
        playing = false
        engine.cancel()
        engine.forgetSync()
        player?.setTapping(false)
        player?.stop()
        bg.execute { stopCaptureNow() }
        if (receiver.tvMuted) receiver.muteTv(false)
        action("متوقف")
    }

    // ------------------------------------------------------------------ panel actions

    fun syncNow() {
        if (!playing) { status("شغّل محطة أولًا"); return }
        if (!capturing) { startCapture(); status("لا يصل صوت القناة بعد — نفّذ «فحص الريسيفر»"); return }
        engine.syncNow()
    }

    fun setAutoSync(on: Boolean) {
        prefs.autoSync = on
        if (!on) engine.cancel()
        action(if (on) "المزامنة التلقائية مفعّلة" else "المزامنة التلقائية متوقفة")
    }

    // two-tap sync: press when a moment is heard on the channel, again when it is heard in the station
    @Volatile var tapStage = 0; private set
    private var tapT1 = 0L
    private var tapWasMuted = false

    fun twoTap() {
        if (!playing) { status("شغّل محطة أولًا"); return }
        if (tapStage == 0) {
            tapWasMuted = receiver.tvMuted
            if (tapWasMuted) receiver.muteTv(false)
            setPlayerVolume(prefs.stationVolume(currentStation!!.url))
            tapT1 = SystemClock.elapsedRealtime()
            tapStage = 1
            action("اضغط مرة ثانية عند سماع اللحظة نفسها في صوتك")
        } else {
            val lag = (SystemClock.elapsedRealtime() - tapT1).toDouble()
            tapStage = 0
            if (tapWasMuted) receiver.muteTv(true)
            applyHearMode()
            action("فرق يدوي %+.2f ث".format(lag / 1000))
            engine.applyManual(lag)
        }
    }

    val audioDelayMs get() = player?.audioDelayMs ?: 0

    fun nudgeAudioDelay(deltaMs: Int) {
        val p = player ?: return
        val next = (p.audioDelayMs + deltaMs).coerceIn(0, SyncPolicy.MAX_AUDIO_DELAY_MS)
        p.audioDelayMs = next
        currentStation?.let { prefs.setStationDelay(it.url, next) }
        action("تأخير صوتك %+.2f ث".format(next / 1000.0))
    }

    /** "mine" = hear the station (channel muted); "channel" = hear the channel (station silent). */
    fun setHearMode(mode: String) {
        prefs.hearMode = mode
        applyHearMode()
        action(if (mode == "mine") "تسمع صوتك" else "تسمع صوت القناة")
    }

    private fun applyHearMode() {
        val url = currentStation?.url
        if (prefs.hearMode == "mine") {
            if (url != null) setPlayerVolume(prefs.stationVolume(url))
            if (engine.synced || !prefs.autoSync) {
                if (!receiver.muteTv(true)) status("تعذّر كتم القناة على هذا الجهاز")
            }
        } else {
            setPlayerVolume(0f)
            receiver.muteTv(false)
        }
    }

    /** Picture-delay test: pause 5 s, or set 5 s through the receiver menu. */
    fun testPictureDelay() {
        bg.execute {
            if (prefs.videoMethod == "menu") {
                action("يفتح قائمة الريسيفر… يجب أن تظهر قائمة الصوت ثم نافذة تأخير الصورة")
                runCatching { receiver.setMenuVideoDelay(5000) }
                    .onSuccess { action("ضُبط تأخير الصورة 5 ث من القائمة") }
                    .onFailure { action("فشل: ${it.message}") }
            } else {
                action("ستتجمّد الصورة 5 ثوانٍ إن كان زر الإيقاف صحيحًا")
                val r = receiver.pauseFor(5000)
                if (r.ok) prefs.timeshiftMs = prefs.timeshiftMs + 5000
                action(if (r.ok) "أُرسل الإيقاف ثم الاستئناف. هل تجمّدت الصورة 5 ثوانٍ؟" else "فشل: ${r.error}")
            }
        }
    }

    fun resetPictureDelay() {
        bg.execute {
            engine.forgetSync()
            if (prefs.videoMethod == "menu") {
                runCatching { receiver.setMenuVideoDelay(0) }
                    .onSuccess { action("تأخير الصورة صُفّر") }
                    .onFailure { action("تعذّر التصفير: ${it.message}") }
            } else {
                prefs.timeshiftMs = 0
                action("التأخير بالإيقاف لا يُلغى إلا بإعادة اختيار القناة في الريسيفر؛ صُفّر العدّاد")
            }
        }
    }

    // record & listen: proves the capture really carries the channel
    private class Recorder(val maxSamples: Int) {
        val data = ShortArray(maxSamples)
        @Volatile var n = 0
        var channels = 1
        var rate = 16000
        fun add(pcm: ShortArray, count: Int) {
            val k = minOf(count, maxSamples - n)
            if (k > 0) { System.arraycopy(pcm, 0, data, n, k); n += k }
        }
    }
    @Volatile private var recorder: Recorder? = null

    fun recordAndListen() {
        bg.execute {
            if (!capturing) { action("لا يعمل الالتقاط — نفّذ «فحص الريسيفر»"); return@execute }
            val src = source ?: return@execute
            val (rate, ch) = when (src) {
                is HelperCaptureSource -> src.mode.rate to src.mode.channels
                else -> 16000 to 1
            }
            val wasMuted = receiver.tvMuted
            if (wasMuted) receiver.muteTv(false)
            val vol = playerVolume()
            if (src.hearsStation) setPlayerVolume(0f)
            action("يسجّل صوت القناة 6 ثوانٍ…")
            val rec = Recorder(rate * ch * 6).apply { channels = ch; this.rate = rate }
            recorder = rec
            Thread.sleep(6200)
            recorder = null
            setPlayerVolume(vol)
            if (wasMuted) receiver.muteTv(true)
            if (rec.n == 0) { action("لم يُلتقط أي صوت"); return@execute }
            var s = 0.0
            for (i in 0 until rec.n) { val v = rec.data[i] / 32768.0; s += v * v }
            val db = 20 * log10(sqrt(s / rec.n) + 1e-9)
            action("يُسمعك التسجيل (مستوى %.0f dB)…".format(db))
            playPcm(rec.data, rec.n, rec.rate, rec.channels)
            action(if (db < -60) "التسجيل صامت تقريبًا: هذا الأسلوب لا يحمل صوت القناة" else "انتهى التسجيل (مستوى %.0f dB)".format(db))
        }
    }

    private fun playPcm(pcm: ShortArray, n: Int, rate: Int, channels: Int) {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()
        try {
            track.write(pcm, 0, n)
            track.play()
            Thread.sleep(n * 1000L / (rate * channels) + 300)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    fun nudgeCalib(deltaMs: Int) {
        prefs.calibMs = (prefs.calibMs + deltaMs).coerceIn(-1000, 1000)
        nudgeAudioDelay(-deltaMs)
    }

    fun cyclePauseKeys(): String {
        val pairs = listOf(85 to 85, 127 to 126, 126 to 126)
        val i = pairs.indexOf(prefs.pauseKey to prefs.resumeKey)
        val (pk, rk) = pairs[(i + 1) % pairs.size]
        prefs.pauseKey = pk; prefs.resumeKey = rk
        return pauseKeysLabel()
    }

    fun pauseKeysLabel(): String = when (prefs.pauseKey to prefs.resumeKey) {
        85 to 85 -> "تشغيل/إيقاف (85)"
        127 to 126 -> "إيقاف 127 / تشغيل 126"
        else -> "${prefs.pauseKey} / ${prefs.resumeKey}"
    }

    fun setStationVolume(v: Float) {
        currentStation?.let { prefs.setStationVolume(it.url, v) }
        if (prefs.hearMode == "mine") setPlayerVolume(v)
    }

    // ------------------------------------------------------------------ engine host

    private inner class EngineHost : SyncEngine.Host {
        override var audioDelayMs: Int
            get() = player?.audioDelayMs ?: 0
            set(v) { player?.audioDelayMs = v }
        override val calibMs get() = prefs.calibMs
        override val sourceHearsStation get() = source?.hearsStation == true
        override val tvMuted get() = receiver.tvMuted
        override val videoMethod get() = prefs.videoMethod
        override val menuVideoDelayMs get() = prefs.menuVideoDelayMs
        override fun setMenuVideoDelay(ms: Int) { receiver.setMenuVideoDelay(ms) }
        override fun setStationVolume(v: Float) = setPlayerVolume(v)
        override fun stationVolume(): Float = playerVolume()
        override fun muteTv(mute: Boolean) = receiver.muteTv(mute)
        override fun pauseTv(ms: Long) = receiver.pauseFor(ms).ok
        override fun rememberDelay(ms: Int) { currentStation?.let { prefs.setStationDelay(it.url, ms) } }
        override fun addTimeshift(ms: Long) { prefs.timeshiftMs = prefs.timeshiftMs + ms.toInt() }
        override val muteAfterSync get() = prefs.hearMode == "mine"
        override fun status(text: String) = this@SadaService.status(text)
        override fun result(text: String, ok: Boolean) {
            if (!ok) lastFailAt = SystemClock.elapsedRealtime()
            this@SadaService.result(text, ok)
        }
    }

    private fun setPlayerVolume(v: Float) = onMain { player?.setVolume(v) }
    private fun playerVolume(): Float {
        var v = 1f
        onMain { v = player?.volume ?: 1f }
        return v
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        latch.await(2, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ status

    /** Something the user did or should know (panel's "last action"). */
    private fun action(text: String) {
        status(text)
    }

    private fun status(text: String) {
        SLog.i("status: $text")
        lastStatus = text
        main.post { listener?.onStatus(text) }
    }

    private fun result(text: String, ok: Boolean) {
        SLog.i("result: $text")
        lastResult = text
        lastStatus = text
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
}
