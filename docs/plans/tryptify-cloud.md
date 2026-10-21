# Tryptify Cloud: plan

Agreed with the owner on 2026-10-21; not started. A paid subscription that
stores a listener's own music and streams it anywhere, for people who do not
want to host a server. Build it in its own session, one phase per session
(CLAUDE.md: one feature per session).

## What it is

Upload your own songs from the phone; play them from any phone signed in to
the same Tryptify account. Private: a listener sees and plays only what they
uploaded. The alternative to Settings › Connections › Music server for someone
with no computer or NAS.

## Decisions

- **It speaks the Subsonic API.** The app already plays any Subsonic server
  (`data/subsonic/`, the My server page, Search, `PlaybackSource.Subsonic`), so
  a cloud that answers the same calls needs almost no new playback code. Only
  the subset the app uses: `ping`, `getAlbumList2`, `getAlbum`, `getArtists`,
  `getArtist`, `search3`, `getRandomSongs`, `stream` (with HTTP Range, so
  seeking works), `getCoverArt`. Plus its own upload and account routes.
- **Same shape as the Theme Store** (`server/theme-store/`): one Bun service on
  Railway, its own Railway Postgres, and a Railway storage bucket for the
  files. Sign-in is the app's Supabase account, checked the way the Theme
  Store checks it (public JWKS, or Supabase Auth for HS256). The service holds
  no Supabase secret.
- **Subsonic sign-in for the cloud:** the app cannot send a Supabase JWT as
  `u`/`t`/`s`. The cloud issues a per-device API password when the app
  connects (POST with the Supabase token), and the app stores it as an ordinary
  `SubsonicServer` in token mode. Revoked on sign-out or cancellation.
- **Private locker, never a distributor** (see Legal). No sharing, no public
  links, and no cross-user de-duplication: two people uploading the same song
  store two copies.
- **Payments: both paths, one entitlement.**
  - Play Store installs: Google Play Billing subscriptions, verified on the
    server with the Play Developer API, renewals and cancellations from Real-time
    developer notifications.
  - APK / website installs: a merchant of record (Lemon Squeezy or Paddle, to
    be picked), which handles VAT and sales tax, refunds and receipts; its
    webhooks update the same record.
  - Both write one `entitlements` row per account (plan, storage limit,
    status, period end, source). The service reads only that row.
- **Files are kept as uploaded.** No transcoding in phase 1.

## Phases and gates

1. **Server, free private beta.** Accounts, upload (resumable, size and type
   checked, tags read on the server), storage quota per account, the Subsonic
   subset above, delete. Owner-only allow-list, no payments.
   Gate: the app's existing Subsonic code plays it with no changes; a second
   account sees none of the first's music; quota refuses an upload over the
   limit; a `test.ts` like the Theme Store's.
2. **App.** "Tryptify Cloud" in Settings › Connections › Music server (sign in
   with the Tryptify account, no address to type), upload from Local (a song,
   an album, a folder) with progress, usage shown against the limit. Strings in
   all seven languages, `docs/ui-invariants.md` updated.
3. **Payments.** Play Billing and the web checkout, webhooks, the entitlement
   row, grace period on failed payment, what happens to files on cancel
   (read-only, then deleted after a stated time, with a warning).
4. **Launch.** Everything under Legal done, pricing set from measured costs,
   the allow-list removed.

## Legal (have a lawyer review before charging money)

- Terms of Service and a privacy policy: what is stored (files, tags, account,
  payment status), where, for how long, and deletion on request (GDPR).
- Private locker only, as above. Sharing turns it into distribution, which
  needs licences from rights holders.
- Copyright takedowns: register a DMCA agent with the US Copyright Office;
  notice-and-action under the EU Digital Services Act; a repeat-infringer rule.
- Refund and cancellation terms; taxes through the merchant of record (web) and
  Google (Play).
- The open items from the 2026-10-21 licensing review still stand and should
  be done first: a root LICENSE (the source headers say GPL-3.0-or-later),
  the upstream Monochrome licence, an in-app open-source licences screen, a
  "not affiliated" line for the services the app names, and Android backup
  rules that leave sign-ins out.

## Open questions for the owner

- Plans and prices (e.g. storage tiers). Set after phase 1 measures the real
  storage and bandwidth cost per GB on Railway.
- Which merchant of record for the web path.
- How long files are kept after a subscription ends.
