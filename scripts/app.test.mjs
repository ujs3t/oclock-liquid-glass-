import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import vm from 'node:vm';
const html = readFileSync('web/index.html','utf8');
test('all inline JavaScript parses', () => {
  for (const match of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) new vm.Script(match[1]);
  for (const name of ['mobile.js','mobile-init.js','native-glass.js']) new vm.Script(readFileSync('web/'+name,'utf8'));
});
test('local fonts and mobile assets are packaged', () => {
  assert.ok(!html.includes('fonts.googleapis.com'));
  for (const name of ['mobile.js','mobile-init.js','mobile.css','fonts/inter-latin-400-normal.woff2','fonts/oswald-latin-400-normal.woff2','fonts/bebas-neue-latin-400-normal.woff2']) assert.ok(existsSync('web/'+name));
  assert.match(readFileSync('android/app/build.gradle','utf8'), /assets.srcDirs = \['\.\.\/\.\.\/web'\]/);
});
test('hardware and reduced motion never downgrade glass quality', () => {
  const code=readFileSync('web/mobile-init.js','utf8');
  for (const [memory,cores,reduced] of [[2,2,false],[2,2,true],[8,8,false]]) {
    const context={window:{}, navigator:{deviceMemory:memory,hardwareConcurrency:cores},matchMedia:()=>({matches:reduced})};
    vm.runInNewContext(code,context); assert.equal(context.window.mobileLite,false);
  }
  assert.ok(!html.includes('window.mobileLite ? 1'));
  assert.ok(!html.includes('!window.mobileLite &&'));
  assert.ok(!readFileSync('web/mobile.css','utf8').includes('.lite '));
});
test('UI bugfixes and native Android upgrades stay intact', () => {
  assert.ok(!html.includes('#clock-fb{display:none !important}'));
  assert.ok(!html.includes('#flash button{pointer-events:auto}'));
  assert.ok(html.includes('#flash.on button{pointer-events:auto}'));
  assert.ok(html.includes('<div class="np-controls" dir="ltr">'));
  assert.ok(html.includes('<div id="clock-frame-actions">'));
  assert.ok(html.includes('--native-safe-t'));
  assert.ok(!readFileSync('web/mobile.css','utf8').includes('button{touch-action:manipulation;min-height:44px}'));

  const mobileJs = readFileSync('web/mobile.js','utf8');
  assert.ok(mobileJs.includes('clock-frame'));
  assert.ok(mobileJs.includes('isPermissionPending'));

  const manifest = readFileSync('android/app/src/main/AndroidManifest.xml','utf8');
  assert.ok(manifest.includes('configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"'));
  assert.ok(manifest.includes('@mipmap/ic_launcher'));
  assert.ok(existsSync('android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml'));
  assert.ok(readFileSync('android/app/src/main/res/values-v28/styles.xml','utf8').includes('shortEdges'));

  const mainJava = readFileSync('android/app/src/main/java/com/oclock/liquidglass/MainActivity.java','utf8');
  assert.ok(mainJava.includes('setTorchMode'));
  assert.ok(mainJava.includes('getDeviceStatus'));
  assert.ok(mainJava.includes('OnBackInvokedCallback'));
  assert.ok(mainJava.includes('ACTION_PICK_IMAGES'));
  assert.ok(mainJava.includes('setDataAndType'));
  assert.ok(mainJava.includes('segmentSubject'));
  assert.ok(mainJava.includes('SubjectSegmentation'));
  assert.ok(readFileSync('android/app/build.gradle','utf8').includes('play-services-mlkit-subject-segmentation'));
  assert.ok(manifest.includes('subject_segment'));

  assert.ok(html.includes('lg-glass-layer'));
  assert.ok(html.includes('w-chip gallery'));
  assert.ok(html.includes('fullWidthSize'));
  assert.ok(html.includes('maxSyAtSize'));
  assert.ok(html.includes('stretchDigitBitmap'));
  assert.ok(html.includes('getColonDots'));
  assert.ok(html.includes('measureNumeralMetrics'));
  assert.ok(html.includes('id="clock-stretch"'));
  assert.ok(html.includes('id="clock-y"'));
  assert.ok(html.includes('id="clock-fmt"'));
  assert.ok(html.includes('id="clock-pro-palette"'));
  assert.ok(html.includes('id="clock-opacity"'));
  assert.ok(html.includes('AIDepth'));
  assert.ok(html.includes('Math.min(3, Math.max(2, Math.round(dpr * 2)))'));

  const nativeJava = readFileSync('android/app/src/main/java/com/oclock/liquidglass/NativeGlass.java','utf8');
  assert.ok(nativeJava.includes('setLatestVersion'));
  assert.ok(nativeJava.includes('copyPixelsFromBuffer'));
});
