package com.dkrb56.bufferzone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val STEPS = listOf(
    "Sign in with Plex" to "Use your Plex account",
    "Choose a server" to "Pick whose library to watch",
    "Pick libraries" to "Hide the ones you do not use",
    "Choose profile" to "Plex Home users on that server"
)

@Composable
fun OnboardingScreen(step: Int) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(420.dp).fillMaxHeight().background(C.bg0).padding(40.dp)) {
            Lockup(28)
            T("A clean player for your friend's Plex server.", 15.sp, color = C.soft, modifier = Modifier.padding(top = 10.dp, bottom = 40.dp))
            STEPS.forEachIndexed { i, (t, d) ->
                val n = i + 1
                Row(Modifier.padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .background(if (n < step) C.ok else if (n == step) C.accentSoft else Color.Transparent)
                            .border(1.5.dp, if (n < step) C.ok else if (n == step) C.accent else C.lineHover, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (n < step) Icon(Icons.Rounded.Check, null, tint = C.okInk, modifier = Modifier.size(16.dp))
                        else T(n.toString(), 12.sp, FontWeight.Bold, if (n == step) C.accent else C.muted)
                    }
                    Column {
                        T(t, 15.sp, FontWeight.SemiBold, if (n <= step) C.text else C.muted)
                        val detail = if (n == 1 && App.user != null) "Signed in as ${App.user?.optString("username")}" else d
                        T(detail, 12.sp, color = C.muted)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            T("You sign in on the Plex website. This app never sees your password.", 12.sp, color = C.muted)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 80.dp, vertical = 64.dp).widthIn(max = 760.dp)) {
            T("Step $step of 4", 13.sp, FontWeight.SemiBold, C.accent)
            Spacer(Modifier.height(6.dp))
            when (step) {
                1 -> SignInStep()
                2 -> ServersStep()
                3 -> LibrariesStep()
                else -> {
                    T("Choose profile", 30.sp, FontWeight.SemiBold)
                    T("Plex Home users on your account. Each profile has its own watch history.", 15.sp, color = C.soft, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                    ProfileTiles()
                }
            }
        }
    }
}

@Composable
private fun SignInStep() {
    var code by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Getting a code…") }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        try {
            val pin = App.plex.createPin()
            code = pin.optString("code")
            status = "Waiting for you to enter the code…"
            val id = pin.optLong("id")
            val until = System.currentTimeMillis() + 14 * 60_000
            while (System.currentTimeMillis() < until) {
                delay(2000)
                val token = App.plex.checkPin(id) ?: continue
                App.store.token = token
                App.plex.token = token; App.plex.accountToken = token
                App.user = App.plex.user()
                App.go(Screen.Onboard(2), root = true)
                return@LaunchedEffect
            }
            status = "The code expired. Get a new code."
        } catch (e: Exception) {
            status = "Sign-in failed: ${e.message}"
        }
    }
    val fr = remember { FocusRequester() }
    T("Sign in with Plex", 30.sp, FontWeight.SemiBold)
    T("On your phone or computer, open the address below and type the code.", 15.sp, color = C.soft, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
    T("plex.tv/link", 34.sp, FontWeight.Bold, C.accent)
    Spacer(Modifier.height(18.dp))
    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(C.bg2).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(horizontal = 36.dp, vertical = 20.dp)) {
        T(code?.uppercase()?.toCharArray()?.joinToString(" ") ?: "· · · ·", 54.sp, FontWeight.Bold)
    }
    T(status, 14.sp, color = C.muted, modifier = Modifier.padding(top = 18.dp, bottom = 22.dp))
    TvButton("Get a new code", { code = null; status = "Getting a code…"; attempt++ }, focusRequester = fr)
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun ColumnScope.ServersStep() {
    var servers by remember { mutableStateOf<List<Resource>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(App.store.serverId) }
    val status = remember { mutableStateMapOf<String, String>() } // id -> "online"/"relay"/"offline"/"local"
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            val list = App.plex.resources().sortedWith(compareBy<Resource> { it.owned }.thenBy { it.name })
            servers = list
            if (selected == null || list.none { it.clientIdentifier == selected }) selected = list.firstOrNull()?.clientIdentifier
            list.forEach { r -> launch { val c = App.plex.probe(r); status[r.clientIdentifier] = when { c == null -> "offline"; c.relay -> "relay"; c.local -> "local"; else -> "online" } } }
        } catch (e: Exception) { error = e.message }
    }
    T("Choose a server", 30.sp, FontWeight.SemiBold)
    T("Servers shared with your Plex account. Friends' servers are listed first.", 15.sp, color = C.soft, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
    val list = servers
    when {
        error != null -> T("Cannot load servers: $error", 14.sp, color = C.muted)
        list == null -> T("Looking for servers…", 14.sp, color = C.muted)
        list.isEmpty() -> T("Your account has no access to a Plex server.", 14.sp, color = C.muted)
        else -> {
            val first = remember { FocusRequester() }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list.size) { idx ->
                    val r = list[idx]
                    val sel = r.clientIdentifier == selected
                    val st = status[r.clientIdentifier]
                    val base = if (r.owned) "Your server" else "Shared by ${r.sourceTitle ?: "a friend"}"
                    Focusable({ selected = r.clientIdentifier }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), focusRequester = if (idx == 0) first else null) { f ->
                        Row(
                            Modifier.fillMaxWidth().background(if (sel) C.accentSoft else if (f) C.bg3 else C.bg2)
                                .border(1.dp, if (sel) C.accent else C.line, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 16.dp)
                                .let { if (st == "offline") it.background(Color(0x66000000)) else it },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(Modifier.size(16.dp).clip(CircleShape).border(if (sel) 5.dp else 1.5.dp, if (sel) C.accent else C.lineHover, CircleShape))
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(C.bg3), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Dns, null, tint = C.soft, modifier = Modifier.size(20.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                T(r.name, 16.sp, FontWeight.SemiBold)
                                T(when (st) { "relay" -> "$base · via Plex relay (limited speed)"; "local" -> "$base · local network"; "online" -> "$base · direct connection"; else -> base }, 12.sp, color = C.muted)
                            }
                            when (st) { null -> Pill("Checking"); "offline" -> Pill("Offline"); "relay" -> Pill("Relay", "accent"); else -> Pill("Online", "ok") }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
    T("Server not listed? Ask the owner to share a library with your Plex account.", 12.sp, color = C.muted, modifier = Modifier.padding(top = 14.dp))
    Row(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TvButton("Back", { App.go(Screen.Onboard(1), root = true) }, kind = Kind.Ghost)
        TvButton(if (busy) "Connecting…" else "Continue", {
            val res = list?.firstOrNull { it.clientIdentifier == selected } ?: return@TvButton
            if (busy) return@TvButton
            busy = true
            scope.launch {
                try { App.connect(res); App.go(Screen.Onboard(3), root = true) } catch (e: Exception) { App.show(e.message ?: "Cannot connect") }
                busy = false
            }
        }, icon = Icons.AutoMirrored.Rounded.ArrowForward, kind = Kind.Primary, large = true)
    }
}

@Composable
private fun ColumnScope.LibrariesStep() {
    App.store.version
    T("Pick libraries", 30.sp, FontWeight.SemiBold)
    T("Libraries on ${App.plex.server?.name}. Hidden libraries do not show in the app. You can change this later in Settings.", 15.sp, color = C.soft, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
    val first = remember { FocusRequester() }
    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(App.sections) { sec ->
            val shown = sec.key !in App.store.hiddenLibraries
            Box(Modifier.let { if (sec == App.sections.first()) it else it }) {
                ToggleRow(sec.title, if (sec.type == "show") "Series" else "Movies", shown) { on ->
                    App.store.hiddenLibraries = if (on) App.store.hiddenLibraries - sec.key else App.store.hiddenLibraries + sec.key
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TvButton("Back", { App.go(Screen.Onboard(2), root = true) }, kind = Kind.Ghost)
        TvButton("Continue", { if (App.homeUsers.size > 1) App.go(Screen.Onboard(4), root = true) else App.enterApp() },
            icon = Icons.AutoMirrored.Rounded.ArrowForward, kind = Kind.Primary, large = true, focusRequester = first)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

// ---------- profiles ----------
private class PinAsk(val title: String, val text: String, val onPin: suspend (String) -> String?)

@Composable
fun ProfilesScreen() {
    Box(Modifier.fillMaxSize().padding(40.dp)) {
        Box(Modifier.align(Alignment.TopStart)) { Lockup(18) }
        App.plex.server?.let { Box(Modifier.align(Alignment.TopEnd)) { Pill(it.name, "ok") } }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            T("Who's watching?", 34.sp, FontWeight.SemiBold)
            T("Plex Home on ${App.plex.server?.name ?: "your server"}", 15.sp, color = C.muted, modifier = Modifier.padding(top = 6.dp, bottom = 36.dp))
            ProfileTiles()
        }
    }
}

@Composable
fun ProfileTiles() {
    var ask by remember { mutableStateOf<PinAsk?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    fun continueTo(u: HomeUser) {
        val isAccount = u.id == App.user?.optLong("id")
        if (isAccount) { scope.launch { App.switchProfile(u, App.plex.accountToken ?: return@launch) }; return }
        if (u.isProtected) {
            ask = PinAsk("PIN for ${u.title}", "This profile is protected with a PIN.") { pin ->
                val t = runCatching { App.plex.switchUser(u.uuid, pin) }.getOrNull() ?: return@PinAsk "Wrong PIN."
                App.switchProfile(u, t); null
            }
            return
        }
        scope.launch {
            val t = runCatching { App.plex.switchUser(u.uuid, null) }.getOrElse { App.show("Cannot switch profile: ${it.message}"); return@launch }
            if (t != null) App.switchProfile(u, t)
        }
    }

    fun pick(u: HomeUser) {
        val cur = App.profile
        val leaving = if (cur != null && cur.id != u.id && !cur.admin) App.rules else null
        val admin = App.homeUsers.firstOrNull { it.admin }
        if (leaving?.pinToLeave == true && admin != null) {
            ask = PinAsk("Admin PIN", "Type the PIN of ${admin.title} to leave this profile.") { pin ->
                runCatching { App.plex.switchUser(admin.uuid, pin) }.getOrNull() ?: return@PinAsk "Wrong PIN."
                ask = null; continueTo(u); null
            }
        } else continueTo(u)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        App.homeUsers.forEachIndexed { idx, u ->
            val r = App.store.rules(u.id)?.let { Rules.from(it) }
            val (icon, meta) = when {
                u.admin -> (if (u.isProtected) Icons.Outlined.Lock else Icons.Outlined.Person) to (if (u.isProtected) "Admin · PIN" else "Admin")
                u.guest -> null to "No history"
                u.restricted -> Icons.Outlined.Shield to ((if (u.restrictionProfile == "little_kid") "Kids" else "Managed") + if (r?.restrict != false) " · up to ${r?.maxAge ?: 12}" else "")
                else -> (if (u.isProtected) Icons.Outlined.Lock else null) to (if (u.isProtected) "PIN" else "Member")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Focusable({ pick(u) }, shape = RoundedCornerShape(16.dp), scale = 1.06f, focusRequester = if (idx == 0) first else null) { f ->
                    Box(Modifier.size(150.dp).background(if (f || App.profile?.id == u.id) C.accentSoft else C.bg3), contentAlignment = Alignment.Center) {
                        T(u.title.take(1).uppercase(), 56.sp, FontWeight.Bold, if (f) C.accent else C.soft)
                    }
                }
                T(u.title, 16.sp, FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (icon != null) Icon(icon, null, tint = C.muted, modifier = Modifier.size(13.dp))
                    T(meta, 12.sp, color = C.muted)
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Focusable({ App.show("Add profiles at plex.tv on a phone or computer.") }, shape = RoundedCornerShape(16.dp)) { f ->
                Box(Modifier.size(150.dp).border(2.dp, if (f) C.accent else C.lineHover, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = C.muted, modifier = Modifier.size(32.dp))
                }
            }
            T("Add profile", 16.sp, FontWeight.SemiBold)
            T("On plex.tv", 12.sp, color = C.muted)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    ask?.let { a ->
        TextDialog(a.title, hint = a.text, okLabel = "Continue", password = true, numeric = true, error = error,
            onDismiss = { ask = null; error = null }) { pin ->
            if (pin.length != 4) { error = "Type the 4-digit PIN."; return@TextDialog }
            // onPin can open the next PIN request (admin PIN, then profile PIN); only close this one.
            scope.launch { val problem = a.onPin(pin); if (problem != null) error = problem else { if (ask === a) ask = null; error = null } }
        }
    }
}
