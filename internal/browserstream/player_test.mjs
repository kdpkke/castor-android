import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const script = await readFile(new URL('./assets/castor-browser/player.js', import.meta.url), 'utf8');

function fixture() {
  const calls = [];
  let generation = 0;
  class Loader {
    load(context, config, callbacks) {
      calls.push(context);
      const body = `#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nhttps://media.example/segment-${++generation}.ts\n`;
      queueMicrotask(() => callbacks.onSuccess({url: context.url, data: body}));
    }
    abort() {}
    destroy() {}
  }
  const hls = {
    url: 'https://media.example/index.m3u8',
    config: {loader: Loader},
    levels: [{details: {url: 'https://media.example/index.m3u8', m3u8: '#EXTM3U\n#EXTINF:6,\nhttps://media.example/initial.ts\n'}}],
    stopLoad() { calls.push('stop'); },
    media: {pause() { calls.push('pause'); }},
  };
  const context = vm.createContext({URL, setTimeout, clearTimeout});
  vm.runInContext(script, context);
  return {hls, calls, run: request => context.castorPlayer({hls}, value => value, request)};
}

test('reads fresh processed manifests through the player loader on every reload', async () => {
  const {hls, calls, run} = fixture();
  const found = await run({operation: 'discover'});
  assert.equal(found[0].url, hls.url);
  const request = {operation: 'read', source: hls.url, url: hls.url};
  assert.match((await run(request)).body, /segment-1\.ts/);
  assert.match((await run(request)).body, /segment-2\.ts/);
  assert.equal(calls.filter(call => call === 'pause').length, 2);
  assert.equal(calls.filter(call => typeof call === 'object')[0].type, 'manifest');
});

test('ordinary default loaders stay on the existing extraction path', async () => {
  const {hls, run} = fixture();
  hls.constructor = {DefaultConfig: hls.config};
  assert.equal((await run({operation: 'discover'})).length, 0);
});

test('discovers a player exposed under an arbitrary window data property', async () => {
  const {hls} = fixture();
  const context = vm.createContext({URL, setTimeout, clearTimeout});
  vm.runInContext(script, context);
  const root = {activeMediaInstance: hls};
  Object.defineProperty(root, 'unrelated', {get() { throw new Error('getter was invoked'); }});
  const found = await context.castorPlayer(root, value => value, {operation: 'discover'});
  assert.equal(found.length, 1);
  assert.equal(found[0].source, hls.url);
});

test('rejects oversized documents and unsafe URLs', async () => {
  const {hls, run} = fixture();
  hls.levels[0].details.m3u8 = '#EXTM3U' + ' '.repeat(4 * 1024 * 1024);
  assert.equal((await run({operation: 'discover'})).length, 0);
  assert.equal(await run({operation: 'read', source: hls.url, url: 'file:///private'}), null);
});
