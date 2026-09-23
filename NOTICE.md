# Notice

## Tally is a modified version of Wholphin

Tally is a fork of **[Wholphin](https://github.com/damontecres/Wholphin)**, the open-source Android TV client for
Jellyfin by damontecres and its contributors. Wholphin is licensed under the **GNU General Public License,
version 2** ([LICENSE](LICENSE)), and so is Tally: every file in this repository, original and modified, is
distributed under the same license. Tally is not affiliated with, reviewed by or endorsed by the Wholphin project.
Please report problems with Tally [here](https://github.com/Scdouglas1999/Tally/issues), not to Wholphin.

## Source code

The complete source for every Tally release is in this repository. Each release on the
[Releases page](https://github.com/Scdouglas1999/Tally/releases) is built from the tag it is attached to.

## What was changed, and when

Tally's changes to Wholphin began in September 2026. Every change, with its author and date, is recorded in this
repository's git history; the fork starts after Wholphin's `v1.0.8` release.

- **New code.** Everything Tally adds lives in its own package, `app/src/main/java/io/github/scdouglas1999/tally/`,
  with its tests under `app/src/test/java/io/github/scdouglas1999/tally/` and its strings in `app/src/main/res/values/strings_tally*.xml`.
- **Modified Wholphin files.** Changes to Wholphin's own files are kept small and are marked in place with
  `TALLY: begin` / `TALLY: end` comments; each modified source file also carries a notice at its top. The
  complete list, with what each change does, is the seam table in [`TALLY.md`](TALLY.md).
- **Changed without an in-file notice** (these files cannot carry one, or would lose it):
  - the launcher icons and TV banner (`app/src/main/res/mipmap-*/ic_launcher*`, `ic_banner*`), replaced with
    Tally's own artwork;
  - `app/src/main/res/values/strings.xml`: only the app name (`app_name`, `app_name_long`) is changed; the file
    is otherwise Wholphin's and is maintained by Wholphin's translation workflow;
  - `.gitignore`: Tally build outputs added.
- **Identity.** Tally has its own name, icon and application id (`io.github.scoduglas1999.jellytv`), so it
  installs alongside Wholphin and cannot be mistaken for it.

## Third-party material

- **IBM Plex** Sans and Mono fonts, © IBM Corp., under the SIL Open Font License 1.1 ([IBM-PLEX-OFL.txt](IBM-PLEX-OFL.txt)).
- The libraries Wholphin uses, under their own licenses, as in Wholphin.

## Trademarks

Jellyfin is a trademark of the Jellyfin project; Tally is an independent client and is not affiliated with it.
League and team names and logos belong to their owners. Tally stores none of them: the optional Tally server
plugin reads public scoreboard data at run time, and Tally is not affiliated with or endorsed by any league, team or
data provider.
