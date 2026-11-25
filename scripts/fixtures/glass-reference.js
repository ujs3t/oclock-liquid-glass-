// Frozen original full-quality math, used only for pixel-equivalence regression tests.

'use strict';
const clamp01=x=>Math.max(0,Math.min(1,x));
const clamp255=x=>Math.max(0,Math.min(255,Math.round(x)));
function convexCircle(x){ const c=clamp01(x); return Math.sqrt(Math.max(0,1-(1-c)*(1-c))); }
function convexSquircle(x){ const c=clamp01(x); return Math.pow(Math.max(0,1-Math.pow(1-c,4)),0.25); }
function smootherstep(x){ const c=clamp01(x); return c*c*c*(c*(c*6-15)+10); }
const SURFACES={convexCircle,convexSquircle,concaveSquircle:x=>1-convexSquircle(x),lipSquircle:x=>{const t=smootherstep(x);const a=convexSquircle(x),b=1-a;return a*(1-t)+b*t;}};
function refract(I,N,n1,n2){const eta=n1/n2,cosI=-(I.x*N.x+I.y*N.y),sin2T=eta*eta*(1-cosI*cosI);if(sin2T>1)return null;const cosT=Math.sqrt(1-sin2T);return {x:eta*I.x+(eta*cosI-cosT)*N.x,y:eta*I.y+(eta*cosI-cosT)*N.y};}
function dispAt(o){const x=clamp01(o.distancePx/o.bezelWidth),h=o.surfaceFn(x)*o.thickness,d=0.001;const x1=Math.max(0,x-d),x2=Math.min(1,x+d);const slope=((o.surfaceFn(x2)-o.surfaceFn(x1))/(x2-x1))*o.thickness/o.bezelWidth;let nx=-slope,nz=1;const len=Math.hypot(nx,nz)||1;nx/=len;nz/=len;const T=refract({x:0,y:-1},{x:nx,y:nz},1,o.refractiveIndex);if(!T||T.y===0)return 0;return (-h/T.y)*T.x;}
function buildProfile(o){const n=127,profile=new Array(n);let maxAbs=0;for(let i=0;i<n;i++){profile[i]=dispAt({distancePx:(i/(n-1))*o.bezelWidth,bezelWidth:o.bezelWidth,thickness:o.thickness,surfaceFn:o.surfaceFn,refractiveIndex:o.ior});maxAbs=Math.max(maxAbs,Math.abs(profile[i]));}return {profile,maxAbs:maxAbs||1e-6,bezelWidth:o.bezelWidth,samples:n};}
function sampleProfile(r,distPx){const c=clamp01(distPx/r.bezelWidth)*r.bezelWidth,p=(c/r.bezelWidth)*(r.samples-1);const i0=Math.floor(p),i1=Math.min(r.samples-1,i0+1),t=p-i0;return r.profile[i0]*(1-t)+r.profile[i1]*t;}
function edt1d(f,d,v,z,n){let k=0;v[0]=0;z[0]=-1e20;z[1]=1e20;for(let q=1;q<n;q++){let s=((f[q]+q*q)-(f[v[k]]+v[k]*v[k]))/(2*q-2*v[k]);while(s<=z[k]){k--;s=((f[q]+q*q)-(f[v[k]]+v[k]*v[k]))/(2*q-2*v[k]);}k++;v[k]=q;z[k]=s;z[k+1]=1e20;}k=0;for(let q=0;q<n;q++){while(z[k+1]<q)k++;const dx=q-v[k];d[q]=dx*dx+f[v[k]];}}
function edt2d(binary,w,h,targetVal){const INF=1e12;const grid=new Float32Array(w*h);for(let i=0;i<w*h;i++)grid[i]=(binary[i]===targetVal)?0:INF;const maxDim=Math.max(w,h);const f=new Float32Array(maxDim),d=new Float32Array(maxDim),v=new Int32Array(maxDim),z=new Float32Array(maxDim+1);for(let y=0;y<h;y++){const off=y*w;for(let x=0;x<w;x++)f[x]=grid[off+x];edt1d(f,d,v,z,w);for(let x=0;x<w;x++)grid[off+x]=d[x];}for(let x=0;x<w;x++){for(let y=0;y<h;y++)f[y]=grid[y*w+x];edt1d(f,d,v,z,h);for(let y=0;y<h;y++)grid[y*w+x]=Math.sqrt(d[y]);}return grid;}

