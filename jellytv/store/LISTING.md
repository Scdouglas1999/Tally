# Store listing: paste-ready

## Google Play

| Field | Value |
|---|---|
| App name (30) | `JellyTV for Jellyfin` |
| Short description (80) | `A Jellyfin client for your TV with a live sports board, score bug and multiview.` |
| App or game / Category | App / Video Players & Editors |
| Free or paid | Free |
| Contact email | your email (it is shown publicly on the listing) |
| Privacy policy URL | `https://github.com/Scdouglas1999/Tally/blob/jellytv/jellytv/PRIVACY.md` |
| Package | `io.github.scoduglas1999.jellytv` |

Full description:

```
JellyTV is a client for your own Jellyfin media server, made for the TV and the remote.

Everything you expect from a Jellyfin client is here: movies, shows, music, live TV, subtitles and audio
tracks, direct play with a modern player. It is built on the open-source Wholphin client.

If your server runs the JellyTV plugin, a JellyTV section appears in the menu:
• Games: every live and upcoming game your channels carry, as big cards you can read from the sofa, with a
  panel showing the score, the clock and the last play for the game in focus.
• A score bug over the picture, and a switcher: press Down while watching to jump to another live game.
• Multiview: up to four channels at once. Sound follows the tile you are on.
• Start a game on the TV from your phone.
• Hide scores, if you are watching later.

You need a Jellyfin server. This app has no content of its own and does not provide any channels or streams.
Without the JellyTV plugin it is a regular Jellyfin client.

Open source (GPL-2.0): github.com/Scdouglas1999/Tally
Not affiliated with the Jellyfin project.
```

Graphics (all in `jellytv/store/graphics/`): `icon-512.png`, `feature-1024x500.png`, `tv-banner-1280x720.png`,
TV screenshots `tv-1…3`. **Do not upload screenshots that show league or team logos**; the three provided are clean.
Phone screenshots are not needed: the Play build is TV-only.

### App content questionnaire (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | the URL above |
| App access | "All or some functionality is restricted" → add instructions: "Needs the address of a Jellyfin server. Reviewers can use the public demo: https://demo.jellyfin.org/stable, user `demo`, empty password." |
| Ads | No ads |
| Content rating | Category "Utility, productivity, communication or other"; every violence/sexuality/language/drugs/gambling question: No; "users can interact or exchange content": No; "shares location": No |
| Target audience | 18 and over (simplest; avoids the families policy) |
| News app | No |
| Data safety | Collects or shares user data: **No**. (Everything stays on the device or goes to the user's own server; that is not "collection" by the developer under Play's definition.) Encrypted in transit: not applicable once "No". |
| Government app / Financial features / Health | No / none / none |
| Advertising ID | No |

### Release settings

- Release → Setup → **App signing**: choose *"Use a key you provide"* / "Export and upload a key from Java keystore"
  and upload from `~/.config/jellytv/release.jks` (alias `jellytv`). This keeps the Play build and the sideloaded
  build interchangeable. If Google only offers its own key for a new app, accept it: the only consequence is that
  someone who sideloaded must uninstall once before installing from Play.
- Release → Setup → Advanced settings → **Form factors** → add **Android TV**, upload the TV banner and TV screenshots.
- Testing → **Internal testing** → create an email list with your friends' Google addresses (the ones their TVs
  use) → create release → upload `jellytv/out/JellyTV-play.aab` → roll out. Send them the opt-in link from the
  Testers tab. Up to 100 testers; no 12-testers-for-14-days requirement; usually live within minutes to hours.

## Amazon Appstore (Fire TV)

Free developer account at developer.amazon.com. New app → Android → upload `jellytv/out/JellyTV-amazon.apk`,
device support: Fire TV only. Same title, descriptions and graphics (Amazon wants a 1280x720 "Fire TV background"
and 1920x1080 screenshots; the banner and the three screenshots fit). Content rating: all "None". Amazon reviews
every submission (typically a few days). "Live App Testing" is Amazon's invite-only equivalent of internal testing.
