import net.labymod.labygradle.common.internal.labymod.addon.model.AddonPermission

plugins {
    id("net.labymod.labygradle")
    id("net.labymod.labygradle.addon")
}

val versions = providers.gradleProperty("net.labymod.minecraft-versions").get().split(";")

group = "net.statustag"
version = providers.environmentVariable("VERSION").getOrElse("1.1.1")

labyMod {
    defaultPackageName = "net.statustag"

    minecraft {
        registerVersion(versions.toTypedArray()) {
            runs {
                getByName("client") {
                }
            }
        }
    }

    addonInfo {
        namespace = "statustag"
        displayName = "StatusTag"
        author = "1iNathan"
        description = "See who is online, away or busy at a glance! Colored status dots for every player in the tab list, on name tags and in chat."
        minecraftVersion = "*"
        version = rootProject.version.toString()
        permission(
            AddonPermission.FILE_SYSTEM,
            AddonPermission.EXTERNAL_SERVERS_GET,
            AddonPermission.EXTERNAL_SERVERS_ACCESS
        )
    }
}

tasks.register("deployLocal") {
    dependsOn("createReleaseJar")
    doLast {
        val libsDir = layout.buildDirectory.dir("libs").get().asFile
        val releaseJar = libsDir.resolve("statustag-release.jar")
        val plainJar = libsDir.resolve("statustag.jar")
        if (releaseJar.exists()) {
            releaseJar.copyTo(plainJar, overwrite = true)
        }

        val addonsDir = providers.gradleProperty("labymodAddonsDir").orNull
        if (addonsDir != null && plainJar.exists()) {
            val target = file(addonsDir).resolve(plainJar.name)
            try {
                plainJar.copyTo(target, overwrite = true)
                logger.lifecycle("Deployed addon to ${target.absolutePath}")
            } catch (exception: Exception) {
                logger.warn(
                    "Could not deploy addon to ${target.absolutePath} " +
                        "(close Minecraft/LabyMod and run deployLocal again): ${exception.message}"
                )
            }
        }
    }
}

subprojects {
    plugins.apply("net.labymod.labygradle")
    plugins.apply("net.labymod.labygradle.addon")

    group = rootProject.group
    version = rootProject.version

    extensions.findByType(JavaPluginExtension::class.java)?.apply {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
