package me.rkt

import java.nio.file.Path
import me.rkt.ast.AstModule
import me.rkt.backend.c.CBackend
import me.rkt.backend.c.COutputWriter
import me.rkt.backend.c.CSourcePaths
import me.rkt.diagnostic.DiagnosticReporter
import me.rkt.ir.IrSourceModule
import me.rkt.lower.IrLowering
import me.rkt.semantic.SemanticAnalyzer

class Compiler {
    private val standardRoot = Path.of("src", "main", "rk").toAbsolutePath().normalize()

    fun compileToC(input: Path, output: Path) {
        val diagnostics = DiagnosticReporter()
        val loaded = ModuleLoader(standardRoot).load(input, diagnostics)
        val modules = loaded.modules
        val root = modules.first()
        val ast = AstModule(
            modules.flatMap { it.declarations },
            root.span,
            modules.flatMap { it.imports }
        )
        val sourcePaths = modules.associate { module ->
            val path = module.span.source.path.toAbsolutePath().normalize()
            path to path.toString()
        }
        val sourceRoot = input.toAbsolutePath().normalize().parent
        val sources = sourcePaths.map { (path, id) ->
            IrSourceModule(
                id,
                CSourcePaths.moduleName(path, sourceRoot, standardRoot),
                loaded.imports.getValue(path).map { sourcePaths.getValue(it) }
            )
        }
        val semantic = SemanticAnalyzer(diagnostics).analyze(ast)
        val ir = IrLowering().lower(semantic).copy(
            sources = sources,
            rootSource = sourcePaths.getValue(root.span.source.path.toAbsolutePath().normalize())
        )
        val directoryName = CSourcePaths.directoryName(output)
        val generated = CBackend(directoryName).generate(ir)
        diagnostics.throwIfErrors()
        COutputWriter().write(output, directoryName, generated)
    }
}
