/* JellyTV — jellyfin-web view controller.
 *
 * jellyfin-web does not execute inline <script> in plugin configuration pages.
 * Instead it reads data-controller="__plugin/<pageName>" from the page element
 * and dynamically imports /web/configurationpage?name=<pageName> as an ES
 * module, then instantiates the module's default export with `new` on every
 * view load. This module is that entry point: it injects the app stylesheet
 * and script, and manages teardown/reboot around jellyfin-web's view cache
 * (views are hidden and restored rather than always destroyed). */
export default function (view) {
  const doc = view.ownerDocument;
  const head = doc.head || doc.getElementsByTagName('head')[0];
  const asset = (f) => (window.ApiClient && ApiClient.getUrl)
    ? ApiClient.getUrl('JellyTV/Assets/' + f)
    : 'JellyTV/Assets/' + f;

  if (!doc.querySelector('link[data-jtv-css]')) {
    const link = doc.createElement('link');
    link.rel = 'stylesheet';
    link.href = asset('app.css');
    link.setAttribute('data-jtv-css', '1');
    head.appendChild(link);
  }

  let injected = false;
  const inject = () => {
    if (injected || view.dataset.jtvBooted) return;
    injected = true;
    const s = doc.createElement('script');
    s.src = asset('app.js');
    s.onerror = () => { injected = false; };
    head.appendChild(s);
  };

  const teardown = () => {
    if (typeof window.__jtvCleanup === 'function') {
      try { window.__jtvCleanup(); } catch (e) { /* ignore */ }
      window.__jtvCleanup = null;
    }
    delete view.dataset.jtvBooted;
    injected = false;
  };

  // Stop streams/timers whenever the view leaves the screen, whether it is
  // destroyed outright or parked hidden in the view cache.
  view.addEventListener('viewbeforehide', teardown);
  view.addEventListener('viewdestroy', teardown);

  // On restore from the view cache the element is intact but not booted —
  // inject app.js again so it rebuilds against fresh state.
  view.addEventListener('viewshow', () => { if (!view.dataset.jtvBooted) inject(); });

  inject();
}
