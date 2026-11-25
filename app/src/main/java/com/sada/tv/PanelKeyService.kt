package com.sada.tv

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.media3.common.util.UnstableApi
import com.sada.tv.overlay.PanelOverlay
import com.sada.tv.util.Prefs
import com.sada.tv.util.SLog
import kotlin.concurrent.thread

/**
 * Shows Sada's panel OVER the live channel as an accessibility overlay — the TV
 * app stays in front and keeps playing (an activity would cover and pause it).
 * Opens with RED pressed twice or a learned key; while open, it takes the remote's
 * arrows/OK/Back for the panel.
 */
@UnstableApi
class PanelKeyService : AccessibilityService() {

    object Config {
        @Volatile var panelKey: Int = -1
        @Volatile var learnMode: Boolean = false
    }

    companion object {
        @Volatile var instance: PanelKeyService? = null; private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastRed = 0L
    private var overlay: PanelOverlay? = null
    @Volatile private var visible = false

    private val keepAlive = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        thread(isDaemon = true) { runCatching { Config.panelKey = Prefs(this).panelKey } }
        ensureSada()
        SLog.i("panel service connected")
    }

    override fun onDestroy() {
        hide()
        runCatching { unbindService(keepAlive) }
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Starts Sada's service and keeps it bound while this accessibility service lives. */
    private fun ensureSada() {
        val i = Intent(this, SadaService::class.java)
        runCatching { startForegroundService(i) }.onFailure { SLog.e("start sada", it) }
        runCatching { bindService(i, keepAlive, BIND_AUTO_CREATE) }
    }

    fun show() {
        main.post {
            if (overlay != null) return@post
            if (SadaService.instance == null) ensureSada()
            val v = PanelOverlay(this) { hide() }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            runCatching { getSystemService(WindowManager::class.java).addView(v, lp) }
                .onSuccess { overlay = v; visible = true }
                .onFailure { SLog.e("panel addView", it) }
        }
    }

    fun hide() {
        visible = false
        main.post {
            val v = overlay ?: return@post
            runCatching { getSystemService(WindowManager::class.java).removeView(v) }
            overlay = null
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (visible) {
            if (!isPanelKey(code)) return false
            if (event.action == KeyEvent.ACTION_DOWN) main.post { overlay?.onKey(code) }
            return true // the TV app must not also act on it
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (Config.learnMode) {
            if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_HOME && code != KeyEvent.KEYCODE_POWER) {
                Config.panelKey = code
                Config.learnMode = false
                thread(isDaemon = true) { runCatching { Prefs(this).panelKey = code } }
            }
            return false
        }
        if (Config.panelKey != -1 && code == Config.panelKey) { show(); return true }
        if (code == KeyEvent.KEYCODE_PROG_RED) {
            val t = event.eventTime
            if (t - lastRed in 1..700) { lastRed = 0; show(); return true }
            lastRed = t
        }
        return false
    }

    private fun isPanelKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> true
        else -> false
    }
}
