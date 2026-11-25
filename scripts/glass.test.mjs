import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const original = 'const WORKER_SRC = `' + readFileSync('scripts/fixtures/glass-reference.js','utf8') + 'self.onmessage';
const updated = readFileSync('web/index.html','utf8');
function core(html) {
  const src = html.split('const WORKER_SRC = `')[1].split('self.onmessage')[0];
  return vm.runInNewContext(src+'\ncomputeGlassJob');
}
function run(fn, alpha,w,h) {
  const it=fn(alpha,w,h,2,{bezel:50,thickness:40,ior:1.5,surface:'convexSquircle',shadowThickness:2.5,shadowAngle:55});
  let next=it.next(); while (!next.done) next=it.next(); return next.value;
}
test('optimized refraction is byte-identical to the original at full resolution', () => {
  const before=core(original), after=core(updated);
  for (const [w,h] of [[96,128],[160,64],[48,80]]) {
    const alpha=new Uint8Array(w*h);
    for(let y=0;y<h;y++) for(let x=0;x<w;x++) {
      const ring=Math.abs(Math.hypot((x-w/2)*1.2,y-h/2)-Math.min(w,h)*.3);
      alpha[y*w+x]=ring<5 ? 255 : ring<6 ? 100 : 0;
    }
    const a=run(before,alpha,w,h), b=run(after,alpha,w,h);
    assert.equal(b.maxAbsCss,a.maxAbsCss);
    assert.deepEqual(Buffer.from(b.dispBuf),Buffer.from(a.dispBuf));
    assert.deepEqual(Buffer.from(b.shadBuf),Buffer.from(a.shadBuf));
  }
});
test('auto-fit does not overwrite saved glyph aspect ratio', () => {
  const guard=updated.split('function clampInsideScreen() {')[1].split('\n  }')[0];
  assert.ok(!guard.includes('CONFIG.clock.sx ='));
  assert.ok(!guard.includes('CONFIG.clock.sy ='));
  assert.ok(updated.includes('size *= Math.max(.1, fit)'));
});
