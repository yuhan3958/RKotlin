package me.rkt.ast

import me.rkt.source.SourceSpan

sealed interface Expression : AstNode

data class VoidLiteral(override val span: SourceSpan) : Expression
data class BooleanLiteral(val value: Boolean, override val span: SourceSpan) : Expression

data class IntegerLiteral(
    val value: Int,
    override val span: SourceSpan
) : Expression

data class StringLiteral(
    val value: String,
    override val span: SourceSpan
) : Expression

data class NullLiteral(override val span: SourceSpan) : Expression

data class NameExpression(
    val name: String,
    override val span: SourceSpan
) : Expression

data class NewExpression(
    val type: TypeReference,
    val arguments: List<Expression>,
    override val span: SourceSpan
) : Expression

data class AllocationExpression(
    val elementType: TypeReference,
    val length: Expression,
    override val span: SourceSpan
) : Expression

data class MemberAccessExpression(
    val receiver: Expression,
    val name: String,
    override val span: SourceSpan,
    val safe: Boolean = false
) : Expression

data class ElvisExpression(
    val nullable: Expression,
    val fallback: Expression,
    override val span: SourceSpan
) : Expression


data class CallExpression(
    val target: Expression,
    val arguments: List<Expression>,
    override val span: SourceSpan
) : Expression

data class IndexExpression(
    val receiver: Expression,
    val index: Expression,
    override val span: SourceSpan
) : Expression

data class UnaryExpression(
    val operator: UnaryOperator,
    val operand: Expression,
    override val span: SourceSpan
) : Expression

enum class UnaryOperator {
    NOT
}

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
    GREATER_EQUALS,
    AND,
    OR,
    XOR
}

data class BinaryExpression(
    val left: Expression,
    val operator: BinaryOperator,
    val right: Expression,
    override val span: SourceSpan
) : Expression
