plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm("desktop")

    sourceSets {
        getByName("desktopMain") {
            dependencies {
                implementation(project(":core"))

                // Compose Multiplatform Desktop.
                //
                // Specified as coordinates rather than the `compose.material3` / `compose.ui` /
                // `compose.foundation` / `compose.components.resources` accessors: those are
                // deprecated in Compose 1.12 and warn on every configure ("Specify dependency
                // directly"). `compose.desktop.currentOs` is not deprecated and stays.
                //
                // `material3` is on a separate release train from the rest of Compose, so it is
                // pinned separately. These are the exact versions plugin 1.12.2 resolved before
                // the change, so behaviour is identical — only the warning is gone. Bumping the
                // plugin will NOT bump material3 automatically; check it deliberately.
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                implementation("org.jetbrains.compose.ui:ui:1.12.2")
                implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
                implementation("org.jetbrains.compose.components:components-resources:1.12.2")
                implementation(compose.desktop.currentOs)
                // No `compose.materialIconsExtended`: it is a 37 MB jar carrying every Material glyph
                // because each is a compiled object, and this app draws 13 of them. Those live in
                // ui/icons/AppIcons.kt, generated from the upstream SVGs. Adding an icon there costs
                // a line; re-adding this dependency costs a third of the installer.

                // Ktor Server 3.6.0 for local HTTP & gRPC-Web.
                //
                // Only core + CIO + CORS are here. `ktor-server-content-negotiation`,
                // `ktor-server-status-pages` and `ktor-serialization-kotlinx-json` used to be
                // declared too and were **never installed on the application**: the server answers
                // gRPC-Web by reading the raw body (`receiveText`) and writing a pre-serialised proto
                // payload, so no Ktor content negotiation or StatusPages interceptor runs. They were
                // not merely dead weight — `content-negotiation` transitively pulls
                // `ktor-server-routing-openapi`, which drags in `ktor-server-auth`, `ktor-server-csrf`,
                // `ktor-client-core` and `ktor-openapi-schema` (~3 MB of unreachable code).
                // `kotlinx-serialization-json` is still needed directly: `core`'s `ProtoCodec` parses
                // JSON with it.
                val ktorVersion = "3.6.0"
                implementation("io.ktor:ktor-server-core:$ktorVersion")
                implementation("io.ktor:ktor-server-cio:$ktorVersion")
                implementation("io.ktor:ktor-server-cors:$ktorVersion")

                // Coroutines & Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

                // ONNX Runtime for the optional GPU path. This jar bundles the Windows natives
                // (onnxruntime.dll + onnxruntime4j_jni.dll) under ai/onnxruntime/native/win-x64, so it
                // is the only artifact needed on desktop — unlike Android, where OrtEnvironment loads
                // the .so from the APK's jniLibs.
                //
                // Pinned rather than floating: the fp16 read path depends on OnnxJavaType.FLOAT16 and
                // on Result keeping tensors alive after the wrapper is closed, both of which changed
                // across 1.x. Bump deliberately and re-run the parity test.
                implementation("com.microsoft.onnxruntime:onnxruntime:1.31.1")
            }
        }

        // The host's own logic -- the durable memo in particular -- was previously only exercised by
        // hand. `WindowsCacheStore` is a JVM-only class with real filesystem behaviour (replay, corrupt
        // tails, compaction), so it belongs in a JVM test rather than in `core`, which may not touch
        // the filesystem.
        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                // Test-only: an independent tokenizer so the ONNX parity test can run without the
                // compiled `vbookmt_native` library. Not a production dependency — DJL ships no
                // Android natives, which is why production uses the native SentencePiece instead.
                implementation("ai.djl.huggingface:tokenizers:0.40.0")
            }
        }
    }
}

// The ONNX parity test runs against a built model directory, which is ~200 MB and not in the repo.
//
// Passed as a **project property** (`-PonnxModel=...`), not an environment variable. The Gradle
// daemon is long-lived, so a variable exported in the shell that started the client is usually NOT
// visible to `providers.environmentVariable` inside the daemon — the test then silently returns early
// and reports a pass in 0.05 s, which is exactly the false green this check exists to prevent.
// Project properties are forwarded to the daemon on every invocation, so they cannot go stale.
tasks.withType<Test>().configureEach {
    (project.findProperty("onnxModel") as String?)
        ?.takeIf { it.isNotBlank() }
        ?.let {
            systemProperty("VBOOKMT_ONNX_MODEL", it)
            // Loading a 200 MB model and decoding a batch takes far longer than the 30 s default
            // some CI runners assume; give it room rather than failing on a slow disk.
            maxHeapSize = "3g"
        }
}

compose.desktop {
    application {
        mainClass = "com.vbookmt.windows.MainKt"

        // Shrink the app jars with ProGuard. Compose Multiplatform ships the required keep rules
        // (under build/compose/default-resources/<compose-version>/default-compose-desktop-rules.pro,
        // generated by the plugin) and this DSL pulls them in automatically.
        //
        // Shrink only -- `optimize` and `obfuscate` are deliberately OFF. With `optimize = true`
        // ProGuard's optimizer mangles Compose's generated accessors badly enough to fail bytecode
        // verification at class load:
        //     VerifyError: Bad return type in SnapshotStateKt__DerivedStateKt.derivedStateOf$102ee4b4
        //     Type 'State' is not assignable to 'DerivedSnapshotState'
        // which surfaces to the user as "Failed to launch JVM" with no window at all. Shrinking alone
        // removes the unreachable classes and is verified to run; the optimizer's extra few MB is not
        // worth an unlaunchable build.
        buildTypes.release.proguard {
            // The plugin's bundled ProGuard (7.2.2) cannot read class files above Java 18
            // ("Unsupported version number [65.0]"), and this project targets Java 21. Pin a version
            // that does; 7.10 is the current release line.
            version.set("7.10.0")
            isEnabled.set(true)
            optimize.set(false)
            obfuscate.set(false)
            joinOutputJars.set(true)
        }
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
            )
            packageName = "VBookMT"
            packageVersion = "1.2.1"
            description = "VBookMT Desktop Host - Offline AI Translation"
            vendor = "VBookMT"

            // Where the native engine DLLs come from.
            //
            // `scripts/build_windows_native.ps1` drops `vbookmt_native.dll`
            // into `windows/` under this root; the plugin copies the contents of the matching OS
            // subdirectory (`windows`, `macos`, `linux`) into the app root, which is where
            // `WindowsCt2Engine.nativeDir()` looks for them.
            //
            // Without this the packaging step succeeded and produced an MSI with no DLLs at all —
            // the app installed cleanly and then failed on the first translation with
            // "Thiếu vbookmt_native.dll".
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources-packaged"))

            windows {
                menu = true
                upgradeUuid = "a6042468-f9b1-4ca6-a212-fbfb4109decf"
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
            }
        }
    }
}

/**
 * Compress the bundled runtime.
 *
 * Compose already passes `--strip-debug --no-header-files --no-man-pages`, so the JRE in the
 * distributable is stripped but *uncompressed*: `jlink --compress` was never passed, and its default
 * is none. `runtime/lib/modules` is the single largest file in the install and compresses to roughly
 * two thirds of its size, costing nothing at run time beyond decompressing the classes actually
 * loaded.
 *
 * `AbstractJLinkTask.compressionLevel` is `internal` to the plugin, so it cannot be set from a build
 * script. `freeArgs` is public and is appended to the jlink command line, which reaches the same
 * flag. `zip-6` is explicit rather than a bare `2` because jlink deprecated the numeric form and
 * warns about it.
 */
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().configureEach {
    freeArgs.add("--compress=zip-6")
}
