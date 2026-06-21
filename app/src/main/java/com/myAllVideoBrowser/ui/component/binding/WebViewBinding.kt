package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

object WebViewBinding {

    @BindingAdapter("loadUrl")
    @JvmStatic
    fun WebView.loadUrl(url: String?) {
        url?.let { if (url.isNotEmpty()) loadUrl(it) }
    }

    @BindingAdapter("javaScriptEnabled")
    @JvmStatic
    fun WebView.javaScriptEnabled(isEnabled: Boolean?) {
        isEnabled?.let { settings.javaScriptEnabled = it }
    }

    @BindingAdapter("webViewClient")
    @JvmStatic
    fun WebView.webViewClient(webViewClient: WebViewClient?) {
        webViewClient?.let { this.webViewClient = it }
    }

    @BindingAdapter("webChromeClient")
    @JvmStatic
    fun WebView.webChromeClient(webChromeClient: WebChromeClient?) {
        webChromeClient?.let { this.webChromeClient = it }
    }
}
