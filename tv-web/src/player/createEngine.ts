import type { Platform } from '../platform/platform';
import { createAvPlayEngine } from './avplayEngine';
import type { EngineEvents, PlayerEngine } from './engine';
import { createHtml5Engine } from './html5Engine';

/**
 * Tizen: AVPlay (falls back to <video> if webapis is missing, e.g. a Tizen browser). webOS: <video> on LG's
 * pipeline (documented stub: the same element code as browsers, without hls.js; Luna-specific handling such as
 * audio track selection comes with the webOS build). Browsers: <video> + hls.js where needed.
 */
export function createEngine(platform: Platform, host: HTMLElement, events: EngineEvents, bundleBase: string): PlayerEngine {
  if (platform.name === 'tizen' && window.webapis?.avplay !== undefined) return createAvPlayEngine(host, events);
  if (platform.name === 'webos') return createHtml5Engine(host, events, bundleBase, 'webos');
  return createHtml5Engine(host, events, bundleBase, 'html5');
}
