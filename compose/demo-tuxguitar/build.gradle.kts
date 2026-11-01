plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
sourceSets {
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
