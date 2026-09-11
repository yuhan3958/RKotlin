package me.rkt.ast

import me.rkt.source.SourceSpan

sealed interface Expression : AstNode

data class IntegerLiteral(
    val value: Int,
    override val span: SourceSpan
) : Expression

data class StringLiteral(
    val value: String,
    override val span: SourceSpan
) : Expression

data class NameExpression(
    val name: String,
    override val span: SourceSpan
) : Expression

data class NewExpression(
    val type: TypeReference,
    val arguments: List<Expression>,
    override val span: SourceSpan
) : Expression

data class MemberAccessExpression(
    val receiver: Expression,
    val name: String,
    override val span: SourceSpan
) : Expression

data class CallExpression(
    val target: Expression,
    val arguments: List<Expression>,
    override val span: SourceSpan
) : Expression

enum class BinaryOperator {
    ADD,
    SUB,
    MUL,
    DIV,
    EQUALS,
    NOT_EQUALS,
    LESS,
    LESS_EQUALS,
    GREATER,
    GREATER_EQUALS
}

data class BinaryExpression(
    val left: Expression,
    val operator: BinaryOperator,
    val right: Expression,
    override val span: SourceSpan
) : Expression
