package me.rkt

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
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
        val source = SourceFile.load(input)

        val tokens = Lexer(source, diagnostics).lex()
        val ast = Parser(tokens, diagnostics).parseModule()
        val semantic = SemanticAnalyzer(diagnostics).analyze(ast)
        val ir = IrLowering().lower(semantic)
        val c = CBackend().generate(ir)

        diagnostics.throwIfErrors()
        Files.writeString(output, c, StandardCharsets.UTF_8)
    }
}
