// Tryptify Cloud — paid, private storage for a listener's own music.
//
// Runs as a Railway Function (one file, Bun) next to its own Railway Postgres,
// like the Theme Store. The app signs in with Supabase; every /v1 request
// carries that session's access token, checked here against the project's
// public keys (JWKS), or by asking Supabase Auth for a token signed with the
// shared secret. This service holds no Supabase secret.
//
// This file is the account and billing half: plans, what a person may do, the
// checkout link, and the payment provider's webhooks. Storage and the Subsonic
// API come next (docs/plans/tryptify-cloud.md, phase 1).
//
// The owner's rule is that a listener can never cost more than they pay, so
// access is worked out from the date they have paid until, never from a
// "cancelled" message: a renewal moves the date on, and if renewals stop for
// any reason — a lost webhook included — access ends by itself. After it ends,
// the library is read-only for READ_ONLY_DAYS, then expired (deleted).
//
// Variables: DATABASE_URL (the Postgres reference). SUPABASE_URL and
// SUPABASE_ANON_KEY default to the app's own project and its public key.
// BILLING_* (see "Billing") belong to the payment provider and are set in
// Railway by the owner, never committed.

import { Hono } from "hono@4";
import { createRemoteJWKSet, decodeJwt, decodeProtectedHeader, jwtVerify } from "jose@5";
import { SQL } from "bun";

const SUPABASE_URL = (Bun.env.SUPABASE_URL ?? "https://lvzorvfhhopillzlwgau.supabase.co").replace(/\/+$/, "");
// The same public key the app ships with: it identifies the project, it grants nothing.
const SUPABASE_ANON_KEY =
  Bun.env.SUPABASE_ANON_KEY ??
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx2em9ydmZoaG9waWxsemx3Z2F1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQzNTc0NDQsImV4cCI6MjA4OTkzMzQ0NH0.Y_TN9r19WS96HyVZSQeNa0TyOqyBGuqFARaj8-7Ylow";
const ISSUER = `${SUPABASE_URL}/auth/v1`;
const JWKS = createRemoteJWKSet(new URL(`${ISSUER}/.well-known/jwks.json`));
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const GB = 1_000_000_000;
const DAY_MS = 86_400_000;

/** Days a lapsed library stays readable (play, export) before it is deleted. */
export const READ_ONLY_DAYS = 7;

/**
 * Days a refunded library stays readable. Shorter, because a refund hands the
 * money back: every day kept after it is storage nobody paid for.
 */
export const REFUND_EXPORT_DAYS = 2;

/**
 * The plans, from docs/plans/tryptify-cloud.md ("Pricing"). Each quota is the
 * largest that is still a profit in the worst case; change one only by
 * rerunning that check. Prices are what the listener is shown; what they are
 * charged is set at the payment provider, and the two must agree.
 */
export const PLANS = {
  lite: { storageGb: 100, monthly: 3.99, yearly: 39.99 },
  plus: { storageGb: 250, monthly: 9.99, yearly: 99.99 },
  max: { storageGb: 500, monthly: 19.99, yearly: 199.99 },
} as const;
export type PlanId = keyof typeof PLANS;
export type Period = "monthly" | "yearly";
export const isPlan = (p: unknown): p is PlanId => typeof p === "string" && Object.hasOwn(PLANS, p);

// ── What a person may do ─────────────────────────────────────────────────────

export type Access =
  /** Upload and play. */
  | "full"
  /** Play and export only: over quota, or paid-up time ran out less than READ_ONLY_DAYS ago. */
  | "read_only"
  /** Nothing; the library is deleted. Also a person who never subscribed. */
  | "none";

export interface Account {
  plan: PlanId | null;
  paidUntil: Date | null;
  usedBytes: number;
}

/**
 * Pure, so it is the one place the rule lives and the test can hold it. Only
 * [paidUntil] grants anything: a plan without a future paid-until date is no
 * plan.
 */
export function accessOf(a: Account, now: Date): { access: Access; quotaBytes: number; deleteAfter: Date | null } {
  if (!a.plan || !a.paidUntil) return { access: "none", quotaBytes: 0, deleteAfter: null };
  const quotaBytes = PLANS[a.plan].storageGb * GB;
  const deleteAfter = new Date(a.paidUntil.getTime() + READ_ONLY_DAYS * DAY_MS);
  if (now >= deleteAfter) return { access: "none", quotaBytes: 0, deleteAfter };
  if (now >= a.paidUntil) return { access: "read_only", quotaBytes, deleteAfter };
  return { access: a.usedBytes >= quotaBytes ? "read_only" : "full", quotaBytes, deleteAfter: null };
}

/** Whether an upload of [bytes] more fits: never over the quota, never without full access. */
export function mayUpload(a: Account, bytes: number, now: Date): boolean {
  const { access, quotaBytes } = accessOf(a, now);
  return access === "full" && bytes > 0 && a.usedBytes + bytes <= quotaBytes;
}

