/* App-owned extension. Websites have no access to the native messaging channel. */
const tabs = new Map();
const pending = new Map();
const extracting = new Set();
const limits = 1600;
// What content.js reported of each document, by tab and frame: the addresses the document itself loaded.
const documents = new Map();
// Addresses a service worker fetched from the network a moment ago, with the worker's origin. Nothing else of
// such a request is kept.
const fetched = [];
// A document's load and a worker's fetch of one address belong together when at most this far apart (ms).
const span = 5000;
function state(tabId) {
  if (!tabs.has(tabId)) tabs.set(tabId, {responses: new Map(), errors: new Map(), status: null});
  return tabs.get(tabId);
}
function send(tabId, message) {
  browser.tabs.sendMessage(tabId, message, {frameId: 0}).catch(() => {});
}
function candidate(response, htmlVideo = false) {
  return {type: 'candidate', ...response, htmlVideo};
}
function origin(value) {
  try { return new URL(value).origin; } catch (_) { return null; }
}
function forget(tabId, frameId) {
  for (const key of [...documents.keys()]) {
    if (frameId === undefined ? key.startsWith(tabId + '|') : key === tabId + '|' + frameId) documents.delete(key);
  }
}
// Which document is in a frame right now: content.js answers with the document's name.
function current(tabId, frameId) {
  return browser.tabs.sendMessage(tabId, {type: 'document'}, {frameId});
}
// A request issued by a service worker has no tab, frame or document, and the worker is a program of its own
// between page and network: no single request can be tied to the document it serves. So nothing of it reaches
// a page, neither its request headers nor its response. It only confirms that a worker of the document's
// origin fetched an address the document itself loaded. The candidate is built from that document alone: the
// address in its own resource list and the headers that follow from the document's address. Headers a page
// script adds and cookies are therefore not replayed for these streams, and an address the worker rewrites or
// requests on its own is not captured.
//
// Such a candidate belongs to its document and to the page above it, not to the tab: it is never kept in the
// tab's record, where the next page would find it. It names the top-level document it is for, which is the
// only one that passes it on, and that document must be the one above the document that loaded the address.
// The two are asked one after the other, never together: first the top-level document, then the document
// that loaded the address, then the top-level document again. A document answers only while it is the one
// in its frame, and its name changes if it leaves and comes back. So when the top-level document gives the
// same name before and after, it was there all along, and the document that answered in between was part of
// its page. A document that a navigation is replacing can therefore give nothing to the page that replaces it.
async function offer(document, url, resource) {
  if (resource.offered) return;
  resource.offered = true;
  try {
    const top = await current(document.tabId, 0);
    if (typeof top !== 'string' || await current(document.tabId, document.frameId) !== document.identity || await current(document.tabId, 0) !== top) return;
    // The tab's own request for an address, with its real headers, always wins.
    if (state(document.tabId).responses.has(url)) return;
    const from = new URL(document.url);
    const same = from.origin === origin(url);
    const headers = {'User-Agent': navigator.userAgent, Referer: same ? from.origin + from.pathname + from.search : from.origin + '/'};
    if (!same) headers.Origin = from.origin;
    send(document.tabId, {...candidate({url, headers, mime: resource.mime, started: Math.round(document.created + resource.started)}), top});
  } catch (_) {
    // No document answered: nothing is offered. A later load of the address may try again.
    resource.offered = false;
  }
}
function workerFetched(d) {
  const from = origin(d.documentUrl || d.originUrl);
  if (!from || d.statusCode < 200 || d.statusCode >= 300) return;
  const now = Date.now();
  while (fetched.length && (fetched.length >= 400 || now - fetched[0].at > span)) fetched.shift();
  fetched.push({origin: from, url: d.url, at: now});
  for (const document of documents.values()) {
    const resource = document.resources.get(d.url);
    if (resource && now - resource.at <= span && origin(document.url) === from) offer(document, d.url, resource);
  }
}
// Each top-level navigation starts a fresh record; leaving a page for about:blank clears it too. The
// app also discards responses whose request started before its own navigation, so a late reset or
// an in-flight request from the previous document cannot leak into the next resolution.
browser.webRequest.onBeforeRequest.addListener(d => {
  if (d.tabId < 0) return;
  if (d.type === 'main_frame') {
    tabs.set(d.tabId, {responses: new Map(), errors: new Map(), status: null});
    forget(d.tabId);
  }
  // A frame that navigates replaces its document.
  if (d.type === 'sub_frame') forget(d.tabId, d.frameId);
}, {urls: ['http://*/*', 'https://*/*']});
browser.webRequest.onBeforeSendHeaders.addListener(d => {
  if (d.tabId < 0 || pending.size >= 3200) return;
  const headers = Object.fromEntries((d.requestHeaders || []).filter(h => h.value !== undefined).map(h => [h.name, h.value]));
  pending.set(d.requestId, {tabId: d.tabId, headers, started: d.timeStamp});
}, {urls: ['http://*/*', 'https://*/*']}, ['requestHeaders']);
browser.webRequest.onResponseStarted.addListener(d => {
  if (d.tabId < 0) {
    workerFetched(d);
    return;
  }
  const page = state(d.tabId);
  const request = pending.get(d.requestId);
  pending.delete(d.requestId);
  if (d.type === 'main_frame') {
    page.status = {type: 'page', url: d.url, status: d.statusCode};
    send(d.tabId, page.status);
    // Empty/error documents may never run a content script. Report main HTTP failures directly,
    // naming the tab so the app can tell its sessions apart.
    if (d.statusCode >= 400) browser.runtime.sendNativeMessage('castor_network', {...page.status, tabId: d.tabId}).catch(() => {});
  }
  if (d.statusCode < 200 || d.statusCode >= 300 || page.responses.size >= limits) return;
  const mime = (d.responseHeaders || []).find(h => h.name.toLowerCase() === 'content-type')?.value || '';
  // A response served from cache has no request headers; keep those of the earlier request.
  const response = {url: d.url, headers: request?.headers || page.responses.get(d.url)?.headers || {}, mime, started: request?.started ?? d.timeStamp};
  page.responses.set(d.url, response);
  // Both blockers run before a response exists. Cancelled ads cannot enter this path.
  send(d.tabId, candidate(response));
}, {urls: ['http://*/*', 'https://*/*']}, ['responseHeaders']);
browser.webRequest.onBeforeRedirect.addListener(d => pending.delete(d.requestId), {urls: ['<all_urls>']});
browser.webRequest.onErrorOccurred.addListener(d => {
  pending.delete(d.requestId);
  if (d.tabId >= 0) {
    const message = {type: 'requestError', url: d.url, error: d.error};
    if (state(d.tabId).errors.size < 100) state(d.tabId).errors.set(d.url, message);
    send(d.tabId, message);
  }
}, {urls: ['<all_urls>']});
browser.tabs.onRemoved.addListener(tabId => {
  tabs.delete(tabId);
  extracting.delete(tabId);
  forget(tabId);
  for (const [id, request] of pending) if (request.tabId === tabId) pending.delete(id);
});
browser.tabs.onUpdated.addListener((tabId, changes) => {
  if (changes.url === 'about:blank') {
    tabs.delete(tabId);
    forget(tabId);
  }
});
browser.runtime.onMessage.addListener((message, sender) => {
  if (sender.id !== browser.runtime.id || !sender.tab || sender.tab.id < 0) return;
  const tabId = sender.tab.id;
  if (message.type === 'loaded' || message.type === 'unloaded') {
    const key = tabId + '|' + sender.frameId;
    let document = documents.get(key);
    if (message.type === 'unloaded') {
      if (document && document.identity === message.document) documents.delete(key);
      return;
    }
    if (!/^https?:\/\//.test(sender.url || '') || !Number.isFinite(message.created) || !Array.isArray(message.resources)) return;
    if (typeof message.document !== 'string' || message.document.length > 64) return;
    // Another address or name in the same frame is another document; an older one never takes its place.
    if (document && message.created < document.created) return;
    if (!document || document.url !== sender.url || document.identity !== message.document) {
      if (!document && documents.size >= 400) return;
      document = {tabId, frameId: sender.frameId, url: sender.url, created: message.created, identity: message.document, resources: new Map()};
      documents.set(key, document);
    }
    const now = Date.now();
    for (const item of message.resources.slice(0, 400)) {
      if (!item || typeof item.url !== 'string' || item.url.length > 8192 || !/^https?:\/\//.test(item.url) || !Number.isFinite(item.started)) continue;
      // The list names failed loads too; a status of 0 is a response the document may not inspect.
      if (Number.isFinite(item.status) && item.status !== 0 && (item.status < 200 || item.status >= 300)) continue;
      document.resources.delete(item.url);
      if (document.resources.size >= limits) document.resources.delete(document.resources.keys().next().value);
      const resource = {at: now, started: item.started, mime: typeof item.mime === 'string' ? item.mime.slice(0, 200) : '', offered: false};
      document.resources.set(item.url, resource);
      if (fetched.some(seen => seen.url === item.url && seen.origin === origin(document.url) && now - seen.at <= span)) offer(document, item.url, resource);
    }
    return;
  }
  const page = state(tabId);
  if (message.type === 'attach') return Promise.resolve({tabId, status: page.status, responses: [...page.responses.values()], errors: [...page.errors.values()]});
  if (message.type === 'extracting?') return Promise.resolve(extracting.has(tabId));
  if (message.type === 'extracting' && sender.frameId === 0) {
    // Only the app, through the top-level frame, starts or ends extraction for every frame.
    if (message.value === true) extracting.add(tabId); else extracting.delete(tabId);
    browser.tabs.sendMessage(tabId, {type: 'extracting', value: message.value === true}).catch(() => {});
    return Promise.resolve();
  }
  if (message.type === 'videos') {
    // A DOM src alone is insufficient: blocked advertisements can remain in the HTML.
    for (const url of (message.urls || []).slice(0, limits)) {
      if (page.responses.has(url)) send(tabId, candidate(page.responses.get(url), true));
    }
    return Promise.resolve();
  }
  if (message.type === 'evaluate' && sender.frameId === 0 && typeof message.code === 'string') {
    return browser.tabs.executeScript(tabId, {frameId: 0, code: message.code}).then(values => values[0]);
  }
  if (message.type === 'player' && sender.frameId === 0 && ['discover', 'read', 'suspend'].includes(message.operation)) {
    const request = {operation: message.operation, source: message.source, url: message.url};
    const code = `typeof castorPlayer === 'function' ? castorPlayer(window.wrappedJSObject, value => cloneInto(value, window, {cloneFunctions: true}), ${JSON.stringify(request)}) : []`;
    return browser.tabs.executeScript(tabId, {allFrames: true, code}).then(values => {
      const documents = values.flat().filter(Boolean);
      if (message.operation !== 'discover') return documents;
      // A page-owned player becomes a source only after its manifest actually reached the browser.
      return documents.filter(document => [...page.responses.keys()].some(value => {
        try {
          const seen = new URL(value);
          return [document.source, document.url].some(value => {
            const target = new URL(value);
            return seen.origin === target.origin && seen.pathname === target.pathname;
          });
        } catch (_) { return false; }
      }));
    });
  }
});
