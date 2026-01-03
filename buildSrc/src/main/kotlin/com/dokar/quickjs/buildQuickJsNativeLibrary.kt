package com.dokar.quickjs

import org.gradle.api.Project
import java.io.File
import java.util.Properties
import java.nio.file.Files
import java.nio.file.Paths

internal fun Project.buildQuickJsNativeLibrary(
    cmakeFile: File,
    platform: Platform,
    sharedLib: Boolean,
    withJni: Boolean,
    release: Boolean,
    outputDir: File? = null,
    withPlatformSuffixIfCopy: Boolean = false,
) {
    val libType = if (sharedLib) "shared" else "static"

    println("Building $libType native library for target '$platform'...")

    val buildType = if (release) "MinSizeRel" else "Debug"
    val commonArgs = arrayOf(
        "-B",
        "build/$platform",
        "-DCMAKE_BUILD_TYPE=${buildType}",
        "-DTARGET_PLATFORM=$platform",
        "-DBUILD_WITH_JNI=${if (withJni) "ON" else "OFF"}",
        "-DLIBRARY_TYPE=${if (sharedLib) "shared" else "static"}",
    )

    // Generators
    val ninja = "-G Ninja"
    val xcode = "-G Xcode"

    fun javaHomeArg(home: String): String {
        return "-DPLATFORM_JAVA_HOME=$home"
    }

    fun findNinja(): String? {
        // Check NINJA_PATH environment variable first
        val ninjaPathEnv = System.getenv("NINJA_PATH")
        if (ninjaPathEnv != null && Files.exists(Paths.get(ninjaPathEnv))) {
            return ninjaPathEnv
        }

        // Try common paths first (for macOS with Homebrew)
        val commonPaths = listOf(
            "/opt/homebrew/bin/ninja",
            "/usr/local/bin/ninja",
            "/usr/bin/ninja",
        )

        for (path in commonPaths) {
            if (Files.exists(Paths.get(path))) {
                return path
            }
        }

        // Try to find ninja in PATH
        val pathEnv = System.getenv("PATH") ?: ""
        val pathDirs = pathEnv.split(File.pathSeparator)
        for (dir in pathDirs) {
            val ninjaPath = Paths.get(dir, "ninja")
            if (Files.exists(ninjaPath) && Files.isExecutable(ninjaPath)) {
                return ninjaPath.toString()
            }
        }

        return null
    }

    // Find ninja and add CMAKE_MAKE_PROGRAM if using Ninja generator
    val ninjaPath = findNinja()
    val ninjaMakeProgram = if (ninjaPath != null) {
        "-DCMAKE_MAKE_PROGRAM=$ninjaPath"
    } else {
        null
    }

    val generateArgs = if (withJni) {
        when (platform) {
            Platform.windows_x64 -> {
                val args = commonArgs + ninja + javaHomeArg(windowX64JavaHome())
                if (ninjaMakeProgram != null) args + ninjaMakeProgram else args
            }
            Platform.linux_x64 -> {
                val args = commonArgs + ninja + javaHomeArg(linuxX64JavaHome())
                if (ninjaMakeProgram != null) args + ninjaMakeProgram else args
            }
            Platform.linux_aarch64 -> {
                val args = commonArgs + ninja + javaHomeArg(linuxAarch64JavaHome())
                if (ninjaMakeProgram != null) args + ninjaMakeProgram else args
            }
            Platform.macos_x64 -> {
                val args = commonArgs + ninja + javaHomeArg(macosX64JavaHome())
                if (ninjaMakeProgram != null) args + ninjaMakeProgram else args
            }
            Platform.macos_aarch64 -> {
                val args = commonArgs + ninja + javaHomeArg(macosAarch64JavaHome())
                if (ninjaMakeProgram != null) args + ninjaMakeProgram else args
            }
            else -> error("Unsupported platform: '$platform'")
        }
    } else {
        when (platform) {
            Platform.windows_x64,
            Platform.linux_aarch64,
            Platform.linux_x64,
            Platform.macos_aarch64,
            Platform.macos_x64 -> {
                val args = commonArgs + ninja
                if (ninjaMakeProgram != null) args + ninjaMakeProgram else args
            }

            Platform.ios_aarch64,
            Platform.ios_x64,
            Platform.ios_simulator_aarch64 -> commonArgs + xcode
        }
    }

    val buildArgs = when (platform) {
        Platform.ios_x64,
        Platform.ios_simulator_aarch64 -> arrayOf(
            commonArgs[1],
            "--",
            "-sdk",
            "iphonesimulator"
        )

        else -> arrayOf(commonArgs[1])
    }

    fun findCmake(): String {
        // Check CMAKE_PATH environment variable first
        val cmakePathEnv = System.getenv("CMAKE_PATH")
        if (cmakePathEnv != null && Files.exists(Paths.get(cmakePathEnv))) {
            return cmakePathEnv
        }
        
        // Try common paths first (for macOS with Homebrew)
        val commonPaths = listOf(
            "/opt/homebrew/bin/cmake",
            "/usr/local/bin/cmake",
            "/usr/bin/cmake",
        )
        
        for (path in commonPaths) {
            if (Files.exists(Paths.get(path))) {
                return path
            }
        }
        
        // Try to find cmake in PATH
        val pathEnv = System.getenv("PATH") ?: ""
        val pathDirs = pathEnv.split(File.pathSeparator)
        for (dir in pathDirs) {
            val cmakePath = Paths.get(dir, "cmake")
            if (Files.exists(cmakePath) && Files.isExecutable(cmakePath)) {
                return cmakePath.toString()
            }
        }
        
        error(
            """
            Cannot find cmake executable. Please install cmake:
            - macOS: brew install cmake
            - Linux: sudo apt-get install cmake (or use your package manager)
            - Windows: Download from https://cmake.org/download/
            
            If cmake is already installed, make sure it's in your PATH or set CMAKE_PATH environment variable.
            """.trimIndent()
        )
    }



    fun runCommand(vararg args: Any) {
        val cmd = args.toList().toTypedArray()
        // Replace "cmake" with actual path if it's the first argument
        if (cmd.isNotEmpty() && cmd[0] == "cmake") {
            cmd[0] = findCmake()
        }
        exec {
            workingDir = cmakeFile.parentFile
            standardOutput = System.out
            errorOutput = System.err
            commandLine(*cmd)
        }
    }

    fun copyLibToOutputDir(outDir: File) {
        // Copy built library to output dir
        if (!outDir.exists() && !outDir.mkdirs()) {
            error("Failed to create library output dir: $outDir")
        }
        println("Copying built QuickJS $libType library to ${file(outDir)}")
        val (dir, ext) = if (sharedLib) {
            when (platform.osName) {
                "windows" -> "" to "dll"
                "linux" -> "" to "so"
                "macos" -> "" to "dylib"
                else -> error("Unsupported platform: $platform")
            }
        } else {
            when (platform) {
                Platform.ios_aarch64 -> "$buildType-iphoneos/" to "a"

                Platform.ios_x64 -> "$buildType-iphonesimulator/" to "a"

                Platform.ios_simulator_aarch64 -> "$buildType/" to "a"

                else -> "" to "a"
            }
        }
        val libraryFile = file("native/build/$platform/${dir}libquickjs.$ext")
        val destFilename = if (withPlatformSuffixIfCopy) {
            "libquickjs_${platform}.$ext"
        } else {
            "libquickjs.$ext"
        }
        libraryFile.copyTo(File(outDir, destFilename), overwrite = true)
    }

    // Check if ninja is required but not found
    val requiresNinja = generateArgs.contains("-G Ninja")
    if (requiresNinja && ninjaPath == null) {
        error(
            """
            Cannot find ninja executable. Ninja is required for building on this platform.
            Please install ninja:
            - macOS: brew install ninja
            - Linux: sudo apt-get install ninja-build (or use your package manager)
            - Windows: Download from https://github.com/ninja-build/ninja/releases
            
            If ninja is already installed, make sure it's in your PATH or set NINJA_PATH environment variable.
            """.trimIndent()
        )
    }

    // Generate build files
    runCommand("cmake", *generateArgs, "./")
    // Build
    runCommand("cmake", "--build", *buildArgs)

    if (outputDir != null) {
        copyLibToOutputDir(outputDir)
    }
}

