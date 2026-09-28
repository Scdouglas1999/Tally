import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { releaseVersion } from '../src/buildVersion';

const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as { version: string };

describe('release version (TALLY_VERSION, else package.json)', () => {
  it('takes the release version the build passes in', () => {
    expect(releaseVersion('2.3.3', '0.1.0')).toBe('2.3.3');
    expect(releaseVersion(' 2.3.3\n', '0.1.0')).toBe('2.3.3');
    expect(releaseVersion('10.12.104', '0.1.0')).toBe('10.12.104');
  });

  it("falls back to package.json's version when it is missing, blank or malformed", () => {
    expect(releaseVersion(undefined, '2.3.3')).toBe('2.3.3');
    expect(releaseVersion('', '2.3.3')).toBe('2.3.3');
    expect(releaseVersion('   ', '2.3.3')).toBe('2.3.3');
    expect(releaseVersion('v2.3.3', '2.3.3')).toBe('2.3.3');
    expect(releaseVersion('2.3', '2.3.3')).toBe('2.3.3');
    expect(releaseVersion('2.3.3-beta', '2.3.3')).toBe('2.3.3');
    expect(releaseVersion('dev', '2.3.3')).toBe('2.3.3');
    expect(releaseVersion('tally-v2.3.4', pkg.version)).toBe(pkg.version);
  });
});
