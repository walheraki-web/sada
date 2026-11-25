package com.sada.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.sada.tv.ui.MainActivity
import com.sada.tv.util.Prefs
import kotlin.concurrent.thread

/**
 * Opens Sada's panel over the live channel: RED pressed twice, or one key the
 * user teaches it. onKeyEvent is on the input path: volatile reads only, no disk,
 * never consumes the key.
 */
class PanelKeyService : AccessibilityService() {

    object Config {
        @Volatile var panelKey: Int = -1
        @Volatile var learnMode: Boolean = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastRed = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        thread(isDaemon = true) { runCatching { Config.panelKey = Prefs(this).panelKey } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val code = event.keyCode
        if (Config.learnMode) {
            if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_HOME && code != KeyEvent.KEYCODE_POWER) {
                Config.panelKey = code
                Config.learnMode = false
                thread(isDaemon = true) { runCatching { Prefs(this).panelKey = code } }
            }
            return false
        }
        if (Config.panelKey != -1 && code == Config.panelKey) { open(); return false }
        if (code == KeyEvent.KEYCODE_PROG_RED) {
            val t = event.eventTime
            if (t - lastRed in 1..700) { lastRed = 0; open() } else lastRed = t
        }
        return false
    }

    private fun open() {
        main.post {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
    }
}
