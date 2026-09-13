package me.rkt

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        printUsage()
        return
    }

    val run = args.any { it == "--run" || it == "-r" }
    val compile = args.any { it == "--compile" || it == "-p" }
    val debug = args.any { it == "--debug" }
    val debugFileIndex = args.indexOf("--debug-file")
    val debugFile = if (debugFileIndex >= 0) {
        args.getOrNull(debugFileIndex + 1)?.let(Path::of)
            ?: error("--debug-file requires a path")
    } else {
        null
    }
    val positional = args.filterIndexed { index, argument ->
        argument != "--run" && argument != "-r" &&
            argument != "--compile" && argument != "-p" &&
            argument != "--debug" &&
            argument != "--debug-file" &&
            !(debugFileIndex >= 0 && index == debugFileIndex + 1)
    }

    if (positional.isEmpty() || positional.size > 2) {
        printUsage()
        return
    }

    val input = Path.of(positional[0])
    val output = positional.getOrNull(1)
        ?.let(Path::of)
        ?: input.resolveSibling("${input.fileName.toString().substringBeforeLast('.')}.c")

    Compiler().compileToC(input, output, debug, debugFile)

    if (run) {
        exitProcess(Ccompiler().compileAndRun(output))
    }
    if (compile) {
        val executable = output.resolveSibling(
            "${output.fileName.toString().substringBeforeLast('.')}" +
                if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        )
        exitProcess(Ccompiler().compile(output.toFile(), executable.toFile()))
    }
}

private fun printUsage() {
    println(
        """
        usage: rkotlin [options] <file.rk> [output.c]

        options:
          -h, --help    Show this help message
          -p, --compile Compile the generated C into an executable
          -r, --run     Compile the generated C and run the executable
          --debug      Print semantic analysis results and lowered IR
          --debug-file <path>
                        Write semantic analysis results and lowered IR to a file
        """.trimIndent()
    )
}
