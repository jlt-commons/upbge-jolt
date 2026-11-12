// The brush sheet from oil-paint's src/oilpaint/brush.js, written as a PNG
// Blender can load. 4 columns x 2 rows of 128x256 marks. R: how thick the
// paint stands, G: where there is paint at all, B: the bristle streak.
import { deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';

const BRUSH_COLS = 4;
const BRUSH_ROWS = 2;
const CW = 128, CH = 256;

const hash = (n) => {
  const s = Math.sin(n * 127.1 + 311.7) * 43758.5453;
  return s - Math.floor(s);
};
const smooth = (t) => t * t * (3 - 2 * t);
const n1 = (x, seed) => {
  const i = Math.floor(x), f = smooth(x - i);
  return hash(i + seed * 57.3) * (1 - f) + hash(i + 1 + seed * 57.3) * f;
};
const n2 = (x, y, seed) => {
  const i = Math.floor(x), j = Math.floor(y), fx = smooth(x - i), fy = smooth(y - j);
  const h = (a, b) => hash(a * 1.31 + b * 91.7 + seed * 13.9);
  return (h(i, j) * (1 - fx) + h(i + 1, j) * fx) * (1 - fy) + (h(i, j + 1) * (1 - fx) + h(i + 1, j + 1) * fx) * fy;
};
const sstep = (a, b, x) => smooth(Math.min(1, Math.max(0, (x - a) / (b - a))));

function mark(u, v, seed, kind) {
  const bend = kind === 2 ? (seed % 2 ? 0.34 : -0.34) : (hash(seed * 3.1) - 0.5) * 0.12;
  const ub = u - bend * (4 * (v - 0.5) * (v - 0.5) - 0.5);
  const wob = (n1(v * 5, seed + 3) - 0.5) * 0.1;
  const a = Math.abs(ub + wob);
  let half, start, end;
  if (kind === 1) {
    const r = Math.hypot(ub / 0.66, (v - 0.5) / 0.44);
    const edge = 1 + (n1(Math.atan2(v - 0.5, ub) * 2.2 + 9, seed) - 0.5) * 0.34;
    half = 0.66; start = 0; end = 1;
    const inside = 1 - sstep(edge - 0.16, edge, r);
    const bristle = n1(ub * 11, seed + 11);
    const dry = sstep(0.55, 1.0, r) * 0.9;
    const alpha = inside * (bristle > dry * 0.8 ? 1 : 0.25);
    const ridge = n1(ub * 13 + n1(v * 3, seed) * 1.5, seed + 31);
    const height = alpha * (0.4 + 0.22 * ridge * n1(ub * 9, seed + 5) + 0.3 * sstep(0.45, 0.95, r) + 0.12 * n2(ub * 4, v * 6, seed));
    return [height, alpha, n2(ub * 3.2, v * 1.3, seed + 20)];
  }
  half = 0.6 * (0.8 + 0.2 * Math.sin(Math.PI * Math.min(1, v * 1.15))) * (1 + (n1(v * 7, seed + 1) - 0.5) * 0.1);
  start = 0.05 + 0.05 * n1(ub * 5, seed + 2);
  end = 0.95 - 0.3 * n1(ub * 4.5 + 2, seed + 4) - 0.06 * n1(ub * 19, seed + 6);
  const side = 1 - sstep(half - 0.07, half, a);
  const head = sstep(start, start + 0.035, v);
  const tail = 1 - sstep(end - 0.02, end + 0.015, v);
  const dryness = sstep(end - 0.2, end, v);
  const bristle = n1(ub * 13, seed + 8) * 0.7 + n1(ub * 31, seed + 9) * 0.3;
  const gap = 1 - sstep(dryness * 0.55 - 0.08, dryness * 0.55, bristle) < 0.5 ? 1.0 : 0.0;
  const alpha = side * head * tail * gap;
  const furrow = n1(ub * 12 + n1(v * 2.5, seed + 12) * 1.2, seed + 33) * 0.7 + n1(ub * 29, seed + 34) * 0.3;
  const load = 1 - 0.55 * sstep(0.1, 0.9, v);
  const squeezed = sstep(half - 0.3, half - 0.06, a);
  const height = alpha * (0.3 + 0.22 * furrow * (0.5 + 0.5 * n1(ub * 11, seed + 13)) + 0.3 * squeezed + 0.25 * load * (1 - sstep(start, start + 0.25, v)) + 0.1 * n2(ub * 3, v * 7, seed + 14));
  return [Math.min(1, height), alpha, n2(ub * 3.4, v * 1.1, seed + 20)];
}

const W = CW * BRUSH_COLS, H = CH * BRUSH_ROWS;
const data = new Uint8Array(W * H * 4);
const kinds = [0, 0, 0, 2, 1, 1, 0, 2];
for (let cell = 0; cell < 8; cell++) {
  const cx = cell % BRUSH_COLS, cy = Math.floor(cell / BRUSH_COLS);
  for (let y = 0; y < CH; y++) {
    for (let x = 0; x < CW; x++) {
      const u = ((x + 0.5) / CW) * 2 - 1, v = (y + 0.5) / CH;
      const m = 0.9;
      const [h, a, s] = Math.abs(u) > m || v < 0.04 || v > 0.96 ? [0, 0, 0.5] : mark(u / m, (v - 0.04) / 0.92, cell * 7 + 1, kinds[cell]);
      const i = ((cx * CW + x) + (cy * CH + y) * W) * 4;
      data[i] = Math.round(h * 255);
      data[i + 1] = Math.round(a * 255);
      data[i + 2] = Math.round(s * 255);
      data[i + 3] = 255;
    }
  }
}

// --- a minimal PNG writer ---------------------------------------------------
const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  crcTable[n] = c >>> 0;
}
const crc32 = (buf) => {
  let c = 0xffffffff;
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
const chunk = (type, payload) => {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(payload.length, 0);
  head.write(type, 4, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), payload])), 0);
  return Buffer.concat([head, payload, crc]);
};
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(W, 0);
ihdr.writeUInt32BE(H, 4);
ihdr[8] = 8;  // bit depth
ihdr[9] = 6;  // RGBA
const raw = Buffer.alloc(H * (W * 4 + 1));
for (let y = 0; y < H; y++) {
  const row = y * (W * 4 + 1);
  raw[row] = 0;
  for (let x = 0; x < W * 4; x++) raw[row + 1 + x] = data[y * W * 4 + x];
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
]);
writeFileSync(new URL('../brush.png', import.meta.url), png);
console.log(`wrote brush.png ${W}x${H}`);
