package com.dkrb56.bufferzone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
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
import kotlinx.coroutines.launch

fun dateText(ts: Long): String {
    if (ts <= 0) return ""
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ts * 1000 }
    return "%d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
}

@Composable
private fun StatusPill(s: Item?) = when {
    s == null -> Pill("Not on server")
    s.type == "show" && s.leafCount > 0 && s.viewedLeafCount >= s.leafCount -> Pill("Watched", "ok")
    s.type == "show" && s.viewedLeafCount > 0 -> Pill("New episodes", "accent")
    s.type == "show" -> Pill("Unwatched")
    s.viewCount > 0 -> Pill("Watched", "ok")
    s.viewOffset > 0 -> Pill("In progress", "info")
    else -> Pill("Unwatched")
}

@Composable
fun WatchlistScreen() {
    var rows by remember { mutableStateOf<List<WlRow>?>(null) }
    var type by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("added") }
    var folderFor by remember { mutableStateOf<String?>(null) }  // guid whose folder menu is open
    var dialog by remember { mutableStateOf<String?>(null) }
    Reload { rows = runCatching { App.watchlistRows() }.getOrDefault(emptyList()) }
    App.store.version

    val all = rows
    Column(Modifier.fillMaxSize()) {
        val items = all?.filter { it.w.guid in App.watchGuids }.orEmpty()
        PageHead("Watchlist", all?.let { "${items.size} titles" })
        if (all == null) { Loading(); return@Column }
        val sel = App.selection()
        val folder = if (sel.size == 1) App.store.folders.firstOrNull { it.id == sel[0] } else null
        var shown = items.filter { App.inSelection(it.w.guid, sel) }
        if (type != "all") shown = shown.filter { it.w.type == type }
        shown = when (sort) {
            "title" -> shown.sortedBy { it.w.title.lowercase() }
            "length" -> shown.sortedByDescending { it.s?.duration ?: it.w.duration }
            else -> shown.sortedByDescending { it.added }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { FolderChips(items) {} }
            TvButton("New folder", { dialog = "new" }, icon = Icons.Rounded.Add, kind = Kind.Ghost)
            if (folder != null) {
                TvButton("Rename", { dialog = "rename" }, kind = Kind.Ghost)
                TvButton("Delete folder", { dialog = "delete" }, kind = Kind.Ghost)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "All", "movie" to "Movies", "show" to "Series").forEach { (k, l) -> Chip(l, type == k, { type = k }) }
            Spacer(Modifier.weight(1f))
            TvButton(when (sort) { "title" -> "Title A–Z"; "length" -> "Length"; else -> "Date added" }, { dialog = "sort" })
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 28.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.bg2).border(1.dp, C.line, RoundedCornerShape(10.dp))) {
            Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Title", Modifier.weight(1f)); Eyebrow("Library", Modifier.width(110.dp)); Eyebrow("Added", Modifier.width(96.dp))
                Eyebrow("Status", Modifier.width(124.dp)); Eyebrow("Length", Modifier.width(70.dp)); Spacer(Modifier.width(132.dp))
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                if (shown.isEmpty()) item { T(if (sel.isNotEmpty()) "No titles in the selected folders. Use the folder button on a title." else "Nothing here.", 14.sp, color = C.muted, modifier = Modifier.padding(20.dp)) }
                items(shown, key = { it.w.guid ?: it.w.ratingKey }) { r -> WatchRow(r) { folderFor = r.w.guid } }
            }
        }
    }

    folderFor?.let { guid -> FolderMenu(guid) { folderFor = null } }
    when (dialog) {
        "new" -> TextDialog("New folder", hint = "Watchlist titles can be in more than one folder.", okLabel = "Create", onDismiss = { dialog = null }) { name ->
            if (name.isNotEmpty()) { val f = App.newFolder(name); App.store.wlSelected = listOf(f.id) }; dialog = null
        }
        "rename" -> {
            val f = App.store.folders.firstOrNull { it.id == App.selection().firstOrNull() }
            if (f != null) TextDialog("Rename folder", f.name, onDismiss = { dialog = null }) { name ->
                if (name.isNotEmpty()) App.store.folders = App.store.folders.map { if (it.id == f.id) Folder(it.id, name, it.guids) else it }; dialog = null
            }
        }
        "delete" -> {
            val f = App.store.folders.firstOrNull { it.id == App.selection().firstOrNull() }
            if (f != null) ConfirmDialog("Delete \"${f.name}\"?", "The titles stay on your watchlist. Only the folder goes away.", "Delete", { dialog = null }) {
                App.store.folders = App.store.folders.filter { it.id != f.id }
                App.store.wlSelected = App.selection() - f.id
            }
        }
        "sort" -> OptionDialog("Sort", listOf("added" to "Date added", "title" to "Title A–Z", "length" to "Length"), sort, { dialog = null }) { sort = it }
    }
}

