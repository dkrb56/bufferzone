// Search palette (Ctrl+F or /). Also used by Multi-view to pick a stream.
(function () {
  const S = window.Sofa;
  const { h, icon, debounce, epCode, fmtDuration, highlight } = S;

  const TABS = [['all', 'All'], ['show', 'Series'], ['movie', 'Movies'], ['episode', 'Episodes']];

  class Palette {
    constructor(app) {
      this.app = app;
      this.root = document.getElementById('palette');
      this.isOpen = false;
      this.root.addEventListener('mousedown', (e) => { if (e.target === this.root) this.close(); });
    }

    // onPick(item, { play }) replaces the default action (open, or play with Ctrl+Enter).
    open({ onPick, title } = {}) {
      this.onPick = onPick || null;
      this.tab = 'all';
      this.query = '';
      this.results = null;
      this.active = 0;
      this.input = h('input', { placeholder: title || 'Search movies, series and episodes', spellcheck: false });
      this.input.addEventListener('input', debounce(() => this.search(), 250));
      this.input.addEventListener('keydown', (e) => this.onKey(e));
      this.tabsEl = h('div.tabs');
      this.resEl = h('div.pal-res');
      this.root.replaceChildren(h('div.pal',
        h('div.pal-input', icon('search', { size: 20 }), this.input, h('span.kbd', 'Esc')),
        h('div.pal-tabs', this.tabsEl),
        this.resEl,
        h('div.pal-foot', h('span', '↑ ↓ move'), h('span', 'Enter open'), this.onPick ? null : h('span', 'Ctrl Enter play'), h('span', 'Esc close'))));
      this.root.classList.add('on');
      this.isOpen = true;
      this.draw();
      this.input.focus();
    }

    close() {
      this.root.classList.remove('on');
      this.root.replaceChildren();
      this.isOpen = false;
    }

    async search() {
      const q = this.input.value.trim();
      this.query = q;
      if (q.length < 2) { this.results = null; return this.draw(); }
      const r = await this.app.plex.search(q).catch(() => ({ movie: [], show: [], episode: [] }));
      if (q !== this.query || !this.isOpen) return;
      for (const k of Object.keys(r)) r[k] = this.app.allow(r[k]);
      this.results = r;
      this.active = 0;
      this.draw();
    }

    // Groups in display order; each row is { item, el }.
    groups() {
      const r = this.results;
      if (!r) return [];
      const q = this.query.toLowerCase();
      const want = (t) => this.tab === 'all' || this.tab === t;
      const out = [];
      let top = null;
      if (this.tab === 'all') {
        top = [...r.show, ...r.movie].find(i => i.title.toLowerCase().startsWith(q)) || r.show[0] || r.movie[0] || null;
        if (top) out.push(['Top result', [top]]);
      }
      const rest = (list) => list.filter(i => i !== top);
      if (want('show') && rest(r.show).length) out.push(['Series', rest(r.show)]);
      if (want('episode') && r.episode.length) out.push(['Episodes', r.episode]);
      if (want('movie') && rest(r.movie).length) out.push(['Movies', rest(r.movie)]);
      return out;
    }

    draw() {
      this.tabsEl.replaceChildren(...TABS.map(([k, l]) => h('button.tab' + (this.tab === k ? '.active' : ''), { onclick: () => { this.tab = k; this.active = 0; this.draw(); this.input.focus(); } }, l)));
      this.rows = [];
      if (!this.results) {
        this.resEl.replaceChildren(h('div.loading', this.query.length ? 'Type at least 2 characters.' : 'Search for a title.'));
        return;
      }
      const els = [];
      for (const [head, list] of this.groups()) {
        els.push(h('div.pal-head.eyebrow', head));
        for (const item of list) {
          const idx = this.rows.length;
          const el = this.row(item, idx);
          this.rows.push({ item, el });
          els.push(el);
        }
      }
      this.resEl.replaceChildren(...(els.length ? els : [h('div.loading', 'No results.')]));
      this.mark();
    }

    row(item, idx) {
      const p = this.app.plex;
      const q = this.query;
      let art, title, detail;
      if (item.type === 'episode') {
        art = h('div.art.still', item.thumb ? h('img', { src: p.image(item.thumb, 144, 81), alt: '' }) : null);
        title = [...highlight(item.grandparentTitle, q), ' · ', epCode(item)];
        detail = [...highlight(item.title, q), item.duration ? ` · ${fmtDuration(item.duration)}` : ''];
      } else {
        art = h('div.art', item.thumb ? h('img', { src: p.image(item.thumb, 80, 120), alt: '' }) : null);
        title = highlight(item.title, q);
        detail = [item.type === 'show' ? 'Series' : 'Movie', item.year,
          item.type === 'show' && item.childCount ? `${item.childCount} season${item.childCount === 1 ? '' : 's'}` : null,
          item.type === 'movie' && item.duration ? fmtDuration(item.duration) : null,
          item.type === 'movie' && item.viewCount ? 'watched' : null,
          item.librarySectionTitle].filter(Boolean).join(' · ');
      }
      return h('div.pal-row', { onmousemove: () => { if (this.active !== idx) { this.active = idx; this.mark(); } }, onclick: (e) => this.choose(idx, e.ctrlKey) },
        art, h('div', { style: { minWidth: 0 } }, h('div.t', title), h('div.d', detail)), h('span.kbd', 'Enter'));
    }

    mark() {
      this.rows.forEach((r, i) => r.el.classList.toggle('active', i === this.active));
      this.rows[this.active]?.el.scrollIntoView({ block: 'nearest' });
    }

    choose(idx, play) {
      const r = this.rows[idx];
      if (!r) return;
      const pick = this.onPick;
      this.close();
      if (pick) return pick(r.item);
      if (play) this.app.play(r.item);
      else this.app.open(r.item);
    }

    onKey(e) {
      if (e.key === 'Escape') { e.preventDefault(); this.close(); }
      else if (e.key === 'ArrowDown') { e.preventDefault(); this.active = Math.min(this.rows.length - 1, this.active + 1); this.mark(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); this.active = Math.max(0, this.active - 1); this.mark(); }
      else if (e.key === 'Enter') { e.preventDefault(); this.choose(this.active, e.ctrlKey); }
    }
  }

  S.Palette = Palette;
})();
