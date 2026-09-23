// Modified for Tally (https://github.com/Scdouglas1999/Tally), a fork of Wholphin
// (https://github.com/damontecres/Wholphin), from September 2026. Changes are marked TALLY: begin/end;
// each change and its date is in the git history. See NOTICE.md.
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ProductFlavor
import com.android.build.api.variant.FilterConfiguration
import com.google.protobuf.gradle.id
import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.openapi.generator)
}

val isCI = providers.environmentVariable("CI").orElse("false").map { it.toBoolean() }
val shouldSign =
    isCI.zip(
        providers.environmentVariable("KEY_ALIAS").orElse("").map { it.isNotBlank() },
    ) { isCI, hasKey ->
        isCI && hasKey
    }
val ffmpegModuleExists =
    providers.provider { project.file("libs/lib-decoder-ffmpeg-release.aar").exists() }
val av1ModuleExists =
    providers.provider { project.file("libs/lib-decoder-av1-release.aar").exists() }
val mpvModuleExists =
    providers.provider { project.file("libs/wholphin-mpv-release.aar").exists() }
val extensionsRepoActive =
    providers.provider { project.hasProperty("WholphinExtensionsUsername") }

// See https://issuetracker.google.com/issues/402800800
val isBuildingBundle =
    providers.provider {
        gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
    }

val gitTags =
    providers
        .exec { commandLine("git", "tag", "--list", "v*", "p*") }
        .standardOutput.asText
        .get()

val gitDescribe =
    providers
        .exec { commandLine("git", "describe", "--tags", "--long", "--match=v*") }
        .standardOutput.asText
        .getOrElse("v0.0.0")

// TALLY: begin
// Tally's own version line: releases are tagged tally-vMAJOR.MINOR.PATCH (tally-v2.0.0 was the first). Wholphin's
// v* tags above still give the upstream base, which Wholphin's one-time upgrade steps are keyed to (BuildConfig
// TALLY_UPSTREAM_VERSION); without a tally-v tag the build falls back to the upstream numbering.
val tallyDescribe =
    providers
        .exec {
            commandLine("git", "describe", "--tags", "--long", "--match=tally-v*")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .getOrElse("")
        .trim()
val tallyVersion = Regex("^tally-v(\\d+)\\.(\\d+)\\.(\\d+)-(\\d+)-g([0-9a-f]+)$").find(tallyDescribe)
// TALLY: end

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
        jvmTarget = JvmTarget.JVM_11
        javaParameters = true

        // Workaround for https://github.com/google/ksp/issues/2964
        moduleName = "${project.group}_${project.name}"
    }
}

private fun Provider<String>.getInt() = get().toInt()

