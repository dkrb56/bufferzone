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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async

/** Shows titles in progress outside the selected folders, until the app restarts. */
private var cwShowAll by mutableStateOf(false)

private class HomeTab(val id: String, val label: String, val secs: List<Section>)

private fun homeTabs(): List<HomeTab> {
    val g = App.groups()
    val secs = App.visibleSections()
    val tabs = mutableListOf(HomeTab("all", "All", secs))
    if (g.movie.isNotEmpty()) tabs += HomeTab("movie", "Movies", g.movie)
    if (g.show.isNotEmpty()) tabs += HomeTab("show", "Series", g.show)
    if (g.kids.isNotEmpty()) tabs += HomeTab("kids", "Kids", g.kids)
    secs.filter { !Regex("^(movies|tv shows)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.title) && it !in g.kids }
        .forEach { tabs += HomeTab("s" + it.key, it.title, listOf(it)) }
    return tabs
}

@Composable
fun SearchChip() {
    Focusable({ App.go(Screen.Search) }, shape = RoundedCornerShape(8.dp)) { f ->
        Row(Modifier.width(300.dp).height(36.dp).background(if (f) C.bgHover else C.bg3).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Search, null, tint = C.muted, modifier = Modifier.size(16.dp))
            T("Search", 13.sp, color = C.muted)
        }
    }
}

@Composable
fun FolderChips(rows: List<WlRow>, onChange: () -> Unit) {
    App.store.version
    val sel = App.selection()
    val folders = App.store.folders
    if (folders.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        item { Chip("All", sel.isEmpty(), { App.toggleSelection("all"); onChange() }, count = rows.size) }
        items(folders, key = { it.id }) { f ->
            Chip(f.name, f.id in sel, { App.toggleSelection(f.id); onChange() }, count = rows.count { App.inFolder(f.id, it.w.guid) }, check = true)
        }
        item { Chip("No folder", "none" in sel, { App.toggleSelection("none"); onChange() }, count = rows.count { App.inFolder("none", it.w.guid) }, check = true) }
    }
}

@Composable
fun HomeScreen() {
    App.store.version
    val tabs = homeTabs()
    val tab = tabs.firstOrNull { it.id == App.store.homeTab } ?: tabs.first()
    val keys = tab.secs.map { it.key }.toSet()
    fun inTab(i: Item) = tab.id == "all" || i.librarySectionID in keys

    var cw by remember { mutableStateOf<List<Item>?>(null) }
    var deck by remember { mutableStateOf<List<Item>>(emptyList()) }
    var wl by remember { mutableStateOf<List<WlRow>?>(null) }
    // The first Continue watching row takes the focus, so the remote works at once.
    val firstFr = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(cw != null, App.playback == null) {
        if (cw != null && App.playback == null) { kotlinx.coroutines.delay(150); runCatching { firstFr.requestFocus() } }
    }
    Reload {
        val a = App.scope.async { runCatching { App.plex.continueWatching() }.getOrDefault(emptyList()) }
        val b = App.scope.async { runCatching { App.plex.onDeck() }.getOrDefault(emptyList()) }
        cw = a.await(); deck = b.await()
        wl = runCatching { App.watchlistRows() }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tabs, key = { it.id }) { t -> Chip(t.label, t == tab, { App.store.homeTab = t.id }) }
            }
            Spacer(Modifier.width(16.dp))
            SearchChip()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Row(Modifier.fillMaxSize()) {
            // Left: continue watching + next up, filtered by the library tab and the selected folders
            Column(Modifier.width(420.dp).fillMaxHeight().background(C.bg2).padding(start = 20.dp, end = 16.dp, top = 18.dp)) {
                val list = cw
                if (list == null) Loading() else {
                    val sel = App.selection()
                    val seen = mutableSetOf<String>()
                    fun once(i: Item) = seen.add(i.guid ?: i.ratingKey)
                    val contAll = App.allow(list).filter { inTab(it) }.sortedByDescending { it.lastViewedAt }.filter { once(it) }
                    val outside = if (sel.isEmpty()) 0 else contAll.count { !App.inSelection(it.titleGuid, sel) }
                    val showAll = cwShowAll && outside > 0
                    val cont = if (showAll) contAll else contAll.filter { App.inSelection(it.titleGuid, sel) }
                    val cwKeys = cont.map { it.ratingKey }.toSet()
                    val next = App.allow(deck).filter { inTab(it) && App.inSelection(it.titleGuid, sel) && it.viewOffset == 0L && it.ratingKey !in cwKeys }.filter { once(it) }
                    val selNames = sel.mapNotNull { k -> if (k == "none") "No folder" else App.store.folders.firstOrNull { it.id == k }?.name }.joinToString(", ")
                    T("Continue watching", 17.sp, FontWeight.Bold)
                    if (selNames.isNotEmpty()) Row(Modifier.padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null, tint = C.accent, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(6.dp)); T(selNames, 12.sp, color = C.accent, maxLines = 1)
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (cont.isEmpty()) item { T(if (selNames.isNotEmpty()) "Nothing in progress in the selected folders." else "Nothing in progress.", 13.sp, color = C.muted, modifier = Modifier.padding(8.dp)) }
                        items(cont, key = { "c" + it.ratingKey }) { ContinueRow(it, false, if (it === cont.first()) firstFr else null) }
                        if (outside > 0) item {
                            Focusable({ cwShowAll = !showAll }, Modifier.fillMaxWidth()) { f ->
                                Row(Modifier.fillMaxWidth().background(if (f) C.bg3 else Color.Transparent).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (showAll) Icons.Rounded.Close else Icons.Rounded.Add, null, tint = C.accent, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    T(if (showAll) "Hide the $outside outside the selected folders" else "$outside more in progress outside these folders · Show", 12.sp, FontWeight.SemiBold, C.accent)
                                }
                            }
                        }
                        if (next.isNotEmpty()) {
                            item { Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(C.line)) }
                            item { Eyebrow("Next up", Modifier.padding(start = 8.dp, bottom = 6.dp)) }
                            items(next.take(12), key = { "n" + it.ratingKey }) { ContinueRow(it, true) }
                        }
                    }
                }
            }
            // Right: watchlist, newest first; folder chips and library tabs filter it
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = 28.dp, top = 18.dp)) {
                val rows = wl
                if (rows == null) { T("Watchlist", 17.sp, FontWeight.Bold); Loading() } else {
                    val inScope = rows.filter { it.w.guid in App.watchGuids && (if (it.s != null) inTab(it.s) else tab.id == "all") }
                    val sel = App.selection()
                    val shown = inScope.filter { App.inSelection(it.w.guid, sel) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("Watchlist", 17.sp, FontWeight.Bold); Spacer(Modifier.width(10.dp)); T("${shown.size} titles", 12.sp, color = C.muted)
                        Spacer(Modifier.weight(1f))
                        TvButton("Open watchlist", { App.go(Screen.Watchlist, root = true) }, kind = Kind.Ghost)
                    }
                    FolderChips(inScope) {}
                    Spacer(Modifier.height(10.dp))
                    if (shown.isEmpty()) T(if (rows.isEmpty()) "Your watchlist is empty. Add titles with the bookmark button on a movie or series." else "Nothing in this view.", 14.sp, color = C.muted)
                    LazyVerticalGrid(GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 30.dp, top = 6.dp)) {
                        items(shown, key = { it.w.guid ?: it.w.ratingKey }) { r -> WlCard(r) }
                    }
                }
            }
        }
    }
}

