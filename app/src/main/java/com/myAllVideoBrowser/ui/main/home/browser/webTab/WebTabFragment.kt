package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.HapticFeedbackConstants
import android.app.ActivityOptions
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.widget.ListPopupWindow
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ShareCompat
import androidx.core.net.toUri
import androidx.core.widget.TextViewCompat
import androidx.databinding.Observable
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.tasks.Task
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.FragmentWebTabBinding
import com.myAllVideoBrowser.ui.component.adapter.SuggestionTabListener
import com.myAllVideoBrowser.ui.component.adapter.TabSuggestionAdapter
import com.myAllVideoBrowser.ui.component.adapter.DownloadTabListener
import com.myAllVideoBrowser.ui.main.home.browser.BaseWebTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserBackPolicy
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserMediaClassifier
import com.myAllVideoBrowser.ui.main.home.browser.ContentType
import com.myAllVideoBrowser.ui.main.home.browser.BrowserListener
import com.myAllVideoBrowser.ui.main.home.browser.CurrentTabIndexProvider
import com.myAllVideoBrowser.ui.main.home.browser.CustomWebChromeClient
import com.myAllVideoBrowser.ui.main.home.browser.CustomWebViewClient
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonState
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.ui.main.home.browser.HOME_TAB_INDEX
import com.myAllVideoBrowser.ui.main.home.browser.HistoryProvider
import com.myAllVideoBrowser.ui.main.home.browser.MAX_WEB_TABS
import com.myAllVideoBrowser.ui.main.home.browser.PageTabProvider
import com.myAllVideoBrowser.ui.main.home.browser.TAB_INDEX_KEY
import com.myAllVideoBrowser.ui.main.home.browser.TabManagerProvider
import com.myAllVideoBrowser.ui.main.home.browser.WorkerEventProvider
import com.myAllVideoBrowser.ui.main.home.browser.WebTabBackAction
import com.myAllVideoBrowser.ui.main.home.browser.WebViewMediaController
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.DetectedVideosTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.PageMediaMetadataParser
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.VideoDetectionTabViewModel
import com.myAllVideoBrowser.ui.main.player.VideoPlayerActivity
import com.myAllVideoBrowser.ui.main.player.VideoPlayerFragment
import com.myAllVideoBrowser.ui.main.player.ExternalPlaybackIntentFactory
import com.myAllVideoBrowser.ui.main.player.PlaybackMediaKind
import com.myAllVideoBrowser.ui.main.player.PlaybackMediaKindResolver
import com.myAllVideoBrowser.ui.main.player.PlaybackTarget
import com.myAllVideoBrowser.ui.main.player.PlaybackTargetMenuAdapter
import com.myAllVideoBrowser.ui.main.player.PlaybackTargetMenuItem
import com.myAllVideoBrowser.ui.main.player.PlaybackTargetResolver
import com.myAllVideoBrowser.ui.main.player.PlaybackTargetStore
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.BrowserThumbnailStore
import com.myAllVideoBrowser.util.FileNameCleaner
import com.myAllVideoBrowser.util.MediaRequestHeaderPolicy
import com.myAllVideoBrowser.util.VideoFormatUi
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebTabFragment : BaseWebTabFragment() {

    companion object {
        fun newInstance() = WebTabFragment()

        private const val MAX_PAGE_TRANSLATION_NODES = 96
        private const val MAX_PAGE_TRANSLATION_CHARACTERS = 16_000
        private const val MAX_SINGLE_TRANSLATION_NODE_CHARACTERS = 16_000
        private const val MAX_PAGE_TRANSLATION_CACHE_ENTRIES = 256
        private const val MAX_PAGE_NODE_LANGUAGE_CACHE_ENTRIES = 512
        private const val MAX_CACHED_TRANSLATION_TEXT_LENGTH = 4_000
        private const val MAX_LANGUAGE_SAMPLE_LENGTH = 4_000
        private const val MAX_NODE_LANGUAGE_SAMPLE_LENGTH = 1_200
        private const val TRANSLATION_BRIDGE_NAME = "SuperXTranslationBridge"
        private const val TRANSLATION_MUTATION_DEBOUNCE_MS = 200L
        private const val TRANSLATION_START_DEBOUNCE_MS = 250L
        private const val MIN_AUTO_TRANSLATION_INTERVAL_MS = 2_000L
        private const val MEDIA_PROBE_BRIDGE_NAME = "SuperXMediaProbe"
        private const val MAX_MEDIA_PROBE_PAYLOAD_LENGTH = 8_192
        private const val MAX_PAGE_MEDIA_METADATA_PAYLOAD_LENGTH = 16_384
        private const val MEDIA_PROBE_THROTTLE_MS = 4_000L
        private val PLAYER_RECOVERY_DELAYS_MS = longArrayOf(1_500L, 3_500L, 6_500L, 10_000L)
        private const val MENU_OPEN_LINK_CURRENT_WINDOW = 1001
        private const val MENU_OPEN_LINK_NEW_WINDOW = 1002
        private const val MENU_OPEN_LINK_BACKGROUND_WINDOW = 1003

        private val MEDIA_PROBE_SCRIPT = """
            (function() {
                ${WebViewMediaController.installPauseListenerScript}

                if (window.__superxMediaProbeInstalled) {
                    return;
                }

                window.__superxMediaProbeInstalled = true;
                var bridgeName = 'SuperXMediaProbe';
                var recent = {};

                function absoluteUrl(raw) {
                    try {
                        if (!raw) {
                            return '';
                        }
                        if (typeof raw === 'object') {
                            raw = raw.url || raw.href || String(raw);
                        }
                        raw = String(raw);
                        if (!raw) {
                            return '';
                        }
                        if (raw.indexOf('blob:') === 0) {
                            return raw;
                        }
                        return new URL(raw, document.baseURI || location.href).href;
                    } catch (e) {
                        return '';
                    }
                }

                function looksLikeMedia(url, contentType, manifestKind) {
                    var cleanUrl = String(url || '').split('#')[0].toLowerCase();
                    var type = String(contentType || '').toLowerCase();

                    return manifestKind === 'hls' || manifestKind === 'dash' ||
                        cleanUrl.indexOf('blob:') === 0 ||
                        /\.(m3u8|mpd|mp4|m4v|webm|mov|flv|ts|m4s)(\?|${'$'})/.test(cleanUrl) ||
                        type.indexOf('video') >= 0 ||
                        type.indexOf('audio') >= 0 ||
                        type.indexOf('mpegurl') >= 0 ||
                        type.indexOf('dash') >= 0 ||
                        type.indexOf('mp2t') >= 0 ||
                        type.indexOf('mp4') >= 0;
                }

                function send(kind, rawUrl, extra) {
                    try {
                        var contentType = extra && extra.contentType ? extra.contentType : '';
                        var manifestKind = extra && extra.manifestKind ? extra.manifestKind : '';
                        var url = absoluteUrl(rawUrl);
                        if (!looksLikeMedia(url, contentType, manifestKind)) {
                            return;
                        }

                        var key = kind + '|' + url + '|' + contentType;
                        var now = Date.now ? Date.now() : new Date().getTime();
                        if (recent[key] && now - recent[key] < 2000) {
                            return;
                        }
                        recent[key] = now;

                        var bridge = window[bridgeName];
                        if (!bridge || typeof bridge.onMediaEvent !== 'function') {
                            return;
                        }

                        bridge.onMediaEvent(JSON.stringify({
                            kind: kind,
                            url: url,
                            pageUrl: location.href,
                            method: extra && extra.method ? extra.method : 'GET',
                            status: extra && extra.status ? extra.status : 0,
                            contentType: contentType,
                            manifestKind: manifestKind
                        }));
                    } catch (e) {
                    }
                }

                function inspectSmallTextBody(kind, url, method, contentType, text) {
                    try {
                        if (!text || text.length > 1000000) return;
                        var normalized = String(text).replace(/^\uFEFF/, '').trim();
                        var lower = normalized.substring(0, 4096).toLowerCase();
                        var manifestKind = '';
                        if (lower.indexOf('#extm3u') === 0) manifestKind = 'hls';
                        else if (/<(?:[a-z0-9_-]+:)?mpd(?:\s|>)/i.test(lower)) manifestKind = 'dash';
                        if (manifestKind) {
                            send(kind + '-manifest', url, {
                                method: method,
                                contentType: contentType,
                                manifestKind: manifestKind
                            });
                        }

                        var unescaped = normalized.replace(/\\\//g, '/');
                        var matches = unescaped.match(/https?:\/\/[^\s"'<>\\]+/g) || [];
                        var seen = {};
                        for (var index = 0; index < matches.length && index < 24; index++) {
                            var candidate = matches[index].replace(/[),\]}]+$/, '');
                            if (!seen[candidate] && looksLikeMedia(candidate, '', '')) {
                                seen[candidate] = true;
                                send(kind + '-body-url', candidate, { method: 'GET' });
                            }
                        }
                    } catch (e) {
                    }
                }

                function mayInspectResponseBody(response, contentType) {
                    try {
                        var type = String(contentType || '').toLowerCase();
                        var length = parseInt(response.headers && response.headers.get('content-length') || '0', 10);
                        if (length > 1000000) return false;
                        return type.indexOf('text/') === 0 || type.indexOf('json') >= 0 ||
                            type.indexOf('xml') >= 0 || type.indexOf('octet-stream') >= 0 ||
                            type.indexOf('mpegurl') >= 0 || type.indexOf('dash') >= 0;
                    } catch (e) {
                        return false;
                    }
                }

                function inspectResponseBody(kind, url, method, contentType, response) {
                    try {
                        var clone = response.clone();
                        if (clone.body && clone.body.getReader && window.TextDecoder) {
                            var reader = clone.body.getReader();
                            var decoder = new TextDecoder('utf-8');
                            var text = '';
                            var bytesRead = 0;
                            function finish() {
                                inspectSmallTextBody(kind, url, method, contentType, text);
                            }
                            function pump() {
                                reader.read().then(function(result) {
                                    if (result.done) {
                                        text += decoder.decode();
                                        finish();
                                        return;
                                    }
                                    bytesRead += result.value ? result.value.byteLength : 0;
                                    if (bytesRead > 1000000) {
                                        try { reader.cancel(); } catch (e) {}
                                        finish();
                                        return;
                                    }
                                    text += decoder.decode(result.value, { stream: true });
                                    pump();
                                }).catch(function() {});
                            }
                            pump();
                            return;
                        }

                        var length = parseInt(response.headers && response.headers.get('content-length') || '0', 10);
                        if (length > 0 && length <= 1000000) {
                            clone.text().then(function(text) {
                                inspectSmallTextBody(kind, url, method, contentType, text);
                            }).catch(function() {});
                        }
                    } catch (e) {
                    }
                }

                var originalFetch = window.fetch;
                if (typeof originalFetch === 'function') {
                    window.fetch = function(input, init) {
                        var requestUrl = input && input.url ? input.url : input;
                        var method = init && init.method ? init.method : (input && input.method ? input.method : 'GET');
                        send('fetch', requestUrl, { method: method });

                        return originalFetch.apply(this, arguments).then(function(response) {
                            try {
                                var responseContentType = response.headers ? (response.headers.get('content-type') || '') : '';
                                send('fetch-response', response.url || requestUrl, {
                                    method: method,
                                    status: response.status || 0,
                                    contentType: responseContentType
                                });
                                if (mayInspectResponseBody(response, responseContentType)) {
                                    inspectResponseBody(
                                        'fetch', response.url || requestUrl, method,
                                        responseContentType, response
                                    );
                                }
                            } catch (e) {
                            }
                            return response;
                        });
                    };
                }

                var OriginalXHR = window.XMLHttpRequest;
                if (typeof OriginalXHR === 'function') {
                    var originalOpen = OriginalXHR.prototype.open;
                    var originalSend = OriginalXHR.prototype.send;

                    OriginalXHR.prototype.open = function(method, url) {
                        this.__superxMediaProbeMethod = method || 'GET';
                        this.__superxMediaProbeUrl = url;
                        return originalOpen.apply(this, arguments);
                    };

                    OriginalXHR.prototype.send = function() {
                        var xhr = this;
                        send('xhr', xhr.__superxMediaProbeUrl, { method: xhr.__superxMediaProbeMethod });

                        function report() {
                            try {
                                send('xhr-response', xhr.responseURL || xhr.__superxMediaProbeUrl, {
                                    method: xhr.__superxMediaProbeMethod,
                                    status: xhr.status || 0,
                                    contentType: xhr.getResponseHeader('content-type') || ''
                                });
                            } catch (e) {
                            }
                        }

                        function inspectResponseText() {
                            try {
                                var text = '';
                                if (xhr.responseType === 'json') {
                                    text = JSON.stringify(xhr.response || {});
                                } else if (!xhr.responseType || xhr.responseType === 'text') {
                                    text = xhr.responseText || '';
                                } else {
                                    return;
                                }
                                inspectSmallTextBody(
                                    'xhr', xhr.responseURL || xhr.__superxMediaProbeUrl,
                                    xhr.__superxMediaProbeMethod,
                                    xhr.getResponseHeader('content-type') || '',
                                    text
                                );
                            } catch (e) {
                            }
                        }

                        try {
                            xhr.addEventListener('readystatechange', function() {
                                if (xhr.readyState >= 2) {
                                    report();
                                }
                            });
                            xhr.addEventListener('load', function() {
                                report();
                                inspectResponseText();
                            });
                        } catch (e) {
                        }

                        return originalSend.apply(this, arguments);
                    };
                }

                try {
                    function reportPerformanceEntries(entries) {
                        for (var index = 0; index < entries.length; index++) {
                            send('performance-resource', entries[index].name, { method: 'GET' });
                        }
                    }
                    if (window.PerformanceObserver) {
                        var resourceObserver = new PerformanceObserver(function(list) {
                            reportPerformanceEntries(list.getEntries());
                        });
                        resourceObserver.observe({ entryTypes: ['resource'] });
                    }
                    if (window.performance && performance.getEntriesByType) {
                        reportPerformanceEntries(performance.getEntriesByType('resource'));
                    }
                } catch (e) {
                }

                if (window.URL && typeof window.URL.createObjectURL === 'function') {
                    var originalCreateObjectURL = window.URL.createObjectURL;
                    window.URL.createObjectURL = function(object) {
                        var objectUrl = originalCreateObjectURL.apply(this, arguments);
                        var type = '';
                        try {
                            type = object && object.type ? object.type : '';
                            if (!type && window.MediaSource && object instanceof MediaSource) {
                                type = 'mediasource';
                            }
                        } catch (e) {
                        }
                        send('blob-url', objectUrl, { contentType: type });
                        return objectUrl;
                    };
                }

                function reportMediaElement(element) {
                    try {
                        if (!element) {
                            return;
                        }
                        if (element.currentSrc) {
                            send('media-current-src', element.currentSrc, { contentType: element.type || '' });
                        }
                        if (element.src) {
                            send('media-src', element.src, { contentType: element.type || '' });
                        }
                        var sources = element.querySelectorAll ? element.querySelectorAll('source[src]') : [];
                        for (var i = 0; i < sources.length; i++) {
                            send('media-source', sources[i].src, { contentType: sources[i].type || '' });
                        }
                    } catch (e) {
                    }
                }

                try {
                    if (window.HTMLMediaElement && HTMLMediaElement.prototype) {
                        var srcDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                        if (srcDescriptor && srcDescriptor.set && srcDescriptor.get) {
                            Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                                configurable: true,
                                get: function() {
                                    return srcDescriptor.get.call(this);
                                },
                                set: function(value) {
                                    send('media-src-set', value, { contentType: this.type || '' });
                                    return srcDescriptor.set.call(this, value);
                                }
                            });
                        }
                    }
                } catch (e) {
                }

                function scanMediaElements() {
                    try {
                        var elements = document.querySelectorAll('video,audio');
                        for (var i = 0; i < elements.length; i++) {
                            reportMediaElement(elements[i]);
                        }
                    } catch (e) {
                    }
                }

                try {
                    var observer = new MutationObserver(function(mutations) {
                        for (var i = 0; i < mutations.length; i++) {
                            var mutation = mutations[i];
                            if (mutation.type === 'attributes') {
                                reportMediaElement(mutation.target);
                            }
                            for (var j = 0; j < mutation.addedNodes.length; j++) {
                                var node = mutation.addedNodes[j];
                                if (node && node.nodeType === 1) {
                                    if (node.matches && node.matches('video,audio')) {
                                        reportMediaElement(node);
                                    }
                                    if (node.querySelectorAll) {
                                        var nested = node.querySelectorAll('video,audio');
                                        for (var k = 0; k < nested.length; k++) {
                                            reportMediaElement(nested[k]);
                                        }
                                    }
                                }
                            }
                        }
                    });

                    observer.observe(document.documentElement || document, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['src']
                    });
                } catch (e) {
                }

                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', scanMediaElements);
                } else {
                    scanMediaElements();
                }
            })()
        """.trimIndent()

        private val PAGE_MEDIA_METADATA_SCRIPT = """
            (function() {
                try {
                    var canonicalElement = document.querySelector('link[rel="canonical"]');
                    var canonicalUrl = canonicalElement && canonicalElement.href
                        ? canonicalElement.href
                        : location.href;
                    var videoObjects = [];
                    var inspectedJsonNodes = 0;

                    function hasVideoObjectType(value) {
                        var type = value && value['@type'];
                        if (Array.isArray(type)) {
                            return type.some(function(item) {
                                return String(item).toLowerCase() === 'videoobject';
                            });
                        }
                        return String(type || '').toLowerCase() === 'videoobject';
                    }

                    function walkJson(value, depth) {
                        if (!value || depth > 8 || videoObjects.length >= 64 || inspectedJsonNodes >= 2048) return;
                        inspectedJsonNodes += 1;
                        if (Array.isArray(value)) {
                            for (var index = 0; index < value.length; index++) {
                                walkJson(value[index], depth + 1);
                            }
                            return;
                        }
                        if (typeof value !== 'object') return;
                        if (hasVideoObjectType(value)) videoObjects.push(value);
                        Object.keys(value).forEach(function(key) {
                            walkJson(value[key], depth + 1);
                        });
                    }

                    var jsonLdScripts = document.querySelectorAll('script[type="application/ld+json"]');
                    for (var scriptIndex = 0; scriptIndex < jsonLdScripts.length; scriptIndex++) {
                        try {
                            var jsonLdText = jsonLdScripts[scriptIndex].textContent || '';
                            if (jsonLdText.length <= 262144) {
                                walkJson(JSON.parse(jsonLdText), 0);
                            }
                        } catch (ignored) {
                        }
                    }

                    function referenceUrl(value) {
                        if (!value) return '';
                        if (typeof value === 'string') return value;
                        if (typeof value === 'object') return value['@id'] || value.url || '';
                        return '';
                    }

                    function normalizedUrl(value) {
                        try {
                            return new URL(referenceUrl(value), document.baseURI || location.href)
                                .href.split('#')[0].replace(/\/$/, '');
                        } catch (ignored) {
                            return '';
                        }
                    }

                    var normalizedCanonical = normalizedUrl(canonicalUrl);
                    var selectedVideo = null;
                    var selectedScore = -1;
                    for (var objectIndex = 0; objectIndex < videoObjects.length; objectIndex++) {
                        var candidate = videoObjects[objectIndex];
                        var score = 0;
                        var pageReference = normalizedUrl(candidate.mainEntityOfPage);
                        var objectUrl = normalizedUrl(candidate.url);
                        if (pageReference && pageReference === normalizedCanonical) score += 100;
                        if (objectUrl && objectUrl === normalizedCanonical) score += 80;
                        if (candidate.contentUrl) score += 20;
                        if (candidate.duration) score += 10;
                        if (score > selectedScore) {
                            selectedScore = score;
                            selectedVideo = candidate;
                        }
                    }

                    var contentUrls = [];
                    function addContentUrl(value) {
                        if (Array.isArray(value)) {
                            value.forEach(addContentUrl);
                            return;
                        }
                        var url = normalizedUrl(value);
                        if (url && /^https?:/i.test(url) && contentUrls.indexOf(url) < 0) {
                            contentUrls.push(url);
                        }
                    }
                    if (selectedVideo) addContentUrl(selectedVideo.contentUrl);
                    document.querySelectorAll(
                        'meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]'
                    ).forEach(function(meta) {
                        addContentUrl(meta.content || '');
                    });

                    var durationMeta = document.querySelector(
                        'meta[property="video:duration"],meta[name="video:duration"]'
                    );
                    return JSON.stringify({
                        pageUrl: location.href,
                        canonicalUrl: canonicalUrl,
                        contentUrls: contentUrls,
                        duration: selectedVideo && selectedVideo.duration
                            ? String(selectedVideo.duration)
                            : '',
                        durationSeconds: durationMeta && durationMeta.content
                            ? String(durationMeta.content)
                            : ''
                    });
                } catch (error) {
                    return JSON.stringify({
                        pageUrl: location.href,
                        canonicalUrl: location.href,
                        contentUrls: [],
                        duration: '',
                        durationSeconds: ''
                    });
                }
            })()
        """.trimIndent()

        private val KVS_PLAYER_RECOVERY_SCRIPT = """
            (function() {
                try {
                    if (window.__superxKvsPlayerRecovered && document.querySelector('#kt_player video')) {
                        return 'already-recovered';
                    }

                    var holder = document.getElementById('kt_player');
                    if (!holder) {
                        return;
                    }

                    var existingVideo = holder.querySelector('video');
                    if (existingVideo && !existingVideo.error && (existingVideo.readyState >= 1 || existingVideo.currentSrc || existingVideo.src)) {
                        return 'video-ok';
                    }

                    var vars = window.flashvars;
                    if (!vars || !vars.video_url) {
                        return 'no-flashvars';
                    }

                    function addCandidate(items, url, label) {
                        if (!url) {
                            return;
                        }
                        url = String(url).replace(/&amp;/g, '&');
                        if (url.indexOf('http://') !== 0 && url.indexOf('https://') !== 0) {
                            return;
                        }
                        for (var i = 0; i < items.length; i++) {
                            if (items[i].url === url) {
                                return;
                            }
                        }
                        items.push({
                            url: url,
                            label: label || 'Video'
                        });
                    }

                    var candidates = [];
                    addCandidate(candidates, vars.video_url, vars.video_url_text);
                    addCandidate(candidates, vars.video_alt_url, vars.video_alt_url_text);
                    addCandidate(candidates, vars.video_alt_url2, vars.video_alt_url2_text);
                    addCandidate(candidates, vars.video_alt_url3, vars.video_alt_url3_text);
                    addCandidate(candidates, vars.video_alt_url4, vars.video_alt_url4_text);
                    addCandidate(candidates, vars.video_alt_url5, vars.video_alt_url5_text);
                    if (!candidates.length) {
                        return 'no-candidates';
                    }

                    window.__superxKvsPlayerRecovered = true;
                    Array.prototype.slice.call(holder.querySelectorAll('video, object, embed')).forEach(function(node) {
                        if (node && node.parentNode) {
                            node.parentNode.removeChild(node);
                        }
                    });
                    holder.style.position = 'relative';
                    holder.style.width = '100%';
                    holder.style.minHeight = '220px';
                    holder.style.aspectRatio = '16 / 9';
                    holder.style.maxHeight = '80vh';
                    holder.style.overflow = 'hidden';
                    holder.style.background = '#050505';

                    var video = document.createElement('video');
                    video.controls = true;
                    video.preload = 'metadata';
                    video.playsInline = true;
                    video.setAttribute('webkit-playsinline', 'true');
                    video.style.width = '100%';
                    video.style.height = '100%';
                    video.style.minHeight = '220px';
                    video.style.background = '#050505';
                    video.style.objectFit = 'contain';
                    if (vars.preview_url) {
                        video.poster = String(vars.preview_url).replace(/&amp;/g, '&');
                    }

                    var select = document.createElement('select');
                    select.setAttribute('aria-label', 'Video quality');
                    select.style.position = 'absolute';
                    select.style.top = '8px';
                    select.style.right = '8px';
                    select.style.zIndex = '3';
                    select.style.maxWidth = '44%';
                    select.style.height = '34px';
                    select.style.border = '0';
                    select.style.borderRadius = '6px';
                    select.style.padding = '0 8px';
                    select.style.background = 'rgba(0,0,0,0.72)';
                    select.style.color = '#fff';

                    candidates.forEach(function(item, index) {
                        var option = document.createElement('option');
                        option.value = item.url;
                        option.textContent = item.label || ('Video ' + (index + 1));
                        select.appendChild(option);
                    });

                    function setSource(url, shouldPlay) {
                        var wasPlaying = shouldPlay || !video.paused;
                        video.src = url;
                        video.load();
                        if (wasPlaying) {
                            var promise = video.play();
                            if (promise && promise.catch) {
                                promise.catch(function() {});
                            }
                        }
                    }

                    select.addEventListener('change', function() {
                        setSource(select.value, true);
                    });

                    holder.appendChild(video);
                    if (candidates.length > 1) {
                        holder.appendChild(select);
                    }
                    setSource(candidates[0].url, false);
                    return 'recovered:' + candidates.length;
                } catch (e) {
                    return 'error:' + (e && e.message ? e.message : e);
                }
            })()
        """.trimIndent()

        private val PAGE_THUMBNAIL_SCRIPT = """
            (function() {
                function abs(url) {
                    try {
                        return new URL(url, document.baseURI).href;
                    } catch (e) {
                        return '';
                    }
                }

                var candidates = [];
                Array.prototype.slice.call(document.querySelectorAll('video')).forEach(function(video) {
                    if (video.poster) {
                        candidates.push(video.poster);
                    }
                });

                [
                    'meta[property="og:image"]',
                    'meta[property="og:image:url"]',
                    'meta[name="twitter:image"]',
                    'meta[name="twitter:image:src"]',
                    'link[rel="image_src"]'
                ].forEach(function(selector) {
                    var node = document.querySelector(selector);
                    var value = node && (node.content || node.href);
                    if (value) {
                        candidates.push(value);
                    }
                });

                for (var i = 0; i < candidates.length; i++) {
                    var resolved = abs(candidates[i]);
                    if (resolved.indexOf('http') === 0) {
                        return resolved;
                    }
                }

                return '';
            })()
        """.trimIndent()

        private val PAGE_LANGUAGE_SCRIPT = """
            (function() {
                var lang = document.documentElement && document.documentElement.lang;
                if (lang) {
                    return lang;
                }

                var meta = document.querySelector('meta[http-equiv="content-language"], meta[name="language"]');
                return meta && (meta.content || meta.lang) || '';
            })()
        """.trimIndent()

        private val PAGE_TRANSLATED_NODE_COUNT_SCRIPT = """
            (function() {
                var root = document.body || document.documentElement;
                if (!root) return 0;
                var count = 0;
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                while (walker.nextNode()) {
                    if (walker.currentNode.__superxTranslateTarget) count++;
                }
                return count;
            })()
        """.trimIndent()

        private val RESTORE_PAGE_TRANSLATION_SCRIPT = """
            (function() {
                if (!window.__superxTranslateOriginals) {
                    return 0;
                }

                var restored = 0;
                var walker = document.createTreeWalker(
                    document.body || document.documentElement,
                    NodeFilter.SHOW_TEXT
                );

                while (walker.nextNode()) {
                    var node = walker.currentNode;
                    var id = node.__superxTranslateId;
                    if (id &&
                        Object.prototype.hasOwnProperty.call(window.__superxTranslateOriginals, id) &&
                        (!node.__superxTranslatedValue || node.nodeValue === node.__superxTranslatedValue)
                    ) {
                        node.nodeValue = window.__superxTranslateOriginals[id];
                        restored++;
                    }
                    node.__superxTranslateId = null;
                    node.__superxTranslateTarget = null;
                    node.__superxTranslatedValue = null;
                }

                return restored;
            })()
        """.trimIndent()

        private val EXTRACT_TRANSLATABLE_TEXT_SCRIPT = """
            (function(targetLanguage, maxNodes) {
                var root = document.body || document.documentElement;
                if (!root) {
                    return '[]';
                }

                window.__superxTranslateOriginals = window.__superxTranslateOriginals || {};
                window.__superxTranslateMeta = window.__superxTranslateMeta || {};
                window.__superxTranslateCounter = window.__superxTranslateCounter || 1;

                var skipTags = {
                    SCRIPT: true,
                    STYLE: true,
                    NOSCRIPT: true,
                    TEXTAREA: true,
                    INPUT: true,
                    SELECT: true,
                    OPTION: true,
                    CODE: true,
                    PRE: true,
                    SVG: true,
                    CANVAS: true
                };
                var nodes = [];

                function isVisible(element) {
                    if (!element) {
                        return false;
                    }
                    var style = window.getComputedStyle(element);
                    return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
                }

                function cleanText(value) {
                    return (value || '').replace(/\s+/g, ' ').trim();
                }

                function isInViewport(element) {
                    if (!element || !element.getBoundingClientRect) return false;
                    var rect = element.getBoundingClientRect();
                    var width = window.innerWidth || document.documentElement.clientWidth || 0;
                    var height = window.innerHeight || document.documentElement.clientHeight || 0;
                    return rect.bottom >= 0 && rect.right >= 0 && rect.top <= height && rect.left <= width;
                }

                function isClearlyTargetText(text) {
                    if (!targetLanguage || targetLanguage.indexOf('zh') !== 0) return false;
                    var han = 0;
                    var latin = 0;
                    for (var index = 0; index < text.length; index++) {
                        var code = text.charCodeAt(index);
                        if ((code >= 0x3400 && code <= 0x9fff) ||
                            (code >= 0xf900 && code <= 0xfaff)) {
                            han++;
                        } else if ((code >= 0x0041 && code <= 0x005a) ||
                            (code >= 0x0061 && code <= 0x007a) ||
                            (code >= 0x00c0 && code <= 0x024f)) {
                            latin++;
                        }
                    }
                    return han >= 2 && han >= latin * 2;
                }

                function shouldSkipText(text) {
                    if (!text || text.length < 2) {
                        return true;
                    }
                    if (/^https?:\/\//i.test(text)) {
                        return true;
                    }
                    if (!/[A-Za-z\u00C0-\uFFFF]/.test(text)) {
                        return true;
                    }
                    return false;
                }

                var scanLimit = maxNodes * 2 + 1;

                function collectTextNodes(scope) {
                    if (!scope || nodes.length >= scanLimit) return;
                    var walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT, {
                        acceptNode: function(node) {
                            var parent = node.parentElement;
                            if (!parent || skipTags[parent.tagName] || parent.closest('[translate="no"], .notranslate')) {
                                return NodeFilter.FILTER_REJECT;
                            }
                            if (!isVisible(parent)) {
                                return NodeFilter.FILTER_REJECT;
                            }
                            if (node.__superxTranslateTarget === targetLanguage &&
                                node.__superxTranslatedValue === node.nodeValue
                            ) {
                                return NodeFilter.FILTER_REJECT;
                            }
                            if (node.__superxTranslationIgnoredTarget === targetLanguage &&
                                node.__superxTranslationIgnoredValue === node.nodeValue
                            ) {
                                return NodeFilter.FILTER_REJECT;
                            }
                            var text = cleanText(node.nodeValue);
                            if (shouldSkipText(text) || isClearlyTargetText(text)) {
                                return NodeFilter.FILTER_REJECT;
                            }
                            return NodeFilter.FILTER_ACCEPT;
                        }
                    });

                    while (walker.nextNode() && nodes.length < scanLimit) {
                        var node = walker.currentNode;
                        var raw = node.nodeValue || '';
                        var text = cleanText(raw);
                        if (node.__superxTranslateTarget && node.__superxTranslatedValue !== raw) {
                            node.__superxTranslateId = null;
                            node.__superxTranslateTarget = null;
                            node.__superxTranslatedValue = null;
                        }
                        if (node.__superxTranslationIgnoredTarget &&
                            node.__superxTranslationIgnoredValue !== raw
                        ) {
                            node.__superxTranslationIgnoredTarget = null;
                            node.__superxTranslationIgnoredValue = null;
                        }
                        var id = node.__superxTranslateId;
                        if (!id) {
                            id = 'sx_' + (window.__superxTranslateCounter++);
                            node.__superxTranslateId = id;
                            window.__superxTranslateOriginals[id] = raw;
                            window.__superxTranslateMeta[id] = {
                                prefix: (raw.match(/^\s*/) || [''])[0],
                                suffix: (raw.match(/\s*$/) || [''])[0]
                            };
                        }

                        nodes.push({
                            id: id,
                            text: text,
                            visible: isInViewport(node.parentElement),
                            order: nodes.length
                        });
                    }
                }

                collectTextNodes(root);
                nodes.sort(function(first, second) {
                    if (first.visible !== second.visible) return first.visible ? -1 : 1;
                    return first.order - second.order;
                });

                return JSON.stringify({
                    nodes: nodes.slice(0, maxNodes),
                    hasMore: nodes.length > maxNodes
                });
            })(TARGET_LANGUAGE, $MAX_PAGE_TRANSLATION_NODES)
        """.trimIndent()

        private val INSTALL_TRANSLATION_OBSERVER_SCRIPT = """
            (function() {
                if (window.__surfSaveTranslationObserverInstalled) return true;
                var root = document.documentElement || document;
                if (!root || !window.MutationObserver || !window.$TRANSLATION_BRIDGE_NAME) return false;
                var timer = null;
                var observer = new MutationObserver(function(mutations) {
                    var shouldNotify = false;
                    for (var index = 0; index < mutations.length; index++) {
                        var mutation = mutations[index];
                        if (mutation.type === 'characterData') {
                            var textNode = mutation.target;
                            var isOwnTranslation = textNode.__superxTranslateTarget &&
                                textNode.__superxTranslatedValue === textNode.nodeValue;
                            if (!isOwnTranslation) {
                                shouldNotify = true;
                                break;
                            }
                        } else if (mutation.type === 'childList' && mutation.addedNodes.length > 0) {
                            shouldNotify = true;
                            break;
                        }
                    }
                    if (!shouldNotify) return;
                    if (timer) clearTimeout(timer);
                    timer = setTimeout(function() {
                        try { window.$TRANSLATION_BRIDGE_NAME.onContentChanged(); } catch (e) {}
                    }, $TRANSLATION_MUTATION_DEBOUNCE_MS);
                });
                observer.observe(root, { childList: true, subtree: true, characterData: true });
                window.__surfSaveTranslationObserverInstalled = true;
                window.__surfSaveTranslationObserver = observer;
                return true;
            })()
        """.trimIndent()
    }

    private lateinit var suggestionAdapter: TabSuggestionAdapter

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var proxyController: CustomProxyController

    @Inject
    lateinit var okHttpProxyClient: OkHttpProxyClient

    private lateinit var dataBinding: FragmentWebTabBinding

    private lateinit var tabManagerProvider: TabManagerProvider

    private lateinit var pageTabProvider: PageTabProvider

    private lateinit var historyProvider: HistoryProvider

    private lateinit var workerEventProvider: WorkerEventProvider

    private lateinit var currentTabIndexProvider: CurrentTabIndexProvider

    private lateinit var tabViewModel: WebTabViewModel

    private lateinit var videoDetectionTabViewModel: VideoDetectionTabViewModel

    private lateinit var webTab: WebTab

    private var customWebChromeClient: CustomWebChromeClient? = null

    private var canGoCounter = 0

    private var translateJob: Job? = null
    private var translationDebounceJob: Job? = null
    private var translationDocumentGeneration = 0L
    private var mediaPageGeneration = 0L
    private var translationFailureNotifiedGeneration = -1L
    private var translationRerunRequested = false
    private var lastAutoTranslationStartedAt = 0L
    private var pageDominantLanguageResolved = false
    private var pageDominantForeignLanguage: String? = null
    private var pageDeclaredLanguage: String? = null
    private var pageTranslationStateUrl: String? = null
    private var pageLanguageIdentifier: LanguageIdentifier? = null
    private var reusableTranslator: Translator? = null
    private var reusableTranslatorSourceLanguage: String? = null
    private var reusableTranslatorTargetLanguage: String? = null
    private var reusableTranslatorModelReady = false
    private val pageNodeLanguageCache = object :
        LinkedHashMap<String, NodeLanguageCacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, NodeLanguageCacheEntry>?
        ): Boolean = size > MAX_PAGE_NODE_LANGUAGE_CACHE_ENTRIES
    }
    private val pageTranslationCache = object :
        LinkedHashMap<TranslationCacheKey, String>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<TranslationCacheKey, String>?
        ): Boolean = size > MAX_PAGE_TRANSLATION_CACHE_ENTRIES
    }
    private var thumbnailJob: Job? = null
    private var playerRecoveryJob: Job? = null
    private var thumbnailTransitionInProgress = false
    private var tabCloseCaptureInProgress = false
    private var mediaProbeScriptHandler: ScriptHandler? = null
    private val mediaProbeBridge = MediaProbeBridge()
    private val translationBridge = TranslationBridge()
    private val recentMediaProbeEvents = mutableMapOf<String, Long>()
    private var previousFabState: DownloadButtonState? = null
    private val playbackTargetStore by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackTargetStore(requireContext())
    }
    private val downloadButtonStateCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val newState = videoDetectionTabViewModel.downloadButtonState.get()
            if (previousFabState is DownloadButtonStateCanNotDownload
                && newState is DownloadButtonStateCanDownload
            ) {
                animateFabPulse(dataBinding.fab)
            }
            previousFabState = newState
        }
    }

    private data class PageTextNode(
        val id: String,
        val text: String,
        val isInViewport: Boolean
    )

    private data class PageTextExtraction(
        val nodes: List<PageTextNode>,
        val hasMore: Boolean
    )

    private data class TranslationNodeBatch(
        val sourceLanguage: String?,
        val nodes: List<PageTextNode>,
        val ignoredNodeIds: List<String>,
        val hasDeferredNodes: Boolean
    )

    private data class TranslationCacheKey(
        val sourceLanguage: String,
        val targetLanguage: String,
        val text: String
    )

    private data class NodeLanguageCacheEntry(
        val text: String,
        val sourceLanguage: String?
    )

    private class TranslationModelDownloadException(cause: Throwable) : Exception(cause)

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            handleOnBackPress()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val thisTabIndex = requireArguments().getInt(TAB_INDEX_KEY)

        tabManagerProvider = mainActivity.mainViewModel.browserServicesProvider!!
        pageTabProvider = mainActivity.mainViewModel.browserServicesProvider!!
        historyProvider = mainActivity.mainViewModel.browserServicesProvider!!
        workerEventProvider = mainActivity.mainViewModel.browserServicesProvider!!
        currentTabIndexProvider = mainActivity.mainViewModel.browserServicesProvider!!

        tabViewModel = ViewModelProvider(this, viewModelFactory)[WebTabViewModel::class]
        videoDetectionTabViewModel =
            ViewModelProvider(this, viewModelFactory)[VideoDetectionTabViewModel::class]
        videoDetectionTabViewModel.settingsModel = mainActivity.settingsViewModel
        videoDetectionTabViewModel.webTabModel = tabViewModel

        tabViewModel.openPageEvent = tabManagerProvider.getOpenTabEvent()
        tabViewModel.openBackgroundPageEvent = tabManagerProvider.getOpenBackgroundTabEvent()
        tabViewModel.closePageEvent = tabManagerProvider.getCloseTabEvent()
        tabViewModel.thisTabIndex.set(thisTabIndex)

        webTab = pageTabProvider.getPageTab(thisTabIndex)
        videoDetectionTabViewModel.initialUrl = webTab.getUrl()
        mediaPageGeneration = videoDetectionTabViewModel.beginPageContext(
            webTab.getWebView()?.url ?: webTab.getUrl()
        )

        AppLogger.d("onCreate Webview::::::::: ${webTab.getUrl()} $savedInstanceState")
        suggestionAdapter =
            TabSuggestionAdapter(requireContext(), mutableListOf(), suggestionListener)

        val shouldLoadInitialPage = recreateWebView(savedInstanceState)

        dataBinding = FragmentWebTabBinding.inflate(inflater, container, false).apply {
            buildWebTabMenu(this.browserMenuButton, false)

            viewModel = tabViewModel
            browserMenuListener = tabListener
            settingsViewModel = mainActivity.settingsViewModel
            videoTabVModel = videoDetectionTabViewModel

            etSearch.setAdapter(suggestionAdapter)
            etSearch.addTextChangedListener(onInputTabChangeListener)
            this.etSearch.imeOptions = EditorInfo.IME_ACTION_DONE
            this.etSearch.setOnClickListener {
                enterAddressEditMode()
            }
            this.etSearch.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    if (!tabViewModel.isTabInputFocused.get()) {
                        enterAddressEditMode()
                    }
                } else {
                    tabViewModel.changeTabFocus(false)
                }
            }
            this.etSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val submittedText = (this@apply.etSearch as EditText).text.toString()
                    this.etSearch.clearFocus()
                    viewModel?.viewModelScope?.launch {
                        delay(400)
                        tabViewModel.loadPage(submittedText)
                    }
                    false
                } else false
            }
            configureFloatingVideoButton(this)
            applyAddressEditMode(false)

            configureWebView(this)
            configureSwipeRefresh(this)
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, backPressedCallback
        )

        addChangeRouteCallBack()

        tabViewModel.userAgent.set(
            webTab.getWebView()?.settings?.userAgentString
                ?: BrowserFragment.MOBILE_USER_AGENT
        )

        val message = webTab.getMessage()
        if (message != null) {
            message.sendToTarget()
            webTab.flushMessage()
        } else if (shouldLoadInitialPage) {
            tabViewModel.loadPage(webTab.getUrl())
        } else {
            tabViewModel.setTabTextInput(webTab.getWebView()?.url ?: webTab.getUrl(), isForce = true)
            tabViewModel.currentTitle.set(webTab.getWebView()?.title ?: webTab.getTitle())
            tabViewModel.refreshBrowseText(webTab.getWebView()?.url ?: webTab.getUrl(), webTab.getWebView()?.title ?: webTab.getTitle())
            tabViewModel.isShowProgress.set(false)
        }

        return dataBinding.root
    }

    private fun configureSwipeRefresh(fragmentWebTabBinding: FragmentWebTabBinding) {
        fragmentWebTabBinding.swipeRefresh.setColorSchemeResources(
            R.color.colorPrimary,
            R.color.colorAccent
        )
        fragmentWebTabBinding.swipeRefresh.setProgressBackgroundColorSchemeResource(
            R.color.sxSurfaceRaised
        )
        fragmentWebTabBinding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            tabViewModel.isTabInputFocused.get() ||
                webTab.getWebView()?.canScrollVertically(-1) == true
        }
        fragmentWebTabBinding.swipeRefresh.setOnRefreshListener {
            tabListener.onBrowserReloadClicked()
            fragmentWebTabBinding.swipeRefresh.isRefreshing = false
        }
    }

    private fun enterAddressEditMode() {
        tabViewModel.changeTabFocus(true)
    }

    private fun closeAddressEditMode() {
        dataBinding.etSearch.clearFocus()
        tabViewModel.changeTabFocus(false)
    }

    private fun applyAddressEditMode(isEditing: Boolean) {
        if (!::dataBinding.isInitialized) {
            return
        }

        val constraintSet = ConstraintSet()
        constraintSet.clone(dataBinding.browserToolbarContent)
        if (isEditing) {
            constraintSet.connect(
                dataBinding.etSearch.id,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START
            )
            constraintSet.connect(
                dataBinding.etSearch.id,
                ConstraintSet.END,
                dataBinding.browserReloadButton.id,
                ConstraintSet.START
            )
            constraintSet.connect(
                dataBinding.browserReloadButton.id,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END
            )
        } else {
            constraintSet.connect(
                dataBinding.etSearch.id,
                ConstraintSet.START,
                dataBinding.browserForwardButton.id,
                ConstraintSet.END
            )
            constraintSet.connect(
                dataBinding.etSearch.id,
                ConstraintSet.END,
                dataBinding.browserReloadButton.id,
                ConstraintSet.START
            )
            constraintSet.connect(
                dataBinding.browserReloadButton.id,
                ConstraintSet.END,
                dataBinding.tabsOverviewButtonContainer.id,
                ConstraintSet.START
            )
        }
        constraintSet.applyTo(dataBinding.browserToolbarContent)

        dataBinding.browserBackButton.visibility = if (isEditing) View.GONE else View.VISIBLE
        dataBinding.tabsOverviewButtonContainer.visibility = if (isEditing) View.GONE else View.VISIBLE
        dataBinding.browserMenuContainer.visibility = if (isEditing) View.GONE else View.VISIBLE
        dataBinding.browserReloadButton.visibility = View.VISIBLE
        dataBinding.etSearch.setPadding(
            dataBinding.etSearch.paddingLeft,
            dataBinding.etSearch.paddingTop,
            resources.getDimensionPixelSize(R.dimen.padding_normal),
            dataBinding.etSearch.paddingBottom
        )
        dataBinding.swipeRefresh.isEnabled = !isEditing
        dataBinding.etSearch.isClickable = true
        dataBinding.etSearch.isFocusable = isEditing
        dataBinding.etSearch.isFocusableInTouchMode = isEditing
        dataBinding.etSearch.isLongClickable = isEditing
        dataBinding.etSearch.isCursorVisible = isEditing
        dataBinding.etSearch.showSoftInputOnFocus = isEditing
        dataBinding.etSearch.inputType = if (isEditing) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        } else {
            InputType.TYPE_NULL
        }
        dataBinding.etSearch.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (isEditing) R.drawable.search_24px else R.drawable.public_24px,
            0,
            0,
            0
        )
        TextViewCompat.setCompoundDrawableTintList(
            dataBinding.etSearch,
            ColorStateList.valueOf(
                dataBinding.root.context.getColor(
                    if (isEditing) R.color.colorPrimary else R.color.sxTextSecondary
                )
            )
        )
        dataBinding.etSearch.compoundDrawablePadding =
            resources.getDimensionPixelSize(R.dimen.padding_small)

        if (isEditing) {
            dataBinding.browserForwardButton.visibility = View.GONE
        } else {
            updateNavigationButtons()
        }
    }

    override fun shareWebLink() {
        val link = webTab.getWebView()?.url
        if (link != null) {
            shareLink(link)
        }
    }

    override fun bookmarkCurrentUrl() {
        val webview = webTab.getWebView()
        val url = webview?.url
        val favicon = webview?.favicon
        val name = webview?.title

        if (url == null) {
            return
        }

        mainActivity.mainViewModel.bookmark(
            url,
            name ?: url.toUri().host.toString(),
            favicon
        )
    }

    override fun translateCurrentPage() {
        val webView = webTab.getWebView()
        val currentUrl = webTab.getWebView()?.url

        if (webView == null || currentUrl == null || !currentUrl.startsWith("http")) {
            Toast.makeText(
                requireContext(),
                getString(R.string.translate_page_unavailable),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (translateJob?.isActive == true) {
            Toast.makeText(
                requireContext(),
                getString(R.string.translate_page_running),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        translationDebounceJob?.cancel()
        val generation = translationDocumentGeneration
        translateJob = lifecycleScope.launch(Dispatchers.Main) {
            togglePageTranslation(webView, generation)
        }
    }

    override fun refreshVideoDetection() {
        val webView = webTab.getWebView()
        val currentUrl = webView?.url ?: tabViewModel.getTabTextInput().get()
        if (currentUrl.isNullOrBlank() || !currentUrl.startsWith("http")) {
            Toast.makeText(requireContext(), R.string.translate_page_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val userAgent = webView?.settings?.userAgentString
            ?: tabViewModel.userAgent.get()
            ?: BrowserFragment.MOBILE_USER_AGENT
        videoDetectionTabViewModel.viewModelScope.launch(videoDetectionTabViewModel.executorReload) {
            videoDetectionTabViewModel.onReloadPage(currentUrl, userAgent)
        }
        injectMediaProbe(webView)
        capturePageMediaMetadata(webView)
        Toast.makeText(requireContext(), R.string.refresh_video_detection_started, Toast.LENGTH_SHORT).show()
    }

    override fun repairPagePlayer() {
        val webView = webTab.getWebView() ?: return
        webView.evaluateJavascript(KVS_PLAYER_RECOVERY_SCRIPT) { result ->
            AppLogger.d("PLAYER_RECOVERY: manual $result")
        }
        Toast.makeText(requireContext(), R.string.repair_page_player_started, Toast.LENGTH_SHORT).show()
    }

    override fun buildBrowserDiagnosticsReport(): String {
        val webView = webTab.getWebView()
        val backForwardList = runCatching { webView?.copyBackForwardList() }.getOrNull()
        val tabs = tabManagerProvider.getTabsListChangeEvent().get().orEmpty()
        val webTabsCount = tabs.count { !it.isHome() }
        val currentUrl = webView?.url ?: webTab.getUrl()
        val currentTitle = webView?.title ?: webTab.getTitle()
        val userAgent = webView?.settings?.userAgentString
            ?: tabViewModel.userAgent.get()
            ?: BrowserFragment.MOBILE_USER_AGENT

        return buildString {
            append(super.buildBrowserDiagnosticsReport().trimEnd())
            appendLine()
            appendLine()
            appendLine("Current tab index: ${tabViewModel.thisTabIndex.get()}")
            appendLine("Open pages: $webTabsCount/$MAX_WEB_TABS")
            appendLine("Title: $currentTitle")
            appendLine("URL: $currentUrl")
            appendLine("Address text: ${tabViewModel.tabDisplayText.get().orEmpty()}")
            appendLine("Can go back: ${webView?.canGoBack() == true}")
            appendLine("Can go forward: ${webView?.canGoForward() == true}")
            appendLine("History entries: ${backForwardList?.size ?: 0}")
            appendLine("History index: ${backForwardList?.currentIndex ?: -1}")
            appendLine("Loading: ${tabViewModel.isShowProgress.get()} (${tabViewModel.progress.get()}%)")
            appendLine("Detected videos: ${videoDetectionTabViewModel.detectedVideosCount.get()}")
            appendLine("User agent: $userAgent")
        }
    }

    override fun openTabsOverview() {
        runAfterThumbnailCapture { openTabsOverviewAfterCapture() }
    }

    override fun openNewTabPage() {
        runAfterThumbnailCapture { openNewTabPageAfterCapture() }
    }

    private fun openTabsOverviewAfterCapture() {
        super.openTabsOverview()
    }

    private fun openNewTabPageAfterCapture() {
        super.openNewTabPage()
    }

    override fun openHomePage() {
        closeAddressEditMode()
        openNewTabPage()
    }

    override fun canNavigateForwardInCurrentPage(): Boolean {
        return webTab.getWebView()?.canGoForward() == true
    }

    override fun navigateForwardInCurrentPage() {
        tabListener.onBrowserForwardClicked()
    }

    override fun setIsDesktop(isDesktop: Boolean) {
        super.setIsDesktop(isDesktop)
        setUserAgentIsDesktop(isDesktop)
        webTab.getWebView()?.reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        webTab.getWebView()?.saveState(outState)
        if (!outState.isEmpty) {
            webTab.setSavedState(outState)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        updateNavigationButtons()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        handleIndexChangeEvent()
        handleLoadPageEvent()
        handleChangeTabFocusEvent()
        handleWorkerEvent()
        handleOpenDetectedVideos()
        handleVideoPushed()
        handleDetectionFeedback()
        tabViewModel.start()
        videoDetectionTabViewModel.start()
    }

    override fun onDestroyView() {
        cancelTranslationWork()
        videoDetectionTabViewModel.downloadButtonState.removeOnPropertyChangedCallback(
            downloadButtonStateCallback
        )
        mainActivity.progressViewModel.progressInfos.removeOnPropertyChangedCallback(
            progressRingCallback
        )
        dataBinding.fab.animate().cancel()
        dataBinding.fabProgressRing?.animate()?.cancel()
        customWebChromeClient?.dispose()
        webTab.saveWebViewState()
        webTab.getWebView()?.let { detachWebView(it) }
        customWebChromeClient = null
        super.onDestroyView()
    }

    override fun onPause() {
        AppLogger.d("onPause Webview::::::::: ${webTab.getUrl()}")
        cancelTranslationWork()
        if (customWebChromeClient?.isCustomViewShown() != true) {
            captureVisibleTabThumbnail()
        }
        webTab.saveWebViewState()
        onWebViewPause()
        backPressedCallback.isEnabled = false
        super.onPause()
    }

    private fun cancelTranslationWork() {
        translationDebounceJob?.cancel()
        translateJob?.cancel()
        translationRerunRequested = false
    }

    override fun onResume() {
        AppLogger.d("onResume Webview::::::::: ${webTab.getUrl()}")
        super.onResume()
        onWebViewResume()
        customWebChromeClient?.restoreCustomViewAfterResume()
        webTab.getWebView()?.let { webView ->
            webView.evaluateJavascript(INSTALL_TRANSLATION_OBSERVER_SCRIPT, null)
            maybeAutoTranslatePage(webView)
        }
        updateBackPressedCallbackState()
        updateNavigationButtons()
    }

    override fun onDestroy() {
        AppLogger.d("onDestroy Webview::::::::: ${webTab.getUrl()}")
        super.onDestroy()
        translateJob?.cancel()
        translationDebounceJob?.cancel()
        closeTranslationClients()
        thumbnailJob?.cancel()
        playerRecoveryJob?.cancel()
        removeMediaProbeScriptHandler()
        webTab.getWebView()?.removeJavascriptInterface(TRANSLATION_BRIDGE_NAME)
        tabViewModel.stop()
        videoDetectionTabViewModel.stop()
        mainActivity.progressViewModel.progressInfos.removeOnPropertyChangedCallback(
            progressRingCallback
        )
        mainActivity.mainViewModel.currentItem.removeOnPropertyChangedCallback(changeRouteCallBack)
        tabManagerProvider.getTabsListChangeEvent()
            .removeOnPropertyChangedCallback(tabsListChangeListener)
    }

    private fun handleOpenDetectedVideos() {
        videoDetectionTabViewModel.showDetectedVideosEvent.observe(viewLifecycleOwner) {
            navigateToDownloadsWithThumbnail()
        }
    }

    private fun handleVideoPushed() {
        videoDetectionTabViewModel.videoPushedEvent.observe(viewLifecycleOwner) {
            onVideoPushed()
        }
    }

    private fun handleDetectionFeedback() {
        videoDetectionTabViewModel.detectionFeedbackEvent.observe(viewLifecycleOwner) { message ->
            Snackbar.make(dataBinding.containerBrowser, message, Snackbar.LENGTH_LONG)
                .setAnchorView(dataBinding.floatingContainer)
                .show()
        }
    }

    private fun onVideoPushed() {
        // Signature feedback: badge pop + snackbar instead of a modal dialog.
        // (The FAB pulse already fires via downloadButtonStateCallback.)
        animateVideoFoundBadge()

        val isDownloadsVisible = isDetectedVideosTabFragmentVisible()
        val isCond = !tabViewModel.isDownloadDialogShown.get() && !isDownloadsVisible
        if (context != null && mainActivity.settingsViewModel.getVideoAlertState()
                .get() && isCond
        ) {
            tabViewModel.isDownloadDialogShown.set(true)
            val count = videoDetectionTabViewModel.detectedVideosList.get()?.size ?: 1
            Snackbar.make(
                dataBinding.containerBrowser,
                getString(R.string.detected_videos_snackbar, count),
                Snackbar.LENGTH_LONG
            )
                .setAnchorView(dataBinding.floatingContainer)
                .setAction(R.string.action_view) {
                    navigateToDownloadsWithThumbnail()
                    tabViewModel.isDownloadDialogShown.set(false)
                }
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        tabViewModel.isDownloadDialogShown.set(false)
                    }
                })
                .show()
        }
    }

    private fun animateVideoFoundBadge() {
        val badge = dataBinding.videoDetectionBadge ?: return
        val micro = resources.getInteger(R.integer.motion_micro_ms).toLong()
        badge.animate().cancel()
        badge.scaleX = 0.6f
        badge.scaleY = 0.6f
        badge.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(micro)
            .setInterpolator(android.view.animation.OvershootInterpolator())
            .start()
    }

    @OptIn(UnstableApi::class)
    private fun onVideoPreviewPropagate(
        videoInfo: VideoInfo, format: String, isForce: Boolean, sharedView: View? = null
    ) {
        val request = createBrowserPlaybackRequest(videoInfo, format, isForce) ?: return
        val defaultComponent = playbackTargetStore.defaultComponent()
        if (defaultComponent == null) {
            launchBuiltInPlayer(request, sharedView)
            return
        }
        val target = PlaybackTargetResolver.resolve(requireContext(), defaultComponent, true)
        if (target == null) {
            playbackTargetStore.remove(defaultComponent)
            Toast.makeText(
                requireContext(),
                getString(R.string.player_target_unavailable, defaultComponent.packageName),
                Toast.LENGTH_SHORT
            ).show()
            launchBuiltInPlayer(request, sharedView)
            return
        }
        launchExternalPlayer(request, target, sharedView)
    }

    private fun createBrowserPlaybackRequest(
        videoInfo: VideoInfo,
        format: String,
        isForce: Boolean
    ): BrowserPlaybackRequest? {
        val selectedFormat = VideoFormatUi.findFormat(videoInfo, format)
        val mediaUrl = selectedFormat?.url?.takeIf { it.isNotBlank() }
            ?: selectedFormat?.manifestUrl?.takeIf { it.isNotBlank() }
        if (selectedFormat == null || mediaUrl == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.player_playback_error),
                Toast.LENGTH_SHORT
            ).show()
            return null
        }
        val freshCookie = CookieManager.getInstance().getCookie(mediaUrl)
        val playbackHeaders = if (isForce) {
            emptyMap()
        } else {
            MediaRequestHeaderPolicy.forPlayback(
                selectedFormat.httpHeaders.orEmpty(),
                freshCookie
            )
        }
        return BrowserPlaybackRequest(
            title = videoInfo.title,
            mediaUrl = mediaUrl,
            playbackHeadersJson = JSONObject(playbackHeaders).toString(),
            mediaKind = PlaybackMediaKindResolver.resolve(selectedFormat),
            formatId = selectedFormat.formatId.orEmpty(),
            height = selectedFormat.height,
            pageUrl = videoInfo.originalUrl,
            detectedBySuperX = videoInfo.isDetectedBySuperX,
            extractedAt = System.currentTimeMillis()
        )
    }

    private fun launchBuiltInPlayer(
        request: BrowserPlaybackRequest,
        sharedView: View?
    ) {
        // 开播前暂停网页内所有 <video>/<audio>，避免和目标播放器双声道。
        WebViewMediaController.pause(webTab.getWebView())
        val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerFragment.VIDEO_NAME, request.title)
            putExtra(VideoPlayerFragment.VIDEO_SOURCE, VideoPlayerFragment.SOURCE_BROWSER)
            putExtra(VideoPlayerFragment.VIDEO_URL, request.mediaUrl)
            putExtra(VideoPlayerFragment.VIDEO_HEADERS, request.playbackHeadersJson)
            putExtra(
                VideoPlayerFragment.VIDEO_MEDIA_KIND,
                request.mediaKind.name
            )
            putExtra(VideoPlayerFragment.VIDEO_FORMAT_ID, request.formatId)
            putExtra(VideoPlayerFragment.VIDEO_FORMAT_HEIGHT, request.height)
            putExtra(VideoPlayerFragment.VIDEO_PAGE_URL, request.pageUrl)
            putExtra(
                VideoPlayerFragment.VIDEO_DETECTED_BY_SUPER_X,
                request.detectedBySuperX
            )
            putExtra(VideoPlayerFragment.VIDEO_EXTRACTED_AT, request.extractedAt)
        }
        // 共享元素过渡：检测视频 sheet 缩略图 → 播放器变形（与 VideoFragment 列表共用 "surf_video_thumb"）
        val options = sharedView?.let { view ->
            view.transitionName = "surf_video_thumb"
            ActivityOptions.makeSceneTransitionAnimation(
                requireActivity(), view, "surf_video_thumb"
            ).toBundle()
        }
        startActivity(intent, options)
    }

    private fun showPlaybackTargetMenu(
        request: BrowserPlaybackRequest,
        sharedView: View,
        anchorView: View
    ) {
        val targets = PlaybackTargetResolver.availableTargets(
            requireContext(),
            playbackTargetStore,
            getString(R.string.player_target_built_in)
        )
        val menuItems = targets.map<PlaybackTarget, PlaybackTargetMenuItem> {
            PlaybackTargetMenuItem.Target(it)
        } + PlaybackTargetMenuItem.Add
        val popup = ListPopupWindow(requireContext()).apply {
            this.anchorView = anchorView
            setAdapter(PlaybackTargetMenuAdapter(requireContext(), menuItems))
            width = minOf(
                resources.getDimensionPixelSize(R.dimen.playback_target_menu_width),
                resources.displayMetrics.widthPixels -
                    2 * resources.getDimensionPixelSize(R.dimen.padding_normal)
            )
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isModal = true
            setDropDownGravity(Gravity.END)
            setOnItemClickListener { _, _, position, _ ->
                dismiss()
                when (val item = menuItems[position]) {
                    is PlaybackTargetMenuItem.Target -> {
                        if (item.target.isBuiltIn) {
                            playbackTargetStore.setBuiltInAsDefault()
                            launchBuiltInPlayer(request, sharedView)
                        } else {
                            playbackTargetStore.rememberAndSetDefault(
                                requireNotNull(item.target.componentName)
                            )
                            launchExternalPlayer(request, item.target, sharedView)
                        }
                    }

                    PlaybackTargetMenuItem.Add -> showSystemPlayerChooser(request)
                }
            }
        }
        popup.show()
        popup.listView?.setOnItemLongClickListener { _, _, position, _ ->
            val target = (menuItems.getOrNull(position) as? PlaybackTargetMenuItem.Target)?.target
            if (target?.componentName != null) {
                popup.dismiss()
                showRemovePlaybackTargetDialog(target)
            }
            true
        }
    }

    private fun showSystemPlayerChooser(request: BrowserPlaybackRequest) {
        val targetIntent = ExternalPlaybackIntentFactory.createPlaybackIntent(
            request.mediaUrl,
            request.title
        )
        if (targetIntent.resolveActivity(requireContext().packageManager) == null) {
            Toast.makeText(
                requireContext(),
                R.string.player_target_no_compatible_app,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val chooserIntent = ExternalPlaybackIntentFactory.createChooserIntent(
            requireContext(),
            targetIntent,
            getString(R.string.player_target_choose)
        )
        WebViewMediaController.pause(webTab.getWebView())
        try {
            startActivity(chooserIntent)
        } catch (error: ActivityNotFoundException) {
            AppLogger.w("No activity could handle the system player chooser", error)
            Toast.makeText(
                requireContext(),
                R.string.player_target_no_compatible_app,
                Toast.LENGTH_SHORT
            ).show()
        } catch (error: SecurityException) {
            AppLogger.w("System player chooser was blocked", error)
            Toast.makeText(
                requireContext(),
                R.string.player_target_no_compatible_app,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun launchExternalPlayer(
        request: BrowserPlaybackRequest,
        target: PlaybackTarget,
        sharedView: View?
    ) {
        val componentName = requireNotNull(target.componentName)
        val intent = ExternalPlaybackIntentFactory.createPlaybackIntent(
            request.mediaUrl,
            request.title,
            componentName
        )
        WebViewMediaController.pause(webTab.getWebView())
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            fallbackFromUnavailableExternalPlayer(request, target, sharedView, error)
        } catch (error: SecurityException) {
            fallbackFromUnavailableExternalPlayer(request, target, sharedView, error)
        }
    }

    private fun fallbackFromUnavailableExternalPlayer(
        request: BrowserPlaybackRequest,
        target: PlaybackTarget,
        sharedView: View?,
        error: Throwable
    ) {
        val componentName = requireNotNull(target.componentName)
        AppLogger.w(
            "Failed to open external playback target: ${componentName.flattenToShortString()}",
            error
        )
        playbackTargetStore.remove(componentName)
        Toast.makeText(
            requireContext(),
            getString(R.string.player_target_unavailable, target.label),
            Toast.LENGTH_SHORT
        ).show()
        launchBuiltInPlayer(request, sharedView)
    }

    private fun showRemovePlaybackTargetDialog(target: PlaybackTarget) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.player_target_remove_title)
            .setMessage(getString(R.string.player_target_remove_message, target.label))
            .setNegativeButton(R.string.all_text_cancel, null)
            .setPositiveButton(R.string.player_target_remove_action) { _, _ ->
                target.componentName?.let(playbackTargetStore::remove)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.player_target_removed, target.label),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private data class BrowserPlaybackRequest(
        val title: String,
        val mediaUrl: String,
        val playbackHeadersJson: String,
        val mediaKind: PlaybackMediaKind,
        val formatId: String,
        val height: Int,
        val pageUrl: String,
        val detectedBySuperX: Boolean,
        val extractedAt: Long
    )

    private fun onVideoDownloadPropagate(
        videoInfo: VideoInfo, videoTitle: String, format: String
    ) {
        val info = videoInfo.copy(
            id = UUID.randomUUID().toString(),
            title = FileNameCleaner.cleanFileName(videoTitle),
            formats = VideFormatEntityList(
                listOfNotNull(VideoFormatUi.findFormat(videoInfo, format))
                    .ifEmpty { videoInfo.formats.formats.take(1) }
            )
        )

        mainActivity.mainViewModel.downloadVideoEvent.value = info
    }

    private fun recreateWebView(savedInstanceState: Bundle?): Boolean {
        val existingWebView = webTab.getWebView()
        val needsNewWebView = existingWebView == null
        if (needsNewWebView) {
            webTab.setWebView(WebView(requireContext()))
        }

        val stateToRestore = if (needsNewWebView) {
            savedInstanceState?.takeIf { !it.isEmpty } ?: webTab.getSavedState()
        } else {
            null
        }
        var restored = false
        if (stateToRestore != null) {
            restored = webTab.getWebView()?.restoreState(stateToRestore) != null
            if (restored) {
                webTab.clearSavedState()
            }
        }

        webTab.markActive()
        return needsNewWebView && !restored && webTab.getMessage() == null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(fragmentWebTabBinding: FragmentWebTabBinding) {
        val currentWebView = this.webTab.getWebView()

        val webViewClient = CustomWebViewClient(
            tabViewModel,
            mainActivity.settingsViewModel,
            videoDetectionTabViewModel,
            historyProvider.getHistoryVModel(),
            okHttpProxyClient,
            tabManagerProvider.getUpdateTabEvent(),
            pageTabProvider,
            proxyController,
            onNavigationStateChanged = {
                updateNavigationButtons()
            },
            onRenderProcessLost = { lostWebView, didCrash ->
                handleRenderProcessLost(lostWebView, didCrash)
            },
            onPageContextStarted = { tabId, pageUrl ->
                onTranslationNavigationStarted()
                mediaPageGeneration = videoDetectionTabViewModel.beginPageContext(pageUrl)
                tabManagerProvider.onTabNavigationStarted(tabId, pageUrl)
            },
            onPageReady = { webView ->
                capturePageMediaMetadata(webView)
            },
        ) { webView ->
            injectPageScripts(webView)
        }

        val chromeClient = CustomWebChromeClient(
            tabViewModel,
            mainActivity.settingsViewModel,
            tabManagerProvider.getUpdateTabEvent(),
            pageTabProvider,
            fragmentWebTabBinding,
            appUtil,
            mainActivity,
            onProtectedMediaRequested = {
                videoDetectionTabViewModel.markProtectedMedia(mediaPageGeneration)
            }
        )
        customWebChromeClient = chromeClient

        currentWebView?.webChromeClient = chromeClient
        currentWebView?.webViewClient = webViewClient
        currentWebView?.addJavascriptInterface(mediaProbeBridge, MEDIA_PROBE_BRIDGE_NAME)
        currentWebView?.addJavascriptInterface(translationBridge, TRANSLATION_BRIDGE_NAME)
        installDocumentStartMediaProbe(currentWebView)
        updateNavigationButtons()

        val webSettings = webTab.getWebView()?.settings
        val webView = webTab.getWebView()

        webView?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView?.isScrollbarFadingEnabled = true

        webSettings?.apply {
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
            allowContentAccess = true
            allowFileAccess = true
            offscreenPreRaster = false
            displayZoomControls = false
            builtInZoomControls = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            useWideViewPort = true
            domStorageEnabled = true
            javaScriptEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            if (mainActivity.settingsViewModel.isDesktopMode.get()) {
                userAgentString = BrowserFragment.DESKTOP_USER_AGENT
            }
        }
        configureLinkContextMenu(currentWebView)
        val activeWebView = webTab.getWebView() ?: return
        (activeWebView?.parent as? ViewGroup)?.removeView(activeWebView)
        fragmentWebTabBinding.webviewContainer.removeAllViews()
        fragmentWebTabBinding.webviewContainer.addView(
            activeWebView,
            LinearLayout.LayoutParams(-1, -1)
        )
    }

    private fun handleRenderProcessLost(lostWebView: WebView?, didCrash: Boolean) {
        val targetUrl = listOf(
            lostWebView?.url,
            webTab.getWebView()?.url,
            webTab.getUrl()
        ).firstOrNull { it?.startsWith("http") == true }.orEmpty()
        val targetTitle = lostWebView?.title ?: webTab.getWebView()?.title ?: webTab.getTitle()
        AppLogger.e("WebView render process lost. didCrash=$didCrash url=$targetUrl")

        destroyLostWebView(lostWebView ?: webTab.getWebView())
        webTab.setWebView(null)
        webTab.clearSavedState()

        if (targetUrl.isBlank()) {
            tabManagerProvider.getUpdateTabEvent().value = webTab.copyWith(
                webview = null,
                savedState = null
            )
            openNewTabPage()
            return
        }

        val restoredWebView = WebView(requireContext())
        val restoredTab = webTab.copyWith(
            url = targetUrl,
            title = targetTitle,
            webview = restoredWebView,
            savedState = null
        )
        webTab = restoredTab
        tabManagerProvider.getUpdateTabEvent().value = restoredTab

        if (::dataBinding.isInitialized) {
            configureWebView(dataBinding)
            tabViewModel.setTabTextInput(targetUrl, isForce = true)
            tabViewModel.refreshBrowseText(targetUrl, targetTitle)
            tabViewModel.isShowProgress.set(true)
            restoredWebView.loadUrl(targetUrl)
            Toast.makeText(
                requireContext(),
                R.string.webview_restored_after_crash,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun destroyLostWebView(webView: WebView?) {
        if (webView == null) {
            return
        }

        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }.onFailure {
            AppLogger.e("Failed to destroy lost WebView: ${it.message}")
        }
    }

    private fun configureFloatingVideoButton(fragmentWebTabBinding: FragmentWebTabBinding) {
        fragmentWebTabBinding.floatingContainer.setOnClickListener {
            videoDetectionTabViewModel.showVideoInfo()
        }
        fragmentWebTabBinding.floatingContainer.setOnPositionChangeListener { xRatio, yRatio ->
            sharedPrefHelper.saveVideoDetectionButtonPosition(xRatio, yRatio)
        }
        sharedPrefHelper.getVideoDetectionButtonPosition()?.let { (xRatio, yRatio) ->
            fragmentWebTabBinding.floatingContainer.restorePosition(xRatio, yRatio)
        }
        videoDetectionTabViewModel.downloadButtonState.removeOnPropertyChangedCallback(
            downloadButtonStateCallback
        )
        videoDetectionTabViewModel.downloadButtonState.addOnPropertyChangedCallback(
            downloadButtonStateCallback
        )

        // Aggregate download progress ring around the FAB
        mainActivity.progressViewModel.progressInfos.removeOnPropertyChangedCallback(
            progressRingCallback
        )
        mainActivity.progressViewModel.progressInfos.addOnPropertyChangedCallback(
            progressRingCallback
        )
        updateFabProgressRing()
    }

    private val progressRingCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            updateFabProgressRing()
        }
    }

    private fun updateFabProgressRing() {
        if (!::dataBinding.isInitialized) {
            return
        }
        val ring = dataBinding.fabProgressRing ?: return
        val active = mainActivity.progressViewModel.progressInfos.get().orEmpty()
            .filter { it.isActive && !it.isProgressIndeterminate }
        if (active.isEmpty()) {
            ring.visibility = View.GONE
            return
        }
        val percent = active.map { it.progress.coerceIn(0, 100) }.average().toInt()
        ring.visibility = View.VISIBLE
        ring.setProgressCompat(percent, true)
    }

    private fun animateFabPulse(view: View) {
        view.animate()
            .scaleX(1.12f).scaleY(1.12f)
            .setDuration(75)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(75).start()
            }.start()
    }

    private fun updateNavigationButtons() {
        if (!::dataBinding.isInitialized) {
            return
        }

        val webView = webTab.getWebView()
        val hasWebView = webView != null
        val canGoBack = webView?.canGoBack() == true
        val canGoForward = webView?.canGoForward() == true

        if (tabViewModel.isTabInputFocused.get()) {
            dataBinding.browserBackButton.visibility = View.GONE
            dataBinding.browserForwardButton.visibility = View.GONE
            return
        }

        dataBinding.browserBackButton.isEnabled = hasWebView
        dataBinding.browserBackButton.visibility = View.VISIBLE
        dataBinding.browserBackButton.alpha = if (canGoBack) 1f else 0.72f
        dataBinding.browserForwardButton.isEnabled = canGoForward
        dataBinding.browserForwardButton.visibility = if (canGoForward) View.VISIBLE else View.GONE
        dataBinding.browserForwardButton.alpha = if (canGoForward) 1f else 0.38f
    }

    /**
     * 长按菜单：挂 setOnCreateContextMenuListener（AOSP 实现会同时 setLongClickable(true)，这是触发
     * WebView 长按链路——内置保存图片/复制图片/文字选字框/复制链接——的必要条件）。
     * 非链接直接 return 交内置菜单；仅链接类型追加"本窗/新窗/后台打开"三项。
     * 切勿叠加 setOnTouchListener / JS prefetch / setOnLongClickListener —— 前几版长按失败的根因。
     */
    private fun configureLinkContextMenu(webView: WebView?) {
        webView?.setOnCreateContextMenuListener { menu, view, _ ->
            val targetWebView = view as? WebView ?: return@setOnCreateContextMenuListener
            val hit = targetWebView.hitTestResult ?: return@setOnCreateContextMenuListener
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    // 纯文字链接：hitTestResult.extra 即 <a href>
                    val url = normalizeLongPressedUrl(hit.extra?.trim().orEmpty())
                        ?: return@setOnCreateContextMenuListener
                    menu.setHeaderTitle(url)
                    menu.add(0, MENU_OPEN_LINK_CURRENT_WINDOW, 0, getString(R.string.open_link_current_window))
                        .setOnMenuItemClickListener { openLinkInCurrentWindow(url); true }
                    menu.add(0, MENU_OPEN_LINK_NEW_WINDOW, 1, getString(R.string.open_link_new_window))
                        .setOnMenuItemClickListener { openLinkInNewWindow(url); true }
                    menu.add(0, MENU_OPEN_LINK_BACKGROUND_WINDOW, 2, getString(R.string.open_link_background_window))
                        .setOnMenuItemClickListener { openLinkInBackgroundWindow(url); true }
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    // 图片链接：hitTestResult.extra 是 <img src>（封面图），不是 href。
                    // 菜单项点击时用 requestFocusNodeHref 取 <a href>，失败再 JS 反查；绝不用 extra。
                    val imgSrc = hit.extra?.trim().orEmpty()
                    menu.add(0, MENU_OPEN_LINK_CURRENT_WINDOW, 0, getString(R.string.open_link_current_window))
                        .setOnMenuItemClickListener { resolveImageAnchorHref(targetWebView, imgSrc, ::openLinkInCurrentWindow); true }
                    menu.add(0, MENU_OPEN_LINK_NEW_WINDOW, 1, getString(R.string.open_link_new_window))
                        .setOnMenuItemClickListener { resolveImageAnchorHref(targetWebView, imgSrc, ::openLinkInNewWindow); true }
                    menu.add(0, MENU_OPEN_LINK_BACKGROUND_WINDOW, 2, getString(R.string.open_link_background_window))
                        .setOnMenuItemClickListener { resolveImageAnchorHref(targetWebView, imgSrc, ::openLinkInBackgroundWindow); true }
                }
            }
        }
    }

    /**
     * 图片链接专用：取 <a href>。优先 requestFocusNodeHref（官方 API，针对最后触摸节点返回 Bundle "url"=href），
     * 为空才 fallback 用 imgSrc 在 DOM 反查父 <a>。绝不用 hitTestResult.extra（那是 <img src>=封面图）。
     * 在菜单项点击时调（WebView 还在、context menu 已关，不碰长按触发链路）。
     */
    private fun resolveImageAnchorHref(webView: WebView, imgSrc: String, open: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper()) { msg ->
            val url = (msg.obj as? Bundle)?.getString("url")?.trim().orEmpty()
            val normalized = normalizeLongPressedUrl(url)
            if (normalized != null) {
                open(normalized)
            } else {
                resolveImageAnchorHrefByJs(webView, imgSrc, open)
            }
            true
        }
        webView.requestFocusNodeHref(Message.obtain(handler, 0))
    }

    /** requestFocusNodeHref 没拿到 url 时的 fallback：用 imgSrc 反查 DOM 父 <a> href。imgSrc 用 JSONObject.quote 安全转义。 */
    private fun resolveImageAnchorHrefByJs(webView: WebView, imgSrc: String, open: (String) -> Unit) {
        if (imgSrc.isBlank()) {
            AppLogger.d("resolveImageAnchorHref: empty imgSrc, JS fallback skipped")
            return
        }
        val quoted = JSONObject.quote(imgSrc)
        val js = "(function(){try{var s=$quoted;var imgs=document.getElementsByTagName('img');for(var i=0;i<imgs.length;i++){if(imgs[i].src===s){var el=imgs[i];while(el){if(el.tagName==='A'&&el.href)return el.href;el=el.parentElement;}}}}catch(e){}return '';})()"
        webView.evaluateJavascript(js) { result ->
            val href = result?.trim()?.removeSurrounding("\"")?.replace("\\/", "/").orEmpty()
            val normalized = normalizeLongPressedUrl(href)
            if (normalized != null) {
                open(normalized)
            } else {
                AppLogger.d("resolveImageAnchorHref: JS fallback found no href for $imgSrc")
            }
        }
    }

    private fun normalizeLongPressedUrl(rawUrl: String): String? {
        val url = when {
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            rawUrl.startsWith("//") -> "https:$rawUrl"
            else -> null
        }

        if (url.isNullOrBlank()) {
            return null
        }

        return runCatching {
            url.toUri().buildUpon().build().toString()
        }.getOrNull()
    }

    private fun openLinkInCurrentWindow(url: String) {
        videoDetectionTabViewModel.cancelAllCheckJobs()
        tabViewModel.loadPage(url)
    }

    private fun openLinkInNewWindow(url: String) {
        tabViewModel.openPage(url)
    }

    private fun openLinkInBackgroundWindow(url: String) {
        tabViewModel.openPageInBackground(url)
        Toast.makeText(requireContext(), R.string.opened_in_background, Toast.LENGTH_SHORT).show()
    }

    private fun installDocumentStartMediaProbe(webView: WebView?) {
        if (webView == null) {
            return
        }

        removeMediaProbeScriptHandler()

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }

        mediaProbeScriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                MEDIA_PROBE_SCRIPT,
                setOf("*")
            )
        }.onFailure {
            AppLogger.d("MEDIA_PROBE: document-start injection unavailable: ${it.message}")
        }.getOrNull()
    }

    private fun injectMediaProbe(webView: WebView?) {
        webView?.evaluateJavascript(MEDIA_PROBE_SCRIPT, null)
    }

    private fun removeMediaProbeScriptHandler() {
        val handler = mediaProbeScriptHandler ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler.remove()
        }
        mediaProbeScriptHandler = null
    }

    private fun injectPageScripts(webView: WebView?) {
        injectMediaProbe(webView)
        recoverKvsPlayerIfNeededSoon(webView)
        webView?.evaluateJavascript(INSTALL_TRANSLATION_OBSERVER_SCRIPT, null)
        maybeAutoTranslatePage(webView)
        captureTabThumbnailSoon(webView)
    }

    private fun onTranslationNavigationStarted() {
        translationDocumentGeneration++
        translationFailureNotifiedGeneration = -1L
        translationDebounceJob?.cancel()
        translateJob?.cancel()
        translationDebounceJob = null
        translateJob = null
        resetPageTranslationState()
    }

    private fun resetPageTranslationState() {
        translationRerunRequested = false
        lastAutoTranslationStartedAt = 0L
        pageTranslationStateUrl = null
        clearPageTranslationCaches()
    }

    private fun ensurePageTranslationStateForUrl(url: String) {
        if (pageTranslationStateUrl == url) return
        pageTranslationStateUrl = url
        clearPageTranslationCaches()
    }

    private fun clearPageTranslationCaches() {
        pageDominantLanguageResolved = false
        pageDominantForeignLanguage = null
        pageDeclaredLanguage = null
        pageNodeLanguageCache.clear()
        pageTranslationCache.clear()
    }

    private fun closeTranslationClients() {
        pageLanguageIdentifier?.close()
        pageLanguageIdentifier = null
        reusableTranslator?.close()
        reusableTranslator = null
        reusableTranslatorSourceLanguage = null
        reusableTranslatorTargetLanguage = null
        reusableTranslatorModelReady = false
    }

    private fun recoverKvsPlayerIfNeededSoon(webView: WebView?) {
        if (webView == null) {
            return
        }
        playerRecoveryJob?.cancel()
        playerRecoveryJob = lifecycleScope.launch(Dispatchers.Main) {
            var elapsed = 0L
            for (delayMs in PLAYER_RECOVERY_DELAYS_MS) {
                delay((delayMs - elapsed).coerceAtLeast(0L))
                elapsed = delayMs
                if (isAdded && webView.url == webTab.getWebView()?.url) {
                    webView.evaluateJavascript(KVS_PLAYER_RECOVERY_SCRIPT) { result ->
                        AppLogger.d("PLAYER_RECOVERY: auto $result")
                    }
                }
            }
        }
    }

    private fun captureTabThumbnailSoon(webView: WebView?) {
        if (webView == null) {
            return
        }
        thumbnailJob?.cancel()
        thumbnailJob = lifecycleScope.launch(Dispatchers.Main) {
            delay(1_000)
            captureVisibleTabThumbnail(webView)
            delay(2_500)
            captureVisibleTabThumbnail(webView)
        }
    }

    private fun captureVisibleTabThumbnail(
        webView: WebView? = webTab.getWebView(),
        onComplete: () -> Unit = {}
    ) {
        if (!isAdded || webView == null) {
            onComplete()
            return
        }

        val expectedTabId = webTab.id
        val expectedUrl = webView.url.orEmpty()
        WebTabThumbnailCapture.capture(activity?.window, webView) { bitmap ->
            try {
                val pageTab = runCatching {
                    pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())
                }.getOrNull()
                val captureStillMatchesPage = bitmap != null &&
                    isAdded &&
                    webTab.id == expectedTabId &&
                    webTab.getWebView() === webView &&
                    webView.url.orEmpty() == expectedUrl
                if (captureStillMatchesPage) {
                    pageTab?.takeIf { it.id == expectedTabId }?.let { currentPageTab ->
                        val thumbnailPath = BrowserThumbnailStore.save(expectedTabId, bitmap)
                        tabManagerProvider.getUpdateTabEvent().value = currentPageTab.copyWith(
                            url = webView.url ?: currentPageTab.getUrl(),
                            title = webView.title ?: currentPageTab.getTitle(),
                            iconBytes = webView.favicon ?: currentPageTab.getFavicon(),
                            pageThumbnail = bitmap,
                            pageThumbnailPath = thumbnailPath ?: currentPageTab.getPageThumbnailPath(),
                            webview = webView
                        )
                    }
                }
            } finally {
                onComplete()
            }
        }
    }

    private fun runAfterThumbnailCapture(action: () -> Unit) {
        if (thumbnailTransitionInProgress) {
            return
        }
        thumbnailTransitionInProgress = true
        captureVisibleTabThumbnail {
            thumbnailTransitionInProgress = false
            if (isAdded) {
                action()
            }
        }
    }

    private fun maybeAutoTranslatePage(webView: WebView?) {
        if (webView == null || !mainActivity.settingsViewModel.isAutoTranslatePages.get()) {
            return
        }
        val url = webView.url ?: return
        if (!url.startsWith("http")) {
            return
        }

        translationRerunRequested = true
        if (translationDebounceJob?.isActive == true) {
            return
        }

        val generation = translationDocumentGeneration
        translationDebounceJob = lifecycleScope.launch(Dispatchers.Main) {
            do {
                val now = SystemClock.elapsedRealtime()
                val intervalDelay = (
                    lastAutoTranslationStartedAt + MIN_AUTO_TRANSLATION_INTERVAL_MS - now
                ).coerceAtLeast(0L)
                delay(maxOf(TRANSLATION_START_DEBOUNCE_MS, intervalDelay))
                translateJob?.join()
                if (!isTranslationContextCurrent(webView, generation) ||
                    !mainActivity.settingsViewModel.isAutoTranslatePages.get()
                ) {
                    return@launch
                }

                translationRerunRequested = false
                lastAutoTranslationStartedAt = SystemClock.elapsedRealtime()
                val deferred = lifecycleScope.async(Dispatchers.Main) {
                    ensurePageTranslated(webView, generation, silent = true)
                }
                translateJob = deferred
                val hasMore = deferred.await()
                if (translateJob === deferred) {
                    translateJob = null
                }
                if (hasMore) {
                    translationRerunRequested = true
                }
            } while (
                translationRerunRequested &&
                isTranslationContextCurrent(webView, generation) &&
                mainActivity.settingsViewModel.isAutoTranslatePages.get()
            )
        }
    }

    private inner class MediaProbeBridge {
        @JavascriptInterface
        fun onMediaEvent(payload: String?) {
            if (payload.isNullOrBlank() || payload.length > MAX_MEDIA_PROBE_PAYLOAD_LENGTH) {
                return
            }

            lifecycleScope.launch(Dispatchers.Main) {
                handleMediaProbePayload(payload)
            }
        }
    }

    private inner class TranslationBridge {
        @JavascriptInterface
        fun onContentChanged() {
            lifecycleScope.launch(Dispatchers.Main) {
                maybeAutoTranslatePage(webTab.getWebView())
            }
        }
    }

    private fun handleMediaProbePayload(payload: String) {
        if (!isAdded) {
            return
        }

        val event = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val url = event.optString("url", "").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return
        }

        val kind = event.optString("kind", "")
        val contentType = event.optString("contentType", "")
        val mediaType = BrowserMediaClassifier.classify(
            url = url,
            contentType = contentType,
            manifestHint = event.optString("manifestKind", "")
        )
        val method = event.optString("method", "GET").uppercase(Locale.US)
        if (method != "GET" && method != "HEAD") {
            return
        }

        if (mediaType == ContentType.OTHER) {
            return
        }

        val throttleKey = buildMediaProbeThrottleKey(url, contentType)
        val now = SystemClock.elapsedRealtime()
        val previous = recentMediaProbeEvents[throttleKey]
        if (previous != null && now - previous < MEDIA_PROBE_THROTTLE_MS) {
            return
        }
        recentMediaProbeEvents[throttleKey] = now
        trimRecentMediaProbeEvents(now)

        val pageUrl = event.optString("pageUrl", "")
            .ifBlank { webTab.getWebView()?.url.orEmpty() }
            .ifBlank { tabViewModel.getTabTextInput().get().orEmpty() }
        val userAgent = webTab.getWebView()?.settings?.userAgentString
            ?: tabViewModel.userAgent.get()
            ?: BrowserFragment.MOBILE_USER_AGENT
        val request = buildMediaProbeRequest(url, pageUrl, userAgent) ?: return

        val isM3u8 = mediaType == ContentType.M3U8
        val isMpd = mediaType == ContentType.MPD
        when {
            (isM3u8 || isMpd) && mainActivity.settingsViewModel.isCheckIfEveryRequestOnM3u8.get() -> {
                AppLogger.d("MEDIA_PROBE: stream $kind $url")
                videoDetectionTabViewModel.verifyLinkStatus(
                    request,
                    tabViewModel.currentTitle.get(),
                    isM3u8,
                    isMpd
                )
            }

            (mediaType == ContentType.VIDEO || mediaType == ContentType.AUDIO) &&
                isProbeRegularDetectionEnabled(mediaType) -> {
                AppLogger.d("MEDIA_PROBE: direct $kind $url")
                videoDetectionTabViewModel.checkRegularVideoOrAudio(
                    request,
                    mainActivity.settingsViewModel.isCheckOnAudio.get(),
                    mediaType == ContentType.VIDEO
                )
            }
        }
    }

    private fun buildMediaProbeRequest(url: String, pageUrl: String, userAgent: String): Request? {
        return runCatching {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)

            if (pageUrl.startsWith("http://") || pageUrl.startsWith("https://")) {
                builder.header("Referer", pageUrl)
            }

            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) {
                builder.header("Cookie", cookie)
            }

            builder.build()
        }.getOrNull()
    }

    private fun isProbeRegularDetectionEnabled(mediaType: ContentType): Boolean {
        val isAudio = mediaType == ContentType.AUDIO
        val isVideoEnabled = mainActivity.settingsViewModel.getIsFindVideoByUrl().get() ||
            mainActivity.settingsViewModel.getIsCheckEveryRequestOnMp4Video().get()
        val isAudioEnabled = mainActivity.settingsViewModel.isCheckOnAudio.get()

        return if (isAudio) {
            isAudioEnabled
        } else {
            isVideoEnabled
        }
    }

    private fun buildMediaProbeThrottleKey(url: String, contentType: String): String {
        val normalizedUrl = url.substringBefore("#")
            .replace(Regex("""([?&])(utm_[^=&]+|fbclid|gclid|cache_bust|_)=([^&]*)"""), "$1")
            .trimEnd('?', '&')
            .lowercase(Locale.US)
        return "$normalizedUrl|${contentType.lowercase(Locale.US)}"
    }

    private fun trimRecentMediaProbeEvents(now: Long) {
        if (recentMediaProbeEvents.size <= 160) {
            return
        }

        val iterator = recentMediaProbeEvents.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > 60_000L) {
                iterator.remove()
            }
        }
    }

    private suspend fun togglePageTranslation(webView: WebView, generation: Long) {
        if (!isTranslationContextCurrent(webView, generation)) {
            return
        }
        if (translatedNodeCount(webView) > 0) {
            mainActivity.settingsViewModel.setIsAutoTranslatePages(false)
            val restored = restorePageTranslation(webView)
            Toast.makeText(
                requireContext(),
                getString(
                    if (restored > 0) R.string.translate_page_restored
                    else R.string.translate_page_unavailable
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        mainActivity.settingsViewModel.setIsAutoTranslatePages(true)
        val hasMore = ensurePageTranslated(webView, generation, silent = false)
        if (hasMore) {
            maybeAutoTranslatePage(webView)
        }
    }

    private suspend fun ensurePageTranslated(
        webView: WebView,
        generation: Long,
        silent: Boolean
    ): Boolean {
        return try {
            if (!isTranslationContextCurrent(webView, generation)) {
                return false
            }
            ensurePageTranslationStateForUrl(webView.url.orEmpty())

            if (!silent) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.translate_page_extracting),
                    Toast.LENGTH_SHORT
                ).show()
            }

            val targetLanguage = getTargetTranslateLanguage()
            val extraction = extractPageTextNodes(webView, targetLanguage)
            if (extraction.nodes.isEmpty()) {
                if (!silent && translatedNodeCount(webView) == 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.translate_page_no_text),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return false
            }

            val batch = selectTranslationNodeBatch(
                webView = webView,
                nodes = extraction.nodes,
                targetLanguage = targetLanguage
            )
            markIgnoredPageNodes(webView, batch.ignoredNodeIds, targetLanguage)
            val sourceLanguage = batch.sourceLanguage
            val hasMore = sourceLanguage != null &&
                (extraction.hasMore || batch.hasDeferredNodes)
            if (sourceLanguage == null || batch.nodes.isEmpty()) {
                if (!silent && !hasMore) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.translate_page_same_language),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return hasMore
            }

            if (!silent) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.translate_page_downloading_model),
                    Toast.LENGTH_SHORT
                ).show()
            }

            val translations = translateTextNodes(
                batch.nodes,
                sourceLanguage,
                targetLanguage
            )
            if (translations.isEmpty()) {
                if (!silent) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.translate_page_unavailable),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return hasMore
            }

            if (!isTranslationContextCurrent(webView, generation)) {
                return false
            }
            val changedCount = applyPageTranslations(webView, translations, targetLanguage)
            val message = if (changedCount > 0) {
                getString(R.string.translate_page_done, changedCount)
            } else {
                getString(R.string.translate_page_unavailable)
            }
            if (!silent) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
            hasMore
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationModelDownloadException) {
            AppLogger.e("ML Kit translation model download failed: ${e.cause?.message}")
            notifyTranslationFailureOnce(generation, R.string.translate_page_model_failed)
            false
        } catch (e: Throwable) {
            AppLogger.e("ML Kit page translation failed: ${e.message}")
            if (!silent) notifyTranslationFailureOnce(generation, R.string.translate_page_failed)
            false
        }
    }

    private fun isTranslationContextCurrent(webView: WebView, generation: Long): Boolean {
        return isAdded &&
            generation == translationDocumentGeneration &&
            webTab.getWebView() === webView
    }

    private fun notifyTranslationFailureOnce(generation: Long, messageRes: Int) {
        if (!isAdded || generation != translationDocumentGeneration ||
            translationFailureNotifiedGeneration == generation
        ) {
            return
        }
        translationFailureNotifiedGeneration = generation
        Toast.makeText(requireContext(), getString(messageRes), Toast.LENGTH_LONG).show()
    }

    private suspend fun translatedNodeCount(webView: WebView): Int {
        return webView.evaluateJavascriptAwait(PAGE_TRANSLATED_NODE_COUNT_SCRIPT).toJsInt()
    }

    private suspend fun restorePageTranslation(webView: WebView): Int {
        return webView.evaluateJavascriptAwait(RESTORE_PAGE_TRANSLATION_SCRIPT).toJsInt()
    }

    private suspend fun extractPageTextNodes(
        webView: WebView,
        targetLanguage: String
    ): PageTextExtraction {
        val script = EXTRACT_TRANSLATABLE_TEXT_SCRIPT.replace(
            "TARGET_LANGUAGE",
            JSONObject.quote(targetLanguage)
        )
        val result = webView.evaluateJavascriptAwait(script)
        val jsonText = decodeJavascriptString(result)
        if (jsonText.isBlank()) {
            return PageTextExtraction(emptyList(), false)
        }

        return withContext(Dispatchers.Default) {
            val payload = JSONObject(jsonText)
            val array = payload.optJSONArray("nodes") ?: JSONArray()
            val nodes = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val text = item.optString("text")
                    if (id.isNotBlank() && text.isNotBlank()) {
                        add(
                            PageTextNode(
                                id = id,
                                text = text,
                                isInViewport = item.optBoolean("visible", false)
                            )
                        )
                    }
                }
            }
            PageTextExtraction(nodes, payload.optBoolean("hasMore", false))
        }
    }

    private suspend fun selectTranslationNodeBatch(
        webView: WebView,
        nodes: List<PageTextNode>,
        targetLanguage: String
    ): TranslationNodeBatch {
        val orderedNodes = withContext(Dispatchers.Default) {
            nodes.sortedByDescending { it.isInViewport }
        }
        val dominantForeignLanguage = resolvePageDominantForeignLanguage(
            webView,
            orderedNodes,
            targetLanguage
        ) ?: return TranslationNodeBatch(null, emptyList(), emptyList(), false)
        val workByNodeId = withContext(Dispatchers.Default) {
            orderedNodes.associate { node ->
                node.id to PageTranslationLanguagePolicy.resolveNodeLanguageWork(
                    text = node.text,
                    targetLanguage = targetLanguage,
                    dominantForeignLanguage = dominantForeignLanguage
                )
            }
        }

        val selectedNodes = mutableListOf<PageTextNode>()
        val ignoredNodeIds = mutableListOf<String>()
        var selectedCharacters = 0
        var individualIdentifications = 0
        var hasDeferredNodes = false
        val languageIdentifier = getOrCreateLanguageIdentifier()

        for (node in orderedNodes) {
            val cachedDecision = pageNodeLanguageCache[node.id]
                ?.takeIf { it.text == node.text }
            val sourceLanguage = if (cachedDecision != null) {
                cachedDecision.sourceLanguage
            } else {
                val resolvedLanguage = when (workByNodeId[node.id]) {
                    NodeLanguageResolution.SKIP_TARGET,
                    NodeLanguageResolution.SKIP,
                    null -> null
                    NodeLanguageResolution.USE_DOMINANT -> dominantForeignLanguage
                    NodeLanguageResolution.IDENTIFY -> {
                        if (!PageTranslationLanguagePolicy.canIdentifyAnotherNode(
                                individualIdentifications
                            )
                        ) {
                            hasDeferredNodes = true
                            continue
                        }
                        individualIdentifications++
                        val candidates = identifyLanguageCandidates(
                            languageIdentifier,
                            node.text.take(MAX_NODE_LANGUAGE_SAMPLE_LENGTH)
                        )
                        PageTranslationLanguagePolicy.selectNodeSourceLanguage(
                            text = node.text,
                            candidates = candidates,
                            targetLanguage = targetLanguage,
                            dominantForeignLanguage = dominantForeignLanguage,
                            declaredLanguage = pageDeclaredLanguage
                        )
                    }
                }
                pageNodeLanguageCache[node.id] = NodeLanguageCacheEntry(
                    text = node.text,
                    sourceLanguage = resolvedLanguage
                )
                resolvedLanguage
            }

            if (sourceLanguage != dominantForeignLanguage ||
                node.text.length > MAX_SINGLE_TRANSLATION_NODE_CHARACTERS
            ) {
                ignoredNodeIds += node.id
                continue
            }
            if (selectedCharacters + node.text.length > MAX_PAGE_TRANSLATION_CHARACTERS) {
                hasDeferredNodes = true
                continue
            }
            selectedNodes += node
            selectedCharacters += node.text.length
        }

        return TranslationNodeBatch(
            sourceLanguage = dominantForeignLanguage,
            nodes = selectedNodes,
            ignoredNodeIds = ignoredNodeIds,
            hasDeferredNodes = hasDeferredNodes
        )
    }

    private suspend fun resolvePageDominantForeignLanguage(
        webView: WebView,
        nodes: List<PageTextNode>,
        targetLanguage: String
    ): String? {
        if (pageDominantLanguageResolved) {
            return pageDominantForeignLanguage
        }

        pageDeclaredLanguage = resolveMlKitLanguage(
            decodeJavascriptString(webView.evaluateJavascriptAwait(PAGE_LANGUAGE_SCRIPT))
        )
        val dominantSample = withContext(Dispatchers.Default) {
            nodes.asSequence()
                .filterNot {
                    PageTranslationLanguagePolicy.isClearlyTargetText(it.text, targetLanguage)
                }
                .joinToString("\n") { it.text }
                .take(MAX_LANGUAGE_SAMPLE_LENGTH)
        }
        pageDominantForeignLanguage = PageTranslationLanguagePolicy
            .selectDominantForeignLanguage(
                candidates = identifyLanguageCandidates(
                    getOrCreateLanguageIdentifier(),
                    dominantSample
                ),
                targetLanguage = targetLanguage,
                declaredLanguage = pageDeclaredLanguage
            )
        pageDominantLanguageResolved = pageDominantForeignLanguage != null
        return pageDominantForeignLanguage
    }

    private fun getOrCreateLanguageIdentifier(): LanguageIdentifier {
        return pageLanguageIdentifier ?: LanguageIdentification.getClient().also {
            pageLanguageIdentifier = it
        }
    }

    private suspend fun identifyLanguageCandidates(
        languageIdentifier: LanguageIdentifier,
        sample: String
    ): List<TranslationLanguageCandidate> {
        if (sample.isBlank()) return emptyList()
        return languageIdentifier.identifyPossibleLanguages(sample).awaitTask()
            .mapNotNull { identified ->
                resolveMlKitLanguage(identified.languageTag)?.let { language ->
                    TranslationLanguageCandidate(language, identified.confidence)
                }
            }
    }

    private suspend fun translateTextNodes(
        nodes: List<PageTextNode>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> {
        val translator = getOrCreateTranslator(sourceLanguage, targetLanguage)
        if (!reusableTranslatorModelReady) {
            val conditions = DownloadConditions.Builder().build()
            try {
                translator.downloadModelIfNeeded(conditions).awaitTask()
                reusableTranslatorModelReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw TranslationModelDownloadException(e)
            }

        }

        val passTextCache = mutableMapOf<String, String>()
        val translations = linkedMapOf<String, String>()
        for (node in nodes) {
            val cacheKey = TranslationCacheKey(sourceLanguage, targetLanguage, node.text)
            val translated = pageTranslationCache[cacheKey]
                ?: passTextCache[node.text]
                ?: run {
                    PageTranslationLanguagePolicy.chunkText(node.text)
                        .map { chunk -> translator.translate(chunk).awaitTask() }
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                }
            if (translated.isNotBlank()) {
                passTextCache[node.text] = translated
                if (node.text.length <= MAX_CACHED_TRANSLATION_TEXT_LENGTH) {
                    pageTranslationCache[cacheKey] = translated
                }
                translations[node.id] = translated
            }
        }
        return translations
    }

    private fun getOrCreateTranslator(
        sourceLanguage: String,
        targetLanguage: String
    ): Translator {
        val currentTranslator = reusableTranslator
        if (currentTranslator != null &&
            reusableTranslatorSourceLanguage == sourceLanguage &&
            reusableTranslatorTargetLanguage == targetLanguage
        ) {
            return currentTranslator
        }

        currentTranslator?.close()
        reusableTranslator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
        )
        reusableTranslatorSourceLanguage = sourceLanguage
        reusableTranslatorTargetLanguage = targetLanguage
        reusableTranslatorModelReady = false
        return checkNotNull(reusableTranslator)
    }

    private suspend fun markIgnoredPageNodes(
        webView: WebView,
        nodeIds: List<String>,
        targetLanguage: String
    ) {
        if (nodeIds.isEmpty()) return
        val ids = JSONArray()
        nodeIds.forEach(ids::put)
        val serializedIds = JSONObject.quote(ids.toString())
        val serializedTargetLanguage = JSONObject.quote(targetLanguage)
        webView.evaluateJavascriptAwait(
            """
                (function(serializedIds, targetLanguage) {
                    var ids = JSON.parse(serializedIds || '[]');
                    var lookup = Object.create(null);
                    for (var index = 0; index < ids.length; index++) lookup[ids[index]] = true;
                    var walker = document.createTreeWalker(
                        document.body || document.documentElement,
                        NodeFilter.SHOW_TEXT
                    );
                    while (walker.nextNode()) {
                        var node = walker.currentNode;
                        if (lookup[node.__superxTranslateId]) {
                            node.__superxTranslationIgnoredTarget = targetLanguage;
                            node.__superxTranslationIgnoredValue = node.nodeValue;
                        }
                    }
                    return true;
                })($serializedIds, $serializedTargetLanguage)
            """.trimIndent()
        )
    }

    private suspend fun applyPageTranslations(
        webView: WebView,
        translations: Map<String, String>,
        targetLanguage: String
    ): Int {
        val payload = JSONObject()
        translations.forEach { (id, translatedText) ->
            payload.put(id, translatedText)
        }

        return webView.evaluateJavascriptAwait(
            buildApplyTranslationsScript(payload, targetLanguage)
        ).toJsInt()
    }

    private fun buildApplyTranslationsScript(payload: JSONObject, targetLanguage: String): String {
        val serializedPayload = JSONObject.quote(payload.toString())
        val serializedTargetLanguage = JSONObject.quote(targetLanguage)
        return """
            (function(serializedTranslations, targetLanguage) {
                var translations = JSON.parse(serializedTranslations || '{}');
                var metaStore = window.__superxTranslateMeta || {};
                var changed = 0;
                var walker = document.createTreeWalker(
                    document.body || document.documentElement,
                    NodeFilter.SHOW_TEXT
                );

                while (walker.nextNode()) {
                    var node = walker.currentNode;
                    var id = node.__superxTranslateId;
                    if (!id || !Object.prototype.hasOwnProperty.call(translations, id)) {
                        continue;
                    }

                    var translated = translations[id];
                    var meta = metaStore[id] || { prefix: '', suffix: '' };
                    var translatedValue = (meta.prefix || '') + translated + (meta.suffix || '');
                    node.nodeValue = translatedValue;
                    node.__superxTranslateTarget = targetLanguage;
                    node.__superxTranslatedValue = translatedValue;
                    changed++;
                }

                return changed;
            })($serializedPayload, $serializedTargetLanguage)
        """.trimIndent()
    }

    private fun getTargetTranslateLanguage(): String {
        val localeTag = Locale.getDefault().toLanguageTag()
        return resolveMlKitLanguage(localeTag) ?: TranslateLanguage.CHINESE
    }

    private fun resolveMlKitLanguage(languageTag: String?): String? {
        val normalized = languageTag?.trim()?.takeIf { it.isNotBlank() && it != "und" }
            ?: return null

        return TranslateLanguage.fromLanguageTag(normalized)
            ?: TranslateLanguage.fromLanguageTag(normalized.substringBefore("-"))
    }

    private suspend fun WebView.evaluateJavascriptAwait(script: String): String {
        return suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result ?: "null")
                }
            }
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            addOnFailureListener { exception ->
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }
        }
    }

    private fun String.toJsInt(): Int {
        return trim().trim('"').toIntOrNull() ?: 0
    }

    private fun buildTranslateUrl(url: String): String {
        val targetLanguage = Locale.getDefault().toLanguageTag().ifBlank { "en" }
        return "https://translate.google.com/translate?sl=auto&tl=${Uri.encode(targetLanguage)}&u=${Uri.encode(url)}"
    }

    private fun openTranslateExternally(url: String) {
        val translateUri = buildTranslateUrl(url).toUri()
        val chromeIntent = Intent(Intent.ACTION_VIEW, translateUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage("com.android.chrome")
        }

        val intent = if (chromeIntent.resolveActivity(requireContext().packageManager) != null) {
            chromeIntent
        } else {
            Intent(Intent.ACTION_VIEW, translateUri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }

        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.translate_page)))
        }.onFailure {
            Toast.makeText(
                requireContext(),
                getString(R.string.translate_page_unavailable),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val onInputTabChangeListener = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            val input = s.toString()

            if (!tabViewModel.isTabInputFocused.get() || !dataBinding.etSearch.hasFocus()) {
                return
            }

            if (input == tabViewModel.getTabTextInput().get()) {
                return
            }

            tabViewModel.setTabTextInput(input, isForce = true)
            tabViewModel.showTabSuggestions()
            tabViewModel.tabPublishSubject.onNext(input)
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }
    }

    private val suggestionListener = object : SuggestionTabListener {
        override fun onItemClicked(suggestion: HistoryItem) {
            tabViewModel.loadPage(suggestion.url)
        }
    }

    private fun handleChangeTabFocusEvent() {
        tabViewModel.changeTabFocusEvent.observe(viewLifecycleOwner) { isFocus ->
            isFocus.let {
                if (it) {
                    tabViewModel.isTabInputFocused.set(true)
                    applyAddressEditMode(true)
                    dataBinding.etSearch.requestFocus()
                    tabViewModel.showTabSuggestions()
                    appUtil.showSoftKeyboard(dataBinding.etSearch)
                    dataBinding.etSearch.post {
                        dataBinding.etSearch.selectAll()
                        runCatching { dataBinding.etSearch.showDropDown() }
                    }
                } else {
                    tabViewModel.isTabInputFocused.set(false)
                    applyAddressEditMode(false)
                    dataBinding.etSearch.dismissDropDown()
                    appUtil.hideSoftKeyboard(
                        dataBinding.etSearch
                    )
                }
            }
        }
    }

    private fun handleLoadPageEvent() {
        tabViewModel.loadPageEvent.observe(viewLifecycleOwner) { tab ->
            if (tab.getUrl().startsWith("http")) {
                webTab.getWebView()?.stopLoading()
                webTab.getWebView()?.loadUrl(tab.getUrl())
            }
        }
    }

    private fun handleWorkerEvent() {
        workerEventProvider.getWorkerM3u8MpdEvent().observe(viewLifecycleOwner) { state ->
            if (state is DownloadButtonStateCanDownload && state.info?.id?.isNotEmpty() == true) {
                videoDetectionTabViewModel.pushNewVideoInfoToAll(state.info)
                videoDetectionTabViewModel.updateM3u8Loading("m3u8", false)
            }
            if (state is DownloadButtonStateLoading) {
                videoDetectionTabViewModel.updateM3u8Loading("m3u8", true)
                videoDetectionTabViewModel.setButtonState(DownloadButtonStateLoading())
            }
            if (state is DownloadButtonStateCanNotDownload) {
                videoDetectionTabViewModel.updateM3u8Loading("m3u8", false)
                videoDetectionTabViewModel.setButtonState(DownloadButtonStateCanNotDownload())
            }
        }

        workerEventProvider.getWorkerMP4Event().observe(viewLifecycleOwner) { state ->
            if (state is DownloadButtonStateCanDownload && state.info?.id?.isNotEmpty() == true) {
                AppLogger.d("Worker MP4 event CanDownload: ${state.info}")
                videoDetectionTabViewModel.pushNewVideoInfoToAll(state.info)
            } else {
                AppLogger.d("Worker MP4 event state: $state")
            }
        }
    }

    private fun handleIndexChangeEvent() {
        tabManagerProvider.getTabsListChangeEvent()
            .addOnPropertyChangedCallback(tabsListChangeListener)
        syncTabsOverviewBadge()
    }

    private val tabsListChangeListener = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val tabs = tabManagerProvider.getTabsListChangeEvent().get()
            val webTab = tabs?.find { it.id == webTab.id }
            val index = tabs?.indexOf(webTab)
            if (index != null && index in tabs.indices) {
                tabViewModel.thisTabIndex.set(index)
            }
            syncTabsOverviewBadge(tabs)
            updateBackPressedCallbackState()
        }
    }

    private fun syncTabsOverviewBadge(
        tabs: List<WebTab>? = tabManagerProvider.getTabsListChangeEvent().get()
    ) {
        val openTabsCount = tabs.orEmpty().count { !it.isHome() }.coerceAtMost(MAX_WEB_TABS)
        tabViewModel.updateTabsBadgeText(openTabsCount)
    }

    private fun onWebViewPause() {
        WebViewMediaController.pause(webTab.getWebView())
    }

    private fun onWebViewResume() {
        WebViewMediaController.resume(webTab.getWebView())
    }

    private val tabListener = object : BrowserListener {
        override fun onBrowserMenuClicked() {
            dataBinding.browserMenuButton.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            showPopupMenu()
        }

        override fun onHomeClicked() {
            dataBinding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            closeAddressEditMode()
            openNewTabPage()
        }

        override fun onTabsOverviewClicked() {
            dataBinding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            openTabsOverview()
        }

        override fun onBrowserGoClicked() {
            val submittedText = dataBinding.etSearch.text.toString().trim()
            if (submittedText.isBlank()) {
                closeAddressEditMode()
                return
            }

            dataBinding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            dataBinding.etSearch.clearFocus()
            tabViewModel.loadPage(submittedText)
        }

        override fun onBrowserReloadClicked() {
            closeAddressEditMode()
            var url = webTab.getWebView()?.url
            var urlWasChange = false

            if (url?.contains("m.facebook") == true) {
                url = url.replace("m.facebook", "www.facebook")
                urlWasChange = true
                val isDesktop = mainActivity.settingsViewModel.isDesktopMode.get()
                if (!isDesktop) {
                    mainActivity.settingsViewModel.setIsDesktopMode(true)
                }
            }

            val userAgent =
                webTab.getWebView()?.settings?.userAgentString ?: tabViewModel.userAgent.get()
                ?: BrowserFragment.MOBILE_USER_AGENT
            if (url != null) {
                videoDetectionTabViewModel.viewModelScope.launch(videoDetectionTabViewModel.executorReload) {
                    videoDetectionTabViewModel.onReloadPage(url, userAgent)
                }

                if (url.contains("www.facebook") && urlWasChange) {
                    tabViewModel.openPage(url)
                    tabViewModel.closeTab(webTab)
                } else {
                    tabViewModel.onPageReload(webTab.getWebView())
                }
            }
            dataBinding.swipeRefresh.isRefreshing = false
        }


        override fun onTabCloseClicked() {
            tabViewModel.closeTab(webTab)
            videoDetectionTabViewModel.cancelAllCheckJobs()
        }

        override fun onBrowserStopClicked() {
            tabViewModel.onPageStop(webTab.getWebView())
            dataBinding.swipeRefresh.isRefreshing = false
        }

        override fun onBrowserBackClicked() {
            handleCurrentTabBack()
        }

        override fun onBrowserForwardClicked() {
            val webView = webTab.getWebView()
            val canGoForward = webView?.canGoForward()
            if (canGoForward == true) {
                tabViewModel.onGoForward(webView)
                videoDetectionTabViewModel.cancelAllCheckJobs()
                webView.post { updateNavigationButtons() }
            }
        }
    }

    private fun getWebViewClientCompat(webView: WebView?): CustomWebViewClient? {
        return try {
            val getWebViewClientMethod = WebView::class.java.getMethod("getWebViewClient")
            val client = getWebViewClientMethod.invoke(webView) as? CustomWebViewClient
            client
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun handleOnBackPress() {
        if (!shouldHandleBackPress()) {
            backPressedCallback.isEnabled = false
            activity?.onBackPressedDispatcher?.onBackPressed()
            updateBackPressedCallbackState()
            return
        }

        handleCurrentTabBack()
    }

    private fun handleCurrentTabBack() {
        if (customWebChromeClient?.hideCustomViewIfShown() == true) {
            return
        }

        val webView = webTab.getWebView()
        when (
            BrowserBackPolicy.resolveWebTabAction(
                isDetectedVideosVisible = isDetectedVideosTabFragmentVisible(),
                isAddressEditorOpen = tabViewModel.isTabInputFocused.get(),
                canGoBack = webView?.canGoBack() == true
            )
        ) {
            WebTabBackAction.CLOSE_DETECTED_VIDEOS -> {
                mainActivity.supportFragmentManager.popBackStack()
            }

            WebTabBackAction.CLOSE_ADDRESS_EDITOR -> closeAddressEditMode()

            WebTabBackAction.GO_BACK -> {
                if (webView == null) {
                    return
                }
                val history = runCatching { webView.copyBackForwardList() }.getOrNull()
                AppLogger.d(
                    "BROWSER_BACK: tab=${webTab.id} entries=${history?.size ?: 0} " +
                        "index=${history?.currentIndex ?: -1}"
                )
                tabViewModel.onGoBack(webView)
                videoDetectionTabViewModel.cancelAllCheckJobs()
                webView.post { updateNavigationButtons() }
            }

            WebTabBackAction.CLOSE_TAB -> {
                closeCurrentTabAfterThumbnailCapture()
            }
        }
    }

    private fun closeCurrentTabAfterThumbnailCapture() {
        if (tabCloseCaptureInProgress) {
            return
        }
        tabCloseCaptureInProgress = true
        captureVisibleTabThumbnail {
            tabCloseCaptureInProgress = false
            if (!isAdded || !shouldHandleBackPress()) {
                return@captureVisibleTabThumbnail
            }
            closeAddressEditMode()
            videoDetectionTabViewModel.cancelAllCheckJobs()
            tabViewModel.closeTab(webTab)
        }
    }

    private fun shouldHandleBackPress(): Boolean {
        if (!::tabViewModel.isInitialized || !::currentTabIndexProvider.isInitialized) {
            return false
        }

        val viewState = viewLifecycleOwnerLiveData.value?.lifecycle?.currentState ?: return false
        return mainActivity.mainViewModel.currentItem.get() == HOME_TAB_INDEX &&
            currentTabIndexProvider.getCurrentTabIndex().get() == tabViewModel.thisTabIndex.get() &&
            viewState == Lifecycle.State.RESUMED
    }

    private fun updateBackPressedCallbackState() {
        backPressedCallback.isEnabled = isAdded && shouldHandleBackPress()
    }

    private fun setUserAgentIsDesktop(isDesktop: Boolean) {
        val settings = webTab.getWebView()?.settings
        if (isDesktop) {
            settings?.userAgentString = BrowserFragment.DESKTOP_USER_AGENT
        } else {
            settings?.userAgentString = null
        }
    }

    private fun addChangeRouteCallBack() {
        mainActivity.mainViewModel.currentItem.removeOnPropertyChangedCallback(changeRouteCallBack)
        mainActivity.mainViewModel.currentItem.addOnPropertyChangedCallback(changeRouteCallBack)
    }

    private val changeRouteCallBack = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            updateBackPressedCallbackState()
        }
    }

    private fun detachWebView(webView: WebView) {
        runCatching {
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeJavascriptInterface(MEDIA_PROBE_BRIDGE_NAME)
            (webView.parent as? ViewGroup)?.removeView(webView)
        }.onFailure {
            AppLogger.e("Failed to detach WebView: ${it.message}")
        }
    }

    private fun navigateToDownloads() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.setCustomAnimations(
                    R.anim.surf_fragment_enter, R.anim.surf_fragment_exit,
                    R.anim.surf_fragment_pop_enter, R.anim.surf_fragment_pop_exit
                )
                val fragment = DetectedVideosTabFragment.newInstance()
                fragment.detectedVideosTabViewModel = videoDetectionTabViewModel
                fragment.candidateFormatListener = downloadListener
                transaction.add(it.id, fragment, DetectedVideosTabFragment.DOWNLOADS_TAB_TAG)
                transaction.addToBackStack(DetectedVideosTabFragment.DOWNLOADS_TAB_TAG)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.e("Can't get the fragment manager with this")
        }
    }

    private fun navigateToDownloadsWithThumbnail() {
        capturePageMediaMetadata(webTab.getWebView())
        capturePageThumbnailForDetectedVideos {
            navigateToDownloads()
        }
    }

    private fun capturePageMediaMetadata(webView: WebView?) {
        if (webView == null) return
        val generation = mediaPageGeneration
        webView.evaluateJavascript(PAGE_MEDIA_METADATA_SCRIPT) { result ->
            val payload = decodeJavascriptString(result)
            if (payload.length > MAX_PAGE_MEDIA_METADATA_PAYLOAD_LENGTH) {
                AppLogger.d("PAGE_MEDIA_METADATA: ignored oversized payload")
                return@evaluateJavascript
            }
            val metadata = runCatching {
                PageMediaMetadataParser.parse(payload)
            }.onFailure {
                AppLogger.d("PAGE_MEDIA_METADATA: parse failed: ${it.message}")
            }.getOrNull() ?: return@evaluateJavascript
            videoDetectionTabViewModel.updatePageMediaMetadata(generation, metadata)
        }
    }

    private fun capturePageThumbnailForDetectedVideos(onComplete: () -> Unit) {
        val webView = webTab.getWebView()
        if (webView == null) {
            onComplete()
            return
        }

        webView.evaluateJavascript(PAGE_THUMBNAIL_SCRIPT) { result ->
            val thumbnailUrl = decodeJavascriptString(result)
            if (thumbnailUrl.startsWith("http")) {
                videoDetectionTabViewModel.applyThumbnailToDetectedVideos(thumbnailUrl)
            }
            onComplete()
        }
    }

    private fun decodeJavascriptString(value: String?): String {
        if (value.isNullOrBlank() || value == "null") {
            return ""
        }

        return try {
            JSONObject("{\"value\":$value}").optString("value", "")
        } catch (_: Throwable) {
            value.trim('"').replace("\\/", "/")
        }
    }

    private fun isDetectedVideosTabFragmentVisible(): Boolean {
        val fragmentManager = requireActivity().supportFragmentManager
        val fragment =
            fragmentManager.findFragmentByTag(
                DetectedVideosTabFragment.DOWNLOADS_TAB_TAG
            ) as? DetectedVideosTabFragment
        return fragment != null && fragment.isAdded && fragment.isVisible && fragment.isResumed
    }

    private val downloadListener = object : DownloadTabListener {
        override fun onCancel() {
            mainActivity.supportFragmentManager.popBackStack()
        }

        override fun onPreviewVideo(
            videoInfo: VideoInfo, sharedView: View, format: String, isForce: Boolean
        ) {
            onVideoPreviewPropagate(videoInfo, format, isForce, sharedView)
        }

        override fun onChoosePlayer(
            videoInfo: VideoInfo,
            sharedView: View,
            anchorView: View,
            format: String,
            isForce: Boolean
        ) {
            val request = createBrowserPlaybackRequest(videoInfo, format, isForce) ?: return
            showPlaybackTargetMenu(request, sharedView, anchorView)
        }

        override fun onDownloadVideo(
            videoInfo: VideoInfo, format: String, videoTitle: String
        ) {
            onVideoDownloadPropagate(videoInfo, videoTitle, format)
        }

        override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
            val formats =
                videoDetectionTabViewModel.selectedFormats.get()?.toMutableMap() ?: mutableMapOf()
            formats[videoInfo.id] = format
            videoDetectionTabViewModel.selectedFormats.set(formats)
        }

        override fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean {
            val foundFormat = VideoFormatUi.findFormat(videoInfo, format)
            if (foundFormat == null) {
                return false
            }

            ShareCompat.IntentBuilder(mainActivity).setType("text/plain")
                .setChooserTitle(getString(R.string.share_link))
                .setText(foundFormat.url).startChooser()
            return true
        }
    }
}
