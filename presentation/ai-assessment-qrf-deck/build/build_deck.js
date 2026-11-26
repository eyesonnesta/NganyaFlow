// Builds the NganyaFlow AI-assessment deck from the persisted October experiment.
// Run from the repository root:
//   NODE_PATH=<folder containing pptxgenjs> APPLY_THEME=<pptx skill>/scripts/apply_theme.js \
//     node presentation/ai-assessment-qrf-deck/build/build_deck.js
// All experiment figures are read from docs/evidence/ at build time; nothing is retyped.
// Speaker notes live in notes.js.
const fs = require("fs");
const path = require("path");
const pptxgen = require("pptxgenjs");

const ROOT = process.cwd();
const OUT = path.join(ROOT, "presentation", "ai-assessment-qrf-deck");
const DECK = path.join(OUT, "NganyaFlow-QRF-presentation.pptx");
const APPLY_THEME = process.env.APPLY_THEME;

// ---------- evidence ----------
const ev = JSON.parse(fs.readFileSync(path.join(ROOT, "docs/evidence/evaluation.json"), "utf8"));
const csvText = fs.readFileSync(path.join(ROOT, "docs/evidence/held-out-predictions.csv"), "utf8").trim();
const [header, ...lines] = csvText.split(/\r?\n/);
const cols = header.split(",");
const rows = lines.map((l) => Object.fromEntries(l.split(",").map((v, i) => [cols[i], v])));
const num = (r, k) => parseFloat(r[k]);
const mean = (a) => a.reduce((s, v) => s + v, 0) / a.length;
const y = rows.map((r) => num(r, "actual_wait_minutes"));
const maeOf = (k) => mean(rows.map((r, i) => Math.abs(y[i] - num(r, k))));
const M = {
  qrf: maeOf("median_minutes"),
  lh: maeOf("last_headway_minutes"), // this CSV column holds the baseline prediction max(0, headway - elapsed)
  hist: maeOf("historical_median_minutes"),
  cov: mean(rows.map((r, i) => (num(r, "lower_minutes") <= y[i] && y[i] <= num(r, "upper_minutes") ? 1 : 0))),
  width: mean(rows.map((r) => num(r, "upper_minutes") - num(r, "lower_minutes"))),
};
for (const [k, e] of [["qrf", ev.mae_minutes], ["lh", ev.last_headway_mae_minutes], ["hist", ev.historical_median_mae_minutes], ["cov", ev.empirical_coverage], ["width", ev.mean_interval_width_minutes]]) {
  if (Math.abs(M[k] - e) > 1e-9) throw new Error(`CSV and evaluation.json disagree on ${k}: ${M[k]} vs ${e}`);
}
const gain = M.lh - M.qrf;
const gainPct = (100 * gain) / M.lh;
const stage = Object.fromEntries(ev.per_group.map((g) => [g.stage_id, g]));
const fmtPct = (v) => (100 * v).toFixed(2) + "%";

// Interval sequence: first held-out session in file order, first 12 consecutive queries.
const firstSession = rows[0].session_id;
const seq = rows.filter((r) => r.session_id === firstSession).sort((a, b) => a.query_at.localeCompare(b.query_at)).slice(0, 12);
const toNairobi = (iso) => new Date(new Date(iso).getTime() + 3 * 3600 * 1000); // Africa/Nairobi is UTC+3 all year
const hhmmss = (d) => d.toISOString().slice(11, 19);
const seqPts = seq.map((r) => ({ t: toNairobi(r.query_at), lo: num(r, "lower_minutes"), md: num(r, "median_minutes"), hi: num(r, "upper_minutes"), y: num(r, "actual_wait_minutes") }));
seqPts.forEach((p) => (p.inside = p.lo <= p.y && p.y <= p.hi));
const seqInside = seqPts.filter((p) => p.inside).length;
// Worked appendix row: the query in that session at 07:09 local time.
const workRow = seq.find((r) => hhmmss(toNairobi(r.query_at)).startsWith("07:09"));
const W = { y: num(workRow, "actual_wait_minutes"), lo: num(workRow, "lower_minutes"), md: num(workRow, "median_minutes"), hi: num(workRow, "upper_minutes"), lh: num(workRow, "last_headway_minutes"), t: hhmmss(toNairobi(workRow.query_at)) };
const pin = (tau, yy, q) => (yy >= q ? tau * (yy - q) : (1 - tau) * (q - yy));
// R-squared, supplementary (cross-checked against server/notebooks/results/model_comparison.csv).
const ybar = mean(y);
const sst = y.reduce((s, v) => s + (v - ybar) ** 2, 0);
const r2 = (k) => 1 - rows.reduce((s, r, i) => s + (y[i] - num(r, k)) ** 2, 0) / sst;
const R2 = { qrf: r2("median_minutes"), lh: r2("last_headway_minutes") };

// ---------- design system ----------
const THEME = {
  name: "NganyaFlow transit",
  headFontFace: "Calibri",
  bodyFontFace: "Calibri",
  colors: {
    dk1: "172127", lt1: "FFFFFF", dk2: "1B4965", lt2: "F7FAFC",
    accent1: "1B4965", accent2: "9B431E", accent3: "CAE9F5", accent4: "526471",
    accent5: "6C7D88", accent6: "BBC9D3", hlink: "1B4965", folHlink: "526471",
  },
};
const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE"; // 13.333 x 7.5 in
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
pres.title = "NganyaFlow: How long until the next matatu?";
pres.subject = "Estimating waiting time with a Quantile Regression Forest";
pres.author = "NganyaFlow team";
const C = pres.SchemeColor;
// Colour jobs: BLUE = model output, PALE = model interval, RUST = observed arrival / exception.
const INK = C.text1, BLUE = C.accent1, RUST = C.accent2, PALE = C.accent3, MUTED = C.accent4, OUTL = C.accent5, RULE = C.accent6, WHITE = C.background1;
const HEX = { ink: "172127", blue: "1B4965", rust: "9B431E", pale: "CAE9F5", muted: "526471", grid: "D5DEE5", bg: "F7FAFC", grey: "8A9AA5", tint: "EBF1F5", tint2: "E2EAF0" };
const SW = 1.75; // icon and diagram stroke, pt

