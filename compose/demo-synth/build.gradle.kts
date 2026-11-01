plugins { id("com.android.library") }
android {
    namespace = "com.tuxcompose.demo.synth"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    sourceSets["main"].apply {
        java.srcDirs(
            "../../desktop/gervill/src/main/java",
            "../../android/TuxGuitar-android-gervill/src"
        )
        java.exclude("app/tuxguitar/android/midi/**")
    }
}
dependencies { implementation(project(":demo-tuxguitar")) }
