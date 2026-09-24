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
  } & Pick<FocusableOptions, 'focusKey' | 'onFocus'>,
) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onEnter: props.onPress, onFocus: props.onFocus });
  return (
    <div ref={f.ref} class={'btn' + (props.primary === true ? ' primary' : '')} role="button" onClick={props.onPress}>
      {props.glyph !== undefined ? <GlyphIcon name={props.glyph} /> : null}
      <span>{tallyUppercase(props.label)}</span>
    </div>
  );
}
