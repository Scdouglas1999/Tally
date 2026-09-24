import { useEffect, useRef, useState } from 'preact/hooks';
import {
  findServer,
  finishQuickConnect,
  probeServer,
  quickConnectApproved,
  signInWithPassword,
  startQuickConnect,
  type QuickConnectCode,
  type ServerInfo,
} from '../../api/jellyfin';
import { app } from '../../app/context';
import { FocusGroup, focusExists, setFocus, useFocusable } from '../../focus/focus';
import { Button } from '../../kit/Button';
import { Field } from '../../kit/Field';
import { Lamp, type LampState } from '../../kit/Lamp';
import { useBack } from '../../platform/keyRouter';
import { splitCode, tallyUppercase } from '../../util/format';
import './signin.css';

type Step =
  | { kind: 'connecting' }
  | { kind: 'address'; error?: string }
  | { kind: 'quick'; server: ServerInfo }
  | { kind: 'password'; server: ServerInfo; error?: string };

const HOLD_MS = 600;
const POLL_MS = 3000;

function Frame(props: { kicker: string; lamp?: LampState; subtitle?: string; children: preact.ComponentChildren }) {
  return (
    <div class="signin">
      <div class="center">{props.children}</div>
      <div class="brand">
        <div class="wordmark">
          <Lamp state="lit" size={23} />
          <span class="name">TALLY</span>
        </div>
        <div class="step">
          {props.lamp !== undefined ? <Lamp state={props.lamp} size={16} /> : null}
          <span class="kicker mono-label">{tallyUppercase(props.kicker)}</span>
        </div>
        {props.subtitle !== undefined ? <div class="subtitle ellipsis">{props.subtitle}</div> : null}
      </div>
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  const status = (e as { response?: { status?: number } }).response?.status;
  if (status === 401 || status === 403) return 'Wrong user name or password.';
  if (status !== undefined) return `${fallback} (HTTP ${status})`;
  return fallback;
}

function AddressStep(props: { error?: string; onServer: (s: ServerInfo) => void }) {
  const [address, setAddress] = useState(app.shell.serverUrl ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(props.error);
  const group = useFocusable({ focusKey: 'signin-address' });
  useEffect(() => setFocus('signin-address-field'), []);
  const connect = async (): Promise<void> => {
    if (busy || address.trim() === '') return;
    setBusy(true);
    setError(undefined);
    try {
      const server = await findServer(address);
      if (server === null) setError('No Jellyfin server answered at that address.');
      else props.onServer(server);
    } catch {
      setError('No Jellyfin server answered at that address.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Frame kicker="Add server">
      <div ref={group.ref} class="form">
        <FocusGroup focusKey="signin-address">
          <div class="help">Type your server's IP address or URL.</div>
          <Field focusKey="signin-address-field" value={address} onInput={setAddress} onSubmit={() => void connect()} placeholder="Server IP or URL" type="url" />
          {error !== undefined ? <div class="error">{error}</div> : null}
          <div class="actions">
            <Button primary label={busy ? 'Connecting…' : 'Connect'} onPress={() => void connect()} />
          </div>
        </FocusGroup>
      </div>
    </Frame>
  );
}

function QuickConnectStep(props: { server: ServerInfo; onPassword: () => void }) {
  const [code, setCode] = useState<QuickConnectCode | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [approved, setApproved] = useState(false);
  const group = useFocusable({ focusKey: 'signin-quick' });
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    let timer = 0;
    const run = async (): Promise<void> => {
      try {
        const c = await startQuickConnect(props.server);
        if (!alive.current) return;
        setCode(c);
        const poll = async (): Promise<void> => {
          if (!alive.current) return;
          try {
            if (await quickConnectApproved(props.server, c.secret)) {
              setApproved(true);
              // the kicker lamp catches before Home appears
              window.setTimeout(() => {
                if (alive.current) finishQuickConnect(props.server, c.secret).catch(() => setError('Quick Connect failed. Try again.'));
              }, HOLD_MS);
              return;
            }
          } catch {
            // a missed poll is retried
          }
          timer = window.setTimeout(() => void poll(), POLL_MS);
        };
        timer = window.setTimeout(() => void poll(), POLL_MS);
      } catch (e) {
        if (!alive.current) return;
        const status = (e as { response?: { status?: number } }).response?.status;
        setError(status === 401 || status === 403 ? 'Quick Connect is off on this server. Sign in with a password.' : 'Could not start Quick Connect.');
      }
    };
    void run();
    return () => {
      alive.current = false;
      window.clearTimeout(timer);
    };
  }, [props.server.url]);

  useEffect(() => {
    if (focusExists('signin-use-password')) setFocus('signin-use-password');
  }, [code, error]);

  return (
    <Frame kicker="Quick Connect" lamp={approved ? 'lit' : error !== null ? 'off' : 'sputtering'} subtitle={props.server.name}>
      <div ref={group.ref} class="form">
        <FocusGroup focusKey="signin-quick">
          {code === null && error === null ? <div class="waiting mono-label">WAITING FOR QUICK CONNECT CODE…</div> : null}
          {code !== null ? <div class="code">{splitCode(code.code)}</div> : null}
          {code !== null ? (
            <div class="help">Use Quick Connect on your device to authenticate to {props.server.name}</div>
          ) : null}
          {error !== null ? <div class="error">{error}</div> : null}
          <div class="actions">
            <Button focusKey="signin-use-password" label="Use username/password" onPress={props.onPassword} />
          </div>
        </FocusGroup>
      </div>
    </Frame>
  );
}

function PasswordStep(props: { server: ServerInfo; error?: string; onBack: () => void }) {
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(props.error);
  const group = useFocusable({ focusKey: 'signin-password' });
  useEffect(() => setFocus('signin-name'), []);
  useBack(props.onBack);
  const submit = async (): Promise<void> => {
    if (busy || name.trim() === '') return;
    setBusy(true);
    setError(undefined);
    try {
      await signInWithPassword(props.server, name.trim(), password);
    } catch (e) {
      setError(errorText(e, 'Could not sign in.'));
      setBusy(false);
    }
  };
  return (
    <Frame kicker="Sign in" subtitle={props.server.name}>
      <div ref={group.ref} class="form">
        <FocusGroup focusKey="signin-password">
          <div class="help">Enter your username and password to sign in to {props.server.name}.</div>
          <Field focusKey="signin-name" value={name} onInput={setName} onSubmit={() => setFocus('signin-pass')} placeholder="Username" />
          <Field focusKey="signin-pass" value={password} onInput={setPassword} onSubmit={() => void submit()} placeholder="Password" type="password" />
          {error !== undefined ? <div class="error">{error}</div> : null}
          <div class="actions">
            <Button primary label={busy ? 'Signing in…' : 'Sign in'} onPress={() => void submit()} />
          </div>
        </FocusGroup>
      </div>
    </Frame>
  );
}

/**
 * Sign-in: the shell's server (or an address typed here) → Quick Connect (a code to approve on a phone) or a user
 * name and password. Signing in replaces this page with Home (App watches the session).
 */
export function SignInPage() {
  const [step, setStep] = useState<Step>({ kind: 'connecting' });
  useEffect(() => {
    const known = app.shell.serverUrl;
    if (known === null) {
      setStep({ kind: 'address' });
      return;
    }
    void probeServer(known).then((server) =>
      setStep(server === null ? { kind: 'address', error: `${known} did not answer.` } : { kind: 'quick', server }),
    );
  }, []);
  switch (step.kind) {
    case 'connecting':
      return (
        <Frame kicker="Connecting" lamp="sputtering">
          <div />
        </Frame>
      );
    case 'address':
      return <AddressStep error={step.error} onServer={(server) => setStep({ kind: 'quick', server })} />;
    case 'quick':
      return <QuickConnectStep server={step.server} onPassword={() => setStep({ kind: 'password', server: step.server })} />;
    case 'password':
      return <PasswordStep server={step.server} error={step.error} onBack={() => setStep({ kind: 'quick', server: step.server })} />;
  }
}
