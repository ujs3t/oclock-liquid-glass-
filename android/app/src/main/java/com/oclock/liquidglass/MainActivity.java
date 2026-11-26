package com.oclock.liquidglass;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.os.Debug;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.util.Base64;
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
import com.google.android.gms.common.moduleinstall.InstallStatusListener;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private WebView web;
    private NativeGlass nativeGlass;
    private SubjectSegmenter subjectSegmenter;
    private final ExecutorService segmentExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService perfWriteExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, byte[]> subjectResults = new ConcurrentHashMap<>();
    private ValueCallback<Uri[]> files;
    private volatile byte[] pendingPerfBytes;
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
                String path = uri.getPath();
                if (ORIGIN.equals(uri.getScheme() + "://" + uri.getHost()) && path != null) {
                    if (nativeGlass != null && path.startsWith("/native-glass/"))
                        return nativeGlass.intercept(path);
                    if (path.startsWith("/native-subject/")) {
                        String token = path.substring("/native-subject/".length());
                        byte[] png = subjectResults.remove(token);
                        if (png == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Cache-Control", "no-store");
                        return new WebResourceResponse("image/png", null, 200, "OK", headers, new ByteArrayInputStream(png));
                    }
                }
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

    private synchronized SubjectSegmenter getSubjectSegmenter() {
        if (subjectSegmenter == null) {
            SubjectSegmenterOptions opts = new SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build();
            subjectSegmenter = SubjectSegmentation.getClient(opts);
        }
        return subjectSegmenter;
    }

    private void pushSubjectProgress(String reqId, int pct, String msg) {
        if (web == null) return;
        String js = "window.onSubjectDepthProgress && window.onSubjectDepthProgress(" +
            JSONObject.quote(reqId) + "," + pct + "," + JSONObject.quote(msg) + ")";
        web.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private void pushSubjectError(String reqId, String code, String msg) {
        if (web == null) return;
        String js = "window.onSubjectDepthError && window.onSubjectDepthError(" +
            JSONObject.quote(reqId) + "," + JSONObject.quote(code) + "," + JSONObject.quote(msg) + ")";
        web.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private void runSegmentationOnBitmap(String reqId, Bitmap bmp, SubjectSegmenter seg) {
        pushSubjectProgress(reqId, 72, "جاري استخراج الشخصية بالذكاء الاصطناعي…");
        InputImage inputImage = InputImage.fromBitmap(bmp, 0);
        seg.process(inputImage)
            .addOnSuccessListener(result -> segmentExecutor.execute(() -> {
                try {
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    FloatBuffer mask = result.getForegroundConfidenceMask();
                    if (mask == null || mask.limit() < w * h) {
                        bmp.recycle();
                        pushSubjectError(reqId, "NO_MASK", "No foreground mask");
                        return;
                    }
                    mask.rewind();
                    int total = w * h;
                    int fgCount = 0;
                    int[] src = new int[total];
                    bmp.getPixels(src, 0, w, 0, 0, w, h);
                    bmp.recycle();
                    int[] out = new int[total];
                    for (int i = 0; i < total; i++) {
                        float c = mask.get(i);
                        if (c > 0.48f) fgCount++;
                        if (c <= 0.28f) {
                            out[i] = 0;
                        } else {
                            float t = Math.min(1f, (c - 0.28f) / 0.44f);
                            float smooth = t * t * (3f - 2f * t);
                            int a = Math.min(255, Math.max(0, Math.round(smooth * 255f)));
                            out[i] = (a << 24) | (src[i] & 0x00ffffff);
                        }
                    }
                    float coverage = (float) fgCount / (float) Math.max(1, total);
                    if (coverage < 0.006f || coverage > 0.96f) {
                        pushSubjectError(reqId, "NO_SUBJECT", "لم يتم العثور على عنصر واضح لعزله");
                        return;
                    }
                    Bitmap cutout = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(w * h / 2);
                    cutout.compress(Bitmap.CompressFormat.PNG, 100, baos);
                    cutout.recycle();
                    String token = reqId.replaceAll("[^A-Za-z0-9_-]", "") + "_" + System.nanoTime() + ".png";
                    subjectResults.put(token, baos.toByteArray());
                    String js = "window.onSubjectDepthReady && window.onSubjectDepthReady(" +
                        JSONObject.quote(reqId) + "," + JSONObject.quote("/native-subject/" + token) + ")";
                    if (web != null) web.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
                } catch (Throwable err) {
                    pushSubjectError(reqId, "ENCODE_FAILED", String.valueOf(err.getMessage()));
                }
            }))
            .addOnFailureListener(e -> {
                bmp.recycle();
                pushSubjectError(reqId, "MLKIT_FAILED", e.getMessage() != null ? e.getMessage() : "MLKit error");
            });
    }

    private void startSubjectSegmentation(String reqId, String dataUrl) {
        if (dataUrl == null || dataUrl.isEmpty()) {
            pushSubjectError(reqId, "EMPTY_IMAGE", "No image data");
            return;
        }
        pushSubjectProgress(reqId, 10, "جاري تجهيز الصورة…");
        segmentExecutor.execute(() -> {
            try {
                int comma = dataUrl.indexOf(',');
                String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
                int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                int sample = 1;
                while (maxDim / sample > 1440) sample *= 2;
                BitmapFactory.Options dec = new BitmapFactory.Options();
                dec.inSampleSize = sample;
                dec.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length, dec);
                if (decoded == null) {
                    pushSubjectError(reqId, "DECODE_FAILED", "Cannot decode wallpaper");
                    return;
                }
                Bitmap bmp = decoded.getConfig() == Bitmap.Config.ARGB_8888
                    ? decoded : decoded.copy(Bitmap.Config.ARGB_8888, false);
                if (bmp != decoded) decoded.recycle();
                if (bmp == null) {
                    pushSubjectError(reqId, "DECODE_FAILED", "Bitmap copy failed");
                    return;
                }
                if (Math.max(bmp.getWidth(), bmp.getHeight()) > 960) {
                    float s = 960f / Math.max(bmp.getWidth(), bmp.getHeight());
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(2, Math.round(bmp.getWidth() * s)), Math.max(2, Math.round(bmp.getHeight() * s)), true);
                    bmp.recycle();
                    bmp = scaled;
                }
                final Bitmap readyBmp = bmp;
                runOnUiThread(() -> {
                    SubjectSegmenter seg = getSubjectSegmenter();
                    ModuleInstallClient mic = ModuleInstall.getClient(this);
                    mic.areModulesAvailable(seg)
                        .addOnSuccessListener(avail -> {
                            if (avail.areModulesAvailable()) {
                                runSegmentationOnBitmap(reqId, readyBmp, seg);
                            } else {
                                pushSubjectProgress(reqId, 18, "جاري تنزيل محرك Google AI المجاني (مرة واحدة فقط)…");
                                final InstallStatusListener[] holder = new InstallStatusListener[1];
                                holder[0] = update -> {
                                    ModuleInstallStatusUpdate.ProgressInfo p = update.getProgressInfo();
                                    if (p != null && p.getTotalBytesToDownload() > 0) {
                                        int pct = 18 + (int) ((p.getBytesDownloaded() * 52L) / p.getTotalBytesToDownload());
                                        pushSubjectProgress(reqId, Math.min(70, pct), "جاري تنزيل محرك الذكاء الاصطناعي…");
                                    }
                                    int st = update.getInstallState();
                                    if (st == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED) {
                                        mic.unregisterListener(holder[0]);
                                        runSegmentationOnBitmap(reqId, readyBmp, seg);
                                    } else if (st == ModuleInstallStatusUpdate.InstallState.STATE_FAILED ||
                                               st == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED) {
                                        mic.unregisterListener(holder[0]);
                                        readyBmp.recycle();
                                        pushSubjectError(reqId, "PLAY_SERVICES_UNAVAILABLE", "Module install state " + st);
                                    }
                                };
                                ModuleInstallRequest req = ModuleInstallRequest.newBuilder()
                                    .addApi(seg)
                                    .setListener(holder[0])
                                    .build();
                                mic.installModules(req)
                                    .addOnSuccessListener(res -> {
                                        if (res.areModulesAlreadyInstalled()) {
                                            mic.unregisterListener(holder[0]);
                                            runSegmentationOnBitmap(reqId, readyBmp, seg);
                                        }
                                    })
                                    .addOnFailureListener(err -> {
                                        mic.unregisterListener(holder[0]);
                                        readyBmp.recycle();
                                        pushSubjectError(reqId, "PLAY_SERVICES_UNAVAILABLE", String.valueOf(err.getMessage()));
                                    });
                            }
                        })
                        .addOnFailureListener(err -> {
                            readyBmp.recycle();
                            pushSubjectError(reqId, "PLAY_SERVICES_UNAVAILABLE", String.valueOf(err.getMessage()));
                        });
                });
            } catch (Throwable t) {
                pushSubjectError(reqId, "SEGMENT_INIT_FAILED", String.valueOf(t.getMessage()));
            }
        });
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
        @JavascriptInterface public void segmentSubject(String requestId, String dataUrl) {
            startSubjectSegmentation(requestId, dataUrl);
        }
        @JavascriptInterface public String getPerfStats() {
            return buildPerfStatsJson();
        }
        @JavascriptInterface public String savePerfReport(String filename, String content, boolean silent) {
            return handleSavePerfReport(filename, content, silent);
        }
        @JavascriptInterface public boolean exportPerfFilePicker(String filename, String content) {
            return launchPerfCreateDocument(filename, content);
        }
        @JavascriptInterface public boolean sharePerfReport(String filename, String content) {
            return handleSharePerfReport(filename, content);
        }
    }

    private String buildPerfStatsJson() {
        JSONObject obj = new JSONObject();
        try {
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);
            obj.put("totalPssMB", Math.round(mi.getTotalPss() / 10.24) / 100.0);
            obj.put("nativePssMB", Math.round(mi.nativePss / 10.24) / 100.0);
            obj.put("dalvikPssMB", Math.round(mi.dalvikPss / 10.24) / 100.0);
            obj.put("otherPssMB", Math.round(mi.otherPss / 10.24) / 100.0);
            obj.put("nativeHeapMB", Math.round(Debug.getNativeHeapAllocatedSize() / 10485.76) / 100.0);
            Runtime rt = Runtime.getRuntime();
            obj.put("javaUsedMB", Math.round((rt.totalMemory() - rt.freeMemory()) / 10485.76) / 100.0);
            obj.put("javaMaxMB", Math.round(rt.maxMemory() / 10485.76) / 100.0);
            obj.put("cpuCores", rt.availableProcessors());
            obj.put("sdk", Build.VERSION.SDK_INT);
            obj.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            obj.put("nativeGlassCacheKB", nativeGlass != null ? Math.round(nativeGlass.getCacheBytes() / 1024.0) : 0);
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo ami = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(ami);
                obj.put("availRamMB", Math.round(ami.availMem / 1048576.0));
                obj.put("totalRamMB", Math.round(ami.totalMem / 1048576.0));
                obj.put("lowMemory", ami.lowMemory);
            }
        } catch (Throwable ignored) {}
        return obj.toString();
    }

    private String handleSavePerfReport(String filename, String content, boolean silent) {
        if (content == null || content.isEmpty()) return "ERROR:empty";
        String safe = (filename == null || filename.isEmpty() ? "OClock-Perf.json" : filename)
            .replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safe.endsWith(".json")) safe += ".json";
        final String finalName = safe;
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            if (silent) {
                perfWriteExecutor.execute(() -> {
                    try { Api29Downloads.save(getApplicationContext(), finalName, bytes, true); }
                    catch (Throwable ignored) {}
                });
                return "QUEUED:" + finalName;
            }
            try {
                Uri saved = Api29Downloads.save(getApplicationContext(), finalName, bytes, false);
                if (saved != null) return "DOWNLOADS:" + finalName;
            } catch (Throwable ignored) {}
        }
        if (!silent && launchPerfCreateDocument(finalName, content)) {
            return "PICKER:" + finalName;
        }
        return "UNAVAILABLE";
    }

    private boolean handleSharePerfReport(String filename, String content) {
        if (content == null || content.isEmpty()) return false;
        String safe = (filename == null || filename.isEmpty() ? "OClock-Perf.json" : filename)
            .replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safe.endsWith(".json")) safe += ".json";
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri uri = Api29Downloads.save(getApplicationContext(), safe, content.getBytes(StandardCharsets.UTF_8), false);
                if (uri != null) {
                    awaitingExternalActivity = true;
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/json");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, safe);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(Intent.createChooser(send, "مشاركة ملف تقرير الأداء"), 31);
                    return true;
                }
            } catch (Throwable ignored) {
                awaitingExternalActivity = false;
            }
        }
        return launchPerfCreateDocument(safe, content);
    }

    private boolean launchPerfCreateDocument(String filename, String content) {
        if (content == null || content.isEmpty()) return false;
        String safe = (filename == null || filename.isEmpty() ? "OClock-Perf.json" : filename)
            .replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safe.endsWith(".json")) safe += ".json";
        pendingPerfBytes = content.getBytes(StandardCharsets.UTF_8);
        awaitingExternalActivity = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, safe);
        try {
            startActivityForResult(intent, 30);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            awaitingExternalActivity = false;
            pendingPerfBytes = null;
            return false;
        }
    }

    private static final class Api29Downloads {
        @android.annotation.SuppressLint("NewApi")
        static Uri save(Context ctx, String displayName, byte[] bytes, boolean replace) throws Exception {
            ContentResolver cr = ctx.getContentResolver();
            if (replace) {
                try {
                    cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{ displayName });
                } catch (Throwable ignored) {}
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("openOutputStream null");
                os.write(bytes);
                os.flush();
            } catch (Throwable t) {
                try { cr.delete(uri, null, null); } catch (Throwable ignored) {}
                throw t;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return uri;
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
        } else if (code == 30) {
            awaitingExternalActivity = false;
            byte[] bytes = pendingPerfBytes;
            pendingPerfBytes = null;
            if (result == RESULT_OK && data != null && data.getData() != null && bytes != null) {
                Uri outUri = data.getData();
                perfWriteExecutor.execute(() -> {
                    try (OutputStream os = getContentResolver().openOutputStream(outUri)) {
                        if (os != null) {
                            os.write(bytes);
                            os.flush();
                            if (web != null) web.post(() -> web.evaluateJavascript("window.toast && window.toast('تم حفظ ملف تقرير الأداء')", null));
                        }
                    } catch (Throwable ignored) {}
                });
            }
        } else if (code == 31) {
            awaitingExternalActivity = false;
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
        if (subjectSegmenter != null) {
            try { subjectSegmenter.close(); } catch (RuntimeException ignored) {}
            subjectSegmenter = null;
        }
        segmentExecutor.shutdownNow();
        perfWriteExecutor.shutdown();
        subjectResults.clear();
        if (nativeGlass != null) nativeGlass.close();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
