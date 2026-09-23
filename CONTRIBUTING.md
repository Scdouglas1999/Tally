# Contributing to Tally

Bug reports, ideas and pull requests are all welcome. Tally is a small project, so a clear report goes a long way.

## Reporting a problem

Open an [issue](https://github.com/Scdouglas1999/Tally/issues/new/choose). The form asks for your Tally version (it's
at the bottom of Settings), your TV or streaming box, and whether the server has the Tally plugin. A photo of the
screen is often the quickest way to show what's wrong.

Tally is built on [Wholphin](https://github.com/damontecres/Wholphin). If something also goes wrong in Wholphin with
one of its own themes, it's most likely Wholphin's to fix. Report it there as well, and link the two.

## Changing the code

- Tally's code lives in `app/src/main/java/io/github/scdouglas1999/tally/`. Everything under
  `com/github/damontecres/wholphin/` is Wholphin's, and Tally only changes those files at marked seams, so it can
  keep taking Wholphin's updates. [TALLY.md](TALLY.md) explains how that works and lists every seam.
- The look is specified in [tally/UI.md](tally/UI.md): the colors, type, spacing and how focus is drawn. New screens
  follow it.
- Test on a TV or an Android TV emulator, with a remote or the D-pad keys, and include screenshots of anything you
  changed on screen.
- Format Kotlin with ktlint (the version is in [.pre-commit-config.yaml](.pre-commit-config.yaml)) and run the unit
  tests: `./gradlew :app:testDefaultDebugUnitTest`.
- Write user-facing text in American English, with units in their normal case (`1080p`, `14.8 Mbps`, `2h 35m`).

Wholphin's developer guide still describes the shared code well: [docs/WHOLPHIN-DEVELOPMENT.md](docs/WHOLPHIN-DEVELOPMENT.md).

By sending a pull request you agree that your code is licensed under the [GPL, version 2](LICENSE), like the rest of
Tally.

## Code of conduct

Tally follows [Jellyfin's community standards](https://jellyfin.org/docs/general/community-standards/#code-of-conduct).
