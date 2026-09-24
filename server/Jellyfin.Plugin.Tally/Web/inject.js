/* JellyTV — Live TV takeover.
 *
 * Injected into jellyfin-web's index.html by the plugin (see Services/WebInjection.cs).
 * Whenever the web client is on its Live TV route — the "Live TV" card on the home
 * screen, the drawer link, a bookmark — JellyTV is mounted full-screen on top of it;
 * leaving the route (JellyTV's back button, browser back, any navigation) unmounts it.
 *
 * It deliberately does not use the plugin's configuration-page route: jellyfin-web
 * only lets administrators open plugin pages, and this has to work for every user. */
(function () {
  'use strict';
  if (window.__jtvInject) return;
  window.__jtvInject = true;

  // "#/livetv.html" (10.9/10.10), "#/livetv" (10.11), legacy "#!/livetv.html"; any query string.
  // Anchored on purpose: the dashboard's Live TV *settings* pages must stay reachable.
  const LIVETV = /^#!?\/livetv(\.html)?(\?.*)?$/i;

  let host = null;

  const signedIn = () => {
    try { return !!(window.ApiClient && ApiClient.accessToken && ApiClient.accessToken()); } catch (e) { return false; }
  };
  const asset = (f) => ApiClient.getUrl('JellyTV/Assets/' + f);

  function mount() {
    if (host || !signedIn()) return;   // not signed in yet: the poll below retries

    // A cached (hidden) copy of the admin plugin page shares JellyTV's element ids —
    // empty it so the app's id lookups can only ever hit the live instance.
    document.querySelectorAll('#jellytv-app').forEach((n) => { n.innerHTML = ''; delete n.dataset.jtvBooted; });

    if (!document.querySelector('link[data-jtv-css]')) {
      const link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = asset('app.css');
      link.setAttribute('data-jtv-css', '1');
      document.head.appendChild(link);
    }

    host = document.createElement('div');
    host.id = 'jellytv-app';
    host.className = 'jtv-root';
    host.setAttribute('data-jtv-overlay', '1');
    document.body.appendChild(host);

    window.__jtvMount = host;          // tells app.js which element to boot into
    const s = document.createElement('script');
    s.src = asset('app.js');
    s.onerror = unmount;
    document.head.appendChild(s);
  }

  function unmount() {
    if (!host) return;
    if (typeof window.__jtvCleanup === 'function') {
      try { window.__jtvCleanup(); } catch (e) { /* ignore */ }
      window.__jtvCleanup = null;
    }
    host.remove();
    host = null;
    window.__jtvMount = null;
  }

  const sync = () => (LIVETV.test(location.hash) ? mount() : unmount());

  // jellyfin-web routes with history.pushState, which fires no event of its own.
  ['pushState', 'replaceState'].forEach((fn) => {
    const original = history[fn];
    history[fn] = function () {
      const r = original.apply(this, arguments);
      setTimeout(sync, 0);
      return r;
    };
  });
  window.addEventListener('popstate', sync);
  window.addEventListener('hashchange', sync);
  setInterval(sync, 1000);             // safety net: sign-in completing, routers we didn't hook
  sync();
})();
