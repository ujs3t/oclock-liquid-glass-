import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import vm from 'node:vm';
const html = readFileSync('web/index.html','utf8');
test('all inline JavaScript parses', () => {
  for (const match of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) new vm.Script(match[1]);
  for (const name of ['mobile.js','mobile-init.js']) new vm.Script(readFileSync('web/'+name,'utf8'));
});
test('local fonts and mobile assets are packaged', () => {
  assert.ok(!html.includes('fonts.googleapis.com'));
  for (const name of ['mobile.js','mobile-init.js','mobile.css','fonts/inter-latin-400-normal.woff2','fonts/oswald-latin-400-normal.woff2','fonts/bebas-neue-latin-400-normal.woff2']) assert.ok(existsSync('web/'+name));
  assert.match(readFileSync('android/app/build.gradle','utf8'), /assets.srcDirs = \['\.\.\/\.\.\/web'\]/);
});
test('performance profiles cover low-end and reduced motion devices', () => {
  const code=readFileSync('web/mobile-init.js','utf8');
  for (const [memory,cores,reduced,expected] of [[2,8,false,true],[8,2,false,true],[8,8,true,true],[8,8,false,false]]) {
    let result;
    const context={window:{}, navigator:{deviceMemory:memory,hardwareConcurrency:cores},matchMedia:()=>({matches:reduced}),document:{documentElement:{classList:{toggle:(_,value)=>result=value}}}};
    vm.runInNewContext(code,context); assert.equal(result,expected);
  }
});
