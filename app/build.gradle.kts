import com.android.build.api.variant.FilterConfiguration
import org.gradle.kotlin.dsl.support.serviceOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.coveralls)
    kotlin("kapt")
    id("jacoco")
}

// =========================================================================
// KOTLIN & DEPENDENCY CONFIGURATIONS
// =========================================================================

configurations.configureEach {
    resolutionStrategy {
        force(libs.kotlin.stdlib)
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

allOpen {
    annotation("com.myAllVideoBrowser.OpenForTesting")
}

jacoco {
    version = "0.8.1"
}

// =========================================================================
// BUILD CONFIGURATION VARIABLES
// =========================================================================

val splitApks = System.getenv("SPLITS_INCLUDE")?.toBoolean() ?: true
val skipGoBuild = (project.findProperty("SKIP_GO_BUILD")?.toString()
    ?: System.getenv("SKIP_GO_BUILD"))
    ?.toBoolean() ?: false
val abiFilterList = (project.findProperty("ABI_FILTERS") as? String ?: "").split(';')
val baseVersionCode = 1_777_830_478
val baseVersionName = "0.8.27"
val exportStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
val testBuildVersionCode = ((project.findProperty("TEST_BUILD_CODE")?.toString()
    ?: System.getenv("TEST_BUILD_CODE"))?.toIntOrNull()
    ?: baseVersionCode)
val testVersionName = (project.findProperty("TEST_VERSION_NAME")?.toString()
    ?: System.getenv("TEST_VERSION_NAME"))
    ?: baseVersionName
val abiCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4
)

// =========================================================================
// ANDROID CONFIGURATION
// =========================================================================

android {
    namespace = "com.myAllVideoBrowser"
    compileSdk = libs.versions.targetSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    // Compile Options
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    // Dependencies Info
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Packaging Options
    packaging {
        resources {
            excludes += listOf(
                "mozilla/public-suffix-list.txt",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libffmpeg.zip.so",
                "**/libpython.zip.so",
                "**/libffmpeg.so",
                "**/libffprobe.so",
                "**/libgojni.so",
                "**/libpython.so",
                "**/libqjs.so"
            )
        }
    }

    // Signing Configurations
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    // Default Config
    defaultConfig {
        applicationId = "com.surfsave.browser"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = baseVersionCode
        versionName = baseVersionName
        buildConfigField("String", "MIGRATION_ROLE", "\"new_identity\"")
        buildConfigField("boolean", "MIGRATION_EXPORT_ENABLED", "true")
        buildConfigField("boolean", "MIGRATION_IMPORT_ENABLED", "true")
        buildConfigField("String", "MIGRATION_COMPANION_PACKAGE", "\"com.myAllVideoBrowser\"")

        if (splitApks) {
            splits {
                abi {
                    isEnable = true
                    reset()
                    include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
                    isUniversalApk = true
                }
            }
        } else {
            ndk {
                abiFilters.addAll(abiFilterList)
            }
        }
    }

    // Build Types
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
            signingConfig?.enableV1Signing = true
            signingConfig?.enableV2Signing = true
        }
        create("diagnostic") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
        release {
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Data Binding & Build Features
    dataBinding {
        enable = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Test Options
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // Android Components - Version Code Adjustment
    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                val name = if (splitApks) {
                    output.filters.find {
                        it.filterType == FilterConfiguration.FilterType.ABI
                    }?.identifier
                } else {
                    abiFilterList.getOrNull(0)
                }

                val abiCode = abiCodes[name] ?: 0
                val variantBaseVersionCode = if (variant.buildType == "release") {
                    baseVersionCode
                } else {
                    testBuildVersionCode
                }

                output.versionCode.set(variantBaseVersionCode + abiCode)
                if (variant.buildType != "release") {
                    output.versionName.set(testVersionName)
                }
            }
        }
    }

    // Lint Options
    lint {
        abortOnError = true
        disable += setOf(
            // Version upgrades are handled as explicit migration tasks because several
            // catalog pins are tied to build compatibility or WebView runtime stability.
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable"
        )
    }

    // Source Sets
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

