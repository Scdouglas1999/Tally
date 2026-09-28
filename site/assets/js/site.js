/* Tally website: one small script for every page. No libraries, no tracking.
   Everything here is progressive: without it the pages read the same, with static pictures. */
(function () {
  'use strict';

  var doc = document, root = doc.documentElement;
  var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!reduce) root.classList.add('motion');
  var motion = function () { return root.classList.contains('motion'); };
  var $ = function (sel, el) { return (el || doc).querySelector(sel); };
  var $$ = function (sel, el) { return Array.prototype.slice.call((el || doc).querySelectorAll(sel)); };
  var store = {
    get: function (k) { try { return sessionStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { sessionStorage.setItem(k, v); } catch (e) { /* private mode */ } }
  };
  var live = doc.createElement('div');
  live.className = 'visually-hidden'; live.setAttribute('aria-live', 'polite');
  doc.body.appendChild(live);
  var say = function (t) { live.textContent = ''; setTimeout(function () { live.textContent = t; }, 30); };

  /* ---------- mobile navigation ---------- */
  var menu = $('.menu-btn');
  if (menu) {
    var setNav = function (open) {
      root.classList.toggle('nav-open', open);
      menu.setAttribute('aria-expanded', open ? 'true' : 'false');
    };
    menu.addEventListener('click', function () { setNav(menu.getAttribute('aria-expanded') !== 'true'); });
    doc.addEventListener('keydown', function (e) { if (e.key === 'Escape' && root.classList.contains('nav-open')) { setNav(false); menu.focus(); } });
    $$('.nav a').forEach(function (a) { a.addEventListener('click', function () { setNav(false); }); });
    window.addEventListener('resize', function () { if (window.innerWidth > 940) setNav(false); });
  }

  /* ---------- reveal on scroll: only things below the fold start hidden, and only with motion ---------- */
  var reveals = $$('.reveal');
  if (motion() && 'IntersectionObserver' in window && reveals.length) {
    var vh = window.innerHeight;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.remove('pre'); en.target.classList.add('in'); io.unobserve(en.target); }
      });
    }, { rootMargin: '0px 0px -6% 0px', threshold: 0 });
    reveals.forEach(function (el) {
      if (el.getBoundingClientRect().top > vh * 0.94) { el.classList.add('pre'); io.observe(el); }
      else el.classList.add('in');
    });
    var showAll = function () { reveals.forEach(function (el) { el.classList.remove('pre'); el.classList.add('in'); }); };
    window.addEventListener('beforeprint', showAll);
    // A jump to an anchor lands content in view without a scroll the observer might miss.
    window.addEventListener('hashchange', function () { setTimeout(function () { reveals.forEach(function (el) { var r = el.getBoundingClientRect(); if (r.top < window.innerHeight && r.bottom > 0) { el.classList.remove('pre'); el.classList.add('in'); } }); }, 60); });
  } else {
    reveals.forEach(function (el) { el.classList.add('in'); });
  }

  /* ---------- latest version, live from GitHub, with the static text as fallback ---------- */
  var API = 'https://api.github.com/repos/Scdouglas1999/Tally/releases/latest';
  var applyRelease = function (rel) {
    if (!rel || !rel.tag) return;
    $$('[data-version]').forEach(function (el) { el.textContent = rel.version; });
    $$('[data-release-date]').forEach(function (el) { el.textContent = rel.date; });
    $$('.ver-chip').forEach(function (el) { el.classList.add('live'); el.setAttribute('title', 'Latest release, from GitHub'); });
    $$('[data-zip]').forEach(function (a) {
      var file = 'Tally-server-' + rel.version + '-jf' + a.getAttribute('data-zip') + '.zip';
      a.href = 'https://github.com/Scdouglas1999/Tally/releases/download/' + rel.tag + '/' + file;
      var s = $('small', a); if (s) s.textContent = file;
    });
  };
  if ($('[data-version]') && !window.TALLY_OFFLINE) {
    var cached = store.get('tally-release');
    if (cached) { try { applyRelease(JSON.parse(cached)); } catch (e) { /* ignore */ } }
    else if (window.fetch) {
      var ctl = window.AbortController ? new AbortController() : null;
      var to = setTimeout(function () { if (ctl) ctl.abort(); }, 6000);
      fetch(API, { headers: { Accept: 'application/vnd.github+json' }, signal: ctl ? ctl.signal : undefined })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          clearTimeout(to);
          if (!j || !j.tag_name) return;
          var v = String(j.name || j.tag_name).replace(/^tally-/, '').replace(/^v/, '');
          if (!/^\d+\.\d+\.\d+$/.test(v)) v = String(j.tag_name).replace(/^tally-v/, '');
          if (!/^\d+\.\d+\.\d+$/.test(v)) return;
          var d = j.published_at ? new Date(j.published_at) : null;
          var rel = { tag: j.tag_name, version: v, date: d ? d.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' }) : '' };
          store.set('tally-release', JSON.stringify(rel));
          applyRelease(rel);
        })
        .catch(function () { clearTimeout(to); });
    }
  }

  /* ---------- copy buttons ---------- */
  var copyText = function (text) {
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    return new Promise(function (res, rej) {
      var ta = doc.createElement('textarea'); ta.value = text; ta.setAttribute('readonly', '');
      ta.style.position = 'fixed'; ta.style.opacity = '0'; doc.body.appendChild(ta); ta.select();
      try { doc.execCommand('copy') ? res() : rej(); } catch (e) { rej(e); } finally { ta.remove(); }
    });
  };
  $$('[data-copy]').forEach(function (btn) {
    var label = $('.t', btn), orig = label ? label.textContent : '';
    btn.addEventListener('click', function () {
      var src = btn.getAttribute('data-copy');
      var text = src.charAt(0) === '#' ? ($(src) || {}).textContent : src;
      if (!text) return;
      copyText(text.trim()).then(function () {
        btn.classList.add('done'); if (label) label.textContent = 'Copied';
        say('Copied to the clipboard');
        clearTimeout(btn._t); btn._t = setTimeout(function () { btn.classList.remove('done'); if (label) label.textContent = orig; }, 2200);
      }, function () { say('Copy failed. Select the text and copy it by hand.'); });
    });
  });

  /* ---------- rolling numbers (the scoreboard flip) ---------- */
  function roll(el, val, instant) {
    if (!el) return;
    val = String(val);
    var cur = el.getAttribute('data-v');
    if (cur === val && !instant) return;
    el.setAttribute('data-v', val);
    clearTimeout(el._t);
    if (instant || !motion() || cur === null) { el.innerHTML = '<span>' + val + '</span>'; el.classList.remove('go'); return; }
    el.innerHTML = '<span class="old">' + cur + '</span><span class="new">' + val + '</span>';
    el.classList.remove('go'); void el.offsetWidth; el.classList.add('go');
    el._t = setTimeout(function () { el.innerHTML = '<span>' + val + '</span>'; el.classList.remove('go'); }, 900);
  }
  $$('.roll').forEach(function (el) { if (!el.hasAttribute('data-v')) el.setAttribute('data-v', el.textContent.trim()); });

  /* ---------- a small timeline engine for the TV demos ---------- */
  function Timeline(opts) {
    // opts: { el, length, events: [{t, fn(instant)}], reset(), onTick(ms), restAt }
    var self = this, t0 = 0, at = 0, raf = 0, running = false, userPaused = false, inView = true, pageVisible = !doc.hidden;
    var events = opts.events.slice().sort(function (a, b) { return a.t - b.t; });
    var next = 0;
    function seek(ms) {
      opts.el.classList.add('snap');
      opts.reset();
      next = 0;
      while (next < events.length && events[next].t <= ms) { events[next].fn(true); next++; }
      at = ms; t0 = performance.now() - ms;
      void opts.el.offsetWidth; opts.el.classList.remove('snap');
      if (opts.onTick) opts.onTick(ms);
    }
    function frame(now) {
      if (!running) return;
      var ms = now - t0;
      if (ms >= opts.length) { seek(0); ms = 0; }
      while (next < events.length && events[next].t <= ms) { events[next].fn(false); next++; }
      at = ms;
      if (opts.onTick) opts.onTick(ms);
      raf = requestAnimationFrame(frame);
    }
    function update() {
      var should = motion() && !userPaused && inView && pageVisible;
      if (should && !running) { running = true; t0 = performance.now() - at; raf = requestAnimationFrame(frame); }
      else if (!should && running) { running = false; cancelAnimationFrame(raf); }
    }
    self.seek = function (ms) { seek(ms); update(); };
    self.setPaused = function (p) { userPaused = p; update(); };
    self.paused = function () { return userPaused; };
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (en) { inView = en[0].isIntersecting; update(); }, { threshold: 0.15 }).observe(opts.el);
    }
    doc.addEventListener('visibilitychange', function () { pageVisible = !doc.hidden; update(); });
    seek(motion() ? 0 : (opts.restAt || 0));
    update();
  }

  var stepFill = function (steps, bounds, ms) {
    steps.forEach(function (s, i) {
      var a = bounds[i], b = bounds[i + 1];
      var on = ms >= a && ms < b;
      s.classList.toggle('past', ms >= b);
      if (on) s.setAttribute('aria-current', 'step'); else s.removeAttribute('aria-current');
      var f = $('.fill', s);
      if (f) f.style.width = on ? (Math.min(1, (ms - a) / (b - a)) * 100).toFixed(1) + '%' : '';
    });
  };

  var setText = function (el, text, instant) {
    if (!el || el.textContent === text) return;
    el.textContent = text;
    if (!instant && motion() && el.animate) el.animate([{ opacity: 0, transform: 'translateY(4px)' }, { opacity: 1, transform: 'none' }], { duration: 420, easing: 'cubic-bezier(.2,.7,.1,1)' });
  };
  var flag = function (el, cls, on) { el.classList.toggle(cls, on); };

  /* ---------- the hero: board → score → bug → Pulse ---------- */
  var hero = $('#hero-tv');
  if (hero) {
    var q = function (s) { return $(s, hero); };
    var clock = q('[data-clock]');
    var tick = function () {
      if (!clock) return;
      clock.textContent = new Date().toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
    };
    tick(); setInterval(tick, 20000);
    var bases = function (on) { $$('.bases i', hero).forEach(function (b) { b.classList.toggle('on', on.indexOf(b.getAttribute('data-b')) >= 0); }); };
    var line7 = q('[data-l7]');
    var reset = function () {
      hero.setAttribute('data-scene', 'board');
      ['bug-in', 'bar-in', 'l3-in', 'tuning', 'press', 'wiping'].forEach(function (c) { hero.classList.remove(c); });
      $$('[data-roll-init]', hero).forEach(function (el) { roll(el, el.getAttribute('data-roll-init'), true); el.classList.remove('hot'); });
      setText(q('[data-count]'), '2-1 · 1 OUT', true);
      setText(q('[data-lastplay]'), 'R. Whitcombe walks. D. Castellano to third.', true);
      bases(['1', '3']);
      if (line7) { line7.textContent = '0'; line7.classList.remove('hot'); }
      setText(q('[data-fb-l2]'), '3RD 8:42 · 2ND & 7 AT CSC 14', true);
      var why = q('[data-why]'); setText(why, 'RED ZONE', true); why.classList.remove('flash');
    };
    var steps = $$('.tv-step', hero.parentNode.parentNode);
    var bounds = [0, 2400, 4800, 9300, 17000];
    var tl = new Timeline({
      el: hero, length: 17000, restAt: 12200, reset: reset,
      onTick: function (ms) { stepFill(steps, bounds, ms); },
      events: [
        { t: 2400, fn: function (i) {
          $$('[data-home-score]', hero).forEach(function (el) { roll(el, '4', i); el.classList.add('hot'); });
          setText(q('[data-count]'), '0-0 · 1 OUT', i);
          setText(q('[data-lastplay]'), 'K. Nakamura singles to center, D. Castellano scores.', i);
          bases(['1', '2']);
          if (line7) { line7.textContent = '1'; line7.classList.add('hot'); }
        } },
        { t: 4200, fn: function () { flag(hero, 'press', true); } },
        { t: 4800, fn: function () { flag(hero, 'press', false); flag(hero, 'tuning', true); } },
        { t: 5500, fn: function () { hero.setAttribute('data-scene', 'play'); flag(hero, 'tuning', false); flag(hero, 'bar-in', true); } },
        { t: 6100, fn: function () { flag(hero, 'bug-in', true); } },
        { t: 8300, fn: function () { flag(hero, 'bar-in', false); } },
        { t: 9300, fn: function (i) { flag(hero, 'bug-in', false); if (!i) flag(hero, 'wiping', true); } },
        { t: 9650, fn: function () { hero.setAttribute('data-scene', 'pulse'); } },
        { t: 10100, fn: function () { flag(hero, 'wiping', false); flag(hero, 'l3-in', true); } },
        { t: 10600, fn: function () { flag(hero, 'bug-in', true); } },
        { t: 12900, fn: function (i) {
          var s = q('[data-fb-away]'); roll(s, '16', i); s.classList.add('hot');
          setText(q('[data-fb-l2]'), '3RD 8:31 · TOUCHDOWN BWB', i);
          var why = q('[data-why]'); setText(why, 'SCORE', i); if (!i) { why.classList.remove('flash'); void why.offsetWidth; why.classList.add('flash'); }
        } },
        { t: 16000, fn: function () { flag(hero, 'l3-in', false); flag(hero, 'bug-in', false); } },
        { t: 16750, fn: function () { reset(); } }
      ]
    });
    steps.forEach(function (s, i) {
      s.addEventListener('click', function () { tl.seek(motion() ? bounds[i] : [0, 3000, 7000, 12200][i]); });
    });
    var pause = $('.tv-pause', hero.parentNode.parentNode);
    if (pause) {
      if (!motion()) pause.hidden = true;
      pause.addEventListener('click', function () {
        var p = !tl.paused(); tl.setPaused(p);
        pause.setAttribute('aria-pressed', p ? 'true' : 'false');
        pause.setAttribute('aria-label', p ? 'Play the demo' : 'Pause the demo');
      });
    }
  }

  /* ---------- Pulse demo (live sports page) ---------- */
  var wa = $('#pulse-tv');
  if (wa) {
    var w = function (s) { return $(s, wa); };
    var games = [
      { img: 'a', title: 'Bison at Captains', why: 'RED ZONE', l1: ['BWB', '10', 'CSC', '17'], l2: '3RD 8:42 · 2ND & 7 AT CSC 14' },
      { img: 'b', title: 'Foxes at Gulls', why: 'CLOSE GAME', l1: ['RDG', '3', 'HCG', '4'], l2: 'BOT 7TH · 0-0 · 1 OUT' }
    ];
    var show = function (g, instant) {
      wa.setAttribute('data-foot', g.img);
      setText(w('[data-w-title]'), g.title, instant);
      setText(w('[data-w-why]'), g.why, instant);
      w('[data-w-a]').textContent = g.l1[0]; roll(w('[data-w-as]'), g.l1[1], true);
      w('[data-w-h]').textContent = g.l1[2]; roll(w('[data-w-hs]'), g.l1[3], true);
      setText(w('[data-w-l2]'), g.l2, true);
      w('[data-w-as]').classList.remove('hot');
    };
    new Timeline({
      el: wa, length: 14000, restAt: 2000,
      reset: function () { wa.setAttribute('data-scene', 'pulse'); show(games[0], true); wa.classList.add('l3-in', 'bug-in'); wa.classList.remove('wiping'); },
      events: [
        { t: 3200, fn: function (i) { var s = w('[data-w-as]'); roll(s, '16', i); s.classList.add('hot'); setText(w('[data-w-l2]'), '3RD 8:31 · TOUCHDOWN BWB', i); setText(w('[data-w-why]'), 'SCORE', i); } },
        { t: 6400, fn: function (i) { wa.classList.remove('l3-in', 'bug-in'); if (!i) wa.classList.add('wiping'); } },
        { t: 6750, fn: function (i) { show(games[1], i); } },
        { t: 7300, fn: function () { wa.classList.remove('wiping'); wa.classList.add('l3-in'); } },
        { t: 7700, fn: function () { wa.classList.add('bug-in'); } },
        { t: 13000, fn: function () { wa.classList.remove('l3-in', 'bug-in'); } },
        { t: 13600, fn: function (i) { if (!i) wa.classList.add('wiping'); } }
      ]
    });
  }

  /* ---------- scoreboard ticker ---------- */
  $$('.ticker').forEach(function (tk) {
    var track = $('.ticker-track', tk);
    if (!track) return;
    var clone = track.cloneNode(true);
    $$('.tk', clone).forEach(function (n) { n.setAttribute('aria-hidden', 'true'); track.appendChild(n); });
    $$('.roll', track).forEach(function (el) { el.setAttribute('data-v', el.textContent.trim()); });
    var setSpeed = function () { tk.style.setProperty('--crawl', Math.max(40, track.scrollWidth / 2 / 55) + 's'); };
    setSpeed(); window.addEventListener('resize', setSpeed);
    var btn = $('.ticker-pause', tk);
    if (btn) btn.addEventListener('click', function () {
      var p = !tk.classList.contains('paused'); tk.classList.toggle('paused', p);
      btn.setAttribute('aria-pressed', p ? 'true' : 'false'); btn.setAttribute('aria-label', p ? 'Play the scores' : 'Pause the scores');
    });
    if (!motion()) return;
    // Now and then a team scores: the same number rolls in both copies of the crawl.
    var scorers = $$('.tk[data-sport]:not([aria-hidden]) .s', track).filter(function (s) { return !s.closest('[data-final]'); });
    setInterval(function () {
      if (doc.hidden || tk.classList.contains('paused') || !scorers.length) return;
      var s = scorers[Math.floor(Math.random() * scorers.length)];
      var item = s.closest('.tk'), sport = item.getAttribute('data-sport');
      var inc = sport === 'football' ? (Math.random() < .7 ? 3 : 7) : sport === 'basketball' ? (Math.random() < .6 ? 2 : 3) : 1;
      var idx = $$('.s', item).indexOf(s), key = item.getAttribute('data-key');
      var nv = String(parseInt(s.textContent, 10) + inc);
      if (parseInt(nv, 10) > 60) return;
      $$('.tk[data-key="' + key + '"]', track).forEach(function (it) { var t = $$('.s', it)[idx]; if (t) { roll(t, nv); t.classList.add('hot'); setTimeout(function () { t.classList.remove('hot'); }, 2600); } });
    }, 4200);
  });

  /* ---------- features in motion: a rundown that plays itself ---------- */
  $$('.fx').forEach(function (fx) {
    var items = $$('.fx-item', fx), panels = $$('.fx-panel', fx), cap = $('.fx-cap .t', fx), chan = $('.fx-cap .ch', fx);
    var cur = 0, started = 0, raf = 0, hover = false, visible = false, DUR = 6500;
    var select = function (i, fromUser) {
      cur = (i + items.length) % items.length;
      items.forEach(function (it, j) {
        var on = j === cur;
        it.setAttribute('aria-selected', on ? 'true' : 'false');
        it.tabIndex = on ? 0 : -1;
        var b = $('.bar i', it); if (b) b.style.width = on ? '0%' : '';
      });
      panels.forEach(function (p, j) {
        var on = j === cur; p.classList.toggle('on', on);
        var v = $('video', p);
        if (v) { if (on && motion() && visible) { v.currentTime = 0; v.play().catch(function () {}); } else v.pause(); }
      });
      if (cap) cap.textContent = items[cur].getAttribute('data-cap') || '';
      if (chan) chan.textContent = 'CH ' + String(cur + 1).padStart(2, '0');
      started = performance.now();
      if (fromUser) items[cur].focus();
    };
    items.forEach(function (it, i) {
      it.addEventListener('click', function () { select(i); });
      it.addEventListener('keydown', function (e) {
        var k = e.key;
        if (k === 'ArrowDown' || k === 'ArrowRight') { e.preventDefault(); select(cur + 1, true); }
        else if (k === 'ArrowUp' || k === 'ArrowLeft') { e.preventDefault(); select(cur - 1, true); }
        else if (k === 'Home') { e.preventDefault(); select(0, true); }
        else if (k === 'End') { e.preventDefault(); select(items.length - 1, true); }
      });
    });
    fx.addEventListener('mouseenter', function () { hover = true; });
    fx.addEventListener('mouseleave', function () { hover = false; started = performance.now() - (lastP * DUR); });
    fx.addEventListener('focusin', function () { hover = true; });
    fx.addEventListener('focusout', function () { hover = false; });
    var lastP = 0;
    var loop = function (now) {
      raf = requestAnimationFrame(loop);
      if (!motion() || hover || !visible || doc.hidden) { started = now - lastP * DUR; return; }
      var p = (now - started) / DUR; lastP = Math.min(p, 1);
      var b = $('.bar i', items[cur]); if (b) b.style.width = (lastP * 100).toFixed(1) + '%';
      if (p >= 1) { lastP = 0; select(cur + 1); }
    };
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (en) {
        visible = en[0].isIntersecting;
        var v = $('.fx-panel.on video', fx);
        if (v) { if (visible && motion()) v.play().catch(function () {}); else v.pause(); }
      }, { threshold: 0.25 }).observe(fx);
    } else visible = true;
    select(0);
    if (motion()) raf = requestAnimationFrame(loop);
  });

  /* ---------- videos: play only when on screen, never with reduced motion ---------- */
  $$('video[data-inview]').forEach(function (v) {
    var btn = v.parentNode.querySelector('.play-toggle');
    var userPaused = !motion();
    var sync = function () { if (btn) btn.textContent = v.paused ? 'Play' : 'Pause'; };
    v.addEventListener('play', sync); v.addEventListener('pause', sync);
    if (btn) btn.addEventListener('click', function () { if (v.paused) { userPaused = false; v.play().catch(function () {}); } else { userPaused = true; v.pause(); } });
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (en) {
        if (en[0].isIntersecting && !userPaused) v.play().catch(function () {}); else v.pause();
      }, { threshold: 0.3 }).observe(v);
    }
    sync();
  });

  /* ---------- hotspots on the games board ---------- */
  $$('.hotspots').forEach(function (box) {
    var list = doc.getElementById(box.getAttribute('data-list'));
    if (!list) return;
    var lis = $$('li', list), spots = $$('.hs', box);
    var light = function (i) {
      lis.forEach(function (li, j) { li.classList.toggle('lit', j === i); });
      spots.forEach(function (s, j) { s.classList.toggle('dim', i >= 0 && j !== i); s.setAttribute('aria-pressed', j === i ? 'true' : 'false'); });
    };
    spots.forEach(function (s, i) {
      s.addEventListener('mouseenter', function () { light(i); });
      s.addEventListener('focus', function () { light(i); });
      s.addEventListener('click', function () { light(i); });
      s.addEventListener('mouseleave', function () { light(-1); });
      s.addEventListener('blur', function () { light(-1); });
    });
    lis.forEach(function (li, i) { li.addEventListener('mouseenter', function () { light(i); }); li.addEventListener('mouseleave', function () { light(-1); }); });
  });

  /* ---------- architecture: the legend lights its part of the diagram ---------- */
  $$('[data-arch-legend]').forEach(function (leg) {
    var arch = doc.getElementById(leg.getAttribute('data-arch-legend'));
    if (!arch) return;
    $$('[data-part]', leg).forEach(function (d) {
      var p = d.getAttribute('data-part');
      var on = function () { arch.classList.add('focus-' + p); d.classList.add('lit'); };
      var off = function () { arch.classList.remove('focus-' + p); d.classList.remove('lit'); };
      d.addEventListener('mouseenter', on); d.addEventListener('mouseleave', off);
      d.addEventListener('focusin', on); d.addEventListener('focusout', off);
    });
  });
  if (!motion()) $$('.arch svg').forEach(function (s) { if (s.pauseAnimations) s.pauseAnimations(); });

  /* ---------- situations that cycle on a score bug ---------- */
  $$('[data-sitbug]').forEach(function (bug) {
    var sets = [
      { a: 'BWB', as: '10', h: 'CSC', hs: '17', l2: '3RD 8:42 · 2ND & 7 AT CSC 14', tag: 'RED ZONE', cls: 'live' },
      { a: 'RDG', as: '3', h: 'HCG', hs: '4', l2: 'BOT 9TH · 3-2 · 2 OUT', tag: 'BASES LOADED', cls: 'live' },
      { a: '(4) NTU', as: '20', h: 'LKS', hs: '24', l2: '4TH 1:52 · 1ST & 10 AT NTU 41', tag: 'UPSET ALERT', cls: 'live' },
      { a: 'EVC', as: '4', h: 'MBH', hs: '4', l2: 'TOP 10TH · 1-1 · 0 OUT', tag: 'EXTRA INNINGS', cls: 'accent' },
      { a: 'KRT', as: '101', h: 'SPR', hs: '99', l2: '4TH 0:41', tag: 'CLUTCH TIME', cls: 'live' },
      { a: 'ALB', as: '2', h: 'FJD', hs: '2', l2: 'OT 3:10', tag: 'OVERTIME', cls: 'accent' }
    ];
    var f = function (s) { return $('[data-f="' + s + '"]', bug); };
    var i = 0;
    var show = function (instant) {
      var s = sets[i];
      f('a').textContent = s.a; f('h').textContent = s.h;
      roll(f('as'), s.as, instant); roll(f('hs'), s.hs, instant);
      setText(f('l2'), s.l2, instant);
      var t = f('tag'); t.textContent = s.tag; t.className = 'tag ' + s.cls;
    };
    show(true);
    if (!motion()) return;
    setInterval(function () { if (doc.hidden) return; i = (i + 1) % sets.length; show(false); }, 3200);
  });

  /* ---------- the stream ladder: one stream stalls, the next picks up, the first comes back ---------- */
  $$('[data-ladder]').forEach(function (lad) {
    var rungs = $$('.rung', lad), log = $('.log-line', lad), outSeg = $('.out .v', lad);
    var N = 16, beat = 0;
    rungs.forEach(function (r) { var seg = $('.seg', r); for (var k = 0; k < N; k++) seg.appendChild(doc.createElement('i')); });
    var script = [ // which rung plays, and the health of each rung, beat by beat
      { on: 0, bad: [], msg: 'Playing 1080p60 · 7.8 Mbps · arriving steadily' },
      { on: 0, bad: [], msg: '' },
      { on: 0, bad: [0], msg: '1080p60: a segment slower than real time' },
      { on: 0, bad: [0], msg: '1080p60: two slow segments in a row' },
      { on: 1, bad: [0], msg: 'Switched to 1080p30 · no reload, picture kept going' },
      { on: 1, bad: [0], msg: '' },
      { on: 1, bad: [], msg: '1080p60 steady again · waiting three stable minutes' },
      { on: 1, bad: [], msg: '' },
      { on: 0, bad: [], msg: 'Stepped back up to 1080p60' },
      { on: 0, bad: [], msg: '' }
    ];
    var render = function () {
      var s = script[beat % script.length];
      rungs.forEach(function (r, j) {
        var segs = $$('.seg i', r);
        segs.forEach(function (x, k) { if (k < N - 1) x.className = segs[k + 1].className; });
        segs[N - 1].className = s.bad.indexOf(j) >= 0 ? 'late' : 'ok';
        r.classList.toggle('on', j === s.on);
        r.classList.toggle('bad', s.bad.indexOf(j) >= 0);
        var st = $('.st', r); st.textContent = j === s.on ? 'On air' : s.bad.indexOf(j) >= 0 ? 'Late' : 'Ready';
      });
      if (outSeg) outSeg.textContent = rungs[s.on].getAttribute('data-name');
      if (s.msg) setText(log, s.msg, false);
      beat++;
    };
    for (var k = 0; k < N; k++) render(); // fill the history so it reads at rest
    beat = 0; render();
    if (!motion()) return;
    setInterval(function () { if (!doc.hidden) render(); }, 1300);
  });

  /* ---------- commentary language mock ---------- */
  $$('[data-lang-swap]').forEach(function (el) {
    if (!motion()) return;
    var vals = ['ESPAÑOL', 'ENGLISH'], i = 0;
    setInterval(function () { if (doc.hidden) return; i = 1 - i; setText(el, vals[i], false); }, 2600);
  });
})();
