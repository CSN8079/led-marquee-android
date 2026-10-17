/* ============================================================
   build-apk.js - 离线打包 APK（不需要 Gradle，不需要联网）

   流程：
     aapt2 编译资源 -> aapt2 链接出基础 APK -> javac 编译 Java
     -> d8 转 classes.dex -> 合入 APK -> zipalign 对齐
     -> apksigner 签名 -> 校验 -> 复制到 dist/

   依赖（任意一项可用即可，也可用环境变量指定）：
     LED_JDK          JDK 17 目录（含 bin/java.exe）
     LED_ANDROID_SDK  Android SDK 目录（含 platforms / build-tools）

   注意：aapt2 在 Windows 上无法处理含中文的路径参数，
        因此所有中间产物都放在纯英文的临时目录里构建，
        最后再把成品 APK 复制回项目的 dist/ 目录。

   用法： node tools/build-apk.js
   ============================================================ */
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { readZip, writeZip } = require('./ziplib.js');

const ROOT = path.join(__dirname, '..');
const APP = path.join(ROOT, 'app', 'src', 'main');
const DIST = path.join(ROOT, 'dist');

const APP_VERSION = '1.2';
const APK_NAME = 'LED滚动字幕-v' + APP_VERSION + '.apk';
const MIN_SDK = '21';
const TARGET_SDK = '34';
const KS_PASS = 'ledmarquee';
const KS_ALIAS = 'ledmarquee';
const KS_FILE = path.join(ROOT, '.keystore', 'ledmarquee.jks');

function firstExisting(list, what) {
  for (const item of list) {
    if (item && fs.existsSync(item)) return item;
  }
  throw new Error('找不到' + what + '，请设置环境变量或修改本脚本顶部的候选路径');
}

const JDK = firstExisting([
  process.env.LED_JDK,
  path.join(process.env.LOCALAPPDATA || '', 'Programs', 'Android Studio', 'jbr'),
  'C:/Program Files/Android/Android Studio/jbr',
], ' JDK');

const SDK = firstExisting([
  process.env.LED_ANDROID_SDK,
  process.env.ANDROID_HOME,
  process.env.ANDROID_SDK_ROOT,
  path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk')
], ' Android SDK');

const JAVA = path.join(JDK, 'bin', 'java.exe');
const JAVAC = path.join(JDK, 'bin', 'javac.exe');
const KEYTOOL = path.join(JDK, 'bin', 'keytool.exe');

function latestEntry(dir, filter) {
  const list = fs.readdirSync(dir).filter(filter).sort();
  if (list.length === 0) throw new Error(dir + ' 下没有可用版本');
  return list[list.length - 1];
}

const BT_VERSION = latestEntry(path.join(SDK, 'build-tools'), (n) => fs.statSync(path.join(SDK, 'build-tools', n)).isDirectory());
const BT = path.join(SDK, 'build-tools', BT_VERSION);
const PLATFORM = 'android-' + TARGET_SDK;
const ANDROID_JAR = path.join(SDK, 'platforms', PLATFORM, 'android.jar');

const AAPT2 = path.join(BT, 'aapt2.exe');
const ZIPALIGN = path.join(BT, 'zipalign.exe');
const D8_JAR = path.join(BT, 'lib', 'd8.jar');
const APKSIGNER_JAR = path.join(BT, 'lib', 'apksigner.jar');

// 纯英文构建目录（aapt2 无法处理中文路径）
const WORK = (() => {
  const list = [path.join(os.tmpdir(), 'ledmarquee-build'), 'C:\\ledmarquee-build'];
  for (const item of list) {
    if (/^[\x20-\x7e\\/:]+$/.test(item)) return item;
  }
  throw new Error('找不到可用的纯英文构建目录');
})();

let step = 0;
function title(t) {
  console.log('\n[' + (++step) + '] ' + t);
}

function run(exe, args) {
  const r = spawnSync(exe, args, { stdio: 'inherit' });
  if (r.error) throw new Error(path.basename(exe) + ' 启动失败：' + r.error.message);
  if (r.status !== 0) throw new Error(path.basename(exe) + ' 执行失败（退出码 ' + r.status + '）');
  return r;
}

function walk(dir, ext, out) {
  out = out || [];
  if (!fs.existsSync(dir)) return out;
  fs.readdirSync(dir).forEach((n) => {
    const p = path.join(dir, n);
    if (fs.statSync(p).isDirectory()) walk(p, ext, out);
    else if (!ext || p.toLowerCase().endsWith(ext)) out.push(p);
  });
  return out;
}

function need(p, what) {
  if (!fs.existsSync(p)) throw new Error('缺少' + what + '：' + p);
}