tasks.register("exportDiagnosticApks") {
    group = "distribution"
    description = "Builds diagnostic APKs and exports uniquely named files for phone testing."
    dependsOn("assembleDiagnostic")

    val exportRoot = layout.buildDirectory.dir("outputs/share-apks")
    outputs.dir(exportRoot)
    outputs.upToDateWhen { false }

    doLast {
        if (skipGoBuild) {
            throw GradleException(
                "exportDiagnosticApks must include libgojni.so. Run without SKIP_GO_BUILD=true."
            )
        }

        val exportDir = exportRoot.get().asFile.resolve(exportStamp)
        exportDir.mkdirs()

        val sourceDir = layout.buildDirectory.dir("outputs/apk/diagnostic").get().asFile
        val apkFiles = sourceDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension.equals("apk", ignoreCase = true)
            }
            .toList()

        if (apkFiles.isEmpty()) {
            throw GradleException("No diagnostic APKs found in ${sourceDir.absolutePath}")
        }

        apkFiles.forEach { apkFile ->
            val abi = when {
                apkFile.name.contains("arm64-v8a") -> "arm64-v8a"
                apkFile.name.contains("armeabi-v7a") -> "armeabi-v7a"
                apkFile.name.contains("x86_64") -> "x86_64"
                apkFile.name.contains("x86") -> "x86"
                apkFile.name.contains("universal") -> "universal"
                else -> "unknown"
            }
            val shortAbi = when (abi) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a" -> "armv7"
                else -> abi
            }
            val exportName = "SurfSave-$testVersionName-$shortAbi.apk"
            val requiredEntries = when (abi) {
                "universal" -> abiCodes.keys.map { "lib/$it/libgojni.so" }
                "unknown" -> emptyList()
                else -> listOf("lib/$abi/libgojni.so")
            }

            if (requiredEntries.isEmpty()) {
                throw GradleException("Cannot determine APK ABI for ${apkFile.name}")
            }

            ZipFile(apkFile).use { apkZip ->
                val missingEntries = requiredEntries.filter { apkZip.getEntry(it) == null }
                if (missingEntries.isNotEmpty()) {
                    throw GradleException(
                        "${apkFile.name} is missing required native library entries: ${missingEntries.joinToString()}"
                    )
                }
            }

            apkFile.copyTo(exportDir.resolve(exportName), overwrite = true)
        }

        logger.lifecycle("Exported phone-test APKs to: ${exportDir.absolutePath}")
        logger.lifecycle("Phone-test versionName=$testVersionName baseVersionCode=$testBuildVersionCode")
    }
}

tasks.matching {
    it.name.contains("Diagnostic", ignoreCase = true) &&
        (
            it.name.startsWith("process") ||
                it.name.startsWith("package")
            )
}.configureEach {
    inputs.property("phoneTestBuildStamp", exportStamp)
    inputs.property("phoneTestBuildVersionCode", testBuildVersionCode)
}

// =========================================================================
// DEPENDENCIES
// =========================================================================

