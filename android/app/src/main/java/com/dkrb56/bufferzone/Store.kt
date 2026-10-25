package com.dkrb56.bufferzone

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Settings on this TV, saved as one JSON object in SharedPreferences.
// Keys and defaults match main.js in the desktop app.

class Folder(val id: String, val name: String, val guids: List<String>)
class Pin(val key: String, val title: String, val type: String)

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("bufferzone", Context.MODE_PRIVATE)
    private var j: JSONObject = runCatching { JSONObject(prefs.getString("settings", "{}") ?: "{}") }.getOrElse { JSONObject() }

    /** Changes on every save; composables that read it redraw when settings change. */
    var version by mutableIntStateOf(0)
        private set

    private fun save() {
        prefs.edit().putString("settings", j.toString()).apply()
        version++
    }

    fun str(k: String, def: String) = j.optString(k, def).ifEmpty { def }
    fun int(k: String, def: Int) = if (j.has(k)) j.optInt(k, def) else def
    fun bool(k: String, def: Boolean) = if (j.has(k)) j.optBoolean(k, def) else def
    fun obj(k: String): JSONObject = j.optJSONObject(k) ?: JSONObject()
    fun set(k: String, v: Any?) { if (v == null) j.remove(k) else j.put(k, v); save() }

    var token: String?
        get() = prefs.getString("token", null)
        set(v) { prefs.edit().putString("token", v).apply() }

    val clientId: String
        get() = j.optString("clientId").ifEmpty { UUID.randomUUID().toString().also { set("clientId", it) } }

    var serverId: String? get() = j.optString("serverId").ifEmpty { null }; set(v) = set("serverId", v)
    var profileId: Long get() = j.optLong("profileId", 0); set(v) = set("profileId", v)
    var quality: String get() = str("quality", "direct"); set(v) = set("quality", v)
    var audioLang: String get() = str("audioLang", "eng"); set(v) = set("audioLang", v)
    var subtitleLang: String get() = str("subtitleLang", "eng"); set(v) = set("subtitleLang", v)
    var subtitleMode: String get() = str("subtitleMode", "auto"); set(v) = set("subtitleMode", v)
    var subtitleSize: Int get() = int("subtitleSize", 100); set(v) = set("subtitleSize", v)
    var subtitleColor: String get() = str("subtitleColor", "#ffffff"); set(v) = set("subtitleColor", v)
    var subtitleBackground: String get() = str("subtitleBackground", "shadow"); set(v) = set("subtitleBackground", v)
    var subtitlePosition: Int get() = int("subtitlePosition", 8); set(v) = set("subtitlePosition", v)
    var subtitleOpacity: Int get() = int("subtitleOpacity", 100); set(v) = set("subtitleOpacity", v)
    var skipBack: Int get() = int("skipBack", 10); set(v) = set("skipBack", v)
    var skipForward: Int get() = int("skipForward", 30); set(v) = set("skipForward", v)
    var autoPlayNext: Boolean get() = bool("autoPlayNext", true); set(v) = set("autoPlayNext", v)
    var nextCountdown: Int get() = int("nextCountdown", 10); set(v) = set("nextCountdown", v)
    var autoSkipIntro: Boolean get() = bool("autoSkipIntro", false); set(v) = set("autoSkipIntro", v)
    var autoSkipCredits: Boolean get() = bool("autoSkipCredits", false); set(v) = set("autoSkipCredits", v)
    var audioBoost: Int get() = int("audioBoost", 100); set(v) = set("audioBoost", v)
    var homeTab: String get() = str("homeTab", "all"); set(v) = set("homeTab", v)
    var mvLayout: String get() = str("mvLayout", "2x2"); set(v) = set("mvLayout", v)

    var hiddenLibraries: Set<String>
        get() = strings("hiddenLibraries").toSet()
        set(v) = set("hiddenLibraries", JSONArray(v.toList()))

    /** Selected watchlist folders; empty = all titles. Shared by home and the watchlist screen. */
    var wlSelected: List<String>
        get() = strings("wlSelected")
        set(v) = set("wlSelected", JSONArray(v))

    var folders: List<Folder>
        get() = (j.optJSONArray("watchlistFolders") ?: JSONArray()).objects().map { f ->
            Folder(f.optString("id"), f.optString("name"), (f.optJSONArray("guids") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } })
        }
        set(v) = set("watchlistFolders", JSONArray(v.map { JSONObject().put("id", it.id).put("name", it.name).put("guids", JSONArray(it.guids)) }))

    var pinned: List<Pin>
        get() = (j.optJSONArray("pinned") ?: JSONArray()).objects().map { Pin(it.optString("key"), it.optString("title"), it.optString("type")) }
        set(v) = set("pinned", JSONArray(v.map { JSONObject().put("key", it.key).put("title", it.title).put("type", it.type) }))

    fun seriesPref(show: String): JSONObject = obj("seriesPrefs").optJSONObject(show) ?: JSONObject()
    fun setSeriesPref(show: String, key: String, value: String) {
        val all = obj("seriesPrefs")
        all.put(show, seriesPref(show).put(key, value))
        set("seriesPrefs", all)
    }
    fun clearSeriesPrefs() = set("seriesPrefs", JSONObject())
    val seriesPrefCount get() = obj("seriesPrefs").length()

    fun libraryFilter(id: String): JSONObject = obj("libraryFilters").optJSONObject(id) ?: JSONObject()
    fun setLibraryFilter(id: String, f: JSONObject) = set("libraryFilters", obj("libraryFilters").put(id, f))
    fun lastLibrary(group: String): String? = obj("lastLibrary").optString(group).ifEmpty { null }
    fun setLastLibrary(group: String, id: String) = set("lastLibrary", obj("lastLibrary").put(group, id))

    fun rules(userId: Long): JSONObject? = obj("profileRules").optJSONObject(userId.toString())
    fun setRules(userId: Long, r: JSONObject) = set("profileRules", obj("profileRules").put(userId.toString(), r))

    private fun strings(k: String): List<String> {
        val a = j.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }
}
