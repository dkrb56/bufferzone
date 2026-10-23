// Video player: direct play or HLS from the Plex transcoder, track selection,
// styled subtitles, skip intro/credits, auto-play next episode, watch tracking.
(function () {
  const { h, fmtTime, epCode, toast, Subtitles } = window.Sofa;

  const QUALITY = [
    { id: 'direct', label: 'Direct Play (original file)', short: 'Direct' },
    { id: 'original', label: 'Original quality', short: 'Original' },
    { id: '20000', label: '20 Mbps · 1080p', short: '20 Mbps', bitrate: 20000, res: '1920x1080' },
    { id: '12000', label: '12 Mbps · 1080p', short: '12 Mbps', bitrate: 12000, res: '1920x1080' },
    { id: '8000', label: '8 Mbps · 1080p', short: '8 Mbps', bitrate: 8000, res: '1920x1080' },
    { id: '4000', label: '4 Mbps · 720p', short: '4 Mbps', bitrate: 4000, res: '1280x720' },
    { id: '2000', label: '2 Mbps · 720p', short: '2 Mbps', bitrate: 2000, res: '1280x720' },
    { id: '1000', label: '1 Mbps · 480p', short: '1 Mbps', bitrate: 1000, res: '720x480' }
  ];

  const DIRECT_CONTAINERS = ['mp4', 'm4v', 'mov', 'webm', 'mkv'];
  const DIRECT_AUDIO = ['aac', 'mp3', 'opus', 'vorbis', 'flac'];

  function canDecodeVideo(stream) {
    const c = String(stream?.codec || '').toLowerCase();
    if (/10/.test(String(stream?.profile || '')) && c === 'h264') return false; // Hi10P
    if (['h264', 'vp8', 'vp9', 'av1'].includes(c)) return true;
    if (c === 'hevc') return MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L120.90"');
    return false;
  }

  const uuid = () => crypto.randomUUID();
  const langOf = (s) => String(s?.languageCode || '').toLowerCase();

  class Player {
    constructor(app) {
      this.app = app;
      this.root = document.getElementById('player');
      this.build();
    }

    get plex() { return this.app.plex; }
    get settings() { return this.app.settings; }

    // ---------- DOM ----------
    build() {
      this.video = h('video', { preload: 'auto' });
      this.subEl = h('div.subs');
      this.spinner = h('div.spinner');
      this.errorBox = h('div.player-error');
      this.skipBtn = h('button.skip-btn', { onclick: () => this.doSkip() });
      this.nextCard = h('div.next-card');
      this.titleEl = h('div.p-title');
      this.timeEl = h('span.p-time');
      this.seekFill = h('div.seek-fill');
      this.seekBuf = h('div.seek-buf');
      this.seekHover = h('div.seek-hover');
      this.markersEl = h('div.seek-markers');
      this.seek = h('div.seek', {
        onclick: (e) => this.seekTo(this.posFromEvent(e)),
        onmousemove: (e) => {
          const t = this.posFromEvent(e);
          this.seekHover.textContent = fmtTime(t);
          this.seekHover.style.left = `${(t / this.duration) * 100}%`;
        }
      }, this.seekBuf, this.markersEl, this.seekFill, this.seekHover);
      this.playBtn = h('button.p-btn.big', { title: 'Play / Pause (Space)', onclick: () => this.togglePlay() });
      this.volume = h('input.vol', {
        type: 'range', min: 0, max: 1, step: 0.05, value: 1,
        oninput: () => { this.video.volume = +this.volume.value; this.video.muted = false; }
      });
      this.nextBtn = h('button.p-btn', { title: 'Next episode (N)', onclick: () => this.playNext() }, '⏭');
      this.audioBtn = h('button.p-btn.txt', { onclick: (e) => this.openMenu('audio', e) }, 'Audio');
      this.subBtn = h('button.p-btn.txt', { onclick: (e) => this.openMenu('subs', e) }, 'Subtitles');
      this.qualBtn = h('button.p-btn.txt', { onclick: (e) => this.openMenu('quality', e) }, 'Quality');
      this.menu = h('div.p-menu');
      this.modeEl = h('span.p-mode');

      this.controls = h('div.p-controls',
        h('div.p-top',
          h('button.p-btn', { title: 'Back (Esc)', onclick: () => this.exit() }, '←'),
          this.titleEl,
          this.modeEl
        ),
        h('div.p-bottom',
          this.seek,
          h('div.p-row',
            this.playBtn,
            h('button.p-btn', { title: 'Back 10 s (←)', onclick: () => this.seekBy(-10) }, '↺10'),
            h('button.p-btn', { title: 'Forward 30 s (→)', onclick: () => this.seekBy(30) }, '30↻'),
            this.nextBtn,
            this.timeEl,
            h('div.spacer'),
            this.audioBtn, this.subBtn, this.qualBtn,
            h('span.vol-icon', { onclick: () => { this.video.muted = !this.video.muted; this.updateUi(); } }, '🔊'),
            this.volume,
            h('button.p-btn', { title: 'Fullscreen (F)', onclick: () => sofa.fullscreen() }, '⛶')
          )
        )
      );

      this.root.append(this.video, this.subEl, this.spinner, this.errorBox, this.skipBtn, this.nextCard, this.controls, this.menu);
      this.overlay = new Subtitles.SubtitleOverlay(this.subEl, () => this.time());

      const v = this.video;
      v.addEventListener('timeupdate', () => this.onTick());
      v.addEventListener('play', () => { this.updateUi(); this.report('playing'); });
      v.addEventListener('pause', () => { this.updateUi(); this.report('paused'); });
      v.addEventListener('waiting', () => this.spinner.classList.add('on'));
      v.addEventListener('playing', () => this.spinner.classList.remove('on'));
      v.addEventListener('canplay', () => this.spinner.classList.remove('on'));
      v.addEventListener('ended', () => this.onEnded());
      v.addEventListener('error', () => this.onVideoError());
      v.addEventListener('volumechange', () => this.updateUi());
      v.addEventListener('click', () => this.togglePlay());
      v.addEventListener('dblclick', () => sofa.fullscreen());

      this.root.addEventListener('mousemove', () => this.poke());
      document.addEventListener('keydown', (e) => this.onKey(e));
      document.addEventListener('click', (e) => {
        if (!this.menu.contains(e.target) && !e.target.closest('.p-btn.txt')) this.closeMenu();
      });
    }

    // ---------- lifecycle ----------
    async play(item, { startAt = 0 } = {}) {
      this.stopInternal();
      this.root.classList.add('on');
      document.body.classList.add('playing');
      this.errorBox.classList.remove('on');
      this.spinner.classList.add('on');
      this.nextCard.classList.remove('on');
      this.skipBtn.classList.remove('on');
      this.overlay.applyStyle(this.settings);
      this.overlay.clear();
      this.overlay.start();
      this.poke();

      try {
        const meta = await this.plex.metadata(item.ratingKey);
        this.meta = meta;
        this.media = meta.Media[0];
        this.part = this.media.Part[0];
        const streams = this.part.Stream || [];
        this.audioStreams = streams.filter(s => s.streamType === 2);
        this.subStreams = streams.filter(s => s.streamType === 3);
        this.videoStream = streams.find(s => s.streamType === 1);
        this.duration = (meta.duration || this.media.duration || 0) / 1000;
        this.markers = (meta.Marker || []).map(m => ({ type: m.type, start: m.startTimeOffset / 1000, end: m.endTimeOffset / 1000, final: !!m.final }));
        this.playbackId = uuid();
        this.scrobbled = false;
        this.skipped = new Set();
        this.nextEp = undefined;
        this.nextCancelled = false;
        this.isEpisode = meta.type === 'episode';
        this.titleEl.textContent = this.isEpisode
          ? `${meta.grandparentTitle} · ${epCode(meta)} · ${meta.title}`
          : `${meta.title}${meta.year ? ` (${meta.year})` : ''}`;
        this.pickTracks();
        this.drawMarkers();
        if (this.isEpisode) this.findNext();
        this.nextBtn.style.display = this.isEpisode ? '' : 'none';
        await this.load(startAt);
      } catch (err) {
        this.fail(err);
      }
    }

    pickTracks() {
      const s = this.settings;
      const pref = (this.isEpisode && s.seriesPrefs[this.meta.grandparentRatingKey]) || {};
      const audioLang = pref.audioLang || s.audioLang;
      const audio =
        this.audioStreams.find(a => langOf(a) === audioLang) ||
        this.audioStreams.find(a => a.selected) ||
        this.audioStreams[0];
      this.audioId = audio?.id ?? null;

      let sub = null;
      if (pref.subtitleLang === 'off') sub = null;
      else if (pref.subtitleLang) sub = this.bestSub(pref.subtitleLang, false);
      else if (s.subtitleMode === 'always') sub = this.bestSub(s.subtitleLang, false);
      else if (s.subtitleMode === 'auto') {
        // Show subtitles when the audio is not in the subtitle language; else only forced ones.
        sub = langOf(audio) && langOf(audio) !== s.subtitleLang
          ? this.bestSub(s.subtitleLang, false)
          : this.bestSub(s.subtitleLang, true);
      }
      this.subId = sub?.id ?? null;
    }

    bestSub(lang, forcedOnly) {
      const list = this.subStreams.filter(x => langOf(x) === lang && (!forcedOnly || x.forced));
      // Prefer full (not forced), text over image, external over embedded.
      const score = (x) => (forcedOnly ? 0 : x.forced ? -4 : 0) + (Subtitles.isTextSub(x) ? 2 : 0) + (x.key ? 1 : 0);
      return list.sort((a, b) => score(b) - score(a))[0] || null;
    }

    get audio() { return this.audioStreams.find(a => a.id === this.audioId); }
    get sub() { return this.subStreams.find(x => x.id === this.subId); }

    canDirectPlay() {
      const container = String(this.media.container || '').toLowerCase();
      const def = this.audioStreams.find(a => a.default) || this.audioStreams[0];
      const audio = this.audio;
      if (!DIRECT_CONTAINERS.includes(container)) return 'container ' + container;
      if (!canDecodeVideo(this.videoStream)) return 'video codec ' + this.videoStream?.codec;
      if (audio && def && audio.id !== def.id) return 'non-default audio track';
      if (audio && !DIRECT_AUDIO.includes(String(audio.codec).toLowerCase())) return 'audio codec ' + audio.codec;
      const sub = this.sub;
      if (sub && !(sub.key && Subtitles.isTextSub(sub))) return 'embedded or image subtitles';
      return true;
    }

    async load(startAt) {
      this.destroyStream();
      this.overlay.clear();

      // Tell the server which tracks we want; the transcoder uses this selection.
      try {
        await this.plex.selectStreams(this.part.id, { audioStreamID: this.audioId ?? undefined, subtitleStreamID: this.subId ?? 0 });
      } catch (e) { sofa.log('selectStreams failed', String(e)); }

      const q = this.settings.quality;
      const direct = q === 'direct' && !this.forceHls ? this.canDirectPlay() : 'quality setting';
      const sub = this.sub;

      if (direct === true) {
        this.mode = 'direct';
        this.offset = 0;
        this.video.src = this.plex.url(this.part.key, { 'X-Plex-Token': this.plex.server.token });
        this.video.addEventListener('loadedmetadata', () => { if (startAt) this.video.currentTime = startAt; }, { once: true });
        this.watchdog = setTimeout(() => {
          if (this.mode === 'direct' && this.video.readyState < 2) this.fallbackToHls('direct play timed out');
        }, 20000);
      } else {
        this.mode = 'hls';
        let subMode = 'none';
        if (sub && !(sub.key && Subtitles.isTextSub(sub))) subMode = Subtitles.isTextSub(sub) && !this.burnSubs ? 'auto' : 'burn';
        this.subMode = subMode;
        await this.startHls(startAt, subMode);
        sofa.log('hls', { reason: direct, quality: q, subMode });
      }

      if (sub && sub.key && Subtitles.isTextSub(sub)) this.loadExternalSub(sub);
      this.modeEl.textContent = this.mode === 'direct' ? 'Direct Play' : (QUALITY.find(x => x.id === q)?.short || '') + ' · stream';
      this.video.volume = this.settings.volume ?? 1;
      this.volume.value = this.video.volume;
      this.video.play().catch(() => {});
      this.startTimers();
      this.updateUi();
    }

    hlsParams(offset, subMode) {
      const q = QUALITY.find(x => x.id === this.settings.quality) || QUALITY[1];
      const p = {
        hasMDE: 1,
        path: `/library/metadata/${this.meta.ratingKey}`,
        mediaIndex: 0, partIndex: 0,
        protocol: 'hls', fastSeek: 1, copyts: 1,
        directPlay: 0, directStream: 1, directStreamAudio: 1,
        offset: Math.floor(offset),
        session: this.transcodeSession,
        subtitles: subMode, subtitleSize: 100,
        audioBoost: 100, mediaBufferSize: 102400,
        location: this.plex.server.local ? 'lan' : 'wan',
        autoAdjustQuality: 0,
        videoQuality: 100
      };
      if (q.bitrate) Object.assign(p, { maxVideoBitrate: q.bitrate, videoResolution: q.res });
      return { ...p, ...this.plex.clientParams(this.plex.server.token), 'X-Plex-Session-Identifier': this.playbackId };
    }

    async startHls(offset, subMode) {
      this.transcodeSession = uuid();
      this.offset = Math.floor(offset);
      const params = this.hlsParams(this.offset, subMode);

      let decision = null;
      try {
        decision = await this.plex.get('/video/:/transcode/universal/decision', params);
        const s = (decision?.Metadata?.[0]?.Media?.[0]?.Part?.[0]?.Stream) || [];
        sofa.log('decision', {
          general: decision?.generalDecisionText, transcode: decision?.transcodeDecisionText,
          streams: s.map(x => ({ t: x.streamType, codec: x.codec, decision: x.decision, location: x.location }))
        });
        this.decisionSub = s.find(x => x.streamType === 3) || null;
      } catch (e) {
        sofa.log('decision failed', String(e));
      }

      const url = this.plex.url('/video/:/transcode/universal/start.m3u8', params);
      if (!window.Hls || !Hls.isSupported()) throw new Error('HLS playback is not available.');
      // With copyts=1 Plex lists the whole file in the playlist and keeps absolute timestamps;
      // segments before the offset are empty, so playback must start at the offset.
      const hls = new Hls({ maxBufferLength: 60, backBufferLength: 60, enableWebVTT: true, renderTextTracksNatively: false, startPosition: this.offset });
      this.hls = hls;
      this.hlsRecovered = false;
      hls.on(Hls.Events.ERROR, (_e, data) => this.onHlsError(data));
      hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, (_e, d) => {
        if (subMode === 'auto' && d.subtitleTracks.length) { hls.subtitleTrack = 0; hls.subtitleDisplay = false; }
      });
      hls.on(Hls.Events.CUES_PARSED, (_e, d) => this.addLiveCues(d.cues));
      hls.loadSource(url);
      hls.attachMedia(this.video);

      if (subMode === 'auto') this.loadSidecarSub(params);
    }

    // Embedded text subtitles: ask the transcoder for them as a separate file.
    // If that does not work, restart the stream with the subtitles burned into the video.
    async loadSidecarSub(params) {
      const myId = this.transcodeSession;
      await new Promise(r => setTimeout(r, 1500));
      if (myId !== this.transcodeSession) return;
      if (this.liveCueCount) return; // subtitles arrived inside the HLS stream
      let cues = [];
      try {
        const text = await this.plex.getText('/video/:/transcode/universal/subtitles', { ...params, offset: 0 });
        cues = Subtitles.parse(text);
        sofa.log('sidecar subs', { cues: cues.length, head: text.slice(0, 80) });
      } catch (e) { sofa.log('sidecar subs failed', String(e)); }
      if (myId !== this.transcodeSession) return;
      if (cues.length) { this.overlay.setCues(cues); return; }
      if (this.liveCueCount) return;
      sofa.log('falling back to burned subtitles');
      this.burnSubs = true;
      this.load(this.time());
    }

    addLiveCues(cues) {
      if (!cues || !cues.length) return;
      this.liveCueCount = (this.liveCueCount || 0) + cues.length;
      // Stream timestamps are absolute (copyts=1), so cue times are too.
      const extra = cues.map(c => ({ start: c.startTime, end: c.endTime, html: Subtitles.parse(`1\n00:00:00,000 --> 00:00:01,000\n${c.text}\n`)[0]?.html || '' }));
      const merged = this.overlay.cues.concat(extra).sort((a, b) => a.start - b.start);
      this.overlay.setCues(merged);
    }

    async loadExternalSub(sub) {
      try {
        const text = await this.plex.getText(sub.key, { encoding: 'utf-8' });
        const cues = Subtitles.parse(text);
        this.overlay.setCues(cues);
        sofa.log('external subs', { codec: sub.codec, cues: cues.length });
        if (!cues.length) toast('Could not read this subtitle file.');
      } catch (e) {
        toast('Could not load subtitles.');
        sofa.log('external subs failed', String(e));
      }
    }

    onHlsError(data) {
      sofa.log('hls error', { type: data.type, details: data.details, fatal: data.fatal, code: data.response?.code, sn: data.frag?.sn, reason: data.reason, error: String(data.error?.message || '') });
      if (!data.fatal) return;
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !this.hlsRecovered) {
        this.hlsRecovered = true;
        this.hls.recoverMediaError();
        return;
      }
      if (data.response?.code === 400 || data.response?.code === 403) {
        this.fail(new Error('The server refused the stream. Your friend may not allow conversion (transcoding) for shared users. Try "Direct Play" in Quality.'));
      } else {
        this.fail(new Error(`Stream error: ${data.details}`));
      }
    }

    onVideoError() {
      const err = this.video.error;
      if (!err || !this.meta) return;
      if (this.mode === 'direct') return this.fallbackToHls(`direct play error ${err.code}`);
      if (!this.hls) this.fail(new Error(`Video error ${err.code}: ${err.message || ''}`));
    }

    fallbackToHls(reason) {
      sofa.log('fallback to hls:', reason);
      toast('This file cannot play directly. Switching to a converted stream.');
      const t = this.video.currentTime || 0;
      this.forceHls = true;
      this.load(t);
    }

    fail(err) {
      sofa.log('player error', String(err?.message || err));
      this.spinner.classList.remove('on');
      this.errorBox.replaceChildren(
        h('div.err-title', 'Playback failed'),
        h('div.err-msg', err?.message || String(err)),
        h('button.btn', { onclick: () => this.exit() }, 'Back')
      );
      this.errorBox.classList.add('on');
    }

    destroyStream() {
      clearTimeout(this.watchdog);
      if (this.hls) {
        this.hls.destroy();
        this.hls = null;
        this.stopTranscode();
      }
      this.liveCueCount = 0;
      this.video.removeAttribute('src');
      this.video.load();
    }

    stopTranscode() {
      if (!this.transcodeSession || !this.plex.server) return;
      sofa.http({ url: this.plex.url('/video/:/transcode/universal/stop', { session: this.transcodeSession, 'X-Plex-Token': this.plex.server.token }), headers: this.plex.clientHeaders() });
    }

    stopInternal() {
      if (this.meta) this.report('stopped');
      clearInterval(this.timer);
      this.destroyStream();
      this.overlay?.stop();
      this.forceHls = false;
      this.burnSubs = false;
    }

    exit() {
      const meta = this.meta;
      this.closeMenu();
      this.stopInternal();
      this.meta = null;
      this.root.classList.remove('on');
      document.body.classList.remove('playing');
      sofa.fullscreen(false);
      this.app.onPlayerExit(meta);
    }

    // ---------- time & seeking ----------
    // Media time is absolute in both modes; before an HLS stream starts, report its start offset.
    time() { return this.video.currentTime || (this.mode === 'hls' ? this.offset : 0); }

    posFromEvent(e) {
      const r = this.seek.getBoundingClientRect();
      return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * this.duration;
    }

    seekBy(sec) { this.seekTo(this.time() + sec); }

    seekTo(t) {
      t = Math.max(0, Math.min(this.duration - 1, t));
      if (this.mode === 'direct') { this.video.currentTime = t; return; }
      const b = this.video.buffered;
      for (let i = 0; i < b.length; i++) {
        if (t >= b.start(i) && t <= b.end(i)) { this.video.currentTime = t; return; }
      }
      // Outside the buffer: restart the transcode at the new position.
      this.spinner.classList.add('on');
      this.destroyStream();
      this.startHls(t, this.subMode).then(() => this.video.play().catch(() => {})).catch(e => this.fail(e));
    }

    togglePlay() {
      if (this.video.paused) this.video.play().catch(() => {}); else this.video.pause();
    }

    // ---------- watch tracking ----------
    startTimers() {
      clearInterval(this.timer);
      this.timer = setInterval(() => {
        if (!this.video.paused) this.report('playing');
      }, 10000);
    }

    report(state) {
      if (!this.meta || !this.plex.server) return;
      const t = this.time();
      this.plex.timeline({ ratingKey: this.meta.ratingKey, state, time: t * 1000, duration: this.duration * 1000, sessionId: this.playbackId });
      if (!this.scrobbled && this.duration && t / this.duration > 0.9) {
        this.scrobbled = true;
        this.plex.scrobble(this.meta.ratingKey).catch(() => {});
      }
    }

    // ---------- markers, skip, next ----------
    drawMarkers() {
      this.markersEl.replaceChildren(...this.markers.map(m => h('div.mk', {
        title: m.type,
        style: { left: `${(m.start / this.duration) * 100}%`, width: `${((m.end - m.start) / this.duration) * 100}%` }
      })));
    }

    async findNext() {
      const key = this.meta.ratingKey;
      try {
        const leaves = await this.plex.allLeaves(this.meta.grandparentRatingKey);
        if (!this.meta || this.meta.ratingKey !== key) return;
        const i = leaves.findIndex(l => l.ratingKey === key);
        this.nextEp = i >= 0 ? leaves[i + 1] || null : null;
      } catch { this.nextEp = null; }
      this.nextBtn.disabled = !this.nextEp;
    }

    activeMarker(t) {
      return this.markers.find(m => t >= m.start && t < m.end - 0.5);
    }

    onTick() {
      if (!this.meta) return;
      const t = this.time();
      this.updateUi();

      const m = this.activeMarker(t);
      const s = this.settings;
      if (m && m.type === 'intro') {
        if (s.autoSkipIntro && !this.skipped.has(m)) { this.skipped.add(m); this.seekTo(m.end); toast('Intro skipped'); return; }
        this.showSkip('Skip Intro', m);
      } else if (m && m.type === 'credits') {
        const hasNext = this.isEpisode && this.nextEp;
        if (s.autoSkipCredits && !this.skipped.has(m) && (!m.final || hasNext)) {
          this.skipped.add(m);
          if (m.final) this.playNext(); else this.seekTo(m.end);
          return;
        }
        if (m.final && hasNext && s.autoPlayNext && !this.nextCancelled) this.skipBtn.classList.remove('on');
        else this.showSkip(m.final && hasNext ? 'Next Episode' : 'Skip Credits', m);
      } else {
        this.skipBtn.classList.remove('on');
        this.skipTarget = null;
      }

      // Next-episode countdown: at the final credits marker, or 30 s before the end.
      if (this.isEpisode && this.nextEp && s.autoPlayNext && !this.nextCancelled) {
        const credits = this.markers.find(x => x.type === 'credits' && x.final) ||
          this.markers.filter(x => x.type === 'credits').sort((a, b) => b.start - a.start)[0];
        const at = credits ? credits.start : this.duration - 30;
        if (t >= at && t < this.duration - 0.5) this.showNextCard(); else this.hideNextCard();
      } else {
        this.hideNextCard();
      }
    }

    showSkip(label, marker) {
      this.skipTarget = marker;
      this.skipBtn.textContent = label;
      this.skipBtn.classList.add('on');
    }

    doSkip() {
      const m = this.skipTarget;
      if (!m) return;
      this.skipped.add(m);
      if (m.type === 'credits' && m.final) {
        if (this.isEpisode && this.nextEp) this.playNext(); else this.onEnded();
        return;
      }
      this.seekTo(m.end);
    }

    showNextCard() {
      if (this.nextCard.classList.contains('on')) return;
      const ep = this.nextEp;
      let left = this.settings.nextCountdown;
      const count = h('span.count', String(left));
      this.nextCard.replaceChildren(
        h('img', { src: ep.thumb ? this.plex.image(ep.thumb, 320, 180) : '' }),
        h('div.nc-body',
          h('div.nc-label', 'Next episode in ', count, ' s'),
          h('div.nc-title', `${epCode(ep)} · ${ep.title}`),
          h('div.nc-actions',
            h('button.btn.primary', { onclick: () => this.playNext() }, 'Play now'),
            h('button.btn', { onclick: () => { this.nextCancelled = true; this.hideNextCard(); } }, 'Cancel')
          )
        )
      );
      this.nextCard.classList.add('on');
      clearInterval(this.countTimer);
      this.countTimer = setInterval(() => {
        if (this.video.paused) return;
        left -= 1;
        count.textContent = String(Math.max(0, left));
        if (left <= 0) this.playNext();
      }, 1000);
    }

    hideNextCard() {
      if (!this.nextCard.classList.contains('on')) return;
      clearInterval(this.countTimer);
      this.nextCard.classList.remove('on');
    }

    playNext() {
      const ep = this.nextEp;
      clearInterval(this.countTimer);
      this.nextCard.classList.remove('on');
      if (!ep) return;
      if (!this.scrobbled) { this.scrobbled = true; this.plex.scrobble(this.meta.ratingKey).catch(() => {}); }
      const resume = ep.viewOffset && !ep.viewCount ? ep.viewOffset / 1000 : 0;
      this.play(ep, { startAt: resume });
    }

    onEnded() {
      if (!this.scrobbled && this.meta) { this.scrobbled = true; this.plex.scrobble(this.meta.ratingKey).catch(() => {}); }
      if (this.isEpisode && this.nextEp && this.settings.autoPlayNext && !this.nextCancelled) this.playNext();
      else this.exit();
    }

    // ---------- menus ----------
    openMenu(kind, e) {
      e.stopPropagation();
      if (this.menu.dataset.kind === kind && this.menu.classList.contains('on')) return this.closeMenu();
      const items = [];
      const item = (label, active, fn, sub) => items.push(h('button.m-item' + (active ? '.active' : ''), { onclick: () => { this.closeMenu(); fn(); } }, h('span', label), sub ? h('small', sub) : null));
      if (kind === 'audio') {
        for (const a of this.audioStreams) item(a.displayTitle || a.title || a.language || `Track ${a.index}`, a.id === this.audioId, () => this.changeAudio(a), a.title && a.title !== a.displayTitle ? a.title : null);
      } else if (kind === 'subs') {
        item('Off', this.subId == null, () => this.changeSub(null));
        for (const x of this.subStreams) {
          const kindTxt = [x.forced ? 'forced' : '', x.key ? 'external' : 'embedded', Subtitles.isTextSub(x) ? '' : 'image, burned in'].filter(Boolean).join(' · ');
          item(x.displayTitle || x.language || `Track ${x.index}`, x.id === this.subId, () => this.changeSub(x), [x.title, kindTxt].filter(Boolean).join(' — '));
        }
      } else {
        for (const q of QUALITY) item(q.label, q.id === this.settings.quality, () => this.changeQuality(q.id));
      }
      this.menu.replaceChildren(h('div.m-head', kind === 'audio' ? 'Audio' : kind === 'subs' ? 'Subtitles' : 'Quality'), ...items);
      this.menu.dataset.kind = kind;
      const r = e.currentTarget.getBoundingClientRect();
      this.menu.style.right = `${Math.max(12, window.innerWidth - r.right)}px`;
      this.menu.classList.add('on');
    }
    closeMenu() { this.menu.classList.remove('on'); }

    saveSeriesPref(patch) {
      if (!this.isEpisode) return;
      const key = this.meta.grandparentRatingKey;
      const all = { ...this.settings.seriesPrefs };
      all[key] = { ...(all[key] || {}), ...patch };
      this.app.saveSettings({ seriesPrefs: all });
    }

    changeAudio(a) {
      if (a.id === this.audioId) return;
      this.audioId = a.id;
      if (langOf(a)) this.saveSeriesPref({ audioLang: langOf(a) });
      this.forceHls = false;
      this.load(this.time());
    }

    changeSub(x) {
      if ((x?.id ?? null) === this.subId) return;
      const prev = this.sub;
      this.subId = x?.id ?? null;
      this.saveSeriesPref({ subtitleLang: x ? langOf(x) || 'off' : 'off' });
      this.burnSubs = false;
      // External text subtitles switch without reloading the video.
      const simple = (s) => !s || (s.key && Subtitles.isTextSub(s));
      if (simple(prev) && simple(x) && (this.mode === 'direct' || this.subMode === 'none')) {
        this.overlay.clear();
        if (x) this.loadExternalSub(x);
        this.plex.selectStreams(this.part.id, { subtitleStreamID: this.subId ?? 0 }).catch(() => {});
        return;
      }
      this.forceHls = false;
      this.load(this.time());
    }

    changeQuality(id) {
      this.app.saveSettings({ quality: id });
      this.forceHls = false;
      this.load(this.time());
    }

    // ---------- UI ----------
    updateUi() {
      const t = this.time();
      const d = this.duration || 1;
      this.playBtn.textContent = this.video.paused ? '▶' : '❚❚';
      this.timeEl.textContent = `${fmtTime(t)} / ${fmtTime(d)}`;
      this.seekFill.style.width = `${(t / d) * 100}%`;
      const b = this.video.buffered;
      this.seekBuf.style.width = b.length ? `${(b.end(b.length - 1) / d) * 100}%` : '0';
      this.audioBtn.style.display = this.audioStreams?.length > 1 ? '' : 'none';
      this.subBtn.textContent = this.sub ? `Subtitles: ${this.sub.languageCode || 'on'}` : 'Subtitles';
      this.qualBtn.textContent = QUALITY.find(q => q.id === this.settings.quality)?.short || 'Quality';
      if (Math.abs((this.settings.volume ?? 1) - this.video.volume) > 0.01) this.app.saveSettings({ volume: this.video.volume });
    }

    poke() {
      this.root.classList.add('show-ui');
      clearTimeout(this.hideTimer);
      this.hideTimer = setTimeout(() => {
        if (!this.video.paused && !this.menu.classList.contains('on')) this.root.classList.remove('show-ui');
      }, 3000);
    }

    onKey(e) {
      if (!this.root.classList.contains('on')) return;
      if (e.target.tagName === 'INPUT' && e.target.type !== 'range') return;
      const k = e.key.toLowerCase();
      const map = {
        ' ': () => this.togglePlay(), k: () => this.togglePlay(),
        arrowleft: () => this.seekBy(-10), arrowright: () => this.seekBy(30),
        arrowup: () => { this.video.volume = Math.min(1, this.video.volume + 0.05); },
        arrowdown: () => { this.video.volume = Math.max(0, this.video.volume - 0.05); },
        f: () => sofa.fullscreen(), m: () => { this.video.muted = !this.video.muted; },
        n: () => this.playNext(), s: () => this.doSkip(),
        escape: () => {
          if (this.menu.classList.contains('on')) return this.closeMenu();
          if (window.innerHeight === screen.height && window.innerWidth === screen.width) sofa.fullscreen(false);
          else this.exit();
        },
        backspace: () => this.exit()
      };
      if (map[k]) { e.preventDefault(); map[k](); this.poke(); }
    }
  }

  window.Sofa.Player = Player;
  window.Sofa.QUALITY = QUALITY;
})();
