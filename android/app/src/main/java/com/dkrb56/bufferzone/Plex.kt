package com.dkrb56.bufferzone

import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// Plex API client: plex.tv sign-in, server discovery, the Plex Media Server API, Plex Home and the Discover watchlist.
// Same requests as renderer/js/plex.js in the desktop app.

class PlexException(message: String, val status: Int = 0) : Exception(message)

// ---------- JSON helpers ----------
fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) opt(k).let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() } else null
fun JSONObject.long(k: String): Long? = if (has(k) && !isNull(k)) opt(k).let { (it as? Number)?.toLong() ?: it.toString().toLongOrNull() } else null
fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) opt(k).let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() } else null
fun JSONObject.bool(k: String): Boolean = when (val v = opt(k)) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> v == "1" || v == "true"
    else -> false
}
fun JSONObject.arr(k: String): List<JSONObject> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

// ---------- models ----------
class Stream(val j: JSONObject) {
    val id get() = j.long("id") ?: 0L
    val streamType get() = j.int("streamType") ?: 0
    val codec get() = (j.str("codec") ?: "").lowercase()
    val index get() = j.int("index")
    val languageCode get() = (j.str("languageCode") ?: "").lowercase()
    val language get() = j.str("language")
    val displayTitle get() = j.str("displayTitle") ?: language ?: "Track ${index ?: ""}"
    val title get() = j.str("title")
    val selected get() = j.bool("selected")
    val isDefault get() = j.bool("default")
    val forced get() = j.bool("forced")
    val key get() = j.str("key")
    val channels get() = j.int("channels")
    val channelLayout get() = j.str("audioChannelLayout")
    val profile get() = j.str("profile")
    val isText get() = codec in TEXT_CODECS

    companion object {
        val TEXT_CODECS = setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "mov_text", "tx3g", "text", "smi")
    }
}

class Part(val j: JSONObject) {
    val id get() = j.long("id") ?: 0L
    val key get() = j.str("key") ?: ""
    val container get() = j.str("container")
    val streams by lazy { j.arr("Stream").map { Stream(it) } }
}

class Media(val j: JSONObject) {
    val container get() = (j.str("container") ?: "").lowercase()
    val videoCodec get() = (j.str("videoCodec") ?: "").lowercase()
    val audioCodec get() = (j.str("audioCodec") ?: "").lowercase()
    val videoResolution get() = j.str("videoResolution")
    val bitrate get() = j.int("bitrate")
    val parts by lazy { j.arr("Part").map { Part(it) } }
}

class Marker(val type: String, val start: Double, val end: Double, val final: Boolean)

class Item(val j: JSONObject) {
    val ratingKey get() = j.str("ratingKey") ?: ""
    val type get() = j.str("type") ?: ""
    val title get() = j.str("title") ?: ""
    val grandparentTitle get() = j.str("grandparentTitle")
    val parentTitle get() = j.str("parentTitle")
    val index get() = j.int("index")
    val parentIndex get() = j.int("parentIndex")
    val year get() = j.int("year")
    val duration get() = j.long("duration") ?: 0L
    val viewOffset get() = j.long("viewOffset") ?: 0L
    val viewCount get() = j.int("viewCount") ?: 0
    val leafCount get() = j.int("leafCount") ?: 0
    val viewedLeafCount get() = j.int("viewedLeafCount") ?: 0
    val childCount get() = j.int("childCount") ?: 0
    val thumb get() = j.str("thumb")
    val art get() = j.str("art")
    val parentThumb get() = j.str("parentThumb")
    val grandparentThumb get() = j.str("grandparentThumb")
    val grandparentArt get() = j.str("grandparentArt")
    val summary get() = j.str("summary")
    val guid get() = j.str("guid")
    val grandparentGuid get() = j.str("grandparentGuid")
    val parentGuid get() = j.str("parentGuid")
    val parentRatingKey get() = j.str("parentRatingKey")
    val grandparentRatingKey get() = j.str("grandparentRatingKey")
    val librarySectionID get() = j.str("librarySectionID")
    val librarySectionTitle get() = j.str("librarySectionTitle")
    val contentRating get() = j.str("contentRating")
    val grandparentContentRating get() = j.str("grandparentContentRating")
    val lastViewedAt get() = j.long("lastViewedAt") ?: 0L
    val addedAt get() = j.long("addedAt") ?: 0L
    val audienceRating get() = j.dbl("audienceRating")
    val originallyAvailableAt get() = j.str("originallyAvailableAt")
    val genres get() = j.arr("Genre").mapNotNull { it.str("tag") }
    val directors get() = j.arr("Director").mapNotNull { it.str("tag") }
    val roles get() = j.arr("Role").mapNotNull { it.str("tag") }
    val media by lazy { j.arr("Media").map { Media(it) } }
    val markers by lazy {
        j.arr("Marker").map {
            Marker(it.str("type") ?: "", (it.long("startTimeOffset") ?: 0) / 1000.0, (it.long("endTimeOffset") ?: 0) / 1000.0, it.bool("final"))
        }
    }

