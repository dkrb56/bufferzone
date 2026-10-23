// Plex API client: plex.tv sign-in, server discovery and the Plex Media Server API.
(function () {
  const PRODUCT = 'Buffer Zone';
  const VERSION = '0.1.0';

  function qs(params) {
    return Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
      .join('&');
  }

  class Plex {
    constructor(clientId) {
      this.clientId = clientId;
      this.token = null;          // plex.tv account token
      this.server = null;         // { name, id, base, token, local, relay }
    }

    clientHeaders() {
      return {
        'X-Plex-Product': PRODUCT,
        'X-Plex-Version': VERSION,
        'X-Plex-Client-Identifier': this.clientId,
        'X-Plex-Platform': 'Chrome',
        'X-Plex-Platform-Version': navigator.userAgent.match(/Chrome\/([\d.]+)/)?.[1] || '1',
        'X-Plex-Device': 'Windows',
        'X-Plex-Device-Name': 'Buffer Zone (Windows)',
        'X-Plex-Model': 'hosted',
        'Accept': 'application/json'
      };
    }

    // Same values as query params, for URLs loaded by <video>, <img> or hls.js.
    clientParams(token) {
      const h = this.clientHeaders();
      delete h.Accept;
      return { ...h, 'X-Plex-Token': token };
    }

    async tv(path, { method = 'GET', params } = {}) {
      const url = `https://plex.tv${path}${params ? '?' + qs(params) : ''}`;
      const headers = this.clientHeaders();
      if (this.token) headers['X-Plex-Token'] = this.token;
      const res = await sofa.http({ url, method, headers });
      if (!res.ok) throw new PlexError(`plex.tv ${path} failed (${res.status})`, res.status);
      return res.body;
    }

    // ---- sign-in (PIN flow; the user signs in on plex.tv in their own browser) ----
    async createPin() {
      return this.tv('/api/v2/pins', { method: 'POST', params: { strong: 'true' } });
    }
    authUrl(code) {
      return 'https://app.plex.tv/auth#?' + qs({
        clientID: this.clientId,
        code,
        'context[device][product]': PRODUCT
      });
    }
    async checkPin(id) {
      const pin = await this.tv(`/api/v2/pins/${id}`);
      return pin.authToken || null;
    }
    async user() {
      return this.tv('/api/v2/user');
    }

    // ---- servers ----
    async resources() {
      const list = await this.tv('/api/v2/resources', { params: { includeHttps: 1, includeRelay: 1, includeIPv6: 1 } });
      return (Array.isArray(list) ? list : []).filter(r => (r.provides || '').includes('server'));
    }

    // Try all connections in parallel; prefer a direct connection over the Plex relay.
    async connect(resource) {
      const token = resource.accessToken || this.token;
      const conns = resource.connections || [];
      const probe = async (c) => {
        const res = await sofa.http({ url: `${c.uri}/identity`, headers: { ...this.clientHeaders(), 'X-Plex-Token': token }, timeout: 6000 });
        if (!res.ok) throw new Error(`${c.uri} -> ${res.status}`);
        return c;
      };
      const direct = conns.filter(c => !c.relay);
      const relay = conns.filter(c => c.relay);
      let chosen = null;
      try { chosen = await Promise.any(direct.map(probe)); } catch {}
      if (!chosen) {
        try { chosen = await Promise.any(relay.map(probe)); } catch {}
      }
      if (!chosen) throw new PlexError(`Cannot reach server "${resource.name}". It may be offline.`);
      this.server = {
        id: resource.clientIdentifier,
        name: resource.name,
        owned: !!resource.owned,
        base: chosen.uri.replace(/\/$/, ''),
        token,
        local: !!chosen.local,
        relay: !!chosen.relay
      };
      sofa.log('connected', { name: resource.name, local: chosen.local, relay: chosen.relay });
      return this.server;
    }

    // ---- server API ----
    url(path, params = {}) {
      const sep = path.includes('?') ? '&' : '?';
      const q = qs(params);
      return `${this.server.base}${path}${q ? sep + q : ''}`;
    }

    async get(path, params, { method = 'GET', timeout } = {}) {
      const res = await sofa.http({
        url: this.url(path, params),
        method,
        timeout,
        headers: { ...this.clientHeaders(), 'X-Plex-Token': this.server.token }
      });
      if (!res.ok) throw new PlexError(`Server request failed: ${path} (${res.status})`, res.status);
      return res.body && res.body.MediaContainer ? res.body.MediaContainer : res.body;
    }
    put(path, params) { return this.get(path, params, { method: 'PUT' }); }

    async getText(path, params) {
      const res = await sofa.http({
        url: this.url(path, params),
        headers: { ...this.clientHeaders(), 'Accept': '*/*', 'X-Plex-Token': this.server.token }
      });
      if (!res.ok) throw new PlexError(`Request failed: ${path} (${res.status})`, res.status);
      return typeof res.body === 'string' ? res.body : JSON.stringify(res.body);
    }

    image(path, w, h) {
      if (!path) return '';
      return this.url('/photo/:/transcode', {
        width: Math.round(w), height: Math.round(h), minSize: 1, upscale: 1,
        url: path, 'X-Plex-Token': this.server.token
      });
    }

    // Libraries
    async sections() {
      const mc = await this.get('/library/sections');
      return (mc.Directory || []).filter(d => d.type === 'movie' || d.type === 'show');
    }
    async sectionItems(id, { sort, genre, unwatched, start = 0, size = 60, type } = {}) {
      const mc = await this.get(`/library/sections/${id}/all`, {
        type, sort, genre, unwatched: unwatched ? 1 : undefined,
        includeCollections: 0,
        'X-Plex-Container-Start': start, 'X-Plex-Container-Size': size
      });
      return { items: mc.Metadata || [], total: mc.totalSize ?? mc.size ?? 0 };
    }
    async genres(id) {
      const mc = await this.get(`/library/sections/${id}/genre`);
      return mc.Directory || [];
    }
    async recentlyAdded(id, size = 24) {
      const mc = await this.get(`/library/sections/${id}/recentlyAdded`, { 'X-Plex-Container-Start': 0, 'X-Plex-Container-Size': size });
      return mc.Metadata || [];
    }
    async continueWatching() {
      try {
        const mc = await this.get('/hubs/home/continueWatching', { count: 30 });
        const hub = (mc.Hub || [])[0];
        if (hub && hub.Metadata) return hub.Metadata;
      } catch {}
      const mc = await this.get('/library/onDeck', { 'X-Plex-Container-Size': 30 });
      return mc.Metadata || [];
    }
    async search(query) {
      const mc = await this.get('/hubs/search', { query, limit: 30, includeCollections: 0, includeExternalMedia: 0 });
      const out = { movie: [], show: [], episode: [] };
      for (const hub of mc.Hub || []) {
        if (out[hub.type]) out[hub.type].push(...(hub.Metadata || []));
      }
      return out;
    }
    async metadata(ratingKey) {
      const mc = await this.get(`/library/metadata/${ratingKey}`, { includeMarkers: 1, includeChapters: 1 });
      return (mc.Metadata || [])[0];
    }
    async children(ratingKey) {
      const mc = await this.get(`/library/metadata/${ratingKey}/children`);
      return mc.Metadata || [];
    }
    async allLeaves(ratingKey) {
      const mc = await this.get(`/library/metadata/${ratingKey}/allLeaves`);
      return mc.Metadata || [];
    }

    // Watch state
    scrobble(ratingKey) { return this.get('/:/scrobble', { identifier: 'com.plexapp.plugins.library', key: ratingKey }); }
    unscrobble(ratingKey) { return this.get('/:/unscrobble', { identifier: 'com.plexapp.plugins.library', key: ratingKey }); }
    timeline({ ratingKey, state, time, duration, sessionId }) {
      return sofa.http({
        url: this.url('/:/timeline', {
          ratingKey, key: `/library/metadata/${ratingKey}`, state,
          time: Math.round(time), duration: Math.round(duration),
          hasMDE: 1
        }),
        headers: { ...this.clientHeaders(), 'X-Plex-Token': this.server.token, 'X-Plex-Session-Identifier': sessionId }
      });
    }

    // Stream selection on the server (affects transcode output)
    selectStreams(partId, { audioStreamID, subtitleStreamID }) {
      return this.put(`/library/parts/${partId}`, { audioStreamID, subtitleStreamID, allParts: 1 });
    }
  }

  class PlexError extends Error {
    constructor(msg, status) { super(msg); this.status = status; }
  }

  window.Sofa = window.Sofa || {};
  Object.assign(window.Sofa, { Plex, PlexError, qs });
})();
