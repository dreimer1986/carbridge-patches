package org.carbridge.patches.androidauto

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.document
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import org.carbridge.core.SplitScreenOptions
import org.carbridge.core.SplitScreenPatch

// The extensions' dex files in the patches bundle, by the APK path the payload goes to
private val PAYLOAD_FILES = mapOf(
    "extensions/splitscreenpayload.mpe" to SplitScreenPatch.PAYLOAD_DEX_ASSET,
    // A class-path resource of the payload's Car App Library; the loader serves it from assets
    "extensions/car-app-api.level" to SplitScreenPatch.CAR_APP_API_LEVEL_ASSET,
)

@Suppress("unused")
val showInAndroidAutoSplitScreenPatch = patch(
    name = "Show in Android Auto split screen",
    description = "Experimental. Shows the app in Android Auto's main split-screen pane, next to " +
        "the media and notification cards. Needs Shizuku running on the phone. " +
        "Don't combine with Show in Android Auto.",
    default = false,
) {
    // No compatibleWith: the patch applies to any app.
    extendWith("extensions/splitscreen.mpe")

    val fullHeightOption = booleanOption(
        key = "fullHeight",
        default = false,
        title = "Let the app go under the Back button",
        description = "Show the app under Android Auto's Back button instead of below it. The app " +
            "gets more room, but the button covers its top-right corner while it's visible.",
    )

    execute {
        document("AndroidManifest.xml").use { doc ->
            val host = DomHost(doc)
            if (SplitScreenPatch.hasCarPatch(host.application)) throw combinedError()
            val options = SplitScreenOptions(fullHeight = fullHeightOption.value == true)
            try {
                SplitScreenPatch.apply(host, doc.documentElement.getAttribute("package"), options)
            } catch (e: IllegalStateException) {
                throw PatchException(e.message ?: "Show in Android Auto split screen failed")
            }
        }
        writePayload()
    }

    finalize {
        // Runs after every patch, so a package rename by another patch is already applied
        document("AndroidManifest.xml").use { doc ->
            val app = DomHost(doc).application
            // Either patch may run first
            if (SplitScreenPatch.hasCarPatch(app)) throw combinedError()
            SplitScreenPatch.updateShizukuAuthority(app, doc.documentElement.getAttribute("package"))
        }
    }
}

private fun combinedError() = PatchException(
    "Show in Android Auto and Show in Android Auto split screen can't be combined. Turn one of them off.",
)

/**
 * Merging the payload would let an app's own copy of the Car App Library replace classes of ours,
 * so it ships as a separate dex.
 */
private fun writePayload() {
    val bundle = object {}.javaClass.classLoader
    for ((source, path) in PAYLOAD_FILES) {
        val input = bundle.getResourceAsStream(source)
            ?: throw PatchException("Missing $source in the patches bundle")
        resourceFile(path, false).apply {
            parentFile.mkdirs()
            input.use { from -> outputStream().use { from.copyTo(it) } }
        }
    }
}
