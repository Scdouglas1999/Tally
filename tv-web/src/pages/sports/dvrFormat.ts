/** Words and numbers for the DVR (port of DvrFormat.kt / RecordingsSections.kt; unit-tested). */
import { DvrState, type DvrJob, type DvrList, type DvrRule, type DvrStorage, type GameRecordingView } from '../../api/tallyDvr';
import { formatRuntime, formatTime } from '../../util/format';

const KB = 1024;

/** `350 MB`, `4.2 GB`, `12 GB`, `1.2 TB` (binary units, as the server counts). */
export function dvrSize(bytes: number): string {
  const gb = bytes / (KB * KB * KB);
  const tb = gb / KB;
  const oneDecimal = (v: number, unit: string): string => (v.toFixed(1) + ' ' + unit).replace('.0 ', ' ');
  if (tb >= 1) return oneDecimal(tb, 'TB');
  if (gb >= 10) return gb.toFixed(0) + ' GB';
  if (gb >= 1) return oneDecimal(gb, 'GB');
  return Math.max(1, bytes / (KB * KB)).toFixed(0) + ' MB';
}

/** `2h 58m`, `42m`: a recording's length. */
export function dvrLength(seconds: number): string {
  return formatRuntime(Math.round(seconds * 10_000_000));
}

const pad2 = (n: number): string => (n < 10 ? '0' : '') + String(n);

