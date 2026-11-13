plugins {
    id("com.android.library")
    `maven-publish`
}

val nativeSourceDir = layout.buildDirectory.dir("generated/tuxguitar")
val prepareNativePrint by tasks.registering(Exec::class) {
    inputs.file("upstream.json")
    inputs.file("../tools/prepare_pdf_sources.py")
    inputs.files(fileTree("../../common/TuxGuitar-lib/src/main/java") { include("**/*.java") })
    inputs.files(fileTree("../../common/TuxGuitar-pdf/src") { include("**/*.java") })
    outputs.dir(nativeSourceDir)
    commandLine("python3", "../tools/prepare_pdf_sources.py", "--output", nativeSourceDir.get().asFile.absolutePath)
}

android {
    namespace = "com.tuxcompose.pdf"
    compileSdk = 37
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main").java.srcDir(nativeSourceDir.get().asFile)
        getByName("test").java.apply {
            // ORIGINAL upstream PDF backend, test-only. Never ships iText in the AAR/APK.
            srcDir("../../common/TuxGuitar-pdf/src")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}
tasks.named("preBuild").configure { dependsOn(prepareNativePrint) }
tasks.configureEach {
    if ((name.startsWith("compile") && (name.contains("Kotlin") || name.contains("Java"))) || name.endsWith("SourcesJar")) dependsOn(prepareNativePrint)
}
dependencies {
    api(project(":tux-compose-core"))
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":demo-tuxguitar"))
    testImplementation("com.itextpdf:itextpdf:5.5.13.3")
}
afterEvaluate {
    publishing.publications.register<MavenPublication>("release") {
        from(components["release"])
        pom {
            name.set("Tux Compose PDF")
            description.set("Optional Android vector PDF export using TuxGuitar's original print engine. No music parser or playback.")
            url.set("https://github.com/romankolov/tuxguitar/tree/arena/3dc87cab-tuxguitar/compose")
            licenses { license { name.set("LGPL-2.1-or-later"); url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html") } }
        }
    }
}