@Composable
private fun WatchRow(r: WlRow, onFolders: () -> Unit) {
    val w = r.w; val s = r.s
    val folderNames = App.store.folders.filter { w.guid in it.guids }.joinToString(", ") { it.name }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Focusable({ if (s != null) App.open(s) else App.show("This title is not on this server.") }, Modifier.weight(1f)) { f ->
            Row(Modifier.fillMaxWidth().background(if (f) C.bg3 else Color.Transparent).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(46.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(C.bg3)) {
                    if (s?.thumb != null) Art(s.thumb, 128, 192, Modifier.fillMaxSize())
                    else coil3.compose.AsyncImage(w.thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                Column(Modifier.weight(1f)) {
                    T(w.title, 16.sp, FontWeight.SemiBold, if (f) C.accent else C.text, 1)
                    T(listOfNotNull(w.year?.toString(), if (s?.type == "show" && s.childCount > 0) "${s.childCount} season${if (s.childCount == 1) "" else "s"}" else null).joinToString(" · "), 12.sp, color = C.muted)
                    if (folderNames.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Outlined.Folder, null, tint = C.accent, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(5.dp)); T(folderNames, 12.sp, color = C.accent, maxLines = 1)
                    }
                }
            }
        }
        T(s?.librarySectionTitle ?: "—", 13.sp, color = C.muted, maxLines = 1, modifier = Modifier.width(110.dp))
        T(dateText(r.added), 12.sp, color = C.muted, modifier = Modifier.width(96.dp))
        Box(Modifier.width(124.dp)) { StatusPill(s) }
        T(if (s == null) "" else if (s.type == "show") "${s.leafCount} eps" else if (s.duration > 0) fmtDuration(s.duration) else "", 12.sp, color = C.muted, modifier = Modifier.width(70.dp))
        Row(Modifier.width(132.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            IconBtn(Icons.Outlined.Folder, onFolders, size = 40.dp)
            if (s != null) IconBtn(Icons.Rounded.PlayArrow, { App.play(s) }, size = 40.dp) else Spacer(Modifier.width(40.dp))
            IconBtn(Icons.Rounded.Close, {
                val g = w.guid ?: return@IconBtn
                App.scope.launch { runCatching { App.removeFromWatchlist(g); App.show("Removed from watchlist") }.onFailure { App.show("Failed: ${it.message}") } }
            }, size = 40.dp)
        }
    }
}

/** Folder menu for one title: a check per folder, plus "New folder". */
@Composable
private fun FolderMenu(guid: String, onDismiss: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        TextDialog("New folder", okLabel = "Create", onDismiss = { naming = false }) { name ->
            if (name.isNotEmpty()) { val f = App.newFolder(name); App.setInFolder(f.id, guid, true); App.show("Added to ${f.name}") }
            naming = false; onDismiss()
        }
        return
    }
    BzDialog(onDismiss) {
        App.store.version
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T("Folders", 20.sp, FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
            val folders = App.store.folders
            if (folders.isEmpty()) T("No folders yet.", 14.sp, color = C.muted)
            folders.forEach { f ->
                val on = guid in f.guids
                Focusable({ App.setInFolder(f.id, guid, !on) }, Modifier.fillMaxWidth()) { foc ->
                    Row(Modifier.fillMaxWidth().background(if (foc) C.bg3 else if (on) C.accentSoft else Color.Transparent).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(26.dp)) { if (on) Icon(Icons.Rounded.Check, null, tint = C.accent, modifier = Modifier.size(18.dp)) }
                        T(f.name, 15.sp, FontWeight.Medium)
                    }
                }
            }
            Focusable({ naming = true }, Modifier.fillMaxWidth()) { foc ->
                Row(Modifier.fillMaxWidth().background(if (foc) C.bg3 else Color.Transparent).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(26.dp)) { Icon(Icons.Rounded.Add, null, tint = C.accent, modifier = Modifier.size(18.dp)) }
                    T("New folder…", 15.sp, FontWeight.Medium)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) { TvButton("Done", onDismiss, kind = Kind.Primary) }
        }
    }
}
