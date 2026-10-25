package com.dkrb56.bufferzone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage

// Black + violet (OLED) tokens from the design handoff.
object C {
    val bg0 = Color(0xFF000000)
    val bg = Color(0xFF050505)
    val bg2 = Color(0xFF0F0F10)
    val bg3 = Color(0xFF19191B)
    val bgHover = Color(0xFF222225)
    val line = Color(0xFF262629)
    val lineHover = Color(0xFF36363A)
    val text = Color(0xFFF4F4F5)
    val soft = Color(0xFFC4C4CC)
    val body = Color(0xFFD4D4D8)
    val dim = Color(0xFFB4B4BC)
    val muted = Color(0xFF8A8A93)
    val accent = Color(0xFFA594FF)
    val accentHover = Color(0xFFBCB0FF)
    val accentInk = Color(0xFF120C2A)
    val accentSoft = Color(0x24A594FF)
    val ok = Color(0xFF4CC38A)
    val okInk = Color(0xFF05210F)
    val info = Color(0xFF78B4FF)
    val marker = Color(0x8C78B4FF)
}

@Composable
fun BzTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = C.accent, onPrimary = C.accentInk, background = C.bg, surface = C.bg2, onSurface = C.text, onBackground = C.text),
    content = content
)

@Composable
fun T(text: String, size: TextUnit = 14.sp, weight: FontWeight = FontWeight.Normal, color: Color = C.text, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, fontSize = size, fontWeight = weight, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = size * 1.35f)

/** Clickable box for the remote. Focus shows a violet frame (and an optional zoom). */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    scale: Float = 1f,
    frame: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus?.invoke() }
    Box(
        modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .graphicsLayer { val s = if (focused) scale else 1f; scaleX = s; scaleY = s }
            .border(if (focused && frame) BorderStroke(2.dp, C.accent.copy(alpha = 0.65f)) else BorderStroke(0.dp, Color.Transparent), shape)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) { content(focused) }
}

enum class Kind { Primary, Secondary, Ghost }

@Composable
fun TvButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, kind: Kind = Kind.Secondary, large: Boolean = false, focusRequester: FocusRequester? = null) {
    Focusable(onClick, modifier, frame = kind != Kind.Primary, focusRequester = focusRequester) { f ->
        val bg = when (kind) {
            Kind.Primary -> if (f) C.accentHover else C.accent
            Kind.Secondary -> if (f) C.bgHover else C.bg3
            Kind.Ghost -> if (f) C.bg3 else Color.Transparent
        }
        val fg = if (kind == Kind.Primary) C.accentInk else if (kind == Kind.Ghost && !f) C.soft else C.text
        Row(
            Modifier.background(bg).padding(horizontal = if (large) 22.dp else 16.dp, vertical = if (large) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(if (large) 20.dp else 18.dp))
            T(label, if (large) 16.sp else 14.sp, FontWeight.SemiBold, fg, 1)
        }
    }
}

@Composable
fun IconBtn(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, size: Dp = 44.dp, label: String? = null) {
    Focusable(onClick, modifier, frame = true) { f ->
        Box(Modifier.size(size).background(if (f) C.bgHover else C.bg3), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (on) C.accent else if (f) C.text else C.soft, modifier = Modifier.size(size * 0.45f))
        }
    }
}

/** Tab chip; active chips invert to white. */
@Composable
fun Chip(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int? = null, check: Boolean = false, focusRequester: FocusRequester? = null) {
    Focusable(onClick, modifier, RoundedCornerShape(20.dp), focusRequester = focusRequester) { f ->
        Row(
            Modifier.background(if (active) C.text else if (f) C.bg3 else Color.Transparent)
                .border(1.dp, if (active) C.text else C.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (check && active) Icon(Icons.Rounded.Check, null, tint = C.bg, modifier = Modifier.size(14.dp))
            T(label, 13.sp, FontWeight.Medium, if (active) C.bg else if (f) C.text else C.muted, 1)
            if (count != null) T(count.toString(), 11.sp, color = if (active) C.bg.copy(alpha = .6f) else C.muted)
        }
    }
}

@Composable
fun Pill(text: String, tone: String = "") {
    val (bg, fg) = when (tone) {
        "ok" -> Color(0x244CC38A) to C.ok
        "accent" -> C.accentSoft to C.accent
        "info" -> Color(0x2478B4FF) to C.info
        else -> Color(0x1AFFFFFF) to Color(0xFFAAAABB)
    }
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(6.dp))
        T(text, 12.sp, FontWeight.SemiBold, fg, 1)
    }
}

