package com.myAllVideoBrowser.util.site_adapters

/**
 * Rule34Video 过滤器面板的"打开状态"探针（站点定向、只读）。
 *
 * 约束（与 #25 约定一致）：
 * - 只读：只调用 `querySelectorAll` / `getComputedStyle` / `getBoundingClientRect` / 读属性，
 *   绝不写 DOM、不改 `style`、不调 `.click()`、不改站点状态；
 * - 只在 `location.hostname` 命中 `rule34video.com`（含子域）时工作，其它站点直接返回；
 * - 状态翻转时才过桥（边沿触发），不产生事件风暴；
 * - `MutationObserver` 负责"面板打开/关闭"这类 DOM 变化，1.5s 的兜底轮询负责我们没观察到的变化。
 *
 * 面板选择器优先级（按 #25 约定）：稳定属性 / `aria-expanded` / `hidden` / `display` /
 * 明确的 panel 容器关系 > class 名 > 文本内容；多个面板取 OR——任意一个可见且打开即抑制。
 *
 * 注意：`PANEL_SELECTORS` 定稿依据一次性只读探针（见 work.md）实测到的 Rule34Video 当前 DOM；
 * 不使用带随机 hash 的 class。
 */
object Rule34FilterPanelProbe {

    /** 与 [FilterPanelStateBridge] 的注册名一致。 */
    const val BRIDGE_NAME = "SurfSaveFilterPanel"

    /** 页面侧状态重新上报（切回标签/回到前台时由原生调用）。 */
    const val REEVALUATE_SCRIPT: String =
        "(function(){var p=window.__surfsaveFilterPanelProbe;" +
            "if(p&&p.evaluate){p.evaluate(true);}})();"

    val SCRIPT: String = """
        (function() {
          if (window.__surfsaveFilterPanelProbe) {
            return;
          }
          var bridge = window.$BRIDGE_NAME;
          if (!bridge) {
            return;
          }

          var SITE = 'rule34video.com';
          var host = (location.hostname || '').toLowerCase();
          if (host !== SITE && host.slice(-(SITE.length + 1)) !== '.' + SITE) {
            return;
          }

          // 稳定属性 / 状态属性优先；class 名兜底。多个面板取 OR。
          var PANEL_SELECTORS = [
            '[aria-expanded="true"][aria-controls]',
            '[role="dialog"][aria-modal="true"]',
            '[data-filter-panel]',
            '[class*="filters"][class*="open"]',
            '#filter',
            '.filters'
          ];

          // 兜底：明确的浮层容器（fixed/absolute + 高 z-index + 覆盖较大面积 + 自身可滚）。
          var USE_OVERLAY_FALLBACK = true;
          var MIN_OVERLAY_WIDTH = 200;
          var MIN_OVERLAY_HEIGHT = 200;
          var MIN_OVERLAY_Z_INDEX = 100;

          var active = false;
          var scheduled = false;

          function box(el) {
            return el.getBoundingClientRect();
          }

          function visible(el, minWidth, minHeight) {
            if (!el || el.nodeType !== 1 || !el.isConnected) {
              return false;
            }
            if (el.hasAttribute('hidden') || el.getAttribute('aria-hidden') === 'true') {
              return false;
            }
            var cs = getComputedStyle(el);
            if (cs.display === 'none' || cs.visibility === 'hidden') {
              return false;
            }
            if (parseFloat(cs.opacity || '1') < 0.05) {
              return false;
            }
            var r = box(el);
            return r.width >= minWidth && r.height >= minHeight;
          }

          function isOpenPanel(el) {
            if (!visible(el, 24, 24)) {
              return false;
            }
            // aria-expanded 明确为 false 的不算打开。
            return el.getAttribute('aria-expanded') !== 'false';
          }

          function selectorPanelOpen() {
            for (var i = 0; i < PANEL_SELECTORS.length; i++) {
              var nodes;
              try {
                nodes = document.querySelectorAll(PANEL_SELECTORS[i]);
              } catch (e) {
                continue;
              }
              for (var j = 0; j < nodes.length; j++) {
                if (isOpenPanel(nodes[j])) {
                  return true;
                }
              }
            }
            return false;
          }

          function overlayPanelOpen() {
            if (!USE_OVERLAY_FALLBACK) {
              return false;
            }
            var nodes;
            try {
              nodes = document.querySelectorAll('div,section,aside,form,ul');
            } catch (e) {
              return false;
            }
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i];
              var cs = getComputedStyle(el);
              if (cs.position !== 'fixed' && cs.position !== 'absolute') {
                continue;
              }
              var zIndex = parseInt(cs.zIndex, 10);
              if (!(zIndex >= MIN_OVERLAY_Z_INDEX)) {
                continue;
              }
              if (!visible(el, MIN_OVERLAY_WIDTH, MIN_OVERLAY_HEIGHT)) {
                continue;
              }
              if (el.scrollHeight - el.clientHeight < 2 && cs.overflowY !== 'auto'
                  && cs.overflowY !== 'scroll') {
                continue;
              }
              return true;
            }
            return false;
          }

          function anyPanelOpen() {
            return selectorPanelOpen() || overlayPanelOpen();
          }

          function report(force) {
            var next = anyPanelOpen();
            if (!force && next === active) {
              return;
            }
            active = next;
            try {
              bridge.setFilterPanelOpen(next);
            } catch (e) {
            }
          }

          function schedule() {
            if (scheduled) {
              return;
            }
            scheduled = true;
            setTimeout(function() {
              scheduled = false;
              report(false);
            }, 80);
          }

          try {
            new MutationObserver(schedule).observe(document.documentElement || document, {
              childList: true,
              subtree: true,
              attributes: true
            });
          } catch (e) {
          }

          window.addEventListener('pagehide', function() {
            active = false;
            try {
              bridge.setFilterPanelOpen(false);
            } catch (e) {
            }
          });

          // 兜底轮询：覆盖 MutationObserver 观察不到的状态变化（例如图片加载后改变布局）。
          setInterval(function() {
            report(false);
          }, 1500);

          window.__surfsaveFilterPanelProbe = {
            evaluate: function(force) {
              report(!!force);
            }
          };

          report(true);
        })();
    """.trimIndent()
}
