package com.oclock.liquidglass;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private WebView web;
    private NativeGlass nativeGlass;
    private ValueCallback<Uri[]> files;
    private PermissionRequest camera;
    private volatile boolean awaitingExternalActivity;
    private volatile boolean pageReady;
    private float safeTopCss;
    private float safeBottomCss;

    private Vibrator vibrator;
    private CameraManager cameraManager;
    private String torchCameraId;
    private volatile boolean torchOn;
    private boolean batteryRegistered;
    private volatile int batteryPct = -1;
    private volatile boolean batteryCharging;
    private Object backCallback;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateBatteryFromIntent(intent);
            pushDeviceStatus();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        initHardwareServices();

        web = new WebView(this);
        web.setBackgroundColor(0xff05060a);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setHapticFeedbackEnabled(true);
        setContentView(web);

        setupInsetsAndFullscreen();

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        web.addJavascriptInterface(new AndroidHost(), "AndroidHost");

        WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (nativeGlass != null && ORIGIN.equals(uri.getScheme() + "://" + uri.getHost()) &&
                    uri.getPath() != null && uri.getPath().startsWith("/native-glass/"))
                    return nativeGlass.intercept(uri.getPath());
                return assets.shouldInterceptRequest(uri);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !(ORIGIN.equals(uri.getScheme() + "://" + uri.getHost()) && uri.getPath() != null && uri.getPath().startsWith("/assets/"));
            }
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushSafeInsets();
                pushDeviceStatus();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (files != null) files.onReceiveValue(null);
                files = callback;
                String mime = "image/*";
                if (params != null && params.getAcceptTypes() != null) {
                    for (String type : params.getAcceptTypes()) {
                        if (type != null && type.startsWith("audio/")) {
                            mime = "audio/*";
                            break;
                        }
                    }
                }
                awaitingExternalActivity = true;
                if ("image/*".equals(mime)) {
                    Intent[] candidates = Build.VERSION.SDK_INT >= 33
                        ? new Intent[]{
                            Api33Picker.photoIntent(),
                            new Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"),
                            new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                          }
                        : new Intent[]{
                            new Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"),
                            new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                          };
                    for (Intent candidate : candidates) {
                        try {
                            startActivityForResult(candidate, 10);
                            return true;
                        } catch (android.content.ActivityNotFoundException ignored) {}
                    }
                    awaitingExternalActivity = false;
                    files.onReceiveValue(null);
                    files = null;
                    return false;
                }
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType(mime);
                try {
                    startActivityForResult(picker, 10);
                } catch (android.content.ActivityNotFoundException e) {
                    awaitingExternalActivity = false;
                    files.onReceiveValue(null);
                    files = null;
                }
                return true;
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (!ORIGIN.equals(request.getOrigin().toString().replaceAll("/$", ""))) { request.deny(); return; }
                    boolean video = false;
                    for (String resource : request.getResources())
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) video = true;
                    if (!video) { request.deny(); return; }
                    setTorchInternal(false);
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    else {
                        if (camera != null) camera.deny();
                        camera = request;
                        awaitingExternalActivity = true;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, 20);
                    }
                });
            }

            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                awaitingExternalActivity = false;
                if (camera == request) camera = null;
            }
        });

        if (NativeGlass.available() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
            nativeGlass = new NativeGlass();
            WebViewCompat.addWebMessageListener(web, "AndroidGlass", Collections.singleton(ORIGIN),
                (view, message, origin, mainFrame, reply) -> {
                    if (mainFrame && ORIGIN.equals(origin.toString().replaceAll("/$", ""))) nativeGlass.receive(view, message, reply);
                });
        }

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = Api33Back.register(this, this::handleBack);
        }

        web.loadUrl(ORIGIN + "/assets/index.html");
    }

    private static final class Api33Picker {
        @android.annotation.SuppressLint("NewApi")
        static Intent photoIntent() {
            Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.setType("image/*");
            return i;
        }
    }

    private static final class Api33Back {
        @android.annotation.SuppressLint("NewApi")
        static Object register(Activity activity, Runnable handler) {
            android.window.OnBackInvokedCallback cb = handler::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            return cb;
        }
        @android.annotation.SuppressLint("NewApi")
        static void unregister(Activity activity, Object cb) {
            if (cb instanceof android.window.OnBackInvokedCallback) {
                activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) cb);
            }
        }
    }

    private void initHardwareServices() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            @SuppressWarnings("deprecation")
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            vibrator = v;
        }
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager != null) {
            try {
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                    Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                        torchCameraId = id;
                        break;
                    }
                }
            } catch (CameraAccessException | SecurityException ignored) {}
        }
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (cap >= 0 && cap <= 100) batteryPct = cap;
        }
        Intent sticky = registerStickyBattery();
        if (sticky != null) updateBatteryFromIntent(sticky);
    }

    private Intent registerStickyBattery() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            return registerReceiver(null, filter, Context.RECEIVER_EXPORTED);
        }
        return registerReceiver(null, filter);
    }

    private void updateBatteryFromIntent(Intent intent) {
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level >= 0 && scale > 0) batteryPct = Math.max(0, Math.min(100, Math.round((level * 100f) / scale)));
        batteryCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
    }

    private String networkKind() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "cell";
            Network net = cm.getActiveNetwork();
            if (net == null) return "offline";
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return "offline";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cell";
            return "wifi";
        } catch (SecurityException ignored) {
            return "cell";
        }
    }

    private String buildStatusJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("batteryPct", batteryPct);
            obj.put("charging", batteryCharging);
            obj.put("network", networkKind());
            obj.put("hasTorch", torchCameraId != null);
            obj.put("torchOn", torchOn);
        } catch (JSONException ignored) {}
        return obj.toString();
    }

    private void pushDeviceStatus() {
        if (!pageReady || web == null) return;
        String json = buildStatusJson();
        web.post(() -> {
            if (web != null) web.evaluateJavascript("window.onNativeDeviceStatus && window.onNativeDeviceStatus(" + json + ")", null);
        });
    }

    private void setupInsetsAndFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            int topPx = 0;
            int bottomPx = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    topPx = Math.max(topPx, cutout.getSafeInsetTop());
                    bottomPx = Math.max(bottomPx, cutout.getSafeInsetBottom());
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets cutInsets = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
                topPx = Math.max(topPx, cutInsets.top);
                bottomPx = Math.max(bottomPx, cutInsets.bottom);
            }
            float density = getResources().getDisplayMetrics().density;
            if (density <= 0f) density = 1f;
            safeTopCss = topPx / density;
            safeBottomCss = bottomPx / density;
            pushSafeInsets();
            return insets;
        });
        fullscreen();
    }

    private void pushSafeInsets() {
        if (!pageReady || web == null) return;
        String js = String.format(java.util.Locale.US,
            "document.documentElement.style.setProperty('--native-safe-t','%.1fpx');" +
            "document.documentElement.style.setProperty('--native-safe-b','%.1fpx');" +
            "window.Clock && window.Clock.relayout && window.Clock.relayout();",
            safeTopCss, safeBottomCss);
        web.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private void fullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            @SuppressWarnings("deprecation")
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            @SuppressWarnings("deprecation")
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(flags);
        }
    }

    private boolean setTorchInternal(boolean on) {
        if (cameraManager == null || torchCameraId == null) {
            torchOn = false;
            return false;
        }
        try {
            cameraManager.setTorchMode(torchCameraId, on);
            torchOn = on;
            return true;
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            torchOn = false;
            return false;
        }
    }

    private void performHaptic(String kind) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int effect = VibrationEffect.EFFECT_TICK;
                if ("pop".equals(kind)) effect = VibrationEffect.EFFECT_CLICK;
                else if ("heavy".equals(kind) || "shutter".equals(kind)) effect = VibrationEffect.EFFECT_HEAVY_CLICK;
                vibrator.vibrate(VibrationEffect.createPredefined(effect));
            } else {
                long ms = "heavy".equals(kind) ? 24 : "pop".equals(kind) ? 14 : 9;
                int amp = "heavy".equals(kind) ? 200 : "pop".equals(kind) ? 140 : 85;
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amp));
            }
        } catch (RuntimeException ignored) {}
    }

    final class AndroidHost {
        @JavascriptInterface public void haptic(String kind) {
            performHaptic(kind);
        }
        @JavascriptInterface public boolean setTorch(boolean on) {
            return setTorchInternal(on);
        }
        @JavascriptInterface public String getDeviceStatus() {
            return buildStatusJson();
        }
        @JavascriptInterface public boolean isPermissionPending() {
            return awaitingExternalActivity;
        }
    }

    private void handleBack() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript("window.appBack ? window.appBack() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) fullscreen();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 20) {
            awaitingExternalActivity = false;
            if (camera != null) {
                if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                    camera.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                else camera.deny();
                camera = null;
            }
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 10) {
            awaitingExternalActivity = false;
            if (files != null) {
                Uri[] uris = WebChromeClient.FileChooserParams.parseResult(result, data);
                if (uris == null && result == RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        uris = new Uri[]{ data.getData() };
                    } else if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                        int count = data.getClipData().getItemCount();
                        uris = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            uris[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                files.onReceiveValue(uris);
                files = null;
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        handleBack();
    }

    @Override protected void onPause() {
        if (!awaitingExternalActivity && !isChangingConfigurations()) {
            setTorchInternal(false);
            if (web != null) {
                web.evaluateJavascript(
                    "document.getElementById('cam')?.classList.contains('on') && window.Shortcuts?.closeCamera?.();" +
                    "document.getElementById('flash')?.classList.contains('on') && document.getElementById('flash-off')?.click();" +
                    "window.appPause && window.appPause();",
                    null
                );
            }
        }
        if (batteryRegistered) {
            try { unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) {}
            batteryRegistered = false;
        }
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!batteryRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent sticky;
            if (Build.VERSION.SDK_INT >= 33) {
                sticky = registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                sticky = registerReceiver(batteryReceiver, filter);
            }
            if (sticky != null) updateBatteryFromIntent(sticky);
            batteryRegistered = true;
        }
        if (web != null) {
            web.onResume();
            pushDeviceStatus();
        }
    }

    @Override protected void onDestroy() {
        setTorchInternal(false);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            try { Api33Back.unregister(this, backCallback); } catch (RuntimeException ignored) {}
        }
        if (batteryRegistered) {
            try { unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) {}
            batteryRegistered = false;
        }
        if (files != null) files.onReceiveValue(null);
        if (camera != null) camera.deny();
        if (nativeGlass != null) nativeGlass.close();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
