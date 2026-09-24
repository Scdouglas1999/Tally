/**
 * Built-ins newer than Chromium 68 (webOS 5; Tizen 5.5 is 69), polyfilled from core-js (MIT). Syntax is lowered by
 * the build (vite.config.ts target chrome68); this file covers library functions only. Keep it first in main.tsx.
 * When code needs another post-68 built-in, add it here (lint's compat check names the APIs it finds).
 */
import 'core-js/actual/global-this';
import 'core-js/actual/array/flat';
import 'core-js/actual/array/flat-map';
import 'core-js/actual/array/at';
import 'core-js/actual/array/find-last';
import 'core-js/actual/array/find-last-index';
import 'core-js/actual/object/from-entries';
import 'core-js/actual/object/has-own';
import 'core-js/actual/promise/all-settled';
import 'core-js/actual/promise/any';
import 'core-js/actual/string/match-all';
import 'core-js/actual/string/replace-all';
import 'core-js/actual/string/at';
import 'core-js/actual/queue-microtask';
