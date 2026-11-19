// The sources live in CarBridge (git submodule), which adds the same code to its car copies
val shared = rootProject.file("carbridge/splitscreen/host")

android {
    namespace = "org.carbridge.extension.splitscreen"

    sourceSets.named("main") {
        java.setSrcDirs(listOf(shared.resolve("src/main/java")))
        manifest.srcFile(shared.resolve("src/main/AndroidManifest.xml"))
    }

    defaultConfig {
        // InMemoryDexClassLoader for the payload; Shizuku needs 24
        minSdk = 26
    }

    buildTypes {
        release {
            // Shrink the bundled libraries; names must stay intact, see proguard-rules.pro
            isMinifyEnabled = true
            proguardFiles(shared.resolve("proguard-rules.pro"))
        }
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
