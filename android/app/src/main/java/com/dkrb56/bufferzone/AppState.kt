package com.dkrb56.bufferzone

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

sealed interface Screen {
    data object Loading : Screen
    data class Message(val title: String, val text: String, val retry: (() -> Unit)? = null) : Screen
    data class Onboard(val step: Int) : Screen
    data object Profiles : Screen
    data object Home : Screen
    data class Library(val id: String) : Screen
    data object Search : Screen
    data class Movie(val key: String) : Screen
    data class Show(val key: String, val season: String? = null, val focus: String? = null) : Screen
    data class Collection(val key: String) : Screen
    data object Watchlist : Screen
    data class Settings(val page: String = "playback", val who: Long = 0) : Screen
    data object MultiView : Screen
}

/** A request to the player. audioId/subId/quality preset the tracks for this playback only. */
class PlayRequest(val item: Item, val startAt: Double, val audioId: Long? = null, val subId: Long? = null, val subPreset: Boolean = false, val quality: String? = null)

/** Parental rules for one managed profile; the admin profile has none. */
class Rules(val restrict: Boolean, val maxAge: Int, val unrated: String, val libraries: List<String>?, val pinToLeave: Boolean, val multiview: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("restrict", restrict).put("maxAge", maxAge).put("unrated", unrated)
        .put("libraries", libraries?.let { JSONArray(it) } ?: JSONObject.NULL).put("pinToLeave", pinToLeave).put("multiview", multiview)
    companion object {
        val DEFAULT = Rules(true, 12, "hide", null, true, false)
        fun from(j: JSONObject?): Rules {
            if (j == null) return DEFAULT
            val libs = j.optJSONArray("libraries")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            return Rules(j.optBoolean("restrict", true), j.optInt("maxAge", 12), j.optString("unrated", "hide"), libs, j.optBoolean("pinToLeave", true), j.optBoolean("multiview", false))
        }
    }
}

class WlRow(val w: Item, val s: Item?, val added: Long)

object App {
    lateinit var store: Store
    lateinit var plex: Plex
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val nav = mutableStateListOf<Screen>(Screen.Loading)
    val screen get() = nav.last()
    var playback by mutableStateOf<PlayRequest?>(null)
    var toast by mutableStateOf<String?>(null)
    var dataVersion by mutableStateOf(0)   // bump to reload data views (watched state, watchlist)

    var user: JSONObject? = null
    var sections by mutableStateOf<List<Section>>(emptyList())
    var homeUsers by mutableStateOf<List<HomeUser>>(emptyList())
    var profile by mutableStateOf<HomeUser?>(null)

