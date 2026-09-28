# Tally: privacy policy

Last updated: 28 September 2026

Tally is open-source software for a Jellyfin media server that you, or someone you know, runs. It has four parts:
the Android app, the Jellyfin server plugin, the Tally TV app for Samsung and LG TVs, and the installers. The
developer runs no service that any of them talks to, and receives no data from them. There's no advertising, no
analytics, no tracking and no account with the developer in any part of Tally.

This page says, for each part, what it stores and which other computers it talks to.

## The Android app

Tally for Android TV, Google TV, Fire TV and Android phones and tablets. It's a fork of
[Wholphin](https://github.com/damontecres/Wholphin).

**What the app stores, and where.** The addresses of the Jellyfin servers you add, the access token each server
issues when you sign in, your app settings, and anything you download for offline viewing. All of it stays on your
device. Uninstalling the app deletes it.

**Who the app talks to.**
- The Jellyfin server(s) you add. Everything you browse and watch is requested from that server, and playback
  progress is reported to it, as with any Jellyfin client. What that server logs is up to whoever runs it.
- When the server has the Tally plugin, the Sports screens show team logos, which load from ESPN's image server
  (`a.espncdn.com`), and channel logos, which load from the addresses your server's sources give.
- If you switch on the optional Discover feature, the Seerr server whose address you enter.
- Builds installed from outside an app store check `api.github.com` for a newer release, and download it from
  GitHub when you choose to update. The request contains nothing about you or your device beyond what any web
  request carries (your IP address). Builds installed from Google Play or the Amazon Appstore do not do this; the
  store updates them.

**Crash reports.** If the app crashes it asks whether to send a report. Saying yes sends it to your own Jellyfin
server, never to the developer. Saying no sends nothing.

**What the app does not do.** No location, contacts, camera or microphone access (voice search uses your TV's own
search, outside this app).

## The server plugin

The Tally plugin runs inside the Jellyfin server of whoever installs it.

**What it stores, and where.** Everything stays on that server, in Jellyfin's plugin folders: the plugin's settings
(including the sources you add, with any headers you give them), each user's Tally settings (favorite channels,
followed teams, Hide scores), recordings and their settings, cached artwork, and, for each LG TV that uses Tally,
that TV's Developer Mode session token (see below). Its log lines go to Jellyfin's own log. Uninstalling the plugin
and deleting its folders removes all of it.

**Who it talks to.**
- **Your own sources.** The playlists, guides, streams and web pages you add in its settings. It requests them from
  the server, with the headers you set, to list channels, check streams, and play and record them.
- **ESPN's public scoreboard,** for live scores (`site.web.api.espn.com` and `site.api.espn.com`): the scoreboard of
  each league on the board, and each league's team list. It asks while someone has Tally open, while a recording is
  scheduled, and at most once a minute while a web page source is enabled. It also downloads team logos from ESPN's
  image server (`a.espncdn.com`) to draw game cards. These requests name a league and a date, nothing about you or
  your users. Switching off live scores in the plugin's settings stops them.
- **GitHub, for updates.** On first start the plugin adds Tally's plugin repository
  (`raw.githubusercontent.com/Scdouglas1999/Tally/main/server/manifest.json`) to Jellyfin's list, and Jellyfin
  checks it for new versions the way it checks its own catalog, downloading updates from `github.com`. When someone
  installs the Android TV app through the server's install page, the plugin downloads the newest `Tally.apk` from
  GitHub's releases.
- **LG's developer-mode service** (`developer.lge.com`), only when an LG TV uses Tally. LG turns off an app installed
  through Developer Mode when the TV's session runs out, so the TV gives the plugin its session token, and once a day
  the plugin sends that token to LG to renew the session and read the time left. Nothing else is sent. The token is
  never shown by any endpoint, and a TV whose renewal fails for 14 days is forgotten.
- **Only when you add a web page source:** a one-time download of a headless browser the plugin uses to read such
  pages: Node.js from `nodejs.org`, the playwright-core package from `registry.npmjs.org`, and, if the server has no
  Chrome or Edge, Chromium from Microsoft Playwright's download servers. On Linux it may also install Chromium's
  system libraries from the server's own package repositories.

The plugin sends nothing to the developer. Jellyfin apps and web browsers that use the plugin load team logos from
`a.espncdn.com` directly.

## The Tally TV app for Samsung and LG TVs

Tally TV is a web app the plugin serves to Samsung and LG TVs; a small shell installed on the TV loads it from your
Jellyfin server.

**What it stores, and where.** The server's address, the access token the server issues when you sign in, and your
settings, in the TV's storage for the app. Removing the app from the TV deletes them.

**Who it talks to.**
- Your Jellyfin server, for everything you browse and watch, as with any Jellyfin client. The app itself is
  downloaded from that server each time it starts.
- Team logos load from ESPN's image server (`a.espncdn.com`), and channel logos from the addresses your server's
  sources give.
- **On LG TVs:** the TV's Developer Mode session token, which the LG installer puts into the app, is sent to your
  Jellyfin server after someone signs in, so the server can keep Developer Mode on (see the server plugin above).

No crash reports, analytics or update checks go anywhere else.

## The installers

`Tally-Server-Setup.exe`, the Docker Compose file, the Linux script, and Tally for Samsung and Tally for LG (the
programs that put Tally TV on a TV). They run once, on your computer, when you choose to.

- **Tally-Server-Setup.exe** installs the plugin it carries. If the computer has no Jellyfin, it downloads the
  official Jellyfin installer from `repo.jellyfin.org`. It writes a log to the computer's temp folder.
- **The Docker Compose file and the Linux script** download the plugin from GitHub's releases. The Linux script also
  runs Jellyfin's official install script from `repo.jellyfin.org` when the server has no Jellyfin, and installs
  curl and unzip from the server's own package repositories if they're missing.
- **Tally for Samsung and Tally for LG** look for TVs on your home network, talk to the TV you pick, and check your
  Jellyfin server's address with that server. Tally for Samsung also, for TVs from 2023 on, has you sign in to your
  Samsung account in your browser (`account.samsung.com`) to get a signing certificate from Samsung's certificate
  service, and downloads Samsung's public Tizen signing files from `download.tizen.org`. The certificates it makes
  (Tally for Samsung) and each TV's key and passphrase (Tally for LG) are kept on your computer, in your user's
  app-data folder, for the next install. Each writes a log to the computer's temp folder.

No installer sends anything to the developer.

## Children

Tally has no content of its own; it shows what the connected Jellyfin server offers.

## Contact

Open an issue at https://github.com/Scdouglas1999/Tally/issues
