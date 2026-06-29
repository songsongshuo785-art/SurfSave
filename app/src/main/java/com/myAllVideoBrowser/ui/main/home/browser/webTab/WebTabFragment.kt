package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.annotation.SuppressLint
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
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
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
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
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
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.DetectedVideosTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.VideoDetectionTabViewModel
import com.myAllVideoBrowser.ui.main.player.VideoPlayerActivity
import com.myAllVideoBrowser.ui.main.player.VideoPlayerFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.FileNameCleaner
import com.myAllVideoBrowser.util.VideoFormatUi
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebTabFragment : BaseWebTabFragment() {

    companion object {
        fun newInstance() = WebTabFragment()

        private const val MAX_PAGE_TRANSLATION_NODES = 120
        private const val MEDIA_PROBE_BRIDGE_NAME = "SuperXMediaProbe"
        private const val MAX_MEDIA_PROBE_PAYLOAD_LENGTH = 8_192
        private const val MEDIA_PROBE_THROTTLE_MS = 4_000L
        private val PLAYER_RECOVERY_DELAYS_MS = longArrayOf(1_500L, 3_500L, 6_500L, 10_000L)
        private const val MENU_OPEN_LINK_CURRENT_WINDOW = 1001
        private const val MENU_OPEN_LINK_NEW_WINDOW = 1002
        private const val MENU_OPEN_LINK_BACKGROUND_WINDOW = 1003

        private val MEDIA_PROBE_SCRIPT = """
            (function() {
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

                function looksLikeMedia(url, contentType) {
                    var cleanUrl = String(url || '').split('#')[0].toLowerCase();
                    var type = String(contentType || '').toLowerCase();

                    return cleanUrl.indexOf('blob:') === 0 ||
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
                        var url = absoluteUrl(rawUrl);
                        if (!looksLikeMedia(url, contentType)) {
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
                            contentType: contentType
                        }));
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
                                send('fetch-response', response.url || requestUrl, {
                                    method: method,
                                    status: response.status || 0,
                                    contentType: response.headers ? (response.headers.get('content-type') || '') : ''
                                });
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

                        try {
                            xhr.addEventListener('readystatechange', function() {
                                if (xhr.readyState >= 2) {
                                    report();
                                }
                            });
                            xhr.addEventListener('load', report);
                        } catch (e) {
                        }

                        return originalSend.apply(this, arguments);
                    };
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

        private val IS_PAGE_TRANSLATED_SCRIPT = """
            (function() {
                return !!window.__superxPageTranslated;
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
                    if (id && Object.prototype.hasOwnProperty.call(window.__superxTranslateOriginals, id)) {
                        node.nodeValue = window.__superxTranslateOriginals[id];
                        restored++;
                    }
                }

                window.__superxPageTranslated = false;
                return restored;
            })()
        """.trimIndent()

        private val EXTRACT_TRANSLATABLE_TEXT_SCRIPT = """
            (function(maxNodes) {
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

                function shouldSkipText(text) {
                    if (!text || text.length < 2 || text.length > 500) {
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

                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                    acceptNode: function(node) {
                        var parent = node.parentElement;
                        if (!parent || skipTags[parent.tagName] || parent.closest('[translate="no"], .notranslate')) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        if (!isVisible(parent)) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        var text = cleanText(node.nodeValue);
                        if (shouldSkipText(text)) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        return NodeFilter.FILTER_ACCEPT;
                    }
                });

                while (walker.nextNode() && nodes.length < maxNodes) {
                    var node = walker.currentNode;
                    var raw = node.nodeValue || '';
                    var text = cleanText(raw);
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

                    nodes.push({ id: id, text: text });
                }

                return JSON.stringify(nodes);
            })($MAX_PAGE_TRANSLATION_NODES)
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

    private var videoToast: Toast? = null

    private var canGoCounter = 0

    private var translateJob: Job? = null
    private var thumbnailJob: Job? = null
    private var playerRecoveryJob: Job? = null
    private var mediaProbeScriptHandler: ScriptHandler? = null
    private val mediaProbeBridge = MediaProbeBridge()
    private val recentMediaProbeEvents = mutableMapOf<String, Long>()
    private var previousFabState: DownloadButtonState? = null
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
        val text: String
    )

    private val backPressedCallback = object : OnBackPressedCallback(true) {
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
        mainActivity.settingsViewModel.setIsAutoTranslatePages(true)
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

        translateJob = lifecycleScope.launch(Dispatchers.Main) {
            translatePageInPlace(webView)
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
            appendLine("Loading: ${tabViewModel.isShowProgress.get()} (${tabViewModel.progress.get()}%)")
            appendLine("Detected videos: ${videoDetectionTabViewModel.detectedVideosCount.get()}")
            appendLine("User agent: $userAgent")
        }
    }

    override fun openTabsOverview() {
        captureVisibleTabThumbnail()
        super.openTabsOverview()
    }

    override fun openNewTabPage() {
        captureVisibleTabThumbnail()
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
        videoDetectionTabViewModel.downloadButtonState.removeOnPropertyChangedCallback(
            downloadButtonStateCallback
        )
        dataBinding.fab.animate().cancel()
        webTab.saveWebViewState()
        webTab.getWebView()?.let { detachWebView(it) }
        super.onDestroyView()
    }

    override fun onPause() {
        AppLogger.d("onPause Webview::::::::: ${webTab.getUrl()}")
        captureVisibleTabThumbnail()
        webTab.saveWebViewState()
        super.onPause()
        onWebViewPause()
        backPressedCallback.remove()
    }

    override fun onResume() {
        AppLogger.d("onResume Webview::::::::: ${webTab.getUrl()}")
        super.onResume()
        onWebViewResume()

        activity?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner, backPressedCallback
        )
        updateNavigationButtons()
    }

    override fun onDestroy() {
        AppLogger.d("onDestroy Webview::::::::: ${webTab.getUrl()}")
        super.onDestroy()
        translateJob?.cancel()
        thumbnailJob?.cancel()
        playerRecoveryJob?.cancel()
        removeMediaProbeScriptHandler()
        tabViewModel.stop()
        videoDetectionTabViewModel.stop()
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
        showToastVideoFound()

        val isDownloadsVisible = isDetectedVideosTabFragmentVisible()
        val isCond = !tabViewModel.isDownloadDialogShown.get() && !isDownloadsVisible
        if (context != null && mainActivity.settingsViewModel.getVideoAlertState()
                .get() && isCond
        ) {
            lifecycleScope.launch(Dispatchers.Main) {
                showAlertVideoFound()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun onVideoPreviewPropagate(
        videoInfo: VideoInfo, format: String, isForce: Boolean, sharedView: View? = null
    ) {
        AppLogger.d(
            "onPreviewVideo: ${videoInfo.formats}  $format"
        )
        val selectedFormat = VideoFormatUi.findFormat(videoInfo, format)

        // 开播前暂停网页内所有 <video>/<audio>，避免和 SurfSave 播放器双声道；try/catch 防页面脚本异常
        webTab.getWebView()?.evaluateJavascript(
            "try{document.querySelectorAll('video,audio').forEach(function(v){v.pause()})}catch(e){}", null
        )

        val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerFragment.VIDEO_NAME, videoInfo.title)
            if (selectedFormat != null) {
                val headers = selectedFormat.httpHeaders?.let {
                    JSONObject(
                        selectedFormat.httpHeaders ?: emptyMap<String, String>()
                    ).toString()
                } ?: "{}"

                putExtra(VideoPlayerFragment.VIDEO_URL, selectedFormat.url)
                val headersFinal = if (isForce) "{}" else headers
                putExtra(VideoPlayerFragment.VIDEO_HEADERS, headersFinal)
            }
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
            mainActivity
        )

        currentWebView?.webChromeClient = chromeClient
        currentWebView?.webViewClient = webViewClient
        currentWebView?.addJavascriptInterface(mediaProbeBridge, MEDIA_PROBE_BRIDGE_NAME)
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
        maybeAutoTranslatePage(webView)
        captureTabThumbnailSoon(webView)
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

    private fun captureVisibleTabThumbnail(webView: WebView? = webTab.getWebView()) {
        if (!isAdded || webView == null) {
            return
        }

        val bitmap = WebTabThumbnailCapture.capture(webView) ?: return

        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())
        val headers = pageTab.getHeaders() ?: emptyMap()
        tabManagerProvider.getUpdateTabEvent().value = WebTab(
            webView.url ?: pageTab.getUrl(),
            webView.title ?: pageTab.getTitle(),
            webView.favicon ?: pageTab.getFavicon(),
            bitmap,
            pageTab.getPageThumbnailPath(),
            headers,
            webView,
            id = pageTab.id
        )
    }

    private fun maybeAutoTranslatePage(webView: WebView?) {
        if (webView == null || !mainActivity.settingsViewModel.isAutoTranslatePages.get()) {
            return
        }
        val url = webView.url ?: return
        if (!url.startsWith("http") || translateJob?.isActive == true) {
            return
        }
        translateJob = lifecycleScope.launch(Dispatchers.Main) {
            translatePageInPlace(webView, silent = true)
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
        val method = event.optString("method", "GET").uppercase(Locale.US)
        if (method != "GET" && method != "HEAD") {
            return
        }

        if (!isMediaProbeCandidate(url, contentType)) {
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

        val isM3u8 = isProbeHls(url, contentType)
        val isMpd = isProbeDash(url, contentType)
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

            isProbeRegularMedia(url, contentType) && isProbeRegularDetectionEnabled(contentType) -> {
                AppLogger.d("MEDIA_PROBE: direct $kind $url")
                videoDetectionTabViewModel.checkRegularVideoOrAudio(
                    request,
                    mainActivity.settingsViewModel.isCheckOnAudio.get(),
                    isProbeVideo(url, contentType)
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
                ?: CookieManager.getInstance().getCookie(pageUrl)
            if (!cookie.isNullOrBlank()) {
                builder.header("Cookie", cookie)
            }

            builder.build()
        }.getOrNull()
    }

    private fun isMediaProbeCandidate(url: String, contentType: String): Boolean {
        return isProbeHls(url, contentType) ||
            isProbeDash(url, contentType) ||
            isProbeRegularMedia(url, contentType)
    }

    private fun isProbeHls(url: String, contentType: String): Boolean {
        val normalized = url.lowercase(Locale.US).substringBefore("#")
        val type = contentType.lowercase(Locale.US)
        return normalized.contains(".m3u8") ||
            type.contains("mpegurl") ||
            type.contains("vnd.apple.mpegurl")
    }

    private fun isProbeDash(url: String, contentType: String): Boolean {
        val normalized = url.lowercase(Locale.US).substringBefore("#")
        val type = contentType.lowercase(Locale.US)
        return normalized.contains(".mpd") ||
            type.contains("dash+xml")
    }

    private fun isProbeRegularMedia(url: String, contentType: String): Boolean {
        val normalized = url.lowercase(Locale.US).substringBefore("#")
        val type = contentType.lowercase(Locale.US)
        if (normalized.contains(".m3u8") || normalized.contains(".mpd")) {
            return false
        }

        if (Regex("""\.(m4s|ts)(\?|$)""").containsMatchIn(normalized)) {
            return false
        }

        return Regex("""\.(mp4|m4v|webm|mov|flv)(\?|$)""").containsMatchIn(normalized) ||
            type.contains("video") ||
            type.contains("audio") ||
            type.contains("mp4")
    }

    private fun isProbeRegularDetectionEnabled(contentType: String): Boolean {
        val isAudio = contentType.contains("audio", ignoreCase = true)
        val isVideoEnabled = mainActivity.settingsViewModel.getIsFindVideoByUrl().get() ||
            mainActivity.settingsViewModel.getIsCheckEveryRequestOnMp4Video().get()
        val isAudioEnabled = mainActivity.settingsViewModel.isCheckOnAudio.get()

        return if (isAudio) {
            isAudioEnabled
        } else {
            isVideoEnabled
        }
    }

    private fun isProbeVideo(url: String, contentType: String): Boolean {
        val normalized = url.lowercase(Locale.US).substringBefore("#")
        val type = contentType.lowercase(Locale.US)
        if (type.contains("audio")) {
            return false
        }

        return type.contains("video") ||
            type.contains("mp4") ||
            Regex("""\.(mp4|m4v|webm|mov|flv)(\?|$)""").containsMatchIn(normalized)
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

    private suspend fun translatePageInPlace(webView: WebView, silent: Boolean = false) {
        try {
            if (isPageTranslated(webView)) {
                val restored = restorePageTranslation(webView)
                val message = if (restored > 0) {
                    R.string.translate_page_restored
                } else {
                    R.string.translate_page_unavailable
                }
                if (!silent) {
                    Toast.makeText(requireContext(), getString(message), Toast.LENGTH_SHORT).show()
                }
                return
            }

            if (!silent) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.translate_page_extracting),
                    Toast.LENGTH_SHORT
                ).show()
            }

            val nodes = extractPageTextNodes(webView)
            if (nodes.isEmpty()) {
                if (!silent) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.translate_page_no_text),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            val targetLanguage = getTargetTranslateLanguage()
            val sourceLanguage = identifySourceLanguage(webView, nodes)
            if (sourceLanguage == null) {
                if (!silent) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.translate_page_unsupported),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            if (sourceLanguage == targetLanguage) {
                if (!silent) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.translate_page_same_language),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            if (!silent) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.translate_page_downloading_model),
                    Toast.LENGTH_SHORT
                ).show()
            }

            val translations = translateTextNodes(nodes, sourceLanguage, targetLanguage)
            if (translations.isEmpty()) {
                if (!silent) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.translate_page_unavailable),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            val changedCount = applyPageTranslations(webView, translations)
            val message = if (changedCount > 0) {
                getString(R.string.translate_page_done, changedCount)
            } else {
                getString(R.string.translate_page_unavailable)
            }
            if (!silent) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            AppLogger.e("ML Kit page translation failed: ${e.message}")
            if (!silent) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.translate_page_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun isPageTranslated(webView: WebView): Boolean {
        return webView.evaluateJavascriptAwait(IS_PAGE_TRANSLATED_SCRIPT).trim() == "true"
    }

    private suspend fun restorePageTranslation(webView: WebView): Int {
        return webView.evaluateJavascriptAwait(RESTORE_PAGE_TRANSLATION_SCRIPT).toJsInt()
    }

    private suspend fun extractPageTextNodes(webView: WebView): List<PageTextNode> {
        val result = webView.evaluateJavascriptAwait(EXTRACT_TRANSLATABLE_TEXT_SCRIPT)
        val jsonText = decodeJavascriptString(result)
        if (jsonText.isBlank()) {
            return emptyList()
        }

        val array = JSONArray(jsonText)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val text = item.optString("text")
                if (id.isNotBlank() && text.isNotBlank()) {
                    add(PageTextNode(id, text))
                }
            }
        }
    }

    private suspend fun identifySourceLanguage(
        webView: WebView,
        nodes: List<PageTextNode>
    ): String? {
        val declaredLanguage = decodeJavascriptString(webView.evaluateJavascriptAwait(PAGE_LANGUAGE_SCRIPT))
        resolveMlKitLanguage(declaredLanguage)?.let { return it }

        val sample = nodes.joinToString("\n") { it.text }.take(1_000)
        if (sample.isBlank()) {
            return null
        }

        val languageIdentifier = LanguageIdentification.getClient()
        return try {
            resolveMlKitLanguage(languageIdentifier.identifyLanguage(sample).awaitTask())
        } finally {
            languageIdentifier.close()
        }
    }

    private suspend fun translateTextNodes(
        nodes: List<PageTextNode>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        val translator = Translation.getClient(options)

        return try {
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).awaitTask()

            val textCache = mutableMapOf<String, String>()
            val translations = linkedMapOf<String, String>()
            for (node in nodes) {
                val translated = textCache.getOrPut(node.text) {
                    translator.translate(node.text).awaitTask()
                }
                if (translated.isNotBlank()) {
                    translations[node.id] = translated
                }
            }
            translations
        } finally {
            translator.close()
        }
    }

    private suspend fun applyPageTranslations(
        webView: WebView,
        translations: Map<String, String>
    ): Int {
        val payload = JSONObject()
        translations.forEach { (id, translatedText) ->
            payload.put(id, translatedText)
        }

        return webView.evaluateJavascriptAwait(buildApplyTranslationsScript(payload)).toJsInt()
    }

    private fun buildApplyTranslationsScript(payload: JSONObject): String {
        val serializedPayload = JSONObject.quote(payload.toString())
        return """
            (function(serializedTranslations) {
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
                    node.nodeValue = (meta.prefix || '') + translated + (meta.suffix || '');
                    changed++;
                }

                window.__superxPageTranslated = changed > 0;
                return changed;
            })($serializedPayload)
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
        }
    }

    private fun syncTabsOverviewBadge(
        tabs: List<WebTab>? = tabManagerProvider.getTabsListChangeEvent().get()
    ) {
        val openTabsCount = tabs.orEmpty().count { !it.isHome() }.coerceAtMost(MAX_WEB_TABS)
        tabViewModel.updateTabsBadgeText(openTabsCount)
    }

    private fun onWebViewPause() {
        webTab.getWebView()?.onPause()
    }

    private fun onWebViewResume() {
        webTab.getWebView()?.onResume()
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
            val webView = webTab.getWebView()
            val canGoBack = webView?.canGoBack()
            if (canGoBack == true) {
                tabViewModel.onGoBack(webView)
                videoDetectionTabViewModel.cancelAllCheckJobs()
                webView.post { updateNavigationButtons() }
            } else {
                closeAddressEditMode()
                openNewTabPage()
            }
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

    private fun showAlertVideoFound() {
        if (!tabViewModel.isDownloadDialogShown.get()) {
            tabViewModel.isDownloadDialogShown.set(true)
            val client = getWebViewClientCompat(webTab.getWebView())

            client?.videoAlert =
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.video_found)
            client?.videoAlert?.setOnDismissListener {
                client.videoAlert = null
            }
            client?.videoAlert?.setMessage(R.string.whatshould)?.setPositiveButton(
                R.string.view
            ) { dialog, _ ->
                navigateToDownloadsWithThumbnail()
                tabViewModel.isDownloadDialogShown.set(false)
                dialog.dismiss()
            }?.setNeutralButton(R.string.dontshow) { dialog, _ ->
                mainActivity.settingsViewModel.setShowVideoAlertOff()
                tabViewModel.isDownloadDialogShown.set(false)
                dialog.dismiss()
            }?.setNegativeButton(R.string.all_text_cancel) { dialog, _ ->
                tabViewModel.isDownloadDialogShown.set(false)
                dialog.dismiss()
            }?.show()
        }
    }

    private fun handleOnBackPress() {
        if (tabViewModel.isTabInputFocused.get()) {
            closeAddressEditMode()
            return
        }

        val isBrowserRoute = mainActivity.mainViewModel.currentItem.get() == 0
        val isCurrentTabSelected =
            currentTabIndexProvider.getCurrentTabIndex().get() == tabViewModel.thisTabIndex.get()
        val isStateResumed = viewLifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED

        if (isStateResumed && isBrowserRoute && isCurrentTabSelected && isVisible) {
            tabListener.onBrowserBackClicked()
        }
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
            val indexRoute = mainActivity.mainViewModel.currentItem.get()
            val currentTabIndexSelected = currentTabIndexProvider.getCurrentTabIndex().get()
            val isCurrentTabSelected =
                currentTabIndexSelected == tabViewModel.thisTabIndex.get()
            val isBrowserRoute = indexRoute == 0
            val isNotHomeTabSelected = currentTabIndexSelected != HOME_TAB_INDEX
            val isVisible = this@WebTabFragment.isVisible
            if (isBrowserRoute && isNotHomeTabSelected && isCurrentTabSelected && isVisible) {
                activity?.onBackPressedDispatcher?.addCallback(
                    viewLifecycleOwner, backPressedCallback
                )
            } else {
                backPressedCallback.remove()
            }
        }
    }

    private fun showToastVideoFound() {
        val context = context

        if (context != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                videoToast?.cancel()
                videoToast = Toast.makeText(
                    context, context.getString(R.string.video_found), Toast.LENGTH_SHORT
                )
                videoToast?.show()
            }, 1)
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
        capturePageThumbnailForDetectedVideos {
            navigateToDownloads()
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
