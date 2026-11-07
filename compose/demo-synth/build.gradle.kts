import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { id("com.android.library") }

val nativeRoot = layout.buildDirectory.dir("generated/fluidsynth")
val generatedAssets = layout.buildDirectory.dir("generated/synth-assets")
val prepareSynth by tasks.registering {
    // Only the demo downloads this SDK/bank. No binary or large sound bank is checked into Git.
    inputs.property("fluidsynth", "2.6.2-f29d1fe9c46c8528c65d22b2bfcd15997960f908370531a5a9427a73ab9e5313")
    inputs.property("generaluser", "684543d5e5efaef08d02be50dcda8d552478fa60-197207fffaf7bad496a1d5d9a44b0edd16efe97d53db42afb6c73cdbdefb4484")
    outputs.dir(nativeRoot)
    outputs.dir(generatedAssets)
    doLast {
        val cache = layout.buildDirectory.dir("downloads").get().asFile.apply { mkdirs() }
        fun sha(file: File) = file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer,0,n) }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun download(name: String, url: String, expected: String): File {
            val file = File(cache,name)
            if (!file.isFile || sha(file) != expected) {
                val partial = File(cache,"$name.part")
                logger.lifecycle("Downloading pinned demo dependency: $name")
                val connection = URI(url).toURL().openConnection().apply { connectTimeout=30_000; readTimeout=120_000 }
                try {
                    connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                    check(sha(partial) == expected) { "SHA-256 mismatch for $name; refusing to package it" }
                    partial.copyTo(file,overwrite=true)
                } finally { partial.delete() }
            }
            return file
        }
        val sdk = download("fluidsynth-2.6.2-android24.zip",
            "https://github.com/FluidSynth/fluidsynth/releases/download/v2.6.2/fluidsynth-v2.6.2-android24.zip",
            "f29d1fe9c46c8528c65d22b2bfcd15997960f908370531a5a9427a73ab9e5313")
        val font = download("generaluser-684543d5.zip",
            "https://codeload.github.com/mrbumpy409/GeneralUser-GS/zip/684543d5e5efaef08d02be50dcda8d552478fa60",
            "197207fffaf7bad496a1d5d9a44b0edd16efe97d53db42afb6c73cdbdefb4484")
        val root = nativeRoot.get().asFile
        val assets = generatedAssets.get().asFile
        root.deleteRecursively(); assets.deleteRecursively()
        val abis = listOf("arm64-v8a","armeabi-v7a","x86_64","x86")
        ZipFile(sdk).use { zip -> zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
            val path = entry.name.replace('\\','/')
            // Select exact library/header subtrees; never extract arbitrary archive paths.
            val abi = abis.firstOrNull { path.contains("lib/$it/") }
            val relative = when {
                abi != null && path.endsWith(".so") -> "lib/$abi/${path.substringAfterLast('/')}"
                path.contains("include/fluidsynth/") -> "include/fluidsynth/${path.substringAfterLast('/')}"
                path.endsWith("include/fluidsynth.h") -> "include/fluidsynth.h"
                else -> null
            }
            relative?.let { rel -> File(root,rel).apply { parentFile.mkdirs() }.outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } } }
        } }
        abis.forEach { check(File(root,"lib/$it/libfluidsynth.so").isFile) { "Missing FluidSynth ABI $it in pinned SDK" } }
        check(File(root,"include/fluidsynth.h").isFile) { "Missing FluidSynth headers in SDK" }
        ZipFile(font).use { zip ->
            val entry = zip.entries().asSequence().single { it.name.endsWith("/GeneralUser-GS.sf2") && it.name.count { c -> c=='/' }==1 }
            val output = File(assets,"soundbank/GeneralUser-GS.sf2").apply { parentFile.mkdirs() }
            zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
            check(sha(output)=="9575028c7a1f589f5770fccc8cff2734566af40cd26ed836944e9a5152688cfe")
        }
    }
}

android {
    namespace = "com.tuxcompose.demo.synth"
    compileSdk = 37
    ndkVersion = "27.3.13750724" // Same NDK as the pinned official Android SDK binaries.
    defaultConfig {
        minSdk = 24
        ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a","x86_64","x86") }
        externalNativeBuild { cmake { arguments += listOf("-DFLUIDSYNTH_ROOT=${nativeRoot.get().asFile.absolutePath}","-DANDROID_STL=c++_shared") } }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild { cmake { path=file("src/main/cpp/CMakeLists.txt"); version="3.22.1" } }
    sourceSets["main"].apply {
        jniLibs.srcDir(nativeRoot.map { it.dir("lib") })
        assets.srcDir(generatedAssets)
    }
    // CMake also reports its directly linked imported library. Both copies come from the SDK.
    packaging.jniLibs.pickFirsts += setOf("**/libfluidsynth.so","**/libc++_shared.so")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.configureEach {
    if (name=="preBuild" || name.startsWith("configureCMake") || name.startsWith("buildCMake") ||
        name.matches(Regex("merge.*(?:Assets|JniLibFolders|NativeLibs)"))) dependsOn(prepareSynth)
}
