package org.carbridge.patches.androidauto

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.document
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import org.carbridge.core.AndroidAttr
import org.carbridge.core.CarPatch
import org.carbridge.core.ManifestElement

private const val EXTENSION = "org.carbridge.extension.splitscreen"
private const val SERVICE = "$EXTENSION.SplitScreenCarAppService"
private const val DESCRIPTOR_NAME = "carbridge_split_screen_app_desc"
private const val NAVIGATION_CATEGORY = "androidx.car.app.category.NAVIGATION"
private const val SHIZUKU_PROVIDER = "rikka.shizuku.ShizukuProvider"
// Added by Show in Android Auto to launcher activities
private const val CAR_LAUNCHER_CATEGORY = "android.intent.category.CAR_LAUNCHER"
// Read by the payload's AppDisplay
private const val FULL_HEIGHT_META = "$EXTENSION.FULL_HEIGHT"
// The car code and its own Car App Library, loaded apart from the app's classes; see the
// extension's PayloadClassLoader, which reads it from the asset path
private const val PAYLOAD_ASSETS = "assets/carbridge"
private val PAYLOAD_FILES = mapOf(
    "extensions/splitscreenpayload.mpe" to "splitscreen.dex",
    // A class-path resource of the payload's Car App Library; the loader serves it from assets
    "extensions/car-app-api.level" to "car-app-api.level",
)

// android.R.attr ids, verified against android-36 android.jar
private val ALLOW_EMBEDDED = AndroidAttr("allowEmbedded", 0x010103f5)
private val EXPORTED = AndroidAttr("exported", 0x01010010)
private val AUTHORITIES = AndroidAttr("authorities", 0x01010018)
private val ENABLED = AndroidAttr("enabled", 0x0101000e)
private val MULTIPROCESS = AndroidAttr("multiprocess", 0x01010013)
private val PERMISSION = AndroidAttr("permission", 0x01010006)

private val SPLIT_SCREEN_PERMISSIONS = listOf(
    "androidx.car.app.ACCESS_SURFACE",
    "androidx.car.app.NAVIGATION_TEMPLATES",
    // Shizuku only sends its binder to apps that request this
    "moe.shizuku.manager.permission.API_V23",
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
        title = "Use the full pane height",
        description = "Show the app under Android Auto's Back button instead of below it. The app " +
            "gets more room, but the button covers its top-right corner while it's visible.",
    )

    execute {
        document("AndroidManifest.xml").use { doc ->
            val host = DomHost(doc)
            val app = host.application
            if (app.children("service").any { it.getString(CarPatch.NAME) == SERVICE }) return@use
            if (CarPatch.launcherActivities(app).isEmpty()) {
                throw PatchException("No launcher activity found")
            }

            for (perm in SPLIT_SCREEN_PERMISSIONS) {
                if (!host.hasPermission(perm)) host.addPermission(perm)
            }

            writePayload()
            writeDescriptor(keepMedia = findMeta(app, CarPatch.CAR_APP_META) != null)
            setMeta(app, CarPatch.CAR_APP_META) { it.setResource(CarPatch.RESOURCE, "xml", DESCRIPTOR_NAME) }
            setMeta(app, "androidx.car.app.minCarApiLevel") { it.setInt(CarPatch.VALUE, 5) }
            setMeta(app, "moe.shizuku.client.V3_SUPPORT") { it.setBoolean(CarPatch.VALUE, true) }

            stripCarAppServices(app)
            addService(app, fullHeight = fullHeightOption.value == true)
            addShizukuProvider(app, doc.documentElement.getAttribute("package"))

            for (activity in app.children("activity")) {
                // Activities may only start on an app-owned virtual display when embeddable
                activity.setBoolean(ALLOW_EMBEDDED, true)
                activity.setBoolean(CarPatch.RESIZEABLE, true)
                if (activity.getInt(CarPatch.SCREEN_ORIENTATION) in CarPatch.PORTRAIT_ORIENTATIONS) {
                    activity.remove(CarPatch.SCREEN_ORIENTATION)
                }
            }
        }
    }

    finalize {
        // Runs after every patch, so a package rename by another patch is already applied
        document("AndroidManifest.xml").use { doc ->
            val packageName = doc.documentElement.getAttribute("package")
            val app = DomHost(doc).application
            if (hasShowInAndroidAuto(app)) {
                throw PatchException(
                    "Show in Android Auto and Show in Android Auto split screen can't be combined. " +
                        "Turn one of them off.",
                )
            }
            app.children("provider").firstOrNull { it.getString(CarPatch.NAME) == SHIZUKU_PROVIDER }
                ?.setString(AUTHORITIES, "$packageName.shizuku")
        }
    }
}

