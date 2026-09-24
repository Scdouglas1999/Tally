/**
 * The two lower thirds of the player (TallyNextUp.kt, TallySkipSegment.kt): the next-up card at the end of an
 * episode, and the "Skip Intro" prompt inside a media segment. Both enter as a broadcast caption (LowerThird.kt): a
 * 3dp accent bar, then the panel revealed left to right.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import type { ComponentChildren } from 'preact';
import { useEffect, useLayoutEffect, useState } from 'preact/hooks';
import { wideUrl } from '../../api/images';
import { setFocus } from '../../focus/focus';
import { Button } from '../../kit/Button';
import { tallyUppercase } from '../../util/format';
import * as F from './playerFormat';

export function LowerThird(props: { children: ComponentChildren; class?: string }) {
  return (
    <div class={'pc-lower' + (props.class !== undefined ? ' ' + props.class : '')}>
      <div class="bar" />
      <div class="reveal">{props.children}</div>
    </div>
  );
}

/**
 * UP NEXT: `S1 E4 · Cancer Man`, the still, PLAY NOW (focused) and DISMISS, and a 2dp accent bar at the foot that
 * empties over the countdown. The countdown runs only while auto-play is on (`autoPlay`); `onCancelCountdown` is
 * how the page's BACK stops it.
 */
export function NextUpCard(props: {
  item: BaseItemDto;
  autoPlay: boolean;
  secondsLeft: number;
  onPlayNow: () => void;
  onDismiss: () => void;
}) {
  const [failed, setFailed] = useState(false);
  // one frame at the full bar first, so the first second slides too
  const [armed, setArmed] = useState(false);
  useEffect(() => {
    setFocus('pc-nextup-play');
    const raf = window.requestAnimationFrame(() => window.requestAnimationFrame(() => setArmed(true)));
    return () => window.cancelAnimationFrame(raf);
  }, []);
  const url = wideUrl(props.item, 256, 144);
  const counting = props.autoPlay && props.secondsLeft > 0;
  // the bar slides each second to where the next tick leaves it (CSS transition, 1 s linear)
  const bar = counting ? F.countdownFraction(armed ? props.secondsLeft - 1 : props.secondsLeft, F.AUTO_PLAY_DELAY_S) : 0;
  return (
    <div class="pc-nextup">
      <LowerThird>
        <div class="nu-card">
          <div class="body">
            <div class="still">{url !== null && !failed ? <img src={url} alt="" onError={() => setFailed(true)} /> : null}</div>
            <div class="text">
              <div class="kicker mono-label">UP NEXT</div>
              <div class="line ellipsis">{F.nextUpLine(props.item) ?? ''}</div>
              <div class="buttons">
                <Button focusKey="pc-nextup-play" label="Play now" primary onPress={props.onPlayNow} />
                <Button focusKey="pc-nextup-dismiss" label="Dismiss" onPress={props.onDismiss} />
              </div>
            </div>
          </div>
          <div class="countdown">{props.autoPlay ? <div class="fill" style={{ width: `${bar * 100}%` }} /> : null}</div>
        </div>
      </LowerThird>
    </div>
  );
}

/** `SKIP INTRO` at the bottom right, focused; OK skips, BACK dismisses (the page's keys). */
export function SkipPrompt(props: { label: string; onSkip: () => void }) {
  useLayoutEffect(() => setFocus('pc-skip-prompt'), []);
  return (
    <div class="pc-skipprompt">
      <LowerThird>
        <div class="box">
          <Button focusKey="pc-skip-prompt" label={tallyUppercase(props.label)} onPress={props.onSkip} />
        </div>
      </LowerThird>
    </div>
  );
}
