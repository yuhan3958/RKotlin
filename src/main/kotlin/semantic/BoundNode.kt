package me.rkt.semantic

import me.rkt.source.SourceSpan

sealed interface BoundNode {
    val span: SourceSpan
}

data class SemanticModule(
    val functions: List<BoundFunction>
)

data class BoundFunction(
    val symbol: FunctionSymbol,
    val body: BoundBlockStatement,
    val parameters: List<ParameterSymbol>
)
