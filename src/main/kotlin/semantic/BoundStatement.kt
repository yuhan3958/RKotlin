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
    val symbol: ValueSymbol,
    val expression: BoundExpression,
    override val span: SourceSpan
) : BoundStatement

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
    override val span: SourceSpan
) : BoundStatement
