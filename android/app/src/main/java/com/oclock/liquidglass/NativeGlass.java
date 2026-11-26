package com.oclock.liquidglass;

import android.graphics.Bitmap;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Native CPU raster computation. No frame-by-frame bridge calls or JS pixel loops. */
final class NativeGlass implements AutoCloseable {
    static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String SESSION = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final boolean AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("oclock_glass"); loaded = true; }
        catch (UnsatisfiedLinkError error) { loaded = false; }
        AVAILABLE = loaded;
    }
    static boolean available() { return AVAILABLE; }
    private static native void setLatestVersion(long version);
    private static native byte[][] compute(byte[] packet, int offset, int w, int h, double[] params, long version);

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicLong generation = new AtomicLong();
    private final LinkedHashMap<String, byte[]> images = new LinkedHashMap<>(16, 0.75f, true);
    private int imageBytes = 0;
    private volatile boolean closed;

    private void postError(WebView web, JavaScriptReplyProxy reply, int id, String reason, boolean fatal) {
        if (closed) return;
        String payload = "{\"jobId\":" + id + ",\"error\":\"" + reason + "\",\"fatal\":" + fatal + "}";
        web.post(() -> { if (!closed) reply.postMessage(payload); });
    }

    void receive(WebView web, WebMessageCompat message, JavaScriptReplyProxy reply) {
        if (closed || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            message.getType() != WebMessageCompat.TYPE_ARRAY_BUFFER) return;
        byte[] packet = message.getArrayBuffer();
        if (packet.length < 80 || packet.length > 3000080) {
            if (packet.length >= 8) {
                double maybeId = ByteBuffer.wrap(packet, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
                if (Double.isFinite(maybeId) && maybeId >= 1) postError(web, reply, (int) maybeId, "packet out of bounds", false);
            }
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        double idValue = buffer.getDouble(), width = buffer.getDouble(), height = buffer.getDouble();
        int w = (int) width, h = (int) height, id = (int) idValue;
        if (!Double.isFinite(idValue) || id < 1 || idValue != id) return;
        if (width != w || height != h || w < 4 || h < 4 || (long) w * h > 3000000 || packet.length != 80 + (long) w * h) {
            postError(web, reply, id, "invalid raster size", false);
            return;
        }
        double[] params = new double[7];
        for (int i = 0; i < 7; i++) {
            params[i] = buffer.getDouble();
            if (!Double.isFinite(params[i])) {
                postError(web, reply, id, "non-finite parameter", false);
                return;
            }
        }
        if (params[0] < 0.1 || params[0] > 4 || params[1] < 1 || params[1] > 3 ||
            params[2] < 0 || params[2] > 200 || params[3] < 1 || params[3] > 200 ||
            params[4] < 0 || params[4] > 20 || Math.abs(params[5]) > 360 || params[6] < 0 || params[6] > 3) {
            postError(web, reply, id, "parameter out of range", false);
            return;
        }
        long version = generation.incrementAndGet();
        setLatestVersion(version);
        executor.execute(() -> {
            if (closed || version != generation.get()) return;
            try {
                long start = System.nanoTime();
                byte[][] result = compute(packet, 80, w, h, params, version);
                if (result == null) {
                    if (!closed && version == generation.get()) {
                        postError(web, reply, id, "native raster failed", true);
                    }
                    return;
                }
                if (closed || version != generation.get()) return;
                byte[] displacement = png(result[0], w, h);
                if (closed || version != generation.get()) return;
                String prefix = "/native-glass/" + SESSION + "/" + version + "/";
                if (displacement.length > 12 * 1024 * 1024) {
                    postError(web, reply, id, "native cache budget exceeded", false);
                    return;
                }
                synchronized (images) {
                    if (closed || version != generation.get()) return;
                    images.put(prefix + "disp.png", displacement);
                    imageBytes += displacement.length;
                    while (images.size() > 6 || imageBytes > 12 * 1024 * 1024) {
                        Map.Entry<String, byte[]> oldest = images.entrySet().iterator().next();
                        imageBytes -= oldest.getValue().length;
                        images.remove(oldest.getKey());
                    }
                }
                JSONObject response = new JSONObject();
                response.put("jobId", id);
                response.put("w", w); response.put("h", h);
                response.put("maxAbsCss", ByteBuffer.wrap(result[2]).order(ByteOrder.nativeOrder()).getDouble());
                response.put("dispUrl", ORIGIN + prefix + "disp.png");
                response.put("nativeMs", (System.nanoTime() - start) / 1000000.0);
                web.post(() -> { if (!closed && version == generation.get()) reply.postMessage(response.toString()); });
            } catch (RuntimeException | org.json.JSONException error) {
                postError(web, reply, id, "native raster failed", true);
            }
        });
    }

    private static byte[] png(byte[] rgba, int w, int h) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            // Displacement alpha is always 255, so RGBA matches ARGB_8888 byte buffer layout directly.
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(4096, rgba.length / 8));
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG encoding failed");
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    WebResourceResponse intercept(String path) {
        byte[] bytes;
        synchronized (images) { bytes = images.get(path); }
        WebResourceResponse response = new WebResourceResponse("image/png", null,
            new ByteArrayInputStream(bytes == null ? new byte[0] : bytes));
        response.setStatusCodeAndReasonPhrase(bytes == null ? 404 : 200, bytes == null ? "Not Found" : "OK");
        if (bytes != null) {
            response.setResponseHeaders(java.util.Collections.singletonMap("Cache-Control", "private, max-age=3600, immutable"));
        } else {
            response.setResponseHeaders(java.util.Collections.singletonMap("Cache-Control", "no-store"));
        }
        return response;
    }

    synchronized int getCacheBytes() {
        return imageBytes;
    }

    @Override public void close() {
        closed = true;
        generation.incrementAndGet();
        setLatestVersion(0);
        executor.shutdownNow();
        synchronized (images) { images.clear(); imageBytes = 0; }
    }
}
