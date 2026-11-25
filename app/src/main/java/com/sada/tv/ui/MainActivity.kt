package com.sada.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.sada.tv.PanelKeyService
import com.sada.tv.SadaService
import com.sada.tv.util.Playlist
import com.sada.tv.util.Prefs
import com.sada.tv.util.SLog
import com.sada.tv.util.Station

/**
 * Remote-friendly side panel over the live channel. Built in code so the whole
 * screen reads in one place. Everything happens in [SadaService]; this only
 * shows state and forwards clicks.
 */
@UnstableApi
class MainActivity : android.app.Activity(), SadaService.Listener {

    private companion object { const val REQ_PICK_PLAYLIST = 91 }

    private lateinit var prefs: Prefs
    private var service: SadaService? = null
    private lateinit var resultView: TextView
    private lateinit var statusView: TextView
    private lateinit var captureView: TextView
    private lateinit var delayView: TextView
    private lateinit var calibView: TextView
    private lateinit var timeshiftView: TextView
    private lateinit var keysButton: Button
    private lateinit var muteButton: Button
    private lateinit var stationsBox: LinearLayout
    private var stationFilter = ""

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as SadaService.LocalBinder).service.also { it.listener = this@MainActivity }
            refreshValues()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        SLog.init(this)
        setContentView(buildUi())
        requestRuntimePermissions()
        val intent = Intent(this, SadaService::class.java)
        startForegroundService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    override fun onDestroy() {
        service?.listener = null
        runCatching { unbindService(conn) }
        super.onDestroy()
    }

    private fun requestRuntimePermissions() {
        val wanted = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)
        val need = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) runCatching { requestPermissions(need.toTypedArray(), 42) }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#E610141F")); cornerRadius = 18f
            }
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        panel.addView(title("صدى Sada — $version"))
        resultView = label(prefs.sp.getString("lastResult", "لم تُجرَ مزامنة بعد")!!).apply { textSize = 15f }
        statusView = label("—")
        panel.addView(resultView)
        panel.addView(statusView)

        panel.addView(row(
            button("مزامنة الآن") { service?.syncNow() },
            button("إلغاء") { service?.cancelSync() },
            button("إيقاف") { service?.stopPlayback() },
        ))
        muteButton = button("كتم صوت القناة") { service?.toggleMuteTv(); refreshValues() }
        panel.addView(muteButton)

        // --- channel sound ---
        panel.addView(section("صوت القناة (للمزامنة)"))
        captureView = label("—")
        panel.addView(captureView)
        panel.addView(row(
            button("التقاط صوت القناة") { service?.requestChannelCapture(); refreshLater() },
            button("استخدم الميكروفون") { service?.useMicrophone(); refreshLater() },
        ))
        panel.addView(label("عند أول مرة تظهر نافذة «بدء التسجيل/البث»: اختر «ابدأ الآن». هذا يسمح لصدى بسماع صوت القناة فقط، دون صوت المحطة."))

        // --- delays ---
        panel.addView(section("التأخير"))
        delayView = label("—")
        panel.addView(delayView)
        panel.addView(row(
            button("محطة −1ث") { service?.nudgeAudioDelay(-1000); refreshValues() },
            button("−0.1") { service?.nudgeAudioDelay(-100); refreshValues() },
            button("+0.1") { service?.nudgeAudioDelay(+100); refreshValues() },
            button("محطة +1ث") { service?.nudgeAudioDelay(+1000); refreshValues() },
        ))
        timeshiftView = label("—")
        panel.addView(timeshiftView)
        panel.addView(row(
            button("أخّر الصورة 0.5ث") { service?.delayPicture(500); refreshLater() },
            button("أخّر الصورة 2ث") { service?.delayPicture(2000); refreshLater() },
        ))
        panel.addView(row(
            button("صوت المحطة −") { adjustVolume(-0.1f) },
            button("صوت المحطة +") { adjustVolume(+0.1f) },
        ))

        // --- timeshift settings ---
        panel.addView(section("تأخير الصورة (الإيقاف المؤقت للريسيفر)"))
        keysButton = button("زر الإيقاف: …") { service?.let { keysButton.text = "زر الإيقاف: " + it.cyclePauseKeys() } }
        panel.addView(keysButton)
        panel.addView(button("اختبار الإيقاف (5 ث)") { service?.testPause() })
        panel.addView(label("صدى يؤخّر الصورة بإيقاف القناة مؤقتًا ثم استئنافها بعد المدة المطلوبة بالضبط. إن لم تتجمّد الصورة في الاختبار، بدّل زر الإيقاف وجرّب مجددًا."))
        calibView = label("—")
        panel.addView(calibView)
        panel.addView(row(
            button("معايرة −20") { service?.nudgeCalib(-20); refreshValues() },
            button("معايرة +20") { service?.nudgeCalib(+20); refreshValues() },
        ))

        // --- options ---
        panel.addView(section("خيارات"))
        panel.addView(check("مراقبة المزامنة وتصحيحها تلقائيًا", prefs.monitor) { prefs.monitor = it })
        panel.addView(check("إعادة المزامنة بعد انقطاع البث", prefs.resyncAfterDrop) { prefs.resyncAfterDrop = it })
        panel.addView(check("كتم القناة بعد المزامنة", prefs.muteTvAfterSync) { prefs.muteTvAfterSync = it })
        panel.addView(check("تشغيل المحطة على مسار المنبّه (يلزم للكتم؛ يُطبَّق بعد إعادة فتح صدى)", prefs.stationOnAlarmStream) {
            prefs.stationOnAlarmStream = it
        })

        // --- remote key ---
        panel.addView(section("زر فتح اللوحة"))
        panel.addView(row(
            button("تفعيل خدمة الزر") {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { toast("افتح: الإعدادات ▸ إمكانية الوصول ▸ Sada") }
            },
            button("تعليم زر") {
                PanelKeyService.Config.learnMode = true
                toast("اضغط الآن الزر الذي تريده لفتح اللوحة")
            },
            button("الأحمر مرتين") {
                prefs.panelKey = -1; PanelKeyService.Config.panelKey = -1
                toast("الاختصار: الزر الأحمر مرتين")
            },
        ))
        panel.addView(button("تصدير السجل") {
            runCatching { toast("حُفظ في التنزيلات: ${SLog.export(this)}") }
                .onFailure { toast("تعذّر الحفظ: ${it.message}") }
        })

        // --- stations (last: may be long) ---
        panel.addView(section("المحطة — اختر واحدة"))
        panel.addView(addStationRow())
        panel.addView(importRow())
        panel.addView(searchRow())
        stationsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(stationsBox)
        refreshStations()

        val scroll = ScrollView(this).apply { addView(panel) }
        val lp = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.58f).toInt(),
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.END }
        frame.addView(scroll, lp)
        return frame
    }

    private fun adjustVolume(delta: Float) {
        val s = service ?: return
        val url = s.currentStation?.url ?: return
        val v = (prefs.stationVolume(url) + delta).coerceIn(0f, 1f)
        s.setStationVolume(v)
        toast("صوت المحطة ${(v * 100).toInt()}%")
    }

    private fun refreshLater() { statusView.postDelayed({ refreshValues() }, 1500) }

    private fun refreshValues() = runOnUiThread {
        val s = service ?: return@runOnUiThread
        captureView.text = if (s.captureActive) "يعمل: ${s.captureLabel}" else "غير مفعّل — اضغط «التقاط صوت القناة»"
        delayView.text = "تأخير المحطة: %.2f ث".format(s.audioDelayMs / 1000.0)
        timeshiftView.text = "الصورة مؤخّرة منذ آخر ضبط للقناة بحوالي %.1f ث".format(prefs.timeshiftMs / 1000.0)
        calibView.text = "معايرة: %+d م.ث (موجب = المحطة تُسمع متأخرة)".format(prefs.calibMs)
        keysButton.text = "زر الإيقاف: " + s.pauseKeysLabel()
        muteButton.text = if (s.receiver.tvMuted) "إلغاء كتم القناة" else "كتم صوت القناة"
        statusView.text = s.lastStatus
        resultView.text = s.lastResult
    }

    private fun searchRow(): View {
        val q = EditText(this).apply {
            hint = "ابحث عن محطة بالاسم…"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#80FFFFFF"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { stationFilter = e?.toString() ?: ""; refreshStations() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        forceIme(q)
        return row(q, button("مسح") { q.setText("") })
    }

    private fun refreshStations() {
        stationsBox.removeAllViews()
        val all = prefs.stations
        if (all.isEmpty()) {
            stationsBox.addView(label("لا توجد محطات بعد. أضف رابط بث أو استورد ملف m3u/cfg."))
            return
        }
        val q = stationFilter.trim()
        val shown = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
        stationsBox.addView(label("المحطات: ${shown.size} / ${all.size}"))
        for (st in shown.take(200)) {
            val play = button(st.name) { service?.play(st); refreshLater() }
            (play.layoutParams as LinearLayout.LayoutParams).weight = 1f
            stationsBox.addView(row(play, button("حذف") {
                prefs.stations = prefs.stations.filterNot { it == st }
                refreshStations()
            }))
        }
    }

    private fun addStationRow(): View {
        val name = EditText(this).apply {
            hint = "اسم المحطة"; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val url = EditText(this).apply {
            hint = "رابط البث (m3u8 أو mp3)"; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }
        forceIme(name); forceIme(url)
        return row(name, url, button("إضافة") {
            val n = name.text.toString().trim(); val u = url.text.toString().trim()
            if (n.isNotEmpty() && u.isNotEmpty()) {
                prefs.stations = prefs.stations.filterNot { it.url == u } + Station(n, u)
                name.setText(""); url.setText(""); refreshStations()
            }
        })
    }

    private fun importRow(): View {
        val src = EditText(this).apply {
            hint = "رابط أو مسار ملف m3u/cfg"; setTextColor(Color.WHITE)
            setText(prefs.sp.getString("lastImport", "") ?: "")
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }
        forceIme(src)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(button("اختر ملفًا من الفلاشة (cfg / m3u)") {
            runCatching {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    REQ_PICK_PLAYLIST,
                )
            }.onFailure { toast("تعذّر فتح متصفّح الملفات: ${it.message}") }
        })
        box.addView(row(src, button("استيراد") {
            val source = src.text.toString().trim()
            if (source.isEmpty()) { toast("أدخل رابطًا أو مسارًا"); return@button }
            prefs.sp.edit().putString("lastImport", source).apply()
            importStations {
                if (source.startsWith("http", true)) {
                    val c = (java.net.URL(source).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
                    }
                    c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                } else java.io.File(source).readText(Charsets.UTF_8)
            }
        }))
        return box
    }

    @Deprecated("plain Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_PLAYLIST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importStations { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
    }

    private fun importStations(read: () -> String) {
        toast("جارٍ الاستيراد…")
        Thread {
            val r = runCatching { Playlist.parse(read()) }
            runOnUiThread {
                r.onSuccess { parsed ->
                    if (parsed.isEmpty()) { toast("لم يُعثر على محطات في الملف"); return@onSuccess }
                    val existing = prefs.stations
                    val urls = existing.map { it.url }.toHashSet()
                    val merged = existing + parsed.filterNot { it.url in urls }
                    prefs.stations = merged
                    refreshStations()
                    toast("أُضيفت ${merged.size - existing.size} محطة (الإجمالي ${merged.size})")
                }.onFailure { toast("تعذّر الاستيراد: ${it.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ service callbacks

    override fun onStatus(text: String) = runOnUiThread { statusView.text = text }

    override fun onResult(text: String, ok: Boolean) = runOnUiThread {
        resultView.text = text
        resultView.setTextColor(Color.parseColor(if (ok) "#7CE0A3" else "#FFB4A8"))
        prefs.sp.edit().putString("lastResult", text).apply()
        refreshValues()
    }

    // ------------------------------------------------------------------ view helpers

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setTextColor(Color.parseColor("#3D8BFD")); setPadding(0, 0, 0, 10)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Color.parseColor("#9DB4D6")); setPadding(0, 16, 0, 4)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(Color.parseColor("#D2DCEA")); setPadding(0, 2, 0, 2)
    }

    private fun buttonBg(focused: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 14f
        setColor(Color.parseColor(if (focused) "#3D8BFD" else "#1B2333"))
        setStroke(if (focused) 5 else 3, Color.parseColor(if (focused) "#BFD8FF" else "#3D8BFD"))
    }

    /** Make the on-screen keyboard open for a text field on a TV box. */
    private fun forceIme(e: EditText) {
        e.isFocusableInTouchMode = true
        e.showSoftInputOnFocus = true
        val show = {
            e.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }
        e.setOnClickListener { show() }
        e.setOnFocusChangeListener { _, has -> if (has) e.post { show() } }
    }

    private fun button(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f
        setTextColor(Color.WHITE)
        background = buttonBg(false)
        gravity = Gravity.CENTER
        minHeight = 0; minimumHeight = 0
        setPadding(22, 12, 22, 12)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(6, 6, 6, 6) }
        isFocusable = true
        isFocusableInTouchMode = false
        setOnFocusChangeListener { v, has -> v.background = buttonBg(has) }
        setOnClickListener(onClick)
    }

    private fun check(t: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t; isChecked = initial; setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}
