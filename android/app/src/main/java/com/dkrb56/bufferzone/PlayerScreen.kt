package com.dkrb56.bufferzone

import android.view.KeyEvent as AKey
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
fun styleSubtitles(view: SubtitleView?) {
    view ?: return
    val st = App.store
    val color = runCatching { android.graphics.Color.parseColor(st.subtitleColor) }.getOrDefault(android.graphics.Color.WHITE)
    val style = when (st.subtitleBackground) {
        "box" -> CaptionStyleCompat(color, 0xB8000000.toInt(), android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK, null)
        "none" -> CaptionStyleCompat(color, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK, null)
        else -> CaptionStyleCompat(color, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null)
    }
    view.setApplyEmbeddedStyles(false)
    view.setApplyEmbeddedFontSizes(false)
    view.setStyle(style)
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * st.subtitleSize / 100f)
    view.setBottomPaddingFraction(st.subtitlePosition / 100f)
    view.alpha = st.subtitleOpacity / 100f
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(req: PlayRequest) {
    val ctx = LocalContext.current
    val pb = remember { Playback(ctx) }
    var current by remember { mutableStateOf(req) }
    DisposableEffect(Unit) { onDispose { pb.release() } }
    LaunchedEffect(current) { pb.play(current) }

    var controls by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pop by remember { mutableStateOf<String?>(null) }
    var nextCancelled by remember(current) { mutableStateOf(false) }
    var countdown by remember(current) { mutableIntStateOf(-1) }
    val skipped = remember(current) { mutableSetOf<Marker>() }
    val rootFr = remember { FocusRequester() }
    val playFr = remember { FocusRequester() }
    val nextFr = remember { FocusRequester() }
    val errFr = remember { FocusRequester() }

    fun exit() { App.playback = null; App.dataVersion++ }
    fun playNext() {
        val ep = pb.nextEp ?: return
        pb.markWatched()
        countdown = -1
        current = PlayRequest(ep, if (ep.viewOffset > 0 && ep.viewCount == 0) ep.viewOffset / 1000.0 else 0.0)
    }
    pb.onEnded = {
        pb.markWatched()
        if (pb.isEpisode && pb.nextEp != null && App.store.autoPlayNext && !nextCancelled) playNext() else exit()
    }

    val t = pb.position
    val marker = pb.activeMarker(t)
    val hasNext = pb.isEpisode && pb.nextEp != null
    // Skip target: intro, or credits (final credits with a next episode become "Next Episode").
    val skipTarget = when {
        marker == null -> null
        marker.type == "intro" -> marker
        marker.type == "credits" && !(marker.final && hasNext && App.store.autoPlayNext && !nextCancelled) -> marker
        else -> null
    }
    fun doSkip() {
        val m = skipTarget ?: return
        skipped += m
        if (m.type == "credits" && m.final) { if (hasNext) playNext() else exit() } else pb.seekTo(m.end)
    }
    // Automatic skips
    LaunchedEffect(marker) {
        val m = marker ?: return@LaunchedEffect
        if (m in skipped) return@LaunchedEffect
        if (m.type == "intro" && App.store.autoSkipIntro) { skipped += m; pb.seekTo(m.end); App.show("Intro skipped") }
        else if (m.type == "credits" && App.store.autoSkipCredits && (!m.final || hasNext)) { skipped += m; if (m.final) playNext() else pb.seekTo(m.end) }
    }
    // Next-episode card: at the final credits marker, or 30 s before the end.
    val credits = pb.markers.firstOrNull { it.type == "credits" && it.final } ?: pb.markers.filter { it.type == "credits" }.maxByOrNull { it.start }
    val nextAt = credits?.start ?: (pb.duration - 30)
    val showNext = hasNext && App.store.autoPlayNext && !nextCancelled && pb.duration > 0 && t >= nextAt && t < pb.duration - 0.5
    LaunchedEffect(showNext) {
        if (!showNext) { countdown = -1; return@LaunchedEffect }
        countdown = App.store.nextCountdown
        controls = false
        runCatching { nextFr.requestFocus() }
        while (countdown > 0) { delay(1000); if (pb.playing) countdown-- }
        if (countdown == 0) playNext()
    }
    // Controls hide after 4 s without input while playing.
    LaunchedEffect(controls, lastInput, pb.playing, pop) {
        if (controls && pb.playing && pop == null) { delay(4000); controls = false }
    }
    LaunchedEffect(controls) {
        if (controls) runCatching { playFr.requestFocus() } else if (!showNext) runCatching { rootFr.requestFocus() }
    }
    LaunchedEffect(pb.error) { if (pb.error != null) runCatching { errFr.requestFocus() } }

    BackHandler {
        when {
            pop != null -> pop = null
            showNext -> { nextCancelled = true; runCatching { rootFr.requestFocus() } }
            controls && pb.error == null -> controls = false
            else -> exit()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFr).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                val code = ev.key.nativeKeyCode
                // Media keys always work.
                when (code) {
                    AKey.KEYCODE_MEDIA_PLAY_PAUSE, AKey.KEYCODE_MEDIA_PLAY, AKey.KEYCODE_MEDIA_PAUSE -> { pb.togglePlay(); controls = true; return@onPreviewKeyEvent true }
                    AKey.KEYCODE_MEDIA_FAST_FORWARD -> { pb.seekBy(App.store.skipForward.toDouble()); return@onPreviewKeyEvent true }
                    AKey.KEYCODE_MEDIA_REWIND -> { pb.seekBy(-App.store.skipBack.toDouble()); return@onPreviewKeyEvent true }
                    AKey.KEYCODE_MEDIA_NEXT -> { playNext(); return@onPreviewKeyEvent true }
                }
                if (controls || pop != null || showNext || pb.error != null) return@onPreviewKeyEvent false
                // Controls hidden: OK plays/pauses (or skips), left/right seek, up/down show the controls.
                when (code) {
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER -> { if (skipTarget != null) doSkip() else { pb.togglePlay(); controls = true }; true }
                    AKey.KEYCODE_DPAD_LEFT -> { pb.seekBy(-App.store.skipBack.toDouble()); true }
                    AKey.KEYCODE_DPAD_RIGHT -> { pb.seekBy(App.store.skipForward.toDouble()); true }
                    AKey.KEYCODE_DPAD_UP, AKey.KEYCODE_DPAD_DOWN -> { controls = true; true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    player = pb.player
                    isFocusable = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(false)
                    pb.applyStyle = { styleSubtitles(subtitleView) }
                    styleSubtitles(subtitleView)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (pb.buffering && pb.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(54.dp), color = C.accent)

        // Skip button
        if (skipTarget != null && !showNext && pb.error == null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (controls) 150.dp else 60.dp)) {
                Focusable({ doSkip() }, shape = RoundedCornerShape(10.dp)) { f ->
                    Box(Modifier.background(if (f) Color.White else Color(0xE60F0F10)).border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(10.dp)).padding(horizontal = 22.dp, vertical = 12.dp)) {
                        T(when { skipTarget.type == "intro" -> "Skip Intro"; skipTarget.final && hasNext -> "Next Episode"; else -> "Skip Credits" } + if (!controls) "  (OK)" else "",
                            15.sp, FontWeight.SemiBold, if (f) Color.Black else Color.White)
                    }
                }
            }
        }

        // Next-episode card
        if (showNext) pb.nextEp?.let { ep ->
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 60.dp).width(520.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF20F0F10)).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Art(ep.thumb, 340, 192, Modifier.width(170.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { T("Next episode in ", 12.sp, color = C.muted); T("${countdown.coerceAtLeast(0)}", 12.sp, FontWeight.Bold, C.accent); T(" s", 12.sp, color = C.muted) }
                    T("${ep.code} · ${ep.title}", 15.sp, FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton("Play now", { playNext() }, kind = Kind.Primary, focusRequester = nextFr)
                        TvButton("Cancel", { nextCancelled = true; runCatching { rootFr.requestFocus() } })
                    }
                }
            }
        }

        if (controls && pb.error == null) Controls(pb, playFr, onPop = { pop = it }, onBack = { exit() }, onNext = { playNext() }, onInput = { lastInput = System.currentTimeMillis() },
            onHide = { controls = false; runCatching { rootFr.requestFocus() } })

        pb.error?.let { msg ->
            Column(Modifier.fillMaxSize().background(Color(0xD9000000)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                T("Playback failed", 22.sp, FontWeight.SemiBold)
                T(msg, 15.sp, color = C.muted)
                TvButton("Back", { exit() }, focusRequester = errFr)
            }
        }
    }

    when (pop) {
        "tracks" -> TracksDialog(pb) { pop = null }
        "quality" -> OptionDialog("Quality", QUALITY.map { it.id to it.label }, pb.quality, { pop = null }) { pb.changeQuality(it) }
        "episodes" -> EpisodesDialog(pb, { pop = null }) { ep -> pop = null; current = PlayRequest(ep, if (ep.viewOffset > 0 && ep.viewCount == 0) ep.viewOffset / 1000.0 else 0.0) }
    }
}

