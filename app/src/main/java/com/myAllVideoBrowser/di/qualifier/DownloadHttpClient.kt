package com.myAllVideoBrowser.di.qualifier

import javax.inject.Qualifier

/** 下载专用 OkHttp 客户端：更长的读超时，独立线程池。 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class DownloadHttpClient
