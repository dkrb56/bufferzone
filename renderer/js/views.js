// Content views: home, library, collection, movie, series and watchlist.
// Each view runs with `this` = app; `alive()` turns false when the user navigates away.
(function () {
  const S = window.Sofa;
  const { h, fmtTime, fmtDuration, epCode, toast, debounce, icon, btn, iconBtn, pill, selectEl, switchEl, QUALITY } = S;
  const P = S.App.prototype;

  const SORTS = [
    ['addedAt:desc', 'Recently added'],
    ['titleSort', 'Title A–Z'],
    ['titleSort:desc', 'Title Z–A'],
    ['year:desc', 'Year (newest)'],
    ['year', 'Year (oldest)'],
    ['audienceRating:desc', 'Audience rating'],
    ['rating:desc', 'Critic rating'],
    ['lastViewedAt:desc', 'Recently watched'],
    ['originallyAvailableAt:desc', 'Release date']
  ];
  const DAY = 86400;

  // ---------- shared pieces ----------
  P.progressOf = function (item) {
    if (!item.viewOffset || !item.duration) return 0;
    return Math.min(1, item.viewOffset / item.duration);
  };

  P.art = function (path, w, hgt, title, extra = []) {
    return h('div.art',
      path ? h('img', { src: this.plex.image(path, w, hgt), loading: 'lazy', alt: '' }) : h('div.noimg', title || ''),
      ...extra);
  };

  P.searchField = function () {
    return h('div.search-field', { onclick: () => this.palette.open() }, icon('search', { size: 15 }), 'Search  ·  Ctrl F');
  };

  P.posterCard = function (item) {
    const thumb = item.type === 'episode' ? item.grandparentThumb || item.parentThumb || item.thumb : item.type === 'season' ? item.thumb || item.parentThumb : item.thumb;
    const watched = item.type === 'movie' ? item.viewCount > 0 : item.leafCount && item.viewedLeafCount >= item.leafCount;
    const unwatchedEps = item.type === 'show' ? (item.leafCount || 0) - (item.viewedLeafCount || 0) : 0;
    const prog = this.progressOf(item);
    const title = item.type === 'episode' ? item.grandparentTitle : item.type === 'season' ? item.parentTitle : item.title;
    const sub = item.newCount > 1 ? `${item.newCount} new episodes` : item.type === 'episode' ? `${epCode(item)} · ${item.title}` : item.type === 'season' ? item.title : item.type === 'collection' ? `${item.childCount || ''} titles` : item.year || '';
    return h('div.card.poster', { onclick: () => this.open(item), title },
      this.art(thumb, 300, 450, title, [
        watched ? h('span.badge.ok', icon('check', { size: 12, stroke: 3 })) : unwatchedEps > 0 && item.viewedLeafCount ? h('span.badge', String(unwatchedEps)) : null,
        prog ? h('div.prog', h('div', { style: { width: `${prog * 100}%` } })) : null
      ]),
      h('div.c-title', title),
      h('div.c-sub', sub));
  };

  P.toggleWatched = async function (item, isWatched = item.viewCount > 0) {
    try {
      if (isWatched) await this.plex.unscrobble(item.ratingKey); else await this.plex.scrobble(item.ratingKey);
      toast(isWatched ? 'Marked unwatched' : 'Marked watched');
      this.render(this.main.scrollTop);
    } catch (e) { toast('Failed: ' + e.message); }
  };

  function leftText(item) {
    if (!item.viewOffset || !item.duration) return '';
    return `${Math.max(1, Math.round((item.duration - item.viewOffset) / 60000))} min left`;
  }

  // ---------- home ----------
  S.views.home = async function (view, _args, alive) {
    const g = this.groups();
    const secs = this.visibleSections();
    const tabs = [{ id: 'all', label: 'All', secs }];
    if (g.movie.length) tabs.push({ id: 'movie', label: 'Movies', secs: g.movie });
    if (g.show.length) tabs.push({ id: 'show', label: 'Series', secs: g.show });
    if (g.kids.length) tabs.push({ id: 'kids', label: 'Kids', secs: g.kids });
    for (const s of secs) if (!/^(movies|tv shows)\b/i.test(s.title) && !g.kids.includes(s)) tabs.push({ id: 's' + s.key, label: s.title, secs: [s] });
    const tab = tabs.find(t => t.id === this.settings.homeTab) || tabs[0];
    const keys = new Set(tab.secs.map(s => String(s.key)));
    const inTab = (i) => tab.id === 'all' || keys.has(String(i.librarySectionID));

    const left = h('div.home-left', h('div.loading', 'Loading…'));
    const right = h('div.home-right');
    view.style.height = '100%';
    view.append(h('div.home',
      h('div.page-head',
        h('div.tabs', tabs.map(t => h('button.tab' + (t === tab ? '.active' : ''), { onclick: () => { this.saveSettings({ homeTab: t.id }); this.render(); } }, t.label))),
        h('div.spacer'), this.searchField()),
      h('div.home-body', left, right)));

    const [cw, deck, ...recent] = await Promise.all([
      this.plex.continueWatching().catch(() => []),
      this.plex.onDeck().catch(() => []),
      ...tab.secs.map(s => this.plex.recentlyAdded(s.key, 30).catch(() => []))
    ]);
    if (!alive()) return;

    // Left: continue watching + next up. The same title can be in two libraries (e.g. Movies and Movies - 4K).
    const seenGuid = new Set();
    const once = (i) => { const k = i.guid || i.ratingKey; return seenGuid.has(k) ? false : seenGuid.add(k); };
    const cont = this.allow(cw).filter(inTab).filter(once);
    const cwKeys = new Set(cont.map(i => i.ratingKey));
    const next = this.allow(deck).filter(i => inTab(i) && !i.viewOffset && !cwKeys.has(i.ratingKey)).filter(once);
    const rowsEls = [];
    const row = (item, isNext) => {
      const img = item.type === 'episode' ? item.thumb || item.grandparentArt : item.art || item.thumb;
      const prog = this.progressOf(item);
      const title = item.type === 'episode' ? item.grandparentTitle : item.title;
      const sub = item.type === 'episode' ? `${epCode(item)} · ${item.title}${isNext ? ' · new' : ''}` : [item.year, 'Movie'].filter(Boolean).join(' · ');
      const el = h('div.cw-row', { onclick: () => this.play(item) },
        this.art(img, 256, 144, title, [prog ? h('div.prog', h('div', { style: { width: `${prog * 100}%` } })) : null]),
        h('div.cw-text', h('div.cw-title', title), h('div.cw-sub', sub), h('div.cw-left', isNext ? (item.duration ? fmtDuration(item.duration) : '') : leftText(item))),
        iconBtn('play', { cls: 'cw-play', title: 'Play', size: 16 }));
      rowsEls.push({ el, item });
      return el;
    };
    left.replaceChildren(
      h('div.sec-head', h('h2', 'Continue watching'), h('div.spacer'), cont.length ? h('span.muted', 'Space to play') : null),
      ...(cont.length ? cont.map(i => row(i, false)) : [h('p.muted.small', 'Nothing in progress.')]),
      next.length ? h('div.divider') : null,
      next.length ? h('div.eyebrow', { style: { margin: '0 8px 6px' } }, 'Next up') : null,
      ...next.slice(0, 12).map(i => row(i, true)));

    let sel = 0;
    const mark = () => rowsEls.forEach((r, i) => r.el.classList.toggle('sel', i === sel));
    if (rowsEls.length) mark();
    this.homeKeys = (e) => {
      if (!rowsEls.length) return;
      if (e.key === 'ArrowDown') { sel = Math.min(rowsEls.length - 1, sel + 1); mark(); rowsEls[sel].el.scrollIntoView({ block: 'nearest' }); e.preventDefault(); }
      else if (e.key === 'ArrowUp') { sel = Math.max(0, sel - 1); mark(); rowsEls[sel].el.scrollIntoView({ block: 'nearest' }); e.preventDefault(); }
      else if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); this.play(rowsEls[sel].item); }
    };

    // Right: recently added, newest first, last 14 days when there is enough
    let all = this.allow(recent.flat()).sort((a, b) => (b.addedAt || 0) - (a.addedAt || 0));
    // One card per series: new episodes of the same series collapse into the newest one.
    const seen = new Map();
    all = all.filter(i => {
      const k = i.type === 'episode' ? 'show:' + i.grandparentRatingKey : i.type === 'season' ? 'show:' + i.parentRatingKey : i.guid || i.ratingKey;
      if (seen.has(k)) { seen.get(k).newCount = (seen.get(k).newCount || 1) + 1; return false; }
      seen.set(k, i);
      return true;
    });
    const cutoff = Date.now() / 1000 - 14 * DAY;
    const fresh = all.filter(i => i.addedAt >= cutoff);
    const list = (fresh.length >= 12 ? fresh : all).slice(0, 36);
    const scope = tab.id === 'all' ? 'All libraries' : tab.label;
    right.replaceChildren(
      h('div.sec-head', h('h2', 'Recently added'), h('span.muted', `${scope} · ${fresh.length >= 12 ? 'last 14 days' : 'newest first'}`)),
      list.length ? h('div.grid', list.map(i => this.posterCard(i))) : h('p.muted', 'Nothing here yet.'));
  };

  // ---------- library ----------
  S.views.library = async function (view, { id }, alive) {
    const sec = this.sections.find(s => s.key === id);
    if (!sec) return view.append(h('div.empty', 'This library is not available.'));
    const g = this.groups();
    const groupKey = g.kids.includes(sec) ? 'kids' : sec.type;
    const siblings = g[groupKey] || [sec];
    this.saveSettings({ lastLibrary: { ...(this.settings.lastLibrary || {}), [groupKey]: id } });

    const f = { sort: 'addedAt:desc', genre: '', unwatched: false, view: 'grid', ...(this.settings.libraryFilters?.[id] || {}) };
    const save = () => this.saveSettings({ libraryFilters: { ...(this.settings.libraryFilters || {}), [id]: f } });
    const count = h('span.muted');
    const content = h('div');
    const letters = h('div.letters');
    const genreWrap = h('span');
    const noun = sec.type === 'show' ? 'series' : 'movies';

    const setView = (v) => { f.view = v; save(); this.render(); };
    view.append(
      h('div.page-head',
        h('h1', sec.title), count,
        siblings.length > 1 ? h('div.tabs', { style: { marginLeft: '12px' } }, siblings.map(s => h('button.tab' + (s === sec ? '.active' : ''), { onclick: () => this.go('library', { id: s.key }, { replace: true }) }, s.title))) : null,
        h('div.spacer'), this.searchField()),
      h('div.toolbar',
        f.view === 'collections' ? null : selectEl(SORTS, f.sort, (v) => { f.sort = v; save(); reload(0); }),
        f.view === 'collections' ? null : genreWrap,
        f.view === 'collections' ? null : switchEl(f.unwatched, (on) => { f.unwatched = on; save(); reload(0); }),
        f.view === 'collections' ? null : h('span', { style: { fontSize: '13px' } }, 'Unwatched only'),
        h('div.spacer'),
        h('div.seg',
          iconBtn('layout-grid', { title: 'Grid', cls: f.view === 'grid' ? 'on' : '', onclick: () => setView('grid') }),
          iconBtn('list', { title: 'List', cls: f.view === 'list' ? 'on' : '', onclick: () => setView('list') }),
          iconBtn('folder', { title: 'Collections', cls: f.view === 'collections' ? 'on' : '', onclick: () => setView('collections') }))),
      h('div.lib-body', content, letters));

    if (f.view === 'collections') {
      content.append(h('div.loading', 'Loading…'));
      const cols = await this.plex.collections(id);
      if (!alive()) return;
      count.textContent = `${cols.length} collections`;
      content.replaceChildren(cols.length ? h('div.grid', cols.map(c => this.posterCard(c))) : h('div.empty', 'No collections.'));
      return;
    }

    this.plex.genres(id).then(gs => {
      if (!alive()) return;
      genreWrap.replaceChildren(selectEl([['', 'All genres'], ...gs.map(x => [x.key, x.title])], f.genre, (v) => { f.genre = v; save(); reload(0); }));
    }).catch(() => {});

    const container = f.view === 'list' ? h('div.list-rows') : h('div.grid');
    const sentinel = h('div.sentinel');
    content.append(container, sentinel);
    const listRow = (i) => h('div.list-row', { onclick: () => this.open(i) },
      this.art(i.thumb, 72, 108, ''),
      h('div', { style: { minWidth: 0 } }, h('div.cw-title', i.title), h('div.c-sub', (i.Genre || []).map(x => x.tag).slice(0, 3).join(', '))),
      h('span.muted.small', i.year || ''),
      h('span.muted.small', i.type === 'show' ? `${i.leafCount || 0} episodes` : i.duration ? fmtDuration(i.duration) : ''),
      h('span.muted.small', i.audienceRating ? `★ ${i.audienceRating.toFixed(1)}` : ''),
      (i.type === 'movie' ? i.viewCount : i.leafCount && i.viewedLeafCount >= i.leafCount) ? h('span', { style: { color: 'var(--ok)' } }, icon('check', { size: 16 })) : h('span'));

    let start = 0, total = Infinity, busy = false, gen = 0;
    const more = async () => {
      if (busy || start >= total) return;
      busy = true;
      const myGen = gen;
      try {
        const r = await this.plex.sectionItems(id, { sort: f.sort, genre: f.genre || undefined, unwatched: f.unwatched, start, size: 60 });
        if (!alive() || myGen !== gen) return;
        total = r.total;
        start += r.items.length;
        if (!r.items.length) total = start;
        count.textContent = `${total} ${noun}`;
        const items = this.allow(r.items);
        container.append(...items.map(i => (f.view === 'list' ? listRow(i) : this.posterCard(i))));
        if (!container.children.length && start >= total) container.append(h('div.empty', 'No matches.'));
      } finally { busy = false; }
      if (alive() && sentinel.getBoundingClientRect().top < window.innerHeight * 2) more();
    };
    const reload = (from) => { gen++; start = from; total = Infinity; busy = false; container.replaceChildren(); more(); };
    new IntersectionObserver((es) => { if (es[0].isIntersecting) more(); }, { root: this.main, rootMargin: '800px' }).observe(sentinel);

    // Letter rail: jumps in the title-sorted list.
    this.plex.firstCharacter(id).then(chars => {
      if (!alive()) return;
      let offset = 0;
      const map = chars.map(c => { const o = { key: c.title, start: offset, size: c.size }; offset += c.size; return o; });
      const btns = '#ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('').map(L => {
        const entry = map.find(m => m.key.toUpperCase() === L);
        const b = h('button', {
          disabled: !entry, title: entry ? `${entry.size}` : '',
          onclick: () => {
            if (f.sort !== 'titleSort') { f.sort = 'titleSort'; save(); view.querySelector('.toolbar select').value = 'titleSort'; }
            // Letter offsets are for the full list; filters change them, so the jump is only exact without filters.
            btns.forEach(x => x.classList.toggle('on', x === b));
            reload(entry.start);
            this.main.scrollTop = 0;
          }
        }, L);
        return b;
      });
      letters.replaceChildren(...btns);
    }).catch(() => {});

    await more();
  };

  // ---------- collection ----------
  S.views.collection = async function (view, { key }, alive) {
    const [meta, items] = await Promise.all([this.plex.metadata(key), this.plex.collectionItems(key)]);
    if (!alive()) return;
    const pinned = this.isPinned(meta);
    view.append(
      h('div.page-head', this.backButton(), h('h1', meta.title), h('span.muted', `${items.length} titles`), h('div.spacer'),
        btn(pinned ? 'Unpin' : 'Pin to sidebar', { icon: 'pin', onclick: () => { this.togglePin(meta); this.render(); } })),
      h('div.pad', h('div.grid', this.allow(items).map(i => this.posterCard(i)))));
  };

  // ---------- movie ----------
  S.views.movie = async function (view, { key }, alive) {
    const m = await this.plex.metadata(key);
    if (!alive()) return;
    if (!this.itemAllowed(m)) return view.append(h('div.empty', 'This title is not available for this profile.'));
    const sec = this.sections.find(s => String(s.key) === String(m.librarySectionID));
    const streams = m.Media?.[0]?.Part?.[0]?.Stream || [];
    const audios = streams.filter(s => s.streamType === 2);
    const subs = streams.filter(s => s.streamType === 3);
    const preset = { audio: 'auto', sub: 'auto', quality: this.settings.quality };
    const opts = () => ({
      audioId: preset.audio === 'auto' ? undefined : Number(preset.audio),
      subId: preset.sub === 'auto' ? undefined : preset.sub === 'off' ? null : Number(preset.sub),
      quality: preset.quality
    });
    const facts = [m.year, m.duration && fmtDuration(m.duration), m.contentRating, m.audienceRating ? `★ ${m.audienceRating.toFixed(1)}` : null].filter(Boolean).join(' · ');
    const watched = m.viewCount > 0;
    const left = h('div.movie-left',
      this.history.length ? h('div', btn(sec?.title || 'Back', { kind: 'ghost', icon: 'arrow-left', onclick: () => this.back() })) : null,
      h('div.art.poster', m.thumb ? h('img', { src: this.plex.image(m.thumb, 400, 600), alt: '' }) : h('div.noimg', m.title)),
      h('h1', m.title),
      h('div.facts', facts),
      m.viewOffset
        ? btn(`Resume from ${fmtTime(m.viewOffset / 1000)}`, { kind: 'primary', size: 'lg', full: true, icon: 'play', onclick: () => this.play(m, opts()) })
        : btn('Play', { kind: 'primary', size: 'lg', full: true, icon: 'play', onclick: () => this.play(m, opts()) }),
      m.viewOffset ? btn('Play from start', { size: 'lg', full: true, icon: 'rotate-ccw', onclick: () => this.play(m, { ...opts(), resume: false }) }) : null,
      h('div.btn-row',
        iconBtn(this.inWatchlist(m) ? 'bookmark-check' : 'bookmark', { cls: 'boxed' + (this.inWatchlist(m) ? ' on' : ''), title: this.inWatchlist(m) ? 'Remove from watchlist' : 'Add to watchlist', onclick: async () => { await this.toggleWatchlist(m); this.render(); } }),
        iconBtn('check', { cls: 'boxed' + (watched ? ' on' : ''), title: watched ? 'Mark unwatched' : 'Mark watched', onclick: () => this.toggleWatched(m) }),
        iconBtn('pin', { cls: 'boxed' + (this.isPinned(m) ? ' on' : ''), title: this.isPinned(m) ? 'Unpin' : 'Pin to sidebar', onclick: () => { this.togglePin(m); this.render(); } }),
        this.multiviewAllowed ? iconBtn('layout-grid', { cls: 'boxed', title: 'Add to Multi-view', onclick: () => this.multiview.add(m) }) : null));

    const people = (arr) => (arr || []).map(x => x.tag).join(', ');
    const label = (t, el) => h('label', t, el);
    const media = m.Media?.[0];
    const right = h('div.movie-right',
      this.art(m.art, 1200, 525, '', []),
      m.summary ? h('p.summary', { style: { marginTop: '18px' } }, m.summary) : null,
      m.Director ? h('div.credit', h('b', 'Director '), people(m.Director)) : null,
      m.Role ? h('div.credit', h('b', 'Cast '), people(m.Role.slice(0, 8))) : null,
      h('div.presets',
        label('Audio', selectEl([['auto', 'Automatic'], ...audios.map(a => [a.id, a.displayTitle || a.language])], 'auto', (v) => { preset.audio = v; })),
        label('Subtitles', selectEl([['auto', 'Automatic'], ['off', 'Off'], ...subs.map(s => [s.id, s.displayTitle || s.language])], 'auto', (v) => { preset.sub = v; })),
        label('Quality', selectEl(QUALITY.map(q => [q.id, q.label]), preset.quality, (v) => { preset.quality = v; }))),
      media ? h('div.tech',
        h('span.k', 'Video'), h('span', [media.videoResolution && `${media.videoResolution}${/^\d+$/.test(media.videoResolution) ? 'p' : ''}`, String(media.videoCodec || '').toUpperCase(), String(media.container || '').toUpperCase(), media.bitrate && `${(media.bitrate / 1000).toFixed(1)} Mbps`].filter(Boolean).join(' · ')),
        h('span.k', 'Audio'), h('span', audios.map(a => a.displayTitle).join(' · ') || '—'),
        h('span.k', 'Subtitles'), h('span', subs.map(s => s.displayTitle).join(' · ') || '—')) : null);
    right.firstChild.classList.add('backdrop');
    view.append(h('div.movie', left, right));
  };

  // ---------- series ----------
  S.views.show = async function (view, { key, season, focus }, alive) {
    const [show, seasons, leaves] = await Promise.all([this.plex.metadata(key), this.plex.children(key), this.plex.allLeaves(key)]);
    if (!alive()) return;
    if (!this.itemAllowed(show)) return view.append(h('div.empty', 'This series is not available for this profile.'));
    const upNext = leaves.find(e => e.viewOffset && !e.viewCount) || leaves.find(e => !e.viewCount) || leaves[0];
    const allWatched = leaves.length && leaves.every(e => e.viewCount);
    const facts = [show.year, show.childCount ? `${show.childCount} season${show.childCount === 1 ? '' : 's'}` : null, show.contentRating,
      show.audienceRating ? `★ ${show.audienceRating.toFixed(1)}` : null, (show.Genre || []).slice(0, 2).map(x => x.tag).join(', ')].filter(Boolean).join(' · ');
    const inWl = this.inWatchlist(show);
    view.append(h('div.hero',
      show.art ? h('div.hero-bg', { style: { backgroundImage: `url("${this.plex.image(show.art, 1600, 900)}")` } }) : null,
      h('div', this.backButton()),
      h('div.hero-body',
        show.thumb ? h('img.hero-poster', { src: this.plex.image(show.thumb, 352, 528), alt: '' }) : null,
        h('div.hero-info',
          h('h1', show.title),
          h('div.facts', facts),
          show.summary ? h('p.summary', show.summary) : null,
          h('div.btn-row',
            upNext ? btn(`${upNext.viewOffset && !upNext.viewCount ? 'Resume' : 'Play'} ${epCode(upNext)}`, { kind: 'primary', size: 'lg', icon: 'play', onclick: () => this.play(upNext) }) : null,
            btn(allWatched ? 'Mark series unwatched' : 'Mark series watched', { size: 'lg', icon: 'check', onclick: () => this.toggleWatched(show, allWatched) }),
            iconBtn(inWl ? 'bookmark-check' : 'bookmark', { cls: 'boxed' + (inWl ? ' on' : ''), title: inWl ? 'Remove from watchlist' : 'Add to watchlist', onclick: async () => { await this.toggleWatchlist(show); this.render(); } }),
            iconBtn('pin', { cls: 'boxed' + (this.isPinned(show) ? ' on' : ''), title: this.isPinned(show) ? 'Unpin' : 'Pin to sidebar', onclick: () => { this.togglePin(show); this.render(); } }))))));

    const tabs = h('div.tabs');
    const bar = h('div.season-bar');
    const list = h('div.episodes');
    const showSeason = (sk) => {
      for (const t of tabs.children) t.classList.toggle('active', t.dataset.key === String(sk));
      const s = seasons.find(x => x.ratingKey === String(sk));
      const eps = leaves.filter(e => e.parentRatingKey === String(sk));
      const watchedN = eps.filter(e => e.viewCount).length;
      const seasonWatched = eps.length && watchedN === eps.length;
      bar.replaceChildren(tabs, h('div.spacer'), h('span.muted', `${eps.length} episodes · ${watchedN} watched`),
        s ? btn(seasonWatched ? 'Mark season unwatched' : 'Mark season watched', { kind: 'ghost', size: 'sm', onclick: () => this.toggleWatched(s, seasonWatched) }) : null);
      list.replaceChildren(...eps.map(e => this.episodeRow(e, e.ratingKey === focus)));
      this.current.args.season = sk;
      const f = list.querySelector('.focus');
      if (f) requestAnimationFrame(() => f.scrollIntoView({ block: 'center' }));
    };
    tabs.append(...seasons.map(s => {
      const done = s.leafCount && s.viewedLeafCount >= s.leafCount;
      const t = h('button.tab', { onclick: () => showSeason(s.ratingKey) }, s.title, done ? h('span.tick', icon('check', { size: 12, stroke: 3 })) : null);
      t.dataset.key = s.ratingKey;
      return t;
    }));
    view.append(h('div', { style: { padding: '0 28px 60px' } }, bar, list));
    const cur = season || upNext?.parentRatingKey || seasons[0]?.ratingKey;
    if (cur) showSeason(cur);
  };

  P.episodeRow = function (e, focus) {
    const prog = this.progressOf(e);
    const watched = e.viewCount > 0;
    const inProgress = prog && !watched;
    return h('div.ep' + (inProgress ? '.current' : '') + (focus ? '.focus' : ''),
      h('div.art', { onclick: () => this.play(e) },
        e.thumb ? h('img', { src: this.plex.image(e.thumb, 384, 216), loading: 'lazy', alt: '' }) : null,
        h('div.play-ov', icon('play', { size: 30 })),
        prog ? h('div.prog', h('div', { style: { width: `${prog * 100}%` } })) : null),
      h('div.ep-body',
        h('div.ep-title', h('span.ep-code', epCode(e)), e.title),
        h('div.muted.small', [e.duration && fmtDuration(e.duration), e.originallyAvailableAt, inProgress ? `${fmtTime(e.viewOffset / 1000)} watched` : null].filter(Boolean).join(' · ')),
        e.summary ? h('p.ep-sum', e.summary) : null),
      h('div.ep-actions',
        inProgress ? btn('From start', { size: 'sm', onclick: () => this.play(e, { resume: false }) }) : null,
        h('button.watch' + (watched ? '.on' : ''), { title: watched ? 'Mark unwatched' : 'Mark watched', onclick: () => this.toggleWatched(e) }, watched ? icon('check', { size: 16, stroke: 3 }) : null)));
  };

  // ---------- watchlist ----------
  async function matchAll(app, items) {
    app.wlMatch = app.wlMatch || new Map();
    const todo = items.filter(w => !app.wlMatch.has(w.guid));
    let i = 0;
    const worker = async () => {
      while (i < todo.length) {
        const w = todo[i++];
        if (!w.watchlistedAt) w.watchlistedAt = (await app.plex.watchlistState(w.ratingKey).catch(() => ({}))).watchlistedAt;
        const found = await app.plex.findByGuid(w.guid).catch(() => []);
        const visible = found.filter(x => !app.settings.hiddenLibraries.includes(String(x.librarySectionID)));
        app.wlMatch.set(w.guid, visible[0] || found[0] || null);
      }
    };
    await Promise.all([1, 2, 3, 4, 5, 6].map(worker));
  }

  function wlStatus(s) {
    if (!s) return pill('Not on server', '', { sm: true });
    if (s.type === 'show') {
      if (s.leafCount && s.viewedLeafCount >= s.leafCount) return pill('Watched', 'ok', { sm: true });
      if (s.viewedLeafCount > 0) return pill('New episodes', 'accent', { sm: true });
      return pill('Unwatched', '', { sm: true });
    }
    if (s.viewCount > 0) return pill('Watched', 'ok', { sm: true });
    if (s.viewOffset > 0) return pill('In progress', 'info', { sm: true });
    return pill('Unwatched', '', { sm: true });
  }

  S.views.watchlist = async function (view, _args, alive) {
    const f = { type: 'all', sort: 'added', ...(this.settings.watchlistView || {}) };
    const save = () => this.saveSettings({ watchlistView: f });
    const count = h('span.muted');
    const body = h('div', h('div.loading', `Matching titles on ${this.plex.server.name}…`));
    view.append(h('div.page-head', h('h1', 'Watchlist'), count, h('div.spacer'), this.searchField()), h('div.pad', body));
    const items = this.watchlist || await this.loadWatchlist();
    await matchAll(this, items);
    if (!alive()) return;

    const draw = () => {
      let rows = items.map(w => ({ w, s: this.wlMatch.get(w.guid) })).filter(r => !r.s || this.itemAllowed(r.s));
      if (f.type !== 'all') rows = rows.filter(r => r.w.type === f.type);
      if (f.sort === 'added') rows.sort((a, b) => (b.w.watchlistedAt || 0) - (a.w.watchlistedAt || 0));
      if (f.sort === 'title') rows.sort((a, b) => a.w.title.localeCompare(b.w.title));
      if (f.sort === 'length') rows.sort((a, b) => ((b.s?.duration || b.w.duration || 0) - (a.s?.duration || a.w.duration || 0)));
      count.textContent = `${rows.length} titles`;
      const added = (w) => w.watchlistedAt ? new Date(w.watchlistedAt * 1000).toISOString().slice(0, 10) : '';
      body.replaceChildren(
        h('div.wl-tools',
          h('div.tabs', [['all', 'All'], ['movie', 'Movies'], ['show', 'Series']].map(([k, l]) => h('button.tab' + (f.type === k ? '.active' : ''), { onclick: () => { f.type = k; save(); draw(); } }, l))),
          h('div', { style: { width: '170px' } }, selectEl([['added', 'Date added'], ['title', 'Title A–Z'], ['length', 'Length']], f.sort, (v) => { f.sort = v; save(); draw(); }))),
        h('div.table',
          h('div.t-row.head', h('div', 'Title'), h('div', 'Library'), h('div', 'Added'), h('div', 'Status'), h('div', 'Length'), h('div')),
          rows.length ? rows.map(({ w, s }) => h('div.t-row',
            h('div.t-title', { onclick: () => (s ? this.open(s) : toast('This title is not on this server.')) },
              h('div.art', s?.thumb ? h('img', { src: this.plex.image(s.thumb, 72, 108), loading: 'lazy', alt: '' }) : w.thumb ? h('img', { src: w.thumb, loading: 'lazy', alt: '' }) : null),
              h('div', { style: { minWidth: 0 } }, h('b', w.title), h('span.muted.small', [w.year, s?.type === 'show' && s.childCount ? `${s.childCount} season${s.childCount === 1 ? '' : 's'}` : null].filter(Boolean).join(' · ')))),
            h('div.muted', s?.librarySectionTitle || '—'),
            h('div.muted.tnum', added(w)),
            h('div', wlStatus(s)),
            h('div.muted', s ? (s.type === 'show' ? `${s.leafCount || 0} eps` : s.duration ? fmtDuration(s.duration) : '') : ''),
            h('div.t-actions',
              s ? iconBtn('play', { title: 'Play', size: 16, onclick: () => this.play(s) }) : null,
              iconBtn('x', { title: 'Remove from watchlist', size: 16, onclick: async () => {
                try {
                  await this.plex.removeFromWatchlist(w.guid);
                  this.watchGuids.delete(w.guid);
                  items.splice(items.indexOf(w), 1);
                  toast('Removed from watchlist');
                  draw();
                } catch (e) { toast('Failed: ' + e.message); }
              } })))) : h('div.empty', 'Your watchlist is empty.')));
    };
    draw();
  };

  S.SORTS = SORTS;
})();