// One content layout: a section header (Background Information, Problem Statement, ...) and nothing else.
pres.defineSlideMaster({ title: "TITLE", background: { color: HEX.bg }, objects: [] });
pres.defineSlideMaster({
  title: "CONTENT",
  background: { color: HEX.bg },
  objects: [{ placeholder: { options: { name: "title", type: "title", x: 0.6, y: 0.35, w: 12.1, h: 0.75, fontSize: 36, bold: true, color: INK, valign: "bottom", align: "left", margin: 0, fontFace: "Calibri" }, text: "" } }],
});

// ---------- drawing helpers ----------
const T = (s, text, o) => s.addText(text, { isTextBox: true, margin: 0, color: INK, fontSize: 20, valign: "top", ...o });
function L(s, x1, y1, x2, y2, o = {}) {
  s.addShape(pres.shapes.LINE, {
    x: Math.min(x1, x2), y: Math.min(y1, y2), w: Math.abs(x2 - x1), h: Math.abs(y2 - y1),
    flipH: x2 < x1, flipV: y2 < y1,
    line: { color: o.color || INK, width: o.width || SW, dashType: o.dash || "solid", endArrowType: o.arrow ? "triangle" : undefined },
  });
}
const R = (s, x, y, w, h, o = {}) => s.addShape(o.round ? pres.shapes.ROUNDED_RECTANGLE : pres.shapes.RECTANGLE, { x, y, w, h, rectRadius: o.round, fill: o.fill ? { color: o.fill } : { type: "none" }, line: o.line ? { color: o.line, width: o.lw || SW, dashType: o.dash || "solid" } : { type: "none" } });
const Dot = (s, cx, cy, d, o = {}) => s.addShape(pres.shapes.OVAL, { x: cx - d / 2, y: cy - d / 2, w: d, h: d, fill: o.hollow ? { color: HEX.bg } : { color: o.fill || RUST }, line: { color: o.line || o.fill || RUST, width: o.lw || SW, dashType: o.dash || "solid" } });
// Bracket spanning x1..x2 at height yy; end ticks point down (or up when below=true).
function Bracket(s, x1, x2, yy, color, below) {
  const d = below ? -0.12 : 0.12;
  L(s, x1, yy, x2, yy, { color, width: 1.5 });
  L(s, x1, yy, x1, yy + d, { color, width: 1.5 });
  L(s, x2, yy, x2, yy + d, { color, width: 1.5 });
}

// Icon family: native vector shapes, one stroke width, square optical box of size z.
const Icon = {
  stage(s, x, y, z, col = BLUE) {
    R(s, x + 0.2 * z, y, 0.6 * z, 0.42 * z, { line: col, round: 0.06, fill: col });
    L(s, x + 0.5 * z, y + 0.42 * z, x + 0.5 * z, y + z, { color: col });
    L(s, x + 0.3 * z, y + z, x + 0.7 * z, y + z, { color: col });
  },
  clock(s, x, y, z, col = BLUE) {
    s.addShape(pres.shapes.OVAL, { x, y, w: z, h: z, fill: { type: "none" }, line: { color: col, width: SW } });
    L(s, x + 0.5 * z, y + 0.5 * z, x + 0.5 * z, y + 0.2 * z, { color: col });
    L(s, x + 0.5 * z, y + 0.5 * z, x + 0.72 * z, y + 0.6 * z, { color: col });
  },
  observe(s, x, y, z, col = RUST) {
    R(s, x + 0.14 * z, y + 0.06 * z, 0.72 * z, 0.9 * z, { line: col, round: 0.05 });
    R(s, x + 0.34 * z, y, 0.32 * z, 0.14 * z, { fill: col, line: col });
    [0.36, 0.56, 0.76].forEach((f) => { Dot(s, x + 0.3 * z, y + f * z, 0.09 * z, { fill: col }); L(s, x + 0.42 * z, y + f * z, x + 0.74 * z, y + f * z, { color: col }); });
  },
  validate(s, x, y, z, col = INK) {
    R(s, x + 0.08 * z, y + 0.08 * z, 0.84 * z, 0.84 * z, { line: col, round: 0.08 });
    L(s, x + 0.26 * z, y + 0.52 * z, x + 0.44 * z, y + 0.7 * z, { color: col, width: 2.25 });
    L(s, x + 0.44 * z, y + 0.7 * z, x + 0.76 * z, y + 0.3 * z, { color: col, width: 2.25 });
  },
  inputs(s, x, y, z, col = INK) {
    [0, 1, 2].forEach((i) => R(s, x + i * 0.34 * z, y + 0.3 * z, 0.3 * z, 0.4 * z, { line: col, lw: SW }));
  },
  forest(s, x, y, z, col = BLUE) {
    R(s, x + 0.08 * z, y + 0.38 * z, 0.84 * z, 0.26 * z, { fill: PALE, line: col, lw: 1 });
    L(s, x + 0.45 * z, y + 0.22 * z, x + 0.45 * z, y + 0.8 * z, { color: col, width: 2.5 });
  },
  rider(s, x, y, z, col = BLUE) {
    R(s, x + 0.24 * z, y, 0.52 * z, z, { line: col, round: 0.08 });
    R(s, x + 0.33 * z, y + 0.4 * z, 0.34 * z, 0.14 * z, { fill: PALE, line: col, lw: 1 });
  },
  unavailable(s, x, y, z, col = RUST) {
    s.addShape(pres.shapes.OVAL, { x, y, w: z, h: z, fill: { type: "none" }, line: { color: col, width: SW } });
    L(s, x + 0.2 * z, y + 0.8 * z, x + 0.8 * z, y + 0.2 * z, { color: col });
  },
};

const NOTE_TEXT = require("./notes.js")({ M, gain, gainPct, stage, ev, seqInside, W, R2, fmtPct });
const NOTES = [];
function slideOf(master, title) {
  const notes = NOTE_TEXT[NOTES.length];
  const s = pres.addSlide({ masterName: master });
  if (title) s.addText(title, { placeholder: "title" });
  s.addNotes(notes);
  NOTES.push({ title: title || "NganyaFlow: How long until the next matatu?", notes });
  return s;
}

