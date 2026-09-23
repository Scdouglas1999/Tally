# Tally for Android TV: privacy policy

Last updated: 21 September 2026

Tally for Android TV is an open-source client for a Jellyfin media server that you, or someone you know, runs.
It is a fork of [Wholphin](https://github.com/damontecres/Wholphin). The developer of this app runs no service
that the app talks to and receives no data from it.

**What the app stores, and where.** The addresses of the Jellyfin servers you add, the access token each server
issues when you sign in, and your app settings. All of it stays on your device. Uninstalling the app deletes it.

**Who the app talks to.**
- The Jellyfin server(s) you add. Everything you browse and watch is requested from that server, and playback
  progress is reported to it, as with any Jellyfin client. What that server logs is up to whoever runs it.
- If you switch on the optional Discover feature, the Seerr server whose address you enter.
- Builds installed from outside an app store check `api.github.com` for a newer release. The request contains
  nothing about you or your device beyond what any web request carries (your IP address). Builds installed from
  Google Play or the Amazon Appstore do not do this; the store updates them.

**Crash reports.** If the app crashes it asks whether to send a report. Saying yes sends it to your own Jellyfin
server, never to the developer. Saying no sends nothing.

**What the app does not do.** No advertising, no analytics, no tracking, no accounts with the developer, no
location, contacts, camera or microphone access (voice search uses your TV's own search, outside this app).

**Children.** The app has no content of its own; it shows what the connected server offers.

**Contact.** Open an issue at https://github.com/Scdouglas1999/Tally/issues
