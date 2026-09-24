import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { useState } from 'preact/hooks';
import { logoUrl } from '../../api/images';
import type { TallyGame } from '../../api/tallyModels';
import { episodeCode } from '../../kit/ItemCard';
import { formatRuntime, formatTime, gameStatusLabel, tallyUppercase } from '../../util/format';

export type HomeFocus = { kind: 'item'; item: BaseItemDto; rowTitle: string } | { kind: 'game'; game: TallyGame; hideScores: boolean } | null;

const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

type MetaPart = { text: string; boxed?: boolean };

/** `S1 E6 · OCT 6, 2018 · [TV-G] · 1m · ★ 6.9 · ENDS 12:53 AM` / `2021 · [PG-13] · 2h 35m · ★ 7.8`. */
export function itemMeta(item: BaseItemDto, now: Date = new Date()): MetaPart[] {
  const parts: MetaPart[] = [];
  const code = episodeCode(item);
  if (code !== null) parts.push({ text: code });
  if (item.Type === 'Episode' && item.PremiereDate != null) {
    const d = new Date(item.PremiereDate);
    if (!isNaN(d.getTime())) parts.push({ text: `${MONTHS[d.getUTCMonth()] ?? ''} ${d.getUTCDate()}, ${d.getUTCFullYear()}` });
  } else if (item.ProductionYear != null) {
    parts.push({ text: String(item.ProductionYear) });
  }
  if (item.OfficialRating != null && item.OfficialRating !== '') parts.push({ text: item.OfficialRating, boxed: true });
  const runtime = item.RunTimeTicks ?? 0;
  if (runtime > 0) parts.push({ text: formatRuntime(runtime) });
  if (item.CommunityRating != null) parts.push({ text: `★ ${item.CommunityRating.toFixed(1)}` });
  if (runtime > 0 && (item.Type === 'Movie' || item.Type === 'Episode')) {
    const left = runtime - (item.UserData?.PlaybackPositionTicks ?? 0);
    parts.push({ text: 'ENDS ' + formatTime(new Date(now.getTime() + left / 10_000)) });
  }
  return parts;
}

function Meta(props: { parts: MetaPart[] }) {
  return (
    <div class="meta">
      {props.parts.map((p, i) => [
        i > 0 ? <span key={'s' + String(i)} class="sep">·</span> : null,
        <span key={'p' + String(i)} class={p.boxed === true ? 'rating' : undefined}>
          {tallyUppercase(p.text)}
        </span>,
      ])}
    </div>
  );
}

function ItemHeader(props: { item: BaseItemDto; rowTitle: string }) {
  const { item } = props;
  const logo = logoUrl(item);
  const [logoFailed, setLogoFailed] = useState(false);
  const title = item.Type === 'Episode' ? (item.SeriesName ?? item.Name ?? '') : (item.Name ?? '');
  return (
    <>
      <div class="kicker mono-label">{tallyUppercase(props.rowTitle)}</div>
      {logo !== null && !logoFailed ? (
        <div class="logo">
          <img src={logo} alt={title} onError={() => setLogoFailed(true)} />
        </div>
      ) : (
        <div class="title ellipsis">{title}</div>
      )}
      <Meta parts={itemMeta(item)} />
      {item.Overview != null ? <div class="overview clamp-2">{item.Overview}</div> : null}
    </>
  );
}

function GameHeader(props: { game: TallyGame; hideScores: boolean }) {
  const { game } = props;
  const away = game.away.shortName !== '' ? game.away.shortName : game.away.abbr;
  const home = game.home.shortName !== '' ? game.home.shortName : game.home.abbr;
  const kicker = [game.sport, gameStatusLabel(game)].filter((x) => x !== '').join(' · ');
  const score =
    game.state !== 'pre' && !props.hideScores
      ? `${game.away.abbr} ${game.away.score ?? 0} · ${game.home.abbr} ${game.home.score ?? 0}`
      : game.watch !== null
        ? game.watch.channelName
        : 'Not on your channels';
  return (
    <>
      <div class="kicker mono-label">{tallyUppercase(kicker)}</div>
      <div class="title ellipsis">
        {away} at {home}
      </div>
      <Meta parts={[{ text: score }]} />
      {game.lastPlay !== null && !props.hideScores ? <div class="overview clamp-2">{game.lastPlay}</div> : null}
    </>
  );
}

/** The fixed-height header over Home's rows: whatever card has focus, described. */
export function HomeHeader(props: { focus: HomeFocus }) {
  const f = props.focus;
  return (
    <div class="home-header">
      {f === null ? null : f.kind === 'item' ? <ItemHeader key={f.item.Id} item={f.item} rowTitle={f.rowTitle} /> : <GameHeader game={f.game} hideScores={f.hideScores} />}
    </div>
  );
}
