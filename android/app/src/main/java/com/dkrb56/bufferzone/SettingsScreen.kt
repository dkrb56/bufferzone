package com.dkrb56.bufferzone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

private val PAGES = listOf("playback" to "Playback", "languages" to "Languages", "style" to "Subtitle style", "libraries" to "Libraries",
    "profiles" to "Profiles", "parental" to "Parental controls", "account" to "Account")

val LANGUAGES = listOf("eng" to "English", "slv" to "Slovenian", "hrv" to "Croatian", "srp" to "Serbian", "bos" to "Bosnian",
    "ger" to "German", "fre" to "French", "spa" to "Spanish", "ita" to "Italian", "por" to "Portuguese", "dut" to "Dutch",
    "pol" to "Polish", "cze" to "Czech", "slo" to "Slovak", "hun" to "Hungarian", "rus" to "Russian", "ukr" to "Ukrainian",
    "swe" to "Swedish", "nor" to "Norwegian", "dan" to "Danish", "fin" to "Finnish", "gre" to "Greek", "tur" to "Turkish",
    "jpn" to "Japanese", "kor" to "Korean", "chi" to "Chinese", "ara" to "Arabic", "hin" to "Hindi")

private val AGES = listOf(7 to "7 · TV-Y7", 12 to "12 · TV-PG, PG", 13 to "13 · PG-13", 14 to "14 · TV-14", 16 to "16", 17 to "17 · R, TV-MA")

private class Choice<V>(val title: String, val options: List<Pair<V, String>>, val current: V, val onPick: (V) -> Unit)

