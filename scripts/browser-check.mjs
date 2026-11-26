import { chromium as playwright } from '@playwright/test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const browser = await playwright.launch({headless:true});
try {
  for (const [width,height,reducedMotion] of [[360,640,'no-preference'],[412,915,'no-preference'],[320,568,'reduce'],[800,360,'no-preference']]) {
    const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true,reducedMotion,acceptDownloads:true});
    const errors=[]; page.on('pageerror',e=>errors.push(e.message));
    await page.route('https://api.open-meteo.com/**',route=>route.abort());
    await page.addInitScript(() => {
      Object.defineProperty(navigator,'deviceMemory',{get:()=>2});
      Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>2});
      const RealDate = Date;
      let offsetMs = 0;
      const baseFake = new RealDate(2026, 10, 25, 18, 16, 5, 0).getTime();
      const basePerf = performance.now();
      class FixedDate extends RealDate {
        constructor(...a) {
          super(...(a.length ? a : [baseFake + offsetMs + (performance.now() - basePerf)]));
        }
        static now() { return baseFake + offsetMs + (performance.now() - basePerf); }
      }
      window.Date = FixedDate;
      window.__advanceClockMs = (ms) => { offsetMs += ms; };
    });
    await page.goto('http://localhost:5173');
    await page.waitForFunction(() => document.getElementById('clock-inner').style.backgroundImage.startsWith('url('), null, {timeout:20000});
    await page.waitForTimeout(1500);
    assert.equal(await page.evaluate(()=>canGlass),true, 'low RAM must not disable refraction');
    const before = await page.evaluate(() => ({
      sx:CONFIG.clock.sx, sy:CONFIG.clock.sy,
      rim:document.getElementById('clock-inner').style.backgroundImage,
      width:document.getElementById('clock-glass').getBoundingClientRect().width,
      screen:document.getElementById('screen').getBoundingClientRect().width,
      quality:Clock.metrics()
    }));
    assert.ok(before.width <= before.screen + 1, 'clock must fit screen');
    const rimPixels = await page.evaluate(async () => {
      const src=document.getElementById('clock-inner').style.backgroundImage.slice(5,-2);
      const image=new Image(); image.src=src; await image.decode();
      const canvas=document.createElement('canvas'); canvas.width=image.width; canvas.height=image.height;
      const ctx=canvas.getContext('2d'); ctx.drawImage(image,0,0);
      const data=ctx.getImageData(0,0,canvas.width,canvas.height).data;
      let white=0; for(let i=0;i<data.length;i+=4) if(data[i]>240 && data[i+1]>240 && data[i+2]>240 && data[i+3]>0) white++;
      return white;
    });
    assert.ok(rimPixels>20,'white inner shadow must contain visible pixels');
    const layoutInfo = await page.evaluate(async () => {
      const top = document.getElementById('topline').getBoundingClientRect();
      const clk = document.getElementById('clock-glass').getBoundingClientRect();
      const sh = document.getElementById('sh-left');
      const shBox = sh.getBoundingClientRect();
      const shRimBg = sh.querySelector('.lg-shadow-layer')?.style.backgroundImage || '';
      const shUrl = shRimBg.slice(4, -1).replace(/^"|"$/g, '');
      const shImg = new Image(); shImg.src = shUrl; await shImg.decode();
      const dnd = document.getElementById('dnd');
      const dndBox = dnd.getBoundingClientRect();
      let dndRimW = 0;
      if (dndBox.width > 0) {
        const dndRimBg = dnd.querySelector('.lg-shadow-layer')?.style.backgroundImage || '';
        const rimUrl = dndRimBg.slice(4, -1).replace(/^"|"$/g, '');
        const img = new Image(); img.src = rimUrl; await img.decode();
        dndRimW = img.width;
      }
      return { topBottom: top.bottom, clkTop: clk.top, shW: shBox.width, shRimW: shImg.width, dndW: dndBox.width, dndRimW };
    });
    assert.ok(layoutInfo.clkTop >= layoutInfo.topBottom - 2, 'clock must sit cleanly below topline');
    assert.ok(layoutInfo.shRimW >= layoutInfo.shW * 1.8, 'sh-left rim must be supersampled (>=2x)');
    if (layoutInfo.dndW > 0) assert.ok(layoutInfo.dndRimW >= layoutInfo.dndW * 1.8, 'dnd rim must be supersampled (>=2x)');
    await page.evaluate(()=>Clock.schedule(true));
    await page.waitForTimeout(600);
    assert.equal(await page.locator('#clock-inner').evaluate(el=>el.style.backgroundImage),before.rim,'unchanged rim is reused');
    const tickCheck = await page.evaluate(async () => {
      const glass = document.getElementById('clock-glass');
      const glow = document.getElementById('clock-glow');
      const beforeKey = glow.dataset.painted;
      let minGlassOp = 1, minGlowOp = 1, sawEmptyMask = false, sawSwapping = false;
      let active = true;
      const sample = () => {
        if (!active) return;
        const gOp = parseFloat(getComputedStyle(glass).opacity || '1');
        const glOp = parseFloat(getComputedStyle(glow).opacity || '1');
        if (gOp < minGlassOp) minGlassOp = gOp;
        if (glOp < minGlowOp) minGlowOp = glOp;
        const m = glass.style.webkitMaskImage || glass.style.maskImage || '';
        if (!m || m === 'none') sawEmptyMask = true;
        if (document.body.classList.contains('swapping')) sawSwapping = true;
        requestAnimationFrame(sample);
      };
      requestAnimationFrame(sample);
      window.__advanceClockMs(60 * 1000);
      Clock.schedule(false);
      await new Promise((res) => {
        const deadline = performance.now() + 3000;
        const wait = () => {
          if (glow.dataset.painted !== beforeKey || performance.now() > deadline) { res(); return; }
          setTimeout(wait, 25);
        };
        wait();
      });
      await new Promise((r) => setTimeout(r, 120));
      active = false;
      return { beforeKey, afterKey: glow.dataset.painted, minGlassOp, minGlowOp, sawEmptyMask, sawSwapping };
    });
    assert.equal(tickCheck.beforeKey, '18:16', 'initial minute is 18:16');
    assert.equal(tickCheck.afterKey, '18:17', 'minute advances cleanly to 18:17');
    assert.ok(tickCheck.minGlassOp >= 0.99 && tickCheck.minGlowOp >= 0.99, 'minute tick must never fade or hide clock digits');
    assert.equal(tickCheck.sawEmptyMask, false, 'minute tick must never drop glyph mask');
    assert.equal(tickCheck.sawSwapping, false, 'minute tick must never disable backdrop refraction');
    await page.setViewportSize({width:height,height:width});
    await page.waitForTimeout(1100);
    assert.deepEqual(await page.evaluate(()=>({sx:CONFIG.clock.sx,sy:CONFIG.clock.sy})),{sx:before.sx,sy:before.sy},'rotation preserves glyph proportions');
    await page.setViewportSize({width,height});
    await page.waitForTimeout(1100);
    await page.evaluate(()=>Editor.open());
    assert.equal(await page.locator('#edit').evaluate(el=>el.classList.contains('open')),true);
    const box=await page.locator('#edit').boundingBox();
    assert.ok(box.x>=-1 && box.x+box.width<=width+1);
    await page.locator('#clock-fmt [data-fmt="12"]').click();
    assert.equal(await page.evaluate(()=>state.hour24),false,'12h format button sets hour24=false');
    await page.locator('#clock-fmt [data-fmt="24"]').click();
    assert.equal(await page.evaluate(()=>state.hour24),true,'24h format button sets hour24=true');
    const preY = (await page.locator('#clock-glass').boundingBox()).y;
    await page.locator('#clock-y').fill('36');
    await page.locator('#clock-y').dispatchEvent('input');
    await page.waitForTimeout(260);
    const postY = (await page.locator('#clock-glass').boundingBox()).y;
    assert.ok(postY > preY + 10, 'clock-y slider moves clock vertically');
    await page.locator('#clock-y').fill('0');
    await page.locator('#clock-y').dispatchEvent('input');
    await page.waitForTimeout(220);
    await page.locator('#clock-opacity').fill('0');
    await page.locator('#clock-opacity').dispatchEvent('input');
    const glassZero = await page.evaluate(() => ({
      tint: document.documentElement.style.getPropertyValue('--clock-tint'),
      shOp: document.documentElement.style.getPropertyValue('--clock-shadow-op'),
      innerBg: document.getElementById('clock-inner').style.backgroundImage
    }));
    assert.equal(glassZero.tint, 'transparent', '0% opacity renders pure liquid glass');
    assert.equal(glassZero.shOp, '0', '0% opacity disables flat shadow tint');
    assert.ok(glassZero.innerBg.startsWith('url('), 'inner rim stays visible at 0% opacity');
    await page.locator('#colors [data-color="#000000"]').click();
    await page.locator('#clock-opacity').fill('100');
    await page.locator('#clock-opacity').dispatchEvent('input');
    const solidBlack = await page.evaluate(() => ({
      tint: document.documentElement.style.getPropertyValue('--clock-tint'),
      innerBg: document.getElementById('clock-inner').style.backgroundImage,
      innerOp: parseFloat(document.getElementById('clock-inner').style.opacity || '0'),
      subjZ: parseInt(getComputedStyle(document.getElementById('subject')).zIndex || '0', 10),
      clkZ: parseInt(getComputedStyle(document.getElementById('clock-layer')).zIndex || '0', 10)
    }));
    assert.equal(solidBlack.tint.replace(/\s+/g, ''), 'rgb(0,0,0)', '100% black fills clock solid black');
    assert.ok(solidBlack.innerBg.startsWith('url(') && solidBlack.innerOp > 0, 'white inner rim shadow remains on solid black');
    assert.ok(solidBlack.subjZ > solidBlack.clkZ, 'subject cutout layer sits above clock layer for depth');
    await page.locator('#colors [data-color="auto"]').click();
    await page.locator('#clock-opacity').fill('22');
    await page.locator('#clock-opacity').dispatchEvent('input');
    if (width === 412) {
      await page.evaluate(() => { Editor.close(); Clock.openResize(); });
      await page.waitForTimeout(200);
      const se = await page.locator('#clock-frame .h.se').boundingBox();
      const startSy = await page.evaluate(() => CONFIG.clock.sy);
      await page.mouse.move(se.x + se.width / 2, se.y + se.height / 2);
      await page.mouse.down();
      await page.mouse.move(se.x + 65, se.y + 180, { steps: 8 });
      await page.mouse.up();
      await page.waitForTimeout(600);
      const afterDrag = await page.evaluate(() => ({ size: CONFIG.clock.size, sy: CONFIG.clock.sy }));
      assert.ok(afterDrag.size > 172 && afterDrag.sy > startSy + 0.15, 'free corner drag increases both width size and vertical stretch');
      await page.evaluate(() => { document.getElementById('clock-frame-reset').click(); Clock.closeResize(); Editor.open(); });
      await page.waitForTimeout(500);
    }
    await page.locator('#clock-size').fill('420');
    await page.locator('#clock-size').dispatchEvent('input');
    await page.waitForTimeout(1200);
    assert.equal(await page.evaluate(()=>CONFIG.clock.size),420);
    const enlarged=await page.locator('#clock-glass').boundingBox();
    assert.ok(enlarged.width<=before.screen+1,'enlarged clock stays in bounds');
    if (width===412) assert.ok(enlarged.width>before.width || enlarged.height>before.quality.textH,'size control really enlarges the clock');
    const [dl] = await Promise.all([
      page.waitForEvent('download'),
      page.locator('#perf-download').click()
    ]);
    assert.match(dl.suggestedFilename(), /^oclock-perf-.*\.json$/);
    const dlPath = await dl.path();
    const perfJson = JSON.parse(readFileSync(dlPath, 'utf8'));
    assert.equal(perfJson.schema, 'oclock.liquidglass.perf.v1');
    assert.ok(perfJson.summary.fps.totalFrames > 0, 'PerfMonitor records continuous frames');
    assert.ok(perfJson.summary.subsystems.clockTickSwap?.count >= 1, 'PerfMonitor records minute tick swap timing');
    assert.ok(Array.isArray(perfJson.topConsumers) && perfJson.topConsumers.length > 0, 'PerfMonitor ranks top consumers');
    assert.equal(await page.evaluate(()=>window.appBack()),true);
    assert.equal(await page.evaluate(()=>window.appBack()),false);
    await page.locator('#sh-left').click();
    await page.waitForTimeout(600);
    await page.evaluate(()=>window.appBack());
    assert.deepEqual(errors,[]);
    console.log(`PASS ${width}x${height} reduced-motion=${reducedMotion}`);
    await page.close();
  }
} catch(error) {
  console.error('::error::'+String(error).replaceAll('\n','%0A'));
  throw error;
} finally { await browser.close(); }
