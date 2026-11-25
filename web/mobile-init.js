/* Decide before the glass engine allocates displacement textures. */
window.mobileLite = (navigator.deviceMemory && navigator.deviceMemory <= 4) ||
  (navigator.hardwareConcurrency && navigator.hardwareConcurrency <= 4) ||
  matchMedia('(prefers-reduced-motion: reduce)').matches;
document.documentElement.classList.toggle('lite', !!window.mobileLite);