// ── Billing events, whichever provider sends them ────────────────────────────

/**
 * What a provider's webhook means, reduced to the one thing this service acts
 * on. `paid` moves the paid-until date to the end of the period just paid
 * for; nothing else ever extends it. `refunded` ends it now, leaving
 * REFUND_EXPORT_DAYS to export. Everything else a
 * provider says (cancelled, payment failed, paused) needs no action: no
 * renewal arrives, and access lapses on its own at the date already stored.
 */
export type BillingEvent =
  | { kind: "paid"; eventId: string; userId: string; plan: PlanId; paidUntil: Date; customerId?: string; subscriptionId?: string }
  | { kind: "refunded"; eventId: string; userId: string };

/** The account after [e]. Pure. The paid-until date only ever moves forward on a payment. */
export function applyEvent(a: Account, e: BillingEvent, now: Date): Account {
  // Paid-until moved back so that deletion comes REFUND_EXPORT_DAYS from now.
  if (e.kind === "refunded") {
    return { ...a, paidUntil: new Date(now.getTime() - (READ_ONLY_DAYS - REFUND_EXPORT_DAYS) * DAY_MS) };
  }
  const later = !a.paidUntil || e.paidUntil > a.paidUntil ? e.paidUntil : a.paidUntil;
  return { ...a, plan: e.plan, paidUntil: later };
}

// ── Storage of accounts ──────────────────────────────────────────────────────

const db = Bun.env.DATABASE_URL ? new SQL(Bun.env.DATABASE_URL) : null;

const SCHEMA = [
  `create table if not exists cloud_accounts (
     user_id          uuid        primary key,
     plan             text        check (plan in ('lite', 'plus', 'max')),
     paid_until       timestamptz,
     used_bytes       bigint      not null default 0 check (used_bytes >= 0),
     provider         text,
     customer_id      text,
     subscription_id  text,
     updated_at       timestamptz not null default now()
   )`,
  // Each provider event is applied once, however often it is delivered.
  `create table if not exists cloud_billing_events (
     provider     text        not null,
     event_id     text        not null,
     user_id      uuid        not null,
     kind         text        not null,
     received_at  timestamptz not null default now(),
     primary key (provider, event_id)
   )`,
];

if (db) {
  for (let attempt = 1; ; attempt++) {
    try {
      for (const statement of SCHEMA) await db.unsafe(statement);
      break;
    } catch (err) {
      if (attempt >= 30) throw err;
      console.error(`database not ready (${attempt}/30): ${(err as Error).message}`);
      await Bun.sleep(2000);
    }
  }
}

async function loadAccount(uid: string): Promise<Account> {
  const [row] = await db!`select plan, paid_until, used_bytes from cloud_accounts where user_id = ${uid}`;
  return row
    ? { plan: row.plan, paidUntil: row.paid_until ? new Date(row.paid_until) : null, usedBytes: Number(row.used_bytes) }
    : { plan: null, paidUntil: null, usedBytes: 0 };
}

/** Applies [e] once: a redelivered event finds its row and changes nothing. */
async function record(provider: string, e: BillingEvent): Promise<boolean> {
  return db!.begin(async (tx) => {
    const inserted = await tx`
      insert into cloud_billing_events (provider, event_id, user_id, kind)
      values (${provider}, ${e.eventId}, ${e.userId}, ${e.kind})
      on conflict do nothing
      returning event_id`;
    if (inserted.length === 0) return false;
    const [row] = await tx`select plan, paid_until, used_bytes from cloud_accounts where user_id = ${e.userId} for update`;
    const before: Account = row
      ? { plan: row.plan, paidUntil: row.paid_until ? new Date(row.paid_until) : null, usedBytes: Number(row.used_bytes) }
      : { plan: null, paidUntil: null, usedBytes: 0 };
    const after = applyEvent(before, e, new Date());
    const customer = e.kind === "paid" ? e.customerId ?? null : null;
    const subscription = e.kind === "paid" ? e.subscriptionId ?? null : null;
    await tx`
      insert into cloud_accounts (user_id, plan, paid_until, provider, customer_id, subscription_id, updated_at)
      values (${e.userId}, ${after.plan}, ${after.paidUntil}, ${provider}, ${customer}, ${subscription}, now())
      on conflict (user_id) do update set
        plan = excluded.plan,
        paid_until = excluded.paid_until,
        provider = excluded.provider,
        customer_id = coalesce(excluded.customer_id, cloud_accounts.customer_id),
        subscription_id = coalesce(excluded.subscription_id, cloud_accounts.subscription_id),
        updated_at = now()`;
    return true;
  });
}

// ── Sign-in (the Theme Store's, unchanged) ───────────────────────────────────

