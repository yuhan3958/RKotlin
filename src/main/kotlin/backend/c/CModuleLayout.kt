package me.rkt.backend.c

import me.rkt.ir.IrModule
import me.rkt.ir.IrSourceModule
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

internal class CModuleLayout(val module: IrModule) {
    val sources = module.sources.associateBy { it.sourcePath }
    val root = sources.getValue(module.rootSource)

    init {
        require(sources.size == module.sources.size) { "duplicate source module" }
        require(module.sources.map { it.outputName.lowercase() }.distinct().size == module.sources.size) {
            "C module output paths collide"
        }
        require(module.functions.all { it.sourcePath in sources } && module.classes.all { it.sourcePath in sources }) {
            "IR declaration has no source module"
        }
        require(module.sources.all { source -> source.imports.all { it in sources } }) { "unresolved C module dependency" }
    }

    fun header(source: IrSourceModule): String = "${source.outputName}.h"
    fun implementation(source: IrSourceModule): String = "${source.outputName}.c"
    fun typeHeader(name: String): String = "types/$name.h"

    fun include(from: String, target: String): String {
        val directory = Path.of(from).parent ?: Path.of("")
        val relative = directory.relativize(Path.of(target)).joinToString("/")
        return "#include \"$relative\""
    }

    fun guarded(path: String, body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val guard = "RK_GENERATED_${digest.uppercase()}"
        return "#ifndef $guard\n#define $guard\n\n$body\n#endif\n"
    }
}
