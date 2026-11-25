/* Android-only binary bridge. A browser or older WebView keeps the full-quality
   Worker implementation; no base64 alpha serialization across the bridge. */
(() => {
  let enabled = !!window.AndroidGlass;
  let pending = null;
  const stats = { backend: 'web-worker', nativeMs: 0, fallbacks: 0 };
  window.NativeGlassMath = {
    get available() { return enabled; },
    stats,
    compute(jobId, meta, params) {
      return new Promise((resolve, reject) => {
        if (!enabled) { reject(new Error('native unavailable')); return; }
        if (pending) { clearTimeout(pending.timer); pending.reject(new Error('superseded')); pending = null; }
        const packet = new ArrayBuffer(80 + meta.alpha.length);
        const header = new DataView(packet);
        const surface = ['convexSquircle','convexCircle','concaveSquircle','lipSquircle'].indexOf(params.surface);
        [jobId,meta.w,meta.h,meta.ss,params.ior,params.thickness,params.bezel,
          params.shadowThickness,params.shadowAngle,Math.max(0,surface)]
          .forEach((value, i) => header.setFloat64(i*8,value,true));
        new Uint8Array(packet,80).set(meta.alpha);
        const fail = (error) => {
          if (!pending || pending.jobId !== jobId) return;
          clearTimeout(pending.timer); pending = null; enabled = false;
          stats.fallbacks++; stats.backend = 'web-worker'; reject(error);
        };
        pending = {jobId,resolve,reject,fail,timer:setTimeout(()=>fail(new Error('native timeout')),8000)};
        try { window.AndroidGlass.postMessage(packet); } catch(error) { fail(error); }
      });
    }
  };
  if (enabled) window.AndroidGlass.onmessage = event => {
    let data;
    try { data = JSON.parse(event.data); } catch (_) { return; }
    if (!pending || data.jobId !== pending.jobId) return;
    if (data.error || !Number.isFinite(data.maxAbsCss) ||
        !data.dispUrl?.startsWith('https://appassets.androidplatform.net/native-glass/') ||
        !data.shadUrl?.startsWith('https://appassets.androidplatform.net/native-glass/')) {
      pending.fail(new Error('invalid native result')); return;
    }
    const job = pending; pending = null; clearTimeout(job.timer);
    stats.backend = 'android-cpp'; stats.nativeMs = data.nativeMs;
    job.resolve(data);
  };
})();
