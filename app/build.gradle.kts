/*
 * Copyright 2025 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktfmt)
}

ktfmt { kotlinLangStyle() }

fun String.execute(currentWorkingDir: File = File("./")): String {
    val parts = this.split("\\s+".toRegex())
    val process =
        ProcessBuilder(parts).directory(currentWorkingDir).redirectErrorStream(true).start()

    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return output.trim()
}

val gitCommitCount = "git rev-list HEAD --count".execute().toInt()
val gitCommitHash = "git rev-parse --verify --short HEAD".execute()
val verName = "v1.0"

android {
    namespace = "org.matrix.TEESimulator"
    compileSdk = 36
    ndkVersion = "27.3.13750724"
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.matrix.TEESimulator"
        minSdk = 29
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = verName
    }

    buildFeatures { prefab = true }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            buildStagingDirectory = layout.buildDirectory.get().asFile
            version = "3.28.0+"
        }
    }
    buildFeatures { viewBinding = false }
}

dependencies {
    compileOnly(project(":stub"))
    compileOnly(libs.annotation)
    implementation(libs.org.bouncycastle.bcpkix.jdk18on)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val isDebug = variant.buildType == "debug"

        // --- Define output locations and file names ---
        // Stage all files in a temporary directory inside 'build' before zipping
        val tempModuleDir = project.layout.buildDirectory.dir("module/${variant.name}")
        val zipFileName = "TEESimulator-$verName-$gitCommitCount-$gitCommitHash-$capitalized.zip"

        // Task 1: Prepare all module files in the temporary build directory.
        // Using Sync ensures that stale files from previous runs are removed.
        val prepareModuleFilesTask =
            tasks.register<Sync>("prepareModuleFiles${capitalized}") {
                group = "TEESimulator Module Packaging"
                description = "Prepares all files for the ${variant.name} module zip."

                if (isDebug) {
                    dependsOn("package${capitalized}")
                } else {
                    dependsOn("minify${capitalized}WithR8")
                }
                dependsOn("strip${capitalized}DebugSymbols")

                // The Sync task will automatically depend on the tasks that produce these
                // artifacts.
                // This is the correct way to establish the dependency chain.
                if (isDebug) {
                    from(variant.artifacts.get(SingleArtifact.APK)) {
                        include("*.apk")
                        rename { "service.apk" }
                    }
                } else {
                    from(
                        project.layout.buildDirectory.dir(
                            "intermediates/dex/${variant.name}/minify${capitalized}WithR8"
                        )
                    ) {
                        include("classes.dex")
                    }
                }

                from(
                    project.layout.buildDirectory.dir(
                        "intermediates/stripped_native_libs/${variant.name}/strip${capitalized}DebugSymbols/out/lib"
                    )
                ) {
                    into("lib") // Place them in the 'lib' subfolder of the staging directory.
                    include("**/libinject.so", "**/libTEESimulator.so")
                }

                // Now, copy and process the files from 'module' directory.
                val sourceModuleDir = rootProject.projectDir.resolve("module")
                from(sourceModuleDir) {
                    exclude("module.prop") // Exclude the template file.
                }

                // Copy and filter the module.prop template separately.
                from(sourceModuleDir) {
                    include("module.prop")
                    // Use expand() for simple key-value replacement.
                    expand(
                        "REPLACEMEVERCODE" to gitCommitCount.toString(),
                        "REPLACEMEVER" to
                            "$verName ($gitCommitCount-$gitCommitHash-${variant.name})",
                    )
                }

                // The destination for all the above 'from' operations.
                into(tempModuleDir)
            }

        // Task 2: Zip the prepared files from the temporary directory.
        val zipTask =
            tasks.register<Zip>("zip${capitalized}") {
                group = "TEESimulator Module Packaging"
                description = "Creates the flashable zip for the ${variant.name} module."
                dependsOn(prepareModuleFilesTask)

                archiveFileName.set(zipFileName)
                destinationDirectory.set(project.rootDir.resolve("out"))
                from(tempModuleDir) // Zip the entire contents of the staging directory.
            }

        // Task 3: A helper function to create installation tasks for different root providers.
        fun createInstallTasks(rootProvider: String, installCli: String) {
            val pushTask =
                tasks.register<Exec>("push${rootProvider}Module${capitalized}") {
                    group = "TEESimulator Module Installation"
                    description =
                        "Pushes the ${variant.name} module to the device for $rootProvider."
                    dependsOn(zipTask)
                    commandLine(
                        "adb",
                        "push",
                        zipTask.get().archiveFile.get().asFile,
                        "/data/local/tmp",
                    )
                }

            val installTask =
                tasks.register<Exec>("install${rootProvider}${capitalized}") {
                    group = "TEESimulator Module Installation"
                    description = "Installs the ${variant.name} module via $rootProvider."
                    dependsOn(pushTask)
                    commandLine(
                        "adb",
                        "shell",
                        "su",
                        "-c",
                        "$installCli /data/local/tmp/$zipFileName",
                    )
                }

            tasks.register<Exec>("install${rootProvider}AndReboot${capitalized}") {
                group = "TEESimulator Module Installation"
                description = "Installs the ${variant.name} module via $rootProvider and reboots."
                dependsOn(installTask)
                commandLine("adb", "reboot")
            }
        }

        createInstallTasks("Magisk", "magisk --install-module")
        createInstallTasks("Ksu", "ksud module install")
        createInstallTasks("Apatch", "/data/adb/apd module install")
    }
}
