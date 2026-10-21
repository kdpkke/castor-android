(function() {
  const frames = [];
  for (const f of document.querySelectorAll('iframe')) {
    if (!/^https?:\/\//i.test(f.src)) continue;
    const style = getComputedStyle(f);
    if (style.display === 'none' || style.visibility !== 'visible' || Number(style.opacity) === 0) continue;
    const r = f.getBoundingClientRect();
    if (r.width <= 100 || r.height <= 100) continue;
    frames.push({src: f.src, area: r.width * r.height});
  }
  frames.sort((a, b) => b.area - a.area);
  return frames.length ? frames.slice(0, 4) : null;
})()
