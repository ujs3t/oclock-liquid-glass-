import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import vm from 'node:vm';

const html = readFileSync('web/index.html','utf8');

test('all inline JavaScript parses', () => {
  for (const match of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) new vm.Script(match[1]);
  for (const name of ['mobile.js','mobile-init.js','native-glass.js']) new vm.Script(readFileSync('web/'+name,'utf8'));
});

test('local fonts and pure native Kotlin assets are packaged', () => {
  assert.ok(!html.includes('fonts.googleapis.com'));
  for (const name of ['mobile.js','mobile-init.js','mobile.css','fonts/inter-latin-400-normal.woff2','fonts/oswald-latin-400-normal.woff2','fonts/bebas-neue-latin-400-normal.woff2']) assert.ok(existsSync('web/'+name));

  // Android app is pure native Kotlin + AGSL + C++ (zero WebView, bundled TTF fonts)
  const gradle = readFileSync('android/app/build.gradle','utf8');
  assert.ok(gradle.includes("id 'org.jetbrains.kotlin.android'"));
  assert.ok(!gradle.includes('androidx.webkit'));
  for (const ttf of ['bebas_neue.ttf','oswald.ttf','inter.ttf']) {
    assert.ok(existsSync('android/app/src/main/assets/fonts/'+ttf), 'missing bundled font '+ttf);
  }
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

test('pure native Kotlin Liquid Glass engine and UI parity stay intact', () => {
  const manifest = readFileSync('android/app/src/main/AndroidManifest.xml','utf8');
  assert.ok(manifest.includes('configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"'));
  assert.ok(manifest.includes('@mipmap/ic_launcher'));
  assert.ok(manifest.includes('subject_segment'));
  assert.ok(manifest.includes('FileProvider'));
  assert.ok(existsSync('android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml'));
  assert.ok(readFileSync('android/app/src/main/res/values-v28/styles.xml','utf8').includes('shortEdges'));
  assert.ok(readFileSync('android/app/build.gradle','utf8').includes('play-services-mlkit-subject-segmentation'));

  const ktDir = 'android/app/src/main/java/com/oclock/liquidglass/';
  assert.ok(!existsSync(ktDir + 'MainActivity.java'));
  assert.ok(!existsSync(ktDir + 'NativeGlass.java'));

  const mainKt = readFileSync(ktDir + 'MainActivity.kt','utf8');
  assert.ok(!mainKt.includes('WebView'));
  assert.ok(mainKt.includes('setTorchMode'));
  assert.ok(mainKt.includes('OnBackInvokedCallback'));
  assert.ok(mainKt.includes('ACTION_PICK_IMAGES'));
  assert.ok(mainKt.includes('ClockGlassView'));
  assert.ok(mainKt.includes('EditorSheetView'));
  assert.ok(mainKt.includes('Gravity.TOP or Gravity.LEFT'));

  const layerKt = readFileSync(ktDir + 'ClockLayerView.kt','utf8');
  assert.ok(layerKt.includes('inkLeftDp'));
  assert.ok(layerKt.includes('Gravity.TOP or Gravity.LEFT'));

  const shadersKt = readFileSync(ktDir + 'LiquidGlassShaders.kt','utf8');
  assert.ok(shadersKt.includes('RuntimeShader'));
  assert.ok(shadersKt.includes('CARD_AGSL'));
  assert.ok(shadersKt.includes('CLOCK_AGSL'));
  assert.ok(shadersKt.includes('msk.gb'));
  assert.ok(shadersKt.includes('uInnerFade'));

  const stateKt = readFileSync(ktDir + 'AppState.kt','utf8');
  assert.ok(stateKt.includes('innerFade'));

  const sheetKt = readFileSync(ktDir + 'EditorSheetView.kt','utf8');
  assert.ok(sheetKt.includes('نسبة التلاشي'));
  assert.ok(sheetKt.includes('inFadeSeek'));

  const glyphKt = readFileSync(ktDir + 'ClockGlyphRenderer.kt','utf8');
  assert.ok(glyphKt.includes('measureNumeralMetrics'));
  assert.ok(glyphKt.includes('stretchDigitBitmap'));
  assert.ok(glyphKt.includes('getColonDots'));
  assert.ok(glyphKt.includes('computeClockField'));
  assert.ok(glyphKt.includes('inkLeftDp'));

  const nativeKt = readFileSync(ktDir + 'NativeGlass.kt','utf8');
  assert.ok(nativeKt.includes('computeClockField'));
  assert.ok(nativeKt.includes('renderClockCpuFallback'));
  assert.ok(nativeKt.includes('renderCardCpuFallback'));

  const jniCpp = readFileSync('android/app/src/main/cpp/glass_jni.cpp','utf8');
  assert.ok(jniCpp.includes('AndroidBitmap_lockPixels'));
  assert.ok(jniCpp.includes('computeClockField'));
  assert.ok(jniCpp.includes('depthSdf'));
  assert.ok(jniCpp.includes('smoothSdf'));

  const viewsKt = readFileSync(ktDir + 'LiquidGlassViews.kt','utf8');
  assert.ok(viewsKt.includes('trackInitialized'));
  assert.ok(viewsKt.includes('playingInitialized'));
  assert.ok(!viewsKt.includes('artView.scaleX = 0.84f'));

  const aiKt = readFileSync(ktDir + 'AIDepthManager.kt','utf8');
  assert.ok(aiKt.includes('SubjectSegmentation'));

  const perfKt = readFileSync(ktDir + 'PerfMonitor.kt','utf8');
  assert.ok(perfKt.includes('Choreographer'));
  assert.ok(perfKt.includes('oclock.liquidglass.perf.v1'));
  assert.ok(perfKt.includes('MediaStore.Downloads'));

  // Reference web checks still preserved
  assert.ok(html.includes('lg-glass-layer'));
  assert.ok(html.includes('fullWidthSize'));
  assert.ok(html.includes('maxSyAtSize'));
  assert.ok(html.includes('stretchDigitBitmap'));
});
