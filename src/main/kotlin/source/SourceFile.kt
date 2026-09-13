package me.rkt.source

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

data class SourceFile(
    val path: Path,
    val content: String
) {
    override fun toString(): String = path.toString()

    fun lineColumn(offset: Int): Pair<Int, Int> {
        var line = 1
        var column = 1

        for (i in 0 until offset.coerceAtMost(content.length)) {
            if (content[i] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }

        return line to column
    }

    companion object {
        fun load(path: Path): SourceFile =
            SourceFile(path, Files.readString(path, StandardCharsets.UTF_8))
    }
}
