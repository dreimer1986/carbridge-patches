android {
    namespace = "org.carbridge.extension.splitscreen"

    defaultConfig {
        // InMemoryDexClassLoader for the payload; Shizuku needs 24
        minSdk = 26
    }

    buildTypes {
        release {
            // Shrink the bundled libraries; names must stay intact, see proguard-rules.pro
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
