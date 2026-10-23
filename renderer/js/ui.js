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

  const LANGUAGES = [
    ['eng', 'English'], ['slv', 'Slovenian'], ['hrv', 'Croatian'], ['srp', 'Serbian'], ['bos', 'Bosnian'],
    ['ger', 'German'], ['fre', 'French'], ['spa', 'Spanish'], ['ita', 'Italian'], ['por', 'Portuguese'],
    ['dut', 'Dutch'], ['pol', 'Polish'], ['cze', 'Czech'], ['slo', 'Slovak'], ['hun', 'Hungarian'],
    ['rus', 'Russian'], ['ukr', 'Ukrainian'], ['swe', 'Swedish'], ['nor', 'Norwegian'], ['dan', 'Danish'],
    ['fin', 'Finnish'], ['gre', 'Greek'], ['tur', 'Turkish'], ['jpn', 'Japanese'], ['kor', 'Korean'],
    ['chi', 'Chinese'], ['ara', 'Arabic'], ['hin', 'Hindi']
  ];

  window.Sofa = window.Sofa || {};
  Object.assign(window.Sofa, { h, append, fmtTime, fmtDuration, epCode, toast, debounce, LANGUAGES });
})();