@Composable
fun SettingsScreen(s: Screen.Settings) {
    App.store.version
    val st = App.store
    var choice by remember { mutableStateOf<Choice<Any>?>(null) }
    @Suppress("UNCHECKED_CAST")
    fun <V> ask(title: String, options: List<Pair<V, String>>, current: V, onPick: (V) -> Unit) {
        choice = Choice(title, options as List<Pair<Any, String>>, current as Any, { onPick(it as V) })
    }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PageHead("Settings")
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PAGES.forEach { (k, l) ->
                    val on = k == s.page
                    Focusable({ App.go(Screen.Settings(k), replace = true) }, Modifier.fillMaxWidth()) { f ->
                        Box(Modifier.fillMaxWidth().background(if (on) C.accentSoft else if (f) C.bg3 else Color.Transparent).padding(horizontal = 14.dp, vertical = 11.dp)) {
                            T(l, 14.sp, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) C.accent else C.soft)
                        }
                    }
                }
            }
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                item { T(PAGES.first { it.first == s.page }.second, 24.sp, FontWeight.SemiBold) }
                when (s.page) {
                    "playback" -> {
                        item { T("Direct Play sends the original file. If the file cannot play, the app switches to a converted stream.", 14.sp, color = C.muted) }
                        item { SettingRow("Default quality", "You can also change it in the player.", QUALITY.firstOrNull { it.id == st.quality }?.label, { ask("Default quality", QUALITY.map { it.id to it.label }, st.quality) { st.quality = it } }) }
                        item { SettingRow("Volume boost", "Makes quiet titles louder. Also in the player, under Audio.", if (st.audioBoost > 100) "${st.audioBoost}%" else "Off (100%)",
                            { ask("Volume boost", listOf(100 to "Off (100%)", 150 to "150%", 200 to "200%", 300 to "300%"), st.audioBoost) { st.audioBoost = it } }) }
                        item { ToggleRow("Play the next episode automatically", null, st.autoPlayNext) { st.autoPlayNext = it } }
                        item { SettingRow("Countdown before next episode", null, "${st.nextCountdown} seconds", { ask("Countdown", listOf(5, 10, 15, 30).map { it to "$it seconds" }, st.nextCountdown) { st.nextCountdown = it } }) }
                        item { SettingRow("Skip back", "Left on the remote and the back button in the player.", "${st.skipBack} seconds", { ask("Skip back", listOf(5, 10, 15, 30, 60).map { it to "$it seconds" }, st.skipBack) { st.skipBack = it } }) }
                        item { SettingRow("Skip forward", "Right on the remote and the forward button in the player.", "${st.skipForward} seconds", { ask("Skip forward", listOf(10, 15, 30, 60, 120).map { it to "$it seconds" }, st.skipForward) { st.skipForward = it } }) }
                        item { ToggleRow("Skip intros automatically", null, st.autoSkipIntro) { st.autoSkipIntro = it } }
                        item { ToggleRow("Skip credits automatically", "Goes to the next episode.", st.autoSkipCredits) { st.autoSkipCredits = it } }
                    }
                    "languages" -> {
                        item { T("For each series, the app remembers the audio and subtitles you select in the player.", 14.sp, color = C.muted) }
                        item { SettingRow("Preferred audio language", null, LANGUAGES.firstOrNull { it.first == st.audioLang }?.second, { ask("Audio language", LANGUAGES, st.audioLang) { st.audioLang = it } }) }
                        item { SettingRow("Subtitle language", null, LANGUAGES.firstOrNull { it.first == st.subtitleLang }?.second, { ask("Subtitle language", LANGUAGES, st.subtitleLang) { st.subtitleLang = it } }) }
                        val modes = listOf("auto" to "When audio is in another language", "always" to "Always", "off" to "Never")
                        item { SettingRow("Show subtitles", null, modes.first { it.first == st.subtitleMode }.second, { ask("Show subtitles", modes, st.subtitleMode) { st.subtitleMode = it } }) }
                        if (st.seriesPrefCount > 0) item { SettingRow("${st.seriesPrefCount} series with remembered tracks", null, "Forget all", { st.clearSeriesPrefs(); App.show("Cleared") }) }
                    }
                    "style" -> {
                        item { T("Applies to text subtitles. Image subtitles keep their own look.", 14.sp, color = C.muted) }
                        item { SubtitlePreview() }
                        item { SettingRow("Size", null, "${st.subtitleSize}%", { ask("Size", (6..20).map { it * 10 }.map { it to "$it%" }, st.subtitleSize) { st.subtitleSize = it } }) }
                        val colors = listOf("#ffffff" to "White", "#fff35c" to "Yellow", "#9fe8ff" to "Light blue", "#c8ffc8" to "Light green", "#bebebe" to "Light gray (HDR, OLED)")
                        item { SettingRow("Color", null, colors.firstOrNull { it.first == st.subtitleColor }?.second, { ask("Color", colors, st.subtitleColor) { st.subtitleColor = it } }) }
                        item { SettingRow("Opacity", null, "${st.subtitleOpacity}%", { ask("Opacity", listOf(100, 90, 80, 70, 60, 50).map { it to "$it%" }, st.subtitleOpacity) { st.subtitleOpacity = it } }) }
                        val bgs = listOf("shadow" to "Outline and shadow", "box" to "Dark box", "none" to "None")
                        item { SettingRow("Background", null, bgs.firstOrNull { it.first == st.subtitleBackground }?.second, { ask("Background", bgs, st.subtitleBackground) { st.subtitleBackground = it } }) }
                        item { SettingRow("Distance from bottom", null, "${st.subtitlePosition}%", { ask("Distance from bottom", listOf(2, 5, 8, 12, 16, 20, 25, 30).map { it to "$it%" }, st.subtitlePosition) { st.subtitlePosition = it } }) }
                    }
                    "libraries" -> {
                        item { T("Hidden libraries do not show on the home screen, in the sidebar or in search.", 14.sp, color = C.muted) }
                        items(App.sections, key = { it.key }) { sec ->
                            ToggleRow(sec.title, if (sec.type == "show") "Series" else "Movies", sec.key !in st.hiddenLibraries) { on ->
                                st.hiddenLibraries = if (on) st.hiddenLibraries - sec.key else st.hiddenLibraries + sec.key
                            }
                        }
                    }
                    "profiles" -> {
                        item { T("Profiles are the Plex Home users of your account. Each profile has its own watch history and watchlist.", 14.sp, color = C.muted) }
                        if (App.homeUsers.size > 1) items(App.homeUsers, key = { it.id }) { u ->
                            SettingRow(u.title, if (u.admin) "Admin" else if (u.restricted) "Managed" else if (u.guest) "Guest" else "Member",
                                if (App.profile?.id == u.id) "Current" else "Switch", { if (App.profile?.id != u.id) App.go(Screen.Profiles, root = true) })
                        } else item { SettingRow("No Plex Home users", "Add users to your Plex Home at plex.tv. Then they show here and on start-up.", null, {}) }
                    }
                    "parental" -> {
                        val admin = App.homeUsers.firstOrNull { it.admin }
                        val managed = App.homeUsers.filter { !it.admin }
                        item { T("Managed profiles see only what is allowed here. ${admin?.title ?: "The admin profile"} is the admin profile and has no limits.", 14.sp, color = C.muted) }
                        if (managed.isEmpty()) item { SettingRow("No managed profiles", "Your Plex Home has no other users. Add them at plex.tv first.", null, {}) }
                        else if (App.profile?.admin == false) item { SettingRow("Admin only", "Switch to the admin profile to change parental controls.", null, {}) }
                        else {
                            val sel = managed.firstOrNull { it.id == s.who } ?: managed.first()
                            val r = Rules.from(st.rules(sel.id))
                            fun save(nr: Rules) = st.setRules(sel.id, nr.toJson())
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(managed, key = { it.id }) { u -> Chip(u.title, u.id == sel.id, { App.go(Screen.Settings("parental", u.id), replace = true) }) }
                                }
                            }
                            item { ToggleRow("Restrict content", "Hide titles above the rating below.", r.restrict) { save(Rules(it, r.maxAge, r.unrated, r.libraries, r.pinToLeave, r.multiview)) } }
                            item { SettingRow("Highest allowed rating", null, AGES.firstOrNull { it.first == r.maxAge }?.second ?: "${r.maxAge}", {
                                ask("Highest allowed rating", AGES, r.maxAge) { save(Rules(r.restrict, it, r.unrated, r.libraries, r.pinToLeave, r.multiview)) } }) }
                            item { SettingRow("Unrated titles", "Home videos and titles without a rating.", if (r.unrated == "hide") "Hide" else "Show", {
                                ask("Unrated titles", listOf("hide" to "Hide", "show" to "Show"), r.unrated) { save(Rules(r.restrict, r.maxAge, it, r.libraries, r.pinToLeave, r.multiview)) } }) }
                            item { Eyebrow("Libraries", Modifier.padding(top = 10.dp)) }
                            items(App.sections, key = { "p" + it.key }) { sec ->
                                val libs = r.libraries ?: App.sections.map { it.key }
                                ToggleRow(sec.title, null, sec.key in libs) { on ->
                                    save(Rules(r.restrict, r.maxAge, r.unrated, if (on) (libs + sec.key).distinct() else libs - sec.key, r.pinToLeave, r.multiview))
                                }
                            }
                            item { ToggleRow("Require admin PIN to leave this profile", "Applies to profile switch and sign out.", r.pinToLeave) { save(Rules(r.restrict, r.maxAge, r.unrated, r.libraries, it, r.multiview)) } }
                            item { ToggleRow("Allow Multi-view", null, r.multiview) { save(Rules(r.restrict, r.maxAge, r.unrated, r.libraries, r.pinToLeave, it)) } }
                        }
                    }
                    "account" -> {
                        val srv = App.plex.server
                        item { SettingRow("Signed in as", App.user?.optString("username"), "Sign out", { if (App.rules?.pinToLeave == true) App.show("Switch to the admin profile to sign out.") else confirmSignOut = true }) }
                        item { SettingRow("Profile", App.profile?.title, if (App.homeUsers.size > 1) "Switch profile" else null, { if (App.homeUsers.size > 1) App.go(Screen.Profiles, root = true) }) }
                        item { SettingRow("Server", srv?.let { "${it.name} · ${if (it.relay) "Plex relay" else if (it.local) "local network" else "direct internet"}" }, "Switch server", { App.go(Screen.Onboard(2), root = true) }) }
                        item { SettingRow("Version", "Buffer Zone for Android TV", "0.1.0", {}) }
                    }
                }
            }
        }
    }

    choice?.let { c -> OptionDialog(c.title, c.options, c.current, { choice = null }) { c.onPick(it) } }
    if (confirmSignOut) ConfirmDialog("Sign out?", "You need the code at plex.tv/link to sign in again.", "Sign out", { confirmSignOut = false }) {
        st.token = null; st.serverId = null; st.profileId = 0
        App.plex.token = null; App.plex.accountToken = null; App.profile = null
        App.go(Screen.Onboard(1), root = true)
    }
}

@Composable
private fun SubtitlePreview() {
    val st = App.store
    val color = runCatching { Color(android.graphics.Color.parseColor(st.subtitleColor)) }.getOrDefault(Color.White)
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 6f).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFF2B2547), Color(0xFF4A3A52), Color(0xFF1D2A30)))),
        contentAlignment = Alignment.BottomCenter) {
        val style = TextStyle(
            fontSize = (22 * st.subtitleSize / 100f).sp, fontWeight = FontWeight.SemiBold, color = color,
            shadow = if (st.subtitleBackground == "shadow") Shadow(Color.Black, blurRadius = 6f) else null,
            background = if (st.subtitleBackground == "box") Color(0xB8000000) else Color.Transparent
        )
        androidx.compose.material3.Text("This is how your subtitles look.\nČ, š, ž, đ and ć work too.", style = style,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(bottom = (st.subtitlePosition * 1.6f).dp))
    }
}