/** Plex artwork through the server's photo transcoder; a flat placeholder with the title when there is none. */
@Composable
fun Art(path: String?, w: Int, h: Int, modifier: Modifier = Modifier, title: String = "") {
    Box(modifier.background(C.bg3)) {
        val url = App.plex.image(path, w, h)
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else T(title, 13.sp, FontWeight.SemiBold, C.muted, 3, Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

@Composable
fun ProgressLine(p: Float, modifier: Modifier = Modifier, color: Color = C.accent) {
    if (p <= 0f) return
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x99000000))) {
        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(color))
    }
}

/** 2:3 poster card with title and subtitle. */
@Composable
fun PosterCard(item: Item, modifier: Modifier = Modifier, dim: Boolean = false, subOverride: String? = null, onClick: () -> Unit = { App.open(item) }) {
    val thumb = when (item.type) { "episode" -> item.grandparentThumb ?: item.parentThumb ?: item.thumb; "season" -> item.thumb ?: item.parentThumb; else -> item.thumb }
    val title = when (item.type) { "episode" -> item.grandparentTitle ?: item.title; "season" -> item.parentTitle ?: item.title; else -> item.title }
    val sub = subOverride ?: when (item.type) { "episode" -> "${item.code} · ${item.title}"; "season" -> item.title; "collection" -> "${item.childCount} titles"; else -> item.year?.toString() ?: "" }
    Column(modifier.graphicsLayer { alpha = if (dim) .55f else 1f }) {
        Focusable(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), scale = 1.06f) { _ ->
            Art(thumb, 300, 450, Modifier.fillMaxSize(), title)
            if (item.isWatched && item.type != "collection") Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape).background(C.ok), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = C.okInk, modifier = Modifier.size(14.dp))
            }
            val unwatched = item.leafCount - item.viewedLeafCount
            if (item.type == "show" && item.viewedLeafCount > 0 && unwatched > 0) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).clip(RoundedCornerShape(20.dp)).background(C.accent).padding(horizontal = 7.dp, vertical = 2.dp)) {
                T(unwatched.toString(), 12.sp, FontWeight.Bold, C.accentInk)
            }
            ProgressLine(item.progress, Modifier.align(Alignment.BottomStart))
        }
        T(title, 13.sp, FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 7.dp))
        T(sub, 12.sp, color = C.muted, maxLines = 1)
    }
}

// ---------- dialogs ----------
@Composable
fun BzDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) = Dialog(onDismissRequest = onDismiss) {
    Box(Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(16.dp)).background(C.bg2).border(1.dp, C.lineHover, RoundedCornerShape(16.dp)).padding(26.dp)) { content() }
}