class AuthUnreachable extends Error {}
const vouched = new Map<string, { uid: string; until: number }>();

/** The person [token] belongs to, or null when it is not a live session of this project. */
async function personOf(token: string): Promise<string | null> {
  let alg: string | undefined;
  let exp = 0;
  try {
    alg = decodeProtectedHeader(token).alg;
    exp = Number(decodeJwt(token).exp ?? 0);
  } catch {
    return null;
  }
  if (alg !== "HS256") {
    try {
      const { payload } = await jwtVerify(token, JWKS, { issuer: ISSUER, audience: "authenticated" });
      return typeof payload.sub === "string" && UUID.test(payload.sub) ? payload.sub : null;
    } catch {
      return null;
    }
  }
  const key = new Bun.CryptoHasher("sha256").update(token).digest("hex");
  const now = Date.now();
  const hit = vouched.get(key);
  if (hit && hit.until > now) return hit.uid;
  let res: Response;
  try {
    res = await fetch(`${ISSUER}/user`, {
      headers: { apikey: SUPABASE_ANON_KEY, authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    throw new AuthUnreachable();
  }
  if (res.status >= 500) throw new AuthUnreachable();
  if (!res.ok) return null;
  const user = (await res.json().catch(() => null)) as { id?: unknown } | null;
  if (typeof user?.id !== "string" || !UUID.test(user.id)) return null;
  if (vouched.size > 10_000) {
    for (const [k, v] of vouched) if (v.until <= now) vouched.delete(k);
    if (vouched.size > 10_000) vouched.clear();
  }
  vouched.set(key, { uid: user.id, until: Math.min(exp * 1000, now + 5 * 60_000) });
  return user.id;
}

// ── Routes ───────────────────────────────────────────────────────────────────

type Vars = { Variables: { uid: string } };
const app = new Hono<Vars>();

const fail = (status: number, error: string) =>
  new Response(JSON.stringify({ error }), { status, headers: { "content-type": "application/json" } });

const health = (c: { json: (body: unknown) => Response }) =>
  c.json({ ok: true, service: "tryptify-cloud", database: db !== null, billing: billing !== null });
app.get("/", health);
app.get("/health", health);

/** The plans, for the app's plan picker. No account needed. */
app.get("/plans", (c) =>
  c.json({
    readOnlyDays: READ_ONLY_DAYS,
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
  } catch {
    return fail(502, "auth_unreachable");
  }
  if (!uid) return fail(401, "signed_out");
  c.set("uid", uid);
  await next();
});

/** The signed-in person's plan, what they may do, and how much they use. */
app.get("/v1/account", async (c) => {
  const a = await loadAccount(c.get("uid"));
  const { access, quotaBytes, deleteAfter } = accessOf(a, new Date());
  return c.json({
    plan: a.plan,
    access,
    paidUntil: a.paidUntil?.toISOString() ?? null,
    deleteAfter: deleteAfter?.toISOString() ?? null,
    usedBytes: a.usedBytes,
    quotaBytes,
  });
});

/** A checkout link for [plan] and [period], carrying the person's id so the webhook knows who paid. */
app.post("/v1/checkout", async (c) => {
  if (!billing) return fail(503, "billing_not_set_up");
  const body = (await c.req.json().catch(() => null)) as { plan?: unknown; period?: unknown } | null;
  const plan = body?.plan;
  const period = body?.period;
  if (!isPlan(plan) || (period !== "monthly" && period !== "yearly")) return fail(400, "bad_request");
  const url = await billing.checkoutUrl(plan, period, c.get("uid"));
  return url ? c.json({ url }) : fail(503, "billing_not_set_up");
});

/** The payment provider's webhook. Answers 2xx only once the event is safely recorded. */
app.post("/webhooks/billing", async (c) => {
  if (!db || !billing) return fail(503, "not_set_up");
  const raw = await c.req.text();
  const events = await billing.parseWebhook(c.req.raw.headers, raw);
  if (events === null) return fail(401, "bad_signature");
  for (const e of events) await record(billing.name, e);
  return c.json({ ok: true, applied: events.length });
});

// ── Billing ──────────────────────────────────────────────────────────────────
//
// One payment provider, chosen by the owner, behind this interface. Until its
// variables are set, checkout and webhooks answer "not set up" and nobody can
// pay — or be granted anything.

export interface Billing {
  name: string;
  checkoutUrl(plan: PlanId, period: Period, userId: string): Promise<string | null>;
  /** The events in a verified webhook, or null when its signature does not check out. */
  parseWebhook(headers: Headers, raw: string): Promise<BillingEvent[] | null>;
}

const billing: Billing | null = null;

export default {
  port: Number(Bun.env.PORT ?? 3000),
  maxRequestBodySize: 256 * 1024,
  fetch: app.fetch,
};