@Composable
private fun Controls(pb: Playback, playFr: FocusRequester, onPop: (String) -> Unit, onBack: () -> Unit, onNext: () -> Unit, onInput: () -> Unit, onHide: () -> Unit) {
    val m = pb.meta
    Box(Modifier.fillMaxSize()) {
        // Top scrim: title, episode, status
        Row(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color(0xC7000000), Color.Transparent))).padding(horizontal = 40.dp, vertical = 24.dp)) {
            Column(Modifier.weight(1f)) {
                T(if (pb.isEpisode) m?.grandparentTitle ?: "" else m?.title ?: "", 20.sp, FontWeight.Bold, maxLines = 1)
                T(if (pb.isEpisode) "${m?.code} · ${m?.title}" else listOfNotNull(m?.year?.toString(), m?.duration?.takeIf { it > 0 }?.let { fmtDuration(it) }).joinToString(" · "), 14.sp, color = C.soft, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pb.mode == "direct") Pill("Direct Play", "ok") else if (pb.mode == "hls") Pill("Converted")
                pb.media?.let { md ->
                    T(listOfNotNull(md.videoResolution?.let { if (it.all(Char::isDigit)) "${it}p" else it.uppercase() }, md.videoCodec.uppercase(), md.bitrate?.let { "%.1f Mbps".format(it / 1000.0) }).joinToString(" · "), 13.sp, color = C.muted)
                }
            }
        }
        // Bottom scrim: seek bar and buttons
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE0000000)))).padding(start = 40.dp, end = 40.dp, top = 80.dp, bottom = 28.dp)) {
            SeekBar(pb, onInput, onHide)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PBtn(if (pb.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play / Pause", playFr) { pb.togglePlay() }
                val back = App.store.skipBack; val fwd = App.store.skipForward
                PBtn(when (back) { 5 -> Icons.Rounded.Replay5; 10 -> Icons.Rounded.Replay10; 30 -> Icons.Rounded.Replay30; else -> Icons.Rounded.Replay }, "Back $back s") { pb.seekBy(-back.toDouble()) }
                PBtn(when (fwd) { 10 -> Icons.Rounded.Forward10; 30 -> Icons.Rounded.Forward30; else -> Icons.Rounded.FastForward }, "Forward $fwd s") { pb.seekBy(fwd.toDouble()) }
                if (pb.isEpisode) PBtn(Icons.Rounded.SkipNext, "Next episode", enabled = pb.nextEp != null) { onNext() }
                if (App.store.audioBoost > 100) Box(Modifier.clip(RoundedCornerShape(6.dp)).background(C.accentSoft).padding(horizontal = 7.dp, vertical = 3.dp)) { T("${App.store.audioBoost}%", 11.sp, FontWeight.Bold, C.accent) }
                T("${fmtTime(pb.position)} / ${fmtTime(pb.duration)}", 14.sp, color = Color(0xFFE4E4EA), modifier = Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                Focusable({ onPop("quality") }, shape = RoundedCornerShape(8.dp)) { f ->
                    Row(Modifier.background(if (f) Color(0x24FFFFFF) else Color.Transparent).border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Speed, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        T(if (pb.mode == "direct") "Direct Play" else if (pb.quality == "direct") "Original" else QUALITY.firstOrNull { it.id == pb.quality }?.short ?: "Quality", 13.sp, FontWeight.SemiBold)
                    }
                }
                PBtn(Icons.Outlined.ClosedCaption, "Audio and subtitles", on = pb.subId != null) { onPop("tracks") }
                if (pb.isEpisode) PBtn(Icons.AutoMirrored.Rounded.PlaylistPlay, "Episodes") { onPop("episodes") }
                PBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { onBack() }
            }
        }
    }
}

