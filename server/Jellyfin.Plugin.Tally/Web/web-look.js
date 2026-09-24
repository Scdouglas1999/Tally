/* Tally look for the Jellyfin web client — the small script half (the look itself is web-look.css).
 *
 * Injected into jellyfin-web's index.html by the plugin (see Services/WebInjection.cs) when "Tally look for
 * Jellyfin web" is on. It only does what CSS cannot:
 *   - names the browser tab Tally (page titles keep their own name: "Dune · Tally"),
 *   - swaps jellyfin's favicon for the Tally lamp,
 *   - adds a Sports entry, which opens Tally (the Live TV takeover), to the side menu and the app bar.
 * Every hook looks for its element and skips quietly when it is not there, so a jellyfin-web version with a
 * different layout just gets less, never a broken page. */
(function () {
  'use strict';
  if (window.__tallyLook) return;
  window.__tallyLook = true;

  const script = document.currentScript;
  const sportsOn = !!(script && script.getAttribute('data-sports') === '1');
  const safe = (fn) => { try { fn(); } catch (e) { /* never let the look break the client */ } };

  // ---------- the browser tab: title and icon ----------
  const BRAND = 'Tally';
  const SUFFIX = ' · ' + BRAND;
  function fixTitle() {
    const t = (document.title || '').trim();
    let want = t;
    if (!t || t === 'Jellyfin') want = BRAND;
    else if (t !== BRAND && !t.endsWith(SUFFIX)) want = t.replace(/\bJellyfin\b/g, BRAND) + SUFFIX;
    if (want !== document.title) document.title = want;
  }
  function fixIcon() {
    document.querySelectorAll('link[rel~="icon"]:not([data-tally-look])').forEach((l) => l.remove());
  }

  // ---------- Sports: the entry that opens Tally ----------
  // 10.10 routes end in .html ("#/home.html"); 10.11 and 12 do not ("#/home"). Follow whatever the menu uses.
  const TROPHY = 'M19 5h-2V3H7v2H5c-1.1 0-2 .9-2 2v1c0 2.55 1.92 4.63 4.39 4.94.63 1.5 1.98 2.63 3.61 2.96V19H7v2h10v-2h-4v-3.1c1.63-.33 2.98-1.46 3.61-2.96C19.08 12.63 21 10.55 21 8V7c0-1.1-.9-2-2-2M5 8V7h2v3.82C5.84 10.4 5 9.3 5 8m14 0c0 1.3-.84 2.4-2 2.82V7h2z';
  const sportsHref = (sample) => (/\.html\b/.test(sample || '') ? '#/livetv.html' : '#/livetv');

  function legacyDrawer() {
    // jellyfin-web's own side menu (every version; hidden but present when 12's MUI menus are in use)
    const home = document.querySelector('.mainDrawer .navMenuOption[href^="#/home"]');
    if (!home || home.parentNode.querySelector(':scope > .tally-sports-link')) return;
    const a = document.createElement('a');
    a.className = 'navMenuOption lnkMediaFolder emby-button tally-sports-link';
    a.href = sportsHref(home.getAttribute('href'));
    a.setAttribute('data-itemid', 'tally-sports');
    const icon = document.createElement('span');
    icon.className = 'material-icons navMenuOptionIcon emoji_events';
    icon.setAttribute('aria-hidden', 'true');
    const text = document.createElement('span');
    text.className = 'navMenuOptionText';
    text.textContent = 'Sports';
    a.append(icon, text);
    home.after(a);
  }

  // MUI screens (10.11 "experimental" layout, 12.x): a copy of the Live TV entry, relabeled. The copy is a plain
  // link (React's handlers are not copied), so navigation is an ordinary hash change the router already follows.
  function muiCopy(link, extraClass) {
    const copy = link.cloneNode(true);
    copy.classList.add('tally-sports-link');
    if (extraClass) copy.classList.add(extraClass);
    copy.classList.remove('Mui-selected', 'MuiButton-colorPrimary', 'active');
    copy.removeAttribute('aria-current');
    copy.setAttribute('href', sportsHref(link.getAttribute('href')));
    copy.querySelectorAll('svg path').forEach((p, i) => { if (i === 0) p.setAttribute('d', TROPHY); else p.remove(); });
    copy.querySelectorAll('svg').forEach((s) => s.setAttribute('data-testid', 'EmojiEventsIcon'));
    // the label is the last text node (MUI buttons: icon span + text; list items: a Typography span)
    const label = copy.querySelector('.MuiListItemText-primary');
    if (label) label.textContent = 'Sports';
    else {
      const texts = [...copy.childNodes].filter((n) => n.nodeType === 3 && n.textContent.trim());
      if (texts.length) texts[texts.length - 1].textContent = 'Sports';
      else copy.append('Sports');
    }
    return copy;
  }

  function muiAppBar() {
    const bar = document.querySelector('.MuiAppBar-root');
    if (!bar || bar.querySelector('.tally-sports-link')) return;
    const live = bar.querySelector('a[href*="#/livetv"]');
    if (live) { live.before(muiCopy(live)); return; }
    // no Live TV library: copy the Favorites (or any library) link instead, right after the brand
    const any = bar.querySelector('.MuiToolbar-root .MuiStack-root > a[href*="#/"]:not(:first-child)');
    if (any) any.before(muiCopy(any));
  }

  function muiDrawer() {
    const paper = document.querySelector('.MuiDrawer-paper');
    if (!paper || paper.querySelector('.tally-sports-link')) return;
    const live = paper.querySelector('a[href*="#/livetv"]');
    const item = live && live.closest('li');
    if (!item) return;
    const li = item.cloneNode(false);
    li.className = item.className;
    li.append(muiCopy(live));
    // a plain link does not close the modal menu the way React's handler does
    li.addEventListener('click', () => {
      const backdrop = paper.parentNode && paper.parentNode.querySelector('.MuiBackdrop-root');
      if (backdrop) setTimeout(() => backdrop.click(), 0);
    });
    item.before(li);
  }

  function sports() {
    if (!sportsOn) return;
    safe(legacyDrawer);
    safe(muiAppBar);
    safe(muiDrawer);
  }

  // ---------- keep it applied: jellyfin-web re-renders menus and titles as it navigates ----------
  let queued = false;
  function apply() {
    queued = false;
    safe(fixTitle);
    safe(fixIcon);
    sports();
  }
  function schedule() {
    if (queued) return;
    queued = true;
    (window.requestAnimationFrame || setTimeout)(apply);
  }

  safe(() => {
    const title = document.querySelector('title');
    if (title) new MutationObserver(schedule).observe(title, { childList: true, characterData: true, subtree: true });
    new MutationObserver(schedule).observe(document.head, { childList: true });
    new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
  });
  window.addEventListener('hashchange', schedule);
  apply();
})();
