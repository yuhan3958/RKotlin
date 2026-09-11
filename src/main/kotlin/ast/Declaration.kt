package me.rkt.ast

import me.rkt.source.SourceSpan

sealed interface Declaration : AstNode

data class Parameter(
    val name: String,
    val type: TypeReference,
    override val span: SourceSpan
) : AstNode

data class FunctionDeclaration(
    val name: String,
    val parameters: List<Parameter>,
    val returnType: TypeReference,
    val body: BlockStatement,
    override val span: SourceSpan
) : Declaration
