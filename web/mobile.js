(() => {
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const active = new Map();
  // Skip elements that already drive their own transform scale/pop animations.
  const OWN_MOTION = '.sh-btn, .np-btn, .c-dot, #cam-shutter';
  function release(id) {
    const el = active.get(id);
    if (!el) return;
    active.delete(id);
    el.getAnimations().filter(a => a.id === 'touch-spring').forEach(a => a.cancel());
    if (reduced.matches || !el.isConnected) { el.style.scale = ''; return; }
    const a = el.animate(
      [{scale:'.94'},{scale:'1.03',offset:.48},{scale:'.992',offset:.76},{scale:'1'}],
      {duration:420,easing:'cubic-bezier(.22,.85,.32,1)'}
    );
    a.id = 'touch-spring';
    el.style.scale = '';
  }
  document.addEventListener('pointerdown', e => {
    const el = e.target.closest('button');
    if (!el || reduced.matches || el.matches(OWN_MOTION)) return;
    active.set(e.pointerId, el);
    el.getAnimations().filter(a => a.id === 'touch-spring').forEach(a => a.cancel());
    el.style.scale = '.94';
  }, {passive:true});
  for (const event of ['pointerup','pointercancel','lostpointercapture'])
    document.addEventListener(event, e => release(e.pointerId), {passive:true});
  document.addEventListener('pointerout', e => {
    if (e.pointerType === 'mouse' && active.get(e.pointerId) === e.target) release(e.pointerId);
  }, {passive:true});
  window.addEventListener('blur', () => [...active.keys()].forEach(release));
  window.appBack = () => {
    if (document.getElementById('cam').classList.contains('on')) { Shortcuts.closeCamera(); return true; }
    if (document.getElementById('flash').classList.contains('on')) { document.getElementById('flash-off').click(); return true; }
    if (document.getElementById('clock-frame').classList.contains('on')) { Clock.closeResize(); return true; }
    if (Editor.isOpen()) { Editor.close(); return true; }
    return false;
  };
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && !window.AndroidHost?.isPermissionPending?.()) {
      Shortcuts.closeCamera();
    }
  });
})();
