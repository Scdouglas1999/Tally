import './polyfills';
import { render } from 'preact';
import './styles/fonts.css';
import './styles/tokens.css';
import './styles/base.css';
import './kit/kit.css';
import { resolveShell } from './shell-contract/shell';
import { createPlatform } from './platform/platform';
import { app } from './app/context';
import { initJellyfin } from './api/jellyfin';
import { initFocus } from './focus/focus';
import { installKeyRouter } from './platform/keyRouter';
import { createStage } from './platform/stage';
import { App, rootBack } from './app/App';

// resolveShell reads document.currentScript: it must run while this script is first evaluated
const shell = resolveShell();
const platform = createPlatform(shell);
app.shell = shell;
app.platform = platform;
initJellyfin(platform.deviceName());
initFocus();
installKeyRouter(platform, () => rootBack(() => platform.exit()));
const stage = createStage();
render(<App onFirstScreen={() => shell.started()} />, stage);
