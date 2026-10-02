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

  if (typeof katex !== 'undefined') {
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var tex = el.getAttribute('data-tex');
      if (!tex) continue;
      try {
        katex.render(tex, el, {
          throwOnError: true,
          displayMode: false,
          // 输出 HTML 而不是 MathML：Android WebView 对 MathML 支持很差
          output: 'html',
          strict: false
        });
        el.setAttribute('data-math-engine', 'katex');
        rendered++;
      } catch (e) {
        // 这一条 KaTeX 渲染不了，保留 Kotlin 的兜底排版
        el.setAttribute('data-math-engine', 'fallback');
      }
    }
  }

  document.documentElement.setAttribute(
    'data-math',
    typeof katex === 'undefined' ? 'fallback' : 'katex:' + rendered + '/' + nodes.length
  );
})();
