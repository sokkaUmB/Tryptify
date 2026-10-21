// The website is static files (public/), served by Workers static assets.
// This script only answers /download/tryptify.apk: the APK is too big for a
// static asset (25 MiB at most), so it lives in the R2 bucket
// tryptify-downloads, uploaded with its file name (and so its version) as its
// Content-Disposition; its version, size and SHA-256 are also published in
// public/data/apk.json (see README). Everything else is a static file.

const APK = "tryptify.apk";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/download/tryptify.apk") return env.ASSETS.fetch(request);
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", { status: 405, headers: { allow: "GET, HEAD" } });
    }
    // Range requests let download managers resume a 90 MB file.
    const object = request.method === "HEAD"
      ? await env.DOWNLOADS.head(APK)
      : await env.DOWNLOADS.get(APK, { range: request.headers, onlyIf: request.headers });
    if (!object) return new Response("The download is being updated. Try again in a minute.", { status: 503 });
    const headers = new Headers({
      "content-type": "application/vnd.android.package-archive",
      "content-disposition": object.httpMetadata?.contentDisposition ?? 'attachment; filename="Tryptify.apk"',
      "accept-ranges": "bytes",
      etag: object.httpEtag,
      "cache-control": "public, max-age=300",
    });
    if (!("body" in object) || !object.body) {
      headers.set("content-length", String(object.size));
      return new Response(null, { status: request.method === "HEAD" ? 200 : 304, headers });
    }
    if (object.range) {
      const { offset = 0, length = object.size - offset } = object.range;
      headers.set("content-range", `bytes ${offset}-${offset + length - 1}/${object.size}`);
      headers.set("content-length", String(length));
      return new Response(object.body, { status: 206, headers });
    }
    headers.set("content-length", String(object.size));
    return new Response(object.body, { headers });
  },
};
