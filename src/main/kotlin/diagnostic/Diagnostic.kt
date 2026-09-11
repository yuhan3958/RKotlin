package me.rkt.diagnostic

import me.rkt.source.SourceSpan

enum class DiagnosticLevel {
    ERROR,
    WARNING
}

data class Diagnostic(
    val level: DiagnosticLevel,
    val message: String,
    val span: SourceSpan
) {
    override fun toString(): String {
        val (line, column) = span.source.lineColumn(span.start)
        return "${span.source.path}:$line:$column: ${level.name.lowercase()}: $message"
    }
}
