import { useRef, useState } from 'preact/hooks';
import { useFocusable } from '../focus/focus';
import { useKeyHandler } from '../platform/keyRouter';
import { tallyUppercase } from '../util/format';

/**
 * A text field. Moving focus onto it does not open the TV's keyboard (Tizen and webOS open it for any focused
 * input, which would pop up whenever the D-pad passed by): OK starts editing (the input takes DOM focus and the
 * keyboard opens), OK again submits, UP/DOWN leave the field, BACK stops editing.
 */
export function Field(props: {
  value: string;
  onInput: (value: string) => void;
  onSubmit?: () => void;
  placeholder: string;
  type?: 'text' | 'password' | 'url';
  focusKey?: string;
}) {
  const [focused, setFocused] = useState(false);
  const [editing, setEditing] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onFocus: () => setFocused(true),
    onBlur: () => {
      setFocused(false);
      setEditing(false);
      input.current?.blur();
    },
    onEnter: () => {
      if (editing) {
        props.onSubmit?.();
        return;
      }
      setEditing(true);
      input.current?.focus();
    },
  });
  useKeyHandler((key) => {
    if (key !== 'back') return false;
    setEditing(false);
    f.ref.current?.focus({ preventScroll: true });
    return true;
  }, editing);
  return (
    <div ref={f.ref} class={'field' + (focused ? ' focused' : '')}>
      <input
        ref={input}
        type={props.type ?? 'text'}
        value={props.value}
        placeholder={tallyUppercase(props.placeholder)}
        autocomplete="off"
        autocapitalize="off"
        spellcheck={false}
        tabIndex={-1}
        onFocus={() => {
          // a click in a desktop browser: move the app's focus here too, then give the input its focus back
          setEditing(true);
          if (!focused) {
            f.focusSelf();
            window.setTimeout(() => input.current?.focus(), 0);
          }
        }}
        onInput={(e) => props.onInput((e.currentTarget as HTMLInputElement).value)}
      />
    </div>
  );
}
