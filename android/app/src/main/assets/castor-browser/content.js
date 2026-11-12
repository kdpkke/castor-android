/* Runs in the extension's isolated world; no window messaging or page-callable bridge. */
const native = window === window.top ? browser.runtime.connectNative('castor') : null;
// When the browser created this document.
const created = performance.timeOrigin;
// How often this document came back from the back/forward cache.
let returns = 0;
// The name of this document while it is the one in its frame. A document that leaves and comes back has
// another name afterwards, so the same name seen twice means it was there all the time in between.
function identity() {
  return created + '/' + returns;
}
// Videos are started and reported only while the app is extracting; otherwise the user controls
// playback and paused videos stay paused.
let extracting = false;
browser.runtime.onMessage.addListener(message => {
  // The background asks which document is in this frame before it offers a stream on a document's behalf.
  if (message.type === 'document') return Promise.resolve(identity());
  if (message.type === 'extracting') extracting = message.value === true;
  // A candidate built from a document is for the top-level document that was above it then, and no other.
  if (message.top !== undefined && message.top !== identity()) return;
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
  if (!['evaluate', 'player'].includes(message.type)) return;
  try {
    const value = await browser.runtime.sendMessage(message);
    const encoded = JSON.stringify(value) ?? 'null';
    native.postMessage({type: 'evaluation', id: message.id, value: new TextEncoder().encode(encoded).length <= 4 * 1024 * 1024 ? encoded : 'null'});
  } catch (_) { native.postMessage({type: 'evaluation', id: message.id, value: 'null'}); }
});
native?.onDisconnect.addListener(() => clearInterval(timer));
addEventListener('pagehide', () => clearInterval(timer), {once: true});
// Reports which addresses this document itself finished loading, as the browser's resource list names them. A
// page can neither write to that list nor reach this script. The background uses the report to recognise a
// stream that a service worker fetched for this document: such a request carries no tab.
const loaded = [];
let reporting = null;
const resources = new PerformanceObserver(list => {
  for (const entry of list.getEntries()) {
    if (loaded.length < 400 && /^https?:\/\//.test(entry.name) && entry.name.length <= 8192) {
      loaded.push({url: entry.name, started: entry.startTime, status: entry.responseStatus, mime: entry.contentType});
    }
  }
  if (reporting === null && loaded.length) {
    reporting = setTimeout(() => {
      reporting = null;
      browser.runtime.sendMessage({type: 'loaded', created, document: identity(), resources: loaded.splice(0)}).catch(() => {});
    }, 0);
  }
});
resources.observe({type: 'resource', buffered: true});
// A document that leaves stops reporting at once, so nothing it loaded is offered after it.
addEventListener('pagehide', () => {
  resources.disconnect();
  clearTimeout(reporting);
  reporting = null;
  loaded.length = 0;
  browser.runtime.sendMessage({type: 'unloaded', document: identity()}).catch(() => {});
});
// A document restored from the back/forward cache is alive again, under another name.
addEventListener('pageshow', event => {
  if (!event.persisted) return;
  returns++;
  resources.observe({type: 'resource'});
});
