// Plex Home profiles: "Who's watching?" screen and profile switching.
(function () {
  const S = window.Sofa;
  const { h, icon, btn, pill, toast, askPin } = S;
  const P = S.App.prototype;

  function profileMeta(app, u) {
    if (u.admin) return [u.protected ? 'lock' : 'user', u.protected ? 'Admin · PIN' : 'Admin'];
    if (u.guest) return [null, 'No history'];
    const r = app.settings.profileRules?.[u.id];
    const limit = r?.restrict ? ` · up to ${r.maxAge ?? 12}` : '';
    if (u.restricted) return ['shield', (u.restrictionProfile === 'little_kid' ? 'Kids' : 'Managed') + limit];
    return [u.protected ? 'lock' : null, u.protected ? 'PIN' : 'Member'];
  }

  P.profileTiles = function () {
    return h('div.prof-row', this.homeUsers.map(u => {
      const [ic, text] = profileMeta(this, u);
      const name = u.title || u.username || 'User';
      return h('button.prof' + (this.profile?.id === u.id ? '.sel' : ''), { onclick: () => this.switchProfile(u) },
        h('div.prof-tile', name[0].toUpperCase()),
        h('div.prof-name', name),
        h('div.prof-meta', ic ? icon(ic, { size: 12 }) : null, text));
    }).concat(h('button.prof.add', { onclick: () => sofa.openExternal('https://app.plex.tv/desktop/#!/settings/manage-home') },
      h('div.prof-tile', icon('plus', { size: 32 })), h('div.prof-name', 'Add profile'), h('div.prof-meta', 'On plex.tv'))));
  };

  P.showProfiles = function () {
    this.bare(h('div.profiles',
      this.lockup(18),
      this.plex.server ? pill(this.plex.server.name, 'ok') : null,
      h('h1', 'Who\'s watching?'),
      h('p.muted', `Plex Home on ${this.plex.server?.name || 'your server'}`),
      this.profileTiles(),
      btn('Manage profiles', { icon: 'users', onclick: () => sofa.openExternal('https://app.plex.tv/desktop/#!/settings/manage-home') })));
  };

  P.switchProfile = async function (u) {
    const admin = this.homeUsers.find(x => x.admin);
    // A managed profile can require the admin PIN before it is left.
    const leaving = this.profile && this.profile.id !== u.id ? this.settings.profileRules?.[this.profile.id] : null;
    if (leaving?.pinToLeave && !this.profile.admin && admin) {
      const ok = await askPin('Admin PIN', `Type the PIN of ${admin.title} to leave this profile.`, async (pin) => {
        try { await this.plex.switchUser(admin.uuid, pin); return null; } catch { return 'Wrong PIN.'; }
      });
      if (!ok) return;
    }
    let token = null;
    try {
      if (u.id === this.user.id) {
        token = this.plex.accountToken;
      } else if (u.protected) {
        const pin = await askPin(`PIN for ${u.title}`, 'This profile is protected with a PIN.', async (p) => {
          try { token = (await this.plex.switchUser(u.uuid, p)).authToken; return null; } catch { return 'Wrong PIN.'; }
        });
        if (!pin) return;
      } else {
        token = (await this.plex.switchUser(u.uuid)).authToken;
      }
    } catch (e) {
      return toast('Cannot switch profile: ' + e.message);
    }
    if (!token) return;

    // The server gives each profile its own access token.
    const prevToken = this.plex.token;
    this.plex.token = token;
    try {
      const servers = await this.plex.resources();
      const res = servers.find(s => s.clientIdentifier === this.settings.serverId);
      if (!res) throw new Error(`${u.title} has no access to ${this.plex.server.name}.`);
      await this.plex.connect(res);
      this.sections = await this.plex.sections();
    } catch (e) {
      this.plex.token = prevToken;
      return toast(e.message);
    }
    this.profile = u;
    this.saveSettings({ profileId: u.id });
    this.watchlist = null;
    this.wlMatch = null;
    this.wlAdded = null;
    if (this.onb && document.body.classList.contains('bare') && this.onb.step === 4) this.onb = null;
    this.enterApp();
  };
})();