    /** Watchlist and folders hold series and movies; an episode or season belongs to its series. */
    val titleGuid get() = when (type) { "episode" -> grandparentGuid; "season" -> parentGuid; else -> guid }
    val progress get() = if (viewOffset > 0 && duration > 0) (viewOffset.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val code get() = if (parentIndex != null && index != null) "S%02dE%02d".format(parentIndex, index) else ""
    val isWatched get() = if (type == "show" || type == "season") leafCount > 0 && viewedLeafCount >= leafCount else viewCount > 0
}

class Section(val key: String, val type: String, val title: String)
class Connection(val uri: String, val local: Boolean, val relay: Boolean)
class Resource(val j: JSONObject) {
    val name get() = j.str("name") ?: ""
    val clientIdentifier get() = j.str("clientIdentifier") ?: ""
    val owned get() = j.bool("owned")
    val sourceTitle get() = j.str("sourceTitle")
    val accessToken get() = j.str("accessToken")
    val connections get() = j.arr("connections").map { Connection(it.str("uri") ?: "", it.bool("local"), it.bool("relay")) }
}
class HomeUser(val j: JSONObject) {
    val id get() = j.long("id") ?: 0L
    val uuid get() = j.str("uuid") ?: ""
    val title get() = j.str("title") ?: j.str("username") ?: "User"
    val admin get() = j.bool("admin")
    val guest get() = j.bool("guest")
    val restricted get() = j.bool("restricted")
    val restrictionProfile get() = j.str("restrictionProfile")
    val isProtected get() = j.bool("protected")
}
class Server(val id: String, val name: String, val base: String, val token: String, val local: Boolean, val relay: Boolean)

class Plex(val clientId: String) {
    var token: String? = null          // current profile's plex.tv token
    var accountToken: String? = null   // the signed-in account's token (Plex Home admin)
    var server: Server? = null

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun clientHeaders(): Map<String, String> = mapOf(
        "X-Plex-Product" to "Buffer Zone",
        "X-Plex-Version" to "0.1.0",
        "X-Plex-Client-Identifier" to clientId,
        "X-Plex-Platform" to "Android",
        "X-Plex-Platform-Version" to Build.VERSION.RELEASE,
        "X-Plex-Device" to "Android TV",
        "X-Plex-Device-Name" to "Buffer Zone (${Build.MODEL})",
        "X-Plex-Model" to Build.MODEL,
        "Accept" to "application/json"
    )

    /** Same values as query parameters, for URLs that the player or the image loader opens. */
    fun clientParams(token: String?): Map<String, String> =
        clientHeaders().filterKeys { it != "Accept" } + ("X-Plex-Token" to (token ?: ""))

    fun qs(params: Map<String, Any?>): String = params.entries
        .filter { it.value != null && it.value.toString().isNotEmpty() }
        .joinToString("&") { "${enc(it.key)}=${enc(it.value.toString())}" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    class Response(val status: Int, val body: String) {
        val ok get() = status in 200..299
        fun json(): Any? = runCatching { JSONTokener(body).nextValue() }.getOrNull()
    }

    suspend fun request(url: String, method: String = "GET", token: String? = null, timeoutMs: Long = 25_000): Response =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
            clientHeaders().forEach { (k, v) -> b.header(k, v) }
            if (token != null) b.header("X-Plex-Token", token)
            when (method) {
                "POST" -> b.post(ByteArray(0).toRequestBody(null))
                "PUT" -> b.put(ByteArray(0).toRequestBody(null))
                "DELETE" -> b.delete()
                else -> b.get()
            }
            val client = if (timeoutMs != 25_000L) http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else http
            try {
                client.newCall(b.build()).execute().use { r -> Response(r.code, r.body?.string() ?: "") }
            } catch (e: Exception) {
                Response(0, e.toString())
            }
        }

    private suspend fun service(base: String, path: String, method: String = "GET", params: Map<String, Any?>? = null, token: String? = this.token): Any? {
        val url = base + path + (if (params != null) "?" + qs(params) else "")
        val r = request(url, method, token)
        if (!r.ok) throw PlexException("$path failed (${r.status})", r.status)
        return r.json()
    }

    // ---------- sign-in (plex.tv/link: the user types the code on another device) ----------
    suspend fun createPin(): JSONObject =
        service("https://plex.tv", "/api/v2/pins", "POST") as? JSONObject ?: throw PlexException("No PIN")

