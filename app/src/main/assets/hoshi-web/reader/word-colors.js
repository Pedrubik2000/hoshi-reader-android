// kumapie word colours: kumapie splits the chapter text into words and answers [start, length, state]
// runs (UTF-16 offsets into the reader's text nodes, furigana skipped); they are painted with CSS highlights, so the
// DOM, raw offsets and layout stay untouched.
window.hoshiWordColors = {
  runs: null,
  request: function() {
    if (!window.HoshiWordColors || !window.hoshiReader) return;
    var text = '';
    var walker = window.hoshiReader.createWalker();
    var node;
    while ((node = walker.nextNode())) text += node.textContent;
    this.text = text;
    window.HoshiWordColors.request(text);
  },
  apply: function(text, runs) {
    if (text !== this.text) return; // an older page's answer
    this.runs = runs;
    this.paint();
    this.splitNext();
  },
  // A character's face right after their name, in the line (kumapie's character dictionary; user, 2026-11-14: in the
  // line, not beside it). A run's offset is where the name ends. The picture takes one letter's room; the text's
  // offsets are unchanged (an image has no text).
  applyFaces: function(text, faces) {
    if (text !== this.text || this.facesDone || !faces.runs.length) return;
    this.facesDone = true;
    var nodes = [];
    var starts = [];
    var cursor = 0;
    var walker = window.hoshiReader.createWalker();
    var node;
    while ((node = walker.nextNode())) {
      nodes.push(node);
      starts.push(cursor);
      cursor += node.textContent.length;
    }
    // Last name first: splitting a text node leaves the offsets before it as they were.
    var n = nodes.length - 1;
    for (var i = faces.runs.length - 1; i >= 0; i--) {
      var start = faces.runs[i][0];
      while (n > 0 && starts[n] > start) n--;
      var at = start - starts[n];
      if (at < 0 || at > nodes[n].textContent.length) continue; // at the very end: after the node
      var rest = at > 0 ? nodes[n].splitText(at) : nodes[n];
      var span = document.createElement('span');
      span.className = 'hoshi-face';
      var img = document.createElement('img');
      img.src = faces.pictures[faces.runs[i][1]];
      span.appendChild(img);
      rest.parentNode.insertBefore(span, rest);
    }
    window.hoshiReader.buildNodeOffsets(); // the text nodes changed (this repaints the colours too)
  },
  // The next chapter's text, read as request() reads this one, goes to kumapie to be split while this one is read.
  splitNext: function() {
    if (this.splitAhead || !window.HoshiWordColors.next) return;
    this.splitAhead = true;
    var href = window.HoshiWordColors.next();
    if (!href) return;
    fetch('https://appassets.androidplatform.net/epub/' + href).then(function(r) { return r.text(); }).then(function(html) {
      var doc = new DOMParser().parseFromString(html, 'application/xhtml+xml');
      if (doc.getElementsByTagName('parsererror').length) doc = new DOMParser().parseFromString(html, 'text/html');
      if (!doc.body) return;
      var text = '';
      var walker = window.hoshiReader.createWalker(doc.body);
      var node;
      while ((node = walker.nextNode())) text += node.textContent;
      if (text) window.HoshiWordColors.split(text);
    }).catch(function() {});
  },
  paint: function() {
    if (!this.runs || typeof CSS === 'undefined' || !CSS.highlights || typeof Highlight === 'undefined') return;
    var byState = { u: new Highlight(), d: new Highlight(), b: new Highlight() };
    var nodes = [];
    var starts = [];
    var cursor = 0;
    var walker = window.hoshiReader.createWalker();
    var node;
    while ((node = walker.nextNode())) {
      nodes.push(node);
      starts.push(cursor);
      cursor += node.textContent.length;
    }
    var n = 0;
    for (var i = 0; i < this.runs.length; i++) {
      var start = this.runs[i][0];
      var end = start + this.runs[i][1];
      var highlight = byState[this.runs[i][2]];
      if (!highlight) continue;
      while (n + 1 < nodes.length && starts[n + 1] <= start) n++;
      // A word can span text nodes (ruby bases): one range per node.
      for (var k = n; k < nodes.length && starts[k] < end; k++) {
        var from = Math.max(start, starts[k]) - starts[k];
        var to = Math.min(end, starts[k] + nodes[k].textContent.length) - starts[k];
        if (to <= from) continue;
        var range = document.createRange();
        range.setStart(nodes[k], from);
        range.setEnd(nodes[k], to);
        highlight.add(range);
      }
    }
    CSS.highlights.set('hoshi-word-u', byState.u);
    CSS.highlights.set('hoshi-word-d', byState.d);
    CSS.highlights.set('hoshi-word-b', byState.b);
  },
  install: function(dark) {
    var style = document.createElement('style');
    style.textContent = dark
      ? '::highlight(hoshi-word-u){color:#FF6B6B}::highlight(hoshi-word-d){color:#7CC47F}::highlight(hoshi-word-b){color:#6FA8FF}'
      : '::highlight(hoshi-word-u){color:#D32F2F}::highlight(hoshi-word-d){color:#2E7D32}::highlight(hoshi-word-b){color:#1565C0}';
    style.textContent += '.hoshi-face{display:inline-block!important;width:1em!important;height:1em!important;margin:0!important;'
      + 'padding:0!important;vertical-align:middle!important;line-height:0!important}'
      + '.hoshi-face>img{display:block!important;width:1em!important;height:1em!important;max-width:none!important;'
      + 'min-width:0!important;margin:0!important;border-radius:50%;object-fit:cover;object-position:top;pointer-events:none}';
    document.head.appendChild(style);
    // Highlight wrapping moves text nodes and breaks live ranges: repaint after each rebuild (the text is unchanged).
    var self = this;
    var rebuild = window.hoshiReader.buildNodeOffsets;
    window.hoshiReader.buildNodeOffsets = function() {
      var result = rebuild.apply(this, arguments);
      self.paint();
      return result;
    };
    this.request();
  }
};
