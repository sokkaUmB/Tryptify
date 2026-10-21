// Tryptify Cloud end to end against a real Postgres, with a stand-in for
// Supabase Auth (public keys for a key made here). Needs an empty database it
// may drop tables in:
//
//   TEST_DATABASE_URL=postgres://postgres@127.0.0.1:55432/cloud bun run test.ts
//
// A plain script rather than `bun test`, like the Theme Store's: the runner
// does not fetch versioned imports such as "jose@5", and a plain run does.

import assert from "node:assert/strict";
import { SQL } from "bun";
import { SignJWT, exportJWK, generateKeyPair } from "jose@5";

const url = Bun.env.TEST_DATABASE_URL;
if (!url) {
  console.error("Set TEST_DATABASE_URL to an empty Postgres database the test may drop tables in.");
  process.exit(2);
}
const A = "11111111-1111-4111-8111-111111111111";
const B = "22222222-2222-4222-8222-222222222222";
const DAY = 86_400_000;
const GB = 1_000_000_000;

let ISSUER = "";
let keys: CryptoKeyPair;
let db: SQL;
let fetchCloud: (r: Request) => Promise<Response>;
let cloud: typeof import("./index.ts");

// A stand-in for Creem: what checkouts it was asked for, and its answer.
const SECRET = "whsec_test_only";
const checkouts: any[] = [];
const sign = (raw: string) => new Bun.CryptoHasher("sha256", SECRET).update(raw).digest("hex");
async function webhook(event: unknown, signature?: string) {
  const raw = JSON.stringify(event);
  return call("POST", "/webhooks/billing", undefined, raw, { "creem-signature": signature ?? sign(raw) });
}
const paidEvent = (id: string, userId: string | null, product: string, end: Date, sub = "sub_1") => ({
  id,
  eventType: "subscription.paid",
  object: {
    id: sub,
    object: "subscription",
    product: { id: product },
    customer: { id: "cust_1" },
    current_period_end_date: end.toISOString(),
    metadata: userId ? { userId } : {},
  },
});

async function token(sub: string) {
  return new SignJWT({ role: "authenticated" })
    .setProtectedHeader({ alg: "ES256", kid: "test" })
    .setSubject(sub)
    .setIssuer(ISSUER)
    .setAudience("authenticated")
    .setIssuedAt()
    .setExpirationTime("5m")
    .sign(keys.privateKey);
}

