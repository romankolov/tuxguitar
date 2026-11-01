plugins { id("com.android.library") }
android {
    namespace = "com.tuxcompose.demo.synth"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].apply {
        // The Android fork already includes media.sound plus javax.sound and AudioTrack shims.
        // Including desktop/gervill as well compiles every synthesizer class twice.
        java.setSrcDirs(listOf("../../android/TuxGuitar-android-gervill/src"))
        java.exclude("app/tuxguitar/android/midi/**")
    }
}
dependencies { implementation(project(":demo-tuxguitar")) }