// Section header plus one plain sentence that states the slide's point.
// Page numbers start at 1 on Background Information (the third slide).
function section(header, point) {
  const s = slideOf("CONTENT", header);
  if (point) T(s, point, { x: 0.6, y: 1.15, w: 12.1, h: 0.5, fontSize: 22, color: BLUE });
  if (NOTES.length >= 3) T(s, String(NOTES.length - 2), { x: 12.2, y: 6.95, w: 0.5, h: 0.3, fontSize: 12, color: MUTED, align: "right" });
  return s;
}

// ===== 1. Title =====
{
  const s = slideOf("TITLE", null);
  T(s, "NganyaFlow", { x: 0.8, y: 0.75, w: 6, h: 0.45, fontSize: 24, bold: true, color: BLUE });
  T(s, [{ text: "How long until", options: { breakLine: true } }, { text: "the next matatu?" }], { x: 0.8, y: 1.3, w: 11, h: 2.0, fontSize: 56, bold: true, lineSpacingMultiple: 0.95 });
  T(s, "Estimating waiting time with a Quantile Regression Forest", { x: 0.8, y: 3.4, w: 11, h: 0.5, fontSize: 26, color: MUTED });
  const x0 = 0.8, x1 = 12.5, ly = 5.85;
  const tx = (m) => 2.0 + m * 0.62;
  L(s, x0, ly, tx(9), ly, { color: RULE, width: 4 });
  L(s, tx(9), ly, x1, ly, { color: RULE, width: 4, dash: "dash" });
  Icon.stage(s, x0, 4.65, 0.5, BLUE);
  T(s, [{ text: "Kencom stage", options: { bold: true } }, { text: " · Nairobi CBD" }], { x: x0 + 0.65, y: 4.7, w: 7.5, h: 0.42, fontSize: 18 });
  [[0, "07:00"], [6, "07:06"]].forEach(([m, lab]) => {
    Dot(s, tx(m), ly, 0.26, { fill: RUST });
    T(s, lab, { x: tx(m) - 0.6, y: ly + 0.25, w: 1.2, h: 0.35, fontSize: 16, color: RUST, align: "center" });
  });
  L(s, tx(9), ly - 0.55, tx(9), ly + 0.3, { color: BLUE, width: 3.5 });
  Icon.clock(s, tx(9) - 0.2, ly - 1.2, 0.4, BLUE);
  T(s, "07:09", { x: tx(9) + 0.3, y: ly - 1.25, w: 1.6, h: 0.5, fontSize: 28, bold: true, color: BLUE });
  T(s, "now", { x: tx(9) - 0.6, y: ly + 0.35, w: 1.2, h: 0.35, fontSize: 16, color: BLUE, align: "center" });
  T(s, "next arrival: ?", { x: 9.4, y: ly - 0.7, w: 3.1, h: 0.5, fontSize: 24, bold: true, color: MUTED, align: "right" });
}

// ===== 2. Presenters =====
{
  const s = slideOf("CONTENT", "Presenters");
  const people = [["Obino Ian Nyamosi", "189942"], ["Makau Robert Mwendwa", "189607"], ["Karani Claude Manoti", "178616"]];
  const lx = 1.1, y0 = 2.15, dy = 1.45;
  L(s, lx, y0, lx, y0 + 2 * dy, { color: BLUE, width: 4 });
  people.forEach(([name, id], i) => {
    const yy = y0 + i * dy;
    Dot(s, lx, yy, 0.34, { fill: WHITE, line: BLUE, lw: 3 });
    T(s, name, { x: lx + 0.55, y: yy - 0.38, w: 9, h: 0.6, fontSize: 32, bold: true });
    T(s, id, { x: lx + 0.55, y: yy + 0.2, w: 9, h: 0.45, fontSize: 22, color: MUTED });
  });
}

// ===== 3. Background Information =====
{
  const s = section("Background Information", "Nairobi's matatu routes are mapped, but arrival times are not recorded.");
  // route fragment
  const ry = 2.9;
  L(s, 0.9, ry, 6.1, ry, { color: MUTED, width: 4 });
  [[0.9, "stage"], [2.6, "Kencom"], [4.35, "stage"], [6.1, "stage"]].forEach(([x, lab], i) => {
    Dot(s, x, ry, i === 1 ? 0.36 : 0.24, { fill: i === 1 ? BLUE : WHITE, line: i === 1 ? BLUE : MUTED });
    T(s, lab, { x: x - 0.8, y: ry + 0.28, w: 1.6, h: 0.38, fontSize: 18, bold: i === 1, color: i === 1 ? BLUE : MUTED, align: "center" });
  });
  T(s, "route →", { x: 4.5, y: ry - 0.62, w: 1.6, h: 0.38, fontSize: 16, color: MUTED, align: "right" });
  T(s, "Digital Matatus route map (GTFS, 2019)", { x: 0.6, y: 3.85, w: 6, h: 0.4, fontSize: 16, italic: true, color: MUTED });
  const pts = [
    [BLUE, "A matatu stage is a boarding point where riders wait for their route."],
    [BLUE, "Digital Matatus mapped the city's matatu routes and stops by riding them with phones (2019)."],
    [RUST, "The map shows where to board and which routes stop there. It does not record when vehicles actually arrive."],
  ];
  pts.forEach(([col, t], i) => {
    const yy = 2.0 + i * 1.2;
    Dot(s, 7.15, yy + 0.2, 0.2, { fill: col, line: col });
    T(s, t, { x: 7.45, y: yy, w: 5.25, h: 1.1, fontSize: 20 });
  });
}

