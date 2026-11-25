import { chromium as playwright } from '@playwright/test';
import assert from 'node:assert/strict';
const browser = await playwright.launch({headless:true});
try {
  for (const [width,height,reducedMotion] of [[360,640,'no-preference'],[412,915,'no-preference'],[320,568,'reduce'],[800,360,'no-preference']]) {
    const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true,reducedMotion});
    const errors=[]; page.on('pageerror',e=>errors.push(e.message));
    await page.route('https://api.open-meteo.com/**',route=>route.abort());
    await page.goto('http://localhost:5173');
    await page.waitForTimeout(1800);
    await page.evaluate(()=>Editor.open());
    assert.equal(await page.locator('#edit').evaluate(el=>el.classList.contains('open')),true);
    const box=await page.locator('#edit').boundingBox();
    assert.ok(box.x>=-1 && box.x+box.width<=width+1);
    assert.equal(await page.evaluate(()=>window.appBack()),true);
    assert.equal(await page.evaluate(()=>window.appBack()),false);
    await page.locator('#sh-left').click();
    await page.waitForTimeout(600);
    await page.evaluate(()=>window.appBack());
    assert.deepEqual(errors,[]);
    console.log(`PASS ${width}x${height} reduced-motion=${reducedMotion}`);
    await page.close();
  }
} finally { await browser.close(); }