async function call(method: string, path: string, who?: string, body?: unknown, headers: Record<string, string> = {}) {
  const h: Record<string, string> = { ...headers };
  if (who) h.authorization = `Bearer ${who}`;
  const res = await fetchCloud(
    new Request(`http://cloud.test${path}`, {
      method,
      headers: h,
      body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    }),
  );
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

const tests: [string, () => Promise<void>][] = [];
const test = (name: string, fn: () => Promise<void>) => tests.push([name, fn]);

async function setUp() {
  db = new SQL(url!);
  await db.unsafe("drop table if exists cloud_billing_events, cloud_accounts cascade");
  keys = await generateKeyPair("ES256");
  const jwk = { ...(await exportJWK(keys.publicKey)), kid: "test", alg: "ES256", use: "sig" };
  const supabase = Bun.serve({
    port: 0,
    fetch(req) {
      const path = new URL(req.url).pathname;
      if (path === "/auth/v1/.well-known/jwks.json") return Response.json({ keys: [jwk] });
      return new Response("no", { status: 404 });
    },
  });
  ISSUER = `http://127.0.0.1:${supabase.port}/auth/v1`;
  const creem = Bun.serve({
    port: 0,
    async fetch(req) {
      if (new URL(req.url).pathname !== "/v1/checkouts" || req.headers.get("x-api-key") !== "creem_test_key") {
        return new Response("no", { status: 403 });
      }
      const body = await req.json();
      checkouts.push(body);
      return Response.json({ id: "ch_1", checkout_url: `https://checkout.creem.test/ch_${checkouts.length}` });
    },
  });
  Object.assign(Bun.env, {
    CREEM_API_KEY: "creem_test_key",
    CREEM_WEBHOOK_SECRET: SECRET,
    CREEM_API_BASE: `http://127.0.0.1:${creem.port}`,
    CREEM_PRODUCT_LITE_MONTHLY: "prod_lite_m",
    CREEM_PRODUCT_LITE_YEARLY: "prod_lite_y",
    CREEM_PRODUCT_PLUS_MONTHLY: "prod_plus_m",
    CREEM_PRODUCT_PLUS_YEARLY: "prod_plus_y",
    CREEM_PRODUCT_MAX_MONTHLY: "prod_max_m",
    // MAX_YEARLY left unset on purpose: that checkout must say not set up.
  });
  Bun.env.DATABASE_URL = url;
  Bun.env.SUPABASE_URL = `http://127.0.0.1:${supabase.port}/`;
  cloud = await import("./index.ts");
  fetchCloud = (r) => Promise.resolve(cloud.default.fetch(r));
}

// ── The rule: nobody costs more than they pay ───────────────────────────────

const now = new Date("2026-11-01T12:00:00Z");
const days = (n: number) => new Date(now.getTime() + n * DAY);

test("no plan, or a plan with no paid-until date, grants nothing", async () => {
  assert.equal(cloud.accessOf({ plan: null, paidUntil: null, usedBytes: 0 }, now).access, "none");
  assert.equal(cloud.accessOf({ plan: "lite", paidUntil: null, usedBytes: 0 }, now).access, "none");
});

test("paid up: full access, with the plan's quota", async () => {
  const r = cloud.accessOf({ plan: "plus", paidUntil: days(10), usedBytes: 0 }, now);
  assert.equal(r.access, "full");
  assert.equal(r.quotaBytes, 250 * GB);
  assert.equal(r.deleteAfter, null);
});

test("over quota while paid up: read-only, never billed extra", async () => {
  const a = { plan: "lite" as const, paidUntil: days(10), usedBytes: 100 * GB };
  assert.equal(cloud.accessOf(a, now).access, "read_only");
  assert.equal(cloud.mayUpload(a, 1, now), false);
});

test("an upload must fit under the quota in full", async () => {
  const a = { plan: "lite" as const, paidUntil: days(10), usedBytes: 99 * GB };
  assert.equal(cloud.mayUpload(a, GB, now), true);
  assert.equal(cloud.mayUpload(a, GB + 1, now), false);
  assert.equal(cloud.mayUpload(a, 0, now), false);
});

test("lapsed: read-only for the grace days, then nothing", async () => {
  const lapsed = (d: number) => ({ plan: "max" as const, paidUntil: days(-d), usedBytes: GB });
  assert.equal(cloud.accessOf(lapsed(0), now).access, "read_only");
  assert.equal(cloud.accessOf(lapsed(cloud.READ_ONLY_DAYS - 1), now).access, "read_only");
  assert.equal(cloud.mayUpload(lapsed(1), 1, now), false);
  assert.equal(cloud.accessOf(lapsed(cloud.READ_ONLY_DAYS), now).access, "none");
});

test("only a payment extends the paid-until date, and never backwards", async () => {
  const base = { plan: "lite" as const, paidUntil: days(20), usedBytes: 0 };
  const paid = (until: Date, plan: "lite" | "plus" = "lite") =>
    ({ kind: "paid", eventId: "e", userId: A, plan, paidUntil: until }) as const;
  assert.deepEqual(cloud.applyEvent(base, paid(days(50)), now).paidUntil, days(50));
  // An older renewal arriving late does not take time away.
  assert.deepEqual(cloud.applyEvent(base, paid(days(5)), now).paidUntil, days(20));
  // An upgrade takes effect with its payment.
  assert.equal(cloud.applyEvent(base, paid(days(30), "plus"), now).plan, "plus");
});

test("a refund ends access now and leaves only the short export window", async () => {
  const a = cloud.applyEvent(
    { plan: "plus", paidUntil: days(300), usedBytes: GB },
    { kind: "refunded", eventId: "r", userId: A },
    now,
  );
  const r = cloud.accessOf(a, now);
  assert.equal(r.access, "read_only");
  assert.deepEqual(r.deleteAfter, days(cloud.REFUND_EXPORT_DAYS));
  assert.equal(cloud.accessOf(a, days(cloud.REFUND_EXPORT_DAYS)).access, "none");
});

test("every plan is a profit in the worst case (the pricing check, kept)", async () => {
  // Worst path: web checkout, non-US PayPal, 27% VAT, a full quota.
  const STORE = 0.015, SAFETY = 1.15, VAT = 1.27;
  for (const [id, p] of Object.entries(cloud.PLANS)) {
    const cost = p.storageGb * STORE * SAFETY;
    for (const [price, months] of [[p.monthly, 1], [p.yearly, 12]] as const) {
      const worst = Math.min(
        (price / VAT) * 0.85,
        price / VAT - (0.085 * price + 0.5),
        price - (0.055 * price + 0.5),
      ) / months;
      assert.ok(worst - cost > 0, `${id} at ${price}: keeps ${worst.toFixed(2)}, costs ${cost.toFixed(2)}`);
    }
  }
});

// ── Routes ───────────────────────────────────────────────────────────────────

test("health and plans answer without an account", async () => {
  assert.equal((await call("GET", "/health")).body.ok, true);
  const plans = await call("GET", "/plans");
  assert.deepEqual(plans.body.plans.map((p: { id: string }) => p.id), ["lite", "plus", "max"]);
});

test("the account needs a sign-in", async () => {
  assert.equal((await call("GET", "/v1/account")).status, 401);
  assert.equal((await call("GET", "/v1/account", "not-a-token")).status, 401);
});

test("a new person has no plan and no access", async () => {
  const r = await call("GET", "/v1/account", await token(A));
  assert.equal(r.status, 200);
  assert.deepEqual(r.body, { plan: null, access: "none", paidUntil: null, deleteAfter: null, usedBytes: 0, quotaBytes: 0 });
});

test("an account row someone paid for shows its plan", async () => {
  await db`insert into cloud_accounts (user_id, plan, paid_until) values (${B}, 'plus', ${days(400)})`;
  const r = await call("GET", "/v1/account", await token(B));
  assert.equal(r.body.plan, "plus");
  assert.equal(r.body.access, "full");
  assert.equal(r.body.quotaBytes, 250 * GB);
  // A sees only A's account.
  assert.equal((await call("GET", "/v1/account", await token(A))).body.plan, null);
});

// ── Creem ────────────────────────────────────────────────────────────────────

const future = (d: number) => new Date(Date.now() + d * DAY);
const C = "33333333-3333-4333-8333-333333333333";

test("checkout asks Creem for the plan's product, carrying the person's id", async () => {
  const r = await call("POST", "/v1/checkout", await token(A), { plan: "plus", period: "yearly" });
  assert.equal(r.status, 200);
  assert.match(r.body.url, /^https:\/\/checkout\.creem\.test\//);
  const sent = checkouts.at(-1);
  assert.equal(sent.product_id, "prod_plus_y");
  assert.deepEqual(sent.metadata, { userId: A });
});

test("checkout refuses a bad plan, needs a sign-in, and says when a product is missing", async () => {
  assert.equal((await call("POST", "/v1/checkout", await token(A), { plan: "gold", period: "monthly" })).status, 400);
  assert.equal((await call("POST", "/v1/checkout", undefined, { plan: "lite", period: "monthly" })).status, 401);
  const r = await call("POST", "/v1/checkout", await token(A), { plan: "max", period: "yearly" });
  assert.equal(r.status, 503);
  assert.equal(r.body.error, "billing_not_set_up");
});

test("a webhook with a wrong or missing signature changes nothing", async () => {
  const e = paidEvent("evt_forged", C, "prod_max_m", future(30));
  assert.equal((await webhook(e, "00".repeat(32))).status, 401);
  assert.equal((await webhook(e, "")).status, 401);
  assert.equal((await call("GET", "/v1/account", await token(C))).body.access, "none");
});

test("subscription.paid grants the plan until the period's end", async () => {
  const end = future(30);
  assert.equal((await webhook(paidEvent("evt_p1", C, "prod_lite_m", end, "sub_c"))).status, 200);
  const r = (await call("GET", "/v1/account", await token(C))).body;
  assert.equal(r.plan, "lite");
  assert.equal(r.access, "full");
  assert.equal(r.paidUntil, end.toISOString());
});

test("a redelivered webhook is applied once", async () => {
  const again = await webhook(paidEvent("evt_p1", C, "prod_lite_m", future(30), "sub_c"));
  assert.equal(again.status, 200);
  const [{ n }] = await db`select count(*)::int as n from cloud_billing_events where event_id = 'evt_p1'`;
  assert.equal(n, 1);
});

test("a renewal moves the date on; an upgrade changes the plan", async () => {
  const end = future(60);
  await webhook(paidEvent("evt_p2", C, "prod_plus_m", end, "sub_c"));
  const r = (await call("GET", "/v1/account", await token(C))).body;
  assert.equal(r.plan, "plus");
  assert.equal(r.paidUntil, end.toISOString());
});

test("an unknown product or a payment naming nobody grants nothing", async () => {
  const D = "44444444-4444-4444-8444-444444444444";
  await webhook(paidEvent("evt_u1", D, "prod_someone_elses", future(30), "sub_d"));
  await webhook(paidEvent("evt_u2", null, "prod_lite_m", future(30), "sub_unknown"));
  assert.equal((await call("GET", "/v1/account", await token(D))).body.access, "none");
});

test("cancelled, past due and expired need nothing: access lapses at the paid-until date", async () => {
  for (const type of ["subscription.canceled", "subscription.past_due", "subscription.expired"]) {
    assert.equal((await webhook({ id: `evt_${type}`, eventType: type, object: { id: "sub_c" } })).status, 200);
  }
  assert.equal((await call("GET", "/v1/account", await token(C))).body.access, "full");
});

test("a partial refund keeps access; a full refund, found by subscription, ends it", async () => {
  const refund = (id: string, amount: number) => ({
    id,
    eventType: "refund.created",
    object: { refund_amount: amount, transaction: { amount_paid: 1210 }, subscription: { id: "sub_c", metadata: {} } },
  });
  await webhook(refund("evt_r1", 300));
  assert.equal((await call("GET", "/v1/account", await token(C))).body.access, "full");
  await webhook(refund("evt_r2", 1210));
  const r = (await call("GET", "/v1/account", await token(C))).body;
  assert.equal(r.access, "read_only");
  const left = new Date(r.deleteAfter).getTime() - Date.now();
  assert.ok(left > (cloud.REFUND_EXPORT_DAYS - 0.1) * DAY && left <= cloud.REFUND_EXPORT_DAYS * DAY, `export window ${left}`);
});

test("a chargeback ends access like a refund", async () => {
  const E = "55555555-5555-4555-8555-555555555555";
  await webhook(paidEvent("evt_e1", E, "prod_max_m", future(30), "sub_e"));
  assert.equal((await call("GET", "/v1/account", await token(E))).body.access, "full");
  await webhook({ id: "evt_d1", eventType: "dispute.created", object: { transaction: { subscription: "sub_e" } } });
  assert.equal((await call("GET", "/v1/account", await token(E))).body.access, "read_only");
});

// ── Run ──────────────────────────────────────────────────────────────────────

await setUp();
let failed = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log(`  ok    ${name}`);
  } catch (e) {
    failed++;
    console.log(`  FAIL  ${name}`);
    console.log(e);
  }
}
await db.close();
console.log(`${tests.length - failed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
