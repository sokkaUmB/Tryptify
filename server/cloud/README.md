# Tryptify Cloud server

Paid, private storage for a listener's own music. The plan, prices and rules
are in [`docs/plans/tryptify-cloud.md`](../../docs/plans/tryptify-cloud.md).
`index.ts` is the whole service: one file, run by Bun as a Railway Function,
with its own Railway Postgres, signed in with the app's Supabase account the
same way as the [Theme Store](../theme-store/README.md).

## Status

Accounts and billing are written and tested. Not deployed yet: Railway was
pausing deploys for Hobby workspaces when this was written. The payment
provider is not chosen yet, so checkout and the webhook answer
`billing_not_set_up` and nobody can pay, or be granted anything. Storage and
the Subsonic API come next (phase 1 of the plan).

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
| `POST /webhooks/billing` | The provider's webhook; each event applied once (`cloud_billing_events`) |

## Testing

Against a throwaway local Postgres (it drops the service's tables):

```
TEST_DATABASE_URL=postgres://postgres@127.0.0.1:55432/cloud bun run test.ts
```

One test reruns the pricing check from the plan against `PLANS`, so a price
or quota change that would lose money in the worst case fails here.
