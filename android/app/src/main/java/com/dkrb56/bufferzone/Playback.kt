package com.dkrb56.bufferzone

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class Quality(val id: String, val label: String, val short: String, val bitrate: Int? = null, val res: String? = null)

val QUALITY = listOf(
    Quality("direct", "Direct Play (original file)", "Direct Play"),
    Quality("original", "Original quality", "Original"),
    Quality("20000", "20 Mbps · 1080p", "20 Mbps", 20000, "1920x1080"),
    Quality("12000", "12 Mbps · 1080p", "12 Mbps", 12000, "1920x1080"),
    Quality("8000", "8 Mbps · 1080p", "8 Mbps", 8000, "1920x1080"),
    Quality("4000", "4 Mbps · 720p", "4 Mbps", 4000, "1280x720"),
    Quality("2000", "2 Mbps · 720p", "2 Mbps", 2000, "1280x720"),
    Quality("1000", "1 Mbps · 480p", "1 Mbps", 1000, "720x480")
)

private val DIRECT_CONTAINERS = setOf("mkv", "mp4", "m4v", "mov", "webm", "mpegts", "ts")

/**
 * One playback: Direct Play through ExoPlayer, with a converted (HLS) stream from the Plex transcoder as fallback.
 * Handles track selection, subtitles, markers, the next episode, watch tracking and keep-alive.
 * `tile` playbacks (Multi-view) skip next-episode logic and the volume boost.
 */
@OptIn(UnstableApi::class)
class Playback(private val ctx: Context, val tile: Boolean = false) {
    private val trackSelector = DefaultTrackSelector(ctx)
    private val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
    val player: ExoPlayer = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timer: Job? = null
    private var enhancer: LoudnessEnhancer? = null

    var meta by mutableStateOf<Item?>(null); private set
    var part: Part? = null; private set
    var media: Media? = null; private set
    val audioStreams get() = part?.streams?.filter { it.streamType == 2 }.orEmpty()
    val subStreams get() = part?.streams?.filter { it.streamType == 3 }.orEmpty()
    var audioId by mutableStateOf<Long?>(null); private set
    var subId by mutableStateOf<Long?>(null); private set
    var mode by mutableStateOf(""); private set           // "direct" or "hls"
    var subBurned by mutableStateOf(false); private set
    var qualityOverride: String? = null
    val quality get() = qualityOverride ?: App.store.quality
    var error by mutableStateOf<String?>(null); private set
    var buffering by mutableStateOf(true); private set
    var playing by mutableStateOf(false); private set
    var position by mutableStateOf(0.0); private set       // seconds, absolute in both modes
    var duration by mutableStateOf(0.0); private set
    var leaves by mutableStateOf<List<Item>>(emptyList()); private set
    var nextEp by mutableStateOf<Item?>(null); private set
    var markers: List<Marker> = emptyList(); private set
    val isEpisode get() = meta?.type == "episode"

