import { useEffect, useRef, useState } from 'preact/hooks';
import { absolute } from '../../api/tally';
import type { TallyGame } from '../../api/tallyModels';
import type { PageProps } from '../../app/page';
import { IndicatorSquare } from '../../kit/Bits';
import { useKeyHandler } from '../../platform/keyRouter';
import { back, replace, type Route } from '../../router/router';
import { board, useBoardPolling } from '../../state/sportsData';
import { gameStatusLabel, tallyUppercase } from '../../util/format';
import { useStore } from '../../util/store';
import { TuneIn, useEngine } from './playerKit';

const BAR_MS = 5000;

/** `RDG 3 · HCG 4` over `BOT 7TH · 0-0 · 1 OUT` (ScoreBug.kt). */
function ScoreBug(props: { game: TallyGame }) {
  const g = props.game;
  if (g.state === 'pre') return null;
  const situation = [gameStatusLabel(g)];
  if (g.sport === 'baseball' && g.state === 'in') {
    if (g.balls !== null && g.strikes !== null) situation.push(`${g.balls}-${g.strikes}`);
    if (g.outs !== null) situation.push(`${g.outs} OUT`);
  } else if (g.downDistance !== null && g.state === 'in') {
    situation.push(g.downDistance);
  }
  return (
    <div class="score-bug">
      <div class="line1">
        {g.away.abbr} {g.away.score ?? 0} · {g.home.abbr} {g.home.score ?? 0}
      </div>
      <div class="line2">{tallyUppercase(situation.join(' · '))}</div>
    </div>
  );
}

/**
 * A live channel from the Tally plugin: its continuous playlist (/JellyTV/Live/{id}.m3u8, signed, anonymous: the
 * same address multiview uses; the live ladder keeps it going when a source struggles). The score bug is live;
 * the bar below is a placeholder for the Tally live controls (game switcher, multiview, box score) of a later task.
 * CH+/CH- step through the board's channels.
 */
export function LivePage(props: PageProps<Extract<Route, { name: 'live' }>>) {
  const host = useRef<HTMLDivElement>(null);
  const player = useEngine(host);
  const current = useStore(board);
  useBoardPolling(props.active);
  const [bar, setBar] = useState(true);
  const timer = useRef(0);

  const showBar = (): void => {
    setBar(true);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setBar(false), BAR_MS);
  };

  useEffect(() => {
    const engine = player.engine.current;
    if (engine === null) return;
    engine.stop();
    void engine.load({ url: absolute(props.route.hlsPath), kind: 'hls', live: true, startMs: 0 }).catch(() => undefined);
    showBar();
    return () => window.clearTimeout(timer.current);
  }, [props.route.hlsPath]);

  const game = current?.games.find((g) => g.id === props.route.gameId) ?? null;
  const channels = current?.channels ?? [];

  const step = (delta: number): void => {
    if (channels.length === 0) return;
    const i = channels.findIndex((c) => c.id === props.route.channelId);
    const next = channels[(i + delta + channels.length) % channels.length];
    if (next === undefined) return;
    replace({ name: 'live', channelId: next.id, hlsPath: next.hlsPath, title: next.now?.title ?? next.name, gameId: next.gameId ?? undefined });
  };

  useKeyHandler((key) => {
    switch (key) {
      case 'back':
        if (bar) {
          setBar(false);
          return true;
        }
        back();
        return true;
      case 'stop':
        back();
        return true;
      case 'channelUp':
      case 'up':
        step(-1);
        return true;
      case 'channelDown':
      case 'down':
        step(1);
        return true;
      default:
        showBar();
        return true;
    }
  }, props.active);

  const channelName = channels.find((c) => c.id === props.route.channelId)?.name ?? '';

  return (
    <div class="player">
      <div ref={host} />
      {game !== null ? <ScoreBug game={game} /> : null}
      <TuneIn key={props.route.channelId} title={props.route.title} firstFrame={player.firstFrame} error={player.error} />
      {bar ? (
        <div class="live-bar">
          <div class="row1">
            <span class="live-tag mono-label">
              <IndicatorSquare tone="live" />
              LIVE
            </span>
            <span class="name ellipsis">{props.route.title}</span>
            <span class="channel mono-label">{tallyUppercase(channelName)}</span>
          </div>
          <div class="note">Live controls (game switcher, multiview, box score) come with the live player task.</div>
          <div class="hints">
            <span class="hint">
              <span class="key">UP / DOWN</span>Change channel
            </span>
            <span class="hint">
              <span class="key">BACK</span>Leave
            </span>
          </div>
        </div>
      ) : null}
    </div>
  );
}
