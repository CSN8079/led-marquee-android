/* 产物自检：列出 APK 条目、校验 classes.dex 中的关键类、打印 SHA-256 */
const fs = require("node:fs");
const crypto = require("node:crypto");
const path = require("node:path");
const { readZip } = require("./ziplib.js");

const file = process.argv[2] || path.join(__dirname, "..", "dist", "LED滚动字幕-v1.0.apk");
const buf = fs.readFileSync(file);
const entries = readZip(buf);
console.log("APK   :", path.basename(file));
console.log("字节数:", buf.length);
console.log("SHA256:", crypto.createHash("sha256").update(buf).digest("hex"));
console.log("条目数:", entries.length);
for (const e of entries) {
  console.log("  " + (e.store ? "STORE" : "defl ") + String(e.data.length).padStart(9) + "  " + e.name);
}
const dex = entries.find((e) => e.name === "classes.dex");
if (!dex) {
  console.log("!! classes.dex 缺失");
  process.exit(1);
}
const text = dex.data.toString("latin1");
for (const name of ["MainActivity", "DisplayActivity", "LedMarqueeView", "MarqueeConfig", "LedPalette", "Prefs"]) {
  console.log("dex 包含 " + name + ": " + text.includes("com/ledmatrix/marquee/" + name));
}
const arsc = entries.find((e) => e.name === "resources.arsc");
console.log("resources.arsc 未压缩:", arsc ? arsc.store : "缺失");