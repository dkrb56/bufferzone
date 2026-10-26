// webOS replacement for the Electron preload bridge (window.sofa), plus polyfills for older TV browsers
// (webOS 5 = Chromium 68). Loaded before the shared renderer scripts.
(function () {
  // ---------- polyfills ----------
  if (!Element.prototype.replaceChildren) {
    Element.prototype.replaceChildren = function () {
      while (this.firstChild) this.removeChild(this.firstChild);
      this.append.apply(this, arguments);
    };
  }
  if (!Array.prototype.flat) {
    Object.defineProperty(Array.prototype, 'flat', {
      configurable: true, writable: true,
      value: function flat(depth) {
        const d = depth === undefined ? 1 : Number(depth);
        return d < 1 ? this.slice() : this.reduce((acc, v) => acc.concat(Array.isArray(v) ? v.flat(d - 1) : v), []);
      }
    });
  }
  if (!Object.fromEntries) Object.fromEntries = (it) => { const o = {}; for (const [k, v] of it) o[k] = v; return o; };
  if (!Promise.any) {
    Promise.any = (list) => new Promise((resolve, reject) => {
      const arr = Array.from(list); let left = arr.length;
      if (!left) return reject(new Error('All promises were rejected'));
      arr.forEach(p => Promise.resolve(p).then(resolve, () => { if (--left === 0) reject(new Error('All promises were rejected')); }));
    });
  }
  if (!String.prototype.replaceAll) String.prototype.replaceAll = function (a, b) { return this.split(a).join(b); };
  if (!String.prototype.matchAll) {
    String.prototype.matchAll = function* (re) {
      const r = new RegExp(re.source, re.flags.indexOf('g') >= 0 ? re.flags : re.flags + 'g');
      let m;
      while ((m = r.exec(this)) !== null) { yield m; if (m[0] === '') r.lastIndex++; }
    };
  }
  if (!window.crypto) window.crypto = {};
  if (!crypto.randomUUID) {
    crypto.randomUUID = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
  }

  // The layout is made for about 1280 px. The TV gives 1920 px, so zoom 1.5: the browser draws text
  // and images at the full resolution (a 1280x720 app is drawn small and scaled up, which looks blurry).
  if (window.innerWidth > 1500) document.documentElement.style.zoom = String(window.innerWidth / 1280);

  // Flex "gap" arrived in Chromium 84; older TVs get the margin fallbacks that build.js adds.
  document.addEventListener('DOMContentLoaded', () => {
    const t = document.createElement('div');
    t.style.cssText = 'display:flex;flex-direction:column;row-gap:1px;position:absolute;visibility:hidden';
    t.appendChild(document.createElement('div')); t.appendChild(document.createElement('div'));
    document.body.appendChild(t);
    if (t.scrollHeight !== 1) document.documentElement.classList.add('no-flexgap');
    t.remove();
  });

  // ---------- settings and token (localStorage) ----------
  const DEFAULTS = {
    clientId: null, serverId: null, quality: 'direct', audioLang: 'eng', subtitleLang: 'eng', subtitleMode: 'auto',
    subtitleSize: 100, subtitleColor: '#ffffff', subtitleBackground: 'shadow', subtitlePosition: 8, audioBoost: 100,
    autoSkipIntro: false, autoSkipCredits: false, autoPlayNext: true, nextCountdown: 10, hiddenLibraries: [],
    seriesPrefs: {}, pinned: [], profileId: null, profileRules: {}, homeTab: 'all', lastLibrary: {}, libraryFilters: {}, mvLayout: '2x2'
  };
  function read() {
    let s = {};
    try { s = JSON.parse(localStorage.getItem('bz.settings') || '{}'); } catch (e) { s = {}; }
    s = Object.assign({}, DEFAULTS, s);
    if (!s.clientId) { s.clientId = crypto.randomUUID(); write(s); }
    return s;
  }
  function write(s) { try { localStorage.setItem('bz.settings', JSON.stringify(s)); } catch (e) {} }

  // ---------- HTTP ----------
  // Plex accepts its X-Plex-* values as query parameters. Sending them that way keeps requests "simple",
  // so the TV browser needs no CORS preflight for GET and POST.
  function toQuery(url, headers) {
    const extra = Object.keys(headers).filter(k => /^X-Plex-/i.test(k) && headers[k] != null && url.indexOf(k + '=') < 0)
      .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(headers[k]));
    if (!extra.length) return url;
    return url + (url.indexOf('?') >= 0 ? '&' : '?') + extra.join('&');
  }

  // The browser obeys the server's CORS rules; the app's own service (webos/service) does not.
  // Use the browser first; when it fails for an origin, send that origin's requests through the service.
  const SERVICE = 'luna://com.dkrb56.bufferzone.service/http';
  const viaService = new Set();
  const bridges = new Set();
  const originOf = (u) => (String(u).match(/^https?:\/\/[^/?#]+/i) || [''])[0].toLowerCase();

  async function http(req) {
    const hasBridge = typeof window.PalmServiceBridge !== 'undefined';
    const origin = originOf(req.url);
    if (!hasBridge || !viaService.has(origin)) {
      const r = await viaFetch(req);
      if (r.status !== 0 || !hasBridge) return r;
      viaService.add(origin);
      console.log('[bz] using the service for', origin);
    }
    return viaLuna(req);
  }

  function viaLuna({ url, method = 'GET', headers = {}, timeout = 15000 }) {
    return new Promise((resolve) => {
      const bridge = new window.PalmServiceBridge();
      bridges.add(bridge);   // keep a reference until the answer arrives
      let done = false;
      const finish = (r) => { if (done) return; done = true; clearTimeout(timer); bridges.delete(bridge); resolve(r); };
      const timer = setTimeout(() => finish({ ok: false, status: 0, body: 'service timeout' }), timeout + 5000);
      bridge.onservicecallback = (text) => {
        let m;
        try { m = JSON.parse(text); } catch (e) { return finish({ ok: false, status: 0, body: 'bad service answer' }); }
        if (m.returnValue === false) return finish({ ok: false, status: 0, body: m.errorText || 'service error' });
        let body = m.body;
        try { body = JSON.parse(m.body); } catch (e) {}
        finish({ ok: !!m.ok, status: m.status, body });
      };
      bridge.call(SERVICE, JSON.stringify({ url, method, headers, timeout }));
    });
  }

  // One call to a TV service; resolves with its parsed answer (a failure has returnValue false).
  function luna(uri, params = {}, timeout = 5000) {
    return new Promise((resolve) => {
      if (typeof window.PalmServiceBridge === 'undefined') return resolve({ returnValue: false, errorText: 'no service bridge' });
      const bridge = new window.PalmServiceBridge();
      bridges.add(bridge);
      let done = false;
      const finish = (m) => { if (done) return; done = true; clearTimeout(timer); bridges.delete(bridge); resolve(m); };
      const timer = setTimeout(() => finish({ returnValue: false, errorText: 'service timeout' }), timeout);
      bridge.onservicecallback = (text) => {
        try { finish(JSON.parse(text)); } catch (e) { finish({ returnValue: false, errorText: 'bad service answer' }); }
      };
      bridge.call(uri, JSON.stringify(params));
    });
  }

  async function viaFetch({ url, method = 'GET', headers = {}, timeout = 15000 }) {
    const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timer = ctrl ? setTimeout(() => ctrl.abort(), timeout) : null;
    try {
      const res = await fetch(toQuery(url, headers), { method, headers: { Accept: headers.Accept || 'application/json' }, signal: ctrl ? ctrl.signal : undefined });
      const text = await res.text();
      let body = text;
      try { body = JSON.parse(text); } catch (e) {}
      return { ok: res.ok, status: res.status, body };
    } catch (err) {
      console.warn('http error', method, String(url).replace(/(X-Plex-Token=)[^&]+/gi, '$1***'), String(err));
      return { ok: false, status: 0, body: String(err) };
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  window.sofa = {
    tv: true,
    nativeHls: true,
    version: '__BZ_VERSION__',   // set by build.js
    device: 'LG webOS',
    http,
    luna,
    getSettings: async () => read(),
    setSettings: async (patch) => { const s = Object.assign(read(), patch); write(s); return s; },
    getToken: async () => { try { return localStorage.getItem('bz.token'); } catch (e) { return null; } },
    setToken: async (t) => { try { if (t) localStorage.setItem('bz.token', t); else localStorage.removeItem('bz.token'); } catch (e) {} return true; },
    // A TV has no browser for these pages; tell the user where to go instead.
    openExternal: async (url) => {
      const short = String(url).replace(/^https:\/\/(app\.)?/, '').replace(/#.*$/, '');
      if (window.Sofa && Sofa.toast) Sofa.toast(`Open ${short} on a phone or computer.`, 6000);
    },
    fullscreen: async () => true,
    log: async (...args) => console.log('[bz]', ...args)
  };
})();
