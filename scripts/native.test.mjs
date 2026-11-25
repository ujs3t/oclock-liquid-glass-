import {test} from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync,spawnSync} from 'node:child_process';
import {readFileSync,mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import vm from 'node:vm';

test('C++ Android engine preserves full-quality reference refraction and rim', () => {
  const temp=mkdtempSync(join(tmpdir(),'oclock-native-'));
  try {
    const binary=join(temp,'core');
    execFileSync('g++',['-std=c++17','-O3','-ffp-contract=off','scripts/native-core-test.cpp','-o',binary]);
    const core=vm.runInNewContext(readFileSync('scripts/fixtures/glass-reference.js','utf8')+'\ncomputeGlassJob');
    for (const [w,h] of [[96,128],[160,64],[48,80]]) for (const [surfaceId,surface] of ['convexSquircle','convexCircle','concaveSquircle','lipSquircle'].entries()) {
      const alpha=new Uint8Array(w*h);
      for(let y=0;y<h;y++) for(let x=0;x<w;x++) {
        const ring=Math.abs(Math.hypot((x-w/2)*1.2,y-h/2)-Math.min(w,h)*.3);
        alpha[y*w+x]=ring<5?255:ring<6?100:0;
      }
      const it=core(alpha,w,h,2,{bezel:50,thickness:40,ior:1.5,surface,shadowThickness:2.5,shadowAngle:55});
      let next=it.next(); while(!next.done) next=it.next(); const reference=next.value;
      const output=execFileSync(binary,[String(w),String(h),String(surfaceId)],{input:alpha,maxBuffer:32000000});
      assert.ok(Math.abs(output.readDoubleLE()-reference.maxAbsCss)<1e-8,`${surface} displacement scale`);
      const minimal=execFileSync(binary,[String(w),String(h),String(surfaceId),'disp-only'],{input:alpha,maxBuffer:32000000});
      assert.deepEqual(minimal,output.subarray(0,8+w*h*4),'omitting unused shadow cannot change refraction');
      const expected=Buffer.concat([Buffer.from(reference.dispBuf),Buffer.from(reference.shadBuf)]);
      const actual=output.subarray(8); assert.equal(actual.length,expected.length);
      for(let i=0;i<actual.length;i++) assert.ok(Math.abs(actual[i]-expected[i])<=1,`${surface} ${w}x${h} byte ${i}: ${actual[i]} vs ${expected[i]}`);
    }
    const invalid=spawnSync(binary,['1','1','0'],{input:Buffer.from([255])});
    assert.notEqual(invalid.status,0,'invalid raster must be rejected');
  } finally { rmSync(temp,{recursive:true,force:true}); }
});

test('Android transport sends binary alpha and native results; failure disables native path', async () => {
  let packet;
  const bridge={postMessage:p=>packet=p};
  const context={window:{AndroidGlass:bridge},document:{getElementById:()=>null,addEventListener:()=>{}},setTimeout,clearTimeout,ArrayBuffer,DataView,Uint8Array,Error,Number,JSON};
  vm.runInNewContext(readFileSync('web/native-glass.js','utf8'),context);
  const engine=context.window.NativeGlassMath;
  const job=engine.compute(7,{w:4,h:4,ss:2,alpha:new Uint8Array(16).fill(255)},
    {ior:1.5,thickness:40,bezel:50,shadowThickness:2.5,shadowAngle:55,surface:'convexSquircle'});
  assert.equal(packet.byteLength,96); assert.equal(new DataView(packet).getFloat64(0,true),7);
  assert.equal(new Uint8Array(packet)[80],255);
  bridge.onmessage({data:JSON.stringify({jobId:7,maxAbsCss:20,dispUrl:'https://appassets.androidplatform.net/native-glass/1/disp.png',nativeMs:12})});
  assert.equal((await job).nativeMs,12); assert.equal(engine.stats.backend,'android-cpp');
  bridge.postMessage=()=>{throw new Error('unsupported');};
  await assert.rejects(engine.compute(8,{w:4,h:4,ss:2,alpha:new Uint8Array(16)},{}));
  assert.equal(engine.available,false); assert.equal(engine.stats.backend,'web-worker');
});
