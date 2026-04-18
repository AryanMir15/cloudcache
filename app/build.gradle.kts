import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

fun getGitCommitHash(): String {
    return try {
        val headFile = file("${project.rootDir}/.git/HEAD")
        if (headFile.exists()) {
            val headContent = headFile.readText().trim()
            if (headContent.startsWith("ref:")) {
                val refPath = headContent.substring(5)
                val commitFile = file("${project.rootDir}/.git/$refPath")
                if (commitFile.exists()) commitFile.readText().trim() else ""
            } else headContent
        } else ""
    } catch (_: Throwable) { "" }.take(7)
}

android {
    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Looks like google likes to add metadata only they can read https://gitlab.com/IzzyOnDroid/repo/-/work_items/491
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    namespace = "com.lagradost.cloudstream3"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lagradost.cloudcache"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 68
        versionName = "4.7.0"

        resValue("string", "commit_hash", getGitCommitHash())
        manifestPlaceholders["target_sdk_version"] = libs.versions.targetSdk.get()

        val localProperties = gradleLocalProperties(rootDir, project.providers)
        buildConfigField("long", "BUILD_DATE", "0L")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${System.getenv("SIMKL_CLIENT_ID") ?: localProperties["simkl.id"] ?: ""}\"")
        buildConfigField("String", "SIMKL_CLIENT_SECRET", "\"${System.getenv("SIMKL_CLIENT_SECRET") ?: localProperties["simkl.secret"] ?: ""}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            buildConfigField("long", "BUILD_DATE", "${System.currentTimeMillis()}L")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            buildConfigField("long", "BUILD_DATE", "0L")
            isDebuggable = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions.add("state")
    productFlavors {
        create("stable") {
            dimension = "state"
        }
        create("prerelease") {
            dimension = "state"
            applicationIdSuffix = ".prerelease"
            versionNameSuffix = "-PRE"
            versionCode = (System.currentTimeMillis() / 60000).toInt()
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            jvmDefault.set(JvmDefaultMode.ENABLE)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
            optIn.addAll(
                "com.lagradost.cloudstream3.InternalAPI",
                "com.lagradost.cloudstream3.Prerelease",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Enables legacy JNI packaging to reduce APK size (similar to builds before minSdk 23).
            // Note: This may increase app startup time slightly.
            useLegacyPackaging = true
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val outputFileName = "CloudCache-${variant.versionName}-${variant.buildType.name}.apk"
                output.outputFileName = outputFileName
            }
    }

    java {
        // Use Java 17 toolchain even if a higher JDK runs the build.
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdkToolchain.get()))
        }
    }
}

dependencies {
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.core)
    implementation(libs.junit.ktx)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // Android Core
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation)

    // Room Database (FIXES UNRESOLVED DAO/ENTITY)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // UI & Media
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.bundles.coil)
    implementation(libs.bundles.media3)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(libs.video)
    implementation(libs.bundles.nextlib)

    // Libs
    implementation(libs.colorpicker)
    implementation(libs.newpipeextractor)
    implementation(libs.juniversalchardet)
    implementation(libs.shimmer)
    implementation(libs.palette.ktx)
    implementation(libs.tvprovider)
    implementation(libs.overlappingpanels)
    implementation(libs.biometric)
    implementation(libs.previewseekbar.media3)
    implementation(libs.qrcode.kotlin)
    implementation(libs.jsoup)
    implementation(libs.rhino)
    implementation(libs.fuzzywuzzy)
    implementation(libs.safefile)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.conscrypt.android)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.zipline)
    implementation(libs.torrentserver)
    implementation(libs.work.runtime.ktx)
    implementation(libs.nicehttp)

    implementation(project(":library"))
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.directories) // Full Sources
}

tasks.register<Copy>("copyJar") {
    dependsOn("build", ":library:jvmJar")
    from(
        "build/intermediates/compile_app_classes_jar/prereleaseDebug/bundlePrereleaseDebugClassesToCompileJar",
        "../library/build/libs"
    )
    into("build/app-classes")
    include("classes.jar", "library-jvm*.jar")
    // Remove the version
    rename("library-jvm.*.jar", "library-jvm.jar")
}

// Merge the app classes and the library classes into classes.jar
tasks.register<Jar>("makeJar") {
    // Duplicates cause hard to catch errors, better to fail at compile time.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(tasks.getByName("copyJar"))
    from(
        zipTree("build/app-classes/classes.jar"),
        zipTree("build/app-classes/library-jvm.jar")
    )
    destinationDirectory.set(layout.buildDirectory)
    archiveBaseName = "classes"
}

tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        jvmDefault.set(JvmDefaultMode.ENABLE)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
        optIn.addAll(
            "com.lagradost.cloudstream3.InternalAPI",
            "com.lagradost.cloudstream3.Prerelease",
        )
    }
}
