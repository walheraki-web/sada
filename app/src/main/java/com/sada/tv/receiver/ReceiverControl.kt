package com.sada.tv.receiver

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.sada.tv.util.SLog
import com.sada.tv.adb.ShellAccess
import com.sada.tv.util.Prefs
import java.io.File
import java.lang.reflect.Method

/**
 * Talks to the receiver box.
 *
 *  - [pauseFor] is the timeshift: it pauses the live channel with a media key,
 *    waits exactly the requested time and resumes it, so the receiver's own
 *    timeshift buffer delays the picture (and its sound) by that amount. Media
 *    keys go through [AudioManager.dispatchMediaKeyEvent], which needs no special
 *    permission and reaches the TV app that holds the media session. This is the
 *    method the SadaSync reference app uses on these boxes.
 *    The channel's sound stops while it is paused, which the engine uses to
 *    verify that the pause really happened.
 *  - [muteTv] silences the channel's own sound while the station keeps playing.
 */
class ReceiverControl(context: Context, private val prefs: Prefs, private val shell: ShellAccess) {

    private val app = context.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)

    @Volatile var tvMuted = false
        private set

    /** Outcome of a pause: how long the channel was actually held, in ms. */
    data class PauseResult(val ok: Boolean, val heldMs: Long, val startedAtMs: Long, val error: String? = null)

    /**
     * Pauses the live channel for [ms] milliseconds and resumes it. Blocks the
     * calling (background) thread. The pause/resume keys come from settings.
     */
    fun pauseFor(ms: Long): PauseResult {
        if (ms <= 0) return PauseResult(true, 0, SystemClock.elapsedRealtime())
        return try {
            val start = System.nanoTime()
            val startedAt = SystemClock.elapsedRealtime()
            sendKey(prefs.pauseKey)
            val end = start + ms * 1_000_000
            // Sleep most of the wait, then spin the last few ms for accuracy.
            val sleepMs = (end - System.nanoTime()) / 1_000_000 - 15
            if (sleepMs > 0) Thread.sleep(sleepMs)
            while (System.nanoTime() < end) { /* spin */ }
            sendKey(prefs.resumeKey)
            val held = (System.nanoTime() - start) / 1_000_000
            SLog.i("pauseFor $ms ms: held $held ms (keys ${prefs.pauseKey}/${prefs.resumeKey})")
            PauseResult(true, held, startedAt)
        } catch (t: Throwable) {
            SLog.e("pauseFor", t)
            // Never leave the channel frozen.
            runCatching { sendKey(prefs.resumeKey) }
            PauseResult(false, 0, SystemClock.elapsedRealtime(), t.message ?: "فشل إرسال الأمر")
        }
    }

    private fun sendKey(code: Int) {
        if (prefs.keyMethod == "adb") {
            // `input keyevent` starts a small VM on each call (~0.3-0.6 s); the
            // sync engine's refine pass measures and trims that imprecision.
            shell.shell("input keyevent $code", 5000)
            return
        }
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /**
     * Sets the receiver's own picture-delay setting (0-60 s, 0.5 s steps) by
     * driving its menu over adb: AUDIO_TRACK twice, RED, all the way left to 0,
     * right once per 0.5 s, OK, BACK. Blocks ~5-10 s. Returns the delay set.
     */
    fun setMenuVideoDelay(ms: Int): Int {
        val steps = ((ms + 499) / 500).coerceIn(0, 120)
        val c = shell.connection()
        fun keys(vararg k: Int) { c.shell("input keyevent " + k.joinToString(" "), 30_000) }
        keys(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK); Thread.sleep(450)
        keys(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK); Thread.sleep(1100)
        keys(KeyEvent.KEYCODE_PROG_RED); Thread.sleep(900)
        keys(*IntArray(125) { KeyEvent.KEYCODE_DPAD_LEFT }); Thread.sleep(500)
        if (steps > 0) keys(*IntArray(steps) { KeyEvent.KEYCODE_DPAD_RIGHT })
        Thread.sleep(900L + steps * 15L)
        keys(KeyEvent.KEYCODE_DPAD_CENTER); Thread.sleep(900)
        keys(KeyEvent.KEYCODE_BACK)
        val set = steps * 500
        prefs.menuVideoDelayMs = set
        SLog.i("menu video delay set to $set ms")
        return set
    }

    /**
     * Mutes (or unmutes) the live channel's sound so only the station is heard.
     *
     * Main path: the TV app plays on the media stream, while Sada plays the
     * station on the alarm stream ([Prefs.stationOnAlarmStream]); setting the
     * media volume to 0 silences the channel only. Vendor HAL switches are tried
     * as well. Returns true only if something verifiably took effect.
     */
    fun muteTv(mute: Boolean): Boolean {
        var done = false
        if (prefs.stationOnAlarmStream) {
            runCatching { if (muteByMediaVolume(mute)) done = true }
                .onFailure { SLog.e("mute by volume", it) }
        }
        // Best-effort vendor switches (unverifiable; logged only).
        runCatching { am.setParameters("hal_param_tv_mute=" + if (mute) 1 else 0) }
        runCatching { droidLogicMute(mute) }
        if (done) tvMuted = mute
        SLog.i("muteTv($mute): ${if (done) "done" else "no method worked"}")
        return done
    }

    private fun muteByMediaVolume(mute: Boolean): Boolean {
        val stream = AudioManager.STREAM_MUSIC
        if (am.isVolumeFixed) {
            SLog.i("media volume is fixed on this box; cannot mute by volume")
            return false
        }
        return if (mute) {
            val cur = am.getStreamVolume(stream)
            if (cur > 0) prefs.savedMusicVolume = cur
            am.setStreamVolume(stream, 0, 0)
            am.getStreamVolume(stream) == 0
        } else {
            val saved = prefs.savedMusicVolume
            val target = if (saved > 0) saved else am.getStreamMaxVolume(stream) / 2
            am.setStreamVolume(stream, target, 0)
            prefs.savedMusicVolume = -1
            am.getStreamVolume(stream) > 0
        }
    }

    /** Makes sure the alarm stream (where the station plays) is audible. */
    fun ensureStationStreamAudible() {
        if (!prefs.stationOnAlarmStream) return
        runCatching {
            val s = AudioManager.STREAM_ALARM
            if (am.getStreamVolume(s) == 0) {
                am.setStreamVolume(s, (am.getStreamMaxVolume(s) * 0.7f).toInt().coerceAtLeast(1), 0)
            }
        }
    }

    // --- optional DroidLogic path, by reflection if the framework jar is present ---
    private var dlChecked = false
    private var dlInstance: Any? = null
    private var dlMethod: Method? = null

    private fun droidLogicMute(mute: Boolean): Boolean {
        if (!dlChecked) {
            dlChecked = true
            val jars = listOf(
                "/vendor/framework/droidlogic-tv.jar",
                "/system/framework/droidlogic-tv.jar",
                "/system/framework/droidlogic.jar",
            )
            var cls: Class<*>? = runCatching { Class.forName("com.droidlogic.app.tv.TvControlManager") }.getOrNull()
            if (cls == null) {
                for (p in jars) if (File(p).exists()) {
                    cls = runCatching {
                        dalvik.system.PathClassLoader(p, ClassLoader.getSystemClassLoader())
                            .loadClass("com.droidlogic.app.tv.TvControlManager")
                    }.getOrNull()
                    if (cls != null) break
                }
            }
            if (cls != null) runCatching {
                dlInstance = cls.getMethod("getInstance").invoke(null)
                dlMethod = cls.methods.firstOrNull {
                    it.name.equals("SetAmAudioPreMute", true) && it.parameterTypes.size == 1
                }
            }
        }
        val m = dlMethod ?: return false
        val inst = dlInstance ?: return false
        return runCatching { m.invoke(inst, if (mute) 1 else 0); true }.getOrDefault(false)
    }
}