// ===== 4. Problem Statement =====
{
  const s = section("Problem Statement", "A rider at a stage cannot tell how long the wait for the next matatu will be.");
  const ax0 = 0.9, ax1 = 12.4, ay = 2.95;
  const tx = (m) => ax0 + 0.4 + m * 0.72;
  L(s, ax0, ay, ax1, ay, { color: MUTED, width: 1.5 });
  [[0, "07:00"], [6, "07:06"]].forEach(([m, lab]) => { Dot(s, tx(m), ay, 0.28, { fill: RUST }); T(s, lab, { x: tx(m) - 0.6, y: ay + 0.22, w: 1.2, h: 0.38, fontSize: 18, color: RUST, align: "center" }); });
  T(s, "vehicles seen", { x: tx(0), y: ay - 0.62, w: tx(6) - tx(0), h: 0.35, fontSize: 16, color: RUST, align: "center" });
  Dot(s, tx(14), ay, 0.28, { hollow: true, line: RUST, dash: "dash" });
  T(s, "next arrival", { x: tx(14) - 0.9, y: ay + 0.22, w: 1.8, h: 0.38, fontSize: 18, color: RUST, align: "center" });
  L(s, tx(9), ay - 0.5, tx(9), ay + 0.35, { color: BLUE, width: 3.5 });
  T(s, "rider asks, 07:09", { x: tx(9) - 1.1, y: ay + 0.4, w: 2.2, h: 0.38, fontSize: 18, bold: true, color: BLUE, align: "center" });
  Bracket(s, tx(9), tx(14), ay - 0.7, INK, false);
  T(s, "waiting time = ?", { x: tx(9), y: ay - 1.12, w: tx(14) - tx(9), h: 0.4, fontSize: 20, bold: true, align: "center" });
  R(s, 0.6, 4.15, 12.1, 1.3, { fill: HEX.tint });
  T(s, [{ text: "Research question", options: { bold: true, color: BLUE, breakLine: true } }, { text: "Given a stage, route, direction and recent arrivals, can we estimate the minutes until the next vehicle and give a useful range?" }], { x: 0.85, y: 4.25, w: 11.6, h: 1.1, fontSize: 22, valign: "middle" });
  T(s, [{ text: "Waiting time: ", options: { bold: true } }, { text: "minutes from the question to the next vehicle arrival. A full vehicle still counts. Seats, boarding time and trip length are not predicted." }], { x: 0.6, y: 5.75, w: 12.1, h: 0.85, fontSize: 19 });
}

// ===== 5. Proposed Solution =====
{
  const s = section("Proposed Solution", "Recent arrivals feed a model that returns a likely wait and a range.");
  const nodes = [
    ["observe", "Enumerator records arrivals", "at the stage"],
    ["validate", "Server checks each record", "time zone, session, duplicates"],
    ["inputs", "Latest arrivals become inputs", "time waited, last gap"],
    ["forest", "Model estimates wait + range", "10th, 50th, 90th percentile"],
    ["rider", "Rider sees the range", "and how fresh it is"],
  ];
  const nw = 2.2, gap = 0.275, ny = 1.9, nh = 2.05;
  nodes.forEach(([ic, head, sub], i) => {
    const x = 0.6 + i * (nw + gap);
    const out = i >= 3;
    R(s, x, ny, nw, nh, { fill: out ? HEX.tint : WHITE, line: out ? HEX.blue : HEX.grid, lw: 1.25, round: 0.08 });
    Icon[ic](s, x + 0.2, ny + 0.2, 0.5, ic === "observe" ? RUST : out ? BLUE : INK);
    T(s, head, { x: x + 0.2, y: ny + 0.8, w: nw - 0.35, h: 0.62, fontSize: 17, bold: true, color: out ? BLUE : INK });
    T(s, sub, { x: x + 0.2, y: ny + 1.45, w: nw - 0.3, h: 0.5, fontSize: 14, color: MUTED });
    if (i < 4) L(s, x + nw + 0.03, ny + nh / 2, x + nw + gap - 0.03, ny + nh / 2, { color: INK, width: 1.5, arrow: true });
  });
  const bx = 0.6 + 2 * (nw + gap) + nw / 2;
  L(s, bx, ny + nh, bx, 4.5, { color: RUST, width: 1.75, arrow: true });
  R(s, 3.75, 4.55, 5.3, 2.15, { fill: WHITE, line: HEX.rust, lw: 1.5, round: 0.08 });
  Icon.unavailable(s, 3.95, 4.75, 0.45, RUST);
  T(s, "Estimate unavailable when", { x: 4.55, y: 4.75, w: 4.3, h: 0.45, fontSize: 20, bold: true, color: RUST });
  T(s, [
    { text: "the newest arrival is over 30 min old", options: { bullet: true, breakLine: true } },
    { text: "fewer than 3 recent arrivals", options: { bullet: true, breakLine: true } },
    { text: "conditions unlike the training data", options: { bullet: true } },
  ], { x: 4.0, y: 5.3, w: 4.9, h: 1.3, fontSize: 17, paraSpaceAfter: 3 });
  T(s, "New arrivals update the inputs immediately. The model itself changes only when we retrain it and activate the new version.", { x: 0.6, y: 4.6, w: 2.9, h: 2.0, fontSize: 16, color: MUTED });
  const img = path.join(OUT, "build", "android-estimate-demo-crop.png");
  const ih = 1.75, iw = ih * (656 / 492);
  const ix = 12.7 - iw;
  s.addImage({ path: img, x: ix, y: 4.55, w: iw, h: ih, altText: "NganyaFlow Android screen showing next vehicle in 0.3–3.4 min on demo data" });
  R(s, ix, 4.55, iw, ih, { line: HEX.grid, lw: 0.75 });
  T(s, "Our Android app (demo data)", { x: 9.3, y: 6.38, w: 3.4, h: 0.3, fontSize: 13, color: MUTED, align: "right" });
}

