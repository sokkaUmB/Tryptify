# Tryptify website

The public site for the Tryptify app and Tryptify Cloud: what they are, the
AutoEQ ranking (SeapEngine's Rankings tab, embedded from seapengine.lol), the
Cloud's plans and prices, Terms, Privacy, Refunds and Contact. Creem (the
payment provider) reviews it before the store can take live payments.

Plain static files in `public/`, served by Cloudflare (Workers static assets,
no code), at https://tryptify.com (and www). Support mail to
support@tryptify.com is forwarded by Cloudflare Email Routing.

```
cd site
CLOUDFLARE_API_TOKEN=... npx wrangler deploy
```

Keep the prices, the trial and the grace periods in step with
`server/cloud/src/rules.ts`; the app reads them from the server, this page does
not. All text is Orbitron, the app's font, in its six weights (Regular 400 to
Black 900, Latin) as WOFF2 under `public/fonts/`, with its licence
`public/fonts/orbitron-license.txt`.

## Motion, glass, designs and fonts

`js/site.js` animates the pages with Motion (motion.dev, MIT), served from
`js/motion-12.23.12.js` with its licence beside it: the hero's live spectrum,
words arriving one by one, sections sliding in, screenshots dealt and tilted,
the pricing switch counting, springy buttons. All of it is decoration: with
"reduce motion" on, without JavaScript, or if Motion fails to load, the pages
are the same, standing still.

The menu is liquid glass after the app's tab bar: clear glass with a light
frost, bent at the rim by an SVG lens where Chromium allows `backdrop-filter:
url()` (`.lens`), a bright rim, a reflection, a glint that follows the pointer,
and a lens bubble that springs to the current page or the hovered link. On
phones the menu leaves the header for a floating tab bar at the bottom.

While the owner chooses, there are three colour designs (Dark, Warm, White,
`<html data-design>`) and eleven fonts, the app's own (`<html data-font>`,
licences in `public/fonts/LICENSES.txt`), picked from the bar at the bottom
of every page or compared at `/designs`. Once chosen, make them the `:root`
values, then delete the others, the picker and `designs.html`.

## Pages with code

- `/account` (`js/account.js`): Your Cloud, the web app. Signs in with the
  same Supabase account as the app (`js/vendor/supabase-2.117.3.js`) and uses
  the cloud server's `/v1` API like the app does: plan, checkout, billing
  portal, uploads in the server's parts (tags and covers read in the browser
  by `js/vendor/jsmediatags-3.9.7.min.js`), the library, and the devices
  signed in to the Subsonic API. Google sign-in needs
  `https://tryptify.com/account` in Supabase's Auth › URL Configuration ›
  Redirect URLs.
- `/autoeq` (`js/autoeq.js`, `js/measures.js`, `js/graphs.js`): the AutoEQ ranking, the measures explained, and the frequency-response and EQ graphs (curves in `data/autoeq-curves.json`), from a snapshot of
  SeapEngine's bench results in `data/autoeq-bench.json` (SeapEngine sends no
  CORS header, so it cannot be read live). Refresh it from
  `https://seapengine.lol/rankings/bench.json` keeping only the metrics.
- `/download`: the APK lives in the R2 bucket `tryptify-downloads` (too big
  for a static file) and `src/worker.js` serves it at
  `/download/tryptify.apk`, with ranges. Publish a new one with
  `wrangler r2 object put tryptify-downloads/tryptify.apk --remote --file … --content-type application/vnd.android.package-archive --content-disposition 'attachment; filename="Tryptify-<version>.apk"'`
  and update `data/apk.json` (version, size, SHA-256). The public APK is built
  without the Trypt Mania server secrets: `env -u TRYPTMANIA_CPU_SERVER -u
  TRYPTMANIA_ROOMS_SERVER ./gradlew :app:assembleRelease`, and checked with
  the BuildConfig greps in AGENTS.md before upload.

## The AutoEQ try-out

`/autoeq`'s "Try it" runs the app's AutoEQ in the browser:
`js/autoeq-engine.js` is a port of `AutoEqEngine.kt`, `EqDspKernels.kt`, the
measurement parser and `AutoPreamp.peakBoostDb`. `WebAutoEqFixtureTest` (app
unit tests) holds the Kotlin engine to `tests/autoeq-engine-fixtures.json`, and
`node site/tests/engine-parity.mjs` holds the port to the same answers (same
bands, gains within 0.01 dB; exact digits are impossible across the JVM and a
browser, see the script). Change the engine, rewrite the fixtures, port it.

The headphone list, `data/headphones.json`, comes from
`python3 tools/headphone_index/build.py` (AutoEq's `results/INDEX.md` and the
twelve squig.link phone books the app searches); re-run it now and then. The
targets in `data/targets/` are copies of the app's `assets/autoeq/targets/`.