dependencies {
    println("\n📦 Resolving Dependencies...")

    // Core Android Libraries
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.swiperefreshlayout)
    implementation(libs.webkit)
    implementation(libs.coreKtx)
    implementation(libs.coreSplashscreen)
    implementation(libs.legacySupportV4)

    // Kotlin
    implementation(libs.kotlin.stdlib)

    // Coroutines & Work Manager
    implementation(libs.workRuntimeKtx)
    implementation(libs.workRxjava3)
    implementation(libs.workMultiprocess)
    implementation(libs.fragmentKtx)
    implementation(libs.concurrentFuturesKtx)

    // Lifecycle Components
    implementation(libs.lifecycleExtensions)
    implementation(libs.lifecycleCommonJava8)
    implementation(libs.lifecycleLivedata)
    implementation(libs.lifecycleViewmodel)

    // Room Database
    implementation(libs.roomRuntime)
    implementation(libs.roomKtx)
    implementation(libs.roomRxjava3)
    implementation(libs.roomGuava)
    ksp(libs.roomCompiler)

    // Dagger 2 - Dependency Injection
    implementation(libs.daggerRuntime)
    implementation(libs.daggerAndroid)
    implementation(libs.daggerAndroidSupport)
    ksp(libs.daggerCompiler)
    ksp(libs.daggerAndroidProcessor)

    // Network - OkHttp & Retrofit
    implementation(libs.okHttpRuntime)
    implementation(libs.okHttpLogging)
    implementation(libs.retrofitRuntime)
    implementation(libs.retrofitGson)
    implementation(libs.retrofitRxjava3)
    implementation(libs.persistentCookieJar)

    // RxJava 3
    implementation(libs.rxjava3)
    implementation(libs.rxandroid3)

    // Media & Video Processing
    implementation(libs.youtubedl)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.ffmpegKit)
    implementation(libs.media3Exoplayer)
    implementation(libs.media3ExoplayerDash)
    implementation(libs.media3ExoplayerHls)
    implementation(libs.media3ExoplayerRtsp)
    implementation(libs.media3Ui)
    implementation(libs.media3Extractor)
    implementation(libs.media3Database)
    implementation(libs.media3Decoder)
    implementation(libs.media3Datasource)
    implementation(libs.media3Common)
    implementation(libs.media3DatasourceOkhttp)

    // Image Loading
    implementation(libs.glideRuntime)

    // Utilities
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.kotlinxSerializationCore)
    implementation(libs.mlkitTranslate)
    implementation(libs.mlkitLanguageId)
    implementation(libs.jsoup)
    implementation(libs.timeago)

    // Desugar for Java 8+ APIs
    coreLibraryDesugaring(libs.desugarJdk)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockitoCore)
    testImplementation(libs.mockitoKotlin)
    testImplementation(libs.okHttpMockWebServer)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.testRunner)
    androidTestImplementation(libs.mockitoAndroid)
    androidTestImplementation(libs.espressoCore)
    androidTestImplementation(libs.espressoIntents)

    println("✓ Dependencies resolved\n")
}

// =========================================================================
// KSP CONFIGURATION
// =========================================================================

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

// =========================================================================
// COVERALLS CONFIGURATION
// =========================================================================

tasks.named("coveralls") {
    dependsOn("check")
    onlyIf { System.getenv("COVERALLS_REPO_TOKEN") != null }
}

// =========================================================================
// GO REPRODUCIBLE BUILD SETUP (Multi-Architecture)
// =========================================================================
val execOps = project.serviceOf<ExecOperations>()

// V2Ray Repository Configuration
val v2rayRepo = "https://github.com/2dust/AndroidLibXrayLite.git"
val v2rayCommit = "d783dc8ea75afa0ff8fc9dcd51a426a9a67f6a70"
val buildDirV2ray = file("${project.rootDir}/build/v2ray")

// Go Executable Detection
val goExecutable = run {
    val envOverride = System.getenv("GO_EXECUTABLE")
    if (envOverride != null && file(envOverride).exists()) return@run envOverride

    val propOverride = project.findProperty("GO_EXECUTABLE")?.toString()
    if (propOverride != null && file(propOverride).exists()) return@run propOverride

    val candidates = listOf(
        "/opt/homebrew/bin/go",
        "/usr/local/go/bin/go",
        "/usr/local/bin/go",
        "/usr/bin/go"
    )
    candidates.find { file(it).exists() } ?: "go"
}

// Git Executable Detection
val gitExecutable = if (file("/usr/bin/git").exists()) "/usr/bin/git" else "git"
val goProxy = (project.findProperty("GO_PROXY")?.toString()
    ?: System.getenv("GO_PROXY")
    ?: System.getenv("GOPROXY"))
    ?: "https://goproxy.cn,https://proxy.golang.org,direct"

// =========================================================================
// NDK PATH DETECTION & VALIDATION
// =========================================================================

fun findNdkPath(): String {
    val envVar = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT")
    if (!envVar.isNullOrEmpty()) {
        println("✓ Found NDK path in environment variable: $envVar")
        return envVar
    }

    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        val properties = Properties()
        localPropertiesFile.inputStream().use { properties.load(it) }
        val sdkDir = properties.getProperty("sdk.dir")
        if (sdkDir != null) {
            val sideBySideNdk = file("${sdkDir}/ndk/${libs.versions.ndk.get()}")
            if (sideBySideNdk.exists()) {
                println("鉁?Found NDK path from sdk.dir and ndkVersion: ${sideBySideNdk.absolutePath}")
                return sideBySideNdk.absolutePath
            }
        }

        val propVar = properties.getProperty("ndk.dir")
        if (propVar != null) {
            println("✓ Found NDK path in local.properties: $propVar")
            return propVar
        }
    }

    throw GradleException(
        "✗ NDK path not found. Please define one of:\n" +
        "  1. Environment: ANDROID_NDK_HOME or ANDROID_NDK_ROOT\n" +
        "  2. Property: ndk.dir in local.properties"
    )
}