    suspend fun checkPin(id: Long): String? =
        (runCatching { service("https://plex.tv", "/api/v2/pins/$id") }.getOrNull() as? JSONObject)?.str("authToken")?.ifEmpty { null }

    suspend fun user(): JSONObject = service("https://plex.tv", "/api/v2/user") as? JSONObject ?: throw PlexException("No user")

    // ---------- servers ----------
    suspend fun resources(): List<Resource> {
        val list = service("https://plex.tv", "/api/v2/resources", params = mapOf("includeHttps" to 1, "includeRelay" to 1, "includeIPv6" to 1))
        return (list as? JSONArray)?.objects()?.map { Resource(it) }?.filter { (it.j.str("provides") ?: "").contains("server") } ?: emptyList()
    }

    /** Tries all connections in parallel; a direct connection wins over the Plex relay. Null when the server is unreachable. */
    suspend fun probe(res: Resource): Connection? {
        val token = res.accessToken ?: this.token
        // The first connection that answers wins; slow ones are cancelled instead of awaited.
        suspend fun first(list: List<Connection>): Connection? = coroutineScope {
            if (list.isEmpty()) return@coroutineScope null
            val result = CompletableDeferred<Connection?>()
            val jobs = list.map { c -> launch { if (request("${c.uri}/identity", token = token, timeoutMs = 6000).ok) result.complete(c) } }
            launch { jobs.joinAll(); result.complete(null) }
            val r = withTimeoutOrNull(7000) { result.await() }
            coroutineContext.cancelChildren()
            r
        }
        val conns = res.connections
        return first(conns.filter { !it.relay }) ?: first(conns.filter { it.relay })
    }

    suspend fun connect(res: Resource): Server {
        val c = probe(res) ?: throw PlexException("Cannot reach server \"${res.name}\". It may be offline.")
        val s = Server(res.clientIdentifier, res.name, c.uri.trimEnd('/'), res.accessToken ?: token ?: "", c.local, c.relay)
        server = s
        return s
    }

    // ---------- server API ----------
    fun url(path: String, params: Map<String, Any?> = emptyMap()): String {
        val s = server ?: throw PlexException("Not connected")
        val q = qs(params)
        return s.base + path + (if (q.isEmpty()) "" else (if (path.contains('?')) "&" else "?") + q)
    }

    suspend fun get(path: String, params: Map<String, Any?> = emptyMap(), method: String = "GET", timeoutMs: Long = 25_000): JSONObject {
        val r = request(url(path, params), method, server?.token, timeoutMs)
        if (!r.ok) throw PlexException("Server request failed: $path (${r.status})", r.status)
        val j = r.json() as? JSONObject ?: return JSONObject()
        return j.optJSONObject("MediaContainer") ?: j
    }

    suspend fun put(path: String, params: Map<String, Any?> = emptyMap()) = get(path, params, "PUT")

    suspend fun getText(path: String, params: Map<String, Any?> = emptyMap()): String {
        val r = request(url(path, params), token = server?.token)
        if (!r.ok) throw PlexException("Request failed: $path (${r.status})", r.status)
        return r.body
    }

    fun image(path: String?, w: Int, h: Int): String? {
        if (path.isNullOrEmpty() || server == null) return null
        if (path.startsWith("http")) return path
        return url("/photo/:/transcode", mapOf("width" to w, "height" to h, "minSize" to 1, "upscale" to 1, "url" to path, "X-Plex-Token" to server!!.token))
    }

    private fun items(mc: JSONObject) = mc.arr("Metadata").map { Item(it) }

    suspend fun sections(): List<Section> = get("/library/sections").arr("Directory")
        .filter { it.str("type") == "movie" || it.str("type") == "show" }
        .map { Section(it.str("key") ?: "", it.str("type") ?: "", it.str("title") ?: "") }

    suspend fun sectionItems(id: String, sort: String?, genre: String?, unwatched: Boolean, start: Int, size: Int): Pair<List<Item>, Int> {
        val mc = get("/library/sections/$id/all", mapOf(
            "sort" to sort, "genre" to genre, "unwatched" to (if (unwatched) 1 else null), "includeCollections" to 0,
            "X-Plex-Container-Start" to start, "X-Plex-Container-Size" to size))
        return items(mc) to (mc.int("totalSize") ?: mc.int("size") ?: 0)
    }

    suspend fun genres(id: String) = get("/library/sections/$id/genre").arr("Directory").map { (it.str("key") ?: "") to (it.str("title") ?: "") }

    suspend fun continueWatching(): List<Item> {
        runCatching {
            val hub = get("/hubs/home/continueWatching", mapOf("count" to 60)).arr("Hub").firstOrNull()
            if (hub != null) return items(hub)
        }
        return onDeck()
    }

