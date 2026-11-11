/* Shared by the Chromium extractor and the isolated Gecko extension. */
async function castorPlayer(root, toPage, request) {
  const limit = 4 * 1024 * 1024;
  function http(value) {
    if (typeof value !== 'string' || value.length > 8192) return false;
    try {
      const url = new URL(value);
      return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password;
    } catch (_) { return false; }
  }
  function players() {
    const values = [root.hls, root.player?.hls, root.player?.hlsjs];
    try {
      // Inspect data properties only: finding a player must not invoke arbitrary window getters.
      const descriptors = Object.getOwnPropertyDescriptors(root);
      for (const descriptor of Object.values(descriptors).slice(0, 1600)) {
        if (descriptor.value && typeof descriptor.value === 'object') values.push(descriptor.value);
      }
    } catch (_) {}
    try {
      if (typeof root.jwplayer === 'function') {
        const provider = root.jwplayer()?.getProvider?.();
        values.push(provider?.hlsjs, provider?.hls);
      }
    } catch (_) {}
    return [...new Set(values)].filter(value => {
      try {
        const loader = value?.config?.pLoader || value?.config?.loader;
        const defaults = value?.constructor?.DefaultConfig;
        const standard = defaults?.pLoader || defaults?.loader;
        return typeof loader === 'function' && Array.isArray(value.levels) &&
          http(value.url) && loader !== standard;
      } catch (_) { return false; }
    });
  }
  function documents(player) {
    const result = [];
    for (const level of player.levels.slice(0, 100)) {
      const details = level.details;
      if (http(details?.url) && typeof details.m3u8 === 'string' &&
          details.m3u8.length <= limit && details.m3u8.trimStart().startsWith('#EXTM3U')) {
        result.push({source: player.url, url: details.url});
      }
    }
    return result;
  }
  const available = players();
  if (request.operation === 'discover') {
    return available.flatMap(documents).slice(0, 100);
  }
  if (request.operation === 'suspend') {
    for (const player of available) {
      player.stopLoad?.();
      player.media?.pause();
    }
    return [];
  }
  if (request.operation !== 'read' || !http(request.url) || !http(request.source)) return null;
  const matches = available.filter(player => player.url === request.source);
  if (matches.length !== 1) return null;
  const player = matches[0];
  const Loader = player.config.pLoader || player.config.loader;
  // The retained page supplies its loader and access state; native playback owns the media.
  player.stopLoad?.();
  player.media?.pause();
  return new Promise((resolve, reject) => {
    let loader;
    let completed = false;
    const timer = setTimeout(() => finish(new Error('Player playlist request timed out')), 15000);
    function finish(error, document) {
      if (completed) return;
      completed = true;
      clearTimeout(timer);
      try { loader?.abort?.(); } catch (_) {}
      try { loader?.destroy?.(); } catch (_) {}
      if (error) reject(error); else resolve(document);
    }
    try {
      loader = new Loader(player.config);
      const context = {
        url: request.url,
        type: request.url === player.url ? 'manifest' : 'level',
        responseType: 'text',
        level: 0,
        id: 0,
      };
      const policy = player.config.playlistLoadPolicy?.default;
      const config = {timeout: 14000, maxRetry: 0, retryDelay: 0, maxRetryDelay: 0};
      if (policy) config.loadPolicy = policy;
      const callbacks = {
        onSuccess(response) {
          const body = response?.data;
          const url = response?.url || request.url;
          if (typeof body !== 'string' || body.length > limit ||
              !body.trimStart().startsWith('#EXTM3U') || !http(url)) {
            finish(new Error('Player returned an invalid playlist'));
            return;
          }
          finish(null, {url, body});
        },
        onError(error) { finish(new Error(`Player playlist request failed (${Number(error?.code) || 0})`)); },
        onTimeout() { finish(new Error('Player playlist request timed out')); },
        onAbort() { finish(new Error('Player playlist request was cancelled')); },
      };
      loader.load(toPage(context), toPage(config), toPage(callbacks));
    } catch (_) { finish(new Error('Player playlist loader failed')); }
  });
}
