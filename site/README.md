# Tally website

The public site for Tally: plain HTML, one stylesheet, one script. No build step, no framework, no tracking.

## What's where

| Path | What |
|---|---|
| `index.html`, `features.html`, `live-sports.html`, `download.html`, `how-it-works.html`, `faq.html`, `changelog.html`, `support.html`, `privacy.html`, `legal.html`, `404.html` | The pages. Each is complete on its own: edit the text right in the file. |
| `assets/css/site.css` | All styles. Tokens (colors, fonts) are at the top and match the app's (`tally/UI.md`). |
| `assets/js/site.js` | Menu, scroll reveals, the live version, copy buttons, and the motion pieces (hero demo, Pulse demo, ticker, rundown, ladder). |
| `assets/img/`, `assets/media/` | Screenshots (webp, a 1280 and a 640 wide copy of each) and two short mp4 clips with posters. |
| `assets/fonts/` | IBM Plex Sans and Mono (woff2), with their license, `OFL.txt`. |
| `assets/og.png` | The 1200x630 social card. |
| `favicon.svg`, `favicon.ico`, `apple-touch-icon.png`, `robots.txt`, `sitemap.xml`, `_headers` | The usual. `_headers` sets caching on Cloudflare Pages and Netlify. |
| `tools/build-preview.py` | Builds `preview/`, a copy of the site for a single-file Artifact preview (see `preview/MANIFEST.txt`). |

The header, footer and `<head>` are repeated in every page. When you change one (a new nav entry, say), change it
in all eleven files; they're identical blocks, so a search and replace does it.

## Before you deploy: your domain

Every page uses the placeholder `https://tally.example` for its canonical URL and social card, and so do
`sitemap.xml` and `robots.txt`. Replace it with your domain everywhere:

```sh
cd site
grep -rl 'https://tally.example' --include='*.html' --include='*.xml' --include='*.txt' . \
  | xargs sed -i 's#https://tally.example#https://your-domain.com#g'
```

(On macOS: `sed -i ''`.) Canonical URLs are extensionless (`/features`), which all three hosts below serve.

## Deploying

The site is the `site/` folder as it is. Leave `preview/` and `tools/` out if you like; they're harmless.

**Cloudflare Pages.** Workers & Pages → Create → Pages → connect the repository. Framework preset: none. Build
command: empty. Build output directory: `site`. Then Custom domains → add your domain; Cloudflare sets up the DNS
record if the domain is on Cloudflare, and shows you the CNAME if not. `404.html` is used for missing pages and
`_headers` is applied automatically.

**Netlify.** Add new site → import the repository. Base directory: `site`. Build command: empty. Publish directory:
`site`. Domain management → add a custom domain and follow the DNS step (a CNAME to your `*.netlify.app` name, or
Netlify DNS). `404.html` and `_headers` work as they are. Netlify's Pretty URLs (on by default) serves `/features`.

**GitHub Pages.** The simplest way is a workflow that publishes the folder: Settings → Pages → Source: GitHub
Actions, then add `.github/workflows/pages.yml` using `actions/upload-pages-artifact` with `path: site` and
`actions/deploy-pages`. For a custom domain, enter it under Settings → Pages → Custom domain (it adds a `CNAME`
file), and point a CNAME record at `<user>.github.io` (or the four `A` records GitHub lists for an apex domain).
Tick Enforce HTTPS. GitHub Pages ignores `_headers`. Serve the site from the root of a domain: `404.html` links
to `/assets/…`, which breaks under a project path like `user.github.io/Tally/`.

## The version number

Pages show the latest release live from `https://api.github.com/repos/Scdouglas1999/Tally/releases/latest` (once
per visit, cached for the session). Until it answers, or if it can't, they show the fallback written in the HTML:
`<span data-version>2.3.3</span>`. After a release, update the fallback:

```sh
cd site
grep -rl 'data-version>2.3.3<' --include='*.html' . | xargs sed -i 's/data-version>2.3.3</data-version>2.3.4</g'
```

The home page's structured data (`"softwareVersion"` in `index.html`) has the same number. Add the release to
`changelog.html` by copying an `<article class="rel">` block. Download buttons point at
`https://github.com/Scdouglas1999/Tally/releases/latest/download/<file>`, so they never need changing unless an
asset is renamed; the per-Jellyfin plugin zips carry the version in their names, so those links are filled in from
the same API answer and fall back to the releases page.

## Pictures

Screenshots come from `tally/readme/` and the workbench's README captures, with made-up teams. To replace one,
export a 1280 wide webp and a 640 wide one (`name.webp`, `name-640.webp`) into `assets/img/`, for example:

```sh
magick shot.png -resize 1280x -quality 78 assets/img/name.webp
magick shot.png -resize 640x -quality 76 assets/img/name-640.webp
```

Never use pictures with real league, team or network logos. Footage credits are on `legal.html`.

## Motion and accessibility

Everything moves only when the visitor hasn't asked for reduced motion (`html.motion`, set by `site.js`).
Without JavaScript, or with reduced motion, every section is visible and the demos show a still frame. Autoplaying
demos have pause buttons, and they stop when scrolled out of view.

## Checking it locally

```sh
cd site && python3 -m http.server 8000
```

Then open `http://localhost:8000/`. (`python3 -m http.server` doesn't serve extensionless URLs or `404.html`; the
real hosts do.)
