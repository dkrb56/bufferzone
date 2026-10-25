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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun MovieScreen(key: String) {
    var m by remember(key) { mutableStateOf<Item?>(null) }
    Reload(key) { m = runCatching { App.plex.metadata(key) }.getOrNull() }
    App.store.version
    val item = m ?: return Loading()
    if (!App.itemAllowed(item)) { T("This title is not available for this profile.", modifier = Modifier.padding(40.dp)); return }
    val part = item.media.firstOrNull()?.parts?.firstOrNull()
    val audios = part?.streams?.filter { it.streamType == 2 }.orEmpty()
    val subs = part?.streams?.filter { it.streamType == 3 }.orEmpty()
    var audio by remember(key) { mutableStateOf<Long?>(null) }        // null = automatic
    var sub by remember(key) { mutableStateOf<Long?>(-1) }            // -1 = automatic, 0 = off
    var quality by remember(key) { mutableStateOf(App.store.quality) }
    var dialog by remember { mutableStateOf<String?>(null) }
    fun play(resume: Boolean) = App.play(item, resume, audioId = audio, subId = if (sub == -1L) null else sub, subPreset = sub != -1L, quality = quality)
    val first = remember { FocusRequester() }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(380.dp).fillMaxHeight().background(C.bg2).padding(horizontal = 28.dp, vertical = 22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Art(item.thumb, 400, 600, Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)), item.title)
            T(item.title, 28.sp, FontWeight.SemiBold)
            T(listOfNotNull(item.year?.toString(), if (item.duration > 0) fmtDuration(item.duration) else null, item.contentRating, item.audienceRating?.let { "★ %.1f".format(it) }).joinToString(" · "), 14.sp, color = C.soft)
            if (item.viewOffset > 0) {
                TvButton("Resume from ${fmtTime(item.viewOffset / 1000.0)}", { play(true) }, Modifier.fillMaxWidth(), Icons.Rounded.PlayArrow, Kind.Primary, true, first)
                TvButton("Play from start", { play(false) }, Modifier.fillMaxWidth(), Icons.Rounded.Replay, large = true)
            } else TvButton("Play", { play(false) }, Modifier.fillMaxWidth(), Icons.Rounded.PlayArrow, Kind.Primary, true, first)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val inWl = App.inWatchlist(item)
                IconBtn(if (inWl) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, { App.scope.launch { App.toggleWatchlist(item) } }, on = inWl)
                IconBtn(Icons.Rounded.Check, { App.scope.launch { App.toggleWatched(item) } }, on = item.isWatched)
                IconBtn(Icons.Outlined.PushPin, { App.togglePin(item) }, on = App.isPinned(item.ratingKey))
                if (App.multiviewAllowed) IconBtn(Icons.Outlined.GridView, { MultiView.add(item); App.go(Screen.MultiView) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Art(item.art, 1280, 560, Modifier.fillMaxWidth().aspectRatio(16f / 7f).clip(RoundedCornerShape(10.dp))) }
            item.summary?.let { s -> item { T(s, 15.sp, color = C.body, modifier = Modifier.widthIn(max = 900.dp)) } }
            if (item.directors.isNotEmpty()) item { T("Director  ${item.directors.joinToString(", ")}", 13.sp, color = C.muted) }
            if (item.roles.isNotEmpty()) item { T("Cast  ${item.roles.take(8).joinToString(", ")}", 13.sp, color = C.muted) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PresetButton("Audio", if (audio == null) "Automatic" else audios.firstOrNull { it.id == audio }?.displayTitle ?: "Automatic", Modifier.weight(1f)) { dialog = "audio" }
                    PresetButton("Subtitles", when (sub) { -1L -> "Automatic"; 0L -> "Off"; else -> subs.firstOrNull { it.id == sub }?.displayTitle ?: "Automatic" }, Modifier.weight(1f)) { dialog = "subs" }
                    PresetButton("Quality", QUALITY.firstOrNull { it.id == quality }?.label ?: quality, Modifier.weight(1f)) { dialog = "quality" }
                }
            }
            item.media.firstOrNull()?.let { md ->
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.bg2).border(1.dp, C.line, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TechRow("Video", listOfNotNull(md.videoResolution?.let { if (it.all(Char::isDigit)) "${it}p" else it.uppercase() }, md.videoCodec.uppercase(), md.container.uppercase(), md.bitrate?.let { "%.1f Mbps".format(it / 1000.0) }).joinToString(" · "))
                        TechRow("Audio", audios.joinToString(" · ") { it.displayTitle }.ifEmpty { "—" })
                        TechRow("Subtitles", subs.joinToString(" · ") { it.displayTitle }.ifEmpty { "—" })
                    }
                }
            }
        }
    }
    // Also when the player closes: the focus would otherwise land in the sidebar.
    LaunchedEffect(key, App.playback == null) { if (App.playback == null) runCatching { first.requestFocus() } }

    when (dialog) {
        "audio" -> OptionDialog("Audio", listOf<Pair<Long?, String>>(null to "Automatic") + audios.map { it.id to it.displayTitle }, audio, { dialog = null }) { audio = it }
        "subs" -> OptionDialog("Subtitles", listOf(-1L to "Automatic", 0L to "Off") + subs.map { it.id to it.displayTitle }, sub, { dialog = null }) { sub = it }
        "quality" -> OptionDialog("Quality", QUALITY.map { it.id to it.label }, quality, { dialog = null }) { quality = it }
    }
}