    // Watchlist cache
    private var watchlist: List<Item>? = null
    var watchGuids by mutableStateOf<Set<String>>(emptySet())
    private val wlMatch = mutableMapOf<String, Item?>()
    private val wlAdded = mutableMapOf<String, Long>()
    private val wlMutex = Mutex()
    private var wlLoading: Deferred<List<Item>>? = null

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = Store(context.applicationContext)
        plex = Plex(store.clientId)
    }

    // ---------- navigation ----------
    fun go(s: Screen, root: Boolean = false, replace: Boolean = false) {
        if (root) { nav.clear(); nav.add(s); return }
        if (replace && nav.isNotEmpty()) nav[nav.lastIndex] = s else nav.add(s)
    }
    fun back(): Boolean {
        if (nav.size <= 1) return false
        nav.removeAt(nav.lastIndex)
        return true
    }
    fun log(msg: String) { android.util.Log.i("BufferZone", msg) }
    fun show(msg: String) { toast = msg; scope.launch { delay(3500); if (toast == msg) toast = null } }

    fun open(i: Item) = when (i.type) {
        "movie" -> go(Screen.Movie(i.ratingKey))
        "show" -> go(Screen.Show(i.ratingKey))
        "season" -> go(Screen.Show(i.parentRatingKey ?: "", season = i.ratingKey))
        "episode" -> go(Screen.Show(i.grandparentRatingKey ?: "", season = i.parentRatingKey, focus = i.ratingKey))
        "collection" -> go(Screen.Collection(i.ratingKey))
        else -> Unit
    }

    // ---------- start-up ----------
    fun start() {
        scope.launch {
            val token = store.token
            if (token == null) { go(Screen.Onboard(1), root = true); return@launch }
            plex.token = token; plex.accountToken = token
            try {
                user = plex.user()
            } catch (e: PlexException) {
                if (e.status == 401) { store.token = null; go(Screen.Onboard(1), root = true); return@launch }
                go(Screen.Message("Cannot reach plex.tv", "Check the network connection of the TV.") { start() }, root = true); return@launch
            }
            if (store.serverId == null) { go(Screen.Onboard(2), root = true); return@launch }
            reconnect()
        }
    }

    suspend fun reconnect() {
        go(Screen.Loading, root = true)
        val servers = runCatching { plex.resources() }.getOrElse {
            go(Screen.Message("Cannot load servers", it.message ?: "") { scope.launch { reconnect() } }, root = true); return
        }
        val saved = servers.firstOrNull { it.clientIdentifier == store.serverId } ?: run { go(Screen.Onboard(2), root = true); return }
        try { connect(saved) } catch (e: Exception) {
            go(Screen.Message("Cannot connect", e.message ?: "") { go(Screen.Onboard(2), root = true) }, root = true); return
        }
        val last = homeUsers.firstOrNull { it.id == store.profileId }
        if (homeUsers.size > 1 && (last == null || last.isProtected || !last.admin)) { go(Screen.Profiles, root = true); return }
        enterApp()
    }

    suspend fun connect(res: Resource) {
        plex.connect(res)
        store.serverId = res.clientIdentifier
        sections = plex.sections()
        homeUsers = runCatching { plex.homeUsers() }.getOrElse { emptyList() }
        if (profile == null) profile = homeUsers.firstOrNull { it.admin }
    }

    fun enterApp() {
        go(Screen.Home, root = true)
        scope.launch { loadWatchlist() }
    }

    suspend fun switchProfile(u: HomeUser, token: String): Boolean {
        val prev = plex.token
        plex.token = token
        return try {
            val res = plex.resources().firstOrNull { it.clientIdentifier == store.serverId }
                ?: throw PlexException("${u.title} has no access to ${plex.server?.name}.")
            plex.connect(res)
            sections = plex.sections()
            profile = u
            store.profileId = u.id
            watchlist = null; wlMatch.clear(); wlAdded.clear()
            enterApp()
            true
        } catch (e: Exception) {
            plex.token = prev
            show(e.message ?: "Cannot switch profile")
            false
        }
    }

    // ---------- parental rules ----------
    val rules: Rules?
        get() {
            val p = profile ?: return null
            if (p.admin) return null
            val saved = store.rules(p.id)
            return if (saved != null) Rules.from(saved) else if (p.restricted) Rules.DEFAULT else null
        }

    private val ratingAge = mapOf(
        "G" to 0, "TV-Y" to 0, "TV-G" to 0, "U" to 0, "APPROVED" to 0, "PASSED" to 0,
        "TV-Y7" to 7, "TV-Y7-FV" to 7, "PG" to 10, "TV-PG" to 10, "12A" to 12, "PG-13" to 13, "TV-14" to 14,
        "R" to 17, "TV-MA" to 17, "NC-17" to 18, "X" to 18)

    fun ageOf(rating: String?): Int? {
        if (rating.isNullOrEmpty()) return null
        val r = rating.replace(Regex("^[a-zA-Z]{2}/"), "").uppercase()
        return ratingAge[r] ?: r.takeWhile { it.isDigit() }.toIntOrNull()
    }

    fun sectionAllowed(key: String?): Boolean { val r = rules ?: return true; return r.libraries == null || key == null || key in r.libraries }

    fun itemAllowed(i: Item): Boolean {
        val r = rules ?: return true
        if (i.librarySectionID != null && !sectionAllowed(i.librarySectionID)) return false
        if (!r.restrict) return true
        val age = ageOf(i.contentRating ?: i.grandparentContentRating) ?: return r.unrated != "hide"
        return age <= r.maxAge
    }

    fun allow(list: List<Item>) = list.filter { itemAllowed(it) }
    val multiviewAllowed get() = rules?.multiview ?: true

    fun visibleSections() = sections.filter { it.key !in store.hiddenLibraries && sectionAllowed(it.key) }

    class Groups(val movie: List<Section>, val show: List<Section>, val kids: List<Section>)
    fun groups(): Groups {
        val secs = visibleSections()
        val kids = secs.filter { it.title.contains("kid", ignoreCase = true) }
        return Groups(secs.filter { it.type == "movie" && it !in kids }, secs.filter { it.type == "show" && it !in kids }, kids)
    }

    // ---------- watchlist ----------
    suspend fun loadWatchlist(): List<Item> {
        val running = wlLoading
        if (running != null) return running.await()
        val d = scope.async {
            val list = runCatching { plex.watchlist() }.getOrElse { emptyList() }
            watchlist = list
            watchGuids = list.mapNotNull { it.guid }.toSet()
            list
        }
        wlLoading = d
        return try { d.await() } finally { wlLoading = null }
    }

    /** Watchlist rows matched to server items, newest first. Matches and dates are cached by guid. */
    suspend fun watchlistRows(): List<WlRow> {
        val items = watchlist ?: loadWatchlist()
        wlMutex.withLock {
            val todo = items.filter { it.guid != null && !wlMatch.containsKey(it.guid!!) }
            todo.chunked(6).forEach { chunk ->
                chunk.map { w ->
                    scope.async {
                        val g = w.guid!!
                        if (!wlAdded.containsKey(g)) wlAdded[g] = runCatching { plex.watchlistedAt(w.ratingKey) }.getOrDefault(0L)
                        val found = runCatching { plex.findByGuid(g) }.getOrDefault(emptyList())
                        val visible = found.filter { it.librarySectionID !in store.hiddenLibraries }
                        wlMatch[g] = visible.firstOrNull() ?: found.firstOrNull()
                    }
                }.awaitAll()
            }
        }
        return items.filter { it.guid != null }
            .map { WlRow(it, wlMatch[it.guid!!], wlAdded[it.guid!!] ?: 0L) }
            .filter { it.s == null || itemAllowed(it.s) }
            .sortedByDescending { it.added }
    }

    fun inWatchlist(i: Item) = i.guid != null && i.guid in watchGuids

    suspend fun toggleWatchlist(i: Item) {
        val g = i.guid
        if (g == null || !g.startsWith("plex://")) { show("This title cannot be added to the watchlist."); return }
        try {
            if (inWatchlist(i)) { plex.removeFromWatchlist(g); watchGuids = watchGuids - g; show("Removed from watchlist") }
            else { plex.addToWatchlist(g); watchGuids = watchGuids + g; show("Added to watchlist") }
            watchlist = null
        } catch (e: Exception) { show("Watchlist failed: ${e.message}") }
    }

    suspend fun removeFromWatchlist(g: String) {
        plex.removeFromWatchlist(g)
        watchGuids = watchGuids - g
        store.folders = store.folders.map { Folder(it.id, it.name, it.guids - g) }
    }

    // ---------- folders ----------
    fun inFolder(key: String, guid: String?): Boolean {
        if (guid == null) return key == "none"
        if (key == "all") return true
        if (key == "none") return store.folders.none { guid in it.guids }
        return store.folders.firstOrNull { it.id == key }?.guids?.contains(guid) == true
    }
    fun selection(): List<String> {
        val ids = store.folders.map { it.id }.toSet()
        return store.wlSelected.filter { it == "none" || it in ids }
    }
    fun inSelection(guid: String?, sel: List<String> = selection()) = sel.isEmpty() || sel.any { inFolder(it, guid) }
    fun toggleSelection(key: String) {
        val sel = selection()
        store.wlSelected = if (key == "all") emptyList() else if (key in sel) sel - key else sel + key
    }
    fun newFolder(name: String): Folder {
        val f = Folder("f" + System.currentTimeMillis().toString(36), name, emptyList())
        store.folders = store.folders + f
        return f
    }
    fun setInFolder(id: String, guid: String, on: Boolean) {
        store.folders = store.folders.map { if (it.id != id) it else Folder(it.id, it.name, if (on) (it.guids + guid).distinct() else it.guids - guid) }
    }

    // ---------- pins ----------
    fun isPinned(key: String) = store.pinned.any { it.key == key }
    fun togglePin(i: Item) {
        val list = store.pinned.filter { it.key != i.ratingKey }
        store.pinned = if (list.size == store.pinned.size) list + Pin(i.ratingKey, i.title, i.type) else list
        show(if (isPinned(i.ratingKey)) "Pinned to the sidebar" else "Unpinned")
    }

    // ---------- watch state & playback ----------
    suspend fun toggleWatched(i: Item, watched: Boolean = i.isWatched) {
        try {
            if (watched) plex.unscrobble(i.ratingKey) else plex.scrobble(i.ratingKey)
            show(if (watched) "Marked unwatched" else "Marked watched")
            dataVersion++
        } catch (e: Exception) { show("Failed: ${e.message}") }
    }

    /** A series or season resolves to its next episode. */
    suspend fun resolvePlayable(i: Item): Item? {
        if (i.type != "show" && i.type != "season") return i
        val leaves = runCatching { plex.allLeaves(if (i.type == "show") i.ratingKey else i.parentRatingKey ?: "") }.getOrDefault(emptyList())
        val eps = if (i.type == "season") leaves.filter { it.parentRatingKey == i.ratingKey } else leaves
        return eps.firstOrNull { it.viewOffset > 0 && it.viewCount == 0 } ?: eps.firstOrNull { it.viewCount == 0 } ?: eps.firstOrNull()
    }

    fun play(i: Item, resume: Boolean = true, audioId: Long? = null, subId: Long? = null, subPreset: Boolean = false, quality: String? = null) = scope.launch {
        val item = resolvePlayable(i) ?: run { show("Nothing to play."); return@launch }
        val start = if (resume && item.viewOffset > 0) item.viewOffset / 1000.0 else 0.0
        playback = PlayRequest(item, start, audioId, subId, subPreset, quality)
    }
}
