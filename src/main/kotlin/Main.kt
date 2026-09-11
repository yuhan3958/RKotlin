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
    val positional = args.filterNot {
        it == "--run" || it == "-r" ||
            it == "--compile" || it == "-p"
    }

    if (positional.isEmpty() || positional.size > 2) {
        printUsage()
        return
    }

    val input = Path.of(positional[0])
    val output = positional.getOrNull(1)
        ?.let(Path::of)
        ?: input.resolveSibling("${input.fileName.toString().substringBeforeLast('.')}.c")

    Compiler().compileToC(input, output)

    if (run) {
        exitProcess(Ccompiler().compileAndRun(output))
    }
    if (compile) {
        val executable = output.resolveSibling(
            "${output.fileName.toString().substringBeforeLast('.')}.exe"
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
        """.trimIndent()
    )
}
