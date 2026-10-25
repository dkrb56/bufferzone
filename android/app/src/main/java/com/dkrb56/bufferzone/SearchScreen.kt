package com.dkrb56.bufferzone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** When set, a picked result goes here instead of opening it (Multi-view uses this to add a stream). */
var searchPick: ((Item) -> Unit)? = null

@Composable
fun SearchScreen() {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("all") }
    var results by remember { mutableStateOf<Map<String, List<Item>>?>(null) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = null; return@LaunchedEffect }
        delay(300)
        results = runCatching { App.plex.search(q) }.getOrDefault(emptyMap()).mapValues { App.allow(it.value) }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 30.dp).widthIn(max = 900.dp)) {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        OutlinedTextField(
            query, { query = it },
            // A text field keeps D-pad down for itself; on a TV, down must go to the results.
            Modifier.fillMaxWidth().focusRequester(fr).onPreviewKeyEvent { ev ->
                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.DirectionDown) {
                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true
                } else false
            },
            singleLine = true,
            placeholder = { T(if (searchPick != null) "Add a stream to Multi-view" else "Search movies, series and episodes", 18.sp, color = C.muted) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = C.accent) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, color = C.text),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.lineHover, cursorColor = C.accent, focusedContainerColor = C.bg2, unfocusedContainerColor = C.bg2)
        )
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "All", "show" to "Series", "movie" to "Movies", "episode" to "Episodes").forEach { (k, l) -> Chip(l, tab == k, { tab = k }) }
        }
        val r = results
        if (r == null) { T(if (query.isEmpty()) "Search for a title." else "Type at least 2 characters.", 15.sp, color = C.muted, modifier = Modifier.padding(20.dp)); return@Column }
        val q = query.trim().lowercase()
        val shows = r["show"].orEmpty(); val movies = r["movie"].orEmpty(); val eps = r["episode"].orEmpty()
        val top = if (tab == "all") (shows + movies).firstOrNull { it.title.lowercase().startsWith(q) } ?: shows.firstOrNull() ?: movies.firstOrNull() else null
        val groups = buildList {
            if (top != null) add("Top result" to listOf(top))
            if (tab == "all" || tab == "show") (shows - setOfNotNull(top)).takeIf { it.isNotEmpty() }?.let { add("Series" to it) }
            if (tab == "all" || tab == "episode") eps.takeIf { it.isNotEmpty() }?.let { add("Episodes" to it) }
            if (tab == "all" || tab == "movie") (movies - setOfNotNull(top)).takeIf { it.isNotEmpty() }?.let { add("Movies" to it) }
        }
        if (groups.isEmpty()) { T("No results.", 15.sp, color = C.muted, modifier = Modifier.padding(20.dp)); return@Column }
        LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
            groups.forEach { (head, list) ->
                item { Eyebrow(head, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp)) }
                items(list, key = { head + it.ratingKey }) { SearchRow(it, q) }
            }
        }
    }
}

@Composable
private fun SearchRow(i: Item, q: String) {
    fun hl(text: String) = buildAnnotatedString {
        val lower = text.lowercase()
        var pos = 0
        while (q.isNotEmpty()) {
            val j = lower.indexOf(q, pos)
            if (j < 0) break
            append(text.substring(pos, j))
            withStyle(SpanStyle(color = C.accent)) { append(text.substring(j, j + q.length)) }
            pos = j + q.length
        }
        append(text.substring(pos))
    }
    val pick = searchPick
    Focusable({ if (pick != null) { searchPick = null; App.back(); pick(i) } else App.open(i) }, Modifier.fillMaxWidth()) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) C.accentSoft else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (i.type == "episode") Art(i.thumb, 144, 81, Modifier.width(80.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)))
            else Art(i.thumb, 80, 120, Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f)) {
                if (i.type == "episode") {
                    androidx.compose.material3.Text(hl("${i.grandparentTitle} · ${i.code}"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.text, maxLines = 1)
                    androidx.compose.material3.Text(hl(i.title), fontSize = 12.sp, color = C.muted, maxLines = 1)
                } else {
                    androidx.compose.material3.Text(hl(i.title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.text, maxLines = 1)
                    T(listOfNotNull(if (i.type == "show") "Series" else "Movie", i.year?.toString(),
                        if (i.type == "show" && i.childCount > 0) "${i.childCount} season${if (i.childCount == 1) "" else "s"}" else null,
                        if (i.type == "movie" && i.duration > 0) fmtDuration(i.duration) else null, i.librarySectionTitle).joinToString(" · "), 12.sp, color = C.muted, maxLines = 1)
                }
            }
            if (f && pick == null) Icon(Icons.Rounded.PlayArrow, null, tint = C.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
        }
    }
}
