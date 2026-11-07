plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}
kotlin {
    android {
        namespace = "com.tuxcompose.core"
        compileSdk = 37
        minSdk = 24
        compilerOptions.configure { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm("desktop")
    if (providers.gradleProperty("enableIos").orNull == "true") {
        iosArm64()
        iosSimulatorArm64()
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.ui)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
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
