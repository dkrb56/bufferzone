package com.dkrb56.bufferzone

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/** Multi-view: up to four streams. One tile has sound; each tile is its own stream on the server. */
object MultiView {
    class Tile(val item: Item, val pb: Playback)
    val tiles = mutableStateListOf<Tile>()
    var audioIndex by mutableIntStateOf(0)
    private var pending: Item? = null
    private lateinit var ctx: Context

    fun bind(context: Context) { ctx = context.applicationContext; pending?.let { pending = null; add(it) } }

    fun add(item: Item) {
        if (!::ctx.isInitialized) { pending = item; return }
        if (tiles.size >= 4) { App.show("Multi-view shows up to four streams."); return }
        App.scope.launchSafe {
            val playable = App.resolvePlayable(item) ?: run { App.show("Nothing to play."); return@launchSafe }
            val pb = Playback(ctx, tile = true)
            tiles += Tile(playable, pb)
            applyVolumes()
            pb.play(PlayRequest(playable, if (playable.viewOffset > 0 && playable.viewCount == 0) playable.viewOffset / 1000.0 else 0.0))
        }
    }

    fun remove(i: Int) {
        val t = tiles.removeAt(i)
        t.pb.release()
        if (audioIndex >= tiles.size) audioIndex = 0
        applyVolumes()
    }

    fun setAudio(i: Int) { audioIndex = i; applyVolumes() }
    fun applyVolumes() = tiles.forEachIndexed { i, t -> t.pb.player.volume = if (i == audioIndex) 1f else 0f }
    fun pauseAll() = tiles.forEach { it.pb.player.pause() }
    fun resumeAll() = tiles.forEach { it.pb.player.play() }
    fun clear() { tiles.forEach { it.pb.release() }; tiles.clear(); audioIndex = 0 }
}

private fun kotlinx.coroutines.CoroutineScope.launchSafe(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
    launch { runCatching { block() }.onFailure { App.show(it.message ?: "Error") } }

@OptIn(UnstableApi::class)
@Composable
fun MultiViewScreen() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MultiView.bind(ctx); MultiView.resumeAll() }
    DisposableEffect(Unit) { onDispose { MultiView.pauseAll() } }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val layout = App.store.mvLayout
    val tiles = MultiView.tiles
    val first = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().background(C.bg0)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Exit multi-view", { MultiView.clear(); App.go(Screen.Home, root = true) }, icon = Icons.AutoMirrored.Rounded.ArrowBack, kind = Kind.Ghost)
            T("Multi-view", 16.sp, FontWeight.Bold)
            val audioTile = tiles.getOrNull(MultiView.audioIndex)
            T(if (tiles.isEmpty()) "Add up to four streams" else "${tiles.size} stream${if (tiles.size == 1) "" else "s"} · audio from ${audioTile?.pb?.meta?.let { if (it.type == "episode") it.grandparentTitle else it.title } ?: "…"}", 13.sp, color = C.muted)
            Spacer(Modifier.weight(1f))
            IconBtn(Icons.Outlined.GridView, { App.store.mvLayout = "2x2" }, on = layout == "2x2", size = 40.dp)
            IconBtn(Icons.Outlined.ViewColumn, { App.store.mvLayout = "side" }, on = layout == "side", size = 40.dp)
            IconBtn(Icons.Outlined.ViewQuilt, { App.store.mvLayout = "1p3" }, on = layout == "1p3", size = 40.dp)
            TvButton("Add stream", { if (tiles.size < 4) { searchPick = { MultiView.add(it) }; App.go(Screen.Search) } }, icon = Icons.Rounded.Add, focusRequester = if (tiles.isEmpty()) first else null)
        }
        Box(Modifier.fillMaxSize().padding(6.dp)) {
            if (tiles.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.GridView, null, tint = C.muted, modifier = Modifier.size(40.dp))
                    T("Add a stream to start. Each stream counts against the server's limit.", 15.sp, color = C.muted)
                }
            } else {
                @Composable fun TileView(i: Int, mod: Modifier) {
                    val t = tiles.getOrNull(i) ?: return
                    val isAudio = i == MultiView.audioIndex
                    Focusable({ menuFor = i }, mod.padding(3.dp), RoundedCornerShape(8.dp), focusRequester = if (i == 0) first else null) { f ->
                        Box(Modifier.fillMaxSize().background(Color.Black).border(2.dp, if (isAudio) C.accent else if (f) C.soft else Color.Transparent, RoundedCornerShape(8.dp))) {
                            AndroidView({ c -> PlayerView(c).apply { useController = false; isFocusable = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; player = t.pb.player } },
                                Modifier.fillMaxSize(), update = { it.player = t.pb.player })
                            Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                                if (isAudio) Pill("Audio", "accent") else Icon(Icons.Outlined.VolumeOff, null, tint = C.muted, modifier = Modifier.size(18.dp))
                            }
                            val m = t.pb.meta
                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))).padding(start = 12.dp, end = 12.dp, top = 30.dp, bottom = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    T((if (m?.type == "episode") m.grandparentTitle else m?.title) ?: t.item.title, 15.sp, FontWeight.SemiBold, maxLines = 1)
                                    Spacer(Modifier.width(8.dp))
                                    T(if (m?.type == "episode") m.code else m?.year?.toString() ?: "", 12.sp, color = C.muted)
                                    Spacer(Modifier.weight(1f))
                                    T(fmtTime(t.pb.position), 12.sp, color = C.soft)
                                }
                                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
                                    Box(Modifier.fillMaxWidth((t.pb.position / t.pb.duration.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(if (isAudio) C.accent else C.soft))
                                }
                            }
                            t.pb.error?.let { e -> T(e, 13.sp, color = C.muted, modifier = Modifier.align(Alignment.Center).padding(20.dp)) }
                        }
                    }
                }
                when (layout) {
                    "side" -> Column(Modifier.fillMaxSize()) {
                        tiles.indices.chunked(2).forEach { row -> Row(Modifier.weight(1f).fillMaxWidth()) { row.forEach { TileView(it, Modifier.weight(1f).fillMaxHeight()) }; if (row.size == 1) Spacer(Modifier.weight(1f)) } }
                    }
                    "1p3" -> Row(Modifier.fillMaxSize()) {
                        TileView(0, Modifier.weight(3f).fillMaxHeight())
                        if (tiles.size > 1) Column(Modifier.weight(1f).fillMaxHeight()) { (1 until 4).forEach { i -> if (i < tiles.size) TileView(i, Modifier.weight(1f).fillMaxWidth()) else Spacer(Modifier.weight(1f)) } }
                    }
                    else -> Column(Modifier.fillMaxSize()) {
                        (0 until 2).forEach { r -> Row(Modifier.weight(1f).fillMaxWidth()) { (0 until 2).forEach { c -> val i = r * 2 + c; if (i < tiles.size) TileView(i, Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f)) } } }
                    }
                }
            }
        }
    }
    LaunchedEffect(tiles.size) { runCatching { first.requestFocus() } }

    menuFor?.let { i ->
        OptionDialog("Stream ${i + 1}", listOf("audio" to "Sound from this stream", "remove" to "Remove this stream"), null, { menuFor = null }) { choice ->
            if (choice == "audio") MultiView.setAudio(i) else MultiView.remove(i)
        }
    }
}