// ===== 6. Data Collection and Preparation =====
{
  const s = section("Data Collection and Preparation", "1,049 recorded arrivals became 5,943 labelled examples.");
  const head = (t, x, y, w) => T(s, t, { x, y, w, h: 0.4, fontSize: 20, bold: true });
  head("Where the data comes from", 0.6, 1.85, 5.9);
  T(s, [
    { text: "Routes and stops: ", options: { bold: true } },
    { text: "Digital Matatus GTFS feed (May 2019), built by University of Nairobi researchers who rode matatus and recorded stops on phones. Used for stage locations and routes.", options: { breakLine: true } },
    { text: "Arrivals: ", options: { bold: true } },
    { text: "a log for Kencom, Odeon and the Railways area, 5–18 Oct 2026: two 90-minute sessions per stage per day, 84 sessions in all. The arrival times come from our simulator, because verified stage logs were not yet available.", options: { breakLine: true } },
    { text: "Each record: ", options: { bold: true } },
    { text: "session, stage, route, direction and an arrival time with its time zone." },
  ], { x: 0.6, y: 2.35, w: 5.9, h: 3.0, fontSize: 16, paraSpaceAfter: 8 });

  head("How we prepared it", 6.9, 1.85, 5.8);
  const steps = [
    ["Validate", "reject records with no time zone, outside their session, at an unknown stage or route, or duplicated. Identical resends are acknowledged, not stored twice."],
    ["Order", "group arrivals by session and stage, route and direction; sort by time."],
    ["Label", "for every minute after an arrival, record the previous gap and time waited (inputs) and the time to the next arrival (answer)."],
    ["Exclude", "a session's first arrival (no previous gap), its open end (next arrival not seen), gaps over 120 min, and anything crossing midnight."],
  ];
  steps.forEach(([h, b], i) => {
    const yy = [2.35, 3.15, 3.7, 4.5][i];
    Dot(s, 7.05, yy + 0.16, 0.22, { fill: WHITE, line: BLUE, lw: 2 });
    T(s, String(i + 1), { x: 6.94, y: yy + 0.05, w: 0.22, h: 0.22, fontSize: 11, bold: true, color: BLUE, align: "center", valign: "middle" });
    T(s, [{ text: h + ": ", options: { bold: true } }, { text: b }], { x: 7.35, y: yy, w: 5.35, h: 0.75, fontSize: 15 });
  });
  T(s, "All 1,049 records passed. A test copy with 7 deliberately broken rows was rejected as expected.", { x: 6.9, y: 5.5, w: 5.8, h: 0.4, fontSize: 14, italic: true, color: MUTED });

  // compact worked example
  const ax0 = 0.9, ax1 = 6.3, ay = 6.25;
  const tx = (m) => ax0 + 0.15 + m * 0.36;
  R(s, tx(9), 5.45, ax1 - tx(9), 1.3, { fill: HEX.tint2 });
  L(s, ax0, ay, ax1, ay, { color: MUTED, width: 1.25 });
  [0, 6, 14].forEach((m) => Dot(s, tx(m), ay, 0.22, { fill: RUST }));
  [[0, "07:00"], [6, "07:06"], [14, "07:14"]].forEach(([m, lab]) => T(s, lab, { x: tx(m) - 0.45, y: ay + 0.15, w: 0.9, h: 0.3, fontSize: 14, bold: true, color: RUST, align: "center" }));
  L(s, tx(9), 5.45, tx(9), ay + 0.12, { color: BLUE, width: 3 });
  T(s, "07:09", { x: tx(9) - 0.45, y: ay + 0.15, w: 0.9, h: 0.3, fontSize: 14, bold: true, color: BLUE, align: "center" });
  Bracket(s, tx(0), tx(6), 5.95, INK, false);
  T(s, "gap 6 min", { x: tx(0), y: 5.6, w: tx(6) - tx(0), h: 0.3, fontSize: 14, bold: true, align: "center" });
  T(s, "waited 3 min", { x: tx(6), y: 5.6, w: tx(9) - tx(6), h: 0.3, fontSize: 13, bold: true, align: "center" });
  T(s, "answer 5 min", { x: tx(9), y: 5.6, w: tx(14) - tx(9) + 0.3, h: 0.3, fontSize: 14, bold: true, color: RUST, align: "center" });
}

// ===== 7. Model: Quantile Regression Forest =====
{
  const s = section("Model: Quantile Regression Forest", "Supervised regression that predicts a typical wait and a range in one model.");
  const block = (h, body, y, hgt) => {
    T(s, h, { x: 0.6, y, w: 5.6, h: 0.38, fontSize: 19, bold: true, color: BLUE });
    T(s, body, { x: 0.6, y: y + 0.4, w: 5.6, h: hgt, fontSize: 16 });
  };
  block("Type of learning", "Supervised regression: every example pairs the inputs known at the query time with the actual wait in minutes. The model learns the mapping from labelled history.", 1.85, 0.95);
  block("Why not deep learning", "We have about 4,700 training rows with 7 inputs in a table. A tree ensemble fits data of this size well; a neural network would need far more data and is harder to explain.", 3.25, 0.95);
  block("How it fits our data", "100 decision trees, each grown on a random resample. Splits such as \"waited under 2.5 min\" separate short waits from long ones. Each leaf keeps the actual waits that land in it; a new query collects those waits and reads off the 10th, 50th and 90th percentiles.", 4.65, 1.35);

  T(s, "Compared with other regression methods", { x: 6.7, y: 1.85, w: 6.0, h: 0.38, fontSize: 19, bold: true, color: BLUE });
  const hdr = (t) => ({ text: t, options: { bold: true, color: HEX.muted, fontSize: 14 } });
  const qrfRow = (t) => ({ text: t, options: { bold: true, color: HEX.blue, fill: { color: HEX.tint } } });
  const tbl = [
    [hdr("Method"), hdr("Output"), hdr("Limitation here")],
    ["Linear regression", "One number", "Assumes straight-line effects; our inputs interact"],
    ["Random forest", "One number (mean)", "No range for the rider"],
    ["Gradient boosting", "One number per model", "Needs a separate model for each percentile"],
    ["Neural network", "One number", "Needs much more data; hard to explain"],
    [qrfRow("Quantile regression forest"), qrfRow("Median + range"), qrfRow("Chosen: one model, small tabular data")],
  ];
  s.addTable(tbl, { x: 6.7, y: 2.35, w: 6.0, colW: [1.9, 1.55, 2.55], fontSize: 14, fontFace: "Calibri", color: HEX.ink, border: { type: "solid", pt: 0.75, color: HEX.grid }, fill: { color: "FFFFFF" }, rowH: 0.62, valign: "middle", margin: [0.03, 0.08, 0.03, 0.08] });
  T(s, "Linear regression, gradient boosting and neural networks were compared on design grounds, not trained. The forest was tested against two simple rules (next slides).", { x: 6.7, y: 6.15, w: 6.0, h: 0.6, fontSize: 13, italic: true, color: MUTED });
}

