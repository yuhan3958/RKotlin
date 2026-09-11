package me.rkt

import java.io.File

class Ccompiler() {
    fun ensureToolchain(): File {
        val gcc = File("toolchain/mingw64/bin/gcc.exe")

        if (gcc.exists()) {
            return gcc
        }

        println("MinGW-w64 not found. Installing...")

        val exitCode = ProcessBuilder(
            "powershell.exe",
            "-ExecutionPolicy", "Bypass",
            "-File", "toolchain/scripts/download-toolchain.ps1"
        )
            .inheritIO()
            .start()
            .waitFor()

        check(exitCode == 0) {
            "Toolchain installation failed (exit code: $exitCode)"
        }

        check(gcc.exists()) {
            "gcc.exe was not found after installation"
        }

        return gcc
    }

    fun compile(source: File, executable: File): Int {
        val gcc = ensureToolchain()

        val process = ProcessBuilder(
            gcc.path,
            "-finput-charset=UTF-8",
            "-fexec-charset=UTF-8",
            source.path,
            "-o",
            executable.path
        )
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "GCC compilation failed"
        }
        return exitCode
    }

    fun run(executable: File): Int {
        val process = ProcessBuilder(executable.path)
            .inheritIO()
            .start()

        return process.waitFor()
    }

    fun compileAndRun(source: java.nio.file.Path): Int {
        val executable = source
            .resolveSibling("${source.fileName.toString().substringBeforeLast('.')}.exe")
            .toFile()

        compile(source.toFile(), executable)
        return run(executable)
    }
}