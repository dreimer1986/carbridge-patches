group = "org.carbridge"

patches {
    about {
        name = "CarBridge Patches"
        description = "Patches that show apps on the Android Auto car screen"
        source = "git@github.com:jmwerk/carbridge-patches.git"
        author = "jmwerk"
        contact = "https://github.com/jmwerk/carbridge-patches/issues"
        website = "https://github.com/jmwerk/carbridge-patches"
        license = "GPLv3"
    }
}

// The Android Auto rules live in CarBridge's core module (git submodule), shared with the app
kotlin.sourceSets.named("main") {
    kotlin.srcDir(rootProject.file("carbridge/core/src/main/kotlin"))
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

// The split-screen payload's Car App Library reads its API level from a class-path resource,
// which its dex can't carry; the patch ships it beside the payload
val carAppLibrary = configurations.create("carAppLibrary") { isTransitive = false }

dependencies {
    carAppLibrary(variantOf(libs.androidx.car.app) { artifactType("aar") })
}

val carAppApiLevel = tasks.register<Copy>("carAppApiLevel") {
    val aar = carAppLibrary.elements.map { files -> files.map { zipTree(it).matching { include("classes.jar") } } }
    from(aar.map { trees -> trees.flatMap { it.files }.map { zipTree(it) } }) {
        include("car-app-api.level")
    }
    into(layout.buildDirectory.dir("generated/carAppApiLevel/extensions"))
}

sourceSets.main {
    resources.srcDir(carAppApiLevel.map { it.destinationDir.parentFile })
}
