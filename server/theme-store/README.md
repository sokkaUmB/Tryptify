# Theme Store server

The API behind the app's Theme Store. `index.ts` is the whole service: one
file, run by Bun as a Railway Function, with its own Railway Postgres. The app
calls it from `ThemeStoreRepository`, at the address in the `THEME_STORE_URL`
build setting (`themeStore.url` in `local.properties` overrides it).

## Where it runs

Railway project **humble-charm**, environment **production**:

| Service       | What it is                                              |
| ------------- | ------------------------------------------------------- |
| `theme-store` | This file, at `https://theme-store-production-a3db.up.railway.app` |
| `Postgres`    | The packs, likes, downloads and reports                 |

`theme-store` has one variable, `DATABASE_URL = ${{Postgres.DATABASE_URL}}`
(a reference, so the password never leaves Railway). It creates its tables on
start, and waits up to a minute for the database if it is still starting.

## Sign-in

There is no account system here. The app is signed in with Supabase, and every
request carries that session's access token. How the server checks it depends
on what signed it:

- **An asymmetric key (ES256, RS256).** Checked here, against the project's
  **public** keys (`/auth/v1/.well-known/jwks.json`), its issuer and the
  `authenticated` audience; the person is the token's `sub`.
- **The project's shared secret (HS256).** This service does not have that
  secret, so it asks Supabase Auth (`GET /auth/v1/user`, with the same public
  key the app ships) who the token belongs to, and remembers the answer until
  the token expires, five minutes at most.

A project can publish an ES256 key while still signing with its shared secret
(the new key waiting as a standby), so both have to work. Either way the service
holds no Supabase secret, and nothing in it can sign in as anyone. If Supabase
Auth does not answer, the store says `auth_unreachable` (502) rather than
`signed_out`, so the app does not throw away a session that may be fine.

## Routes

`GET /health` answers without an account. Everything under `/v1` needs one:

| Route                          | Does                                                    |
| ------------------------------ | ------------------------------------------------------- |
| `GET /v1/packs?sort=&q=&page=` | The store, 20 a page. `sort` is `popular`, `new` or `mine` |
| `GET /v1/packs/:id`            | One pack, the pack itself included                      |
| `GET /v1/codes/:code`          | A pack by its share code, listed or not                 |
| `POST /v1/packs`               | Publish. At most 50 per person; the pack at most 64 KB  |
| `PATCH /v1/packs/:id`          | `{"listed": bool}`, author only                         |
| `DELETE /v1/packs/:id`         | Author only                                             |
| `GET /v1/likes?ids=`           | Which of those ids this person likes                    |
| `PUT` / `DELETE /v1/packs/:id/like` | Like, unlike                                       |
| `POST /v1/packs/:id/download`  | Counted once per person and pack                        |
| `POST /v1/packs/:id/report`    | One report per person and pack                          |
| `GET /v1/packs/:id/snapshot`   | The pack's store picture                                |
| `PUT /v1/packs/:id/snapshot`   | Set or replace it, author only: the image as the body   |

A store picture is the phone-shaped poster the app draws of a pack with its own
renderers when it is shared (lyrics, player, and the mini player and tab bar),
as WebP, at most 160 KB. The server takes WebP, PNG or JPEG by their first
bytes, whatever the upload says it is. Every listing row carries `snapshot`, a
short hash of the picture or null, and the app keeps pictures by that hash, so
a replaced picture is fetched once and an unchanged one never again.

Errors are `{"error": code}`: `signed_out` (401), `bad_pack` and `bad_image` (400),
`not_found` (404), `limit_reached` (409), `too_big` (413), `auth_unreachable`
(502), `not_set_up` (503, no database). `StoreWire.errorOf` in the app reads
exactly these; anything else is treated as the network failing.

The list of theme parts the server accepts and the 64 KB pack cap have to match
the app's `ThemePart` and `ThemePackCodec.MAX_BYTES`. `ThemeStoreWireTest`
reads this file and fails if they drift apart.

## Testing

Against a throwaway local Postgres (it drops the store's tables), with a
stand-in for Supabase Auth — public keys for a signing key made in the test,
and its "who is this" endpoint:

```
TEST_DATABASE_URL=postgres://postgres@127.0.0.1:55432/store bun run test.ts
```

It is a plain script rather than `bun test`, because the test runner does not
fetch versioned imports like `hono@4` and a plain run does — the same way
Railway runs `index.ts`.

## Deploying a change

Railway Functions hold their code themselves; nothing deploys from this
repository on push. After changing `index.ts`, run the test, then replace the
function's code with the whole file (the Railway dashboard's editor, or the
Railway MCP's `update-function-source-code`), and check `/health` afterwards.
This file is the source of truth: never edit the deployed code only.
