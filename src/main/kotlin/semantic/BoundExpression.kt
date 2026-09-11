package me.rkt.semantic

import me.rkt.source.SourceSpan

sealed interface BoundExpression : BoundNode {
    val type: RType
}

data class BoundIntLiteral(
    val value: Int,
    override val span: SourceSpan,
    override val type: RType = Int32Type
) : BoundExpression

data class BoundStringLiteral(
    val value: String,
    override val span: SourceSpan,
    override val type: RType = StringType
) : BoundExpression

data class BoundNameExpression(
    val symbol: ValueSymbol,
    override val span: SourceSpan,
    override val type: RType = symbol.type
) : BoundExpression

data class BoundThisExpression(
    val symbol: ParameterSymbol,
    override val span: SourceSpan,
    override val type: RType = symbol.type
) : BoundExpression

data class BoundNewExpression(
    val classType: ClassType,
    val arguments: List<BoundExpression>,
    override val span: SourceSpan,
    override val type: RType = classType
) : BoundExpression

data class BoundObjectReference(
    val classType: ClassType,
    override val span: SourceSpan,
    override val type: RType = classType
) : BoundExpression

data class BoundMemberExpression(
    val receiver: BoundExpression,
    val field: FieldSymbol,
    override val span: SourceSpan,
    override val type: RType = field.type
) : BoundExpression

enum class BoundBinaryOperator {
    ADD_I32,
    SUB_I32,
    MUL_I32,
    DIV_I32,
    EQ_I32,
    NE_I32,
    LT_I32,
    LE_I32,
    GT_I32,
    GE_I32
}

data class BoundBinaryExpression(
    val left: BoundExpression,
    val operator: BoundBinaryOperator,
    val right: BoundExpression,
    override val span: SourceSpan,
    override val type: RType = Int32Type
) : BoundExpression

data class BoundCallExpression(
    val function: FunctionSymbol,
    val arguments: List<BoundExpression>,
    override val span: SourceSpan,
    override val type: RType = function.returnType,
    val receiver: BoundExpression? = null
) : BoundExpression
