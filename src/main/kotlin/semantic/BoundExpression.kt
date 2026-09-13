package me.rkt.semantic

import me.rkt.source.SourceSpan

sealed interface BoundExpression : BoundNode {
    val type: RType
}

data class BoundVoidLiteral(
    override val span: SourceSpan,
    override val type: RType = UnitType
) : BoundExpression

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

data class BoundNullLiteral(
    override val span: SourceSpan,
    override val type: RType = NullType
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
    val constructor: FunctionSymbol?,
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
    override val type: RType = field.type,
    val safe: Boolean = false,
    val getter: FunctionSymbol? = null,
    val setter: FunctionSymbol? = null,
    val direct: Boolean = false
) : BoundExpression

data class BoundElvisExpression(
    val nullable: BoundExpression,
    val fallback: BoundExpression,
    override val type: RType,
    override val span: SourceSpan
) : BoundExpression

data class BoundDereferenceExpression(
    val pointer: BoundExpression,
    override val span: SourceSpan,
    override val type: RType
) : BoundExpression

data class BoundAddressExpression(
    val value: BoundNameExpression,
    override val span: SourceSpan,
    override val type: RType = ManagedPointerType(value.type, value.symbol.mutable)
) : BoundExpression

data class BoundPointerWriteExpression(
    val pointer: BoundExpression,
    val value: BoundExpression,
    override val span: SourceSpan,
    override val type: RType = UnitType
) : BoundExpression

data class BoundPointerAddExpression(
    val pointer: BoundExpression,
    val offset: BoundExpression,
    override val type: ManagedPointerType,
    override val span: SourceSpan,
    val direction: Int = 1
) : BoundExpression

data class BoundBufferGetExpression(
    val pointer: BoundExpression,
    val index: BoundExpression,
    override val type: RType,
    override val span: SourceSpan
) : BoundExpression

data class BoundBufferSetExpression(
    val pointer: BoundExpression,
    val index: BoundExpression,
    val value: BoundExpression,
    override val span: SourceSpan,
    override val type: RType = UnitType
) : BoundExpression

data class BoundBufferLengthExpression(
    val pointer: BoundExpression,
    override val span: SourceSpan,
    override val type: RType = Int32Type
) : BoundExpression

data class BoundBufferAllocationExpression(
    val length: BoundExpression,
    override val type: ManagedPointerType,
    override val span: SourceSpan
) : BoundExpression

data class BoundFreeExpression(
    val pointer: BoundExpression,
    override val span: SourceSpan,
    override val type: RType = UnitType
) : BoundExpression

data class BoundTypeIsFreedExpression(
    val value: BoundExpression,
    override val span: SourceSpan,
    override val type: RType = BoolType
) : BoundExpression

data class BoundManagedPointerExpression(
    val initializer: BoundExpression,
    override val type: ManagedPointerType,
    override val span: SourceSpan
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
    val receiver: BoundExpression? = null,
    val safe: Boolean = false,
    val direct: Boolean = false
) : BoundExpression