/**
 * Merging the payload would let an app's own copy of the Car App Library replace classes of ours,
 * so it ships as a separate dex.
 */
private fun writePayload() {
    val bundle = object {}.javaClass.classLoader
    for ((source, asset) in PAYLOAD_FILES) {
        val input = bundle.getResourceAsStream(source)
            ?: throw PatchException("Missing $source in the patches bundle")
        resourceFile("$PAYLOAD_ASSETS/$asset", false).apply {
            parentFile.mkdirs()
            input.use { from -> outputStream().use { from.copyTo(it) } }
        }
    }
}

/** Keeps Android Auto's media player for apps that already declared one, and adds templates. */
private fun writeDescriptor(keepMedia: Boolean) {
    val uses = listOfNotNull("template", "media".takeIf { keepMedia })
    resourceFile("res/xml/$DESCRIPTOR_NAME.xml", false).apply {
        parentFile.mkdirs()
        writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<automotiveApp>\n" +
                uses.joinToString("") { "    <uses name=\"$it\"/>\n" } +
                "</automotiveApp>\n",
        )
    }
}

/** The app's own template screens would compete with the split-screen service. */
private fun stripCarAppServices(app: ManifestElement) {
    for (service in app.children("service")) {
        for (filter in service.children("intent-filter")) {
            val actions = filter.children("action").mapNotNull { it.getString(CarPatch.NAME) }
            if (CarPatch.CAR_APP_SERVICE_ACTION in actions) service.removeChild(filter)
        }
    }
}

private fun addService(app: ManifestElement, fullHeight: Boolean) {
    val service = app.append("service")
    service.setString(CarPatch.NAME, SERVICE)
    service.setBoolean(EXPORTED, true)
    setMeta(service, FULL_HEIGHT_META) { it.setBoolean(CarPatch.VALUE, fullHeight) }
    val filter = service.append("intent-filter")
    filter.append("action").setString(CarPatch.NAME, CarPatch.CAR_APP_SERVICE_ACTION)
    filter.append("category").setString(CarPatch.NAME, NAVIGATION_CATEGORY)
}

/** Shizuku delivers its binder through this provider; only system and shell may call it. */
private fun addShizukuProvider(app: ManifestElement, packageName: String) {
    if (app.children("provider").any { it.getString(CarPatch.NAME) == SHIZUKU_PROVIDER }) return
    val provider = app.append("provider")
    provider.setString(CarPatch.NAME, SHIZUKU_PROVIDER)
    // finalize updates this if another patch renames the package
    provider.setString(AUTHORITIES, "$packageName.shizuku")
    provider.setBoolean(MULTIPROCESS, false)
    provider.setBoolean(ENABLED, true)
    provider.setBoolean(EXPORTED, true)
    provider.setString(PERMISSION, "android.permission.INTERACT_ACROSS_USERS_FULL")
}

/**
 * Show in Android Auto adds the car launcher category to the app's launcher activities; apps don't
 * declare it themselves. Checked in finalize, since either patch may run first.
 */
private fun hasShowInAndroidAuto(app: ManifestElement) =
    CarPatch.launcherActivities(app).any { activity ->
        activity.children("intent-filter").any { filter ->
            filter.children("category").any { it.getString(CarPatch.NAME) == CAR_LAUNCHER_CATEGORY }
        }
    }

private fun findMeta(parent: ManifestElement, name: String) =
    parent.children("meta-data").firstOrNull { it.getString(CarPatch.NAME) == name }

private inline fun setMeta(parent: ManifestElement, name: String, setValue: (ManifestElement) -> Unit) {
    val meta = findMeta(parent, name) ?: parent.append("meta-data").also { it.setString(CarPatch.NAME, name) }
    setValue(meta)
}
