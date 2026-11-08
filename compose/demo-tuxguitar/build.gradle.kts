plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
sourceSets {
    test {
        // Compile this actual upstream style class, not a copied table of expected constants.
        java.srcDir("../../android/TuxGuitar-android/src")
        java.include("app/tuxguitar/android/view/tablature/TGSongViewStyles.java")
    }
    main {
        java.srcDirs(
            "../../common/TuxGuitar-lib/src/main/java",
            "../../common/TuxGuitar-gm-utils/src",
            "../../common/TuxGuitar-gtp/src",
            "../../common/TuxGuitar-gpx/src",
            "../../common/TuxGuitar-midi/src/main/java"
        )
    }
}
dependencies {
    api(project(":tux-compose-core"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("commons-io:commons-io:2.18.0")
    implementation("org.apache.commons:commons-lang3:3.17.0")
    testImplementation(kotlin("test"))
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

// These are real test outputs: cache hits must restore the audit payloads as well
// as JUnit XML, rather than silently dropping the 1,152-layout/feature reports.
tasks.withType<Test>().configureEach {
    outputs.files(
        layout.buildDirectory.file("reports/showcase-feature-coverage.json"),
        layout.buildDirectory.file("reports/showcase-layout-audit.json")
    ).withPropertyName("showcaseAudits")
}