// ===== 7b. Model: how a prediction is made =====
{
  const s = section("Model: How a Prediction Is Made", "Our 07:09 query runs through 100 trees; the waits in their leaves give the range.");
  const fields = [["stage", "Kencom"], ["route", "study route"], ["direction", "townbound"], ["time of day", "07:09"], ["day", "Fri"], ["waited", "3 min"], ["previous gap", "6 min"]];
  const fw = 1.66, fg = 0.08;
  fields.forEach(([k, v], i) => {
    const x = 0.6 + i * (fw + fg);
    R(s, x, 1.85, fw, 0.8, { fill: WHITE, line: i >= 5 ? HEX.blue : HEX.grid, lw: i >= 5 ? 1.5 : 1 });
    T(s, k, { x: x + 0.1, y: 1.9, w: fw - 0.2, h: 0.3, fontSize: 13, color: MUTED });
    T(s, v, { x: x + 0.1, y: 2.2, w: fw - 0.2, h: 0.4, fontSize: 18, bold: true });
  });
  const leafVals = [[3.4, 4.1, 4.6, 5.0, 5.4, 6.2], [2.6, 3.9, 4.5, 5.2, 5.9, 7.1], [3.1, 4.8, 5.3, 5.8, 6.4, 8.0]];
  const leafPick = [2, 1, 3];
  const rowY = [3.55, 4.6, 5.75];
  const sx0 = 4.0, sx1 = 6.7;
  const sxv = (v) => sx0 + (v / 10) * (sx1 - sx0);
  T(s, "100 trees, each trained on a different sample", { x: 0.6, y: 2.88, w: 6.3, h: 0.3, fontSize: 15, bold: true });
  rowY.forEach((ry, t) => {
    const tx0 = 1.55, tw = 1.6, th = 0.62;
    const top = ry - th / 2;
    const root = [tx0 + tw / 2, top];
    const mids = [[tx0 + tw * 0.25, top + th * 0.5], [tx0 + tw * 0.75, top + th * 0.5]];
    const leaves = [[tx0, top + th], [tx0 + tw * 0.33, top + th], [tx0 + tw * 0.67, top + th], [tx0 + tw, top + th]];
    const pick = leafPick[t];
    const viaMid = pick < 2 ? 0 : 1;
    L(s, root[0], root[1], mids[0][0], mids[0][1], { color: viaMid === 0 ? BLUE : RULE, width: viaMid === 0 ? 3 : 1.5 });
    L(s, root[0], root[1], mids[1][0], mids[1][1], { color: viaMid === 1 ? BLUE : RULE, width: viaMid === 1 ? 3 : 1.5 });
    leaves.forEach((lf, li) => L(s, mids[li < 2 ? 0 : 1][0], mids[li < 2 ? 0 : 1][1], lf[0], lf[1], { color: li === pick ? BLUE : RULE, width: li === pick ? 3 : 1.5 }));
    [root, ...mids].forEach((p) => Dot(s, p[0], p[1], 0.14, { fill: WHITE, line: OUTL, lw: 1.25 }));
    leaves.forEach((p, li) => Dot(s, p[0], p[1], li === pick ? 0.2 : 0.14, { fill: li === pick ? BLUE : WHITE, line: li === pick ? BLUE : OUTL, lw: 1.25 }));
    T(s, ["Tree 1", "Tree 2", "Tree 100"][t], { x: 0.6, y: ry - 0.18, w: 0.9, h: 0.35, fontSize: 15, bold: true, color: MUTED });
    if (t === 1) T(s, "⋮", { x: 0.85, y: 5.0, w: 0.4, h: 0.4, fontSize: 18, color: MUTED });
    L(s, leaves[pick][0] + 0.12, leaves[pick][1], sx0 - 0.15, ry + 0.3, { color: BLUE, width: 1.25, dash: "dash", arrow: true });
    L(s, sx0, ry + 0.3, sx1, ry + 0.3, { color: RULE, width: 1 });
    leafVals[t].forEach((v) => Dot(s, sxv(v), ry + 0.3, 0.16, { fill: RUST }));
  });
  T(s, "past waits kept in the leaf the query reaches", { x: sx0 - 0.1, y: 6.3, w: 3.2, h: 0.5, fontSize: 14, italic: true, color: RUST });
  const dx0 = 7.75, dx1 = 12.6, dyb = 5.6, dH = 1.75;
  const dxv = (v) => dx0 + (v / 10) * (dx1 - dx0);
  T(s, "All leaves together → range of likely waits", { x: 7.6, y: 2.88, w: 5.1, h: 0.3, fontSize: 15, bold: true });
  const p10 = 3.3, p50 = 4.9, p90 = 6.9;
  R(s, dxv(p10), dyb - dH - 0.15, dxv(p90) - dxv(p10), dH + 0.15, { fill: PALE });
  [0, 0.05, 0.18, 0.55, 0.85, 1.0, 0.72, 0.42, 0.2, 0.08].forEach((h, i) => { if (h > 0) R(s, dxv(i) + 0.03, dyb - h * dH, dxv(1) - dxv(0) - 0.06, h * dH, { fill: WHITE, line: HEX.rust, lw: 1.25 }); });
  L(s, dx0, dyb, dx1, dyb, { color: MUTED, width: 1.25 });
  [0, 5, 10].forEach((v) => T(s, `${v}`, { x: dxv(v) - 0.3, y: dyb + 0.05, w: 0.6, h: 0.28, fontSize: 13, color: MUTED, align: "center" }));
  T(s, "minutes until next arrival", { x: dx0 + 1.2, y: dyb + 0.3, w: 2.6, h: 0.28, fontSize: 13, color: MUTED, align: "center" });
  L(s, dxv(p50), dyb - dH - 0.15, dxv(p50), dyb, { color: BLUE, width: 3.5 });
  T(s, "10th", { x: dxv(p10) - 0.75, y: dyb - dH - 0.1, w: 0.7, h: 0.32, fontSize: 16, bold: true, color: BLUE, align: "right" });
  T(s, "median", { x: dxv(p50) - 0.7, y: dyb - dH - 0.5, w: 1.4, h: 0.32, fontSize: 16, bold: true, color: BLUE, align: "center" });
  T(s, "90th", { x: dxv(p90) + 0.05, y: dyb - dH - 0.1, w: 0.7, h: 0.32, fontSize: 16, bold: true, color: BLUE });
  T(s, "The shaded band is the range shown to the rider.", { x: 7.6, y: 6.3, w: 5.1, h: 0.35, fontSize: 15, color: BLUE });
}


