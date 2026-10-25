package com.dkrb56.bufferzone

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { BzTheme { Root() } }
        if (savedInstanceState == null) App.start()
    }
}

@Composable
fun Root() {
    Box(Modifier.fillMaxSize().background(C.bg)) {
        val s = App.screen
        val bare = s is Screen.Loading || s is Screen.Message || s is Screen.Onboard || s is Screen.Profiles
        if (bare) ScreenContent(s)
        else Row(Modifier.fillMaxSize()) {
            Rail()
            Box(Modifier.weight(1f).fillMaxHeight()) { ScreenContent(s) }
        }
        App.playback?.let { req -> PlayerScreen(req) }
        Toast()
    }
    BackHandler(enabled = App.playback == null && App.nav.size > 1) { App.back() }
}

@Composable
fun ScreenContent(s: Screen) {
    when (s) {
        Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.accent) }
        is Screen.Message -> MessageScreen(s)
        is Screen.Onboard -> OnboardingScreen(s.step)
        Screen.Profiles -> ProfilesScreen()
        Screen.Home -> HomeScreen()
        is Screen.Library -> LibraryScreen(s.id)
        Screen.Search -> SearchScreen()
        is Screen.Movie -> MovieScreen(s.key)
        is Screen.Show -> ShowScreen(s)
        is Screen.Collection -> CollectionScreen(s.key)
        Screen.Watchlist -> WatchlistScreen()
        is Screen.Settings -> SettingsScreen(s)
        Screen.MultiView -> MultiViewScreen()
    }
}

@Composable
fun MessageScreen(s: Screen.Message) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(480.dp).clip(RoundedCornerShape(16.dp)).background(C.bg2).padding(34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(s.title, 20.sp, FontWeight.SemiBold)
            T(s.text, 14.sp, color = C.muted)
            s.retry?.let { r -> TvButton("Try again", r, kind = Kind.Primary) }
        }
    }
}

/** Icon rail: Home, Search, Movies, Series, Kids, Watchlist, Multi-view, pins, Settings and the profile avatar. */
@Composable
fun Rail() {
    App.store.version // redraw when pins or hidden libraries change
    val g = App.groups()
    val s = App.screen
    val activeKey = when (s) {
        Screen.Home -> "home"
        Screen.Search -> "search"
        is Screen.Library -> App.sections.firstOrNull { it.key == s.id }?.let { if (it in g.kids) "kids" else it.type } ?: ""
        Screen.Watchlist -> "watchlist"
        is Screen.Settings -> "settings"
        Screen.MultiView -> "multiview"
        is Screen.Movie -> if (App.isPinned(s.key)) "pin:${s.key}" else ""
        is Screen.Show -> if (App.isPinned(s.key)) "pin:${s.key}" else ""
        is Screen.Collection -> if (App.isPinned(s.key)) "pin:${s.key}" else ""
        else -> ""
    }
    // Last used library of the group; else "Movies" or "TV Shows" when present; else the first.
    fun lib(group: String, list: List<Section>) = list.firstOrNull { it.key == App.store.lastLibrary(group) }?.key
        ?: list.firstOrNull { it.title.equals("Movies", true) || it.title.equals("TV Shows", true) }?.key ?: list.firstOrNull()?.key
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(C.bg0).padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(painterResource(R.drawable.logo_mark), "Buffer Zone", tint = Color.Unspecified, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(14.dp))
        RailItem(Icons.Outlined.Home, "home", activeKey) { App.go(Screen.Home, root = true) }
        RailItem(Icons.Outlined.Search, "search", activeKey) { App.go(Screen.Search, root = true) }
        lib("movie", g.movie)?.let { id -> RailItem(Icons.Outlined.Movie, "movie", activeKey) { App.go(Screen.Library(id), root = true) } }
        lib("show", g.show)?.let { id -> RailItem(Icons.Outlined.Tv, "show", activeKey) { App.go(Screen.Library(id), root = true) } }
        lib("kids", g.kids)?.let { id -> RailItem(Icons.Outlined.SentimentSatisfied, "kids", activeKey) { App.go(Screen.Library(id), root = true) } }
        RailItem(Icons.Outlined.BookmarkBorder, "watchlist", activeKey) { App.go(Screen.Watchlist, root = true) }
        if (App.multiviewAllowed) RailItem(Icons.Outlined.GridView, "multiview", activeKey) { App.go(Screen.MultiView, root = true) }
        val pins = App.store.pinned
        if (pins.isNotEmpty()) Box(Modifier.padding(vertical = 8.dp).width(38.dp).height(1.dp).background(C.line))
        pins.forEach { p ->
            RailItem(Icons.Outlined.PushPin, "pin:${p.key}", activeKey) {
                App.go(when (p.type) { "movie" -> Screen.Movie(p.key); "collection" -> Screen.Collection(p.key); else -> Screen.Show(p.key) }, root = true)
            }
        }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Outlined.Settings, "settings", activeKey) { App.go(Screen.Settings(), root = true) }
        val name = App.profile?.title ?: App.user?.optString("username") ?: "?"
        Focusable({ if (App.homeUsers.size > 1) App.go(Screen.Profiles, root = true) else App.go(Screen.Settings("account"), root = true) }, shape = CircleShape) { _ ->
            Box(Modifier.size(36.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) {
                T(name.take(1).uppercase(), 14.sp, FontWeight.Bold, C.accent)
            }
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, key: String, active: String, onClick: () -> Unit) {
    val on = key == active
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(18.dp).background(if (on) C.accent else Color.Transparent))
        Spacer(Modifier.width(5.dp))
        Focusable(onClick) { f ->
            Box(Modifier.size(44.dp).background(if (on) C.accentSoft else if (f) C.bg3 else Color.Transparent), contentAlignment = Alignment.Center) {
                Icon(icon, key, tint = if (on) C.accent else if (f) C.text else C.muted, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.accent) }

/** Runs [block] when the screen opens and again when watched state or settings change. */
@Composable
fun Reload(vararg keys: Any?, block: suspend () -> Unit) {
    LaunchedEffect(App.dataVersion, *keys) { block() }
}
