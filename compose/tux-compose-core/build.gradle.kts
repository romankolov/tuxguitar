plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
    `maven-publish`
}
kotlin {
    androidTarget { publishLibraryVariants("release") }
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.ui)
            implementation(compose.foundation)
            api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
android {
    namespace = "com.tuxcompose.core"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Tux Compose Core")
            description.set("Model-to-Canvas Compose notation. No parsers or playback.")
            url.set("https://github.com/romankolov/tuxguitar/tree/master/compose")
            licenses { license { name.set("LGPL-2.1-or-later"); url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html") } }
        }
    }
}