fun validateNdkPath(ndkPath: String): String {
    val prebuiltToolchainsDir = file("${ndkPath}/toolchains/llvm/prebuilt")

    if (!prebuiltToolchainsDir.exists()) {
        throw GradleException(
            "✗ NDK toolchains prebuilt directory not found at: ${prebuiltToolchainsDir}\n" +
            "  Verify your NDK installation and configuration."
        )
    }

    val prebuiltChildren = prebuiltToolchainsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    if (prebuiltChildren.isEmpty()) {
        throw GradleException("✗ No prebuilt toolchain directory found under ${prebuiltToolchainsDir}")
    }

    val ndkPrebuiltFolder = (prebuiltChildren.find { it.name.contains("darwin") } ?: prebuiltChildren[0]).name
    println("✓ Using NDK prebuilt folder: $ndkPrebuiltFolder")
    return ndkPrebuiltFolder
}

// =========================================================================
// ARCHITECTURE CONFIGURATIONS
// =========================================================================

data class ArchConfig(
    val abi: String,
    val goArch: String,
    val target: String
)

val archConfigs = listOf(
    ArchConfig("arm64-v8a", "arm64", "aarch64-linux-android"),
    ArchConfig("armeabi-v7a", "arm", "armv7a-linux-androideabi"),
    ArchConfig("x86_64", "amd64", "x86_64-linux-android"),
    ArchConfig("x86", "386", "i686-linux-android")
)

// =========================================================================
// GO BUILD HELPER FUNCTIONS
// =========================================================================

fun verifyGoExecutable(builderDir: File, executablePath: String) {
    try {
        execOps.exec {
            workingDir(builderDir)
            commandLine(executablePath, "version")
        }
    } catch (e: Exception) {
        throw GradleException(
            "✗ Go executable not found or failed to run at: $executablePath\n" +
            "  Install Go or set:\n" +
            "  - Environment: GO_EXECUTABLE=/path/to/go\n" +
            "  - Property: -PGO_EXECUTABLE=/path/to/go\n" +
            "  Error: ${e.message}"
        )
    }
}

fun createGoModule(builderDir: File) {
    val goModFile = file("${builderDir}/go.mod")
    goModFile.writeText(
        """
        module builder
        go 1.25.7

        require (
	        github.com/xtls/xray-core v1.260123.1-0.20260206094241-12ee51e4bb1d
	        golang.org/x/mobile v0.0.0-20260204172633-1dceadbbeea3
        )
        
        // grpc fix, should remove in next updates
        replace google.golang.org/grpc v1.78.0 => google.golang.org/grpc v1.79.3 
        // grpc fix 
        """.trimIndent()
    )
    file("${builderDir}/go.sum").delete()
}

fun vendorGoDependencies(builderDir: File, executablePath: String) {
    val goEnv = mapOf("GOPROXY" to goProxy)

    fun runGoStep(description: String, vararg args: String) {
        var lastFailure: Exception? = null
        repeat(3) { attemptIndex ->
            try {
                execOps.exec {
                    workingDir(builderDir)
                    environment(goEnv)
                    commandLine(executablePath, *args)
                }
                return
            } catch (e: Exception) {
                lastFailure = e
                if (attemptIndex < 2) {
                    logger.warn("$description failed, retrying (${attemptIndex + 2}/3): ${e.message}")
                }
            }
        }

        throw GradleException("$description failed after 3 attempts.", lastFailure)
    }

    // Add the replace directive for our local clone
    runGoStep(
        "Add local V2Ray replace",
        "mod",
        "edit",
        "-replace=github.com/2dust/AndroidLibXrayLite=../../../../../build/v2ray/src"
    )

    // Tidy the module
    runGoStep("Tidy Go module", "mod", "tidy")

    // Create the vendor directory
    runGoStep("Vendor Go dependencies", "mod", "vendor")
}

// =========================================================================
// GO BUILD TASKS
// =========================================================================

