plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}
android {
    namespace = "com.tuxcompose.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.tuxcompose.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("evaluationRelease") {
            // CI injects an ephemeral key. No private signing material enters Git/artifacts.
            storeFile = providers.environmentVariable("TUX_RELEASE_STORE_FILE").orNull?.let(::file)
            storePassword = providers.environmentVariable("TUX_RELEASE_STORE_PASSWORD").orNull
            keyAlias = "tux-compose-evaluation"
            keyPassword = providers.environmentVariable("TUX_RELEASE_STORE_PASSWORD").orNull
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("evaluationRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        // Optional native/demo utilities use Java time/NIO. The TG envelope has a
        // separate Java-ZIP adapter for API-24 ZipEntry/FileTime compatibility.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
}
val demoAssets by tasks.registering(Sync::class) {
    from("../../docs/LICENSE") { into("licenses"); rename { "TuxGuitar-LGPL.txt" } }
    from("../licenses/alphaTab-MPL-2.0.txt") { into("licenses"); rename { "alphaTab-MPL-2.0.txt" } }
    from("../NOTICE.md") { into("licenses"); rename { "Tux-Compose-NOTICE.md" } }
    into(layout.buildDirectory.dir("generated/demo-assets"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/demo-assets").get().asFile.absolutePath)
    }
}
tasks.named("preBuild").configure { dependsOn(demoAssets) }
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(project(":tux-compose-core"))
    implementation(project(":tux-compose-pdf"))
    implementation(project(":demo-tuxguitar"))
    implementation(project(":demo-synth"))
    // Expressive is not in stable Material3 1.4.0. Pin the current RC explicitly.
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.tom-roush:pdfbox-android:2.0.27.0")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")
}
