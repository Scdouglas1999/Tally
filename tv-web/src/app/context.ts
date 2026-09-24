import type { Platform } from '../platform/platform';
import type { TallyShell } from '../shell-contract/shell';

/** Set once at startup (main.tsx). */
export const app = {
  shell: null as unknown as TallyShell,
  platform: null as unknown as Platform,
};