@Composable
private fun PresetButton(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(label, 12.sp, FontWeight.SemiBold, C.muted)
        Focusable(onClick, Modifier.fillMaxWidth()) { f ->
            Box(Modifier.fillMaxWidth().background(if (f) C.bgHover else C.bg3).padding(horizontal = 12.dp, vertical = 10.dp)) { T(value, 14.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun TechRow(k: String, v: String) = Row {
    T(k, 13.sp, color = C.muted, modifier = Modifier.width(90.dp)); T(v, 13.sp)
}

@Composable
fun ShowScreen(s: Screen.Show) {
    var show by remember(s.key) { mutableStateOf<Item?>(null) }
    var seasons by remember(s.key) { mutableStateOf<List<Item>>(emptyList()) }
    var leaves by remember(s.key) { mutableStateOf<List<Item>>(emptyList()) }
    var season by remember(s.key) { mutableStateOf(s.season) }
    Reload(s.key) {
        show = runCatching { App.plex.metadata(s.key) }.getOrNull()
        seasons = runCatching { App.plex.children(s.key) }.getOrDefault(emptyList())
        leaves = runCatching { App.plex.allLeaves(s.key) }.getOrDefault(emptyList())
    }
    App.store.version
    val sh = show ?: return Loading()
    if (!App.itemAllowed(sh)) { T("This series is not available for this profile.", modifier = Modifier.padding(40.dp)); return }
    val upNext = leaves.firstOrNull { it.viewOffset > 0 && it.viewCount == 0 } ?: leaves.firstOrNull { it.viewCount == 0 } ?: leaves.firstOrNull()
    val allWatched = leaves.isNotEmpty() && leaves.all { it.viewCount > 0 }
    val cur = season ?: upNext?.parentRatingKey ?: seasons.firstOrNull()?.ratingKey
    val eps = leaves.filter { it.parentRatingKey == cur }
    val seasonItem = seasons.firstOrNull { it.ratingKey == cur }
    val watchedN = eps.count { it.viewCount > 0 }
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(380.dp)) {
                Art(sh.art, 1600, 900, Modifier.fillMaxSize().graphicsLayer { alpha = .35f })
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.bg, C.bg.copy(alpha = .6f), C.bg.copy(alpha = .3f)))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, C.bg))))
                Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 28.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.Bottom) {
                    Art(sh.thumb, 352, 528, Modifier.width(176.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)), sh.title)
                    Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        T(sh.title, 40.sp, FontWeight.SemiBold, maxLines = 2)
                        T(listOfNotNull(sh.year?.toString(), if (sh.childCount > 0) "${sh.childCount} season${if (sh.childCount == 1) "" else "s"}" else null, sh.contentRating,
                            sh.audienceRating?.let { "★ %.1f".format(it) }, sh.genres.take(2).joinToString(", ").ifEmpty { null }).joinToString(" · "), 14.sp, color = C.soft)
                        sh.summary?.let { T(it, 14.sp, color = C.body, maxLines = 3, modifier = Modifier.widthIn(max = 620.dp)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            if (upNext != null) TvButton("${if (upNext.viewOffset > 0 && upNext.viewCount == 0) "Resume" else "Play"} ${upNext.code}", { App.play(upNext) }, icon = Icons.Rounded.PlayArrow, kind = Kind.Primary, large = true, focusRequester = first)
                            TvButton(if (allWatched) "Mark series unwatched" else "Mark series watched", { App.scope.launch { App.toggleWatched(sh, allWatched) } }, icon = Icons.Rounded.Check, large = true)
                            val inWl = App.inWatchlist(sh)
                            IconBtn(if (inWl) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, { App.scope.launch { App.toggleWatchlist(sh) } }, on = inWl)
                            IconBtn(Icons.Outlined.PushPin, { App.togglePin(sh) }, on = App.isPinned(sh.ratingKey))
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(seasons, key = { it.ratingKey }) { se -> Chip(se.title, se.ratingKey == cur, { season = se.ratingKey }, check = false) }
                }
                T("${eps.size} episodes · $watchedN watched", 12.sp, color = C.muted, modifier = Modifier.padding(horizontal = 12.dp))
                if (seasonItem != null) TvButton(if (eps.isNotEmpty() && watchedN == eps.size) "Mark season unwatched" else "Mark season watched",
                    { App.scope.launch { App.toggleWatched(seasonItem, eps.isNotEmpty() && watchedN == eps.size) } }, kind = Kind.Ghost)
            }
        }
        items(eps, key = { it.ratingKey }) { e -> EpisodeRow(e, e.ratingKey == s.focus) }
    }
    LaunchedEffect(s.key, show != null, App.playback == null) { if (App.playback == null) runCatching { first.requestFocus() } }
}

