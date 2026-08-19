import support.natives.cargoAvailable
import support.natives.hostNativeKey
import support.natives.nativeLibraryBaseNames
import support.natives.nativeLibraryName
import support.natives.nativePlatformKeys
import support.natives.toPlatformList
import support.natives.toStrictBoolean
import java.io.File

/** Adds the native libraries to the process resources. */
tasks.withType<ProcessResources>().configureEach {
    val rootProjectDir = rootProject.projectDir
    val nativeBundleDir = rootProject.file("native/build/ci-bundle/dreamdisplayx-natives")
    val requireNatives = providers.gradleProperty("dreamdisplayx.requireNatives")
        .orElse(providers.environmentVariable("DREAMDISPLAYX_REQUIRE_NATIVES"))
        .map { it.toStrictBoolean() }
        .getOrElse(false)
    val requiredNativePlatforms = providers.gradleProperty("dreamdisplayx.requiredNativePlatforms")
        .orElse(providers.environmentVariable("DREAMDISPLAYX_REQUIRED_NATIVE_PLATFORMS"))
        .map { it.toPlatformList() }
        .getOrElse(nativePlatformKeys)
    val unknownNativePlatforms = requiredNativePlatforms.filterNot { it in nativePlatformKeys }
    if (unknownNativePlatforms.isNotEmpty()) {
        throw GradleException("Unknown required native platforms: ${unknownNativePlatforms.joinToString()}.")
    }

    inputs.property("dreamdisplayxRequireNatives", requireNatives)
    inputs.property("dreamdisplayxRequiredNativePlatforms", requiredNativePlatforms.joinToString(","))
    inputs.files(rootProject.fileTree(nativeBundleDir)).optional()

    if (nativeBundleDir.isDirectory) {
        from(nativeBundleDir) {
            into("dreamdisplayx-natives")
        }
    } else {
        val nativeKey = hostNativeKey()
        val hostReleaseNatives = nativeLibraryBaseNames.map { libBaseName ->
            rootProject.file("native/target/release/" + System.mapLibraryName(libBaseName))
        }
        val autoBuild = providers.gradleProperty("dreamdisplayx.autoBuildNatives")
            .map { it.toStrictBoolean() }
            .getOrElse(cargoAvailable())
        if (autoBuild) dependsOn(":native:buildHostNatives")
        inputs.files(hostReleaseNatives).optional()
        from({ hostReleaseNatives.filter { it.isFile } }) {
            into("dreamdisplayx-natives/$nativeKey")
        }
    }

    doFirst {
        if (!requireNatives) return@doFirst
        if (!nativeBundleDir.isDirectory) {
            throw GradleException(
                "Native bundle is required, but $nativeBundleDir does not exist. " +
                        "Run the CI native matrix first or disable DREAMDISPLAYX_REQUIRE_NATIVES."
            )
        }

        val missingNativeLibraries = requiredNativePlatforms.flatMap { platformKey ->
            nativeLibraryBaseNames.map { libBaseName ->
                File(nativeBundleDir, "$platformKey/${nativeLibraryName(platformKey, libBaseName)}")
            }
        }.filterNot { it.isFile }

        val missingLicenseFiles = requiredNativePlatforms
            .map { platformKey -> File(nativeBundleDir, "$platformKey/licenses/ffmpeg-license.txt") }
            .filterNot { it.isFile && it.length() > 0L }

        if (missingNativeLibraries.isNotEmpty() || missingLicenseFiles.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Native bundle is incomplete.")
                    if (missingNativeLibraries.isNotEmpty()) {
                        appendLine("Missing required Dream DisplaysX libraries:")
                        missingNativeLibraries.forEach { appendLine(" - ${it.relativeTo(rootProjectDir)}") }
                    }
                    if (missingLicenseFiles.isNotEmpty()) {
                        appendLine("Missing required FFmpeg license metadata:")
                        missingLicenseFiles.forEach { appendLine(" - ${it.relativeTo(rootProjectDir)}") }
                    }
                }
            )
        }
    }
}