configure<ApplicationExtension> {
    namespace = "com.github.damontecres.wholphin"
    compileSdk = libs.versions.compileSdk.getInt()

    defaultConfig {
        // TALLY: begin
        applicationId = "io.github.scoduglas1999.jellytv"
        // TALLY: end
        minSdk = libs.versions.minSdk.getInt()
        targetSdk = libs.versions.targetSdk.getInt()
        // TALLY: begin
        // Upstream counts release tags, which never changes between this fork's releases; Play (and a sane
        // update order) needs every release to be higher. tags * 1000 + commits since the upstream tag:
        // 59 tags, v1.0.8-26-g… -> 59026; rebasing onto upstream's next tag jumps to 60xxx.
        // From 2.0.0 on: MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + commits since the tag (development builds),
        // above every upstream-numbered build (the last was 59156). The name is "2.0.0" on a release tag and
        // "2.0.0-3-gabc1234" between releases; the in-app updater compares these names.
        versionCode =
            tallyVersion?.destructured?.let { (major, minor, patch, commits) ->
                major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + commits.toInt().coerceAtMost(99)
            } ?: (
                gitTags.trim().lines().size * 1000 +
                    (Regex("-(\\d+)-g[0-9a-f]+$").find(gitDescribe.trim())?.groupValues?.get(1)?.toIntOrNull() ?: 0).coerceAtMost(999)
            )
        buildConfigField("String", "TALLY_UPSTREAM_VERSION", "\"${gitDescribe.trim().removePrefix("v").ifBlank { "0.0.0" }}\"")
        // TALLY: end
        versionName =
            // TALLY: begin
            tallyVersion?.destructured?.let { (major, minor, patch, commits, hash) ->
                if (commits == "0") "$major.$minor.$patch" else "$major.$minor.$patch-$commits-g$hash"
            } ?:
            // TALLY: end
            gitDescribe.trim().removePrefix("v").ifBlank { "0.0.0" }
        testInstrumentationRunner = "com.github.damontecres.wholphin.test.WholphinTestRunner"

        buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString())
    }

    signingConfigs {
        if (shouldSign.get()) {
            create("ci") {
                file("ci.keystore").writeBytes(
                    Base64.getDecoder().decode(System.getenv("SIGNING_KEY")),
                )
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                storeFile = file("ci.keystore")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            isDebuggable = false
            if (shouldSign.get()) {
                signingConfig = signingConfigs.getByName("ci")
            } else {
                val localPropertiesFile = project.rootProject.file("local.properties")
                if (localPropertiesFile.exists()) {
                    val properties = Properties()
                    properties.load(localPropertiesFile.inputStream())
                    val signingConfigName = properties["release.signing.config"]?.toString()
                    if (signingConfigName != null) {
                        signingConfig = signingConfigs.getByName(signingConfigName)
                    }
                }
            }
        }

        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    flavorDimensions += "version"
    productFlavors {
        val featureLeanback = "leanback"
        val featureUpdate = "UPDATING_ENABLED"
        val featureDiscover = "DISCOVER_ENABLED"

        fun ProductFlavor.setFeatureFlag(
            name: String,
            enabled: Boolean,
        ) {
            this.buildConfigField("boolean", name, "Boolean.parseBoolean(\"${enabled}\")")
        }
        create("default") {
            dimension = "version"
            isDefault = true
            manifestPlaceholders += mapOf(featureLeanback to false)
            // TALLY: begin
            // Self-updating stays on: the update URL default points at this fork's releases (AppPreference.UpdateUrl).
            setFeatureFlag(featureUpdate, true)
            // TALLY: end
            setFeatureFlag(featureDiscover, true)
        }
        create("appstore") {
            dimension = "version"
            manifestPlaceholders += mapOf(featureLeanback to true)
            setFeatureFlag(featureUpdate, false)
            setFeatureFlag(featureDiscover, true)
        }
        create("firetv") {
            dimension = "version"
            manifestPlaceholders += mapOf(featureLeanback to true)
            setFeatureFlag(featureUpdate, false)
            setFeatureFlag(featureDiscover, false)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    splits {
        abi {
            // Disable split abis when building bundles
            isEnable = !isBuildingBundle.get()

            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs {
            // Work around because libass-android & wholphin-mpv both (incorrectly) package libc++_shared.so
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += "$buildDir/generated/seerr_api/src/main/kotlin"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        disable.add("MissingTranslation")
    }
    androidResources {
        generateLocaleConfig = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs
            .map { it as com.android.build.api.variant.impl.VariantOutputImpl }
            .forEach { output ->
                val abi =
                    output
                        .getFilter(FilterConfiguration.FilterType.ABI)
                        .let { if (it != null) "-${it.identifier}" else "" }
                val outputFileName =
                    // TALLY: begin
                    "Tally-${variant.flavorName}-${variant.buildType}-${output.versionName.get()}-${output.versionCode.get()}$abi.apk"
                // TALLY: end
                output.outputFileName = outputFileName
            }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.protobuf.kotlin.lite.get().version}"
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("java") {
                    option("lite")
                }
            }
            it.builtins {
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}
aboutLibraries {
    collect {
        configPath = file("config")
    }
    library {
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.SIMPLE
    }
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set("$projectDir/src/main/seerr/seerr-api.yml")
    templateDir.set("$projectDir/src/main/seerr/templates")
    outputDir.set("$buildDir/generated/seerr_api")
    apiPackage.set("com.github.damontecres.wholphin.api.seerr")
    modelPackage.set("com.github.damontecres.wholphin.api.seerr.model")
    groupId.set("com.github.damontecres.wholphin.api.seerr")
    id.set("seerr-api")
    packageName.set("com.github.damontecres.wholphin.api.seerr")
    additionalProperties.apply {
        put("serializationLibrary", "kotlinx_serialization")
        put("sortModelPropertiesByRequiredFlag", true)
        put("sortParamsByRequiredFlag", true)
        put("useCoroutines", true)
        put("enumPropertyNaming", "UPPERCASE")
        put("modelMutable", false)

        // Note: this is only for downloading files, so it's not necessary to enable
        put("supportAndroidApiLevel25AndBelow", false)
    }
}

tasks.named("preBuild") {
    dependsOn.add(tasks.named("openApiGenerate"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.ass.media)

    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.cachecontrol)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)

    implementation(libs.jellyfin.core)
    implementation(libs.jellyfin.api)
    implementation(libs.jellyfin.api.okhttp)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    implementation(libs.androidx.room.common.jvm)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.room.testing)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.runner)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.timber)
    implementation(libs.slf4j2.timber)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.multiplatform.markdown.renderer)
    implementation(libs.multiplatform.markdown.renderer.m3)
    implementation(libs.programguide)
    implementation(libs.acra.http)
    implementation(libs.acra.dialog)
    implementation(libs.acra.limiter)
    compileOnly(libs.auto.service.annotations)
    ksp(libs.auto.service.ksp)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.kache)
    implementation(libs.kache.file)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    if (ffmpegModuleExists.get()) {
        logger.info("Using local ffmpeg decoder")
        implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    } else if (extensionsRepoActive.get()) {
        logger.info("Using prebuilt ffmpeg decoder")
        implementation(libs.wholphin.extensions.ffmpeg)
    } else {
        logger.warn("Media3 ffmpeg decoder was NOT found")
    }
    if (av1ModuleExists.get()) {
        logger.info("Using local av1 decoder")
        implementation(files("libs/lib-decoder-av1-release.aar"))
    } else if (extensionsRepoActive.get()) {
        logger.info("Using prebuilt av1 decoder")
        implementation(libs.wholphin.extensions.av1)
    } else {
        logger.warn("Media3 av1 decoder was NOT found")
    }
    if (mpvModuleExists.get()) {
        logger.info("Using local libMPV build")
        implementation(files("libs/wholphin-mpv-release.aar"))
    } else if (extensionsRepoActive.get()) {
        logger.info("Using prebuilt libMPV")
        implementation(libs.wholphin.extensions.mpv)
    } else {
        logger.warn("libMPV was NOT found, using stub library")
        implementation(project(":wholphin-mpv-stub"))
    }

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.mockk.android)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}