// ===== 8. Evaluation Method =====
{
  const s = section("Evaluation Method", "Trained on 11 earlier days, tested on 3 later days the model never saw.");
  const cw = 0.8, cg = 0.064;
  const wd = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]; // 5 Oct 2026 is a Monday
  T(s, "October 2026", { x: 0.6, y: 1.8, w: 4, h: 0.3, fontSize: 15, color: MUTED });
  for (let i = 0; i < 14; i++) {
    const x = 0.6 + i * (cw + cg);
    const test = i >= 11;
    R(s, x, 2.15, cw, 0.85, { fill: test ? HEX.ink : HEX.tint2, line: test ? HEX.ink : HEX.grid, lw: 0.75 });
    T(s, String(5 + i), { x, y: 2.2, w: cw, h: 0.45, fontSize: 22, bold: true, color: test ? WHITE : INK, align: "center" });
    T(s, wd[i % 7], { x, y: 2.65, w: cw, h: 0.3, fontSize: 12, color: test ? WHITE : MUTED, align: "center" });
  }
  const trainEnd = 0.6 + 11 * (cw + cg) - cg;
  Bracket(s, 0.6, trainEnd, 3.15, MUTED, true);
  T(s, [{ text: "Training  ", options: { bold: true } }, { text: "66 sessions · 4,684 examples" }], { x: 0.6, y: 3.25, w: trainEnd - 0.6, h: 0.4, fontSize: 18, align: "center" });
  const testX = 0.6 + 11 * (cw + cg);
  Bracket(s, testX, 12.7, 3.15, INK, true);
  T(s, [{ text: "Test  ", options: { bold: true } }, { text: "18 sessions", options: { breakLine: true } }, { text: "1,259 examples" }], { x: 9.7, y: 3.25, w: 3.0, h: 0.7, fontSize: 16, align: "right" });
  T(s, "Why split by date", { x: 0.6, y: 4.3, w: 5.8, h: 0.4, fontSize: 20, bold: true });
  const qy = 5.2, qx0 = 1.0, step = 0.6;
  L(s, 0.75, qy, qx0 + 8 * step + 0.25, qy, { color: MUTED, width: 1.25 });
  Dot(s, qx0, qy, 0.26, { fill: RUST });
  Dot(s, qx0 + 8 * step, qy, 0.26, { fill: RUST });
  for (let k = 1; k <= 7; k++) L(s, qx0 + k * step, qy - 0.25, qx0 + k * step, qy + 0.12, { color: BLUE, width: 2.5 });
  T(s, "Neighbouring minutes share one gap. A random split would leak them into the test.", { x: 0.6, y: 5.55, w: 5.8, h: 0.85, fontSize: 17 });
  T(s, "Compared against", { x: 6.9, y: 4.3, w: 5.8, h: 0.4, fontSize: 20, bold: true });
  T(s, [
    { text: "Usual wait at that stage (historical median)", options: { bullet: true, breakLine: true } },
    { text: "Previous gap − time waited (last-headway rule)", options: { bullet: true } },
  ], { x: 6.9, y: 4.8, w: 5.8, h: 0.8, fontSize: 17, paraSpaceAfter: 4 });
  T(s, [{ text: "Measured: ", options: { bold: true } }, { text: "average error of the median (MAE), share of waits inside the range (coverage), range width" }], { x: 6.9, y: 5.75, w: 5.8, h: 0.85, fontSize: 17 });
}

