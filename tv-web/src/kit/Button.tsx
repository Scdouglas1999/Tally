import type { ComponentChildren } from 'preact';
import { useFocusable, type FocusableOptions } from '../focus/focus';
import { tallyUppercase } from '../util/format';
import { GlyphIcon } from './Bits';
import type { GlyphName } from './glyphs';

/** TallyButton: square, mono uppercase label, optional glyph. Primary = accent fill (one per screen). */
export function Button(
  props: {
    label: string;
    onPress: () => void;
    primary?: boolean;
    glyph?: GlyphName;
    /** Drawn after the label (the favorite button's accent square). */
    trailing?: ComponentChildren;
  } & Pick<FocusableOptions, 'focusKey' | 'onFocus' | 'onArrow'>,
) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onEnter: props.onPress, onFocus: props.onFocus, onArrow: props.onArrow });
  return (
    <div ref={f.ref} class={'btn' + (props.primary === true ? ' primary' : '')} role="button" onClick={props.onPress}>
      {props.glyph !== undefined ? <GlyphIcon name={props.glyph} /> : null}
      <span>{tallyUppercase(props.label)}</span>
      {props.trailing !== undefined ? <span class="btn-trailing">{props.trailing}</span> : null}
    </div>
  );
}
