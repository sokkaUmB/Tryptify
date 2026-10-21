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