function* computeGlassJob(alpha, w, h, ss, params) {
  const nPx = w * h;
  const binary = new Uint8Array(nPx);
  for (let i = 0; i < nPx; i++) binary[i] = alpha[i] >= 128 ? 1 : 0;
  const distOut = edt2d(binary, w, h, 1);
  const distIn = edt2d(binary, w, h, 0);
  const sdf = new Float32Array(nPx);
  let maxInside = 0;
  for (let i = 0; i < nPx; i++) {
    const s = distOut[i] > 0 ? (distOut[i] - 0.5) : -(distIn[i] - 0.5);
    sdf[i] = s;
    if (-s > maxInside) maxInside = -s;
  }
  const smooth = new Float32Array(nPx);
  for (let y = 0; y < h; y++) {
    const ym = y > 0 ? y - 1 : 0, yp = y < h - 1 ? y + 1 : h - 1;
    for (let x = 0; x < w; x++) {
      const xm = x > 0 ? x - 1 : 0, xp = x < w - 1 ? x + 1 : w - 1;
      smooth[y*w+x] = (sdf[ym*w+xm]+2*sdf[ym*w+x]+sdf[ym*w+xp]+2*sdf[y*w+xm]+4*sdf[y*w+x]+2*sdf[y*w+xp]+sdf[yp*w+xm]+2*sdf[yp*w+x]+sdf[yp*w+xp])*0.0625;
    }
  }
  const userBezelRaster = Math.max(2, params.bezel * ss);
  const bezelRaster = Math.max(2, Math.min(userBezelRaster, Math.max(2, 0.5 * maxInside)));
  const thickRaster = (params.thickness * ss) * (bezelRaster / userBezelRaster);
  const surfaceFn = SURFACES[params.surface] || SURFACES.convexSquircle;
  const radial = buildProfile({ bezelWidth: bezelRaster, thickness: thickRaster, ior: params.ior, surfaceFn });
  const dispData = new Uint8ClampedArray(nPx * 4);
  const shadData = new Uint8ClampedArray(nPx * 4);
  const shadThick = Math.max(1, params.shadowThickness * ss);
  const feather = Math.max(0.9, shadThick * 0.15);
  const radAngle = params.shadowAngle * Math.PI / 180;
  const ax = Math.cos(radAngle), ay = Math.sin(radAngle);
  for (let y = 0; y < h; y++) {
    const ym = y > 0 ? y - 1 : 0, yp = y < h - 1 ? y + 1 : h - 1;
    for (let x = 0; x < w; x++) {
      const xm = x > 0 ? x - 1 : 0, xp = x < w - 1 ? x + 1 : w - 1;
      const idx = y*w+x, p = idx*4, dd = sdf[idx];
      const gx = (smooth[y*w+xp]-smooth[y*w+xm])*0.5;
      const gy = (smooth[yp*w+x]-smooth[ym*w+x])*0.5;
      const len = Math.hypot(gx,gy)||1;
      const nx = gx/len, ny = gy/len;
      if (dd > 0) {
        dispData[p]=dispData[p+1]=dispData[p+2]=128; dispData[p+3]=255;
      } else {
        const disp = sampleProfile(radial, Math.min(-dd, radial.bezelWidth));
        dispData[p]   = clamp255(128 - nx*(disp/radial.maxAbs)*127);
        dispData[p+1] = clamp255(128 - ny*(disp/radial.maxAbs)*127);
        dispData[p+2] = 128; dispData[p+3] = 255;
      }
      if (dd > feather || -dd > shadThick + feather) {
        shadData[p+3] = 0;
      } else {
        const edge = -dd;
        const band = clamp01((shadThick - edge) / shadThick);
        const sharp = Math.pow(band, 0.6);
        const edgeAA = clamp01((feather - dd) / (2 * feather));
        const k = Math.abs(nx*ax + ny*ay);
        shadData[p]=shadData[p+1]=shadData[p+2]=255;
        shadData[p+3]=clamp255(sharp*edgeAA*Math.pow(k,1.8)*255);
      }
    }
  }
  return { maxAbsCss: radial.maxAbs/ss, dispBuf: dispData.buffer, shadBuf: shadData.buffer };
}

