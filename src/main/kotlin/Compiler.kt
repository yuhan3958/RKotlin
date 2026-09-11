package me.rkt

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.io.IOException
import me.rkt.ast.AstModule
import me.rkt.backend.c.CBackend
import me.rkt.diagnostic.DiagnosticReporter
import me.rkt.lexer.Lexer
import me.rkt.lower.IrLowering
import me.rkt.parser.Parser
import me.rkt.semantic.SemanticAnalyzer
import me.rkt.source.SourceFile

class Compiler {
    fun compileToC(input: Path, output: Path) {
        val diagnostics = DiagnosticReporter()
        val modules = loadModules(input, diagnostics)
        val root = modules.first()
        val ast = AstModule(
            modules.flatMap { it.declarations },
            root.span,
            modules.flatMap { it.imports }
        )
        val semantic = SemanticAnalyzer(diagnostics).analyze(ast)
        val ir = IrLowering().lower(semantic)
        val c = CBackend().generate(ir)

        diagnostics.throwIfErrors()
        Files.writeString(output, c, StandardCharsets.UTF_8)
    }

    private fun loadModules(
        root: Path,
        diagnostics: DiagnosticReporter
    ): List<AstModule> {
        val visited = linkedSetOf<Path>()
        val modules = mutableListOf<AstModule>()

        fun load(path: Path) {
            val normalized = path.toAbsolutePath().normalize()
            if (!visited.add(normalized)) return
            if (!Files.exists(normalized)) {
                return
            }

            val source = SourceFile.load(normalized)
            val ast = Parser(Lexer(source, diagnostics).lex(), diagnostics).parseModule()
            modules += ast

            for (importDeclaration in ast.imports) {
                val imported = normalized.parent.resolve(
                    importDeclaration.path.joinToString(java.io.File.separator) + ".rk"
                )
                if (!Files.exists(imported)) {
                    diagnostics.error(
                        importDeclaration.span,
                        "cannot find imported module '${importDeclaration.path.joinToString(".")}'"
                    )
                } else {
                    load(imported)
                }
            }
        }

        load(root)
        if (modules.isEmpty()) {
            throw IOException("cannot find source module '$root'")
        }
        return modules
    }
}