// Task 1: Clone V2Ray source code and checkout specific commit
tasks.register<DefaultTask>("cloneV2raySource") {
    group = "Go Setup"
    description = "Clones V2Ray source and checks out a specific commit."

    val srcDir = file("${buildDirV2ray}/src")
    outputs.dir(srcDir)
    onlyIf { !skipGoBuild && !srcDir.resolve(".git").exists() }

    doLast {
        fun runGitStep(description: String, workingDirectory: File, vararg args: String) {
            var lastFailure: Exception? = null
            repeat(3) { attemptIndex ->
                try {
                    execOps.exec {
                        workingDir(workingDirectory)
                        commandLine(gitExecutable, *args)
                    }
                    return
                } catch (e: Exception) {
                    lastFailure = e
                    if (attemptIndex < 2) {
                        logger.warn("$description failed, retrying (${attemptIndex + 2}/3): ${e.message}")
                    }
                }
            }

            throw GradleException("$description failed after 3 attempts.", lastFailure)
        }

        println("\n╔════════════════════════════════════════════════════════╗")
        println("║  CLONING V2RAY REPOSITORY                              ║")
        println("╚════════════════════════════════════════════════════════╝")

        if (srcDir.exists() && !srcDir.resolve(".git").exists()) {
            println("→ Removing incomplete V2Ray checkout: ${srcDir.absolutePath}")
            srcDir.deleteRecursively()
        }

        // Clone the default branch with a shallow history
        println("→ Cloning repository with depth=1...")
        runGitStep(
            "Clone V2Ray repository",
            project.rootDir,
            "clone",
            "--depth=1",
            v2rayRepo,
            srcDir.absolutePath
        )

        // Fetch the specific commit from the origin
        println("→ Fetching specific commit: $v2rayCommit...")
        runGitStep("Fetch V2Ray commit", srcDir, "fetch", "origin", v2rayCommit)

        // Checkout the fetched commit
        println("→ Checking out commit...")
        runGitStep("Checkout V2Ray commit", srcDir, "checkout", v2rayCommit)
        println("✓ V2Ray repository ready\n")
    }
}

// Task 2: Prepare Go module and create vendor directory
tasks.register<DefaultTask>("vendorGoDependencies") {
    group = "Go Setup"
    description = "Initializes main go.mod and creates a vendor directory."

    val builderDir = file("src/main/go/builder")
    val vendorDir = file("${builderDir}/vendor")

    dependsOn(tasks.named("cloneV2raySource"))

    inputs.file("${builderDir}/builder.go")
    outputs.dir(vendorDir)
    onlyIf { !skipGoBuild }

    doFirst {
        println("\n╔════════════════════════════════════════════════════════╗")
        println("║  VERIFYING GO ENVIRONMENT                              ║")
        println("╚════════════════════════════════════════════════════════╝")

        val overrideEnv = System.getenv("GO_EXECUTABLE")
        val overrideProp = project.findProperty("GO_EXECUTABLE")?.toString()
        val goExecCandidate = overrideEnv ?: overrideProp ?: goExecutable

        println("→ Verifying Go executable: $goExecCandidate...")
        verifyGoExecutable(builderDir, goExecCandidate)
        println("✓ Go executable verified\n")
    }

    doLast {
        println("\n╔════════════════════════════════════════════════════════╗")
        println("║  PREPARING GO DEPENDENCIES                             ║")
        println("╚════════════════════════════════════════════════════════╝")

        val builderDirectory = file("src/main/go/builder")
        println("→ Creating go.mod...")
        createGoModule(builderDirectory)

        println("→ Vendoring dependencies...")
        vendorGoDependencies(builderDirectory, goExecutable)
        println("✓ Go dependencies ready\n")
    }
}

// Task 3: Prepare Go build dependencies
val prepareGoBuild = tasks.register("prepareGoBuild") {
    group = "Go Setup"
    description = "Prepares all dependencies for Go library builds."
    dependsOn(tasks.named("vendorGoDependencies"))
    onlyIf { !skipGoBuild }
}

// Task 4: Aggregate all architecture-specific copy tasks
val copyAllGoSharedLibs = tasks.register("copyAllGoSharedLibs") {
    group = "Go Build"
    description = "Copies Go shared libraries for all architectures to jniLibs."
    onlyIf { !skipGoBuild }
}

