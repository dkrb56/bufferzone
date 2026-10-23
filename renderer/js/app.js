// App shell: sign-in, server choice, navigation and all non-player views.
(function () {
  const { Plex, Player, h, fmtTime, fmtDuration, epCode, toast, debounce, LANGUAGES, QUALITY } = window.Sofa;

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

  class App {
    constructor() {
      this.main = document.getElementById('main');
      this.nav = document.getElementById('nav');
      this.history = [];
    }

    async init() {
      this.settings = await sofa.getSettings();
      this.plex = new Plex(this.settings.clientId);
      this.player = new Player(this);
      document.addEventListener('keydown', (e) => {
        if (document.body.classList.contains('playing')) return;
        if ((e.ctrlKey && e.key.toLowerCase() === 'f') || (e.key === '/' && e.target.tagName !== 'INPUT')) {
          e.preventDefault();
          document.getElementById('search')?.focus();
        }
        if (e.key === 'Backspace' && e.target.tagName !== 'INPUT' && e.target.tagName !== 'SELECT') this.back();
      });
      const token = await sofa.getToken();
      if (!token) return this.showLogin();
      this.plex.token = token;
      try {
        this.user = await this.plex.user();
      } catch (e) {
        if (e.status === 401) { await sofa.setToken(null); return this.showLogin(); }
        return this.showMessage('Cannot reach plex.tv', 'Check your internet connection.', () => this.init());
      }
      await this.chooseServer(false);
    }

    saveSettings(patch) {
      Object.assign(this.settings, patch);
      sofa.setSettings(patch);
    }

    // ---------- full-screen states ----------
    bare(...children) {
      this.nav.replaceChildren();
      document.body.classList.add('bare');
      this.main.replaceChildren(h('div.center', ...children));
    }

    showMessage(title, msg, retry) {
      this.bare(h('div.panel', h('h2', title), h('p.muted', msg), retry ? h('button.btn.primary', { onclick: retry }, 'Try again') : null));
    }

    showLogin() {
      const status = h('p.muted', 'You sign in on the Plex website. This app never sees your password.');
      const btn = h('button.btn.primary.lg', { onclick: () => this.login(btn, status) }, 'Sign in with Plex');
      this.bare(h('div.panel', h('div.logo.big', 'Buffer Zone'), h('p', 'A clean player for your friend\'s Plex server.'), btn, status));
    }

    async login(btn, status) {
      btn.disabled = true;
      try {
        const pin = await this.plex.createPin();
        sofa.openExternal(this.plex.authUrl(pin.code));
        status.textContent = 'Your browser opened. Sign in there, then come back here.';
        const until = Date.now() + 10 * 60 * 1000;
        while (Date.now() < until) {
          await new Promise(r => setTimeout(r, 2000));
          const token = await this.plex.checkPin(pin.id).catch(() => null);
          if (token) {
            await sofa.setToken(token);
            this.plex.token = token;
            this.user = await this.plex.user();
            return this.chooseServer(true);
          }
        }
        status.textContent = 'Sign-in timed out. Try again.';
      } catch (e) {
        status.textContent = 'Sign-in failed: ' + e.message;
      }
      btn.disabled = false;
    }

    async chooseServer(force) {
      this.bare(h('div.spinner.on.static'), h('p.muted', 'Looking for servers…'));
      let servers;
      try { servers = await this.plex.resources(); }
      catch (e) { return this.showMessage('Cannot load servers', e.message, () => this.chooseServer(force)); }
      if (!servers.length) return this.showMessage('No servers', 'Your account has no access to a Plex server. Ask your friend to share their library with you.', () => this.chooseServer(force));
      // Friends' servers first.
      servers.sort((a, b) => (a.owned === b.owned ? a.name.localeCompare(b.name) : a.owned ? 1 : -1));
      const saved = servers.find(s => s.clientIdentifier === this.settings.serverId);
      if (saved && !force) return this.connect(saved);
      if (servers.length === 1) return this.connect(servers[0]);
      this.bare(h('div.panel',
        h('h2', 'Choose a server'),
        h('div.server-list', servers.map(s => h('button.server', { onclick: () => this.connect(s) },
          h('span.s-name', s.name),
          h('span.muted', s.owned ? 'Your server' : `Shared by ${s.sourceTitle || 'a friend'}`),
          s.presence === false ? h('span.badge.off', 'offline') : null
        )))
      ));
    }

    async connect(resource) {
      this.bare(h('div.spinner.on.static'), h('p.muted', `Connecting to ${resource.name}…`));
      try {
        await this.plex.connect(resource);
      } catch (e) {
        return this.showMessage('Cannot connect', e.message, () => this.chooseServer(true));
      }
      this.saveSettings({ serverId: resource.clientIdentifier });
      try { this.sections = await this.plex.sections(); }
      catch (e) { return this.showMessage('Cannot load libraries', e.message, () => this.connect(resource)); }
      document.body.classList.remove('bare');
      this.buildNav();
      this.history = [];
      this.go('home');
    }

    // ---------- navigation ----------
    visibleSections() {
      return this.sections.filter(s => !this.settings.hiddenLibraries.includes(s.key));
    }

    buildNav() {
      const search = h('input#search', {
        type: 'search', placeholder: 'Search movies and series…  (Ctrl+F)',
        oninput: debounce(() => {
          const q = search.value.trim();
          if (q.length >= 2) this.go('search', { q }, { replace: this.current?.name === 'search' });
          else if (!q && this.current?.name === 'search') this.back();
        }, 250),
        onkeydown: (e) => { if (e.key === 'Escape') { search.value = ''; search.blur(); if (this.current?.name === 'search') this.back(); } }
      });
      this.navLinks = {};
      const link = (label, name, args) => {
        const el = h('button.nav-link', { onclick: () => this.go(name, args, { root: true }) }, label);
        this.navLinks[name + (args?.id || '')] = el;
        return el;
      };
      this.backBtn = h('button.nav-back', { title: 'Back (Backspace)', onclick: () => this.back() }, '←');
      this.nav.replaceChildren(
        this.backBtn,
        h('div.logo', 'Buffer Zone'),
        link('Home', 'home'),
        ...this.visibleSections().map(s => link(s.title, 'library', { id: s.key })),
        h('div.spacer'),
        search,
        h('button.nav-link.icon', { title: 'Settings', onclick: () => this.go('settings') }, '⚙')
      );
    }

    go(name, args = {}, { replace = false, root = false } = {}) {
      if (root) this.history = [];
      else if (this.current && !replace) this.history.push({ ...this.current, scroll: this.main.scrollTop });
      this.current = { name, args };
      this.render();
    }

    back() {
      const prev = this.history.pop();
      if (!prev) return;
      if (prev.name !== 'search') { const s = document.getElementById('search'); if (s) s.value = ''; }
      this.current = prev;
      this.render(prev.scroll);
    }

    async render(scroll = 0) {
      const { name, args } = this.current;
      for (const [k, el] of Object.entries(this.navLinks || {})) el.classList.toggle('active', k === name + (args.id || ''));
      this.backBtn.style.visibility = this.history.length ? 'visible' : 'hidden';
      const token = (this.renderToken = Symbol());
      const view = h('div.view');
      this.main.replaceChildren(view);
      this.main.scrollTop = 0;
      try {
        await this.views[name].call(this, view, args, () => token === this.renderToken);
        if (scroll) requestAnimationFrame(() => { this.main.scrollTop = scroll; });
      } catch (e) {
        sofa.log('view error', name, String(e?.stack || e));
        if (token === this.renderToken) view.replaceChildren(h('div.empty', h('h3', 'Something went wrong'), h('p.muted', e.message), h('button.btn', { onclick: () => this.render() }, 'Retry')));
      }
    }

    onPlayerExit() {
      if (this.current) this.render(this.main.scrollTop);
    }

    // ---------- playback entry ----------
    play(item, { resume = true } = {}) {
      const startAt = resume && item.viewOffset ? item.viewOffset / 1000 : 0;
      this.player.play(item, { startAt });
    }

    // ---------- cards ----------
    progressOf(item) {
      if (!item.viewOffset || !item.duration) return 0;
      return Math.min(1, item.viewOffset / item.duration);
    }

    posterCard(item) {
      const p = this.plex;
      const thumb = item.type === 'episode' ? item.grandparentThumb || item.parentThumb || item.thumb : item.type === 'season' ? item.thumb || item.parentThumb : item.thumb;
      const watched = item.type === 'movie' ? item.viewCount > 0 : item.leafCount && item.viewedLeafCount >= item.leafCount;
      const unwatchedEps = item.type === 'show' ? (item.leafCount || 0) - (item.viewedLeafCount || 0) : 0;
      const prog = this.progressOf(item);
      const title = item.type === 'episode' ? item.grandparentTitle : item.type === 'season' ? item.parentTitle : item.title;
      const sub = item.type === 'episode' ? `${epCode(item)} · ${item.title}` : item.type === 'season' ? item.title : item.year || '';
      return h('div.card.poster', { onclick: () => this.open(item), title },
        h('div.img', thumb ? h('img', { src: p.image(thumb, 300, 450), loading: 'lazy' }) : h('div.noimg', title),
          watched ? h('span.badge.ok', '✓') : unwatchedEps > 0 && item.viewedLeafCount ? h('span.badge', String(unwatchedEps)) : null,
          prog ? h('div.prog', h('div', { style: { width: `${prog * 100}%` } })) : null),
        h('div.c-title', title),
        h('div.c-sub', sub)
      );
    }

    wideCard(item, { playOnClick = false } = {}) {
      const p = this.plex;
      const img = item.type === 'episode' ? item.thumb || item.art || item.grandparentArt : item.art || item.thumb;
      const prog = this.progressOf(item);
      const title = item.type === 'episode' ? item.grandparentTitle : item.title;
      const sub = item.type === 'episode' ? `${epCode(item)} · ${item.title}` : [item.year, item.duration && fmtDuration(item.duration)].filter(Boolean).join(' · ');
      const left = item.viewOffset && item.duration ? `${Math.round((item.duration - item.viewOffset) / 60000)} min left` : '';
      return h('div.card.wide', { onclick: () => (playOnClick ? this.play(item) : this.open(item)), title: playOnClick ? 'Play' : title },
        h('div.img', img ? h('img', { src: p.image(img, 480, 270), loading: 'lazy' }) : h('div.noimg', title),
          playOnClick ? h('div.play-ov', '▶') : null,
          prog ? h('div.prog', h('div', { style: { width: `${prog * 100}%` } })) : null),
        h('div.c-row',
          h('div', h('div.c-title', title), h('div.c-sub', sub, left ? ` · ${left}` : '')),
          item.type === 'episode' ? h('button.mini', { title: 'Open series', onclick: (e) => { e.stopPropagation(); this.go('show', { key: item.grandparentRatingKey }); } }, 'ⓘ') : null
        )
      );
    }

    open(item) {
      if (item.type === 'movie') this.go('movie', { key: item.ratingKey });
      else if (item.type === 'show') this.go('show', { key: item.ratingKey });
      else if (item.type === 'season') this.go('show', { key: item.parentRatingKey, season: item.ratingKey });
      else if (item.type === 'episode') this.go('show', { key: item.grandparentRatingKey, season: item.parentRatingKey, focus: item.ratingKey });
    }

    row(title, items, cardFn) {
      if (!items.length) return null;
      return h('section.row', h('h2.row-title', title), h('div.row-scroll', items.map(cardFn)));
    }

    // ---------- views ----------
    get views() {
      return {
        home: async (view, _a, alive) => {
          const secs = this.visibleSections();
          view.append(h('div.loading', 'Loading…'));
          const [cw, ...recent] = await Promise.all([
            this.plex.continueWatching().catch(() => []),
            ...secs.map(s => this.plex.recentlyAdded(s.key).catch(() => []))
          ]);
          if (!alive()) return;
          const rows = [this.row('Continue Watching', cw, (i) => this.wideCard(i, { playOnClick: true }))];
          secs.forEach((s, i) => rows.push(this.row(`Recently Added · ${s.title}`, recent[i], (it) => this.posterCard(it))));
          view.replaceChildren(
            h('div.server-line.muted', `${this.plex.server.name}`, this.plex.server.relay ? ' · via Plex relay (limited speed)' : ''),
            ...rows.filter(Boolean)
          );
          if (!view.querySelector('.row')) view.append(h('div.empty', h('p.muted', 'Nothing here yet.')));
        },

        library: async (view, { id }, alive) => {
          const sec = this.sections.find(s => s.key === id);
          const f = { sort: 'addedAt:desc', genre: '', unwatched: false, ...(this.settings.libraryFilters?.[id] || {}) };
          const save = () => this.saveSettings({ libraryFilters: { ...(this.settings.libraryFilters || {}), [id]: f } });
          const grid = h('div.grid');
          const count = h('span.muted');
          const genreSel = h('select', { onchange: () => { f.genre = genreSel.value; save(); reload(); } }, h('option', { value: '' }, 'All genres'));
          const sortSel = h('select', { onchange: () => { f.sort = sortSel.value; save(); reload(); } }, SORTS.map(([v, l]) => h('option', { value: v, selected: v === f.sort }, l)));
          const unw = h('input', { type: 'checkbox', checked: f.unwatched, onchange: () => { f.unwatched = unw.checked; save(); reload(); } });
          view.append(
            h('div.lib-head', h('h1', sec.title), count),
            h('div.filters', sortSel, genreSel, h('label.check', unw, sec.type === 'show' ? 'Unwatched episodes only' : 'Unwatched only')),
            grid
          );
          this.plex.genres(id).then(gs => {
            if (!alive()) return;
            genreSel.append(...gs.map(g => h('option', { value: g.key, selected: String(g.key) === String(f.genre) }, g.title)));
          }).catch(() => {});

          let start = 0, total = Infinity, busy = false, gen = 0;
          const sentinel = h('div.sentinel');
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
              count.textContent = `${total} ${sec.type === 'show' ? 'series' : 'movies'}`;
              grid.append(...r.items.map(i => this.posterCard(i)));
              if (!grid.children.length) grid.append(h('div.empty', h('p.muted', 'No matches.')));
            } finally { busy = false; }
            if (sentinel.getBoundingClientRect().top < window.innerHeight * 2) more();
          };
          const reload = () => { gen++; start = 0; total = Infinity; busy = false; grid.replaceChildren(); more(); };
          view.append(sentinel);
          new IntersectionObserver((es) => { if (es[0].isIntersecting) more(); }, { root: this.main, rootMargin: '800px' }).observe(sentinel);
          await more();
        },

        search: async (view, { q }, alive) => {
          view.append(h('div.loading', `Searching “${q}”…`));
          const r = await this.plex.search(q);
          if (!alive()) return;
          const rows = [
            r.movie.length ? h('section', h('h2.row-title', 'Movies'), h('div.grid', r.movie.map(i => this.posterCard(i)))) : null,
            r.show.length ? h('section', h('h2.row-title', 'Series'), h('div.grid', r.show.map(i => this.posterCard(i)))) : null,
            r.episode.length ? h('section', h('h2.row-title', 'Episodes'), h('div.grid.wide', r.episode.map(i => this.wideCard(i)))) : null
          ].filter(Boolean);
          view.replaceChildren(h('h1', `Results for “${q}”`), ...(rows.length ? rows : [h('div.empty', h('p.muted', 'No results.'))]));
        },

        movie: async (view, { key }, alive) => {
          const m = await this.plex.metadata(key);
          if (!alive()) return;
          view.append(this.hero(m, [
            this.playButtons(m),
            h('button.btn', { onclick: () => this.toggleWatched(m) }, m.viewCount ? 'Mark unwatched' : 'Mark watched')
          ]), this.techInfo(m));
        },

        show: async (view, { key, season, focus }, alive) => {
          const [show, seasons, leaves] = await Promise.all([this.plex.metadata(key), this.plex.children(key), this.plex.allLeaves(key)]);
          if (!alive()) return;
          const upNext = leaves.find(e => e.viewOffset && !e.viewCount) || leaves.find(e => !e.viewCount) || leaves[0];
          const allWatched = leaves.length && leaves.every(e => e.viewCount);
          const actions = [];
          if (upNext) {
            actions.push(h('button.btn.primary.lg', { onclick: () => this.play(upNext) },
              `▶ ${upNext.viewOffset && !upNext.viewCount ? 'Resume' : 'Play'} ${epCode(upNext)}`));
          }
          actions.push(h('button.btn', { onclick: () => this.toggleWatched(show, allWatched) }, allWatched ? 'Mark series unwatched' : 'Mark series watched'));
          const meta = { ...show, viewCount: 0 };
          view.append(this.hero(meta, actions));

          const curSeason = season || upNext?.parentRatingKey || seasons[0]?.ratingKey;
          const tabs = h('div.tabs');
          const list = h('div.episodes');
          const showSeason = (sk) => {
            for (const t of tabs.children) t.classList.toggle('active', t.dataset.key === String(sk));
            const s = seasons.find(x => x.ratingKey === String(sk));
            const eps = leaves.filter(e => e.parentRatingKey === String(sk));
            const seasonWatched = eps.length && eps.every(e => e.viewCount);
            list.replaceChildren(
              h('div.season-bar',
                h('span.muted', `${eps.length} episodes`),
                s ? h('button.btn.sm', { onclick: () => this.toggleWatched(s, seasonWatched) }, seasonWatched ? 'Mark season unwatched' : 'Mark season watched') : null),
              ...eps.map(e => this.episodeRow(e, e.ratingKey === focus))
            );
            this.current.args.season = sk;
            const f = list.querySelector('.focus');
            if (f) requestAnimationFrame(() => f.scrollIntoView({ block: 'center' }));
          };
          tabs.append(...seasons.map(s => {
            const done = s.leafCount && s.viewedLeafCount >= s.leafCount;
            return h('button.tab', { 'data-key': s.ratingKey, onclick: () => showSeason(s.ratingKey) }, s.title, done ? h('span.tick', ' ✓') : null);
          }));
          view.append(tabs, list);
          if (curSeason) showSeason(curSeason);
        },

        settings: async (view) => this.settingsView(view)
      };
    }

    hero(m, actions) {
      const p = this.plex;
      const facts = [
        m.year, m.duration && fmtDuration(m.duration), m.contentRating,
        m.audienceRating ? `★ ${m.audienceRating.toFixed(1)}` : m.rating ? `★ ${Number(m.rating).toFixed(1)}` : null,
        m.childCount ? `${m.childCount} season${m.childCount === 1 ? '' : 's'}` : null
      ].filter(Boolean);
      const genres = (m.Genre || []).map(g => g.tag).join(', ');
      const directors = (m.Director || []).map(g => g.tag).join(', ');
      const cast = (m.Role || []).slice(0, 8).map(r => r.tag).join(', ');
      return h('div.hero',
        m.art ? h('div.hero-bg', { style: { backgroundImage: `url("${p.image(m.art, 1600, 900)}")` } }) : null,
        h('div.hero-body',
          m.thumb ? h('img.hero-poster', { src: p.image(m.thumb, 300, 450) }) : null,
          h('div.hero-info',
            h('h1', m.title),
            h('div.facts', facts.join('  ·  ')),
            genres ? h('div.muted', genres) : null,
            h('div.actions', actions),
            m.summary ? h('p.summary', m.summary) : null,
            directors ? h('div.credit', h('b', 'Director: '), directors) : null,
            cast ? h('div.credit', h('b', 'Cast: '), cast) : null
          )
        )
      );
    }

    playButtons(m) {
      if (m.viewOffset) {
        return h('span.btn-group',
          h('button.btn.primary.lg', { onclick: () => this.play(m) }, `▶ Resume from ${fmtTime(m.viewOffset / 1000)}`),
          h('button.btn.lg', { onclick: () => this.play(m, { resume: false }) }, 'Play from start'));
      }
      return h('button.btn.primary.lg', { onclick: () => this.play(m) }, '▶ Play');
    }

    techInfo(m) {
      const media = m.Media?.[0];
      if (!media) return null;
      const streams = media.Part?.[0]?.Stream || [];
      const audio = streams.filter(s => s.streamType === 2).map(s => s.displayTitle).join(' · ');
      const subs = streams.filter(s => s.streamType === 3).map(s => s.displayTitle).join(' · ');
      return h('div.tech',
        h('div', h('b', 'Video: '), [media.videoResolution && `${media.videoResolution}${/^\d+$/.test(media.videoResolution) ? 'p' : ''}`, media.videoCodec, media.container, media.bitrate && `${(media.bitrate / 1000).toFixed(1)} Mbps`].filter(Boolean).join(' · ')),
        audio ? h('div', h('b', 'Audio: '), audio) : null,
        subs ? h('div', h('b', 'Subtitles: '), subs) : null
      );
    }

    episodeRow(e, focus) {
      const p = this.plex;
      const prog = this.progressOf(e);
      const watched = e.viewCount > 0;
      return h('div.ep' + (focus ? '.focus' : ''),
        h('div.ep-img', { onclick: () => this.play(e) },
          e.thumb ? h('img', { src: p.image(e.thumb, 320, 180), loading: 'lazy' }) : h('div.noimg'),
          h('div.play-ov', '▶'),
          prog ? h('div.prog', h('div', { style: { width: `${prog * 100}%` } })) : null),
        h('div.ep-body',
          h('div.ep-title', h('span.ep-code', epCode(e)), e.title),
          h('div.muted.small', [e.duration && fmtDuration(e.duration), e.originallyAvailableAt, prog ? `${fmtTime(e.viewOffset / 1000)} watched` : null].filter(Boolean).join(' · ')),
          e.summary ? h('p.ep-sum', e.summary) : null
        ),
        h('div.ep-actions',
          e.viewOffset && !watched ? h('button.btn.sm', { onclick: () => this.play(e, { resume: false }) }, 'From start') : null,
          h('button.watch' + (watched ? '.on' : ''), { title: watched ? 'Mark unwatched' : 'Mark watched', onclick: () => this.toggleWatched(e) }, watched ? '✓' : '○')
        )
      );
    }

    async toggleWatched(item, isWatched = item.viewCount > 0) {
      try {
        if (isWatched) await this.plex.unscrobble(item.ratingKey); else await this.plex.scrobble(item.ratingKey);
        toast(isWatched ? 'Marked unwatched' : 'Marked watched');
        this.render(this.main.scrollTop);
      } catch (e) { toast('Failed: ' + e.message); }
    }

    // ---------- settings ----------
    settingsView(view) {
      const s = this.settings;
      const set = (patch) => { this.saveSettings(patch); preview.applyStyle(this.settings); };
      const select = (key, options, onchange) => h('select', { onchange: (e) => { set({ [key]: e.target.value }); onchange?.(); } },
        options.map(([v, l]) => h('option', { value: v, selected: String(s[key]) === String(v) }, l)));
      const check = (key, label) => h('label.check', h('input', { type: 'checkbox', checked: !!s[key], onchange: (e) => set({ [key]: e.target.checked }) }), label);
      const field = (label, control, hint) => h('div.field', h('div.f-label', label, hint ? h('div.muted.small', hint) : null), control);

      const previewEl = h('div.subs.preview-subs');
      const preview = new Sofa.Subtitles.SubtitleOverlay(previewEl, () => 1);
      preview.applyStyle(s);
      preview.setCues([{ start: 0, end: 5, html: 'This is how your subtitles look.<br><i>Second line in italics.</i>' }]);

      const libs = this.sections.map(sec => h('label.check', h('input', {
        type: 'checkbox', checked: !s.hiddenLibraries.includes(sec.key),
        onchange: (e) => {
          const hidden = new Set(this.settings.hiddenLibraries);
          if (e.target.checked) hidden.delete(sec.key); else hidden.add(sec.key);
          set({ hiddenLibraries: [...hidden] });
          this.buildNav();
        }
      }), sec.title));

      const seriesCount = Object.keys(s.seriesPrefs || {}).length;
      const langs = LANGUAGES;

      view.append(
        h('h1', 'Settings'),
        h('section.settings',
          h('h2', 'Playback'),
          field('Default quality', select('quality', QUALITY.map(q => [q.id, q.label])), 'Direct Play sends the original file. If the file cannot play, the app switches to a converted stream.'),
          check('autoPlayNext', 'Play the next episode automatically'),
          field('Countdown before next episode', select('nextCountdown', [[5, '5 seconds'], [10, '10 seconds'], [15, '15 seconds'], [30, '30 seconds']])),
          check('autoSkipIntro', 'Skip intros automatically'),
          check('autoSkipCredits', 'Skip credits automatically (goes to the next episode)')
        ),
        h('section.settings',
          h('h2', 'Languages'),
          field('Preferred audio language', select('audioLang', langs)),
          field('Subtitle language', select('subtitleLang', langs)),
          field('Show subtitles', select('subtitleMode', [['auto', 'When audio is in another language'], ['always', 'Always'], ['off', 'Never']]), 'For each series, the app remembers the audio and subtitles you select in the player.'),
          seriesCount ? h('div.field', h('div.f-label', `${seriesCount} series with remembered tracks`), h('button.btn.sm', { onclick: (e) => { set({ seriesPrefs: {} }); e.target.disabled = true; toast('Cleared'); } }, 'Forget all')) : null
        ),
        h('section.settings',
          h('h2', 'Subtitle style'),
          h('div.preview', previewEl),
          field('Size', h('input', { type: 'range', min: 60, max: 200, step: 10, value: s.subtitleSize, oninput: (e) => set({ subtitleSize: +e.target.value }) })),
          field('Color', select('subtitleColor', [['#ffffff', 'White'], ['#fff35c', 'Yellow'], ['#9fe8ff', 'Light blue'], ['#c8ffc8', 'Light green']])),
          field('Background', select('subtitleBackground', [['shadow', 'Outline and shadow'], ['box', 'Dark box'], ['none', 'None']])),
          field('Distance from bottom', h('input', { type: 'range', min: 2, max: 30, step: 1, value: s.subtitlePosition, oninput: (e) => set({ subtitlePosition: +e.target.value }) }))
        ),
        h('section.settings',
          h('h2', 'Libraries'),
          h('p.muted.small', 'Hidden libraries do not show on the home screen or in the top bar.'),
          h('div.libs', libs)
        ),
        h('section.settings',
          h('h2', 'Account'),
          h('div.field', h('div.f-label', 'Signed in as', h('div.muted.small', this.user?.username || this.user?.email || '')),
            h('button.btn.sm', { onclick: async () => { await sofa.setToken(null); this.saveSettings({ serverId: null }); this.plex.token = null; this.showLogin(); } }, 'Sign out')),
          h('div.field', h('div.f-label', 'Server', h('div.muted.small', `${this.plex.server.name} · ${this.plex.server.relay ? 'Plex relay' : this.plex.server.local ? 'local network' : 'direct internet'}`)),
            h('button.btn.sm', { onclick: () => this.chooseServer(true) }, 'Switch server'))
        )
      );
    }
  }

  window.addEventListener('DOMContentLoaded', () => {
    const app = new App();
    window.app = app;
    app.init().catch(e => {
      sofa.log('init error', String(e?.stack || e));
      app.showMessage('Start-up failed', e.message, () => location.reload());
    });
  });
})();
