(function () {
    var root = document.body;
    var width = window.innerWidth || document.documentElement.clientWidth;
    var height = window.innerHeight || document.documentElement.clientHeight;
    var scroll = window.scrollY || document.documentElement.scrollTop || 0;
    var cap = 1600, probes = 0, visited = 0, truncated = false;
    var output = [], length = 0;
    var clips = new WeakMap();

    // Intersect the viewport with every clipping ancestor, including nested scrollers.
    function clipFor(el) {
        if (!el || el === document.documentElement) return {left: 0, top: 0, right: width, bottom: height};
        if (clips.has(el)) return clips.get(el);
        var parent = clipFor(el.parentElement), style = getComputedStyle(el), clip = null;
        if (parent && !/^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA)$/.test(el.tagName) &&
            style.display !== 'none' && style.visibility !== 'hidden' && style.visibility !== 'collapse' &&
            parseFloat(style.opacity || '1') !== 0 && !el.hidden && el.getAttribute('aria-hidden') !== 'true') {
            clip = {left: parent.left, top: parent.top, right: parent.right, bottom: parent.bottom};
            var bounds = el.getBoundingClientRect();
            if (/hidden|clip|auto|scroll/.test(style.overflowX)) {
                clip.left = Math.max(clip.left, bounds.left + el.clientLeft);
                clip.right = Math.min(clip.right, bounds.left + el.clientLeft + el.clientWidth);
            }
            if (/hidden|clip|auto|scroll/.test(style.overflowY)) {
                clip.top = Math.max(clip.top, bounds.top + el.clientTop);
                clip.bottom = Math.min(clip.bottom, bounds.top + el.clientTop + el.clientHeight);
            }
            if (clip.right <= clip.left || clip.bottom <= clip.top) clip = null;
        }
        clips.set(el, clip);
        return clip;
    }

    function intersects(rect, clip) {
        return rect.width > 0 && rect.height > 0 && rect.right > clip.left && rect.left < clip.right &&
            rect.bottom > clip.top && rect.top < clip.bottom;
    }
    function inside(rect, clip) {
        return rect.left >= clip.left && rect.right <= clip.right && rect.top >= clip.top && rect.bottom <= clip.bottom;
    }

    // A single text node can span the entire article. Split only mixed visible/hidden ranges.
    function visibleParts(node, clip) {
        var range = document.createRange(), spans = [];
        function scan(start, end) {
            if (start >= end || probes >= 12000) { if (probes >= 12000) truncated = true; return; }
            probes++;
            range.setStart(node, start); range.setEnd(node, end);
            var rects = Array.from(range.getClientRects());
            if (!rects.some(function (r) { return intersects(r, clip); })) return;
            if (end - start === 1 || rects.every(function (r) { return inside(r, clip); })) {
                var last = spans[spans.length - 1];
                if (last && last[1] === start) last[1] = end;
                else spans.push([start, end]);
                return;
            }
            var mid = start + Math.floor((end - start) / 2);
            scan(start, mid); scan(mid, end);
        }
        scan(0, node.length);
        return spans.map(function (span) { return node.data.slice(span[0], span[1]); }).join('');
    }

    if (root) {
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        var node;
        while ((node = walker.nextNode())) {
            if (++visited > 10000 || probes >= 12000 || length >= cap) { truncated = true; break; }
            if (!node.data.trim()) continue;
            var clip = clipFor(node.parentElement);
            if (!clip) continue;
            var text = visibleParts(node, clip).replace(/\s+/g, ' ').trim();
            if (!text) continue;
            var available = cap - length - (output.length ? 1 : 0);
            if (text.length > available) { text = text.slice(0, available); truncated = true; }
            if (text) { output.push(text); length += text.length + (output.length > 1 ? 1 : 0); }
        }
    }
    return JSON.stringify({scrollY: Math.round(scroll), viewportHeight: Math.round(height),
        scanTop: Math.round(scroll), scanBottom: Math.round(scroll + height), text: output.join('\n'), truncated: truncated});
})();
