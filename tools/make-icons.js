/* ============================================================
   make-icons.js - 生成 Android 启动图标（纯 Node，无第三方库）
   输出到 app/src/main/res/：
     mipmap-<密度>/ic_launcher.png            传统方角图标
     mipmap-<密度>/ic_launcher_round.png      圆形图标
     mipmap-<密度>/ic_launcher_foreground.png 自适应图标前景（透明）
     mipmap-anydpi-v26/ic_launcher*.xml       自适应图标描述
   用法: node tools/make-icons.js
   ============================================================ */
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'app', 'src', 'main', 'res');

// ---------------------------------------------------------------- PNG 编码
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

function encodePNG(width, height, rgba) {
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // RGBA
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0))]);
}

// ---------------------------------------------------------------- 绘图工具
const clamp = (v, a, b) => (v < a ? a : (v > b ? b : v));
const mix = (a, b, t) => a + (b - a) * t;

function sdRoundRect(px, py, cx, cy, hw, hh, r) {
  const qx = Math.abs(px - cx) - (hw - r);
  const qy = Math.abs(py - cy) - (hh - r);
  const ax = Math.max(qx, 0);
  const ay = Math.max(qy, 0);
  return Math.sqrt(ax * ax + ay * ay) + Math.min(Math.max(qx, qy), 0) - r;
}

/** 预乘 alpha 的浮点画布 */
class Canvas2D {
  constructor(w, h) {
    this.w = w;
    this.h = h;
    this.d = new Float32Array(w * h * 4);
  }

  blend(x, y, r, g, b, a) {
    if (a <= 0 || x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const i = (y * this.w + x) * 4;
    const d = this.d;
    const ia = 1 - a;
    d[i] = (r / 255) * a + d[i] * ia;
    d[i + 1] = (g / 255) * a + d[i + 1] * ia;
    d[i + 2] = (b / 255) * a + d[i + 2] * ia;
    d[i + 3] = a + d[i + 3] * ia;
  }

  add(x, y, r, g, b, strength) {
    if (strength <= 0 || x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const i = (y * this.w + x) * 4;
    const d = this.d;
    d[i] += (r / 255) * strength;
    d[i + 1] += (g / 255) * strength;
    d[i + 2] += (b / 255) * strength;
  }

  fillCircle(cx, cy, radius, r, g, b) {
    const x0 = Math.max(0, Math.floor(cx - radius - 1));
    const x1 = Math.min(this.w - 1, Math.ceil(cx + radius + 1));
    const y0 = Math.max(0, Math.floor(cy - radius - 1));
    const y1 = Math.min(this.h - 1, Math.ceil(cy + radius + 1));
    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        const dist = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
        const cov = clamp(0.5 - (dist - radius), 0, 1);
        if (cov > 0) this.blend(x, y, r, g, b, cov);
      }
    }
  }

  glow(cx, cy, sigma, r, g, b, strength) {
    const reach = sigma * 2.6;
    const x0 = Math.max(0, Math.floor(cx - reach));
    const x1 = Math.min(this.w - 1, Math.ceil(cx + reach));
    const y0 = Math.max(0, Math.floor(cy - reach));
    const y1 = Math.min(this.h - 1, Math.ceil(cy + reach));
    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        const dx = x + 0.5 - cx;
        const dy = y + 0.5 - cy;
        const d2 = dx * dx + dy * dy;
        const k = Math.exp(-d2 / (2 * sigma * sigma)) * strength;
        if (k > 0.002) this.add(x, y, r, g, b, k);
      }
    }
  }

  toRGBA() {
    const out = Buffer.alloc(this.w * this.h * 4);
    for (let i = 0; i < this.d.length; i += 4) {
      const a = this.d[i + 3];
      out[i + 3] = Math.round(clamp(a, 0, 1) * 255);
      if (a > 0.001) {
        out[i] = Math.round(clamp(this.d[i] / a, 0, 1) * 255);
        out[i + 1] = Math.round(clamp(this.d[i + 1] / a, 0, 1) * 255);
        out[i + 2] = Math.round(clamp(this.d[i + 2] / a, 0, 1) * 255);
      }
    }
    return out;
  }
}

// ---------------------------------------------------------------- 图标内容
const RED = [255, 52, 24];
const RED_HOT = [255, 128, 96];
const UNLIT = [58, 12, 9];

