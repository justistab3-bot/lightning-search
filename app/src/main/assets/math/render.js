/*
 * 答案页的公式渲染。
 *
 * 设计取舍：Kotlin 侧已经先把 LaTeX 转成 HTML+CSS 兜底排版，这里再用 KaTeX
 * 重新渲染一遍并替换掉兜底结果。
 *
 * 好处是**双向容错**：
 *  - KaTeX 加载失败或被 CSP 拦住时，页面上仍是 Kotlin 渲染的公式，不是原始 LaTeX 源码
 *  - KaTeX 可用时，cases / matrix / 复杂上下标这些兜底覆盖不到的写法也能正确显示
 */
(function () {
  'use strict';

  var nodes = document.querySelectorAll('.math[data-tex]');
  var rendered = 0;

  function renderInto(el, tex, displayMode, throwOnError) {
    katex.render(tex, el, {
      throwOnError: throwOnError,
      displayMode: displayMode,
      // 输出 HTML 而不是 MathML：Android WebView 对 MathML 支持很差
      output: 'html',
      strict: false
    });
  }

  if (typeof katex !== 'undefined') {
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var tex = el.getAttribute('data-tex');
      if (!tex) continue;
      var displayMode = el.getAttribute('data-display') === '1';
      try {
        renderInto(el, tex, displayMode, true);
        el.setAttribute('data-math-engine', 'katex');
        rendered++;
      } catch (e) {
        // 整条公式里只要有一个 KaTeX 拿不准的命令就整条报错 —— 之前直接退回
        // Kotlin 兜底排版（覆盖面小得多），公式看起来就是“没解析”。
        // 现在降一级重试：渲染得动的部分照常排，出错的那一小截标红。
        try {
          renderInto(el, tex, displayMode, false);
          el.setAttribute('data-math-engine', 'katex-lenient');
          rendered++;
        } catch (e2) {
          // 这一条 KaTeX 完全渲染不了，保留 Kotlin 的兜底排版
          el.setAttribute('data-math-engine', 'fallback');
        }
      }
    }
  }

  document.documentElement.setAttribute(
    'data-math',
    typeof katex === 'undefined' ? 'fallback' : 'katex:' + rendered + '/' + nodes.length
  );
})();
