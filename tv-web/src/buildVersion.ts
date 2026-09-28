/**
 * The version the build gives the bundle (the 2.3.3 version contract): `TALLY_VERSION`, the plugin's release version
 * that server/build.sh and tally/release.sh pass in (a plain `x.y.z`, from the release tag), else package.json's
 * version (the dev fallback: `npm run dev`, `npm test`, a plain `npm run build`). A missing, blank or malformed
 * value ("v2.3.3", "2.3", "dev") falls back. Build time only: vite.config.ts puts the result in `__TALLY_VERSION__`
 * (Settings, the Jellyfin client info) and in manifest.json.
 */
export function releaseVersion(env: string | undefined, packageVersion: string): string {
  const v = env?.trim();
  return v !== undefined && /^\d+\.\d+\.\d+$/.test(v) ? v : packageVersion;
}
