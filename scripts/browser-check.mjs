import { chromium as playwright } from '@playwright/test';
import assert from 'node:assert/strict';
const browser = await playwright.launch({headless:true});
try {
  for (const [width,height,reducedMotion] of [[360,640,'no-preference'],[412,915,'no-preference'],[320,568,'reduce'],[800,360,'no-preference']]) {
    const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true,reducedMotion});
    const errors=[]; page.on('pageerror',e=>errors.push(e.message));
    await page.route('https://api.open-meteo.com/**',route=>route.abort());
    await page.addInitScript(() => {
      Object.defineProperty(navigator,'deviceMemory',{get:()=>2});
      Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>2});
      const RealDate = Date;
      const baseFake = new RealDate(2026, 10, 25, 18, 16, 5, 0).getTime();
      const basePerf = performance.now();
      class FixedDate extends RealDate {
        constructor(...a) {
          super(...(a.length ? a : [baseFake + (performance.now() - basePerf)]));
        }
        static now() { return baseFake + (performance.now() - basePerf); }
      }
      window.Date = FixedDate;
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
      const dnd = document.getElementById('dnd');
      const dndBox = dnd.getBoundingClientRect();
      const dndRimBg = dnd.querySelector('.lg-shadow-layer')?.style.backgroundImage || '';
      const rimUrl = dndRimBg.slice(4, -1).replace(/^"|"$/g, '');
      const img = new Image(); img.src = rimUrl; await img.decode();
      return { topBottom: top.bottom, clkTop: clk.top, dndW: dndBox.width, rimW: img.width };
    });
    assert.ok(layoutInfo.clkTop >= layoutInfo.topBottom - 2, 'clock must sit cleanly below topline');
    assert.ok(layoutInfo.rimW >= layoutInfo.dndW * 1.8, 'dnd rim must be supersampled (>=2x)');
    await page.evaluate(()=>Clock.schedule(true));
    await page.waitForTimeout(600);
    assert.equal(await page.locator('#clock-inner').evaluate(el=>el.style.backgroundImage),before.rim,'unchanged rim is reused');
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
    await page.locator('#clock-size').fill('420');
    await page.locator('#clock-size').dispatchEvent('input');
    await page.waitForTimeout(1200);
    assert.equal(await page.evaluate(()=>CONFIG.clock.size),420);
    const enlarged=await page.locator('#clock-glass').boundingBox();
    assert.ok(enlarged.width<=before.screen+1,'enlarged clock stays in bounds');
    if (width===412) assert.ok(enlarged.width>before.width || enlarged.height>before.quality.textH,'size control really enlarges the clock');
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
