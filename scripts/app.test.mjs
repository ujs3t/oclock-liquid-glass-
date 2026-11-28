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
  for (const ttf of [
    'bebas_neue.ttf','oswald.ttf','inter.ttf','sf_rounded.ttf',
    'ios_classic.ttf','ios_slab.ttf','ios_stencil.ttf','ios_rails.ttf','ios_heavy.ttf',
    'thin.ttf','light.ttf','cyber.ttf','mono.ttf','retro.ttf',
    'editorial.ttf','tech.ttf','black.ttf','poster.ttf','bubble.ttf'
  ]) {
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
  assert.ok(shadersKt.includes('m0.gb'));
  assert.ok(shadersKt.includes('uInnerFade'));

  const stateKt = readFileSync(ktDir + 'AppState.kt','utf8');
  assert.ok(stateKt.includes('innerFade'));
  assert.ok(stateKt.includes('innerOpacity: Int = 100'));
  assert.ok(stateKt.includes('innerThickness: Float = 0.3f'));
  assert.ok(stateKt.includes('innerAngle: Float = 59f'));
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
  assert.ok(!notifKt.includes('GlassMoreHintPill'));
  assert.ok(!notifKt.includes('اسحب للأعلى أو للأسفل'));
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
  assert.ok(notifKt.includes('deckCycleProg'));
  assert.ok(notifKt.includes('rotateFrontCardToBack'));
  assert.ok(notifKt.includes('settleDeckCycle'));
  assert.ok(glyphKt.includes('inValidBand'));
  assert.ok(shadersKt.includes('0.75 * rg.x'));
  assert.ok(shadersKt.includes('255.0 / 1.5 - 1.0'));
  assert.ok(shadersKt.includes('mix(1.0, lit, fadeAmt)'));
  assert.ok(!shadersKt.includes('tLip'));
  assert.ok(jniCpp.includes('blurR'));
  assert.ok(!viewsKt.includes('165, 174, 190'));
  assert.ok(mainKt.includes('hadNotifsInLayout'));
  assert.ok(mainKt.includes('appearingFromEmpty'));
  assert.ok(mainKt.includes('94f * dens'));
  assert.ok(mainKt.includes('UnlockSwipeShell'));
  assert.ok(mainKt.includes('isEligibleForUnlockSwipe'));
  assert.ok(mainKt.includes('passcodeOverlay'));
  assert.ok(mainKt.includes('onTogglePasscode'));
  assert.ok(mainKt.includes('onChangePasscodePin'));
  assert.ok(notifKt.includes('isPointInsideInteractiveDeck'));
  assert.ok(notifKt.includes('rejectDownGesture'));
  assert.ok(stateKt.includes('passcodeEnabled'));
  assert.ok(stateKt.includes('passcodePin'));
  assert.ok(shadersKt.includes('createSoftBlurredBackdrop'));
  assert.ok(shadersKt.includes('blurredShader'));
  assert.ok(viewsKt.includes('useBlurredBackdrop'));
  assert.ok(sheetKt.includes('onTogglePasscode'));
  assert.ok(sheetKt.includes('onChangePasscodePin'));
  const passcodeKt = readFileSync(ktDir + 'PasscodeOverlayView.kt','utf8');
  assert.ok(passcodeKt.includes('GlassKeypadButton'));
  assert.ok(passcodeKt.includes('PasscodeDotsView'));
  assert.ok(passcodeKt.includes('useBlurredBackdrop = false'));
  assert.ok(passcodeKt.includes('shadowInt = 0.0f'));
  assert.ok(passcodeKt.includes('innerShadow = 0.46f'));
  assert.ok(shadersKt.includes('clamp(uBezel * 0.095, 4.2, 9.5)'));
  assert.ok(shadersKt.includes('smoothstep(0.65, -0.65, d0)'));
  assert.ok(mainKt.includes('shellH * 0.35f'));
  assert.ok(passcodeKt.includes('Choreographer.FrameCallback'));
  assert.ok(passcodeKt.includes('applyCascadeTransforms'));
  assert.ok(passcodeKt.includes('setInteractiveProgress'));
  assert.ok(passcodeKt.includes('resetToClosedState'));
  assert.ok(passcodeKt.includes('cancelAnimations'));
  assert.ok(passcodeKt.includes('isClosing'));
  assert.ok(passcodeKt.includes('openForSetup'));
  assert.ok(passcodeKt.includes('activePointerId'));
  assert.ok(passcodeKt.includes('isMotionEventSplittingEnabled = false'));
  assert.ok(mainKt.includes('isWallOrClockPulling'));
  assert.ok(mainKt.includes('cancelActivePullForPasscode'));
  assert.ok(mainKt.includes('isMotionEventSplittingEnabled = false'));
  assert.ok(notifKt.includes('playFrontSettleBounce'));
  assert.ok(notifKt.includes('isActivelyInteracting'));
  assert.ok(passcodeKt.includes('applyProgressFrame'));
  assert.ok(passcodeKt.includes('openSilkEase'));
  assert.ok(passcodeKt.includes('closeSilkEase'));
  assert.ok(notifKt.includes('resetAllTouchAndScroll'));
  assert.ok(notifKt.includes('requestDisallowInterceptTouchEvent'));

  const appStateKt = readFileSync(ktDir + 'AppState.kt', 'utf8');
  assert.ok(appStateKt.includes('var depth: Boolean = false'));
  const depthKt = readFileSync(ktDir + 'DepthRevealOverlayView.kt', 'utf8');
  assert.ok(depthKt.includes('playSeparationReveal'));
  assert.ok(depthKt.includes('DepthLoadingPill'));
  assert.ok(mainKt.includes('runLockScreenDepthActivation'));
  assert.ok(mainKt.includes('playDepthSeparationReveal'));
  assert.ok(mainKt.includes('passcodeProgress / 0.30f'));
  assert.ok(notifKt.includes('clearCascadeActive'));
  assert.ok(notifKt.includes('cardPtrId'));
  assert.ok(notifKt.includes('4800f'));
  assert.ok(notifKt.includes('gapSpringRunning'));
  assert.ok(notifKt.includes('gapSpringDelayMs'));
  assert.ok(notifKt.includes('gapSpringOmega'));
  assert.ok(notifKt.includes('effTopBand'));
  assert.ok(notifKt.includes('effBotBand'));
  assert.ok(notifKt.includes('wasBottomOverscroll'));

  const aiKt = readFileSync(ktDir + 'AIDepthManager.kt','utf8');
  assert.ok(aiKt.includes('SubjectSegmentation'));
  assert.ok(!aiKt.includes('مجاني'));

  // Split dark Liquid Glass Clock vs Wallpaper customizers + iPhone fonts + live preview stage
  for (const fontId of [
    'ROUNDED','CLASSIC','SLAB','STENCIL','RAILS','HEAVY',
    'THIN','LIGHT','CYBER','MONO','RETRO','EDITORIAL','TECH','BLACK','POSTER','BUBBLE'
  ]) {
    assert.ok(appStateKt.includes(fontId));
  }
  assert.ok(sheetKt.includes('enum class Mode { CLOCK, WALLPAPER }'));
  assert.ok(sheetKt.includes('GlassSheetCard'));
  assert.ok(sheetKt.includes('GlassSectionCard'));
  assert.ok(sheetKt.includes('GlassPillButton'));
  assert.ok(sheetKt.includes('GlassFontChip'));
  assert.ok(sheetKt.includes('IosSwitchView'));
  assert.ok(sheetKt.includes('IosToggleRow'));
  assert.ok(sheetKt.includes('IosSegmentedControl'));
  assert.ok(sheetKt.includes('IosWallActionTile'));
  assert.ok(sheetKt.includes('IosCapsuleButton'));
  assert.ok(sheetKt.includes('makeIosActionRow'));
  assert.ok(sheetKt.includes('EditorRubberScrollView'));
  assert.ok(sheetKt.includes('RubberHorizontalScrollView'));
  assert.ok(sheetKt.includes('sheetSpringCallback'));
  assert.ok(sheetKt.includes('morphTopPx'));
  assert.ok(sheetKt.includes('fun setLabel'));
  assert.ok(viewsKt.includes('drawTopOffsetPx'));
  assert.ok(mainKt.includes('editorStageSpringCallback'));
  const horizIdx = sheetKt.indexOf('private inner class RubberHorizontalScrollView');
  const horizEnd = sheetKt.indexOf('private inner class GlassPillButton', horizIdx);
  const horizSlice = sheetKt.slice(horizIdx, horizEnd);
  assert.ok(horizSlice.includes('scrollTo(maxScroll, 0)'));
  assert.ok(!horizSlice.includes('scrollTo(0, maxScroll)'));
  assert.ok(horizSlice.includes('computeScrollDeltaToGetChildRectOnScreen'));
  assert.ok(horizSlice.includes('userInteracted'));
  assert.ok(glyphKt.includes('bestGapStart'));
  assert.ok(glyphKt.includes('GlyphDrawOp'));
  assert.ok(glyphKt.includes("for (d in '0'..'9')"));
  assert.ok(glyphKt.includes('tryLoadBootArtifact'));
  assert.ok(glyphKt.includes('saveBootArtifact'));
  assert.ok(glyphKt.includes('peekFastMemoryArtifact'));
  assert.ok(glyphKt.includes('companion object'));
  assert.ok(layerKt.includes('ssOverride = 1.25f'));
  assert.ok(layerKt.includes('beginFontTransition'));
  assert.ok(layerKt.includes('fontCrossAnim'));
  assert.ok(layerKt.includes('ClockGhostView'));
  assert.ok(sheetKt.includes('ensureBodiesBuilt'));
  assert.ok(mainKt.includes('estimatedShellWidthDp'));
  assert.ok(mainKt.includes('clockView.beginFontTransition()'));
  assert.ok(mainKt.includes('glideEditorStageToLatest'));
  assert.ok(mainKt.includes('notifExpandFrac > 0.02f && ::resizeOverlay.isInitialized && resizeOverlay.isOpen'));
  assert.ok(sheetKt.includes('widthSeek'));
  assert.ok(sheetKt.includes('العرض'));
  assert.ok(!sheetKt.includes('مجاني'));
  assert.ok(layerKt.includes('committedSx'));
  assert.ok(layerKt.includes('onClockTap'));
  assert.ok(mainKt.includes('openClockCustomizerFromResize'));
  assert.ok(mainKt.includes('openWallpaperCustomizer'));
  assert.ok(mainKt.includes('wallHoldRunnable'));
  assert.ok(mainKt.includes('560L'));
  assert.ok(mainKt.includes('clockEditorStageFrac'));
  assert.ok(mainKt.includes('wallEditorStageFrac'));

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
