// The site's motion, on Motion (motion.dev, MIT, js/motion-12.23.12.js).
// Decoration only: the pages read the same without it. The head's inline
// script adds .motion to <html> when movement is welcome; everything that
// starts hidden in styles.css is shown again here, or by that script's
// timeout if this file never runs.
(() => {
  "use strict";
  const root = document.documentElement;
  const M = window.Motion;
  const moving = root.classList.contains("motion") && !!M;
  window.__tryptifyMotion = true;

  const $ = (sel, el = document) => el.querySelector(sel);
  const $$ = (sel, el = document) => Array.from(el.querySelectorAll(sel));
  const finePointer = matchMedia("(pointer: fine)").matches;

  // ── Monthly / yearly (works with or without movement) ────────────────
  const period = $(".period");
  if (period) {
    period.hidden = false;
    const pill = $(".period-pill", period);
    const buttons = $$("button", period);
    const placePill = (btn, instant) => {
      const box = { x: btn.offsetLeft - 4, width: btn.offsetWidth };
      if (M) M.animate(pill, box, moving && !instant ? { type: "spring", stiffness: 420, damping: 32 } : { duration: 0 });
      else { pill.style.transform = `translateX(${box.x}px)`; pill.style.width = box.width + "px"; }
    };
    const show = (which, instant) => {
      buttons.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.period === which)));
      placePill(buttons.find((b) => b.dataset.period === which), instant);
      $$(".plan").forEach((plan, i) => {
        const amount = $(".amount", plan);
        const to = Number(amount.dataset[which]);
        const from = Number(amount.textContent.replace(/[^0-9.]/g, ""));
        const write = (v) => { amount.textContent = "$" + v.toFixed(2); };
        if (moving && !instant) {
          M.animate(from, to, { duration: 0.7, delay: i * 0.06, ease: [0.22, 1, 0.36, 1], onUpdate: write });
          M.animate(amount, { scale: [1, 1.12, 1] }, { duration: 0.5, delay: i * 0.06 });
        } else write(to);
        $$("[data-monthly]", plan).forEach((el) => { if (el !== amount) el.textContent = el.dataset[which]; });
      });
    };
    buttons.forEach((b) => b.addEventListener("click", () => show(b.dataset.period, false)));
    // Fonts change the buttons' widths: place the pill once they are in.
    const settle = () => placePill(buttons.find((b) => b.getAttribute("aria-pressed") === "true"), true);
    settle();
    document.fonts && document.fonts.ready.then(settle);
    addEventListener("resize", settle);
  }

  // ── Design picker (while the owner chooses a design) ────────────────
  const DESIGNS = {
    mono: ["#0a0a0a", "#f2f2f2"], neon: ["#07070d", "#ff2e88"], ember: ["#0d0a08", "#ff6a1a"],
    mint: ["#061113", "#19e3b1"], acid: ["#080808", "#c6ff3d"], paper: ["#f5f3ee", "#e4572e"],
  };
  const embedded = window.top !== window;
  const current = () => root.dataset.design || "mono";
  const setDesign = (name) => {
    root.dataset.design = name;
    try { localStorage.setItem("tryptify-design", name); } catch (e) {}
    $$(".designs button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.design === name)));
  };
  if (!embedded) {
    const picker = document.createElement("div");
    picker.className = "designs";
    picker.setAttribute("role", "group");
    picker.setAttribute("aria-label", "Site design");
    picker.innerHTML = '<a href="/designs">Design</a>';
    Object.entries(DESIGNS).forEach(([name, [bg, accent]]) => {
      const b = document.createElement("button");
      b.type = "button";
      b.dataset.design = name;
      b.title = name[0].toUpperCase() + name.slice(1);
      b.setAttribute("aria-label", b.title);
      b.setAttribute("aria-pressed", String(name === current()));
      b.style.setProperty("--sw-bg", bg);
      b.style.setProperty("--sw-accent", accent);
      b.addEventListener("click", () => {
        setDesign(name);
        if (moving) M.animate(b, { scale: [1, 1.5, 1.15] }, { type: "spring", stiffness: 500, damping: 14 });
      });
      picker.append(b);
    });
    document.body.append(picker);
    if (moving) M.animate(picker, { opacity: [0, 1], y: [30, 0] }, { delay: 1, type: "spring", stiffness: 200, damping: 20 });
  }
  // The designs page: scale each live preview to its card, and "Use this".
  $$(".gallery .preview").forEach((box) => {
    const frame = $("iframe", box);
    const fit = () => { frame.style.transform = `scale(${box.clientWidth / 1280})`; };
    fit();
    addEventListener("resize", fit);
  });
  $$("[data-use-design]").forEach((btn) => btn.addEventListener("click", () => {
    setDesign(btn.dataset.useDesign);
    location.href = "/";
  }));

  if (!moving) {
    root.classList.remove("motion");
    return;
  }
  const { animate, inView, scroll, hover, press, stagger } = M;
  const easeOut = [0.22, 1, 0.36, 1];
  const rise = (els, opts = {}) =>
    animate(els, { opacity: [0, 1], y: [opts.y ?? 28, 0] }, { duration: 0.7, ease: easeOut, delay: stagger(opts.gap ?? 0.08, { startDelay: opts.start ?? 0 }) });

  // ── Scroll progress along the top ───────────────────────────────────
  const bar = document.createElement("div");
  bar.className = "progress";
  bar.setAttribute("aria-hidden", "true");
  document.body.prepend(bar);
  scroll(animate(bar, { scaleX: [0, 1] }, { ease: "linear" }));

  // ── The hero: words arrive one by one over a live spectrum ──────────
  const hero = $(".hero");
  if (hero) {
    const h1 = $("h1", hero);
    if (h1) {
      const words = h1.textContent.trim().split(/\s+/);
      h1.textContent = "";
      words.forEach((w, i) => {
        const span = document.createElement("span");
        span.className = "word";
        span.textContent = w + (i < words.length - 1 ? " " : "");
        h1.append(span);
      });
      h1.style.opacity = "1";
      animate($$(".word", h1), { opacity: [0, 1], y: [26, 0], filter: ["blur(10px)", "blur(0px)"] },
        { duration: 0.65, ease: easeOut, delay: stagger(0.055, { startDelay: 0.15 }) });
    }
    const rest = $$(":scope > :not(h1):not(.spectrum)", hero);
    rise(rest, { start: 0.35, gap: 0.1, y: 18 });

    // Spectrum bars: each loops at its own pace, and the ones near the
    // pointer jump up, like a visualizer reacting to the music.
    const spectrum = document.createElement("div");
    spectrum.className = "spectrum";
    spectrum.setAttribute("aria-hidden", "true");
    const count = innerWidth < 560 ? 26 : 44;
    const bars = [];
    for (let i = 0; i < count; i++) {
      const wrap = document.createElement("span");
      wrap.className = "bar";
      const fill = document.createElement("i");
      wrap.append(fill);
      spectrum.append(wrap);
      bars.push(wrap);
      // A rough "music" shape: more energy low and mid, less at the top.
      const t = i / count;
      const energy = 0.35 + 0.55 * Math.exp(-((t - 0.3) ** 2) / 0.08);
      const keys = Array.from({ length: 5 }, () => Math.max(0.08, Math.min(1, energy * (0.35 + Math.random() * 0.75))));
      animate(fill, { scaleY: keys }, {
        duration: 1.6 + Math.random() * 1.4, repeat: Infinity, repeatType: "mirror", ease: "easeInOut", delay: Math.random() * -2,
      });
    }
    hero.prepend(spectrum);
    animate(spectrum, { opacity: [0, innerWidth < 560 ? 0.32 : 0.55] }, { duration: 1.2, delay: 0.2 });

    let queued = null;
    hero.addEventListener("pointermove", (e) => {
      // One update per frame, however fast the pointer moves.
      if (queued) { queued = e; return; }
      queued = e;
      requestAnimationFrame(() => { const last = queued; queued = null; follow(last); });
    });
    const follow = (e) => {
      const r = hero.getBoundingClientRect();
      hero.style.setProperty("--mx", `${e.clientX - r.left}px`);
      hero.style.setProperty("--my", `${e.clientY - r.top}px`);
      const s = spectrum.getBoundingClientRect();
      bars.forEach((b, i) => {
        const x = s.left + (i + 0.5) * (s.width / bars.length);
        const near = Math.max(0, 1 - Math.abs(e.clientX - x) / 120);
        animate(b, { scaleY: 1 + near * 0.6 }, { type: "spring", stiffness: 300, damping: 18 });
      });
    };
    hero.addEventListener("pointerleave", () => animate(bars, { scaleY: 1 }, { type: "spring", stiffness: 200, damping: 20 }));
  }

  // ── Sections arrive as they scroll in ───────────────────────────────
  const once = (el, fn, amount = 0.25) => inView(el, () => { fn(); }, { amount });

  $$("section > h2, section > p, .trial, .ticks, .period, section > .card").forEach((el) =>
    once(el, () => rise(el, { y: 22 })));

  $$(".grid").forEach((grid) => once(grid, () => rise(Array.from(grid.children), { gap: 0.09 }), 0.15));

  $$(".steps").forEach((list) => once(list, () => {
    const items = Array.from(list.children);
    rise(items, { gap: 0.12, y: 16 });
    // The numbers (drawn by CSS) pop in after their lines, one beat each.
    items.forEach((li, i) => setTimeout(() => li.classList.add("pop"), 150 + 120 * i));
  }, 0.3));

  // Screenshots fan in like cards dealt onto a table, then tilt under the pointer.
  $$(".shots").forEach((shots) => {
    const figs = Array.from(shots.children);
    once(shots, () => {
      figs.forEach((fig, i) => {
        const side = i - (figs.length - 1) / 2;
        animate(fig, { opacity: [0, 1], y: [90, 0], rotate: [side * 7, 0], scale: [0.92, 1] },
          { type: "spring", stiffness: 140, damping: 18, delay: 0.1 + i * 0.09 });
      });
    }, 0.15);
    if (finePointer) figs.forEach((fig) => {
      const img = $("img", fig);
      fig.addEventListener("pointermove", (e) => {
        const r = fig.getBoundingClientRect();
        const px = (e.clientX - r.left) / r.width - 0.5;
        const py = (e.clientY - r.top) / r.height - 0.5;
        animate(img, { rotateY: px * 14, rotateX: -py * 14, scale: 1.04 }, { type: "spring", stiffness: 260, damping: 20 });
      });
      fig.addEventListener("pointerleave", () =>
        animate(img, { rotateY: 0, rotateX: 0, scale: 1 }, { type: "spring", stiffness: 200, damping: 18 }));
    });
  });

  // Plans: dealt in, prices counted up from zero, the featured one breathing.
  $$(".plans").forEach((plans) => once(plans, () => {
    const cards = Array.from(plans.children);
    cards.forEach((card, i) => {
      animate(card, { opacity: [0, 1], y: [40, 0], scale: [0.96, 1] },
        { type: "spring", stiffness: 160, damping: 20, delay: i * 0.1 });
      const amount = $(".amount", card);
      if (amount) {
        const to = Number(amount.textContent.replace(/[^0-9.]/g, ""));
        animate(0, to, { duration: 1.1, delay: 0.15 + i * 0.1, ease: easeOut, onUpdate: (v) => { amount.textContent = "$" + v.toFixed(2); } });
      }
    });
    const featured = $(".plan.featured", plans);
    if (featured) featured.classList.add("breathing");
  }, 0.2));

  // Legal pages: each paragraph slides in as it is reached.
  $$(".doc > *").forEach((el) => once(el, () => rise(el, { y: 14 }), 0.1));

  // The embedded ranking eases in.
  $$(".embed").forEach((el) => once(el, () => animate(el, { opacity: [0, 1], y: [30, 0] }, { duration: 0.8, ease: easeOut }), 0.05));

  // ── Touch and pointer feel ──────────────────────────────────────────
  // Buttons and switches give a springy push.
  press(".btn, .period button", (el) => {
    animate(el, { scale: 0.93 }, { type: "spring", stiffness: 600, damping: 20 });
    return () => animate(el, { scale: 1 }, { type: "spring", stiffness: 500, damping: 12 });
  });
  if (finePointer) {
    // Buttons lean toward the pointer a little.
    $$(".btn").forEach((btn) => {
      btn.addEventListener("pointermove", (e) => {
        const r = btn.getBoundingClientRect();
        animate(btn, { x: (e.clientX - r.left - r.width / 2) * 0.18, y: (e.clientY - r.top - r.height / 2) * 0.3 },
          { type: "spring", stiffness: 350, damping: 15 });
      });
      btn.addEventListener("pointerleave", () => animate(btn, { x: 0, y: 0 }, { type: "spring", stiffness: 300, damping: 12 }));
    });
    // Cards lift, and a light follows the pointer across them.
    hover(".card, .plan", (el) => {
      animate(el, { y: -6 }, { type: "spring", stiffness: 300, damping: 18 });
      return () => animate(el, { y: 0 }, { type: "spring", stiffness: 300, damping: 18 });
    });
    $$(".card, .plan").forEach((el) => el.addEventListener("pointermove", (e) => {
      const r = el.getBoundingClientRect();
      el.style.setProperty("--mx", `${e.clientX - r.left}px`);
      el.style.setProperty("--my", `${e.clientY - r.top}px`);
    }));
  }
})();