    suspend fun onDeck(): List<Item> = items(get("/library/onDeck", mapOf("X-Plex-Container-Size" to 30)))

    suspend fun search(query: String): Map<String, List<Item>> {
        val mc = get("/hubs/search", mapOf("query" to query, "limit" to 30, "includeCollections" to 0, "includeExternalMedia" to 0))
        val out = mutableMapOf("movie" to mutableListOf<Item>(), "show" to mutableListOf(), "episode" to mutableListOf())
        for (hub in mc.arr("Hub")) out[hub.str("type")]?.addAll(items(hub))
        return out
    }

    suspend fun metadata(rk: String): Item = items(get("/library/metadata/$rk", mapOf("includeMarkers" to 1, "includeChapters" to 1))).first()
    suspend fun children(rk: String) = items(get("/library/metadata/$rk/children"))
    suspend fun allLeaves(rk: String) = items(get("/library/metadata/$rk/allLeaves"))
    suspend fun collections(sec: String) = items(get("/library/sections/$sec/collections"))
    suspend fun collectionItems(rk: String) = items(get("/library/collections/$rk/children"))
    suspend fun firstCharacter(sec: String) = get("/library/sections/$sec/firstCharacter").arr("Directory").map { (it.str("title") ?: "") to (it.int("size") ?: 0) }
    suspend fun findByGuid(guid: String) = items(get("/library/all", mapOf("guid" to guid)))

    // Watch state
    suspend fun scrobble(rk: String) = get("/:/scrobble", mapOf("identifier" to "com.plexapp.plugins.library", "key" to rk))
    suspend fun unscrobble(rk: String) = get("/:/unscrobble", mapOf("identifier" to "com.plexapp.plugins.library", "key" to rk))
    suspend fun timeline(rk: String, state: String, timeMs: Long, durationMs: Long, sessionId: String) {
        val u = url("/:/timeline", mapOf("ratingKey" to rk, "key" to "/library/metadata/$rk", "state" to state, "time" to timeMs, "duration" to durationMs, "hasMDE" to 1))
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(u)
            clientHeaders().forEach { (k, v) -> b.header(k, v) }
            b.header("X-Plex-Token", server?.token ?: "").header("X-Plex-Session-Identifier", sessionId)
            runCatching { http.newCall(b.build()).execute().close() }
        }
    }
    suspend fun selectStreams(partId: Long, audioId: Long?, subtitleId: Long?) =
        put("/library/parts/$partId", mapOf("audioStreamID" to audioId, "subtitleStreamID" to subtitleId, "allParts" to 1))
    suspend fun ping(session: String) = runCatching { get("/video/:/transcode/universal/ping", mapOf("session" to session)) }
    suspend fun stopTranscode(session: String) = runCatching { get("/video/:/transcode/universal/stop", mapOf("session" to session)) }

    // ---------- Plex Home ----------
    suspend fun homeUsers(): List<HomeUser> =
        ((service("https://clients.plex.tv", "/api/v2/home/users", token = accountToken ?: token) as? JSONObject)?.arr("users") ?: emptyList()).map { HomeUser(it) }

    /** Returns the profile's own token. A wrong PIN gives status 401 or 403. */
    suspend fun switchUser(uuid: String, pin: String?): String? =
        (service("https://clients.plex.tv", "/api/v2/home/users/$uuid/switch", "POST", mapOf("pin" to pin), accountToken ?: token) as? JSONObject)?.str("authToken")

    // ---------- watchlist (Discover allows at most 100 items per request) ----------
    suspend fun watchlist(): List<Item> {
        val all = mutableListOf<Item>()
        var start = 0
        while (start < 1000) {
            val mc = (service("https://discover.provider.plex.tv", "/library/sections/watchlist/all",
                params = mapOf("X-Plex-Container-Start" to start, "X-Plex-Container-Size" to 100)) as? JSONObject)?.optJSONObject("MediaContainer") ?: break
            val page = items(mc)
            all += page
            if (page.isEmpty() || all.size >= (mc.int("totalSize") ?: 0)) break
            start += 100
        }
        return all
    }
    suspend fun watchlistedAt(rk: String): Long =
        ((service("https://discover.provider.plex.tv", "/library/metadata/$rk/userState") as? JSONObject)
            ?.optJSONObject("MediaContainer")?.arr("UserState")?.firstOrNull()?.long("watchlistedAt")) ?: 0L
    suspend fun addToWatchlist(guid: String) = service("https://discover.provider.plex.tv", "/actions/addToWatchlist", "PUT", mapOf("ratingKey" to guid.substringAfterLast('/')))
    suspend fun removeFromWatchlist(guid: String) = service("https://discover.provider.plex.tv", "/actions/removeFromWatchlist", "PUT", mapOf("ratingKey" to guid.substringAfterLast('/')))
}
