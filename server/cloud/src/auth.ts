// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Who a request is, from the app's Supabase session, the Theme Store's way. A
// token signed with one of the project's asymmetric keys is checked here
// against its public keys (JWKS); one signed with the project's shared secret
// is checked by asking Supabase Auth who it belongs to. This service holds no
// Supabase secret.

import { createRemoteJWKSet, decodeJwt, decodeProtectedHeader, jwtVerify } from "jose";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class AuthUnreachable extends Error {}

/** Returns `personOf(token)`: the person [token] belongs to, or null when it is not a live session. */
export function supabaseAuth(supabaseUrl: string, anonKey: string) {
  const issuer = `${supabaseUrl.replace(/\/+$/, "")}/auth/v1`;
  const jwks = createRemoteJWKSet(new URL(`${issuer}/.well-known/jwks.json`));
  // Tokens Supabase Auth has vouched for, by their hash, until they expire
  // (five minutes at most). Per isolate, which is all a cache needs to be.
  const vouched = new Map<string, { uid: string; until: number }>();

  return async function personOf(token: string): Promise<string | null> {
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
        const { payload } = await jwtVerify(token, jwks, { issuer, audience: "authenticated" });
        return typeof payload.sub === "string" && UUID.test(payload.sub) ? payload.sub : null;
      } catch {
        return null;
      }
    }
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)));
    const key = Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
    const now = Date.now();
    const hit = vouched.get(key);
    if (hit && hit.until > now) return hit.uid;
    let res: Response;
    try {
      res = await fetch(`${issuer}/user`, {
        headers: { apikey: anonKey, authorization: `Bearer ${token}` },
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
  };
}