// ===== 9. Results =====
{
  const R2h = 1 - rows.reduce((s2, r, i) => s2 + (y[i] - num(r, "historical_median_minutes")) ** 2, 0) / sst;
  const s = section("Results", "The forest had the lowest error, and its range held 80% of actual waits.");
  T(s, [{ text: "Mean Absolute Error (MAE)", options: { bold: true, breakLine: true } }, { text: "average gap between predicted and actual wait, in minutes; lower is better", options: { color: MUTED } }], { x: 0.6, y: 1.8, w: 6.9, h: 0.7, fontSize: 16 });
  s.addChart(pres.charts.BAR, [{ name: "MAE (minutes)", labels: ["Quantile forest", "Last-headway rule", "Historical median"], values: [M.qrf, M.lh, M.hist] }], {
    x: 0.45, y: 2.5, w: 6.9, h: 2.75, barDir: "bar", chartColors: [HEX.blue, HEX.grey, HEX.grey], barGapWidthPct: 50,
    valAxisMinVal: 0, valAxisMaxVal: 3, valAxisMajorUnit: 0.5, valAxisLabelFormatCode: "0.0",
    showValue: true, dataLabelFormatCode: '0.000" min"', dataLabelPosition: "outEnd", dataLabelFontSize: 16, dataLabelColor: HEX.ink, dataLabelFontBold: true,
    catAxisLabelFontSize: 16, catAxisLabelColor: HEX.ink, valAxisLabelFontSize: 12, valAxisLabelColor: HEX.muted,
    catAxisLabelFontFace: "+mn-lt", valAxisLabelFontFace: "+mn-lt", dataLabelFontFace: "+mn-lt",
    valGridLine: { color: HEX.grid, size: 0.75 }, catGridLine: { style: "none" }, catAxisLineShow: false, valAxisLineShow: false, showLegend: false,
  });
  T(s, [
    { text: `Forest: ${M.qrf.toFixed(3)} min (≈ ${Math.round(M.qrf * 60)} s), ${(gain * 60).toFixed(1)} s (${gainPct.toFixed(1)}%) better than the last-headway rule.`, options: { bold: true, color: BLUE, breakLine: true } },
    { text: `R² (share of variation explained): forest ${R2.qrf.toFixed(2)}, last-headway rule ${R2.lh.toFixed(2)}, historical median ${R2h.toFixed(2)}.` },
  ], { x: 0.6, y: 5.35, w: 6.9, h: 0.95, fontSize: 15, paraSpaceAfter: 4 });
  T(s, "No accuracy % is given: the model predicts minutes, so error is measured in minutes. Test set: 1,259 queries, 16–18 Oct 2026, simulated arrivals.", { x: 0.6, y: 6.35, w: 6.9, h: 0.6, fontSize: 13, italic: true, color: MUTED });

  // coverage by stage
  T(s, [{ text: "Coverage", options: { bold: true, breakLine: true } }, { text: "share of actual waits inside the 10th–90th range; target 80%", options: { color: MUTED } }], { x: 8.0, y: 1.8, w: 4.7, h: 0.7, fontSize: 16 });
  const qx0 = 9.55, qx1 = 12.55, lo = 70, hi = 90;
  const QX = (v) => qx0 + ((v - lo) / (hi - lo)) * (qx1 - qx0);
  const st = [["Kencom", stage.kencom.empirical_coverage], ["Odeon", stage.odeon.empirical_coverage], ["Railways", stage.railways.empirical_coverage], ["All stages", M.cov]];
  const rowTop = 2.7, rowH = 0.55;
  L(s, QX(80), rowTop - 0.1, QX(80), rowTop + st.length * rowH, { color: INK, width: 1.5, dash: "dash" });
  T(s, "80%", { x: QX(80) - 0.4, y: rowTop + st.length * rowH + 0.04, w: 0.8, h: 0.28, fontSize: 13, align: "center" });
  [70, 90].forEach((v) => T(s, `${v}%`, { x: QX(v) - 0.35, y: rowTop + st.length * rowH + 0.04, w: 0.7, h: 0.28, fontSize: 13, color: MUTED, align: "center" }));
  st.forEach(([name, v], i) => {
    const yy = rowTop + i * rowH + rowH / 2;
    if (i === 3) L(s, 8.0, rowTop + 3 * rowH, 12.7, rowTop + 3 * rowH, { color: HEX.grid, width: 0.75 });
    L(s, qx0, yy, qx1, yy, { color: HEX.grid, width: 0.75 });
    T(s, name, { x: 8.0, y: yy - 0.17, w: 1.4, h: 0.34, fontSize: 16, bold: i === 3 });
    Dot(s, QX(100 * v), yy, 0.22, { fill: BLUE, line: BLUE });
    const right = 100 * v > 84;
    T(s, (100 * v).toFixed(1) + "%", { x: right ? QX(100 * v) + 0.15 : QX(100 * v) - 0.85, y: yy - 0.15, w: 0.7, h: 0.3, fontSize: 14, bold: true, color: BLUE, align: right ? "left" : "right", valign: "middle" });
  });
  T(s, [{ text: "Range width", options: { bold: true, breakLine: true } }, { text: "average size of the range shown to the rider", options: { color: MUTED } }], { x: 8.0, y: 5.35, w: 3.0, h: 0.7, fontSize: 16 });
  T(s, `${M.width.toFixed(1)} min`, { x: 11.0, y: 5.35, w: 1.7, h: 0.6, fontSize: 30, bold: true, color: BLUE, align: "right" });
}

// ===== 10. Conclusion =====
{
  const s = section("Conclusion", "Recorded arrivals in, a wait estimate with a range out.");
  const flow = [["1,049", "recorded arrivals"], ["5,943", "labelled examples"], ["100", "trees in the forest"], ["median + range", "for each query"]];
  const fw = 2.75, fg = 0.37;
  flow.forEach(([n, lab], i) => {
    const x = 0.6 + i * (fw + fg);
    R(s, x, 1.9, fw, 1.2, { fill: i === 3 ? HEX.tint : WHITE, line: i === 3 ? HEX.blue : HEX.grid, lw: 1.25, round: 0.08 });
    T(s, n, { x: x + 0.2, y: 2.0, w: fw - 0.4, h: 0.55, fontSize: i === 3 ? 24 : 30, bold: true, color: i === 3 ? BLUE : INK, valign: "middle" });
    T(s, lab, { x: x + 0.2, y: 2.58, w: fw - 0.4, h: 0.4, fontSize: 16, color: MUTED });
    if (i < 3) L(s, x + fw + 0.04, 2.5, x + fw + fg - 0.04, 2.5, { color: INK, width: 1.5, arrow: true });
  });
  const pts = [
    [BLUE, "Data: ", "each example pairs what is known at the moment a rider asks (time waited, previous gap, stage, time of day) with the wait that actually followed."],
    [BLUE, "Model: ", "a quantile regression forest learns from those examples and returns a median wait and a 10th–90th percentile range, not just one number."],
    [BLUE, "In use: ", "the latest arrivals at a stage become the inputs; if the data is stale or too thin, the app shows no estimate instead of guessing."],
    [BLUE, "Result: ", `average error ${M.qrf.toFixed(3)} min, lower than both simple rules, and ${(100 * M.cov).toFixed(0)}% of actual waits fell inside the range.`],
    [RUST, "Limit: ", "the arrival times were simulated, so accuracy at real stages still has to be measured."],
  ];
  pts.forEach(([col, h, t], i) => {
    const yy = 3.45 + i * 0.68;
    Dot(s, 0.72, yy + 0.17, 0.2, { fill: col, line: col });
    T(s, [{ text: h, options: { bold: true, color: col } }, { text: t }], { x: 1.0, y: yy, w: 11.7, h: 0.62, fontSize: 18 });
  });
}

(async () => {
  await pres.writeFile({ fileName: DECK });
  if (APPLY_THEME) {
    const { applyTheme } = require(APPLY_THEME);
    await applyTheme(DECK, THEME);
  }
  const md = ["# NganyaFlow: How long until the next matatu?", "", "Speaker notes · Estimating waiting time with a Quantile Regression Forest", "",
    "10 slides, about 10–12 minutes if you say only the SAY cues. IF ASKED lines are for questions.", ""];
  NOTES.forEach((n, i) => md.push(`## Slide ${i + 1}. ${n.title}`, "", n.notes, ""));
  fs.writeFileSync(path.join(OUT, "speaker-notes.md"), md.join("\n"), "utf8");
  console.log(JSON.stringify({ slides: NOTES.length, gainSec: gain * 60, gainPct, seqInside, W, R2 }));
})();
