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

## Motion and designs

`js/site.js` animates the pages with Motion (motion.dev, MIT), served from
`js/motion-12.23.12.js` with its licence beside it: the hero's live spectrum,
words arriving one by one, sections sliding in, screenshots dealt and tilted,
the pricing switch counting, springy buttons. All of it is decoration: with
"reduce motion" on, without JavaScript, or if Motion fails to load, the pages
are the same, standing still.

While the owner chooses, there are six colour designs (Mono, Neon, Ember,
Mint, Acid, Paper), set by `<html data-design>`, picked with the dots at the
bottom of every page or compared live at `/designs`. Once one is chosen, make
it the `:root` colours and delete the others, the picker and `designs.html`.
