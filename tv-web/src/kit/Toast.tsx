import { useEffect, useState } from 'preact/hooks';
import { createStore, useStore } from '../util/store';
import './toast.css';

/**
 * A one-line notice at the bottom of the screen ("Added to multiview", the server's reason a recording was refused):
 * the Android app's toasts, drawn in the Tally look. Pages that can raise one render a <ToastHost/> (hidden pages
 * are display:none, so only the visible page's host shows).
 */
interface Toast {
  id: number;
  text: string;
  ms: number;
}

const current = createStore<Toast | null>(null);
let nextId = 1;

/** Shows `text` for `ms` (Android's LENGTH_SHORT is 2 s, LENGTH_LONG 3.5 s). */
export function showToast(text: string, ms = 2500): void {
  current.set({ id: nextId++, text, ms });
}

export function ToastHost() {
  const toast = useStore(current);
  const [visible, setVisible] = useState<Toast | null>(null);
  useEffect(() => {
    if (toast === null) return undefined;
    setVisible(toast);
    const t = window.setTimeout(() => {
      setVisible(null);
      if (current.get() === toast) current.set(null);
    }, toast.ms);
    return () => window.clearTimeout(t);
  }, [toast]);
  if (visible === null) return null;
  return (
    <div class="toast-host">
      <div class="toast">{visible.text}</div>
    </div>
  );
}
