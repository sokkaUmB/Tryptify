# Tryptify Cloud server

Paid, private storage for a listener's own music. The plan, prices and rules
are in [`docs/plans/tryptify-cloud.md`](../../docs/plans/tryptify-cloud.md).
`index.ts` is the whole service: one file, run by Bun as a Railway Function,
with its own Railway Postgres, signed in with the app's Supabase account the
same way as the [Theme Store](../theme-store/README.md).

## Status

Accounts and billing are written and tested, with Creem as the payment
provider. Not deployed yet: Railway was pausing deploys for Hobby workspaces
when this was written. Until the Creem variables are set, checkout and the
webhook answer `billing_not_set_up`, and nobody can pay or be granted
anything. Storage and the Subsonic API come next (phase 1 of the plan).

## Payments: Creem

[Creem](https://www.creem.io) is the merchant of record: it charges the card,
handles VAT and sales tax, refunds and chargebacks, and pays the owner out.
It was chosen because it needs no Stripe account. Fee 3.9% + $0.40.

What the service does with it:

- **Checkout** (`POST /v1/checkout`) asks Creem's API for a checkout link for
  the plan's product, with the signed-in person's id in `metadata.userId`.
  Only the server holds the API key, so nobody can attach someone else's id.
- **`subscription.paid`** is the only event that grants anything: the plan
  from the product id, paid until Creem's `current_period_end_date`.
- **`refund.created`** for the whole payment, and **`dispute.created`** (a
  chargeback), end access with 2 days to export. A partial refund does not.
- Everything else (cancelled, past due, paused, expired) needs nothing: no
  payment arrives, so access ends by itself at the paid-until date.
- Every webhook is checked against the `creem-signature` header (HMAC-SHA256
  of the raw body with the webhook secret) before anything in it is read.

### Setting it up (the owner, once)

1. Sign up at creem.io, pass the ID check, and add a payout account in the
   name on the ID.
2. **Start in test mode.** Create six subscription products, one per plan
   and period, at the prices in `PLANS` (Lite $3.99 / $39.99, Plus $9.99 /
   $99.99, Max $19.99 / $199.99). Tax included or added on top are both
   inside the worst case the prices were checked against.
3. Under Developers, copy the API key, and add a webhook to
   `<the service's address>/webhooks/billing` with at least
   `subscription.paid`, `refund.created` and `dispute.created`. Copy its
   secret.
4. In Railway, on the `cloud` service, set these variables (never in chat,
   never in the repository):

   | Variable | Value |
   | --- | --- |
   | `CREEM_API_KEY` | the API key |
   | `CREEM_WEBHOOK_SECRET` | the webhook's secret |
   | `CREEM_API_BASE` | `https://test-api.creem.io` while testing; remove it to go live |
   | `CREEM_PRODUCT_LITE_MONTHLY` … `CREEM_PRODUCT_MAX_YEARLY` | the six product ids (`prod_…`) |
   | `CREEM_SUCCESS_URL` | optional: the page shown after paying |

5. `/health` then says `"billing": true`. Buy a plan with Creem's test card,
   and `GET /v1/account` shows it.
6. To go live, repeat 2 to 4 in live mode with the live key, secret and
   product ids, and remove `CREEM_API_BASE`.

## The rule it enforces

A listener can never cost more than they pay. Access comes only from the date
they have paid until (`accessOf`): a payment moves it forward (`applyEvent`),
and nothing else does. If payments stop, for any reason, access ends by
itself at that date; the library is then read-only (play, export) for 7 days
and deleted. A refund leaves 2 days. A quota is never exceeded: an upload
that does not fit is refused (`mayUpload`).

## Routes

| Route | Does |
| --- | --- |
| `GET /health` | Up, with whether it has a database and a payment provider |
| `GET /plans` | The plans and prices, no account needed |
| `GET /v1/account` | Plan, `access` (`full`, `read_only`, `none`), paid-until, delete-after, used and quota bytes |
| `POST /v1/checkout` | `{"plan", "period"}` → `{"url"}`, a checkout link that carries the person's id |
| `POST /webhooks/billing` | Creem's webhook, signature checked; each event applied once (`cloud_billing_events`) |

## Testing

Against a throwaway local Postgres (it drops the service's tables):

```
TEST_DATABASE_URL=postgres://postgres@127.0.0.1:55432/cloud bun run test.ts
```

One test reruns the pricing check from the plan against `PLANS`, so a price
or quota change that would lose money in the worst case fails here.