    private var playbackId = UUID.randomUUID().toString()
    private var transcodeSession: String? = null
    private var scrobbled = false
    private var forceHls = false
    private var tracksChecked = false
    private var restarts = mutableListOf<Long>()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_ENDED) onEnded?.invoke()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                report(if (isPlaying) "playing" else "paused")
            }
            override fun onPlayerError(e: PlaybackException) = onError(e)
            override fun onTracksChanged(tracks: Tracks) = checkTracks(tracks)
        })
        if (!tile) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val sid = am.generateAudioSessionId()
            player.setAudioSessionId(sid)
            enhancer = runCatching { LoudnessEnhancer(sid) }.getOrNull()
        }
    }

    var onEnded: (() -> Unit)? = null

    // ---------- start ----------
    fun play(req: PlayRequest) {
        stopInternal()
        error = null; buffering = true
        qualityOverride = req.quality
        scope.launch {
            try {
                val m = App.plex.metadata(req.item.ratingKey)
                meta = m
                media = m.media.firstOrNull()
                part = media?.parts?.firstOrNull() ?: throw PlexException("This title has no playable file.")
                duration = (if (m.duration > 0) m.duration else 0L) / 1000.0
                markers = m.markers
                playbackId = UUID.randomUUID().toString()
                scrobbled = false; forceHls = false; subBurned = false
                pickTracks()
                if (req.audioId != null) audioId = req.audioId
                if (req.subPreset) subId = req.subId?.takeIf { it != 0L }
                leaves = emptyList(); nextEp = null
                if (isEpisode && !tile) findNext()
                load(req.startAt)
            } catch (e: Exception) { fail(e.message ?: "Playback failed") }
        }
    }

    private fun lang(s: Stream?) = s?.languageCode ?: ""

    /** Same rules as the desktop: series memory, then the preferred languages, then the server's selection. */
    private fun pickTracks() {
        val st = App.store
        val m = meta ?: return
        val pref = if (m.type == "episode") st.seriesPref(m.grandparentRatingKey ?: "") else org.json.JSONObject()
        val audioLang = pref.optString("audioLang").ifEmpty { st.audioLang }
        val audio = audioStreams.firstOrNull { lang(it) == audioLang } ?: audioStreams.firstOrNull { it.selected } ?: audioStreams.firstOrNull()
        audioId = audio?.id
        val subPref = pref.optString("subtitleLang")
        val sub = when {
            subPref == "off" -> null
            subPref.isNotEmpty() -> bestSub(subPref, false)
            st.subtitleMode == "always" -> bestSub(st.subtitleLang, false)
            st.subtitleMode == "auto" -> if (lang(audio).isNotEmpty() && lang(audio) != st.subtitleLang) bestSub(st.subtitleLang, false) else bestSub(st.subtitleLang, true)
            else -> null
        }
        subId = sub?.id
    }

    private fun bestSub(lang: String, forcedOnly: Boolean): Stream? = subStreams
        .filter { it.languageCode == lang && (!forcedOnly || it.forced) }
        .maxByOrNull { (if (forcedOnly) 0 else if (it.forced) -4 else 0) + (if (it.isText) 2 else 0) + (if (it.key != null) 1 else 0) }

    val audio get() = audioStreams.firstOrNull { it.id == audioId }
    val sub get() = subStreams.firstOrNull { it.id == subId }

    private fun subtitleMime(codec: String) = when (codec) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    // ---------- load ----------
    private suspend fun load(startAt: Double) {
        val p = part ?: return
        val m = meta ?: return
        tracksChecked = false
        runCatching { App.plex.selectStreams(p.id, audioId, subId ?: 0) }
        val container = (media?.container ?: "").lowercase()
        val sub = this.sub
        val externalSub = sub?.takeIf { it.key != null && it.isText }
        val subConfigs = if (externalSub != null) listOf(
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(App.plex.url(externalSub.key!!, mapOf("X-Plex-Token" to App.plex.server?.token))))
                .setId("ext-${externalSub.id}").setMimeType(subtitleMime(externalSub.codec)).setLanguage(externalSub.languageCode)
                .setSelectionFlags(MC.SELECTION_FLAG_DEFAULT).build()
        ) else emptyList()

        val direct = quality == "direct" && !forceHls && container in DIRECT_CONTAINERS
        val item: MediaItem
        if (direct) {
            mode = "direct"; subBurned = false
            val url = App.plex.url(p.key, mapOf("X-Plex-Token" to App.plex.server?.token))
            item = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subConfigs).build()
        } else {
            mode = "hls"
            // Embedded subtitles cannot leave the transcoder as a file here (see .claude/memory.md), so burn them in.
            val burn = sub != null && externalSub == null
            subBurned = burn
            transcodeSession = UUID.randomUUID().toString()
            val params = hlsParams(startAt, if (burn) "burn" else "none")
            runCatching { App.plex.get("/video/:/transcode/universal/decision", params) }
            val url = App.plex.url("/video/:/transcode/universal/start.m3u8", params)
            item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).setSubtitleConfigurations(subConfigs).build()
        }
        trackSelector.parameters = trackSelector.buildUponParameters()
            .clearOverrides()
            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, sub == null || subBurned)
            .build()
        // With copyts=1 the HLS playlist covers the whole file and keeps absolute times, so both modes start at startAt.
        player.setMediaItem(item, (startAt * 1000).toLong())
        player.prepare()
        player.playWhenReady = true
        applyStyle()
        applyBoost()
        startTimer()
    }

    private fun hlsParams(offset: Double, subtitles: String): Map<String, Any?> {
        val q = QUALITY.firstOrNull { it.id == quality } ?: QUALITY[1]
        val p = mutableMapOf<String, Any?>(
            "hasMDE" to 1, "path" to "/library/metadata/${meta?.ratingKey}", "mediaIndex" to 0, "partIndex" to 0,
            "protocol" to "hls", "fastSeek" to 1, "copyts" to 1,
            // Video may be copied; audio is converted, because the usual reason for this path is audio the TV cannot play.
            "directPlay" to 0, "directStream" to 1, "directStreamAudio" to 0,
            "offset" to offset.toLong(), "session" to transcodeSession, "subtitles" to subtitles, "subtitleSize" to 100,
            "audioBoost" to 100, "mediaBufferSize" to 102400, "location" to if (App.plex.server?.local == true) "lan" else "wan",
            "autoAdjustQuality" to 0, "videoQuality" to 100
        )
        if (q.bitrate != null) { p["maxVideoBitrate"] = q.bitrate; p["videoResolution"] = q.res }
        return p + App.plex.clientParams(App.plex.server?.token) + ("X-Plex-Session-Identifier" to playbackId)
    }

    /** Applies the chosen audio and subtitle tracks once ExoPlayer knows them; falls back to HLS when the TV cannot play the file. */
    private fun checkTracks(tracks: Tracks) {
        if (tracksChecked || tracks.groups.isEmpty()) return
        tracksChecked = true
        if (mode == "direct") {
            val videoGroups = tracks.groups.filter { it.type == MC.TRACK_TYPE_VIDEO }
            if (videoGroups.isNotEmpty() && videoGroups.none { it.isSupported }) return fallbackToHls("video codec not supported")
            val audioGroups = tracks.groups.filter { it.type == MC.TRACK_TYPE_AUDIO }
            val target = matchGroup(audioGroups, audioStreams, audio)
            if (audioGroups.isNotEmpty()) {
                if (target == null || !target.isSupported) return fallbackToHls("audio not supported (${audio?.codec})")
                trackSelector.parameters = trackSelector.buildUponParameters().setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0)).build()
            }
        }
        applySubtitleSelection(tracks)
    }

    private fun applySubtitleSelection(tracks: Tracks = player.currentTracks) {
        val s = sub
        val textGroups = tracks.groups.filter { it.type == MC.TRACK_TYPE_TEXT }
        val b = trackSelector.buildUponParameters()
        if (s == null || subBurned) { trackSelector.parameters = b.setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true).build(); return }
        val target = textGroups.firstOrNull { it.mediaTrackGroup.getFormat(0).id == "ext-${s.id}" }
            ?: matchGroup(textGroups.filter { !(it.mediaTrackGroup.getFormat(0).id ?: "").startsWith("ext-") }, subStreams.filter { it.key == null }, s)
        trackSelector.parameters = if (target != null && target.isSupported)
            b.setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0)).build()
        else b.setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true).build()
    }

    /** Plex stream -> ExoPlayer track: container track number first (Plex index + 1), else the position among tracks of that type. */
    private fun matchGroup(groups: List<Tracks.Group>, streams: List<Stream>, s: Stream?): Tracks.Group? {
        if (s == null || groups.isEmpty()) return groups.firstOrNull()
        val idx = s.index
        if (idx != null) groups.firstOrNull { it.mediaTrackGroup.getFormat(0).id == (idx + 1).toString() }?.let { return it }
        val pos = streams.indexOfFirst { it.id == s.id }
        return groups.getOrNull(pos) ?: groups.firstOrNull { it.mediaTrackGroup.getFormat(0).language?.startsWith(s.languageCode.take(2)) == true }
    }

    private fun onError(e: PlaybackException) {
        App.log("player error ${e.errorCodeName}: ${e.message}")
        if (mode == "direct") { fallbackToHls(e.errorCodeName); return }
        // HTTP 404: the server ended the session (long pause or network break). Restart at the same spot, at most twice a minute.
        val is404 = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode == 404
        val now = System.currentTimeMillis()
        restarts.removeAll { now - it > 60_000 }
        if (is404 && restarts.size < 2) { restarts += now; scope.launch { reload(position) }; return }
        fail("Stream error: ${e.errorCodeName}")
    }

    private fun fallbackToHls(reason: String) {
        App.log("fallback to hls: $reason")
        if (!tile) App.show("This file cannot play directly. Switching to a converted stream.")
        val t = position
        forceHls = true
        scope.launch { reload(t) }
    }

    private suspend fun reload(t: Double) {
        stopTranscode()
        load(t)
    }

    fun fail(msg: String) { error = msg; buffering = false; App.log("playback failed: $msg") }

    // ---------- controls ----------
    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }

    fun seekTo(t0: Double) {
        val t = t0.coerceIn(0.0, (duration - 1).coerceAtLeast(0.0))
        if (mode == "direct") { player.seekTo((t * 1000).toLong()); position = t; return }
        // HLS: inside the buffer seek directly; outside, start a new transcode at the new position.
        val bufferedTo = player.bufferedPosition / 1000.0
        if (t >= position - 30 && t <= bufferedTo) { player.seekTo((t * 1000).toLong()); position = t; return }
        position = t
        scope.launch { reload(t) }
    }

    fun seekBy(d: Double) = seekTo(position + d)

    fun changeAudio(s: Stream) {
        if (s.id == audioId) return
        audioId = s.id
        meta?.let { m -> if (m.type == "episode" && s.languageCode.isNotEmpty()) App.store.setSeriesPref(m.grandparentRatingKey ?: "", "audioLang", s.languageCode) }
        if (mode == "direct") {
            tracksChecked = false
            checkTracks(player.currentTracks)
            scope.launch { runCatching { App.plex.selectStreams(part!!.id, s.id, null) } }
        } else { forceHls = false; scope.launch { reload(position) } }
    }

    fun changeSub(s: Stream?) {
        if (s?.id == subId) return
        val prevNeedsReload = mode == "hls" && (subBurned || sub?.key != null)
        subId = s?.id
        meta?.let { m -> if (m.type == "episode") App.store.setSeriesPref(m.grandparentRatingKey ?: "", "subtitleLang", s?.languageCode?.ifEmpty { "off" } ?: "off") }
        val needsReload = (s?.key != null && s.isText) || (mode == "hls" && s != null) || prevNeedsReload
        if (!needsReload) {
            applySubtitleSelection()
            scope.launch { runCatching { App.plex.selectStreams(part!!.id, null, s?.id ?: 0) } }
        } else scope.launch { reload(position) }
    }

    fun changeQuality(id: String) {
        qualityOverride = null
        App.store.quality = id
        forceHls = false
        scope.launch { reload(position) }
    }

    /** Subtitle look from the settings; set by the player view. */
    var applyStyle: () -> Unit = {}

    fun applyBoost() {
        val e = enhancer ?: return
        val boost = App.store.audioBoost
        runCatching {
            // 150 % ≈ +3.5 dB, 200 % ≈ +6 dB, 300 % ≈ +9.5 dB (gain in millibels).
            e.setTargetGain((2000 * Math.log10(boost / 100.0)).toInt())
            e.enabled = boost > 100
        }
    }

    // ---------- next episode ----------
    private suspend fun findNext() {
        val m = meta ?: return
        val list = runCatching { App.plex.allLeaves(m.grandparentRatingKey ?: return) }.getOrDefault(emptyList())
        if (meta?.ratingKey != m.ratingKey) return
        leaves = list
        val i = list.indexOfFirst { it.ratingKey == m.ratingKey }
        nextEp = if (i >= 0) list.getOrNull(i + 1) else null
    }

    fun activeMarker(t: Double = position) = markers.firstOrNull { t >= it.start && t < it.end - 0.5 }

    // ---------- watch tracking & keep-alive ----------
    private fun startTimer() {
        timer?.cancel()
        timer = scope.launch {
            var tick = 0
            while (isActive) {
                position = player.currentPosition / 1000.0
                if (duration <= 0 && player.duration > 0) duration = player.duration / 1000.0
                if (tick % 20 == 0) report(if (player.isPlaying) "playing" else "paused")
                // Plex ends idle HLS sessions after a few minutes; a ping every 30 s keeps them.
                if (mode == "hls" && tick % 60 == 0) transcodeSession?.let { App.plex.ping(it) }
                tick++
                delay(500)
            }
        }
    }

    fun report(state: String) {
        val m = meta ?: return
        val t = position; val d = duration
        scope.launch { App.plex.timeline(m.ratingKey, state, (t * 1000).toLong(), (d * 1000).toLong(), playbackId) }
        if (!scrobbled && d > 0 && t / d > 0.9) { scrobbled = true; scope.launch { runCatching { App.plex.scrobble(m.ratingKey) } } }
    }

    fun markWatched() {
        val m = meta ?: return
        if (!scrobbled) { scrobbled = true; scope.launch { runCatching { App.plex.scrobble(m.ratingKey) } } }
    }

    private suspend fun stopTranscode() {
        val s = transcodeSession ?: return
        transcodeSession = null
        App.plex.stopTranscode(s)
    }

    private fun stopInternal() {
        if (meta != null) report("stopped")
        timer?.cancel()
        player.stop()
        val s = transcodeSession
        transcodeSession = null
        if (s != null) App.scope.launch { App.plex.stopTranscode(s) }
    }

    fun release() {
        stopInternal()
        meta = null
        enhancer?.release()
        player.release()
        scope.cancel()
    }
}
