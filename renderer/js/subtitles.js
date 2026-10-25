// Text subtitle parsing (SRT, WebVTT, ASS/SSA) and a styled overlay renderer.
(function () {
  function toSec(t) {
    // 01:02:03,456 | 01:02:03.456 | 02:03.456 | 1:02:03.45 (ASS)
    const m = String(t).trim().match(/(?:(\d+):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})/);
    if (!m) return NaN;
    const frac = m[4].length === 2 ? +m[4] / 100 : m[4].length === 1 ? +m[4] / 10 : +m[4] / 1000;
    return (+(m[1] || 0)) * 3600 + (+m[2]) * 60 + (+m[3]) + frac;
  }

  function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // Keep only <i>, <b>, <u>; drop everything else.
  function cleanMarkup(s) {
    let out = escapeHtml(s);
    out = out.replace(/&lt;(\/?)(i|b|u)&gt;/gi, '<$1$2>');
    out = out.replace(/&lt;\/?[a-z][^&]*?&gt;/gi, '');
    return out.replace(/\n/g, '<br>');
  }

  function parseSrtVtt(text) {
    const cues = [];
    const blocks = text.replace(/\r/g, '').replace(/^﻿/, '').split(/\n{2,}/);
    for (const block of blocks) {
      const lines = block.split('\n');
      const idx = lines.findIndex(l => l.includes('-->'));
      if (idx < 0) continue;
      const [a, b] = lines[idx].split('-->');
      const start = toSec(a), end = toSec(b);
      if (isNaN(start) || isNaN(end)) continue;
      const body = lines.slice(idx + 1).join('\n').trim();
      if (body) cues.push({ start, end, html: cleanMarkup(body) });
    }
    return cues;
  }

  function parseAss(text) {
    const cues = [];
    let format = null;
    let inEvents = false;
    for (const raw of text.replace(/\r/g, '').split('\n')) {
      const line = raw.trim();
      if (/^\[events\]/i.test(line)) { inEvents = true; continue; }
      if (/^\[/.test(line)) { inEvents = false; continue; }
      if (!inEvents) continue;
      if (/^format:/i.test(line)) {
        format = line.slice(7).split(',').map(s => s.trim().toLowerCase());
        continue;
      }
      if (!/^dialogue:/i.test(line) || !format) continue;
      const parts = line.slice(9).split(',');
      const fields = parts.slice(0, format.length - 1);
      fields.push(parts.slice(format.length - 1).join(','));
      const get = (k) => fields[format.indexOf(k)] || '';
      const start = toSec(get('start')), end = toSec(get('end'));
      let body = get('text');
      // Skip drawing commands and positioned signs (they look wrong without libass).
      if (/\\p[1-9]/.test(body)) continue;
      body = body.replace(/\{[^}]*\}/g, '').replace(/\\N/gi, '\n').replace(/\\h/g, ' ').trim();
      if (body && !isNaN(start) && !isNaN(end)) cues.push({ start, end, html: cleanMarkup(body) });
    }
    return cues.sort((x, y) => x.start - y.start);
  }

  function parse(text) {
    if (!text) return [];
    if (/\[script info\]|\[events\]/i.test(text)) return parseAss(text);
    return parseSrtVtt(text);
  }

  // Overlay renderer. `getTime()` returns the absolute media time in seconds.
  class SubtitleOverlay {
    constructor(el, getTime) {
      this.el = el;
      this.getTime = getTime;
      this.cues = [];
      this.delay = 0;
      this.last = '';
      this.raf = null;
    }
    setCues(cues) {
      this.cues = cues || [];
      this.last = null;
      this.render();
    }
    clear() { this.setCues([]); }
    applyStyle(s) {
      const st = this.el.style;
      st.setProperty('--sub-size', (s.subtitleSize / 100).toFixed(2));
      st.setProperty('--sub-color', s.subtitleColor);
      st.setProperty('--sub-opacity', ((s.subtitleOpacity || 100) / 100).toFixed(2));
      st.setProperty('--sub-bottom', s.subtitlePosition + '%');
      this.el.dataset.bg = s.subtitleBackground;
    }
    start() {
      const tick = () => { this.render(); this.raf = requestAnimationFrame(tick); };
      if (!this.raf) this.raf = requestAnimationFrame(tick);
    }
    stop() { cancelAnimationFrame(this.raf); this.raf = null; }
    render() {
      const t = this.getTime() - this.delay;
      let html = '';
      if (this.cues.length) {
        const active = [];
        for (const c of this.cues) {
          if (c.start > t) break;
          if (c.end >= t) active.push(c.html);
        }
        html = active.map(h => `<span class="cue">${h}</span>`).join('<br>');
      }
      if (html !== this.last) { this.el.innerHTML = html; this.last = html; }
    }
  }

  const TEXT_CODECS = ['srt', 'subrip', 'ass', 'ssa', 'vtt', 'webvtt', 'mov_text', 'tx3g', 'text', 'smi', 'eia_608'];
  const isTextSub = (s) => TEXT_CODECS.includes(String(s.codec || '').toLowerCase());

  window.Sofa = window.Sofa || {};
  Object.assign(window.Sofa, { Subtitles: { parse, SubtitleOverlay, isTextSub } });
})();
