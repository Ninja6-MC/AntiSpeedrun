#!/usr/bin/env node
// Regenerates every AntiSpeedrun icon variant from docs/assets/icon-master.svg.
//
//   node scripts/export-icons.mjs
//
// The master holds geometry only. This script is where colour and plates live, so
// the two never duplicate: one geometry, one variant table, N outputs. See
// ICON_PLAN.md in the brand repository.
//
// Rasterizer is @resvg/resvg-js, pinned below.
//
// THREE INVARIANTS ARE ENFORCED (fails the run rather than warning):
//   1. Safe zone. Artwork must fit inside radius 460 of centre on the 1024 canvas.
//   2. Palette. A themed variant must contain its own colours and none of the other theme's.
//   3. Master preview reconciliation. Master's preview must match the canonical palette.

import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const RESVG = '@resvg/resvg-js@2.6.2';
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ASSETS = join(ROOT, 'docs', 'assets');
const TOOLS = join(ROOT, 'build', 'icon-tools');

const CANVAS = 1024;
const CENTRE = CANVAS / 2;
const SAFE_RADIUS = 460;
const MITER_LIMIT = 4; // Standard SVG stroke-miterlimit default

// ---------------------------------------------------------------- palette

const PLATE = '#12161A';
const VIOLET = '#A855F7';      // structural stroke, dark contexts
const VIOLET_DARK = '#6B21A8'; // structural stroke, light contexts
const AMBER = '#FFAC1C';       // family accent, dark contexts
const AMBER_DARK = '#B8730A';  // family accent, light contexts
const SILVER = '#E8EDF2';

const THEME_INK = { dark: [VIOLET, AMBER], light: [VIOLET_DARK, AMBER_DARK] };

const VARIANTS = [
  // v1 - primary, opaque. Platform avatars: Modrinth, Hangar, GitHub, CurseForge.
  { file: 'icon', geo: 'geo', plate: PLATE, stroke: VIOLET, dot: AMBER, theme: 'dark',
    sizes: [64, 128, 180, 256, 400, 512, 1024] },

  // v1r - rounded plate. Apple touch icon, docs headers.
  { file: 'icon-rounded', geo: 'geo', plate: PLATE, rx: 225, stroke: VIOLET, dot: AMBER,
    theme: 'dark', sizes: [180, 256, 512, 1024] },

  // v2/v3 - transparent, one per theme.
  { file: 'icon-transparent-dark', geo: 'geo', stroke: VIOLET, dot: AMBER,
    theme: 'dark', sizes: [64, 128, 256, 512] },
  { file: 'icon-transparent-light', geo: 'geo', stroke: VIOLET_DARK, dot: AMBER_DARK,
    theme: 'light', sizes: [64, 128, 256, 512] },

  // v4 - simplified small cut, <=24px (stroke 118, dot dropped).
  { file: 'icon-small-dark', geo: 'geo-small', stroke: VIOLET, theme: 'dark',
    sizes: [16, 24, 32, 48, 64] },
  { file: 'icon-small-light', geo: 'geo-small', stroke: VIOLET_DARK, theme: 'light',
    sizes: [16, 24, 32, 48, 64] },
  { file: 'icon-small-plate', geo: 'geo-small', plate: PLATE, stroke: VIOLET,
    theme: 'dark', sizes: [64, 128] },

  // v5 - monochrome. Single colour, no raster ladder.
  { file: 'icon-mono-dark', geo: 'geo-small', stroke: SILVER, theme: 'dark', sizes: [] },
  { file: 'icon-mono-light', geo: 'geo-small', stroke: PLATE, theme: 'light', sizes: [] },
];

// ------------------------------------------------------------- svg parsing

