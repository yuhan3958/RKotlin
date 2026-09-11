package me.rkt.ast

import me.rkt.source.SourceSpan

sealed interface AstNode {
    val span: SourceSpan
}

data class AstModule(
    val declarations: List<Declaration>,
    override val span: SourceSpan
) : AstNode
