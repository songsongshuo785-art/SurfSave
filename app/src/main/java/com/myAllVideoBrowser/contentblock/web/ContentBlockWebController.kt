package com.myAllVideoBrowser.contentblock.web

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.myAllVideoBrowser.contentblock.ContentBlockEngineStatus
import com.myAllVideoBrowser.contentblock.ContentBlockManager
import com.myAllVideoBrowser.contentblock.CosmeticResources
import com.myAllVideoBrowser.contentblock.TrustedScriptletInvocation
import com.myAllVideoBrowser.contentblock.TrustedScriptletRegistry
import com.myAllVideoBrowser.util.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class ContentBlockWebController @Inject constructor(
    private val manager: ContentBlockManager,
    private val scriptletRegistry: TrustedScriptletRegistry
) {
    private var attachedWebView = WeakReference<WebView>(null)
    private var documentStartHandler: ScriptHandler? = null
    private var statusCollectionJob: Job? = null

    fun attach(webView: WebView) {
        detach()
        attachedWebView = WeakReference(webView)
        webView.addJavascriptInterface(
            ContentBlockJavaScriptBridge(manager, scriptletRegistry),
            BRIDGE_NAME
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStartHandler = runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, BOOTSTRAP_SCRIPT, setOf("*"))
            }.onFailure { error ->
                AppLogger.d(
                    "Content blocking document-start injection unavailable: " +
                        error.javaClass.simpleName
                )
            }.getOrNull()
        }
        var previousStatus = manager.state.value.status
        statusCollectionJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            manager.state.map { it.status }.distinctUntilChanged().collect { status ->
                val currentWebView = attachedWebView.get()
                when {
                    status == ContentBlockEngineStatus.DISABLED -> {
                        clearCurrentDocument(currentWebView)
                    }
                    status in COSMETIC_READY_STATUSES &&
                        previousStatus !in COSMETIC_READY_STATUSES -> {
                        forceRefreshCurrentDocument(currentWebView)
                    }
                }
                previousStatus = status
            }
        }
        webView.post {
            if (manager.state.value.status in COSMETIC_READY_STATUSES) {
                forceRefreshCurrentDocument(webView)
            } else if (!manager.isEnabled()) {
                clearCurrentDocument(webView)
            }
        }
    }

    fun injectCurrentDocument(webView: WebView?) {
        if (webView == null || attachedWebView.get() !== webView) return
        runCatching { webView.evaluateJavascript(BOOTSTRAP_SCRIPT, null) }
            .onFailure { error ->
                AppLogger.d(
                    "Content blocking page injection skipped: ${error.javaClass.simpleName}"
                )
            }
    }

    private fun forceRefreshCurrentDocument(webView: WebView?) {
        evaluatePageScript(webView, FORCE_REFRESH_SCRIPT)
    }

    private fun clearCurrentDocument(webView: WebView?) {
        evaluatePageScript(webView, CLEAR_SCRIPT)
    }

    private fun evaluatePageScript(webView: WebView?, script: String) {
        if (webView == null || attachedWebView.get() !== webView) return
        runCatching { webView.evaluateJavascript(script, null) }
            .onFailure { error ->
                AppLogger.d(
                    "Content blocking page refresh skipped: ${error.javaClass.simpleName}"
                )
            }
    }

    fun detach() {
        statusCollectionJob?.cancel()
        statusCollectionJob = null
        val webView = attachedWebView.get()
        if (documentStartHandler != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            runCatching { documentStartHandler?.remove() }
        }
        documentStartHandler = null
        runCatching { webView?.removeJavascriptInterface(BRIDGE_NAME) }
        attachedWebView.clear()
    }

    companion object {
        const val BRIDGE_NAME = "SurfSaveContentBlockBridge"
        private const val HIDDEN_ATTRIBUTE = "data-surfsave-content-block-hidden"
        private const val CONCEALED_ATTRIBUTE = "data-surfsave-content-block-concealed"
        private const val COMPACTED_ATTRIBUTE = "data-surfsave-content-block-compacted"
        private const val PROTECTED_ATTRIBUTE = "data-surfsave-content-block-protected"

        internal val BOOTSTRAP_SCRIPT = """
            (() => {
              'use strict';
              const bridge = window.$BRIDGE_NAME;
              if (!bridge || !location || !/^https?:$/.test(location.protocol)) return false;
              const pageUrl = location.href.split('#')[0];
              const documentToken = (() => {
                try {
                  const values = new Uint32Array(4);
                  crypto.getRandomValues(values);
                  return Array.from(values, (value) => value.toString(36)).join('-');
                } catch (_) {
                  return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
                }
              })();
              const prior = window.__surfSaveContentBlockState;
              if (prior && prior.url === pageUrl) return true;
              if (prior) {
                try { prior.dispose && prior.dispose(); } catch (_) {}
              }
              const oldStyle = document.getElementById('__surfsave_content_block_style');
              if (oldStyle) oldStyle.remove();

              let payload;
              try {
                payload = JSON.parse(bridge.bootstrap(pageUrl, documentToken));
              } catch (_) { return false; }
              if (!payload || payload.active !== true) return false;

              const selectors = new Set();
              const proceduralRules = Array.isArray(payload.proceduralRules)
                ? payload.proceduralRules
                : [];
              const state = {
                url: pageUrl,
                documentToken: documentToken,
                observer: null,
                timer: null,
                frame: null,
                dynamicRuns: 0,
                proceduralRuns: 0,
                proceduralCursor: 0,
                selectorScanRuns: 0,
                selectorScanRequested: true,
                selectorBatchCursor: 0,
                selectorBatches: [],
                pending: [],
                pendingSet: new WeakSet(),
                classes: new Set(),
                ids: new Set(),
                undo: [],
                touched: new WeakMap(),
                applyingUntil: 0,
                disposed: false,
                dispose: null
              };
              window.__surfSaveContentBlockState = state;
              const buildSelectorBatches = (items) => {
                const batches = [];
                let current = [];
                let currentLength = 0;
                for (const selector of items) {
                  if (typeof selector !== 'string' || selector.length === 0 ||
                      selector.includes('::')) continue;
                  if (current.length >= 24 || currentLength + selector.length > 8192) {
                    if (current.length) batches.push(current.join(','));
                    current = [];
                    currentLength = 0;
                  }
                  current.push(selector);
                  currentLength += selector.length + 1;
                }
                if (current.length) batches.push(current.join(','));
                return batches;
              };
              const ensureStyle = () => {
                let style = document.getElementById('__surfsave_content_block_style');
                if (!style) {
                  style = document.createElement('style');
                  style.id = '__surfsave_content_block_style';
                  const parent = document.head || document.documentElement;
                  if (!parent) return null;
                  parent.appendChild(style);
                }
                return style;
              };
              const renderStyle = () => {
                const style = ensureStyle();
                if (!style) return;
                const semanticExclusions =
                  ':not(html):not(body):not(main):not(nav):not(header)' +
                  ':not([role="main"]):not([role="navigation"]):not([role="tablist"])';
                const supportsIsSelector = typeof CSS !== 'undefined' && CSS.supports &&
                  CSS.supports('selector(:is(*))');
                const rules = Array.from(selectors)
                  .map((selector) => {
                    let guarded;
                    if (selector.includes('::')) {
                      return selector +
                        '{visibility:hidden!important;pointer-events:none!important;}';
                    } else if (supportsIsSelector) guarded = ':is(' + selector + ')' + semanticExclusions;
                    else if (selector.includes(',')) return '';
                    else guarded = selector + semanticExclusions;
                    return guarded + ':not([$PROTECTED_ATTRIBUTE="1"])' +
                      '{visibility:hidden!important;pointer-events:none!important;}';
                  })
                  .filter(Boolean);
                rules.push('[$CONCEALED_ATTRIBUTE="1"]{' +
                  'visibility:hidden!important;pointer-events:none!important;}');
                rules.push('[$HIDDEN_ATTRIBUTE="1"]{display:none!important;}');
                rules.push('[$COMPACTED_ATTRIBUTE="1"]{display:none!important;}');
                style.textContent = rules.join('\n');
              };
              const applySelectors = (items) => {
                if (!Array.isArray(items)) return;
                let changed = false;
                for (const selector of items) {
                  if (typeof selector === 'string' && selector.length > 0 &&
                      !selectors.has(selector)) {
                    selectors.add(selector);
                    changed = true;
                  }
                }
                renderStyle();
                if (changed) {
                  state.selectorBatches = buildSelectorBatches(Array.from(selectors));
                  state.selectorBatchCursor = 0;
                  state.selectorScanRequested = true;
                  if (state.scheduleClassification) state.scheduleClassification();
                }
              };
              applySelectors(payload.selectors);
              if (!document.getElementById('__surfsave_content_block_style')) {
                document.addEventListener('DOMContentLoaded', () => applySelectors([]), {
                  once: true
                });
              }

              if (Array.isArray(payload.actions)) {
                for (const action of payload.actions) {
                  if (!action || typeof action.argument !== 'string') continue;
                  if (action.name === 'remove-cookie') {
                    document.cookie = action.argument + '=; Max-Age=0; path=/';
                  } else if (action.name === 'set-local-storage') {
                    const split = action.argument.indexOf('=');
                    if (split > 0) {
                      try {
                        localStorage.setItem(
                          action.argument.slice(0, split),
                          action.argument.slice(split + 1)
                        );
                      } catch (_) {}
                    }
                  }
                }
              }

              const rememberUndo = (element, key, undo) => {
                if (!element || state.undo.length >= 2000) return false;
                let keys = state.touched.get(element);
                if (!keys) {
                  keys = new Set();
                  state.touched.set(element, keys);
                }
                if (keys.has(key)) return false;
                keys.add(key);
                state.undo.push(undo);
                return true;
              };
              const compileMatcher = (raw) => {
                if (typeof raw !== 'string' || raw.length === 0 || raw.length > 1024) return null;
                if (raw[0] === '/') {
                  const end = raw.lastIndexOf('/');
                  if (end > 0) {
                    const source = raw.slice(1, end);
                    const flags = raw.slice(end + 1);
                    if (source.length > 256 || !/^[imsu]*$/.test(flags)) return null;
                    if (/(\([^)]*[+*][^)]*\))[+*{]/.test(source)) return null;
                    try {
                      const regex = new RegExp(source, flags);
                      return (value) => regex.test(String(value).slice(0, 8192));
                    } catch (_) { return null; }
                  }
                }
                return (value) => String(value).slice(0, 8192).includes(raw);
              };
              const limitedElements = (items, limit) => {
                const output = [];
                const seen = new Set();
                for (const item of items) {
                  if (!item || item.nodeType !== Node.ELEMENT_NODE || seen.has(item)) continue;
                  seen.add(item);
                  output.push(item);
                  if (output.length >= limit) break;
                }
                return output;
              };
              const protectedSelector =
                'html,body,main,nav,header,[role="main"],[role="navigation"],' +
                '[role="tablist"],[role="toolbar"],[role="menubar"]';
              const interactiveSelector =
                'a[href],button,input,select,[role="button"],[role="tab"],[role="menuitem"]';
              const structuralDisplays = new Set([
                'table', 'inline-table', 'table-row', 'table-cell',
                'flex', 'inline-flex', 'grid', 'inline-grid'
              ]);
              const layoutStyleProperties = new Set([
                'display', 'overflow', 'overflow-x', 'overflow-y', 'position',
                'top', 'right', 'bottom', 'left', 'width', 'height', 'min-width',
                'min-height', 'max-width', 'max-height', 'margin', 'margin-top',
                'margin-right', 'margin-bottom', 'margin-left', 'padding',
                'padding-top', 'padding-right', 'padding-bottom', 'padding-left'
              ]);
              const hasAdvertisingHint = (element) => {
                if (!element || !element.getAttribute) return false;
                const signals = [
                  element.id || '',
                  typeof element.className === 'string' ? element.className : '',
                  element.getAttribute('role') || '',
                  element.getAttribute('aria-label') || '',
                  element.getAttribute('data-testid') || '',
                  element.getAttribute('data-ad') || '',
                  element.getAttribute('data-event') || '',
                  element.getAttribute('data-ad-slot') || '',
                  element.getAttribute('href') || '',
                  element.getAttribute('src') || ''
                ].join(' ').toLowerCase();
                return /(?:^|[^a-z0-9])(ad|ads|advert|advertisement|banner|paid|preroll|sponsor|sponsored|promo|trafficjunky|doubleclick)(?:[^a-z0-9]|$)/
                  .test(signals);
              };
              const hasVisibleSafeContent = (element) => {
                if (!element || !element.querySelectorAll) return true;
                const hidden = (node) => {
                  if (!node || !node.getAttribute) return false;
                  if (node.getAttribute('$HIDDEN_ATTRIBUTE') === '1' ||
                      node.getAttribute('$CONCEALED_ATTRIBUTE') === '1' ||
                      node.getAttribute('$COMPACTED_ATTRIBUTE') === '1') return true;
                  try {
                    const style = getComputedStyle(node);
                    return style.display === 'none' || style.visibility === 'hidden';
                  } catch (_) { return true; }
                };
                const interactiveOrMedia =
                  'a[href],button,input,select,video,audio,canvas,iframe,' +
                  '[role="button"],[role="tab"],[role="menuitem"]';
                let descendants;
                try { descendants = element.querySelectorAll('*'); } catch (_) { return true; }
                const limit = Math.min(descendants.length, 96);
                for (let index = 0; index < limit; index++) {
                  const node = descendants[index];
                  if (hidden(node)) continue;
                  try {
                    if (node.matches(interactiveOrMedia)) return true;
                  } catch (_) { return true; }
                  if (!node.children.length && node.textContent.trim().length > 0 &&
                      !hasAdvertisingHint(node) && !hasAdvertisingHint(element)) return true;
                }
                return false;
              };
              const hasAdvertisingDescendant = (element) => {
                if (hasAdvertisingHint(element)) return true;
                let descendants;
                try { descendants = element.querySelectorAll('*'); } catch (_) { return false; }
                const limit = Math.min(descendants.length, 96);
                for (let index = 0; index < limit; index++) {
                  if (hasAdvertisingHint(descendants[index])) return true;
                }
                return false;
              };
              const isSafeCompactContainer = (element) => {
                if (!element || element.nodeType !== Node.ELEMENT_NODE ||
                    !hasAdvertisingDescendant(element) || hasVisibleSafeContent(element)) {
                  return false;
                }
                try {
                  if (element.matches(protectedSelector) ||
                      element.matches('html,body,main,nav,header,video,audio,canvas')) return false;
                  const style = getComputedStyle(element);
                  if (style.position === 'fixed' || style.position === 'sticky') return false;
                } catch (_) { return false; }
                return true;
              };
              const compactAdContainer = (element) => {
                let current = element && element.parentElement;
                for (let depth = 0; current && depth < 3; depth++, current = current.parentElement) {
                  if (!isSafeCompactContainer(current)) continue;
                  updateOwnedAttribute(current, '$COMPACTED_ATTRIBUTE', '1');
                  break;
                }
              };
              const directInteractiveCount = (element) => {
                if (!element || !element.children) return 0;
                let count = 0;
                const limit = Math.min(element.children.length, 32);
                for (let index = 0; index < limit; index++) {
                  const child = element.children[index];
                  try {
                    if (child.matches(interactiveSelector) ||
                        child.querySelector(interactiveSelector)) count++;
                  } catch (_) { return 32; }
                }
                return count;
              };
              const hasPositionedDescendant = (element) => {
                if (!element || !element.querySelectorAll) return false;
                let descendants;
                try { descendants = element.querySelectorAll('*'); } catch (_) { return true; }
                const limit = Math.min(descendants.length, 32);
                for (let index = 0; index < limit; index++) {
                  try {
                    const position = getComputedStyle(descendants[index]).position;
                    if (position === 'sticky' || position === 'fixed') return true;
                  } catch (_) { return true; }
                }
                return false;
              };
              const classifyElement = (element) => {
                if (!element || element.nodeType !== Node.ELEMENT_NODE) return 'protected';
                if (element === document.documentElement || element === document.body ||
                    element === document.scrollingElement) return 'protected';
                try { if (element.matches(protectedSelector)) return 'protected'; }
                catch (_) { return 'protected'; }
                const advertisingHint = hasAdvertisingHint(element);
                if (!advertisingHint) {
                  try { if (element.querySelector(protectedSelector)) return 'protected'; }
                  catch (_) { return 'protected'; }
                  try {
                    if (element.querySelectorAll(interactiveSelector).length >= 3) {
                      return 'protected';
                    }
                  } catch (_) { return 'protected'; }
                }
                let current = element;
                for (let depth = 0; current && depth < 8; depth++, current = current.parentElement) {
                  let style;
                  try { style = getComputedStyle(current); } catch (_) { return 'preserve'; }
                  if (style.position === 'sticky' || style.position === 'fixed') return 'preserve';
                  if (current !== element) {
                    try { if (current.matches(protectedSelector)) return 'preserve'; }
                    catch (_) { return 'preserve'; }
                  }
                  if (structuralDisplays.has(style.display) &&
                      directInteractiveCount(current) >= 2) return 'preserve';
                  if (current === element && style.display === 'table-cell' &&
                      current.parentElement && current.parentElement.children.length >= 2) {
                    return 'preserve';
                  }
                }
                if (hasPositionedDescendant(element)) return 'preserve';
                return 'collapse';
              };
              const ownsAttribute = (element, name) => {
                if (!element || !name) return false;
                let keys = state.touched.get(element);
                if (!keys) {
                  keys = new Set();
                  state.touched.set(element, keys);
                }
                const key = 'owned-attr:' + name;
                if (keys.has(key)) return true;
                if (state.undo.length >= 2000) return false;
                const hadAttribute = element.hasAttribute(name);
                const previous = element.getAttribute(name);
                keys.add(key);
                state.undo.push(() => {
                  if (hadAttribute) element.setAttribute(name, previous || '');
                  else element.removeAttribute(name);
                });
                return true;
              };
              const alreadyOwnsAttribute = (element, name) => {
                const keys = element ? state.touched.get(element) : null;
                return !!keys && keys.has('owned-attr:' + name);
              };
              const updateOwnedAttribute = (element, name, value) => {
                if (value === null) {
                  if (alreadyOwnsAttribute(element, name)) element.removeAttribute(name);
                } else if (ownsAttribute(element, name)) {
                  element.setAttribute(name, value);
                }
              };
              const enqueueHide = (element) => {
                if (!element || element.nodeType !== Node.ELEMENT_NODE ||
                    state.pending.length >= 1000 || state.pendingSet.has(element)) return;
                state.pendingSet.add(element);
                state.pending.push(element);
                if (state.scheduleClassification) state.scheduleClassification();
              };
              const applyHideDecision = (element) => {
                const decision = classifyElement(element);
                if (decision === 'protected') {
                  updateOwnedAttribute(element, '$HIDDEN_ATTRIBUTE', null);
                  updateOwnedAttribute(element, '$CONCEALED_ATTRIBUTE', null);
                  updateOwnedAttribute(element, '$PROTECTED_ATTRIBUTE', '1');
                } else if (decision === 'preserve') {
                  updateOwnedAttribute(element, '$HIDDEN_ATTRIBUTE', null);
                  updateOwnedAttribute(element, '$PROTECTED_ATTRIBUTE', null);
                  updateOwnedAttribute(element, '$CONCEALED_ATTRIBUTE', '1');
                } else {
                  updateOwnedAttribute(element, '$CONCEALED_ATTRIBUTE', null);
                  updateOwnedAttribute(element, '$PROTECTED_ATTRIBUTE', null);
                  updateOwnedAttribute(element, '$HIDDEN_ATTRIBUTE', '1');
                  compactAdContainer(element);
                }
              };
              const queryDocument = (selector, work) => {
                if (work.operations >= 4096) return [];
                try {
                  const found = document.querySelectorAll(selector);
                  const limit = Math.min(found.length, 200, 4096 - work.operations);
                  const output = [];
                  for (let index = 0; index < limit; index++) output.push(found[index]);
                  work.operations += limit;
                  return output;
                } catch (_) { return []; }
              };
              const filterElements = (elements, predicate, work) => {
                const output = [];
                for (const element of elements) {
                  if (work.operations++ >= 4096) break;
                  try { if (predicate(element)) output.push(element); } catch (_) {}
                  if (output.length >= 200) break;
                }
                return output;
              };
              const applyOperator = (elements, operator, work) => {
                if (!operator || typeof operator.type !== 'string' ||
                    typeof operator.argument !== 'string') return [];
                const argument = operator.argument;
                if (operator.type === 'css-selector') {
                  return filterElements(elements, (element) => {
                    const relative = /^[>+~]/.test(argument) ? ':scope ' + argument : argument;
                    return element.querySelector(relative) !== null;
                  }, work);
                }
                if (operator.type === 'has-text') {
                  const matcher = compileMatcher(argument);
                  return matcher ? filterElements(
                    elements,
                    (element) => matcher(element.textContent || ''),
                    work
                  ) : [];
                }
                if (operator.type === 'matches-attr') {
                  const separator = argument.indexOf('=');
                  const nameRaw = separator >= 0 ? argument.slice(0, separator) : argument;
                  const valueRaw = separator >= 0 ? argument.slice(separator + 1) : '';
                  const nameMatcher = compileMatcher(nameRaw);
                  const valueMatcher = separator >= 0 ? compileMatcher(valueRaw) : null;
                  if (!nameMatcher || (separator >= 0 && !valueMatcher)) return [];
                  return filterElements(elements, (element) => {
                    for (const attribute of element.attributes) {
                      if (nameMatcher(attribute.name) &&
                          (!valueMatcher || valueMatcher(attribute.value))) return true;
                    }
                    return false;
                  }, work);
                }
                if (operator.type === 'matches-css' ||
                    operator.type === 'matches-css-before' ||
                    operator.type === 'matches-css-after') {
                  const separator = argument.indexOf(':');
                  const property = separator > 0 ? argument.slice(0, separator).trim() : '';
                  const matcher = compileMatcher(
                    separator > 0 ? argument.slice(separator + 1).trim() : argument
                  );
                  const pseudo = operator.type === 'matches-css-before'
                    ? '::before'
                    : (operator.type === 'matches-css-after' ? '::after' : null);
                  return matcher ? filterElements(elements, (element) => {
                    const style = getComputedStyle(element, pseudo);
                    if (property) return matcher(style.getPropertyValue(property));
                    const limit = Math.min(style.length, 64);
                    for (let index = 0; index < limit; index++) {
                      const name = style[index];
                      if (matcher(name + ':' + style.getPropertyValue(name))) return true;
                    }
                    return false;
                  }, work) : [];
                }
                if (operator.type === 'matches-path') {
                  const matcher = compileMatcher(argument);
                  return matcher && matcher(location.pathname + location.search) ? elements : [];
                }
                if (operator.type === 'min-text-length') {
                  const minimum = Number.parseInt(argument, 10);
                  return Number.isFinite(minimum) ? filterElements(
                    elements,
                    (element) => (element.textContent || '').length >= minimum,
                    work
                  ) : [];
                }
                if (operator.type === 'upward') {
                  const levels = Number.parseInt(argument, 10);
                  const output = [];
                  for (const element of elements) {
                    if (work.operations++ >= 4096) break;
                    let target = element;
                    if (String(levels) === argument && levels > 0) {
                      for (let count = 0; count < levels && target; count++) {
                        target = target.parentElement;
                      }
                    } else {
                      try {
                        target = element.parentElement
                          ? element.parentElement.closest(argument)
                          : null;
                      } catch (_) { target = null; }
                    }
                    if (target) output.push(target);
                  }
                  return limitedElements(output, 200);
                }
                if (operator.type === 'xpath') {
                  const output = [];
                  for (const element of elements) {
                    if (work.operations++ >= 4096) break;
                    try {
                      const result = document.evaluate(
                        argument,
                        element,
                        null,
                        XPathResult.ORDERED_NODE_ITERATOR_TYPE,
                        null
                      );
                      let node;
                      while ((node = result.iterateNext()) && output.length < 200) {
                        if (node.nodeType === Node.ELEMENT_NODE) output.push(node);
                      }
                    } catch (_) {}
                  }
                  return limitedElements(output, 200);
                }
                return [];
              };
              const applyAction = (element, action) => {
                if (!element || !action || typeof action.type !== 'string') return;
                if (action.type === 'hide') {
                  enqueueHide(element);
                  return;
                }
                const structure = classifyElement(element);
                if (action.type === 'style' && Array.isArray(action.styles)) {
                  for (const declaration of action.styles) {
                    if (!declaration || typeof declaration.property !== 'string' ||
                        typeof declaration.value !== 'string') continue;
                    const property = declaration.property;
                    if (structure !== 'collapse' && layoutStyleProperties.has(property)) {
                      if (property === 'display' &&
                          declaration.value.trim().toLowerCase() === 'none') {
                        enqueueHide(element);
                      }
                      continue;
                    }
                    const key = 'style:' + property;
                    const previous = element.style.getPropertyValue(property);
                    const priority = element.style.getPropertyPriority(property);
                    if (rememberUndo(element, key, () => {
                      if (previous) element.style.setProperty(property, previous, priority);
                      else element.style.removeProperty(property);
                    })) {
                      element.style.setProperty(
                        property,
                        declaration.value,
                        declaration.important === true ? 'important' : ''
                      );
                    }
                  }
                  return;
                }
                if (structure !== 'collapse') return;
                if (action.type === 'remove-attr' && action.argument) {
                  const name = action.argument;
                  if (!element.hasAttribute(name)) return;
                  const previous = element.getAttribute(name);
                  if (rememberUndo(element, 'attr:' + name, () => {
                    element.setAttribute(name, previous || '');
                  })) element.removeAttribute(name);
                  return;
                }
                if (action.type === 'remove-class' && action.argument &&
                    element.classList.contains(action.argument)) {
                  const name = action.argument;
                  if (rememberUndo(element, 'class:' + name, () => element.classList.add(name))) {
                    element.classList.remove(name);
                  }
                }
              };
              const applyProceduralRule = (rule, work) => {
                if (!rule || !Array.isArray(rule.operators) || rule.operators.length === 0) return;
                const first = rule.operators[0];
                if (!first || first.type !== 'css-selector') return;
                let elements = queryDocument(first.argument, work);
                for (let index = 1; index < rule.operators.length && elements.length; index++) {
                  elements = applyOperator(elements, rule.operators[index], work);
                }
                for (const element of elements) {
                  if (work.operations++ >= 4096) break;
                  applyAction(element, rule.action);
                }
              };
              const runProcedural = () => {
                if (proceduralRules.length === 0 || state.proceduralRuns >= 24) return false;
                const deadline = performance.now() + 12;
                const work = { operations: 0 };
                let visited = 0;
                let index = state.proceduralCursor % proceduralRules.length;
                while (visited < proceduralRules.length && visited < 64 &&
                    work.operations < 4096 && performance.now() < deadline) {
                  applyProceduralRule(proceduralRules[index], work);
                  index = (index + 1) % proceduralRules.length;
                  visited++;
                }
                state.proceduralCursor = index;
                state.proceduralRuns++;
                state.applyingUntil = performance.now() + 50;
                return visited < proceduralRules.length;
              };

              const requestSelectorScan = () => {
                if (selectors.size === 0 || state.selectorScanRuns >= 24) return;
                if (!state.selectorScanRequested) {
                  state.selectorBatchCursor = 0;
                  state.selectorScanRequested = true;
                }
                if (state.scheduleClassification) state.scheduleClassification();
              };
              const scanSelectorMatches = () => {
                if (!state.selectorScanRequested || state.selectorScanRuns >= 24) return false;
                const deadline = performance.now() + 4;
                let visited = 0;
                while (state.selectorBatchCursor < state.selectorBatches.length &&
                    visited < 4 && performance.now() < deadline && state.pending.length < 1000) {
                  const batch = state.selectorBatches[state.selectorBatchCursor++];
                  visited++;
                  let found;
                  try { found = document.querySelectorAll(batch); } catch (_) { continue; }
                  const limit = Math.min(found.length, 250);
                  for (let index = 0; index < limit && state.pending.length < 1000; index++) {
                    enqueueHide(found[index]);
                  }
                }
                if (state.selectorBatchCursor >= state.selectorBatches.length) {
                  state.selectorScanRequested = false;
                  state.selectorBatchCursor = 0;
                  state.selectorScanRuns++;
                }
                return state.selectorScanRequested;
              };
              const processPending = () => {
                const deadline = performance.now() + 4;
                let processed = 0;
                while (state.pending.length > 0 && processed < 40 &&
                    performance.now() < deadline) {
                  const element = state.pending.shift();
                  state.pendingSet.delete(element);
                  if (element && element.isConnected) applyHideDecision(element);
                  processed++;
                }
                return state.pending.length > 0;
              };
              const runClassificationFrame = () => {
                state.frame = null;
                if (state.disposed) return;
                const hasMoreSelectors = scanSelectorMatches();
                const hasMoreElements = processPending();
                if (hasMoreSelectors || hasMoreElements) state.scheduleClassification();
              };
              state.scheduleClassification = () => {
                if (state.disposed || state.frame !== null) return;
                state.frame = requestAnimationFrame(runClassificationFrame);
              };

              state.dispose = () => {
                if (state.disposed) return;
                state.disposed = true;
                try { state.observer && state.observer.disconnect(); } catch (_) {}
                try { state.timer && clearTimeout(state.timer); } catch (_) {}
                try { state.frame !== null && cancelAnimationFrame(state.frame); } catch (_) {}
                for (let index = state.undo.length - 1; index >= 0; index--) {
                  try { state.undo[index](); } catch (_) {}
                }
                const style = document.getElementById('__surfsave_content_block_style');
                if (style) style.remove();
              };

              const collectElement = (node) => {
                if (!node || node.nodeType !== Node.ELEMENT_NODE) return;
                if (node.id === '__surfsave_content_block_style') return;
                if (node.id && state.ids.size < 500) state.ids.add(node.id);
                if (node.classList) {
                  for (const name of node.classList) {
                    if (state.classes.size >= 500) break;
                    state.classes.add(name);
                  }
                }
              };
              const collectTree = (node) => {
                if (!node || node.nodeType !== Node.ELEMENT_NODE) return;
                collectElement(node);
                if (state.classes.size + state.ids.size >= 500) return;
                const descendants = node.querySelectorAll('[class],[id]');
                const limit = Math.min(descendants.length, 500);
                for (let index = 0; index < limit; index++) {
                  if (state.classes.size + state.ids.size >= 500) break;
                  collectElement(descendants[index]);
                }
              };
              const requestDynamic = () => {
                state.timer = null;
                if (state.disposed) return;
                if (payload.generichide !== true && state.dynamicRuns < 6 &&
                    (state.classes.size > 0 || state.ids.size > 0)) {
                  const classes = Array.from(state.classes);
                  const ids = Array.from(state.ids);
                  state.classes.clear();
                  state.ids.clear();
                  state.dynamicRuns++;
                  try {
                    const result = JSON.parse(
                      bridge.dynamic(
                        pageUrl,
                        state.documentToken,
                        JSON.stringify(classes),
                        JSON.stringify(ids)
                      )
                    );
                    applySelectors(result);
                  } catch (_) {}
                }
                const hasMoreProcedural = runProcedural();
                state.scheduleClassification();
                if (hasMoreProcedural && state.proceduralRuns < 24) schedule(50);
              };
              const schedule = (delay) => {
                if (state.disposed) return;
                if (state.timer) clearTimeout(state.timer);
                state.timer = setTimeout(requestDynamic, typeof delay === 'number' ? delay : 650);
              };
              state.observer = new MutationObserver((mutations) => {
                if (performance.now() < state.applyingUntil) return;
                for (const mutation of mutations) {
                  if (mutation.type === 'attributes') {
                    collectElement(mutation.target);
                  } else {
                    for (const node of mutation.addedNodes) collectTree(node);
                  }
                }
                requestSelectorScan();
                schedule();
              });
              state.observer.observe(document, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['class', 'id'],
                characterData: proceduralRules.length > 0
              });
              if (document.documentElement) {
                collectTree(document.documentElement);
                schedule(0);
              } else {
                document.addEventListener('DOMContentLoaded', () => {
                  collectTree(document.documentElement);
                  schedule(0);
                }, { once: true });
              }
              return true;
            })();
        """.trimIndent()

        internal val CLEAR_SCRIPT = """
            (() => {
              const state = window.__surfSaveContentBlockState;
              try { state && state.dispose && state.dispose(); } catch (_) {}
              const style = document.getElementById('__surfsave_content_block_style');
              if (style) style.remove();
              try { delete window.__surfSaveContentBlockState; } catch (_) {
                window.__surfSaveContentBlockState = null;
              }
              return true;
            })();
        """.trimIndent()

        internal val FORCE_REFRESH_SCRIPT = "$CLEAR_SCRIPT\n$BOOTSTRAP_SCRIPT"

        private val COSMETIC_READY_STATUSES = setOf(
            ContentBlockEngineStatus.BUNDLED,
            ContentBlockEngineStatus.UP_TO_DATE,
            ContentBlockEngineStatus.STALE,
            ContentBlockEngineStatus.UPDATE_FAILED,
            ContentBlockEngineStatus.UPDATING
        )
    }
}

