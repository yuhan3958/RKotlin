package me.rkt.semantic

import me.rkt.source.SourceSpan

sealed interface BoundStatement : BoundNode

data class BoundBlockStatement(
    val statements: List<BoundStatement>,
    override val span: SourceSpan
) : BoundStatement

data class BoundReturnStatement(
    val expression: BoundExpression,
    override val span: SourceSpan
) : BoundStatement

data class BoundVariableDeclarationStatement(
    val symbol: VariableSymbol,
    val initializer: BoundExpression,
    override val span: SourceSpan
) : BoundStatement

data class BoundAssignmentStatement(
    val target: BoundExpression,
    val expression: BoundExpression,
    override val span: SourceSpan
) : BoundStatement {
    constructor(symbol: ValueSymbol, expression: BoundExpression, span: SourceSpan) :
        this(BoundNameExpression(symbol, span), expression, span)

    val symbol: ValueSymbol?
        get() = (target as? BoundNameExpression)?.symbol
}

data class BoundExpressionStatement(
    val expression: BoundExpression,
    override val span: SourceSpan
) : BoundStatement

data class BoundIfStatement(
    val condition: BoundExpression,
    val thenBranch: BoundBlockStatement,
    val elseBranch: BoundBlockStatement?,
    override val span: SourceSpan
) : BoundStatement

data class BoundWhileStatement(
    val condition: BoundExpression,
    val body: BoundBlockStatement,
    override val span: SourceSpan
) : BoundStatement

data class BoundForStatement(
    val symbol: VariableSymbol,
    val start: BoundExpression,
    val end: BoundExpression,
    val body: BoundBlockStatement,
    override val span: SourceSpan,
    val iterable: BoundExpression? = null,
    val indexSymbol: VariableSymbol? = null,
    val elementAccess: BoundExpression? = null
) : BoundStatement
