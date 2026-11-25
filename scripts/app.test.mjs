import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import vm from 'node:vm';
const html = readFileSync('web/index.html','utf8');
test('all inline JavaScript parses', () => {
  for (const match of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) new vm.Script(match[1]);
  for (const name of ['mobile.js','mobile-init.js','native-glass.js']) new vm.Script(readFileSync('web/'+name,'utf8'));
});
test('local fonts and mobile assets are packaged', () => {
  assert.ok(!html.includes('fonts.googleapis.com'));
  for (const name of ['mobile.js','mobile-init.js','mobile.css','fonts/inter-latin-400-normal.woff2','fonts/oswald-latin-400-normal.woff2','fonts/bebas-neue-latin-400-normal.woff2']) assert.ok(existsSync('web/'+name));
  assert.match(readFileSync('android/app/build.gradle','utf8'), /assets.srcDirs = \['\.\.\/\.\.\/web'\]/);
});
test('hardware and reduced motion never downgrade glass quality', () => {
  const code=readFileSync('web/mobile-init.js','utf8');
  for (const [memory,cores,reduced] of [[2,2,false],[2,2,true],[8,8,false]]) {
    const context={window:{}, navigator:{deviceMemory:memory,hardwareConcurrency:cores},matchMedia:()=>({matches:reduced})};
    vm.runInNewContext(code,context); assert.equal(context.window.mobileLite,false);
  }
  assert.ok(!html.includes('window.mobileLite ? 1'));
  assert.ok(!html.includes('!window.mobileLite &&'));
  assert.ok(!readFileSync('web/mobile.css','utf8').includes('.lite '));
});