function parseElements(xml) {
  const els = [];
  let i = 0;
  while (i < xml.length) {
    const lt = xml.indexOf('<', i);
    if (lt < 0) break;
    if (xml.startsWith('<!--', lt)) {
      const end = xml.indexOf('-->', lt);
      i = end < 0 ? xml.length : end + 3;
      continue;
    }
    if (xml.startsWith('<?', lt) || xml.startsWith('<!', lt)) {
      const end = xml.indexOf('>', lt);
      i = end < 0 ? xml.length : end + 1;
      continue;
    }
    let j = lt + 1;
    let quote = null;
    while (j < xml.length) {
      const c = xml[j];
      if (quote) { if (c === quote) quote = null; }
      else if (c === '"' || c === "'") quote = c;
      else if (c === '>') break;
      j++;
    }
    const raw = xml.slice(lt + 1, j);
    i = j + 1;
    if (raw.startsWith('/')) { els.push({ tag: raw.slice(1).trim(), close: true, attrs: {} }); continue; }
    const name = raw.match(/^([a-zA-Z][\w:-]*)/);
    if (!name) continue;
    const attrs = {};
    const attrRe = /([a-zA-Z_:][\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')/g;
    let a;
    while ((a = attrRe.exec(raw))) attrs[a[1]] = a[3] !== undefined ? a[3] : a[4];
    els.push({ tag: name[1], attrs, selfClose: /\/\s*$/.test(raw) });
  }
  return els;
}

const master = readFileSync(join(ASSETS, 'icon-master.svg'), 'utf8');
const masterEls = parseElements(master);

{
  const svg = masterEls.find((e) => e.tag === 'svg');
  const want = `0 0 ${CANVAS} ${CANVAS}`;
  if (!svg || svg.attrs.viewBox !== want) {
    throw new Error(`icon-master.svg viewBox must be "${want}", found "${svg?.attrs.viewBox}"`);
  }

  // Reconcile master preview with palette constants, scoped strictly to elements outside <defs>
  let inDefs = false;
  let previewRect = null;
  let previewG = null;
  for (const e of masterEls) {
    if (e.tag === 'defs' && !e.close) inDefs = true;
    else if (e.tag === 'defs' && e.close) inDefs = false;
    else if (!inDefs && !e.close) {
      if (e.tag === 'rect' && !previewRect) previewRect = e;
      if (e.tag === 'g' && e.attrs.stroke && !previewG) previewG = e;
    }
  }

  if (!previewRect || previewRect.attrs.fill !== PLATE) {
    throw new Error(`icon-master.svg preview <rect> must have fill="${PLATE}", found "${previewRect?.attrs.fill}"`);
  }
  if (!previewG || previewG.attrs.stroke !== VIOLET || previewG.attrs.fill !== AMBER) {
    throw new Error(`icon-master.svg preview <g> must have stroke="${VIOLET}" and fill="${AMBER}", found stroke="${previewG?.attrs.stroke}" fill="${previewG?.attrs.fill}"`);
  }
}

function num(v, desc) {
  const n = Number(v);
  if (!Number.isFinite(n)) throw new Error(`${desc}: non-numeric value ${JSON.stringify(v)}`);
  return n;
}

function geometryOf(id) {
  let inDefs = false;
  let inTarget = false;
  let depth = 0;
  const els = [];
  for (const e of masterEls) {
    if (e.tag === 'defs' && !e.close) inDefs = true;
    else if (e.tag === 'defs' && e.close) inDefs = false;
    else if (inDefs && e.tag === 'g' && e.attrs.id === id && !e.close) {
      inTarget = true;
      depth = 1;
    } else if (inTarget) {
      if (e.tag === 'g' && !e.close && !e.selfClose) {
        depth++;
      } else if (e.tag === 'g' && e.close) {
        depth--;
        if (depth === 0) {
          inTarget = false;
          break;
        }
      }
      // Collect leaf geometry elements only, flattening <g> container wrappers
      if (!e.close && e.tag !== 'g') {
        if (e.attrs.transform) {
          throw new Error(`<${e.tag}> inside #${id} has transform="${e.attrs.transform}". `
            + 'The master must bake transforms into its coordinates, not declare them.');
        }
        els.push(e);
      }
    }
  }
  if (!els.length) throw new Error(`found no geometry inside <g id="${id}"> in <defs>`);
  return els;
}

// ---------------------------------------------------- safe-zone assertion

function worstRadius(elements) {
  const cand = [];
  for (const el of elements) {
    if (el.tag === 'polyline') {
      const sw = num(el.attrs['stroke-width'], 'polyline stroke-width');
      const hw = sw / 2;
      const pts = String(el.attrs.points ?? '').trim().split(/\s+/).filter(Boolean)
        .map((p) => {
          if (!/^-?[\d.]+,-?[\d.]+$/.test(p)) {
            throw new Error(`polyline point ${JSON.stringify(p)} is not "x,y".`);
          }
          return p.split(',').map(Number);
        });
      if (pts.length < 2) throw new Error('polyline needs at least two points');

      // Square caps at both ends
      for (const [i, j] of [[0, 1], [pts.length - 1, pts.length - 2]]) {
        const [x, y] = pts[i];
        const [xn, yn] = pts[j];
        const len = Math.hypot(x - xn, y - yn);
        if (len === 0) throw new Error('polyline has a zero-length end segment');
        const ux = (x - xn) / len;
        const uy = (y - yn) / len;
        const ex = x + ux * hw;
        const ey = y + uy * hw;
        cand.push([ex - uy * hw, ey + ux * hw], [ex + uy * hw, ey - ux * hw]);
      }

      // Miter joins & vertices with SVG stroke-miterlimit clamping
      for (let k = 1; k < pts.length - 1; k++) {
        const [xp, yp] = pts[k - 1];
        const [x, y] = pts[k];
        const [xn, yn] = pts[k + 1];

        const len1 = Math.hypot(x - xp, y - yp);
        const len2 = Math.hypot(xn - x, yn - y);
        const u1x = (x - xp) / len1;
        const u1y = (y - yp) / len1;
        const u2x = (xn - x) / len2;
        const u2y = (yn - y) / len2;

        const n1x = -u1y;
        const n1y = u1x;
        const n2x = -u2y;
        const n2y = u2x;

        // Miter vector
        const mx = n1x + n2x;
        const my = n1y + n2y;
        const mlenSq = mx * mx + my * my;

        // mlenSq = 4 * sin^2(theta/2)
        // miter ratio = 2 / sqrt(mlenSq)
        const miterRatio = 2 / Math.sqrt(Math.max(mlenSq, 1e-8));

        if (miterRatio <= MITER_LIMIT) {
          // Within miter limit: true miter tip
          const factor = (2 * hw) / mlenSq;
          cand.push([x + mx * factor, y + my * factor]);
          cand.push([x - mx * factor, y - my * factor]);
        } else {
          // Exceeds miterlimit: SVG bevels the join at stroke boundaries
          for (const n of [[n1x, n1y], [n2x, n2y]]) {
            cand.push([x + n[0] * hw, y + n[1] * hw]);
            cand.push([x - n[0] * hw, y - n[1] * hw]);
          }
        }
      }
    } else if (el.tag === 'circle') {
      const cx = num(el.attrs.cx, 'circle cx');
      const cy = num(el.attrs.cy, 'circle cy');
      const r = num(el.attrs.r, 'circle r');
      cand.push([cx + r, cy], [cx - r, cy], [cx, cy + r], [cx, cy - r],
        [cx + r * 0.7071, cy + r * 0.7071], [cx - r * 0.7071, cy - r * 0.7071],
        [cx + r * 0.7071, cy - r * 0.7071], [cx - r * 0.7071, cy + r * 0.7071]);
    } else {
      throw new Error(`worstRadius cannot measure <${el.tag}>; unsupported element.`);
    }
  }

  let worst = 0;
  let at = null;
  for (const [x, y] of cand) {
    const r = Math.hypot(x - CENTRE, y - CENTRE);
    if (!Number.isFinite(r)) throw new Error('non-finite candidate point');
    if (r > worst) { worst = r; at = [x, y]; }
  }
  if (!at) throw new Error('no geometry to measure');
  return { worst, at };
}

// ---------------------------------------------------------------- compose

function compose(v) {
  const els = geometryOf(v.geo);

  const body = els.map((e) => {
    const attrs = Object.entries(e.attrs).map(([k, val]) => `${k}="${val}"`).join(' ');
    return `    <${e.tag} ${attrs} />`;
  }).join('\n');

  const paint = [`stroke="${v.stroke}"`, v.dot ? `fill="${v.dot}"` : null]
    .filter(Boolean).join(' ');
  const rx = v.rx ? ` rx="${v.rx}"` : '';
  const plate = v.plate
    ? `\n  <rect width="${CANVAS}" height="${CANVAS}"${rx} fill="${v.plate}" />`
    : '';

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${CANVAS} ${CANVAS}" width="${CANVAS}" height="${CANVAS}">
  <!-- GENERATED by scripts/export-icons.mjs from icon-master.svg. Do not edit.
       Colour and plate are applied here; the master carries geometry only. -->${plate}
  <g ${paint}>
${body}
  </g>
</svg>
`;
}

// --------------------------------------------------- sync-assets generator

function generateSyncAssetsEntry() {
  const pngVariants = VARIANTS.filter((v) => v.sizes.length > 0).map((v) => ({
    name: v.file,
    format: 'png',
    sizes: v.sizes,
    path: `docs/assets/${v.file}-{size}.png`
  }));

  const entry = {
    repo: 'AntiSpeedrun',
    master: 'docs/assets/icon-master.svg',
    script: 'scripts/export-icons.mjs',
    palette: {
      plate: PLATE,
      structural: {
        dark: VIOLET,
        light: VIOLET_DARK
      },
      accent: {
        dark: AMBER,
        light: AMBER_DARK
      },
      monochrome: {
        dark: SILVER,
        light: PLATE
      }
    },
    variants: pngVariants
  };

  return JSON.stringify(entry, null, 2) + '\n';
}

// ---------------------------------------------------------------- rasterize

async function loadResvg() {
  try {
    const mod = await import('@resvg/resvg-js');
    if (mod.Resvg) return mod;
  } catch {}

  const entry = join(TOOLS, 'node_modules', '@resvg', 'resvg-js', 'index.js');
  const attempt = () => import(pathToFileURL(entry).href);
  if (existsSync(entry)) {
    try { return await attempt(); } catch {
      console.log('  cached resvg is incomplete, reinstalling ...');
      rmSync(TOOLS, { recursive: true, force: true });
    }
  }
  console.log(`  fetching ${RESVG} into build/icon-tools ...`);
  mkdirSync(TOOLS, { recursive: true });
  writeFileSync(join(TOOLS, 'package.json'), '{"private":true}\n');
  execSync(`npm install --no-save --ignore-scripts --prefix "${TOOLS}" ${RESVG}`,
    { stdio: 'inherit' });
  return attempt();
}

const { Resvg } = await loadResvg();

function hexAt(px, i) {
  return '#' + [px[i], px[i + 1], px[i + 2]]
    .map((c) => c.toString(16).padStart(2, '0')).join('').toUpperCase();
}

// ---------------------------------------------------------------- run

const problems = [];

console.log(`safe zone (limit ${SAFE_RADIUS}):`);
for (const id of [...new Set(VARIANTS.map((v) => v.geo))]) {
  const { worst, at } = worstRadius(geometryOf(id));
  const ok = worst <= SAFE_RADIUS;
  if (!ok) problems.push(`${id} breaches the safe zone at r ${worst.toFixed(1)}`);
  console.log(`  ${id.padEnd(10)}  r = ${worst.toFixed(1)}`
    + ` at (${at.map((n) => n.toFixed(0)).join(',')})  ${ok ? 'ok' : 'BREACH'}`);
}
if (problems.length) {
  console.error('\nrefusing to export: artwork would be clipped by a circular crop.');
  for (const p of problems) console.error(`  ${p}`);
  process.exit(1);
}

console.log('\nvariants:');
const pending = [];

for (const v of VARIANTS) {
  const svg = compose(v);
  const label = [];

  const forbidden = THEME_INK[v.theme === 'light' ? 'dark' : 'light'];
  for (const bad of forbidden) {
    if (svg.includes(bad)) {
      problems.push(`${v.file}: ${v.theme === 'light' ? 'dark' : 'light'}-theme `
        + `${bad} present in a ${v.theme} variant`);
    }
  }
  pending.push([join(ASSETS, `${v.file}.svg`), Buffer.from(svg, 'utf8')]);

  for (const size of v.sizes) {
    const img = new Resvg(svg, {
      fitTo: { mode: 'width', value: size },
      font: { loadSystemFonts: false }
    }).render();

    if (size >= 64) {
      const px = img.pixels;
      let seen = false;
      for (let i = 0; i < px.length && !seen; i += 4) {
        if (px[i + 3] >= 250 && hexAt(px, i) === v.stroke.toUpperCase()) seen = true;
      }
      if (!seen) problems.push(`${v.file}@${size}: stroke ${v.stroke} did not render`);
    }

    pending.push([join(ASSETS, `${v.file}-${size}.png`), img.asPng()]);
    label.push(String(size));
  }
  console.log(`  ${v.file.padEnd(24)}  ${label.join(' ') || '(svg only)'}`);
}

// Generate sync-assets-entry.json from VARIANTS and palette constants
pending.push([join(ASSETS, 'sync-assets-entry.json'), Buffer.from(generateSyncAssetsEntry(), 'utf8')]);

if (problems.length) {
  console.error(`\n${problems.length} problem(s); nothing was written:`);
  for (const p of problems) console.error(`  ${p}`);
  process.exit(1);
}

for (const [path, buf] of pending) writeFileSync(path, buf);

console.log(`\ndone. ${pending.length} files written.`);
