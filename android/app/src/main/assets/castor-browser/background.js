/* App-owned extension. Websites have no access to the native messaging channel. */
const tabs = new Map();
const pending = new Map();
const limits = 1600;
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
browser.webRequest.onBeforeRequest.addListener(d => {
  if (d.tabId < 0) return;
  if (d.type === 'main_frame') tabs.set(d.tabId, {responses: new Map(), errors: new Map(), status: null});
}, {urls: ['http://*/*', 'https://*/*']});
browser.webRequest.onBeforeSendHeaders.addListener(d => {
  if (d.tabId < 0 || pending.size >= 3200) return;
  const headers = Object.fromEntries((d.requestHeaders || []).filter(h => h.value !== undefined).map(h => [h.name, h.value]));
  pending.set(d.requestId, {tabId: d.tabId, page: state(d.tabId), headers});
}, {urls: ['http://*/*', 'https://*/*']}, ['requestHeaders']);
browser.webRequest.onResponseStarted.addListener(d => {
  if (d.tabId < 0) return;
  const page = state(d.tabId);
  const request = pending.get(d.requestId);
  pending.delete(d.requestId);
  if (request && request.page !== page) return;
  if (d.type === 'main_frame') {
    page.status = {type: 'page', url: d.url, status: d.statusCode};
    send(d.tabId, page.status);
    // Empty/error documents may never run a content script. Report main HTTP failures directly.
    if (d.statusCode >= 400) browser.runtime.sendNativeMessage('castor_network', page.status).catch(() => {});
  }
  if (d.statusCode < 200 || d.statusCode >= 300 || page.responses.size >= limits) return;
  const mime = (d.responseHeaders || []).find(h => h.name.toLowerCase() === 'content-type')?.value || '';
  const response = {url: d.url, headers: request?.headers || {}, mime};
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
  for (const [id, request] of pending) if (request.tabId === tabId) pending.delete(id);
});
browser.tabs.onUpdated.addListener((tabId, changes) => {
  if (changes.url === 'about:blank') tabs.delete(tabId);
});
browser.runtime.onMessage.addListener((message, sender) => {
  if (sender.id !== browser.runtime.id || !sender.tab || sender.tab.id < 0) return;
  const page = state(sender.tab.id);
  if (message.type === 'attach') return Promise.resolve({status: page.status, responses: [...page.responses.values()], errors: [...page.errors.values()]});
  if (message.type === 'videos') {
    // A DOM src alone is insufficient: blocked advertisements can remain in the HTML.
    for (const url of (message.urls || []).slice(0, limits)) {
      if (page.responses.has(url)) send(sender.tab.id, candidate(page.responses.get(url), true));
    }
    return Promise.resolve();
  }
  if (message.type === 'evaluate' && sender.frameId === 0 && typeof message.code === 'string') {
    return browser.tabs.executeScript(sender.tab.id, {frameId: 0, code: message.code}).then(values => values[0]);
  }
});
