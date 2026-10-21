(function() {
  const visible = e => {
    const s = e.ownerDocument.defaultView.getComputedStyle(e), r = e.getBoundingClientRect();
    return s.display !== 'none' && s.visibility === 'visible' && Number(s.opacity) !== 0 && r.width > 32 && r.height > 32;
  };
  const candidates = [];
  const scan = (doc, frames, depth) => {
    for (const e of doc.querySelectorAll('video')) {
      if (visible(e) && e.paused) candidates.push({e, frames});
    }
    if (frames.length === 0) {
      for (const e of doc.querySelectorAll('button, [role="button"]')) {
        if (!visible(e)) continue;
        const label = [e.getAttribute('aria-label'), e.getAttribute('title'), e.textContent].join(' ');
        if (/\b(play|start|watch)\b/i.test(label)) candidates.push({e, frames});
      }
    }
    if (depth >= 5) return;
    for (const frame of doc.querySelectorAll('iframe')) {
      if (!visible(frame)) continue;
      try {
        if (frame.contentDocument) scan(frame.contentDocument, [...frames, frame], depth + 1);
      } catch (_) { /* Cross-origin frames are visited by the navigation path. */ }
    }
  };
  scan(document, [], 0);
  candidates.sort((a, b) => {
    const ar = a.e.getBoundingClientRect(), br = b.e.getBoundingClientRect();
    return br.width * br.height - ar.width * ar.height;
  });
  const {e, frames} = candidates[0] || {};
  if (!e) return null;
  for (const frame of frames) frame.scrollIntoView({block: 'center', behavior: 'instant'});
  e.scrollIntoView({block: 'center', behavior: 'instant'});
  const r = e.getBoundingClientRect();
  let x = r.left + r.width / 2, y = r.top + r.height / 2;
  for (let i = frames.length - 1; i >= 0; i--) {
    const f = frames[i].getBoundingClientRect();
    x += f.left + frames[i].clientLeft;
    y += f.top + frames[i].clientTop;
  }
  return {x, y};
})()