function build() {
  console.log('LED 滚动字幕 - APK 打包');
  console.log('  项目目录：' + ROOT);
  console.log('  JDK      ：' + JDK);
  console.log('  SDK      ：' + SDK + ' (build-tools ' + BT_VERSION + ')');
  console.log('  构建目录 ：' + WORK + '（纯英文路径）');

  title('检查工具链');
  need(JAVA, 'java'); need(JAVAC, 'javac'); need(KEYTOOL, 'keytool');
  need(AAPT2, 'aapt2'); need(ZIPALIGN, 'zipalign');
  need(ANDROID_JAR, 'android.jar'); need(D8_JAR, 'd8.jar'); need(APKSIGNER_JAR, 'apksigner.jar');
  console.log('  工具链就绪');

  title('准备构建目录');
  fs.rmSync(WORK, { recursive: true, force: true });
  fs.mkdirSync(path.join(WORK, 'src'), { recursive: true });
  fs.mkdirSync(path.join(WORK, 'classes'), { recursive: true });
  fs.mkdirSync(path.join(WORK, 'gen'), { recursive: true });
  fs.mkdirSync(DIST, { recursive: true });

  fs.cpSync(path.join(APP, 'res'), path.join(WORK, 'src', 'res'), { recursive: true });
  fs.cpSync(path.join(APP, 'java'), path.join(WORK, 'src', 'java'), { recursive: true });

  // AGP 8 使用 namespace，源码清单里不带 package；aapt2 link 需要，这里注入到临时副本
  const manifest = fs.readFileSync(path.join(APP, 'AndroidManifest.xml'), 'utf8')
    .replace('<manifest xmlns:android=', '<manifest package="com.ledmatrix.marquee" xmlns:android=');
  fs.writeFileSync(path.join(WORK, 'src', 'AndroidManifest.xml'), manifest, 'utf8');
  fs.copyFileSync(ANDROID_JAR, path.join(WORK, 'android.jar'));

  title('aapt2 编译资源');
  const resZip = path.join(WORK, 'res.zip');
  run(AAPT2, ['compile', '--dir', path.join(WORK, 'src', 'res'), '-o', resZip]);

  title('aapt2 链接生成基础 APK');
  const unsignedApk = path.join(WORK, 'app-unsigned.apk');
  run(AAPT2, [
    'link',
    '-o', unsignedApk,
    '-I', path.join(WORK, 'android.jar'),
    '--manifest', path.join(WORK, 'src', 'AndroidManifest.xml'),
    '--java', path.join(WORK, 'gen'),
    '--min-sdk-version', MIN_SDK,
    '--target-sdk-version', TARGET_SDK,
    '--version-code', '3',
    '--version-name', APP_VERSION,
    resZip
  ]);

  title('javac 编译 Java 源码');
  const javaFiles = walk(path.join(WORK, 'src', 'java'), '.java')
    .concat(walk(path.join(WORK, 'gen'), '.java'));
  console.log('  待编译源文件：' + javaFiles.length + ' 个');
  const argFile = path.join(WORK, 'javac-args.txt');
  fs.writeFileSync(argFile, javaFiles.map((f) => '"' + f.replace(/\\/g, '/') + '"').join('\n'), 'utf8');
  run(JAVAC, ['--release', '8', '-encoding', 'UTF-8',

    '-cp', path.join(WORK, 'android.jar'),
    '-d', path.join(WORK, 'classes'), '-nowarn', '@' + argFile]);

  title('d8 转换出 classes.dex');
  const classFiles = walk(path.join(WORK, 'classes'), '.class');
  console.log('  待转换 class：' + classFiles.length + ' 个');
  const dexDir = path.join(WORK, 'dex');
  fs.mkdirSync(dexDir, { recursive: true });
  run(JAVA, ['-cp', D8_JAR, 'com.android.tools.r8.D8',
    '--lib', path.join(WORK, 'android.jar'), '--min-api', MIN_SDK,
    '--output', dexDir].concat(classFiles));
  const dexFile = path.join(dexDir, 'classes.dex');
  need(dexFile, 'classes.dex');
  console.log('  classes.dex：' + fs.statSync(dexFile).size + ' 字节');

  title('合入 classes.dex');
  const entries = readZip(fs.readFileSync(unsignedApk))
    .filter((e) => e.name !== 'classes.dex')
    .map((e) => ({ name: e.name.replace(/\\/g, '/'), data: e.data, store: e.store }));
  entries.push({ name: 'classes.dex', data: fs.readFileSync(dexFile), store: false });
  const withDex = path.join(WORK, 'app-with-dex.apk');
  fs.writeFileSync(withDex, writeZip(entries));
  console.log('  APK 条目共 ' + entries.length + ' 个');

  title('zipalign 对齐');
  const alignedApk = path.join(WORK, 'app-aligned.apk');
  run(ZIPALIGN, ['-f', '-p', '4', withDex, alignedApk]);

  title('准备签名密钥');
  if (!fs.existsSync(KS_FILE)) {
    fs.mkdirSync(path.dirname(KS_FILE), { recursive: true });
    console.log('  首次打包，生成自签名密钥：.keystore/ledmarquee.jks');
    run(KEYTOOL, ['-genkeypair', '-v', '-keystore', KS_FILE, '-storetype', 'PKCS12',
      '-storepass', KS_PASS, '-keypass', KS_PASS, '-alias', KS_ALIAS,
      '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
      '-dname', 'CN=LED Marquee, OU=Personal, O=Personal, C=CN']);
  } else {
    console.log('  复用已有密钥：.keystore/ledmarquee.jks');
  }

  title('apksigner 签名');
  const signedApk = path.join(WORK, 'app-signed.apk');
  run(JAVA, ['-jar', APKSIGNER_JAR, 'sign',
    '--ks', KS_FILE, '--ks-key-alias', KS_ALIAS,
    '--ks-pass', 'pass:' + KS_PASS, '--key-pass', 'pass:' + KS_PASS,
    '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
    '--out', signedApk, alignedApk]);

  title('校验签名');
  run(JAVA, ['-jar', APKSIGNER_JAR, 'verify', '--verbose', signedApk]);

  title('检查 APK 信息');
  run(AAPT2, ['dump', 'badging', signedApk]);

  title('复制到 dist');
  const finalApk = path.join(DIST, APK_NAME);
  fs.copyFileSync(signedApk, finalApk);
  const size = fs.statSync(finalApk).size;

  console.log('\n' + '='.repeat(58));
  console.log('  打包成功！');
  console.log('  APK：' + finalApk);
  console.log('  大小：' + (size / 1024).toFixed(1) + ' KB（' + size + ' 字节）');
  console.log('  支持：Android 5.0 (API 21) 及以上');
  console.log('='.repeat(58));
}

try {
  build();
} catch (e) {
  console.error('\n打包失败：' + e.message);
  process.exit(1);
}
