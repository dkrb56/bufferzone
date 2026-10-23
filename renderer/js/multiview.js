// Multi-view: up to four streams at once. One tile has audio; click another tile to move it.
// Each tile is its own Player (and its own stream on the server).
(function () {
  const S = window.Sofa;
  const { h, icon, btn, iconBtn, toast, fmtTime, epCode, Player } = S;
  const MAX = 4;
  const LAYOUTS = [['2x2', 'grid-2x2', 'Grid'], ['side', 'columns-2', 'Side by side'], ['1p3', 'panels-top-left', 'One large and three small']];

  class MultiView {
    constructor(app) {
      this.app = app;
      this.root = document.getElementById('multiview');
      this.tiles = [];
      this.audioIndex = 0;
      this.isOpen = false;
      document.addEventListener('keydown', (e) => {
        if (this.isOpen && e.key === 'Escape' && !this.app.palette.isOpen) { e.preventDefault(); this.close(); }
      });
    }

    get layout() { return this.app.settings.mvLayout || '2x2'; }

    open() {
      if (!this.app.multiviewAllowed) return toast('Multi-view is not allowed for this profile.');
      this.isOpen = true;
      this.root.classList.add('on');
      this.draw();
      clearInterval(this.timer);
      this.timer = setInterval(() => this.updateInfo(), 500);
    }

    close() {
      for (const t of this.tiles) t.player.exit();
      this.tiles = [];
      this.audioIndex = 0;
      clearInterval(this.timer);
      this.root.classList.remove('on');
      this.root.replaceChildren();
      this.isOpen = false;
      this.app.onPlayerExit();
    }

    async add(item) {
      if (!this.isOpen) this.open();
      if (this.tiles.length >= MAX) return toast('Multi-view shows up to four streams.');
      const playable = await this.app.resolvePlayable(item);
      if (!playable) return toast('Nothing to play.');
      const vp = h('div');
      const tile = { item: playable, vp, player: new Player(this.app, vp, { tile: true }) };
      this.tiles.push(tile);
      this.draw();
      tile.player.tileVolume = this.volumeFor(this.tiles.length - 1);
      tile.player.play(playable, { startAt: playable.viewOffset && !playable.viewCount ? playable.viewOffset / 1000 : 0 });
    }

    remove(i) {
      const [t] = this.tiles.splice(i, 1);
      t.player.exit();
      if (this.audioIndex >= this.tiles.length) this.audioIndex = 0;
      this.applyVolumes();
      this.draw();
    }

    volumeFor(i) { return i === this.audioIndex ? (this.app.settings.volume ?? 1) : 0; }

    applyVolumes() {
      this.tiles.forEach((t, i) => { t.player.tileVolume = this.volumeFor(i); t.player.video.volume = t.player.tileVolume; });
    }

    setAudio(i) {
      if (i === this.audioIndex) return;
      this.audioIndex = i;
      this.applyVolumes();
      this.draw();
    }

    titleOf(t) {
      const m = t.player.meta || t.item;
      return (m.type === 'episode' ? m.grandparentTitle : m.title) || 'stream';
    }

    draw() {
      // Moving a <video> to a new parent pauses it, so remember which tiles were playing.
      const playing = this.tiles.filter(t => t.player.meta && !t.player.video.paused);
      const audioTile = this.tiles[this.audioIndex];
      const info = this.tiles.length
        ? `${this.tiles.length} stream${this.tiles.length === 1 ? '' : 's'} · audio from ${this.titleOf(audioTile)}`
        : 'Add up to four streams';
      const grid = h(`div.mv-grid.l-${this.layout}`);
      this.infoEls = [];
      this.tiles.forEach((t, i) => {
        const time = h('span.t');
        const bar = h('div');
        this.infoEls.push({ t, time, bar });
        const el = h('div.tile' + (i === this.audioIndex ? '.audio' : ''), { onclick: () => this.setAudio(i) },
          t.vp,
          h('div.tile-badge', i === this.audioIndex ? S.pill('Audio', 'accent') : icon('volume-x', { size: 16 })),
          iconBtn('x', { cls: 'tile-close', title: 'Remove stream', onclick: (e) => { e.stopPropagation(); this.remove(i); } }),
          h('div.tile-info',
            h('div.tile-row', h('b', this.titleOf(t)), h('span.muted', t.item.type === 'episode' ? epCode(t.item) : t.item.year || ''), time),
            h('div.tile-bar', bar)));
        grid.append(el);
      });
      if (!this.tiles.length) {
        grid.append(h('div.mv-empty', icon('layout-grid', { size: 34 }), h('div', 'Add a stream to start. Each stream counts against the server\'s limit.'),
          btn('Add stream', { kind: 'primary', icon: 'plus', onclick: () => this.pick() })));
      }
      this.root.replaceChildren(
        h('div.mv-head',
          btn('Exit multi-view', { kind: 'ghost', icon: 'arrow-left', onclick: () => this.close() }),
          h('span.mv-title', 'Multi-view'),
          h('span.muted.small', info),
          h('div.spacer'),
          h('div.seg', LAYOUTS.map(([k, ic, label]) => iconBtn(ic, { title: label, cls: this.layout === k ? 'on' : '', onclick: () => { this.app.saveSettings({ mvLayout: k }); this.draw(); } }))),
          btn('Add stream', { icon: 'plus', disabled: this.tiles.length >= MAX, onclick: () => this.pick() })),
        grid);
      for (const t of playing) t.player.video.play().catch(() => {});
      this.updateInfo();
    }

    pick() {
      this.app.palette.open({ title: 'Add a stream to Multi-view', onPick: (item) => this.add(item) });
    }

    updateInfo() {
      for (const { t, time, bar } of this.infoEls || []) {
        const p = t.player;
        const cur = p.meta ? p.time() : 0;
        time.textContent = fmtTime(cur);
        bar.style.width = p.duration ? `${(cur / p.duration) * 100}%` : '0';
      }
    }
  }

  S.MultiView = MultiView;
})();
