// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The HTTP API, built from its dependencies so the Worker and the tests run
// exactly the same routes.

import { Hono } from "hono";
import { AuthUnreachable } from "./auth";
import type { Billing } from "./creem";
import { accessOf, isPlan, mayStartTrial, PLANS, READ_ONLY_DAYS, TRIAL_DAYS, TRIAL_GB } from "./rules";
import { loadAccount, record, type Db } from "./store";

export interface Deps {
  db: Db | null;
  billing: Billing | null;
  personOf: (token: string) => Promise<string | null>;
  now?: () => Date;
}

const fail = (status: number, error: string) =>
  new Response(JSON.stringify({ error }), { status, headers: { "content-type": "application/json" } });

export function createApp({ db, billing, personOf, now = () => new Date() }: Deps) {
  const app = new Hono<{ Variables: { uid: string } }>();

  const health = (c: { json: (body: unknown) => Response }) =>
    c.json({ ok: true, service: "tryptify-cloud", database: db !== null, billing: billing !== null });
  app.get("/", health);
  app.get("/health", health);

  /** The plans and the trial's terms, for the app's plan picker. No account needed. */
  app.get("/plans", (c) =>
    c.json({
      readOnlyDays: READ_ONLY_DAYS,
      trial: { days: TRIAL_DAYS, storageGb: TRIAL_GB, periods: ["monthly"] },
      plans: Object.entries(PLANS).map(([id, p]) => ({ id, ...p })),
    }),
  );

  app.use("/v1/*", async (c, next) => {
    if (!db) return fail(503, "not_set_up");
    const header = c.req.header("authorization") ?? "";
    if (!header.startsWith("Bearer ")) return fail(401, "signed_out");
    let uid: string | null;
    try {
      uid = await personOf(header.slice(7));
    } catch (e) {
      if (e instanceof AuthUnreachable) return fail(502, "auth_unreachable");
      throw e;
    }
    if (!uid) return fail(401, "signed_out");
    c.set("uid", uid);
    await next();
  });

  /** The signed-in person's plan, what they may do, and how much they use. */
  app.get("/v1/account", async (c) => {
    const a = await loadAccount(db!, c.get("uid"));
    const { access, quotaBytes, deleteAfter } = accessOf(a, now());
    return c.json({
      plan: a.plan,
      access,
      paidUntil: a.paidUntil?.toISOString() ?? null,
      deleteAfter: deleteAfter?.toISOString() ?? null,
      usedBytes: a.usedBytes,
      quotaBytes,
      trial: a.trial ?? false,
      trialAvailable: mayStartTrial(a),
    });
  });

  /** A checkout link for [plan] and [period], carrying the person's id so the webhook knows who paid. */
  app.post("/v1/checkout", async (c) => {
    if (!billing) return fail(503, "billing_not_set_up");
    const body = (await c.req.json().catch(() => null)) as { plan?: unknown; period?: unknown; trial?: unknown } | null;
    const plan = body?.plan;
    const period = body?.period;
    const trial = body?.trial === true;
    if (!isPlan(plan) || (period !== "monthly" && period !== "yearly")) return fail(400, "bad_request");
    // Trials come with the monthly plans only, and once per account.
    if (trial && period !== "monthly") return fail(400, "bad_request");
    if (trial && !mayStartTrial(await loadAccount(db!, c.get("uid")))) return fail(409, "trial_used");
    const url = await billing.checkoutUrl(plan, period, c.get("uid"), trial);
    return url ? c.json({ url }) : fail(503, "billing_not_set_up");
  });

  /** The payment provider's webhook. Answers 2xx only once the event is safely recorded. */
  app.post("/webhooks/billing", async (c) => {
    if (!db || !billing) return fail(503, "not_set_up");
    const raw = await c.req.text();
    const events = await billing.parseWebhook(c.req.raw.headers, raw);
    if (events === null) return fail(401, "bad_signature");
    for (const e of events) await record(db, billing.name, e, now);
    return c.json({ ok: true, applied: events.length });
  });

  return app;
}
