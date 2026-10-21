/* Runs in the extension's isolated world; no window messaging or page-callable bridge. */
const native = window === window.top ? browser.runtime.connectNative('castor') : null;
// Videos are started and reported only while the app is extracting; otherwise the user controls
// playback and paused videos stay paused.
let extracting = false;
browser.runtime.onMessage.addListener(message => {
  if (message.type === 'extracting') extracting = message.value === true;
  if (native && ['candidate', 'page', 'requestError'].includes(message.type)) native.postMessage(message);
});
browser.runtime.sendMessage({type: 'extracting?'}).then(value => { extracting = value === true; }).catch(() => {});
if (native) browser.runtime.sendMessage({type: 'attach'}).then(snapshot => {
  native.postMessage({type: 'tab', id: snapshot.tabId});
  if (snapshot.status) native.postMessage(snapshot.status);
  for (const response of snapshot.responses) native.postMessage({type: 'candidate', ...response});
  for (const error of snapshot.errors) native.postMessage(error);
}).catch(() => {});
const timer = setInterval(async () => {
  if (!extracting) return;
  const urls = Array.from(document.querySelectorAll('video,video source')).map(video => {
    if (video.tagName === 'VIDEO') video.play().catch(() => {});
    return video.currentSrc || video.src;
  }).filter(url => /^https?:\/\//.test(url));
  if (!urls.length) return;
  try {
    await browser.runtime.sendMessage({type: 'videos', urls});
  } catch (_) {}
}, 500);
native?.onMessage.addListener(async message => {
  if (window !== window.top) return;
  if (message.type === 'extracting') {
    browser.runtime.sendMessage({type: 'extracting', value: message.value === true}).catch(() => {});
    return;
  }
  if (message.type !== 'evaluate') return;
  try {
    const value = await browser.runtime.sendMessage(message);
    native.postMessage({type: 'evaluation', id: message.id, value: JSON.stringify(value) ?? 'null'});
  } catch (_) { native.postMessage({type: 'evaluation', id: message.id, value: 'null'}); }
});
native?.onDisconnect.addListener(() => clearInterval(timer));
addEventListener('pagehide', () => clearInterval(timer), {once: true});
