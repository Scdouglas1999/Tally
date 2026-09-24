import type { TallyChannel, TallyInfo } from '../../api/tallyModels';
import { push } from '../../router/router';
import { KeyHint, EmptyState, TallyRow, TallySwitch } from '../../sports/SportsBits';
import { setHideScores, setOnlyWatchable } from '../../state/sportsData';
import { removeFromMultiview } from './sportsState';
import { useTabArrival } from './tabArrival';

/**
 * MULTIVIEW (TallyScreens.kt MultiviewQueue): "Open multiview" first (it takes focus: it is what people come here
 * for), then the queued channels as rows (OK removes one). An empty queue says how to add channels.
 */
export function MultiviewQueueTab(props: { queue: string[]; channels: TallyChannel[]; takeFocus: boolean; active: boolean }) {
  const empty = props.queue.length === 0;
  useTabArrival(props.takeFocus && props.active, empty ? 'smv-empty' : 'smv-open', true);
  if (empty) {
    return <EmptyState focusKey="smv-empty" class="tab-empty" title="No channels queued" subtitle="Hold OK on a game or a channel to add it to multiview" />;
  }
  return (
    <div class="tab-list">
      <TallyRow focusKey="smv-open" label="Open multiview" primary={true} onPress={() => push({ name: 'multiview' })} />
      {props.queue.map((id) => (
        <TallyRow key={id} focusKey={'smv-' + id} label={props.channels.find((c) => c.id === id)?.name ?? id} onPress={() => removeFromMultiview(id)}>
          <KeyHint keyName="OK" label="Remove" />
        </TallyRow>
      ))}
    </div>
  );
}

/**
 * SETTINGS (TallySettingsContent.kt): "My channels only" and "Hide scores" as rows with square switches, then the
 * server plugin's build, API version and features.
 */
export function SportsSettingsTab(props: { onlyWatchable: boolean; hideScores: boolean; info: TallyInfo | null; takeFocus: boolean; active: boolean }) {
  useTabArrival(props.takeFocus && props.active, 'sst-mine', true);
  return (
    <div class="tab-list">
      <TallyRow
        focusKey="sst-mine"
        label="My channels only"
        description="Only show games you can watch on your channels"
        onPress={() => void setOnlyWatchable(!props.onlyWatchable)}
      >
        <TallySwitch checked={props.onlyWatchable} />
      </TallyRow>
      <TallyRow focusKey="sst-hide" label="Hide scores" description="Never show scores, results, or spoilers" onPress={() => void setHideScores(!props.hideScores)}>
        <TallySwitch checked={props.hideScores} />
      </TallyRow>
      <div class="server-block">
        <div class="mono-label heading">SERVER PLUGIN</div>
        {props.info === null ? (
          <div class="line">No response from the Tally plugin on the server</div>
        ) : (
          <>
            <div class="line">
              Build {props.info.pluginBuild ?? '—'} · API v{props.info.apiVersion}
            </div>
            <div class="line">Features: {props.info.features.length > 0 ? props.info.features.join(', ') : '—'}</div>
          </>
        )}
      </div>
    </div>
  );
}