@Composable
fun WlCard(r: WlRow) {
    if (r.s != null) PosterCard(r.s)
    else Column(Modifier) {
        Focusable({ App.show("This title is not on this server.") }, Modifier.fillMaxWidth().aspectRatio(2f / 3f)) { _ ->
            Box(Modifier.fillMaxSize().background(C.bg3)) {
                coil3.compose.AsyncImage(r.w.thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = .5f)
            }
        }
        T(r.w.title, 13.sp, FontWeight.SemiBold, C.muted, 1, Modifier.padding(top = 7.dp))
        T("Not on server", 12.sp, color = C.muted, maxLines = 1)
    }
}

@Composable
private fun ContinueRow(i: Item, isNext: Boolean, fr: androidx.compose.ui.focus.FocusRequester? = null) {
    val img = if (i.type == "episode") i.thumb ?: i.grandparentArt else i.art ?: i.thumb
    val title = if (i.type == "episode") i.grandparentTitle ?: i.title else i.title
    val sub = if (i.type == "episode") "${i.code} · ${i.title}${if (isNext) " · new" else ""}" else listOfNotNull(i.year?.toString(), "Movie").joinToString(" · ")
    val left = if (isNext) (if (i.duration > 0) fmtDuration(i.duration) else "")
        else if (i.viewOffset > 0 && i.duration > 0) "${((i.duration - i.viewOffset) / 60000).coerceAtLeast(1)} min left" else ""
    Focusable({ App.play(i) }, Modifier.fillMaxWidth(), focusRequester = fr) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) C.bg3 else Color.Transparent).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                Art(img, 280, 158, Modifier.fillMaxSize(), title)
                ProgressLine(i.progress, Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.weight(1f)) {
                T(title, 15.sp, FontWeight.SemiBold, maxLines = 1)
                T(sub, 12.sp, color = C.soft, maxLines = 1)
                if (left.isNotEmpty()) T(left, 12.sp, color = C.muted, maxLines = 1)
                if (i.lastViewedAt > 0) T(watchedText(i.lastViewedAt), 11.sp, color = C.muted, maxLines = 1)
            }
            Icon(Icons.Rounded.PlayArrow, null, tint = if (f) C.text else C.muted, modifier = Modifier.size(20.dp))
        }
    }
}
