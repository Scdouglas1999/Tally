# The part only you can do (about 15 minutes, then a wait)

1. Go to https://play.google.com/console/signup and sign in with the Google account you want to own the app.
2. Choose **Yourself** (personal account). Developer name: anything, e.g. `JellyTV`. It is public, as is your legal
   name once verified.
3. Accept the Developer Distribution Agreement and pay the **$25** one-time fee.
4. Verify your identity: photo of a government ID, and a phone number for a code.
5. On an Android phone, install the **Google Play Console** app, sign in, and complete **device verification**
   (Google requires new personal accounts to prove they have a real Android device).
6. Wait for the "identity verified" email. Usually one to a few days. Nothing else can happen before this.
7. In the console: **Create app** → name `JellyTV for Jellyfin`, App, Free, tick the two declarations.
8. Everything after that is paste-and-upload from `LISTING.md`. Two ways to finish:
   - do it yourself in ~30 minutes following `LISTING.md`, or
   - Setup → **API access** → create a service account, grant it "Release manager" on this app, download its
     JSON key to `~/.config/jellytv/play-api.json`. From then on `jellytv/release.sh --publish` can push every new
     version to the internal testing track without you.

What you never need: a company, a website, a D-U-N-S number, or the 12-testers/14-days closed test (that is only
for a public production listing; internal testing covers up to 100 invited people).