@Composable
private fun EpisodeRow(e: Item, focus: Boolean) {
    val watched = e.viewCount > 0
    val inProgress = e.progress > 0 && !watched
    Row(
        Modifier.padding(horizontal = 18.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (inProgress) C.bg2 else Color.Transparent)
            .border(1.dp, if (inProgress || focus) C.accent else Color.Transparent, RoundedCornerShape(10.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Focusable({ App.play(e) }, Modifier.width(192.dp).aspectRatio(16f / 9f), scale = 1.04f) { f ->
            Art(e.thumb, 384, 216, Modifier.fillMaxSize())
            if (f) Box(Modifier.fillMaxSize().background(Color(0x59000000)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            ProgressLine(e.progress, Modifier.align(Alignment.BottomStart))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row { T(e.code, 15.sp, FontWeight.SemiBold, C.accent); Spacer(Modifier.width(8.dp)); T(e.title, 15.sp, FontWeight.SemiBold, maxLines = 1) }
            T(listOfNotNull(if (e.duration > 0) fmtDuration(e.duration) else null, e.originallyAvailableAt, if (inProgress) "${fmtTime(e.viewOffset / 1000.0)} watched" else null).joinToString(" · "), 12.sp, color = C.muted)
            e.summary?.let { T(it, 13.sp, color = C.dim, maxLines = 3, modifier = Modifier.padding(top = 4.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (inProgress) TvButton("From start", { App.play(e, resume = false) })
            Focusable({ App.scope.launch { App.toggleWatched(e) } }, shape = CircleShape) { f ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(if (watched) C.ok else Color.Transparent).border(1.dp, if (watched) C.ok else if (f) C.text else C.lineHover, CircleShape), contentAlignment = Alignment.Center) {
                    if (watched) Icon(Icons.Rounded.Check, null, tint = C.okInk, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
