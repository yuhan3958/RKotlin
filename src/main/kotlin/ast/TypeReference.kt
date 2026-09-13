package me.rkt.ast

import me.rkt.source.SourceSpan

data class TypeReference(
    val name: String,
    override val span: SourceSpan,
    val nullable: Boolean = false,
    val arguments: List<TypeReference> = emptyList()
) : AstNode
