package me.rkt.ast

import me.rkt.source.SourceSpan

sealed interface Statement : AstNode

data class BlockStatement(
    val statements: List<Statement>,
    override val span: SourceSpan
) : Statement

data class ReturnStatement(
    val expression: Expression,
    override val span: SourceSpan
) : Statement

data class VariableDeclarationStatement(
    val mutable: Boolean,
    val name: String,
    val type: TypeReference?,
    val initializer: Expression,
    override val span: SourceSpan
) : Statement

data class AssignmentStatement(
    val target: Expression,
    val expression: Expression,
    override val span: SourceSpan
) : Statement {
    constructor(name: String, expression: Expression, span: SourceSpan) :
        this(NameExpression(name, span), expression, span)

    val name: String
        get() = (target as? NameExpression)?.name ?: ""
}

data class ExpressionStatement(
    val expression: Expression,
    override val span: SourceSpan
) : Statement

data class IfStatement(
    val condition: Expression,
    val thenBranch: BlockStatement,
    val elseBranch: BlockStatement?,
    override val span: SourceSpan
) : Statement

data class WhileStatement(
    val condition: Expression,
    val body: BlockStatement,
    override val span: SourceSpan
) : Statement

data class ForStatement(
    val name: String,
    val start: Expression,
    val end: Expression,
    val body: BlockStatement,
    override val span: SourceSpan,
    val iterable: Expression? = null
) : Statement
