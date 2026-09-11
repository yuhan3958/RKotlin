package me.rkt.diagnostic

import me.rkt.source.SourceSpan

class CompilationFailedException(
    val diagnostics: List<Diagnostic>
) : RuntimeException(diagnostics.joinToString("\n"))

class DiagnosticReporter {
    private val diagnostics = mutableListOf<Diagnostic>()

    fun error(span: SourceSpan, message: String) {
        diagnostics += Diagnostic(DiagnosticLevel.ERROR, message, span)
    }

    fun fail(span: SourceSpan, message: String): Nothing {
        error(span, message)
        throw CompilationFailedException(diagnostics.toList())
    }

    fun hasErrors(): Boolean = diagnostics.any { it.level == DiagnosticLevel.ERROR }

    fun throwIfErrors() {
        if (hasErrors()) {
            throw CompilationFailedException(diagnostics.toList())
        }
    }
}
