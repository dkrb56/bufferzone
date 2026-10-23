// Small DOM helpers shared by the views and the player.
(function () {
  // h('div.card#id', { onclick, attrs }, ...children)
  function h(tag, props, ...children) {
    const [, name = 'div', rest = ''] = tag.match(/^([a-z0-9-]*)(.*)$/i);
    const el = document.createElement(name || 'div');
    for (const part of rest.match(/[.#][^.#]+/g) || []) {
      if (part[0] === '.') el.classList.add(part.slice(1));
      else el.id = part.slice(1);
    }
    if (props !== undefined && props !== null && (typeof props !== 'object' || props instanceof Node || Array.isArray(props))) {
      children.unshift(props);
      props = null;
    }
    for (const [k, v] of Object.entries(props || {})) {
      if (v === undefined || v === null || v === false) continue;
      if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k === 'html') el.innerHTML = v;
      else if (k in el && k !== 'list') el[k] = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    append(el, children);
    return el;
  }
  function append(el, children) {
    for (const c of children.flat(Infinity)) {
      if (c === null || c === undefined || c === false) continue;
      el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
  }

  function fmtTime(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    const hh = Math.floor(sec / 3600), mm = Math.floor((sec % 3600) / 60), ss = sec % 60;
    return hh ? `${hh}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}` : `${mm}:${String(ss).padStart(2, '0')}`;
  }
  function fmtDuration(ms) {
    const min = Math.round((ms || 0) / 60000);
    if (min < 60) return `${min} min`;
    return `${Math.floor(min / 60)} h ${min % 60} min`;
  }
  function epCode(ep) {
    if (ep.parentIndex == null || ep.index == null) return '';
    return `S${String(ep.parentIndex).padStart(2, '0')}E${String(ep.index).padStart(2, '0')}`;
  }

  let toastTimer;
  function toast(msg, ms = 3500) {
    const el = document.getElementById('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), ms);
  }

  function debounce(fn, ms) {
    let t;
    return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  }

  const ic = (name, opts) => window.Sofa.icon(name, opts);

  // btn('Play', { kind: 'primary', size: 'lg', icon: 'play', onclick })
  function btn(label, { kind = '', size = '', icon, full, onclick, title, disabled } = {}) {
    const cls = ['button.btn', kind, size, full ? 'full' : ''].filter(Boolean).join('.');
    return h(cls, { onclick, title, disabled }, icon ? ic(icon, { size: size === 'lg' ? 18 : 16 }) : null, label);
  }
  function iconBtn(name, { title, onclick, cls = '', size = 18, stroke } = {}) {
    return h('button.icon-btn' + (cls ? '.' + cls.split(' ').join('.') : ''), { title, onclick }, ic(name, { size, stroke }));
  }
  function pill(text, tone = '', { sm, dot = true } = {}) {
    return h('span.pill' + (tone ? '.' + tone : '') + (sm ? '.sm' : ''), dot ? h('span.dot') : null, text);
  }
  function switchEl(checked, onchange) {
    const input = h('input', { type: 'checkbox', checked, onchange: (e) => onchange(e.target.checked) });
    return h('label.switch', input, h('span.track'));
  }
  // options: [[value, label], ...]
  function selectEl(options, value, onchange) {
    const sel = h('select', { onchange: (e) => onchange(e.target.value) },
      options.map(([v, l]) => h('option', { value: v, selected: String(v) === String(value) }, l)));
    return h('div.select', sel, ic('chevron-down', { size: 14 }));
  }
  function checkEl(checked, label, onchange) {
    return h('label.check', h('input', { type: 'checkbox', checked, onchange: (e) => onchange(e.target.checked) }), label);
  }
  // Wraps every case-insensitive match of q in <mark>.
  function highlight(text, q) {
    text = String(text || '');
    if (!q) return [text];
    const out = [];
    const lower = text.toLowerCase(), ql = q.toLowerCase();
    let i = 0, j;
    while ((j = lower.indexOf(ql, i)) >= 0) {
      if (j > i) out.push(text.slice(i, j));
      out.push(h('mark', text.slice(j, j + q.length)));
      i = j + q.length;
    }
    if (i < text.length) out.push(text.slice(i));
    return out;
  }
  // PIN prompt. Resolves with the PIN, or null when cancelled. `check(pin)` returns an error text or null.
  function askPin(title, text, check) {
    return new Promise((resolve) => {
      const err = h('div.err');
      const input = h('input', { type: 'password', inputMode: 'numeric', maxLength: 4, autocomplete: 'off' });
      const close = (v) => { bg.remove(); resolve(v); };
      const submit = async () => {
        const pin = input.value.trim();
        if (pin.length !== 4) { err.textContent = 'Type the 4-digit PIN.'; return; }
        const problem = check ? await check(pin) : null;
        if (problem) { err.textContent = problem; input.select(); return; }
        close(pin);
      };
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); if (e.key === 'Escape') close(null); });
      const bg = h('div.modal-bg', h('div.modal',
        h('h2', title), h('p.muted', text), input, err,
        h('div.btn-row', { style: { justifyContent: 'flex-end' } }, btn('Cancel', { kind: 'ghost', onclick: () => close(null) }), btn('Continue', { kind: 'primary', onclick: submit }))));
      document.body.append(bg);
      input.focus();
    });
  }

  const LANGUAGES = [
    ['eng', 'English'], ['slv', 'Slovenian'], ['hrv', 'Croatian'], ['srp', 'Serbian'], ['bos', 'Bosnian'],
    ['ger', 'German'], ['fre', 'French'], ['spa', 'Spanish'], ['ita', 'Italian'], ['por', 'Portuguese'],
    ['dut', 'Dutch'], ['pol', 'Polish'], ['cze', 'Czech'], ['slo', 'Slovak'], ['hun', 'Hungarian'],
    ['rus', 'Russian'], ['ukr', 'Ukrainian'], ['swe', 'Swedish'], ['nor', 'Norwegian'], ['dan', 'Danish'],
    ['fin', 'Finnish'], ['gre', 'Greek'], ['tur', 'Turkish'], ['jpn', 'Japanese'], ['kor', 'Korean'],
    ['chi', 'Chinese'], ['ara', 'Arabic'], ['hin', 'Hindi']
  ];

  window.Sofa = window.Sofa || {};
  Object.assign(window.Sofa, { h, append, fmtTime, fmtDuration, epCode, toast, debounce, LANGUAGES, btn, iconBtn, pill, switchEl, selectEl, checkEl, highlight, askPin });
})();
