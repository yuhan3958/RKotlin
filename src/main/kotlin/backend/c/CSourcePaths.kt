package me.rkt.backend.c

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

object CSourcePaths {
    fun moduleName(source: Path, sourceRoot: Path, standardRoot: Path): String {
        val relative = when {
            source.startsWith(standardRoot) -> standardRoot.relativize(source)
            source.startsWith(sourceRoot) -> Path.of("app").resolve(sourceRoot.relativize(source))
            else -> Path.of("external", digest(source.toString()), source.fileName.toString())
        }
        val name = relative.joinToString("/") { safeSegment(it.toString()) }
        return name.substringBeforeLast('.')
    }

    fun directoryName(output: Path): String =
        safeSegment(output.fileName.toString().substringBeforeLast('.')) + ".modules"

    private fun safeSegment(value: String): String = buildString {
        for (character in value) {
            if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "_.-") {
                append(character)
            } else {
                append("_u${character.code.toString(16)}_")
            }
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
