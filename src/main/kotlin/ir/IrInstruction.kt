package me.rkt.ir

sealed interface IrInstruction

enum class IrBinaryOperator {
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

data class IrBinaryInstruction(
    val result: IrRegister,
    val operator: IrBinaryOperator,
    val left: IrValue,
    val right: IrValue
) : IrInstruction

data class IrCallInstruction(
    val result: IrRegister?,
    val functionName: String,
    val arguments: List<IrValue>,
    val returnType: IrType
) : IrInstruction

data class IrLoadInstruction(
    val result: IrRegister,
    val local: IrLocal
) : IrInstruction

data class IrStoreInstruction(
    val local: IrLocal,
    val value: IrValue
) : IrInstruction

data class IrReturnInstruction(
    val value: IrValue?
) : IrInstruction

data class IrLabelInstruction(
    val id: Int
) : IrInstruction

data class IrJumpInstruction(
    val target: Int
) : IrInstruction

data class IrBranchInstruction(
    val condition: IrValue,
    val thenTarget: Int,
    val elseTarget: Int
) : IrInstruction
