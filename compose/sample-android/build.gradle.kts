plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}
kotlin { jvmToolchain(17) }
android {
    namespace = "com.tuxcompose.sample"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tuxcompose.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    sourceSets["main"].assets.srcDir("build/generated/demo-assets")
}
val demoAssets by tasks.registering(Sync::class) {
    from("../../android/TuxGuitar-android-gervill-soundbank/share/soundbank-lightweight") {
        include("*.sf2")
        into("soundbank")
    }
    from("../../docs/LICENSE") { into("licenses"); rename { "TuxGuitar-LGPL.txt" } }
    from("../../desktop/gervill/LICENSE") { into("licenses"); rename { "Gervill.txt" } }
    into(layout.buildDirectory.dir("generated/demo-assets"))
}
tasks.named("preBuild").configure { dependsOn(demoAssets) }
dependencies {
    implementation(project(":tux-compose-core"))
    implementation(project(":demo-tuxguitar"))
    implementation(project(":demo-synth"))
    implementation(compose.material3)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.8.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.8.2")
}
