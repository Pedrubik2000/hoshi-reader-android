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