@Composable
private fun PBtn(icon: ImageVector, label: String, fr: FocusRequester? = null, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Focusable({ if (enabled) onClick() }, shape = RoundedCornerShape(8.dp), focusRequester = fr, frame = false) { f ->
        Box(Modifier.size(48.dp).background(if (f) Color(0x40FFFFFF) else if (on) C.accentSoft else Color.Transparent), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (!enabled) Color(0x59FFFFFF) else if (on) C.accent else Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

/** Focusable seek bar: left/right move 10 s while it has focus, up hides the controls. Intro and credits show in blue. */
@Composable
private fun SeekBar(pb: Playback, onInput: () -> Unit, onHide: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val d = pb.duration.coerceAtLeast(1.0)
    Box(
        Modifier.fillMaxWidth().height(24.dp).focusable(interactionSource = interaction)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key.nativeKeyCode) {
                    AKey.KEYCODE_DPAD_LEFT -> { pb.seekBy(-10.0); onInput(); true }
                    AKey.KEYCODE_DPAD_RIGHT -> { pb.seekBy(10.0); onInput(); true }
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER -> { pb.togglePlay(); true }
                    AKey.KEYCODE_DPAD_UP -> { onHide(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val h = if (focused) 6.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF)))
        Box(Modifier.fillMaxWidth((pb.player.bufferedPosition / 1000.0 / d).toFloat().coerceIn(0f, 1f)).height(h).background(Color(0x52FFFFFF)))
        pb.markers.forEach { mk ->
            Box(Modifier.fillMaxWidth((mk.end / d).toFloat().coerceIn(0f, 1f)).height(h)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(((mk.end - mk.start) / mk.end.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)).align(Alignment.CenterEnd).background(C.marker))
            }
        }
        val p = (pb.position / d).toFloat().coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth(p).height(h).clip(RoundedCornerShape(3.dp)).background(C.accent))
        Box(Modifier.fillMaxWidth(p)) {
            Box(Modifier.align(Alignment.CenterEnd).offset(x = 8.dp).size(if (focused) 18.dp else 14.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TracksDialog(pb: Playback, onDismiss: () -> Unit) = BzDialog(onDismiss) {
    val first = remember { FocusRequester() }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1f).height(380.dp)) {
                item { Eyebrow("Audio", Modifier.padding(bottom = 8.dp)) }
                items(pb.audioStreams, key = { "a" + it.id }) { a ->
                    TrackItem(a.language ?: a.displayTitle, listOfNotNull(a.channelLayout ?: a.channels?.let { "$it ch" }, a.codec.uppercase(), a.title, if (a.isDefault) "default" else null).joinToString(" · "),
                        a.id == pb.audioId, if (a.id == pb.audioId) first else null) { pb.changeAudio(a); onDismiss() }
                }
                item {
                    Column(Modifier.padding(top = 14.dp)) {
                        Eyebrow("Volume boost", Modifier.padding(bottom = 8.dp))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(100, 150, 200, 300).forEach { v -> Chip("$v%", App.store.audioBoost == v, { App.store.audioBoost = v; pb.applyBoost() }) }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).height(380.dp).background(C.line))
            LazyColumn(Modifier.weight(1f).height(380.dp)) {
                item { Eyebrow("Subtitles", Modifier.padding(bottom = 8.dp)) }
                item { TrackItem("Off", null, pb.subId == null, if (pb.subId == null && pb.audioStreams.isEmpty()) first else null) { pb.changeSub(null); onDismiss() } }
                items(pb.subStreams, key = { "s" + it.id }) { s ->
                    TrackItem(s.language ?: s.displayTitle, listOfNotNull(if (s.forced) "forced" else null, s.title, s.codec.uppercase(), if (s.key != null) "external" else null).joinToString(" · "),
                        s.id == pb.subId, null) { pb.changeSub(s); onDismiss() }
                }
            }
        }
        T(if (pb.isEpisode) "Remembered for ${pb.meta?.grandparentTitle}" else "Applies to this movie", 12.sp, color = C.muted, modifier = Modifier.padding(top = 12.dp))
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun TrackItem(label: String, detail: String?, active: Boolean, fr: FocusRequester?, onClick: () -> Unit) {
    Focusable(onClick, Modifier.fillMaxWidth().padding(vertical = 2.dp), focusRequester = fr) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) C.bg3 else if (active) C.accentSoft else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Box(Modifier.width(22.dp)) { if (active) Icon(Icons.Rounded.Check, null, tint = C.accent, modifier = Modifier.size(16.dp)) }
            Column {
                T(label, 14.sp, FontWeight.SemiBold, maxLines = 1)
                if (!detail.isNullOrEmpty()) T(detail, 11.sp, color = C.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EpisodesDialog(pb: Playback, onDismiss: () -> Unit, onPick: (Item) -> Unit) = BzDialog(onDismiss) {
    val m = pb.meta
    val season = pb.leaves.filter { it.parentRatingKey == m?.parentRatingKey }
    val fr = remember { FocusRequester() }
    Column {
        Eyebrow(m?.parentTitle ?: "Episodes", Modifier.padding(bottom = 10.dp))
        LazyColumn(Modifier.height(440.dp)) {
            items(season, key = { it.ratingKey }) { ep ->
                val cur = ep.ratingKey == m?.ratingKey
                Focusable({ if (cur) onDismiss() else onPick(ep) }, Modifier.fillMaxWidth().padding(vertical = 2.dp), focusRequester = if (cur) fr else null) { f ->
                    Row(Modifier.fillMaxWidth().background(if (f) C.bg3 else if (cur) C.accentSoft else Color.Transparent).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Art(ep.thumb, 192, 108, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
                        Column {
                            T("${ep.code} · ${ep.title}", 14.sp, FontWeight.SemiBold, maxLines = 1)
                            T(if (ep.duration > 0) fmtDuration(ep.duration) else "", 11.sp, color = C.muted)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}
