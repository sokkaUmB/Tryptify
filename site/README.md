# Tryptify website

The public site for the Tryptify app and Tryptify Cloud: what they are, the
Cloud's plans and prices, Terms, Privacy, Refunds and Contact. Creem (the
payment provider) reviews it before the store can take live payments.

Plain static files in `public/`, served by Cloudflare (Workers static assets,
no code). It goes on `tryptify.seapengine.lol` once the `seapengine.lol` zone is
active on Cloudflare; until then it is at the `workers.dev` address the deploy
prints.

```
cd site
CLOUDFLARE_API_TOKEN=... npx wrangler deploy
```

Keep the prices, the trial and the grace periods in step with
`server/cloud/src/rules.ts`; the app reads them from the server, this page does
not. All text is Orbitron, the app's font, in its six weights (Regular 400 to
Black 900, Latin) as WOFF2 under `public/fonts/`, with its licence
`public/fonts/orbitron-license.txt`.
