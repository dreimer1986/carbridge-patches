// Loaded at runtime by the splitscreen extension's PayloadClassLoader, isolated from the app's
// classes. The patch copies this dex into the app's assets instead of merging it.
// The sources live in CarBridge (git submodule), which adds the same code to its car copies
val shared = rootProject.file("carbridge/splitscreen/payload")

android {
    namespace = "org.carbridge.extension.splitscreen.payload"

    sourceSets.named("main") {
        java.setSrcDirs(listOf(shared.resolve("src/main/java")))
        manifest.srcFile(shared.resolve("src/main/AndroidManifest.xml"))
    }

    defaultConfig {
        // Detached SurfaceTexture for the compositor
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(shared.resolve("proguard-rules.pro"))
        }
    }
}

dependencies {
    implementation(libs.androidx.car.app)
    // The app's merged copy is used at runtime: Shizuku hands its binder to that one
    compileOnly(libs.shizuku.api)
}