/** `42:10`, `1:02:03`: how long a recording has been running. */
export function dvrElapsed(sinceMs: number, nowMs: number): string {
  const total = Math.max(0, Math.floor((nowMs - sinceMs) / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${m}:${pad2(s)}`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** `Sep 24`, month first. */
export function dvrDate(ms: number): string {
  const d = new Date(ms);
  return `${MONTHS[d.getMonth()] ?? ''} ${d.getDate()}`;
}

/** `Sep 24 · 7:12 PM`, or `Today 7:12 PM`. */
export function dvrDayAndTime(ms: number, nowMs: number): string {
  const d = new Date(ms);
  const n = new Date(nowMs);
  const today = d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
  return today ? `Today ${formatTime(d)}` : `${dvrDate(ms)} · ${formatTime(d)}`;
}

const parse = (iso: string | null): number | null => {
  if (iso === null || iso === '') return null;
  const t = Date.parse(iso);
  return isNaN(t) ? null : t;
};

const FREE_SLOT_PREFIX = 'Waiting for a free slot';

/**
 * A job's state in words: "Recording since 7:12 PM", "Waiting for a stream", "Scheduled to record", "Failed: No
 * stream found for this game", "Finishing the recording", "Recorded", "Canceled".
 */
export function recordingStateText(view: Pick<GameRecordingView, 'state' | 'startedAt' | 'reason'>): string {
  switch (view.state) {
    case DvrState.RECORDING: {
      const since = parse(view.startedAt);
      return since !== null ? `Recording since ${formatTime(new Date(since))}` : 'Recording';
    }
    case DvrState.WAITING:
      return view.reason !== null && view.reason.indexOf(FREE_SLOT_PREFIX) === 0 ? view.reason : 'Waiting for a stream';
    case DvrState.SCHEDULED:
      return 'Scheduled to record';
    case DvrState.FINISHING:
      return 'Finishing the recording';
    case DvrState.DONE:
      return 'Recorded';
    case DvrState.FAILED:
      return `Failed: ${view.reason ?? 'The server could not do that.'}`;
    case DvrState.CANCELED:
      return 'Canceled';
    default:
      return view.state;
  }
}

/** "~9 GB · 1.2 TB free on the server" (or only the free space while the estimate is not in). */
export function estimateLine(storage: DvrStorage | null | undefined): string | null {
  if (storage === null || storage === undefined) return null;
  const parts: string[] = [];
  if (storage.estimate !== null && storage.estimate.bytes > 0) parts.push('~' + dvrSize(storage.estimate.bytes));
  if (storage.freeBytes !== null) parts.push(`${dvrSize(storage.freeBytes)} free on the server`);
  return parts.length > 0 ? parts.join(' · ') : null;
}

/** The storage line at the top of RECORDINGS: "1.2 TB free on the server · 14 GB used by recordings". */
export function storageLine(storage: DvrStorage | null): string | null {
  if (storage === null) return null;
  const parts: string[] = [];
  if (storage.freeBytes !== null) parts.push(`${dvrSize(storage.freeBytes)} free on the server`);
  parts.push(`${dvrSize(storage.usedBytes)} used by recordings`);
  return parts.join(' · ');
}

/** "Keep all" / "Keep the last 5". */
export function keepLastText(keepLast: number): string {
  return keepLast <= 0 ? 'Keep all' : `Keep the last ${keepLast}`;
}

/** The choices of the keep-last menu: all, 5, 10, 20. */
export const KEEP_LAST_CHOICES = [0, 5, 10, 20];

const displayName = (t: { shortName: string; abbr: string; name: string }): string => (t.shortName !== '' ? t.shortName : t.abbr !== '' ? t.abbr : t.name);

/** "Otters at Herons" with the board's short names; the job's own title when the teams are unknown. */
export function jobTitle(job: DvrJob): string {
  const a = displayName(job.game.away);
  const h = displayName(job.game.home);
  return a !== '' && h !== '' ? `${a} at ${h}` : job.title;
}

/** The Recordings tab's sections: RECORDING NOW, SCHEDULED, RECORDED, FAILED, TEAM RULES (canceled jobs left out). */
export interface RecordingsSections {
  recordingNow: DvrJob[];
  scheduled: DvrJob[];
  recorded: DvrJob[];
  failed: DvrJob[];
  rules: DvrRule[];
}

export function recordingsSections(list: DvrList): RecordingsSections {
  const scheduled = list.jobs.filter((j) => j.state === DvrState.SCHEDULED || j.state === DvrState.WAITING);
  return {
    recordingNow: list.jobs.filter((j) => j.state === DvrState.RECORDING || j.state === DvrState.FINISHING),
    scheduled: scheduled
      .map((j, i) => ({ j, i }))
      .sort((a, b) => (a.j.game.start < b.j.game.start ? -1 : a.j.game.start > b.j.game.start ? 1 : a.i - b.i))
      .map((x) => x.j),
    recorded: list.jobs.filter((j) => j.state === DvrState.DONE),
    failed: list.jobs.filter((j) => j.state === DvrState.FAILED),
    rules: list.rules.filter((r) => r.kind === 'team'),
  };
}

export const sectionsEmpty = (s: RecordingsSections): boolean =>
  s.recordingNow.length === 0 && s.scheduled.length === 0 && s.recorded.length === 0 && s.failed.length === 0 && s.rules.length === 0;

/** A recording in progress: "MLB · 42:10 · 1.3 GB" (finishing: "MLB · Finishing the recording"). */
export function recordingNowMeta(job: DvrJob, nowMs: number): string {
  const parts = [job.game.league];
  if (job.state === DvrState.FINISHING) parts.push('Finishing the recording');
  else {
    const started = parse(job.startedAt);
    if (started !== null) parts.push(dvrElapsed(started, nowMs));
    parts.push(dvrSize(job.bytes));
  }
  return parts.filter((p) => p !== '').join(' · ');
}

/** A scheduled job: "MLB · Today 7:05 PM · Every Otters game" (or "Waiting for a stream" once it is due). */
export function scheduledMeta(job: DvrJob, rules: readonly DvrRule[], nowMs: number): string {
  const parts = [job.game.league];
  if (job.state === DvrState.WAITING) parts.push(recordingStateText({ state: job.state, startedAt: null, reason: job.reason }));
  else {
    const start = parse(job.game.start);
    if (start !== null) parts.push(dvrDayAndTime(start, nowMs));
  }
  const rule = rules.find((r) => r.id === job.ruleId && r.kind === 'team');
  if (rule !== undefined) parts.push(rule.title);
  return parts.filter((p) => p !== '').join(' · ');
}

/** A finished recording's card line: "MLB · Sep 24 · 2h 58m". */
export function recordedMeta(job: DvrJob): string {
  const parts: string[] = [];
  if (job.game.league !== '') parts.push(job.game.league);
  const start = parse(job.game.start);
  if (start !== null) parts.push(dvrDate(start));
  if (job.seconds > 0) parts.push(dvrLength(job.seconds));
  return parts.join(' · ');
}

/** A team rule: "MLB · Keep the last 5" (any league: "All leagues · Keep all"). */
export function ruleMeta(rule: DvrRule, list: DvrList): string {
  const job = list.jobs.find((j) => j.game.leaguePath.toLowerCase() === rule.leaguePath.toLowerCase() && rule.leaguePath !== '');
  const tail = rule.leaguePath.split('/').pop() ?? '';
  const league = job !== undefined ? job.game.league : tail !== '' ? tail.toUpperCase() : 'All leagues';
  return `${league} · ${keepLastText(rule.keepLast)}`;
}
