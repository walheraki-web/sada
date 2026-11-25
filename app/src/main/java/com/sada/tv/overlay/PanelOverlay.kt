package com.sada.tv.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import com.sada.tv.SadaService
import com.sada.tv.audio.LevelHistory

/**
 * The panel drawn over the live channel: a list of rows driven by the remote
 * (▲▼ move, ◂ ▸ change, OK runs, Back closes) next to a live status area with the
 * channel's and the station's sound drawn as two traces.
 *
 * It is shown by [com.sada.tv.PanelKeyService] as an accessibility overlay, so
 * the TV app stays in front and keeps playing underneath.
 */
@UnstableApi
class PanelOverlay(context: Context, private val onClose: () -> Unit) : FrameLayout(context) {

    private class Row(
        val label: String,
        val value: (SadaService) -> String,
        val change: ((SadaService, Int) -> Unit)? = null,
        val ok: ((SadaService) -> Unit)? = null,
    )

    private val svc get() = SadaService.instance

    private val rows: List<Row> = listOf(
        Row("تشغيل", { if (it.playing) "يعمل" else "متوقف" }, ok = { it.togglePlay() }),
        Row("المصدر", { it.selectedStation()?.name ?: "لم تختر مصدرًا" },
            change = { s, d -> s.stepStation(d) }, ok = { s -> s.selectedStation()?.let { s.play(it) } }),
        Row("المزامنة التلقائية", { if (it.prefs.autoSync) "مفعّلة" else "متوقفة" },
            change = { s, _ -> s.setAutoSync(!s.prefs.autoSync) }, ok = { s -> s.setAutoSync(!s.prefs.autoSync) }),
        Row("مزامنة الآن", { "اضغط" }, ok = { it.syncNow() }),
        Row("مزامنة بنقرتين", { if (it.tapStage == 0) "اضغط (1/2)" else "عند سماعها في صوتك (2/2)" },
            ok = { it.twoTap() }),
        Row("تأخير صوتك", { "%+.1f ث".format(it.audioDelayMs / 1000.0) },
            change = { s, d -> s.nudgeAudioDelay(500 * d) }),
        Row("ضبط دقيق", { "%+.2f ث".format(it.audioDelayMs / 1000.0) },
            change = { s, d -> s.nudgeAudioDelay(50 * d) }),
        Row("الصوت", { if (it.prefs.hearMode == "mine") "صوتي" else "صوت القناة" },
            change = { s, _ -> s.setHearMode(if (s.prefs.hearMode == "mine") "channel" else "mine") },
            ok = { s -> s.setHearMode(if (s.prefs.hearMode == "mine") "channel" else "mine") }),
        Row("طريقة تأخير الصورة", { if (it.prefs.videoMethod == "menu") "قائمة الريسيفر" else "الإيقاف المؤقت" },
            change = { s, _ -> s.prefs.videoMethod = if (s.prefs.videoMethod == "menu") "pause" else "menu" }),
        Row("تجربة تأخير الصورة", { "اضغط" }, ok = { it.testPictureDelay() }),
        Row("تصفير تأخير الصورة", { "اضغط" }, ok = { it.resetPictureDelay() }),
        Row("فحص الريسيفر", { "اضغط" }, ok = { it.scanReceiver() }),
        Row("تسجيل صوت القناة وسماعه", { "اضغط" }, ok = { it.recordAndListen() }),
        Row("زر الإيقاف", { it.pauseKeysLabel() }, change = { s, _ -> s.cyclePauseKeys() }),
        Row("إرسال الأزرار", { if (it.prefs.keyMethod == "adb") "ADB" else "مباشر" },
            change = { s, _ -> s.prefs.keyMethod = if (s.prefs.keyMethod == "adb") "media" else "adb" }),
        Row("معايرة", { "%+d م.ث".format(it.prefs.calibMs) }, change = { s, d -> s.nudgeCalib(20 * d) }),
        Row("إغلاق", { "" }, ok = { onClose() }),
    )

    private var selected = 0
    private val rowViews = ArrayList<Pair<LinearLayout, TextView>>()
    private val menu = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val stationName = text(20f, Color.WHITE, bold = true)
    private val phaseView = text(14f, ACCENT)
    private val graph = GraphView(context)
    private val offsetView = text(13f, TEXT)
    private val delayView = text(13f, TEXT)
    private val captureView = text(13f, TEXT)
    private val receiverView = text(13f, TEXT)
    private val actionView = text(13f, MUTED).apply { maxLines = 3 }

