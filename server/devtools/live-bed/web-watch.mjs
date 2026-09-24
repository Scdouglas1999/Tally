#!/usr/bin/env node
// Plays a Live TV channel in Jellyfin's own web client (headless Chromium over CDP) and reports what a viewer
// would see: time to first frame, every stall ('waiting' until playback moves again), errors, resolution changes,
// and whether the player re-requested the stream (a re-tune shows up as a new <video> source).
//
//   node web-watch.mjs --server http://127.0.0.1:18200 --user friend --password '...' \
//        --channel "Bed Game" --seconds 240 [--events events.jsonl]
//
// Playback is started the way a phone starts it on a TV: POST /Sessions/{id}/Playing?playCommand=PlayNow against
// the browser's own session, so the timing starts at a known instant. Lines are JSON; the last one is a summary.
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, appendFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, all) => (v.startsWith('--') ? a.concat([[v.slice(2), all[i + 1]]]) : a), []));
const server = args.server ?? 'http://127.0.0.1:18200';
const seconds = Number(args.seconds ?? 120);
const port = Number(args.port ?? 9333);
const out = args.events;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (o) => { const line = JSON.stringify({ t: Date.now() / 1000, ...o }); console.log(line); if (out) appendFileSync(out, line + '\n'); };

const profile = mkdtempSync(join(tmpdir(), 'tally-web-watch-'));
const chrome = spawn(args.chrome ?? 'chromium', [
  '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, '--autoplay-policy=no-user-gesture-required',
  '--mute-audio', '--no-first-run', '--no-default-browser-check', '--window-size=1280,720', 'about:blank'
], { stdio: 'ignore' });
process.on('exit', () => chrome.kill('SIGKILL'));