/** 7x7 灯珠组成的向右三角形（"播放"意象） */
function isLitDot(col, row) {
  const half = 3 - Math.abs(3 - row);
  return col <= 3 + half;
}

/**
 * @param {number} size 画布边长
 * @param {{background:boolean, round:boolean, pitchRatio:number}} opt
 */
function renderIcon(size, opt) {
  const c = new Canvas2D(size, size);
  const cx = size / 2;
  const cy = size / 2;

  if (opt.background) {
    const radius = opt.round ? size / 2 : size * 0.225;
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        const sd = opt.round
          ? Math.hypot(x + 0.5 - cx, y + 0.5 - cy) - (size / 2 - 0.5)
          : sdRoundRect(x + 0.5, y + 0.5, cx, cy, size / 2 - 0.5, size / 2 - 0.5, radius);
        const cov = clamp(0.5 - sd, 0, 1);
        if (cov <= 0) continue;
        const t = y / size;
        c.blend(x, y, Math.round(mix(24, 8, t)), Math.round(mix(27, 9, t)),
          Math.round(mix(34, 12, t)), cov);
      }
    }
    // 顶部浅浅的高光，像面板玻璃
    for (let y = 0; y < Math.round(size * 0.45); y++) {
      const a = (1 - y / (size * 0.45)) * 0.055;
      for (let x = 0; x < size; x++) c.blend(x, y, 255, 255, 255, a * 0.35);
    }
  }

  const pitch = size * (opt.pitchRatio || 0.105);
  const dotR = pitch * 0.34;
  const start = -3 * pitch;

  // 未点亮灯珠
  for (let row = 0; row < 7; row++) {
    for (let col = 0; col < 7; col++) {
      const px = cx + start + col * pitch;
      const py = cy + start + row * pitch;
      if (!isLitDot(col, row)) {
        c.fillCircle(px, py, dotR * 0.92, UNLIT[0], UNLIT[1], UNLIT[2]);
      }
    }
  }

  // 辉光（先画，避免盖住灯珠本体）
  for (let row = 0; row < 7; row++) {
    for (let col = 0; col < 7; col++) {
      if (!isLitDot(col, row)) continue;
      const px = cx + start + col * pitch;
      const py = cy + start + row * pitch;
      c.glow(px, py, dotR * 1.45, RED[0], RED[1], RED[2], 0.22);
    }
  }

  // 点亮的灯珠：外圈亮红 + 中心偏白的热点
  for (let row = 0; row < 7; row++) {
    for (let col = 0; col < 7; col++) {
      if (!isLitDot(col, row)) continue;
      const px = cx + start + col * pitch;
      const py = cy + start + row * pitch;
      c.fillCircle(px, py, dotR, RED[0], RED[1], RED[2]);
      c.fillCircle(px, py, dotR * 0.44, RED_HOT[0], RED_HOT[1], RED_HOT[2]);
    }
  }

  return encodePNG(size, size, c.toRGBA());
}

// ---------------------------------------------------------------- 输出
const DENSITIES = [
  ['mdpi', 1],
  ['hdpi', 1.5],
  ['xhdpi', 2],
  ['xxhdpi', 3],
  ['xxxhdpi', 4]
];

let count = 0;
function write(dir, name, buf) {
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, name), buf);
  count++;
}

DENSITIES.forEach(([density, factor]) => {
  const dir = path.join(RES, 'mipmap-' + density);
  const legacy = Math.round(48 * factor);
  const foreground = Math.round(108 * factor);
  write(dir, 'ic_launcher.png', renderIcon(legacy, { background: true, round: false }));
  write(dir, 'ic_launcher_round.png', renderIcon(legacy, { background: true, round: true }));
  write(dir, 'ic_launcher_foreground.png',
    renderIcon(foreground, { background: false, round: false, pitchRatio: 0.072 }));
});

const adaptive = '<?xml version="1.0" encoding="utf-8"?>\n' +
  '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n' +
  '    <background android:drawable="@color/icon_bg" />\n' +
  '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n' +
  '</adaptive-icon>\n';

const anydpi = path.join(RES, 'mipmap-anydpi-v26');
write(anydpi, 'ic_launcher.xml', Buffer.from(adaptive, 'utf8'));
write(anydpi, 'ic_launcher_round.xml', Buffer.from(adaptive, 'utf8'));

console.log('已生成 ' + count + ' 个图标文件到 ' + RES);