    init {
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(18).toFloat() }
        }
        // menu column (right)
        val menuCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        menuCol.addView(text(24f, ACCENT, bold = true).apply { text = "صدى" })
        for (r in rows) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(5), dp(12), dp(5))
            }
            val l = text(14f, Color.WHITE).apply { text = r.label }
            val v = text(13f, ACCENT).apply { gravity = Gravity.END; textDirection = View.TEXT_DIRECTION_RTL }
            line.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            menu.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(2), 0, dp(2))
            })
            rowViews += line to v
        }
        menuCol.addView(menu)
        // info column (left)
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(34), dp(8), 0)
        }
        info.addView(stationName)
        info.addView(phaseView)
        info.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply {
            setMargins(0, dp(10), 0, dp(10))
        })
        for (v in listOf(offsetView, delayView, captureView, receiverView, actionView)) info.addView(v)

        panel.addView(menuCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f))

        val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(panel)
        outer.addView(text(12f, MUTED).apply {
            text = "▲▼ تنقّل      ◂ ▸ تغيير      OK تنفيذ      رجوع إغلاق"
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        val w = (resources.displayMetrics.widthPixels * 0.66f).toInt()
        addView(outer, LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            marginEnd = dp(24)
        })
        refresh()
    }

    private val ticker = object : Runnable {
        override fun run() { refresh(); postDelayed(this, 300) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    /** Handles a remote key; returns true if consumed. */
    fun onKey(code: Int): Boolean {
        val s = svc
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> selected = (selected - 1 + rows.size) % rows.size
            KeyEvent.KEYCODE_DPAD_DOWN -> selected = (selected + 1) % rows.size
            KeyEvent.KEYCODE_DPAD_RIGHT -> s?.let { rows[selected].change?.invoke(it, +1) }
            KeyEvent.KEYCODE_DPAD_LEFT -> s?.let { rows[selected].change?.invoke(it, -1) }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val r = rows[selected]
                val ok = r.ok
                when {
                    ok == null -> s?.let { r.change?.invoke(it, +1) }
                    s != null -> ok(s)
                    else -> onClose() // without the service only "close" means anything
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> onClose()
            else -> return false
        }
        refresh()
        return true
    }

    private fun refresh() {
        val s = svc
        rowViews.forEachIndexed { i, (line, v) ->
            line.background = if (i == selected) GradientDrawable().apply {
                setColor(SELECTED_BG); cornerRadius = dp(10).toFloat(); setStroke(dp(2), ACCENT)
            } else GradientDrawable().apply { setColor(ROW_BG); cornerRadius = dp(10).toFloat() }
            val r = rows[i]
            val value = if (s != null) r.value(s) else "…"
            v.text = if (r.change != null && value.isNotEmpty()) "◂  $value  ▸" else value
        }
        if (s == null) { phaseView.text = "صدى لا يعمل بعد…"; return }
        stationName.text = s.currentStation?.name ?: s.selectedStation()?.name ?: "لم تختر مصدرًا"
        phaseView.text = if (!s.playing) "متوقف" else if (!s.prefs.autoSync && !s.engine.busy && !s.engine.synced) "المزامنة متوقفة" else s.engine.phase.label
        offsetView.text = "الفرق المقاس: " + (s.engine.lastLagMs?.let { "%+.2f ث".format(it / 1000) } ?: "—")
        delayView.text = "تأخير صوتك الحالي: %+.2f ث".format(s.audioDelayMs / 1000.0)
        captureView.text = if (s.capturing) "الالتقاط: يعمل (${s.captureLabel})" else "الالتقاط: متوقف"
        captureView.setTextColor(if (s.capturing) TEXT else WARN)
        receiverView.text = "الريسيفر: ${s.receiverState}"
        actionView.text = "آخر إجراء: ${s.lastStatus}"
        graph.update(s.channelLevels, s.stationLevels)
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        if (bold) paint.isFakeBoldText = true
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
        textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Two rolling level traces: the channel (top) and the station as heard (bottom). */
    private class GraphView(context: Context) : View(context) {
        private var chan: List<Pair<Long, Float>> = emptyList()
        private var mine: List<Pair<Long, Float>> = emptyList()
        private var now = 0L
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22FFFFFF") }
        private val chanPaint = line(Color.parseColor("#9CC3FF"))
        private val minePaint = line(ACCENT)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED; textSize = 26f; textAlign = Paint.Align.RIGHT
        }

        private fun line(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c; style = Paint.Style.STROKE; strokeWidth = 3f
        }

        fun update(c: LevelHistory, m: LevelHistory) {
            now = SystemClock.elapsedRealtime()
            chan = c.window(now - SPAN, now)
            mine = m.window(now - SPAN, now)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRoundRect(0f, 0f, w, h, 18f, 18f, box)
            val half = h / 2
            draw(canvas, chan, 0f, half, w, chanPaint)
            draw(canvas, mine, half, half, w, minePaint)
            canvas.drawText("القناة", w - 12f, 30f, label)
            canvas.drawText("صوتك", w - 12f, half + 30f, label)
        }

        private fun draw(c: Canvas, pts: List<Pair<Long, Float>>, top: Float, h: Float, w: Float, p: Paint) {
            if (pts.size < 2) {
                c.drawLine(8f, top + h - 10f, w - 8f, top + h - 10f, p)
                return
            }
            val path = Path()
            pts.forEachIndexed { i, (t, db) ->
                // newest on the left (RTL reading), oldest on the right
                val x = w - 8f - (now - t).toFloat() / SPAN * (w - 16f)
                val x2 = w - x
                val y = top + h - 10f - ((db.coerceIn(-70f, 0f) + 70f) / 70f) * (h - 40f)
                if (i == 0) path.moveTo(x2, y) else path.lineTo(x2, y)
            }
            c.drawPath(path, p)
        }

        companion object { const val SPAN = 10_000L }
    }

    companion object {
        val BG = Color.parseColor("#EE0F1522")
        val ROW_BG = Color.parseColor("#33FFFFFF")
        val SELECTED_BG = Color.parseColor("#553D8BFD")
        val ACCENT = Color.parseColor("#3D8BFD")
        val TEXT = Color.parseColor("#E3EAF5")
        val MUTED = Color.parseColor("#A8B4C8")
        val WARN = Color.parseColor("#FF8A80")
    }
}
