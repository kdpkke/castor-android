/* Runs in the extension's isolated world; no window messaging or page-callable bridge. */
const native = window === window.top ? browser.runtime.connectNative('castor') : null;
browser.runtime.onMessage.addListener(message => {
  if (native && ['candidate', 'page', 'requestError'].includes(message.type)) native.postMessage(message);
});
if (native) browser.runtime.sendMessage({type: 'attach'}).then(snapshot => {
  if (snapshot.status) native.postMessage(snapshot.status);
  for (const response of snapshot.responses) native.postMessage({type: 'candidate', ...response});
  for (const error of snapshot.errors) native.postMessage(error);
}).catch(() => {});
const timer = setInterval(async () => {
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
  if (window !== window.top || message.type !== 'evaluate') return;
  try {
    const value = await browser.runtime.sendMessage(message);
    native.postMessage({type: 'evaluation', id: message.id, value: JSON.stringify(value) ?? 'null'});
  } catch (_) { native.postMessage({type: 'evaluation', id: message.id, value: 'null'}); }
});
native?.onDisconnect.addListener(() => clearInterval(timer));
addEventListener('pagehide', () => clearInterval(timer), {once: true});
