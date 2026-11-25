package com.sada.tv.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Station(val name: String, val url: String)

/** Typed access to Sada's settings and per-station memory. */
class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("sada", Context.MODE_PRIVATE)

    // --- stations ---

    var stations: List<Station>
        get() = runCatching {
            val arr = JSONArray(sp.getString("stations", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Station(o.getString("name"), o.getString("url"))
            }
        }.getOrDefault(emptyList())
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
            sp.edit().putString("stations", arr.toString()).apply()
        }

    var lastStationUrl: String?
        get() = sp.getString("lastStationUrl", null)
        set(v) = sp.edit().putString("lastStationUrl", v).apply()

    /** Station audio delay last applied for a station (restored on play). */
    fun stationDelay(url: String): Int = sp.getInt("delay:$url", 0)
    fun setStationDelay(url: String, ms: Int) = sp.edit().putInt("delay:$url", ms).apply()

    /** Last measured raw lag for a station: centres the next search. */
    fun lastLag(url: String): Int? = if (sp.contains("lag:$url")) sp.getInt("lag:$url", 0) else null
    fun setLastLag(url: String, lag: Int) = sp.edit().putInt("lag:$url", lag).apply()

    fun stationVolume(url: String): Float = sp.getFloat("vol:$url", 1f)
    fun setStationVolume(url: String, v: Float) = sp.edit().putFloat("vol:$url", v).apply()

    // --- sync behaviour ---

    /** Keep checking the sync while playing and correct drift automatically. */
    var monitor: Boolean
        get() = sp.getBoolean("monitor", true)
        set(v) = sp.edit().putBoolean("monitor", v).apply()

    /** Re-sync automatically after the station reconnects. */
    var resyncAfterDrop: Boolean
        get() = sp.getBoolean("resyncAfterDrop", true)
        set(v) = sp.edit().putBoolean("resyncAfterDrop", v).apply()

    /** Mute the channel's own sound after a successful sync. */
    var muteTvAfterSync: Boolean
        get() = sp.getBoolean("muteTvAfterSync", true)
        set(v) = sp.edit().putBoolean("muteTvAfterSync", v).apply()

    /** Constant correction added to every measured lag (box/TV output latency), ms. */
    var calibMs: Int
        get() = sp.getInt("calibMs", 0)
        set(v) = sp.edit().putInt("calibMs", v).apply()

    // --- timeshift by pausing the receiver ---

    /** Key that pauses the live channel (85 = PLAY_PAUSE, 127 = PAUSE). */
    var pauseKey: Int
        get() = sp.getInt("pauseKey", 85)
        set(v) = sp.edit().putInt("pauseKey", v).apply()

    /** Key that resumes it (85 = PLAY_PAUSE, 126 = PLAY). */
    var resumeKey: Int
        get() = sp.getInt("resumeKey", 85)
        set(v) = sp.edit().putInt("resumeKey", v).apply()

    /** Total picture delay added by pauses since the channel was last tuned (info only). */
    var timeshiftMs: Int
        get() = sp.getInt("timeshiftMs", 0)
        set(v) = sp.edit().putInt("timeshiftMs", v).apply()

    // --- muting the channel ---

    /** Play the station on the alarm stream so the media volume can mute the TV alone. */
    var stationOnAlarmStream: Boolean
        get() = sp.getBoolean("stationOnAlarmStream", true)
        set(v) = sp.edit().putBoolean("stationOnAlarmStream", v).apply()

    /** Media volume saved before muting the channel (-1 = none). */
    var savedMusicVolume: Int
        get() = sp.getInt("savedMusicVolume", -1)
        set(v) = sp.edit().putInt("savedMusicVolume", v).apply()

    // --- capture source ---

    /** "playback" (default, needs one consent) or "mic". */
    var captureMode: String
        get() = sp.getString("captureMode", "playback")!!
        set(v) = sp.edit().putString("captureMode", v).apply()

    // --- remote key ---

    /** Remote key code that opens the panel (-1 = RED pressed twice). */
    var panelKey: Int
        get() = sp.getInt("panelKey", -1)
        set(v) = sp.edit().putInt("panelKey", v).apply()
}
