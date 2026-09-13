package me.rkt.backend.c

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class COutputWriter {
    fun write(output: Path, directoryName: String, generated: COutput) {
        val entry = output.toAbsolutePath().normalize()
        val directory = entry.resolveSibling(directoryName).normalize()
        require(directory.parent == entry.parent && directory != entry) { "invalid module output directory" }
        val files = generated.files.map { (relative, content) ->
            val path = Path.of(relative)
            val target = directory.resolve(path).normalize()
            require(!path.isAbsolute && target.startsWith(directory) && target != directory) {
                "generated C path escapes its module directory: $relative"
            }
            target to content
        }
        // Validate every path before writing. Write the entry after its dependencies.
        for ((path, content) in files) {
            Files.createDirectories(path.parent)
            Files.writeString(path, content, StandardCharsets.UTF_8)
        }
        Files.createDirectories(entry.parent)
        Files.writeString(entry, generated.entrySource, StandardCharsets.UTF_8)
    }
}
