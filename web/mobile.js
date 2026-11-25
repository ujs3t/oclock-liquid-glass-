(() => {
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const active = new Map();
  // The individual scale property does not overwrite existing drag transforms.
  function release(id) {
    const el = active.get(id);
    if (!el) return;
    active.delete(id);
    el.getAnimations().filter(a => a.id === 'touch-spring').forEach(a => a.cancel());
    if (reduced.matches) { el.style.scale = ''; return; }
    const a = el.animate([{scale:'.94'},{scale:'1.035',offset:.5},{scale:'.99',offset:.75},{scale:'1'}],
      {duration:460,easing:'cubic-bezier(.22,.8,.3,1)'});
    a.id = 'touch-spring';
    el.style.scale = '';
  }
  document.addEventListener('pointerdown', e => {
    const el = e.target.closest('button');
    if (!el || reduced.matches) return;
    active.set(e.pointerId, el);
    el.getAnimations().filter(a => a.id === 'touch-spring').forEach(a => a.cancel());
    el.style.scale = '.94';
  }, {passive:true});
  for (const event of ['pointerup','pointercancel','lostpointercapture'])
    document.addEventListener(event, e => release(e.pointerId), {passive:true});
  window.addEventListener('blur', () => [...active.keys()].forEach(release));
  window.appBack = () => {
    if (document.getElementById('cam').classList.contains('on')) { Shortcuts.closeCamera(); return true; }
    if (document.getElementById('flash').classList.contains('on')) { document.getElementById('flash-off').click(); return true; }
    if (Editor.isOpen()) { Editor.close(); return true; }
    return false;
  };
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) Shortcuts.closeCamera();
  });
})();