let ws;
for (let i = 0; i < 50 && !ws; i++) {
  try {
    const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
    const page = targets.find((t) => t.type === 'page');
    if (page) ws = new WebSocket(page.webSocketDebuggerUrl);
  } catch { await sleep(200); }
}
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let nextId = 1;
const pending = new Map();
ws.addEventListener('message', (m) => {
  const msg = JSON.parse(m.data);
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
});
const cdp = (method, params = {}) => new Promise((r) => { const id = nextId++; pending.set(id, r); ws.send(JSON.stringify({ id, method, params })); });
const evaluate = async (expr) => (await cdp('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.result?.value;

await cdp('Page.enable');
await cdp('Runtime.enable');
// Watch every <video> the client creates.
await cdp('Page.addScriptToEvaluateOnNewDocument', { source: `
  window.__ladder = [];
  const note = (e) => window.__ladder.push({ t: Date.now() / 1000, ...e });
  const hook = (v) => {
    if (v.__hooked) return; v.__hooked = true;
    for (const ev of ['loadstart', 'playing', 'waiting', 'stalled', 'error', 'resize', 'seeking', 'emptied', 'ended']) {
      v.addEventListener(ev, () => note({ ev, ct: v.currentTime, w: v.videoWidth, h: v.videoHeight, src: (v.currentSrc || '').slice(0, 120), err: v.error && v.error.message }));
    }
  };
  new MutationObserver(() => document.querySelectorAll('video').forEach(hook)).observe(document, { childList: true, subtree: true });
  setInterval(() => { const v = document.querySelector('video'); if (v) note({ ev: 'tick', ct: v.currentTime, w: v.videoWidth, h: v.videoHeight, rs: v.readyState, paused: v.paused,
    dropped: v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality().droppedVideoFrames : null, frames: v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality().totalVideoFrames : null }); }, 250);
` });

await cdp('Page.navigate', { url: `${server}/web/index.html` });
for (let i = 0; i < 60; i++) { await sleep(500); if (await evaluate(`!!document.querySelector('#txtManualName') && document.querySelector('#txtManualName').offsetParent !== null`)) break;
  if (await evaluate(`!!document.querySelector('.manualLoginForm') === false && !!document.querySelector('.btnManual')`)) await evaluate(`document.querySelector('.btnManual').click()`); }
await evaluate(`(() => { const n = document.querySelector('#txtManualName'); n.value = ${JSON.stringify(args.user)}; n.dispatchEvent(new Event('input', { bubbles: true }));
  const p = document.querySelector('#txtManualPassword'); p.value = ${JSON.stringify(args.password)}; p.dispatchEvent(new Event('input', { bubbles: true }));
  document.querySelector('.manualLoginForm button[type=submit]').click(); })()`);
await sleep(6000);

// The browser's session and the channel's Live TV item.
const auth = await (await fetch(`${server}/Users/AuthenticateByName`, { method: 'POST', headers: { 'Content-Type': 'application/json',
  'X-Emby-Authorization': 'MediaBrowser Client="web-watch", Device="web-watch", DeviceId="web-watch", Version="1"' },
  body: JSON.stringify({ Username: args.user, Pw: args.password }) })).json();
const H = { 'X-Emby-Token': auth.AccessToken };
const channels = (await (await fetch(`${server}/LiveTv/Channels?UserId=${auth.User.Id}&Limit=500`, { headers: H })).json()).Items;
const channel = channels.find((c) => c.Name === args.channel);
if (!channel) { log({ error: `no Live TV channel named ${args.channel}`, have: channels.map((c) => c.Name) }); process.exit(2); }
const sessions = await (await fetch(`${server}/Sessions?ControllableByUserId=${auth.User.Id}&ActiveWithinSeconds=60`, { headers: H })).json();
const web = sessions.filter((s) => s.Client === 'Jellyfin Web' && s.UserName === args.user).sort((a, b) => b.LastActivityDate.localeCompare(a.LastActivityDate))[0];
if (!web) { log({ error: 'browser session not found', sessions: sessions.map((s) => s.Client) }); process.exit(3); }

const t0 = Date.now() / 1000;
log({ ev: 'command', channel: channel.Name, item: channel.Id, session: web.Id });
await fetch(`${server}/Sessions/${web.Id}/Playing?playCommand=PlayNow&itemIds=${channel.Id}`, { method: 'POST', headers: H });

let seen = 0;
const all = [];
const end = t0 + seconds;
while (Date.now() / 1000 < end) {
  await sleep(1000);
  const evs = (await evaluate('window.__ladder ? window.__ladder.splice(0) : []')) ?? [];
  for (const e of evs) { all.push(e); if (e.ev !== 'tick') log(e); }
  seen += evs.length;
}

// Summary: first frame = first tick where currentTime moved; stalls = time spans where currentTime stood still
// for more than 0.5 s after playback had started (paused excluded).
const ticks = all.filter((e) => e.ev === 'tick');
let first = null; const stalls = []; let stillSince = null; let last = null;
for (const k of ticks) {
  if (last && k.ct > last.ct + 0.01) { if (!first) first = k.t; if (stillSince && k.t - stillSince > 0.75) stalls.push({ at: stillSince, seconds: +(k.t - stillSince).toFixed(2) }); stillSince = null; }
  else if (first && last && !k.paused && stillSince === null) stillSince = last.t;
  last = k;
}
const sizes = [...new Set(ticks.map((k) => `${k.w}x${k.h}`))];
const sources = [...new Set(all.filter((e) => e.ev === 'loadstart').map((e) => e.src))];
log({ summary: true, channel: channel.Name, firstFrameSeconds: first ? +(first - t0).toFixed(2) : null, stalls, errors: all.filter((e) => e.ev === 'error'),
  sizes, videoSources: sources.length, dropped: ticks.at(-1)?.dropped, frames: ticks.at(-1)?.frames, playedSeconds: ticks.at(-1)?.ct });
// Stop like a viewer would, so Jellyfin closes the live stream and the next run starts cold.
await fetch(`${server}/Sessions/${web.Id}/Playing/Stop`, { method: 'POST', headers: H });
await sleep(3000);
ws.close();
chrome.kill('SIGKILL');
process.exit(0);