/** A list of choices; the remote picks one. */
@Composable
fun <V> OptionDialog(title: String, options: List<Pair<V, String>>, current: V?, onDismiss: () -> Unit, onPick: (V) -> Unit) = BzDialog(onDismiss) {
    val first = remember { FocusRequester() }
    Column {
        T(title, 20.sp, FontWeight.SemiBold, modifier = Modifier.padding(bottom = 14.dp))
        LazyColumn(Modifier.height((options.size * 52).coerceAtMost(420).dp)) {
            items(options.size) { idx ->
                val (v, label) = options[idx]
                val selected = v == current
                Focusable({ onPick(v); onDismiss() }, Modifier.fillMaxWidth().padding(vertical = 2.dp), focusRequester = if (selected || (current == null && idx == 0)) first else null) { f ->
                    Row(Modifier.fillMaxWidth().background(if (f) C.bg3 else if (selected) C.accentSoft else Color.Transparent).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(26.dp)) { if (selected) Icon(Icons.Rounded.Check, null, tint = C.accent, modifier = Modifier.size(18.dp)) }
                        T(label, 15.sp, FontWeight.Medium)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
fun TextDialog(title: String, initial: String = "", hint: String? = null, okLabel: String = "Save", password: Boolean = false, numeric: Boolean = false, error: String? = null, onDismiss: () -> Unit, onDone: (String) -> Unit) = BzDialog(onDismiss) {
    var value by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T(title, 20.sp, FontWeight.SemiBold)
        if (hint != null) T(hint, 14.sp, color = C.muted)
        OutlinedTextField(
            value, { value = if (numeric) it.filter(Char::isDigit).take(4) else it.take(60) },
            Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(value.trim()) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line, focusedTextColor = C.text, unfocusedTextColor = C.text, cursorColor = C.accent)
        )
        if (error != null) T(error, 13.sp, color = Color(0xFFFFBBBB))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            TvButton("Cancel", onDismiss, kind = Kind.Ghost)
            TvButton(okLabel, { onDone(value.trim()) }, kind = Kind.Primary)
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
fun ConfirmDialog(title: String, text: String, okLabel: String, onDismiss: () -> Unit, onOk: () -> Unit) = BzDialog(onDismiss) {
    val fr = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        T(title, 20.sp, FontWeight.SemiBold)
        T(text, 14.sp, color = C.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            TvButton("Cancel", onDismiss, kind = Kind.Ghost, focusRequester = fr)
            TvButton(okLabel, { onOk(); onDismiss() }, kind = Kind.Primary)
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

// ---------- settings rows ----------
@Composable
fun SettingRow(label: String, hint: String?, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Focusable(onClick, modifier.fillMaxWidth(), RoundedCornerShape(10.dp)) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) C.bg3 else C.bg2).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(label, 15.sp, FontWeight.SemiBold)
                if (hint != null) T(hint, 12.sp, color = C.muted)
            }
            if (value != null) T(value, 14.sp, color = C.soft, maxLines = 1)
            trailing?.invoke()
        }
    }
}

@Composable
fun SwitchMark(on: Boolean) {
    Box(Modifier.width(36.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.accent else C.lineHover), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(2.dp).size(16.dp).clip(CircleShape).background(if (on) C.accentInk else C.text))
    }
}

@Composable
fun ToggleRow(label: String, hint: String?, on: Boolean, onToggle: (Boolean) -> Unit) =
    SettingRow(label, hint, null, { onToggle(!on) }) { SwitchMark(on) }

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) = T(text.uppercase(), 11.sp, FontWeight.SemiBold, C.muted, 1, modifier)

@Composable
fun Lockup(size: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((size * 0.43f).dp)) {
        Icon(androidx.compose.ui.res.painterResource(R.drawable.logo_mark), null, tint = Color.Unspecified, modifier = Modifier.size((size * 1.6f).dp))
        T("Buffer Zone", size.sp, FontWeight.ExtraBold, C.accent, 1)
    }
}

@Composable
fun Toast() {
    val msg = App.toast ?: return
    Box(Modifier.fillMaxSize().padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(C.bg3).border(1.dp, C.line, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
            T(msg, 15.sp)
        }
    }
}

fun fmtTime(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}

fun fmtDuration(ms: Long): String {
    val min = Math.round(ms / 60000.0)
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

/** "Watched today 21:26", "Watched yesterday 17:49", else "Watched 2026-10-21" (local time). */
fun watchedText(ts: Long): String {
    if (ts <= 0) return ""
    val d = java.util.Calendar.getInstance().apply { timeInMillis = ts * 1000 }
    val now = java.util.Calendar.getInstance()
    fun day(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 400 + c.get(java.util.Calendar.DAY_OF_YEAR)
    val diff = day(now) - day(d)
    val time = "%02d:%02d".format(d.get(java.util.Calendar.HOUR_OF_DAY), d.get(java.util.Calendar.MINUTE))
    return when (diff) {
        0 -> "Watched today $time"
        1 -> "Watched yesterday $time"
        else -> "Watched %d-%02d-%02d".format(d.get(java.util.Calendar.YEAR), d.get(java.util.Calendar.MONTH) + 1, d.get(java.util.Calendar.DAY_OF_MONTH))
    }
}
