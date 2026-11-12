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
  if (!['evaluate', 'player', 'autoplay'].includes(message.type)) return;
  try {
    const value = await browser.runtime.sendMessage(message);
    const encoded = JSON.stringify(value) ?? 'null';
    native.postMessage({type: 'evaluation', id: message.id, value: new TextEncoder().encode(encoded).length <= 4 * 1024 * 1024 ? encoded : 'null'});
  } catch (_) { native.postMessage({type: 'evaluation', id: message.id, value: 'null'}); }
});
// Starting a player that waits for a person. While the app looks for a stream that has not started, it asks each
// frame whether a video plays, and the top-level document where the largest player is, to tap its middle as a
// person would. Where it cannot tap, each frame clicks its own play control. A link is never clicked: it would
// take the page elsewhere.
const playControls = [
  '.vjs-big-play-button', '.jw-display-icon-display', '.jw-icon-display', '.plyr__control--overlaid',
  '.fp-play', '.mejs__overlay-button', '.shaka-play-button', '.bmpui-ui-hugeplaybacktogglebutton',
  '.play-wrapper', '[data-plyr="play"]', '[aria-label="play" i]', 'button[title="play" i]',
  '[class*="play-button" i]', '[class*="play_button" i]', '[class*="playbutton" i]', '[class*="btn-play" i]',
  '[id*="play-button" i]', '[id*="playbutton" i]',
];
function shown(element) {
  const box = element.getBoundingClientRect();
  if (box.width < 24 || box.height < 24) return null;
  const style = getComputedStyle(element);
  if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0) return null;
  return box;
}
function linked(element) {
  return !element || element.closest('a[href]') !== null;
}
function largestPlayer() {
  let best = null;
  let area = 0;
  for (const element of document.querySelectorAll('video, iframe, embed, object')) {
    const box = shown(element);
    if (!box || box.width < 160 || box.height < 90 || box.width * box.height <= area) continue;
    best = element;
    area = box.width * box.height;
  }
  return best;
}
function press(element) {
  const box = element.getBoundingClientRect();
  const at = {bubbles: true, cancelable: true, composed: true, clientX: box.left + box.width / 2, clientY: box.top + box.height / 2};
  for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup']) {
    element.dispatchEvent(type.startsWith('pointer') ? new PointerEvent(type, {...at, pointerType: 'touch', isPrimary: true}) : new MouseEvent(type, at));
  }
  element.click();
}
function castorAutoplay(operation) {
  const playing = Array.from(document.querySelectorAll('video')).some(video => !video.paused && !video.ended);
  if (operation === 'probe') {
    const report = {top: window === window.top, playing};
    if (!report.top) return report;
    const player = largestPlayer();
    if (!player) return report;
    player.scrollIntoView({block: 'center', inline: 'center'});
    const box = player.getBoundingClientRect();
    const x = box.left + box.width / 2;
    const y = box.top + box.height / 2;
    if (linked(document.elementFromPoint(x, y))) return report;
    // From the layout viewport to the view's pixels, through the pinch zoom and the screen density.
    const view = window.visualViewport;
    const scale = (view ? view.scale : 1) * window.devicePixelRatio;
    report.target = {x: (x - (view ? view.offsetLeft : 0)) * scale, y: (y - (view ? view.offsetTop : 0)) * scale};
    return report;
  }
  if (operation !== 'click' || playing) return null;
  for (const selector of playControls) {
    for (const control of document.querySelectorAll(selector)) {
      if (shown(control) && !linked(control)) {
        press(control);
        return {clicked: selector};
      }
    }
  }
  // A frame big enough to be a player: its middle, where its own play control usually sits.
  if (window !== window.top && innerWidth >= 160 && innerHeight >= 90) {
    const middle = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    if (middle && !linked(middle)) {
      press(middle);
      return {clicked: 'middle'};
    }
  }
  return null;
}
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