internal class ContentBlockJavaScriptBridge private constructor(
    private val isActive: (String) -> Boolean,
    private val cosmeticProvider: (String) -> CosmeticResources,
    private val dynamicProvider: (
        String,
        Collection<String>,
        Collection<String>,
        Collection<String>
    ) -> List<String>,
    private val scriptletProvider: (String) -> List<TrustedScriptletInvocation>
) {
    constructor(
        manager: ContentBlockManager,
        scriptletRegistry: TrustedScriptletRegistry
    ) : this(
        isActive = { pageUrl ->
            manager.state.value.status != ContentBlockEngineStatus.INITIALIZING &&
                manager.isEnabled() &&
                !manager.isSiteDisabled(pageUrl)
        },
        cosmeticProvider = manager::cosmeticResources,
        dynamicProvider = manager::hiddenSelectors,
        scriptletProvider = scriptletRegistry::forPage
    )

    internal constructor(
        isActive: (String) -> Boolean,
        cosmeticProvider: (String) -> CosmeticResources,
        dynamicProvider: (
            String,
            Collection<String>,
            Collection<String>,
            Collection<String>
        ) -> List<String>,
        scriptletProvider: (String) -> List<TrustedScriptletInvocation>,
        testMarker: Unit = Unit
    ) : this(isActive, cosmeticProvider, dynamicProvider, scriptletProvider)

    private val totalDynamicCalls = AtomicInteger(0)
    private val documentStates = LinkedHashMap<String, DocumentState>(
        MAX_DOCUMENT_STATES,
        0.75f,
        true
    )

    @JavascriptInterface
    @Synchronized
    fun bootstrap(pageUrl: String, documentToken: String): String {
        val normalizedToken = normalizeDocumentToken(documentToken)
            ?: return JSON.encodeToString(BootstrapPayload(active = false))
        val normalized = normalizePageUrl(pageUrl)
            ?: return JSON.encodeToString(BootstrapPayload(active = false))
        if (!isActive(normalized)) {
            documentStates.remove(normalizedToken)
            return JSON.encodeToString(BootstrapPayload(active = false))
        }
        val resources = runCatching { cosmeticProvider(normalized) }
            .getOrDefault(CosmeticResources())
        val extendedSelectors = ExtendedCosmeticSelectorParser.partition(resources.hideSelectors)
        val selectors = sanitizeSelectors(extendedSelectors.staticSelectors)
        val proceduralRules = ProceduralCosmeticSanitizer.sanitize(
            resources.proceduralRules + extendedSelectors.proceduralRules
        )
        documentStates[normalizedToken] = DocumentState(
            pageUrl = normalized,
            exceptions = sanitizeSelectors(resources.exceptions, MAX_EXCEPTION_SELECTORS),
            generichide = resources.generichide
        )
        trimDocumentStates()
        val actions = runCatching { scriptletProvider(normalized) }
            .getOrDefault(emptyList())
            .take(MAX_SCRIPTLET_ACTIONS)
            .mapNotNull { invocation ->
                invocation.takeIf {
                    it.name in TRUSTED_SCRIPTLET_NAMES &&
                        it.argument.length in 1..MAX_SCRIPTLET_ARGUMENT_LENGTH &&
                        it.argument.none(Char::isISOControl)
                }?.let { TrustedAction(it.name, it.argument) }
            }
        return JSON.encodeToString(
            BootstrapPayload(
                active = true,
                selectors = selectors,
                generichide = resources.generichide,
                proceduralRules = proceduralRules,
                actions = actions
            )
        )
    }

    @JavascriptInterface
    @Synchronized
    fun dynamic(
        pageUrl: String,
        documentToken: String,
        classesJson: String,
        idsJson: String
    ): String {
        val normalized = normalizePageUrl(pageUrl) ?: return EMPTY_LIST_JSON
        val normalizedToken = normalizeDocumentToken(documentToken) ?: return EMPTY_LIST_JSON
        val documentState = documentStates[normalizedToken] ?: return EMPTY_LIST_JSON
        if (normalized != documentState.pageUrl || documentState.generichide) {
            return EMPTY_LIST_JSON
        }
        if (documentState.dynamicCalls >= MAX_DYNAMIC_CALLS_PER_PAGE ||
            totalDynamicCalls.incrementAndGet() > MAX_DYNAMIC_CALLS_PER_CONTROLLER
        ) {
            return EMPTY_LIST_JSON
        }
        documentState.dynamicCalls++
        val classes = parseNames(classesJson)
        val ids = parseNames(idsJson)
        if (classes.isEmpty() && ids.isEmpty()) return EMPTY_LIST_JSON
        val selectors = runCatching {
            dynamicProvider(normalized, classes, ids, documentState.exceptions)
        }.getOrDefault(emptyList())
        return JSON.encodeToString(sanitizeSelectors(selectors, MAX_DYNAMIC_SELECTORS))
    }

    private fun trimDocumentStates() {
        while (documentStates.size > MAX_DOCUMENT_STATES) {
            val eldest = documentStates.entries.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }

    private fun normalizeDocumentToken(value: String): String? {
        return value.trim().takeIf {
            it.length in MIN_DOCUMENT_TOKEN_LENGTH..MAX_DOCUMENT_TOKEN_LENGTH &&
                it.all { character ->
                    character.isLetterOrDigit() || character == '-' || character == '_'
                }
        }
    }

    private fun parseNames(value: String): List<String> {
        if (value.length > MAX_DYNAMIC_JSON_CHARS) return emptyList()
        return runCatching { JSON.decodeFromString<List<String>>(value) }
            .getOrDefault(emptyList())
            .asSequence()
            .map(String::trim)
            .filter { it.length in 1..MAX_DYNAMIC_NAME_LENGTH && it.none(Char::isISOControl) }
            .distinct()
            .take(MAX_DYNAMIC_NAMES)
            .toList()
    }

    private fun sanitizeSelectors(
        values: Collection<String>,
        limit: Int = MAX_STATIC_SELECTORS
    ): List<String> {
        var usedChars = 0
        return values.asSequence()
            .map(String::trim)
            .filter(::isSafeSelector)
            .distinct()
            .take(limit)
            .takeWhile { selector ->
                usedChars += selector.length + CSS_RULE_OVERHEAD
                usedChars <= MAX_CSS_CHARS
            }
            .toList()
    }

    private fun isSafeSelector(selector: String): Boolean {
        if (selector.length !in 1..MAX_SELECTOR_LENGTH || selector.any(Char::isISOControl)) {
            return false
        }
        if (selector.any { it == '{' || it == '}' || it == ';' || it == '@' }) return false
        val lowercase = selector.lowercase()
        return "/*" !in selector && "*/" !in selector && "url(" !in lowercase
    }

    private fun normalizePageUrl(value: String): String? {
        val trimmed = value.trim().substringBefore('#')
        if (trimmed.length !in 1..MAX_PAGE_URL_LENGTH) return null
        return runCatching {
            val uri = URI(trimmed)
            trimmed.takeIf {
                (uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true)) &&
                    !uri.host.isNullOrBlank()
            }
        }.getOrNull()
    }

    private data class DocumentState(
        val pageUrl: String,
        val exceptions: List<String>,
        val generichide: Boolean,
        var dynamicCalls: Int = 0
    )

    @Serializable
    private data class BootstrapPayload(
        val active: Boolean,
        val selectors: List<String> = emptyList(),
        val generichide: Boolean = true,
        val proceduralRules: List<SafeProceduralRule> = emptyList(),
        val actions: List<TrustedAction> = emptyList()
    )

    @Serializable
    private data class TrustedAction(val name: String, val argument: String)

    companion object {
        private const val MAX_PAGE_URL_LENGTH = 4_096
        private const val MIN_DOCUMENT_TOKEN_LENGTH = 8
        private const val MAX_DOCUMENT_TOKEN_LENGTH = 128
        private const val MAX_DOCUMENT_STATES = 32
        private const val MAX_SELECTOR_LENGTH = 512
        private const val MAX_STATIC_SELECTORS = 2_048
        private const val MAX_EXCEPTION_SELECTORS = 2_048
        private const val MAX_DYNAMIC_SELECTORS = 512
        private const val MAX_DYNAMIC_NAMES = 500
        private const val MAX_DYNAMIC_NAME_LENGTH = 128
        private const val MAX_DYNAMIC_JSON_CHARS = 64 * 1024
        private const val MAX_DYNAMIC_CALLS_PER_PAGE = 6
        private const val MAX_DYNAMIC_CALLS_PER_CONTROLLER = 24
        private const val MAX_CSS_CHARS = 64 * 1024
        private const val CSS_RULE_OVERHEAD = 27
        private const val MAX_SCRIPTLET_ACTIONS = 16
        private const val MAX_SCRIPTLET_ARGUMENT_LENGTH = 256
        private const val EMPTY_LIST_JSON = "[]"
        private val TRUSTED_SCRIPTLET_NAMES = setOf("remove-cookie", "set-local-storage")
        private val JSON = Json { encodeDefaults = true }
    }
}
