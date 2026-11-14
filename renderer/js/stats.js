// Playback stats box in the player (key D, or Quality → Playback stats): delivery, buffer, segments,
// HTTP status, stalls, restarts and the last error, to tell a server fault from a network or client fault.
// While the box is off only the counters change: no timer, no DOM update and no hls.js listener run.
(function () {
  const { h } = window.Sofa;

  const clock = (ms) => new Date(ms).toTimeString().slice(0, 8);
  const mbps = (bps) => (bps > 0 ? `${(bps / 1e6).toFixed(1)} Mbps` : 'n/a');
  const LINES = ['Delivery', 'Server', 'Video', 'Buffer', 'Segment', 'Bitrate', 'Stalls', 'Last error'];

  class PlayerStats {
    constructor(player) {
      this.p = player;
      this.el = null;
      this.timer = null;
      this.hls = null;
      this.onFrag = (_e, d) => this.fragLoaded(d);
      this.reset();
    }

    get isOn() { return !!this.el; }

    // A new title starts at zero.
    reset() {
      this.stalls = 0;
      this.stalledMs = 0;
      this.stallStart = 0;
      this.restarts = 0;
      this.played = false;
      this.waitExpected = false;
      this.seg = null;
      this.lastStatus = null;
      this.lastPing = null;
      this.lastError = null;
    }

    // ---------- hooks the player calls (cheap, also while the box is off) ----------
    // A stall is a spinner after the stream played once; not the first load, not a seek.
    stallBegin() {
      if (this.stallStart || !this.played || this.waitExpected || this.p.video.seeking) return;
      this.stalls++;
      this.stallStart = Date.now();
    }
    stallEnd() {
      this.played = true;
      this.waitExpected = false;
      if (this.stallStart) { this.stalledMs += Date.now() - this.stallStart; this.stallStart = 0; }
    }
    // A user seek that restarts the stream shows a spinner but is no stall.
    expectWait() { this.waitExpected = true; }
    restart() { this.restarts++; }
    ping(status) { this.lastPing = { status, at: Date.now() }; }
    error(text, status) {
      this.lastError = { text, at: Date.now() };
      if (status) this.lastStatus = status;
    }

    // ---------- on / off ----------
    on() {
      if (this.p.tile || this.el) return;
      this.cells = {};
      this.el = h('div.p-stats', LINES.map(k => [h('span.k', k), this.cells[k] = h('span.v')]));
      this.p.root.append(this.el);
      this.attach(this.p.hls);
      this.timer = setInterval(() => this.tick(), 500);
      this.tick();
    }
    off() {
      clearInterval(this.timer);
      this.timer = null;
      this.detach();
      if (this.el) this.el.remove();
      this.el = null;
      this.cells = null;
    }
    // Every new session is a new hls.js instance; destroy() drops the old listener.
    attach(hls) {
      this.hls = null;
      if (!this.el || !hls || !window.Hls) return;
      hls.on(Hls.Events.FRAG_LOADED, this.onFrag);
      this.hls = hls;
    }
    detach() {
      if (this.hls) { try { this.hls.off(Hls.Events.FRAG_LOADED, this.onFrag); } catch (e) {} }
      this.hls = null;
    }

    fragLoaded(d) {
      const f = d && d.frag;
      if (!f || f.type !== 'main' || typeof f.sn !== 'number') return;
      const st = f.stats || {};
      const status = d.networkDetails && d.networkDetails.status;
      this.seg = { sn: f.sn, bytes: st.loaded || 0, ms: st.loading ? Math.max(0, st.loading.end - st.loading.start) : 0, at: Date.now() };
      if (status) this.lastStatus = status;
    }

    // ---------- drawing ----------
    tick() {
      if (!this.el) return;
      const p = this.p, v = p.video, now = Date.now();
      const hlsjs = p.mode === 'hls' && !p.nativeHls ? p.hls : null;
      const set = (k, text) => { if (this.cells[k].textContent !== text) this.cells[k].textContent = text; };

      // Delivery: the same quality label as the Quality chip.
      const Q = window.Sofa.QUALITY || [];
      const qLabel = p.quality === 'direct' ? 'Original' : (Q.find(q => q.id === p.quality) || {}).short || p.quality;
      set('Delivery', !p.mode ? 'starting' : p.mode === 'direct' ? 'Direct Play' : `Converted · ${qLabel} · subtitles ${p.subMode || 'none'}`);

      const srv = p.plex.server;
      const conn = !srv ? 'no server' : srv.relay ? 'Plex relay' : srv.local ? 'local network' : 'direct internet';
      const engine = p.mode === 'direct' ? 'direct file' : p.nativeHls ? 'TV player' : `hls.js ${window.Hls ? Hls.version : ''}`;
      set('Server', [conn, p.mode === 'hls' && p.transcodeSession ? `session ${p.transcodeSession.slice(0, 6)}` : null, engine].filter(Boolean).join(' · '));

      // Video: size and dropped frames.
      let frames = 'frames n/a';
      if (v.getVideoPlaybackQuality) {
        const q = v.getVideoPlaybackQuality();
        frames = `dropped ${q.droppedVideoFrames} of ${q.totalVideoFrames}`;
      } else if (v.webkitDecodedFrameCount !== undefined) {
        frames = `dropped ${v.webkitDroppedFrameCount} of ${v.webkitDecodedFrameCount}`;
      }
      set('Video', `${v.videoWidth ? `${v.videoWidth}×${v.videoHeight}` : 'no picture yet'} · ${frames}`);

      // Buffer ahead of the play position.
      const b = v.buffered, t = v.currentTime;
      let ahead = 0;
      for (let i = 0; i < b.length; i++) if (t >= b.start(i) - 0.1 && t <= b.end(i)) ahead = b.end(i) - t;
      set('Buffer', `${ahead.toFixed(1)} s ahead · ${b.length} range${b.length === 1 ? '' : 's'}`);

      // Segment: the last video segment hls.js loaded, and the last HTTP status of the stream.
      let seg;
      if (p.mode === 'direct') seg = 'n/a (direct play)';
      else if (p.nativeHls) seg = 'n/a (TV player)';
      else if (!this.seg) seg = this.lastStatus ? `none yet · last HTTP ${this.lastStatus}` : 'none yet';
      else {
        const s = this.seg;
        seg = `#${s.sn} · ${(s.bytes / 1048576).toFixed(2)} MB · ${Math.round(s.ms)} ms · ${Math.round((now - s.at) / 1000)} s ago · last HTTP ${this.lastStatus || '?'}`;
      }
      set('Segment', seg);

      // Bitrate: the converted stream and the network estimate, or the file.
      let rate;
      if (hlsjs) {
        const level = (hlsjs.levels || [])[hlsjs.currentLevel];
        rate = `stream ${mbps(level && level.bitrate)} · network ${mbps(hlsjs.bandwidthEstimate)}`;
      } else if (p.mode === 'direct') rate = p.media && p.media.bitrate ? `file ${(p.media.bitrate / 1000).toFixed(1)} Mbps` : 'n/a';
      else rate = 'n/a (TV player)';
      set('Bitrate', rate);

      const stalled = (this.stalledMs + (this.stallStart ? now - this.stallStart : 0)) / 1000;
      const lp = this.lastPing;
      const ping = !lp ? 'ping none yet' : `${lp.status ? `ping HTTP ${lp.status}` : 'ping failed'} at ${clock(lp.at)}`;
      set('Stalls', `${this.stalls} · ${stalled.toFixed(1)} s stalled · restarts ${this.restarts} · ${ping}`);

      set('Last error', this.lastError ? `${clock(this.lastError.at)} ${this.lastError.text}` : 'none');
    }
  }

  window.Sofa.PlayerStats = PlayerStats;
})();
