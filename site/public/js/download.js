// The download page: version, size and SHA-256 from /data/apk.json, which is
// published with each APK (see README).
(() => {
  "use strict";
  const info = document.getElementById("apk-info");
  const sha = document.getElementById("apk-sha");
  fetch("/data/apk.json", { cache: "no-cache" })
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
    .then((a) => {
      info.textContent = `Version ${a.version} · ${Math.round(a.size / 1e6)} MB · Android ${a.minAndroid} and newer.`;
      sha.textContent = a.sha256;
    })
    .catch(() => { sha.textContent = "Unavailable right now."; });
})();
