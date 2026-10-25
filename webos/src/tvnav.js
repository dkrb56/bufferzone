// Remote-control navigation for webOS: the arrows move the focus to the nearest control in that
// direction, OK clicks it, Back closes the top layer or goes back, the media keys control the player.
(function () {
  const KEY = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 461, ESC: 27, PLAY: 415, PAUSE: 19, PLAYPAUSE: 10252, STOP: 413, FF: 417, RW: 412 };
  const DIRS = { 37: 'left', 38: 'up', 39: 'right', 40: 'down' };
  const FOCUSABLE = 'button:not([disabled]), a[href], input:not([type=hidden]), select, textarea, [tabindex]:not([tabindex="-1"]), label.switch';
  let lastRect = null;

  const app = () => window.app;
  const on = (el) => el && el.classList.contains('on');

  // The layer that takes the remote: the newest dialog, then the search, the player, Multi-view, the page.
  function layer() {
    const modals = document.querySelectorAll('.modal-bg');
    if (modals.length) return modals[modals.length - 1];
    const pal = document.getElementById('palette');
    if (on(pal)) return pal;
    const player = document.getElementById('player');
    if (on(player)) {
      const pop = player.querySelector('.p-pop');
      return on(pop) ? pop : player;
    }
    const mv = document.getElementById('multiview');
    if (on(mv)) return mv;
    return document.body;
  }

  function visible(el) {
    if (!el.getClientRects().length) return false;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const st = getComputedStyle(el);
    return st.visibility !== 'hidden' && st.opacity !== '0';
  }

  function candidates(root) {
    return Array.from(root.querySelectorAll(FOCUSABLE)).filter(el => {
      if (el.closest('.p-controls') && !document.getElementById('player').classList.contains('show-ui') && !el.closest('.p-pop')) return false;
      if (el.matches('input[type=checkbox]') && el.closest('label.switch')) return false;
      return visible(el);
    });
  }

  function focusEl(el) {
    if (!el) return;
    if (el.matches('label.switch') && el.tabIndex < 0) el.tabIndex = 0;
    el.focus({ preventScroll: true });
    reveal(el);
    lastRect = el.getBoundingClientRect();
  }

  // Keep the focused control in view, away from the edges and from the sticky page head.
  // Only client rects are compared (no scrollTop maths), so the page zoom does not matter.
  function reveal(el) {
    el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    let sc = el.parentElement;
    while (sc && sc !== document.body && !(/(auto|scroll)/.test(getComputedStyle(sc).overflowY) && sc.scrollHeight > sc.clientHeight)) sc = sc.parentElement;
    if (!sc || sc === document.body) return;
    const c = sc.getBoundingClientRect(), r = el.getBoundingClientRect();
    const head = sc.querySelector('.page-head');
    const headH = head && !head.contains(el) ? head.getBoundingClientRect().height : 0;
    const margin = c.height * 0.08;
    if (r.top - c.top < headH + margin || c.bottom - r.bottom < margin) el.scrollIntoView({ block: 'center', inline: 'nearest' });
  }

  // Score = distance in the arrow direction + 2 × sideways gap. A control that overlaps sideways wins.
  function best(from, list, dir) {
    let pick = null, score = Infinity;
    for (const el of list) {
      const r = el.getBoundingClientRect();
      let main, side;
      if (dir === 'right') { main = r.left - from.right; side = gap(r.top, r.bottom, from.top, from.bottom); }
      else if (dir === 'left') { main = from.left - r.right; side = gap(r.top, r.bottom, from.top, from.bottom); }
      else if (dir === 'down') { main = r.top - from.bottom; side = gap(r.left, r.right, from.left, from.right); }
      else { main = from.top - r.bottom; side = gap(r.left, r.right, from.left, from.right); }
      if (main < -4) continue;
      const s = Math.max(0, main) + side * 2;
      if (s < score) { score = s; pick = el; }
    }
    return pick;
  }
  function gap(a1, a2, b1, b2) { return Math.max(0, Math.max(a1, b1) - Math.min(a2, b2)); }

  function move(dir) {
    const root = layer();
    const list = candidates(root);
    if (!list.length) return false;
    const cur = document.activeElement;
    if (!cur || cur === document.body || !root.contains(cur) || !list.includes(cur)) {
      // Nothing focused yet (new page or closed dialog): start near the last place, else top left of the page.
      const main = document.getElementById('main');
      const pool = root === document.body ? (list.filter(el => main.contains(el)).length ? list.filter(el => main.contains(el)) : list) : list;
      const ref = lastRect && root === document.body ? lastRect : { left: 0, top: 0, right: 0, bottom: 0 };
      let pick = pool[0], d = Infinity;
      for (const el of pool) {
        const r = el.getBoundingClientRect();
        const dd = Math.abs(r.left - ref.left) + Math.abs(r.top - ref.top) * 1.5;
        if (dd < d) { d = dd; pick = el; }
      }
      focusEl(pick);
      return true;
    }
    const next = best(cur.getBoundingClientRect(), list.filter(el => el !== cur && !el.contains(cur) && !cur.contains(el)), dir);
    if (next) focusEl(next);
    // Up from the top row of the player (the back arrow) hides the controls.
    else if (dir === 'up' && root.id === 'player') { root.classList.remove('show-ui'); cur.blur(); }
    return true;
  }

  function back() {
    const a = app();
    const modals = document.querySelectorAll('.modal-bg');
    if (modals.length) {
      const m = modals[modals.length - 1];
      const cancel = Array.from(m.querySelectorAll('button')).find(b => /^(cancel|close|not now)$/i.test(b.textContent.trim()));
      if (cancel) cancel.click(); else m.remove();
      return;
    }
    if (!a) return;
    if (a.palette && a.palette.isOpen) return a.palette.close();
    if (on(document.getElementById('player'))) {
      if (on(a.player.pop)) return a.player.closePop();
      return a.player.exit();
    }
    if (a.multiview && a.multiview.isOpen) return a.multiview.close();
    if (a.history && a.history.length) return a.back();
    if (window.Sofa && Sofa.askConfirm) Sofa.askConfirm('Exit Buffer Zone?', 'The app closes.', 'Exit').then(ok => { if (ok) window.close(); });
  }

  function media(code) {
    const a = app();
    if (!a || !on(document.getElementById('player'))) return false;
    const p = a.player, v = p.video;
    if (code === KEY.PLAY) v.play();
    else if (code === KEY.PAUSE) v.pause();
    else if (code === KEY.PLAYPAUSE) p.togglePlay();
    else if (code === KEY.FF) { p.seekBy(p.skipForward); return true; }   // seeking keeps the controls hidden
    else if (code === KEY.RW) { p.seekBy(-p.skipBack); return true; }
    else if (code === KEY.STOP) p.exit();
    else return false;
    p.poke();
    return true;
  }

  // Back and the media keys come first, before the page handlers.
  window.addEventListener('keydown', (e) => {
    const code = e.keyCode;
    if (code === KEY.BACK) { e.preventDefault(); e.stopPropagation(); back(); return; }
    if ([KEY.PLAY, KEY.PAUSE, KEY.PLAYPAUSE, KEY.STOP, KEY.FF, KEY.RW].includes(code) && media(code)) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  // The arrows and OK come last: the search box, the player and the dialogs handle their own keys first.
  window.addEventListener('keydown', (e) => {
    if (e.defaultPrevented) return;
    const code = e.keyCode, t = e.target;
    const dir = DIRS[code];
    if (dir) {
      const text = t.tagName === 'TEXTAREA' || (t.tagName === 'INPUT' && !/^(checkbox|radio|range|button)$/.test(t.type));
      if (text && (dir === 'left' || dir === 'right')) {
        // Move the caret inside the text; leave the field only at its ends.
        try {
          const atStart = t.selectionStart === 0 && t.selectionEnd === 0, atEnd = t.selectionStart === t.value.length;
          if ((dir === 'left' && !atStart) || (dir === 'right' && !atEnd)) return;
        } catch (err) {}
      }
      // A slider changes its value only after OK (adjust mode); otherwise the arrows move on, so it cannot trap the focus.
      if (t.type === 'range' && t.classList.contains('adjusting')) {
        if (dir === 'left' || dir === 'right') return;
        t.classList.remove('adjusting');
      }
      if (move(dir)) e.preventDefault();
      return;
    }
    if (code === KEY.ENTER && t.type === 'range') {
      e.preventDefault();
      t.classList.toggle('adjusting');
      return;
    }
    // OK clicks the focused control. Lists and text fields keep their own OK behaviour.
    if (code === KEY.ENTER && t !== document.body && t.click) {
      const text = t.tagName === 'TEXTAREA' || (t.tagName === 'INPUT' && !/^(checkbox|radio|button|submit)$/.test(t.type));
      if (t.tagName === 'SELECT' || text) return;
      e.preventDefault();
      t.click();
    }
  });

  // A closed dialog or a new page removes the focused control; the next arrow press finds a new one.
  document.addEventListener('focusin', (e) => { if (e.target && e.target.getBoundingClientRect) lastRect = e.target.getBoundingClientRect(); });
  document.addEventListener('focusout', (e) => { if (e.target && e.target.classList) e.target.classList.remove('adjusting'); });

  // A new page with a main button (Play, Resume, Sign in…) puts the focus on it.
  let pending = null;
  document.addEventListener('DOMContentLoaded', () => {
    new MutationObserver(() => {
      clearTimeout(pending);
      pending = setTimeout(() => {
        const cur = document.activeElement;
        if (layer() !== document.body || (cur && cur !== document.body && cur.isConnected)) return;
        const primary = document.querySelector('#main .btn.primary');
        if (primary && visible(primary)) focusEl(primary);
      }, 250);
    }).observe(document.getElementById('main'), { childList: true, subtree: true });
  });
})();