/// Multiplatform JDK locations

private fun Project.windowX64JavaHome() =
    requireNotNull(envVarOrLocalPropOf("JAVA_HOME_WINDOWS_X64")) {
        "'JAVA_HOME_WINDOWS_X64' is not found in env vars or local.properties"
    }

private fun Project.linuxX64JavaHome() =
    requireNotNull(envVarOrLocalPropOf("JAVA_HOME_LINUX_X64")) {
        "'JAVA_HOME_LINUX_X64' is not found in env vars or local.properties"
    }

private fun Project.linuxAarch64JavaHome() =
    requireNotNull(envVarOrLocalPropOf("JAVA_HOME_LINUX_AARCH64")) {
        "'JAVA_HOME_LINUX_AARCH64' is not found env vars or in local.properties"
    }

private fun Project.macosX64JavaHome() =
    requireNotNull(envVarOrLocalPropOf("JAVA_HOME_MACOS_X64")) {
        "'JAVA_HOME_MACOS_X64' is not found in env vars or local.properties"
    }

private fun Project.macosAarch64JavaHome() =
    requireNotNull(envVarOrLocalPropOf("JAVA_HOME_MACOS_AARCH64")) {
        "'JAVA_HOME_MACOS_AARCH64' is not found in env vars or local.properties"
    }

private fun Project.envVarOrLocalPropOf(key: String): String? {
    val localProperties = Properties()
    val localPropertiesFile = project.rootDir.resolve("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use {
            localProperties.load(it)
        }
    }
    return System.getenv(key) ?: localProperties[key]?.toString()
}
