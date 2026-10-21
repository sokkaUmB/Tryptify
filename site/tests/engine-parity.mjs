// Runs the website's AutoEQ engine (site/public/js/autoeq-engine.js) on the
// inputs the app's engine answered in autoeq-engine-fixtures.json (written by
// WebAutoEqFixtureTest), and fails on any difference beyond float noise.
//   node site/tests/engine-parity.mjs [extra-measurement.txt target.txt]
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const ctx = { window: {}, Math, Number, Float64Array, Set };
vm.runInNewContext(readFileSync(join(here, "../public/js/autoeq-engine.js"), "utf8"), ctx);
const E = ctx.window.TryptifyAutoEq;
const fixtures = JSON.parse(readFileSync(join(here, "autoeq-engine-fixtures.json"), "utf8"));
const read = (p) => readFileSync(p, "utf8");
const close = (a, b, rel = 2e-5, abs = 2e-4) => Math.abs(a - b) <= Math.max(abs, rel * Math.abs(b));
// Gains may differ by a few thousandths of a dB, never more. The JVM's and
// V8's sin/cos/exp can disagree in the last bit, and the fit amplifies that:
// nudging cos by one part in 1e15 changes most fits outright. Band types,
// frequencies and Qs must still match, and gains within 0.01 dB (inaudible;
// about 1 dB is the smallest difference people notice).
const GAIN_DB = 0.01;

let failed = 0, worst = 0;
for (const c of fixtures.cases) {
  const meas = E.parse(read(join(here, "inputs", c.input)));
  const target = E.parse(read(join(here, "../public/data/targets", c.target)));
  const fit = E.run(E.smoothCurve(meas, c.smoothing), target, { bandCount: c.bandCount, maxFrequency: c.maxFrequency, algorithm: c.algorithm });
  const label = `${c.input} → ${c.target} · ${c.bandCount} bands · ${c.maxFrequency} Hz · ${c.algorithm} · smoothing ${c.smoothing}`;
  let ok = meas.length === c.points && fit.length === c.bands.length;
  if (ok) fit.forEach((b, i) => {
    const [t, f, g, q] = c.bands[i];
    worst = Math.max(worst, Math.abs(b.gain - g));
    if (b.type !== t || !close(b.freq, f) || Math.abs(b.gain - g) > GAIN_DB || !close(b.q, q)) ok = false;
  });
  const pre = E.peakBoostDb(fit);
  if (Math.abs(pre - c.preamp) > GAIN_DB) ok = false;
  if (!ok) {
    failed++;
    console.log("DIFFERS", label);
    console.log("  app:", JSON.stringify(c.bands), "preamp", c.preamp);
    console.log("  web:", JSON.stringify(fit.map((b) => [b.type, b.freq, b.gain, b.q])), "preamp", pre);
  }
}
console.log(`${fixtures.cases.length - failed} of ${fixtures.cases.length} cases match the app's engine (largest gain difference ${worst.toFixed(4)} dB)`);
process.exit(failed ? 1 : 0);
