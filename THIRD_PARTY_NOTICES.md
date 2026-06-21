# Third-party Notices

SurfSave depends on open-source Android, Kotlin, media, networking, and proxy projects. This file is an inventory aid; each dependency remains governed by its own license.

Primary dependencies include:

- Android Gradle Plugin, Android SDK, AndroidX AppCompat, Core KTX, Fragment, Lifecycle, RecyclerView, Room, WebKit, WorkManager, ConstraintLayout, SwipeRefreshLayout, SplashScreen, and Media3.
- Kotlin, Kotlin Serialization, Kotlin Symbol Processing, and Kotlin coroutines APIs used through AndroidX and project code.
- Google Material Components for Android.
- Dagger 2.
- OkHttp, OkHttp Logging Interceptor, Retrofit, and PersistentCookieJar.
- RxJava 3 and RxAndroid.
- yt-dlp integration through `io.github.junkfood02.youtubedl-android`.
- FFmpeg components through youtubedl-android and FFmpegKit.
- Xray/libv2ray native proxy components built from `2dust/AndroidLibXrayLite` and related Go modules.
- Glide.
- jsoup.
- Google ML Kit Translate and Language ID.
- TimeAgo.
- JUnit, Mockito, AndroidX Test, Espresso, Jacoco, and Coveralls for tests and quality tooling.

See `gradle/libs.versions.toml`, `app/build.gradle.kts`, and `app/src/main/go/builder/go.mod` for the exact dependency coordinates and versions used by a given commit.

Some packaged metadata files from dependencies are excluded from APK resources by Gradle packaging rules to avoid duplicate Android build entries. This does not change the upstream license obligations for those dependencies.
