package me.rkt

import java.io.File

class Ccompiler() {
    fun ensureToolchain(): String {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val gcc = File("toolchain/mingw64/bin/gcc.exe")

            if (!gcc.exists()) {
                println("MinGW-w64 not found. Installing...")
                runInstaller("powershell.exe", "-ExecutionPolicy", "Bypass",
                    "-File", "toolchain/scripts/download-toolchain.ps1")
            }

            check(gcc.exists()) { "gcc.exe was not found after installation" }
            return gcc.path
        }

        findCommand("clang")?.let { return it }
        findCommand("gcc")?.let { return it }
        findCommand("cc")?.let { return it }

        println("A C compiler was not found. Installing one...")
        runInstaller("sh", "toolchain/scripts/install-toolchain.sh")

        return findCommand("clang")
            ?: findCommand("gcc")
            ?: findCommand("cc")
            ?: error("No C compiler was found after installation")
    }

    private fun findCommand(command: String): String? {
        val probe = ProcessBuilder("sh", "-c", "command -v $command")
            .redirectErrorStream(true)
            .start()
        if (probe.waitFor() != 0) return null
        return probe.inputStream.bufferedReader().readLine()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun runInstaller(vararg command: String) {
        val exitCode = ProcessBuilder(*command)
            .inheritIO()
            .start()
            .waitFor()
        check(exitCode == 0) {
            "C toolchain installation failed (exit code: $exitCode)"
        }
    }

    fun compile(source: File, executable: File): Int {
        val compiler = ensureToolchain()

        val command = mutableListOf(compiler)
        if (System.getProperty("os.name").startsWith("Windows")) {
            command += "-finput-charset=UTF-8"
            command += "-fexec-charset=UTF-8"
        }
        command += source.path
        command += "-o"
        command += executable.path

        val process = ProcessBuilder(command)
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
        val extension = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val executable = source
            .resolveSibling("${source.fileName.toString().substringBeforeLast('.')}$extension")
            .toFile()

        compile(source.toFile(), executable)
        return run(executable)
    }
}