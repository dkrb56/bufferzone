// Settings view with a sub-nav: playback, languages, subtitle style, libraries, profiles, parental controls, account.
(function () {
  const S = window.Sofa;
  const { h, btn, pill, toast, selectEl, switchEl, checkEl, LANGUAGES, QUALITY } = S;

  const PAGES = [
    ['playback', 'Playback'], ['languages', 'Languages'], ['style', 'Subtitle style'], ['libraries', 'Libraries'],
    ['profiles', 'Profiles'], ['parental', 'Parental controls'], ['account', 'Account']
  ];
  const AGES = [['7', '7 · TV-Y7'], ['12', '12 · TV-PG, PG'], ['13', '13 · PG-13'], ['14', '14 · TV-14'], ['16', '16'], ['17', '17 · R, TV-MA']];

  const row = (label, hint, control) => h('div.s-row', h('div', h('div.s-label', label), hint ? h('div.s-hint', hint) : null), control);
  const card = (...rows) => h('div.s-card', ...rows);

  S.views.settings = async function (view, args) {
    const page = args.page || 'playback';
    const s = this.settings;
    const set = (patch) => this.saveSettings(patch);
    const nav = h('div.subnav', PAGES.map(([k, l]) => h('button' + (k === page ? '.active' : ''), { onclick: () => this.go('settings', { page: k }, { replace: true }) }, l)));
    const body = h('div.s-page');
    view.append(h('div.page-head', h('h1', 'Settings')), h('div.settings', nav, body));
    const title = PAGES.find(p => p[0] === page)[1];

    if (page === 'playback') {
      body.append(h('h2', title), h('p.muted', 'Direct Play sends the original file. If the file cannot play, the app switches to a converted stream.'),
        card(
          row('Default quality', 'You can also change it in the player.', selectEl(QUALITY.map(q => [q.id, q.label]), s.quality, (v) => set({ quality: v }))),
          row('Play the next episode automatically', null, switchEl(s.autoPlayNext, (v) => set({ autoPlayNext: v }))),
          row('Countdown before next episode', null, selectEl([[5, '5 seconds'], [10, '10 seconds'], [15, '15 seconds'], [30, '30 seconds']], s.nextCountdown, (v) => set({ nextCountdown: Number(v) })))),
        card(
          row('Skip intros automatically', null, switchEl(s.autoSkipIntro, (v) => set({ autoSkipIntro: v }))),
          row('Skip credits automatically', 'Goes to the next episode.', switchEl(s.autoSkipCredits, (v) => set({ autoSkipCredits: v })))));
    }

    else if (page === 'languages') {
      const n = Object.keys(s.seriesPrefs || {}).length;
      body.append(h('h2', title), h('p.muted', 'For each series, the app remembers the audio and subtitles you select in the player.'),
        card(
          row('Preferred audio language', null, selectEl(LANGUAGES, s.audioLang, (v) => set({ audioLang: v }))),
          row('Subtitle language', null, selectEl(LANGUAGES, s.subtitleLang, (v) => set({ subtitleLang: v }))),
          row('Show subtitles', null, selectEl([['auto', 'When audio is in another language'], ['always', 'Always'], ['off', 'Never']], s.subtitleMode, (v) => set({ subtitleMode: v })))),
        n ? card(row(`${n} series with remembered tracks`, null, btn('Forget all', { size: 'sm', onclick: () => { set({ seriesPrefs: {} }); toast('Cleared'); this.render(); } }))) : null);
    }

    else if (page === 'style') {
      const previewEl = h('div.subs');
      const preview = new S.Subtitles.SubtitleOverlay(previewEl, () => 1);
      const restyle = (patch) => { set(patch); preview.applyStyle(this.settings); };
      preview.applyStyle(s);
      preview.setCues([{ start: 0, end: 5, html: 'This is how your subtitles look.<br><i>Č, š, ž, đ and ć work too.</i>' }]);
      const range = (key, min, max, step) => h('input', { type: 'range', min, max, step, value: s[key], oninput: (e) => restyle({ [key]: +e.target.value }) });
      body.append(h('h2', title), h('p.muted', 'Applies to text subtitles. Image subtitles are part of the video and keep their own look.'),
        h('div.preview', previewEl),
        card(
          row('Size', null, range('subtitleSize', 60, 200, 10)),
          row('Color', null, selectEl([['#ffffff', 'White'], ['#fff35c', 'Yellow'], ['#9fe8ff', 'Light blue'], ['#c8ffc8', 'Light green']], s.subtitleColor, (v) => restyle({ subtitleColor: v }))),
          row('Background', null, selectEl([['shadow', 'Outline and shadow'], ['box', 'Dark box'], ['none', 'None']], s.subtitleBackground, (v) => restyle({ subtitleBackground: v }))),
          row('Distance from bottom', null, range('subtitlePosition', 2, 30, 1))));
    }

    else if (page === 'libraries') {
      body.append(h('h2', title), h('p.muted', 'Hidden libraries do not show on the home screen, in the sidebar or in search.'),
        card(h('div.s-checks', this.sections.map(sec => checkEl(!s.hiddenLibraries.includes(sec.key), sec.title, (on) => {
          const hidden = new Set(this.settings.hiddenLibraries);
          if (on) hidden.delete(sec.key); else hidden.add(sec.key);
          set({ hiddenLibraries: [...hidden] });
          this.buildRail();
        })))));
    }

    else if (page === 'profiles') {
      body.append(h('h2', title), h('p.muted', 'Profiles are the Plex Home users of your account. Each profile has its own watch history and watchlist.'));
      if (this.homeUsers.length > 1) {
        body.append(card(...this.homeUsers.map(u => row(u.title, u.admin ? 'Admin' : u.restricted ? 'Managed' : u.guest ? 'Guest' : 'Member',
          this.profile?.id === u.id ? pill('Current', 'accent') : btn('Switch', { size: 'sm', onclick: () => this.switchProfile(u) })))),
          btn('Manage profiles on plex.tv', { icon: 'users', onclick: () => sofa.openExternal('https://app.plex.tv/desktop/#!/settings/manage-home') }));
      } else {
        body.append(card(row('No Plex Home users', 'Add users to your Plex Home on plex.tv. Then they show here and on start-up.', btn('Open plex.tv', { size: 'sm', onclick: () => sofa.openExternal('https://app.plex.tv/desktop/#!/settings/manage-home') }))));
      }
    }

    else if (page === 'parental') {
      const admin = this.homeUsers.find(u => u.admin);
      const managed = this.homeUsers.filter(u => !u.admin);
      body.append(h('h2', title), h('p.muted', `Managed profiles see only what is allowed here. ${admin ? admin.title : 'The admin profile'} is the admin profile and has no limits.`));
      if (!managed.length) {
        body.append(card(row('No managed profiles', 'Your Plex Home has no other users. Add them on plex.tv first.', null)));
      } else if (this.profile && !this.profile.admin) {
        body.append(card(row('Admin only', 'Switch to the admin profile to change parental controls.', null)));
      } else {
        const sel = managed.find(u => u.id === Number(args.who)) || managed[0];
        const rules = { ...S.DEFAULT_RULES, ...(s.profileRules?.[sel.id] || {}) };
        const save = (patch) => { Object.assign(rules, patch); set({ profileRules: { ...(this.settings.profileRules || {}), [sel.id]: rules } }); };
        const libs = rules.libraries || this.sections.map(x => x.key);
        body.append(
          h('div.tabs', { style: { marginBottom: '14px' } }, managed.map(u => h('button.tab' + (u === sel ? '.active' : ''), { onclick: () => this.go('settings', { page, who: u.id }, { replace: true }) }, S.icon('shield', { size: 13 }), u.title))),
          card(
            row('Restrict content', 'Hide titles above the rating below.', switchEl(rules.restrict, (v) => save({ restrict: v }))),
            row('Highest allowed rating', null, selectEl(AGES, rules.maxAge, (v) => save({ maxAge: Number(v) }))),
            row('Unrated titles', 'Home videos and titles without a rating.', selectEl([['hide', 'Hide'], ['show', 'Show']], rules.unrated, (v) => save({ unrated: v })))),
          card(h('div.s-row', h('div.s-label', 'Libraries')), h('div.s-checks', { style: { paddingTop: 0 } }, this.sections.map(sec => checkEl(libs.includes(sec.key), sec.title, (on) => {
            const set2 = new Set(rules.libraries || this.sections.map(x => x.key));
            if (on) set2.add(sec.key); else set2.delete(sec.key);
            save({ libraries: [...set2] });
          })))),
          card(
            row('Require admin PIN to leave this profile', 'Applies to profile switch and sign out.', switchEl(rules.pinToLeave, (v) => save({ pinToLeave: v }))),
            row('Allow Multi-view', null, switchEl(rules.multiview, (v) => save({ multiview: v })))));
      }
    }

    else if (page === 'account') {
      const srv = this.plex.server;
      const signOut = async () => {
        const r = this.rules;
        if (r?.pinToLeave) return toast('Switch to the admin profile to sign out.');
        await sofa.setToken(null);
        this.saveSettings({ serverId: null, profileId: null });
        this.plex.token = this.plex.accountToken = null;
        this.profile = null;
        this.onb = null;
        this.onboarding(1);
      };
      body.append(h('h2', title),
        card(
          row('Signed in as', this.user?.username || this.user?.email || '', btn('Sign out', { size: 'sm', icon: 'log-out', onclick: signOut })),
          row('Profile', this.profile?.title || '', this.homeUsers.length > 1 ? btn('Switch profile', { size: 'sm', onclick: () => this.showProfiles() }) : null),
          row('Server', `${srv.name} · ${srv.relay ? 'Plex relay' : srv.local ? 'local network' : 'direct internet'}`, btn('Switch server', { size: 'sm', onclick: () => { this.onb = null; this.onboarding(2); } }))));
    }
  };
})();
