// Loaded at runtime by the splitscreen extension's PayloadClassLoader, isolated from the app's
// classes. The patch copies this dex into the app's assets instead of merging it.
android {
    namespace = "org.carbridge.extension.splitscreen.payload"

    defaultConfig {
        // Detached SurfaceTexture for the compositor
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(libs.androidx.car.app)
    // The app's merged copy is used at runtime: Shizuku hands its binder to that one
    compileOnly(libs.shizuku.api)
}
