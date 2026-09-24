/**
 * Text subtitles drawn by the app (not by the engine), so they look the same on AVPlay, webOS and browsers and
 * follow Tally's type. The server converts any text format (SRT, ASS, embedded) to WebVTT.
 */
export interface Cue {
  start: number;
  end: number;
  text: string;
}

function toMs(stamp: string): number {
  const parts = stamp.trim().split(':');
  let h = 0;
  let m: number;
  let s: number;
  if (parts.length === 3) {
    h = parseInt(parts[0] ?? '0', 10);
    m = parseInt(parts[1] ?? '0', 10);
    s = parseFloat((parts[2] ?? '0').replace(',', '.'));
  } else {
    m = parseInt(parts[0] ?? '0', 10);
    s = parseFloat((parts[1] ?? '0').replace(',', '.'));
  }
  return Math.round(((h * 60 + m) * 60 + s) * 1000);
}

/** WebVTT → cues (tags stripped; positioning ignored: cues sit at the bottom center). */
export function parseVtt(vtt: string): Cue[] {
  const cues: Cue[] = [];
  const blocks = vtt.replace(/\r/g, '').split(/\n\n+/);
  for (const block of blocks) {
    const lines = block.split('\n');
    const timing = lines.findIndex((l) => l.indexOf('-->') >= 0);
    if (timing < 0) continue;
    const [a, b] = (lines[timing] as string).split('-->');
    if (a === undefined || b === undefined) continue;
    const end = b.trim().split(/\s+/)[0] ?? '';
    const text = lines
      .slice(timing + 1)
      .join('\n')
      .replace(/<[^>]+>/g, '')
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&nbsp;/g, ' ')
      .trim();
    if (text !== '') cues.push({ start: toMs(a), end: toMs(end), text });
  }
  return cues.sort((x, y) => x.start - y.start);
}

/** The cues showing at `ms` (binary search on start, then a short scan for overlaps). */
export function activeCues(cues: readonly Cue[], ms: number): Cue[] {
  let lo = 0;
  let hi = cues.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if ((cues[mid] as Cue).start <= ms) lo = mid + 1;
    else hi = mid;
  }
  const out: Cue[] = [];
  for (let i = lo - 1; i >= 0 && i >= lo - 20; i--) {
    const c = cues[i] as Cue;
    if (c.end > ms) out.unshift(c);
  }
  return out;
}
