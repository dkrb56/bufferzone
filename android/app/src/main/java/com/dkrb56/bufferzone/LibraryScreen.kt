package com.dkrb56.bufferzone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

val SORTS = listOf(
    "addedAt:desc" to "Recently added", "titleSort" to "Title A–Z", "titleSort:desc" to "Title Z–A",
    "year:desc" to "Year (newest)", "year" to "Year (oldest)", "audienceRating:desc" to "Audience rating",
    "rating:desc" to "Critic rating", "lastViewedAt:desc" to "Recently watched", "originallyAvailableAt:desc" to "Release date"
)

@Composable
fun PageHead(title: String, count: String? = null, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        T(title, 18.sp, FontWeight.Bold, maxLines = 1)
        if (count != null) T(count, 13.sp, color = C.muted)
        extra()
        Spacer(Modifier.weight(1f))
        SearchChip()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
}

@Composable
fun LibraryScreen(id: String) {
    val sec = App.sections.firstOrNull { it.key == id } ?: run { T("This library is not available.", modifier = Modifier.padding(40.dp)); return }
    val g = App.groups()
    val group = if (sec in g.kids) "kids" else sec.type
    val siblings = when (group) { "kids" -> g.kids; "movie" -> g.movie; else -> g.show }
    LaunchedEffect(id) { App.store.setLastLibrary(group, id) }

    val saved = App.store.libraryFilter(id)
    var sort by remember(id) { mutableStateOf(saved.optString("sort", "addedAt:desc")) }
    var genre by remember(id) { mutableStateOf(saved.optString("genre", "")) }
    var unwatched by remember(id) { mutableStateOf(saved.optBoolean("unwatched", false)) }
    var view by remember(id) { mutableStateOf(saved.optString("view", "grid")) }
    fun save() = App.store.setLibraryFilter(id, JSONObject().put("sort", sort).put("genre", genre).put("unwatched", unwatched).put("view", view))

    var genres by remember(id) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var letters by remember(id) { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val items = remember(id) { mutableStateListOf<Item>() }
    var total by remember(id) { mutableStateOf(-1) }
    var startAt by remember(id) { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var gen by remember { mutableStateOf(0) }
    var letterOn by remember { mutableStateOf<String?>(null) }
    var collections by remember(id) { mutableStateOf<List<Item>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun more() {
        if (loading || (total >= 0 && startAt >= total)) return
        loading = true
        val myGen = gen
        try {
            val (page, t) = App.plex.sectionItems(id, sort, genre.ifEmpty { null }, unwatched, startAt, 60)
            if (myGen != gen) return
            total = if (page.isEmpty()) startAt else t
            startAt += page.size
            items += App.allow(page)
        } catch (e: Exception) { App.show(e.message ?: "Cannot load") } finally { loading = false }
    }
    fun reload(from: Int = 0) { gen++; items.clear(); total = -1; startAt = from; loading = false; scope.launch { more() } }

    LaunchedEffect(id, App.dataVersion) {
        genres = runCatching { App.plex.genres(id) }.getOrDefault(emptyList())
        letters = runCatching { App.plex.firstCharacter(id) }.getOrDefault(emptyList())
        reload()
    }
    LaunchedEffect(id, view) { if (view == "collections" && collections == null) collections = runCatching { App.plex.collections(id) }.getOrDefault(emptyList()) }

    Column(Modifier.fillMaxSize()) {
        PageHead(sec.title, if (view == "collections") collections?.let { "${it.size} collections" } else if (total >= 0) "$total ${if (sec.type == "show") "series" else "movies"}" else null) {
            if (siblings.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp).width(620.dp)) {
                items(siblings, key = { it.key }) { s -> Chip(s.title, s.key == id, { App.go(Screen.Library(s.key), replace = true) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (view != "collections") {
                TvButton(SORTS.firstOrNull { it.first == sort }?.second ?: "Sort", { dialog = "sort" })
                TvButton(genres.firstOrNull { it.first == genre }?.second ?: "All genres", { dialog = "genre" })
                Focusable({ unwatched = !unwatched; save(); reload() }) { f ->
                    Row(Modifier.background(if (f) C.bg3 else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SwitchMark(unwatched); T("Unwatched only", 14.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconBtn(Icons.Outlined.GridView, { view = "grid"; save() }, on = view == "grid", size = 40.dp)
            IconBtn(Icons.AutoMirrored.Outlined.List, { view = "list"; save() }, on = view == "list", size = 40.dp)
            IconBtn(Icons.Outlined.Folder, { view = "collections"; save() }, on = view == "collections", size = 40.dp)
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp)) {
                when {
                    view == "collections" -> {
                        val cols = collections
                        if (cols == null) Loading() else LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 40.dp, top = 6.dp, end = 8.dp)) {
                            items(cols.size) { PosterCard(cols[it]) }
                        }
                    }
                    view == "list" -> {
                        val state = rememberLazyListState()
                        LaunchedEffect(state, gen) {
                            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { if (it > items.size - 15) more() }
                        }
                        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 40.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(items, key = { it.ratingKey }) { ListRow(it) }
                            if (total == 0) item { T("No matches.", 14.sp, color = C.muted, modifier = Modifier.padding(20.dp)) }
                        }
                    }
                    else -> {
                        val state = rememberLazyGridState()
                        LaunchedEffect(state, gen) {
                            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { if (it > items.size - 24) more() }
                        }
                        LazyVerticalGrid(GridCells.Adaptive(150.dp), state = state, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 40.dp, top = 6.dp, end = 8.dp)) {
                            items(items.size, key = { items[it].ratingKey }) { PosterCard(items[it]) }
                        }
                        if (total == 0) T("No matches.", 14.sp, color = C.muted, modifier = Modifier.padding(20.dp))
                    }
                }
            }
            // A–Z rail: jumps in the title-sorted list. The offsets are exact only without filters.
            if (view != "collections" && letters.isNotEmpty()) {
                var offset = 0
                val map = letters.map { (k, n) -> Triple(k, offset, n).also { offset += n } }
                LazyColumn(Modifier.width(34.dp).fillMaxHeight().padding(end = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(bottom = 20.dp)) {
                    items("#ABCDEFGHIJKLMNOPQRSTUVWXYZ".map { it.toString() }) { L ->
                        val entry = map.firstOrNull { it.first.uppercase() == L }
                        if (entry == null) T(L, 11.sp, FontWeight.Bold, C.muted.copy(alpha = .3f), modifier = Modifier.padding(vertical = 2.dp))
                        else Focusable({
                            letterOn = L
                            if (sort != "titleSort") { sort = "titleSort"; save() }
                            reload(entry.second)
                        }, shape = RoundedCornerShape(4.dp)) { f ->
                            T(L, 11.sp, FontWeight.Bold, if (letterOn == L) C.accent else if (f) C.text else C.muted, modifier = Modifier.background(if (f) C.bg3 else Color.Transparent).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "sort" -> OptionDialog("Sort", SORTS, sort, { dialog = null }) { sort = it; save(); reload() }
        "genre" -> OptionDialog("Genre", listOf("" to "All genres") + genres, genre, { dialog = null }) { genre = it; save(); reload() }
    }
}

@Composable
private fun ListRow(i: Item) {
    Focusable({ App.open(i) }, Modifier.fillMaxWidth()) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) C.bg2 else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Art(i.thumb, 72, 108, Modifier.width(40.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f)) {
                T(i.title, 15.sp, FontWeight.SemiBold, maxLines = 1)
                T(i.genres.take(3).joinToString(", "), 12.sp, color = C.muted, maxLines = 1)
            }
            T(i.year?.toString() ?: "", 13.sp, color = C.muted, modifier = Modifier.width(60.dp))
            T(if (i.type == "show") "${i.leafCount} episodes" else if (i.duration > 0) fmtDuration(i.duration) else "", 13.sp, color = C.muted, modifier = Modifier.width(110.dp))
            T(i.audienceRating?.let { "★ %.1f".format(it) } ?: "", 13.sp, color = C.muted, modifier = Modifier.width(60.dp))
            Box(Modifier.width(24.dp)) { if (i.isWatched) Icon(Icons.Rounded.Check, null, tint = C.ok, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun CollectionScreen(key: String) {
    var meta by remember(key) { mutableStateOf<Item?>(null) }
    var items by remember(key) { mutableStateOf<List<Item>?>(null) }
    Reload(key) {
        meta = runCatching { App.plex.metadata(key) }.getOrNull()
        items = runCatching { App.plex.collectionItems(key) }.getOrDefault(emptyList())
    }
    App.store.version
    Column(Modifier.fillMaxSize()) {
        val m = meta
        PageHead(m?.title ?: "Collection", items?.let { "${it.size} titles" }) {
            if (m != null) TvButton(if (App.isPinned(key)) "Unpin" else "Pin to sidebar", { App.togglePin(m) }, icon = Icons.Outlined.PushPin)
        }
        val list = items
        if (list == null) Loading() else LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
            val allowed = App.allow(list)
            items(allowed.size) { PosterCard(allowed[it]) }
        }
    }
}
