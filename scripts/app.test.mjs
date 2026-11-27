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
  assert.ok(manifest.includes('READ_MEDIA_AUDIO'));
  assert.ok(manifest.includes('READ_MEDIA_IMAGES'));
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
  assert.ok(stateKt.includes('innerOpacity: Int = 100'));
  assert.ok(stateKt.includes('innerThickness: Float = 0.9f'));
  assert.ok(stateKt.includes('innerFade: Int = 100'));

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
  assert.ok(viewsKt.includes('onLongPress'));
  assert.ok(viewsKt.includes('longPressTimeoutMs'));
  assert.ok(viewsKt.includes('springCapsule'));
  assert.ok(viewsKt.includes('syncMedia'));
  assert.ok(viewsKt.includes('syncFlashlight'));
  assert.ok(viewsKt.includes('expandInteractive'));
  assert.ok(viewsKt.includes('activeFrac'));

  const mediaKt = readFileSync(ktDir + 'MediaController.kt','utf8');
  assert.ok(mediaKt.includes('MediaStore.Audio.Media.EXTERNAL_CONTENT_URI'));
  assert.ok(mediaKt.includes('startShuffled'));
  assert.ok(mediaKt.includes('loadTrackPoster'));

  const libKt = readFileSync(ktDir + 'MusicLibraryOverlayView.kt','utf8');
  assert.ok(libKt.includes('مكتبة موسيقى الجهاز'));
  assert.ok(libKt.includes('OvershootInterpolator'));

  const manifestXml = readFileSync('android/app/src/main/AndroidManifest.xml','utf8');
  assert.ok(manifestXml.includes('BIND_NOTIFICATION_LISTENER_SERVICE'));
  assert.ok(manifestXml.includes('OClockNotificationListenerService'));
  const hubKt = readFileSync(ktDir + 'NotificationHub.kt','utf8');
  assert.ok(hubKt.includes('NotificationListenerService'));
  assert.ok(hubKt.includes('cancelById'));
  const mainCheck = readFileSync(ktDir + 'MainActivity.kt','utf8');
  assert.ok(mainCheck.includes('applyDepthAndLayerTransforms'));
  assert.ok(mainCheck.includes('clockRestOffset'));
  assert.ok(mainCheck.includes('clockFitScale'));
  assert.ok(mainCheck.includes('springTargetClockFit'));
  assert.ok(mainCheck.includes('minClockInkTop'));
  assert.ok(mainCheck.includes('416f * dens'));
  assert.ok(!mainCheck.includes('max(hostH * 0.56f'));
  assert.ok(mainCheck.includes('onScrollExpandChanged'));
  assert.ok(mainCheck.includes('layoutSpringCallback'));
  assert.ok(mainCheck.includes('springTargetNc'));
  assert.ok(mainCheck.includes('setAnimatedRestingOffset'));

  const notifKt = readFileSync(ktDir + 'NotificationCenterView.kt','utf8');
  assert.ok(notifKt.includes('dismissWithSpring'));
  assert.ok(notifKt.includes('snapBackRubbery'));
  assert.ok(notifKt.includes('nudgeFromMusic'));
  assert.ok(notifKt.includes('LiquidGlassCardHelper'));
  assert.ok(notifKt.includes('GlassMoreHintPill'));
  assert.ok(!notifKt.includes('GlassShowLessPill'));
  assert.ok(notifKt.includes('configureLayoutBounds'));
  assert.ok(notifKt.includes('setAnimatedRestingOffset'));
  assert.ok(notifKt.includes('preserveCurrentOffset'));
  assert.ok(notifKt.includes('trackRawMovement'));
  assert.ok(notifKt.includes('processVerticalDelta'));
  assert.ok(notifKt.includes('vertSlop'));
  assert.ok(notifKt.includes('isFastUpFlick'));
  assert.ok(notifKt.includes('playEntrance3D'));
  assert.ok(notifKt.includes('updateCard3DAndPeek'));
  assert.ok(notifKt.includes('clipRect(-width * 2'));
  assert.ok(!notifKt.includes('"Notification Center"'));
  assert.ok(notifKt.includes('processNextBurstReveal'));
  assert.ok(notifKt.includes('burstDelayMs'));
  assert.ok(notifKt.includes('enterFrac'));
  assert.ok(notifKt.includes('onDisplayedCountChanged'));
  assert.ok(mainKt.includes('hadNotifsInLayout'));
  assert.ok(mainKt.includes('appearingFromEmpty'));
  assert.ok(mainKt.includes('94f * dens'));

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
