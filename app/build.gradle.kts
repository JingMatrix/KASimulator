/*
 * Copyright 2025 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun String.execute(currentWorkingDir: File = File("./")): String {
    val parts = this.split("\\s+".toRegex())
    val process = ProcessBuilder(parts)
        .directory(currentWorkingDir)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return output.trim()
}


val gitCommitCount = "git rev-list HEAD --count".execute().toInt()
val gitCommitHash = "git rev-parse --verify --short HEAD".execute()
val verName = "v2.1.0"

android {
    namespace = "io.github.beakthoven.TrickyStoreOSS"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.beakthoven.TrickyStoreOSS"
        minSdk = 29
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = verName

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                arguments += "-DANDROID_ALLOW_UNDEFINED_SYMBOLS=ON"
                arguments += "-DCMAKE_CXX_STANDARD=23"
                arguments += "-DCMAKE_C_STANDARD=23"
                arguments += "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON"
                arguments += "-DLSPLT_BUILD_SHARED=OFF"
                arguments += "-DLSPLT_STANDALONE=ON"

                cppFlags += "-std=c++23"
                cppFlags += "-fno-exceptions"
                cppFlags += "-fno-rtti"
                cppFlags += "-fvisibility=hidden"
                cppFlags += "-fvisibility-inlines-hidden"
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    compileOnly(project(":stub"))
    compileOnly(libs.annotation)
    implementation(libs.org.bouncycastle.bcpkix.jdk18on)
    implementation(libs.org.lsposed.libcxx.libcxx)
}

afterEvaluate {
    android.applicationVariants.forEach { variant ->
        val variantName = variant.name
        val capitalized = variantName.replaceFirstChar { it.uppercase() }
        val tempModuleDir = project.layout.buildDirectory.dir("tmp/module-${variantName}")
        
        tasks.register("copyFiles${capitalized}") {
            dependsOn("assemble${capitalized}")
            val moduleFolder = project.rootDir.resolve("module")
            val buildDir = project.layout.buildDirectory
            
            doLast {
                val isDebug = variantName.contains("debug", ignoreCase = true)
                //val apkFile = variant.outputs.first().outputFile

                listOf("service.apk", "classes.dex").forEach { fileName ->
                    val oldFile = moduleFolder.resolve(fileName)
                    if (oldFile.exists()) oldFile.delete()
                }

                // Select source file based on build type
                val sourceFile = if (isDebug) {
                    variant.outputs.first().outputFile
                } else {
                    buildDir.get().asFile.resolve("intermediates/dex/release/minifyReleaseWithR8/classes.dex")
                }

                val destFileName = if (isDebug) "service.apk" else "classes.dex"
                sourceFile.copyTo(moduleFolder.resolve(destFileName), overwrite = true)

                val soDir = buildDir.get()
                    .asFile
                    .resolve("intermediates/stripped_native_libs/$variantName/strip${capitalized}DebugSymbols/out/lib")
                
                //apkFile.copyTo(moduleFolder.resolve("service.apk"), overwrite = true)
                
                val allowedLibs = setOf("libinject.so", "libTrickyStoreOSS.so")
                soDir.walk()
                    .filter { it.isFile && it.name in allowedLibs }
                    .forEach { soFile ->
                        val abiFolder = soFile.parentFile.name
                        val destination = moduleFolder.resolve("lib/$abiFolder/${soFile.name}")
                        soFile.copyTo(destination, overwrite = true)
                    }
            }
        }
        
        // Prepare temp directory with all files
        tasks.register("prepareModuleFiles${capitalized}") {
            dependsOn("copyFiles${capitalized}")
            val sourceDir = project.rootDir.resolve("module")
            
            doLast {
                val tempDir = tempModuleDir.get().asFile
                
                // Clean and create temp directory
                tempDir.deleteRecursively()
                tempDir.mkdirs()
                
                // Copy all files except module.prop
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.name != "module.prop" }
                    .forEach { sourceFile ->
                        val relativePath = sourceFile.relativeTo(sourceDir)
                        val destFile = tempDir.resolve(relativePath)
                        destFile.parentFile.mkdirs()
                        sourceFile.copyTo(destFile, overwrite = true)
                    }
                
                // Process module.prop
                val sourceProp = sourceDir.resolve("module.prop")
                val destProp = tempDir.resolve("module.prop")
                val content = sourceProp.readText()
                val processedContent = content
                    .replace("REPLACEMEVERCODE", gitCommitCount.toString())
                    .replace("REPLACEMEVER", "$verName ($gitCommitCount-$gitCommitHash-$variantName)")
                destProp.writeText(processedContent)
            }
        }
        
        // Zip task uses the temp directory
        val zipTask =
            tasks.register<Zip>("zip${capitalized}") {
                dependsOn("prepareModuleFiles${capitalized}")
                archiveFileName.set(
                    "TEESimulator-$verName-$gitCommitCount-$gitCommitHash-${capitalized}.zip"
                )
                destinationDirectory.set(project.rootDir.resolve("out"))
                from(tempModuleDir)
            }

        val pushTask =
            tasks.register<Exec>("push${capitalized}") {
                group = "TEESimulator Module Installation"
                dependsOn(zipTask)
                commandLine(
                    "adb",
                    "push",
                    zipTask.get().archiveFile.get().asFile,
                    "/data/local/tmp",
                )
                description = "Pushes the $variantName module zip to the device."
            }

        // --- Magisk Install Tasks ---
        val installMagiskTask =
            tasks.register<Exec>("installMagisk${capitalized}") {
                group = "TEESimulator Module Installation"
                dependsOn(pushTask)
                commandLine(
                    "adb",
                    "shell",
                    "su",
                    "-c",
                    "magisk --install-module /data/local/tmp/${zipTask.get().archiveFileName.get()}",
                )
                description = "Installs the $variantName module via Magisk."
            }
        tasks.register<Exec>("installMagiskAndReboot${capitalized}") {
            group = "TEESimulator Module Installation"
            dependsOn(installMagiskTask)
            commandLine("adb", "reboot")
            description = "Installs the $variantName module via Magisk and reboots."
        }

        // --- KernelSU Install Tasks ---
        val installKsuTask =
            tasks.register<Exec>("installKsu${capitalized}") {
                group = "TEESimulator Module Installation"
                dependsOn(pushTask)
                commandLine(
                    "adb",
                    "shell",
                    "su",
                    "-c",
                    "ksud module install /data/local/tmp/${zipTask.get().archiveFileName.get()}",
                )
                description = "Installs the $variantName module via KernelSU."
            }
        tasks.register<Exec>("installKsuAndReboot${capitalized}") {
            group = "TEESimulator Module Installation"
            dependsOn(installKsuTask)
            commandLine("adb", "reboot")
            description = "Installs the $variantName module via KernelSU and reboots."
        }

        // --- APatch Install Tasks ---
        val installApatchTask =
            tasks.register<Exec>("installApatch${capitalized}") {
                group = "TEESimulator Module Installation"
                dependsOn(pushTask)
                commandLine(
                    "adb",
                    "shell",
                    "su",
                    "-c",
                    "/data/adb/apd module install /data/local/tmp/${zipTask.get().archiveFileName.get()}",
                )
                description = "Installs the $variantName module via APatch."
            }
        tasks.register<Exec>("installApatchAndReboot${capitalized}") {
            group = "TEESimulator Module Installation"
            dependsOn(installApatchTask)
            commandLine("adb", "reboot")
            description = "Installs the $variantName module via APatch and reboots."
        }
        
        tasks["assemble${capitalized}"].finalizedBy("zip${capitalized}")
    }
}