// =========================================================================
// GENERATE ARCHITECTURE-SPECIFIC BUILD & COPY TASKS
// =========================================================================

archConfigs.forEach { arch ->
    // Build task for current architecture
    val buildTask = tasks.register<Exec>("buildGoSharedLib_${arch.abi}") {
        dependsOn(prepareGoBuild)
        group = "Go Build"
        description = "Builds Go shared library (${arch.abi})"

        val builderDir = file("src/main/go/builder")
        val outputDir = file("${layout.buildDirectory.get().asFile}/generated/go_build/${arch.abi}")
        val outputSO = file("${outputDir}/libgojni.so")

        inputs.dir(builderDir)
        outputs.file(outputSO)
        workingDir(builderDir)

        doFirst {
            println("\n>>> Building Go library for ${arch.abi}...")
            val ndkPath = findNdkPath().replace("\\", "/")
            val ndkPrebuiltFolder = validateNdkPath(ndkPath)
            val apiLevel = 21
            val toolchainPath = "${ndkPath}/toolchains/llvm/prebuilt/${ndkPrebuiltFolder}"
            val compiler = "${toolchainPath}/bin/${arch.target}${apiLevel}-clang"
            val sysroot = "${toolchainPath}/sysroot"
            if (!file(compiler).exists()) {
                throw GradleException(
                    "✗ C compiler for ${arch.abi} not found at: $compiler\n" +
                    "  Verify NDK installation and configuration."
                )
            }

            environment("CGO_ENABLED", "1")
            environment("GOOS", "android")
            environment("GOARCH", arch.goArch)
            environment("CC", compiler)
            environment("CGO_CFLAGS", "--sysroot=${sysroot}")
            environment("CGO_LDFLAGS", "--sysroot=${sysroot} -llog -Wl,-z,max-page-size=16384")
            commandLine(
                goExecutable, "build",
                "-mod=vendor",
                "-buildmode=c-shared",
                "-trimpath",
                "-ldflags", "-s -w -buildid=",
                "-o", outputSO.absolutePath,
                "."
            )
        }

        doLast {
            println("✓ Built ${arch.abi}")
        }
        onlyIf { !skipGoBuild }
    }

    // Copy task for current architecture
    val copyTask = tasks.register<Copy>("copyGoSharedLib_${arch.abi}") {
        dependsOn(buildTask)
        group = "Go Build"
        description = "Copies Go library to jniLibs (${arch.abi})"

        from(buildTask.map { it.outputs.files })
        into("src/main/jniLibs/${arch.abi}")

        doFirst {
            val sourceFile = buildTask.get().outputs.files.singleFile
            if (!sourceFile.exists()) {
                throw InvalidUserDataException(
                    "✗ Go build failed: ${sourceFile.path} not created for ${arch.abi}"
                )
            }
            println(">>> Copying library to jniLibs (${arch.abi})...")
        }

        doLast {
            println("✓ Copied ${arch.abi}")
        }

        onlyIf { !skipGoBuild }
    }

    if (!skipGoBuild) {
        copyAllGoSharedLibs.configure {
            dependsOn(copyTask)
        }
    }
}

// =========================================================================
// BUILD LIFECYCLE HOOKS
// =========================================================================

// Hook Go build into Android build lifecycle
project.afterEvaluate {
    tasks.named("preBuild") {
        if (!skipGoBuild) {
            dependsOn(copyAllGoSharedLibs)
        } else {
            doFirst {
                logger.lifecycle("Skipping Go shared library build because SKIP_GO_BUILD=true.")
            }
        }
    }

    // Add summary task for all Go builds
    tasks.register("buildAllGoLibraries") {
        group = "Go Build"
        description = "Builds and copies all Go shared libraries"
        if (!skipGoBuild) {
            dependsOn(copyAllGoSharedLibs)
        }
        onlyIf { !skipGoBuild }

        doLast {
            println("\n╔════════════════════════════════════════════════════════╗")
            println("║  ✓ ALL GO LIBRARIES BUILT SUCCESSFULLY                 ║")
            println("║  Ready for Android APK build                           ║")
            println("╚════════════════════════════════════════════════════════╝\n")
        }
    }
}
