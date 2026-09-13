package me.rkt.ir

sealed interface IrInstruction

data class IrBoxInstruction(val result: IrRegister, val value: IrValue) : IrInstruction
data class IrUnboxInstruction(val result: IrRegister, val value: IrValue) : IrInstruction
data class IrCastInstruction(val result: IrRegister, val value: IrValue) : IrInstruction

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
    val returnType: IrType,
    val safe: Boolean = false
) : IrInstruction

data class IrNewObjectInstruction(
    val result: IrRegister,
    val type: IrObjectType
) : IrInstruction

data class IrSelectNonNullInstruction(
    val result: IrRegister,
    val nullable: IrValue,
    val fallback: IrValue
) : IrInstruction

data class IrPointerLoadInstruction(val result: IrRegister, val pointer: IrValue) : IrInstruction
data class IrAddressInstruction(val result: IrRegister, val local: IrLocal) : IrInstruction
data class IrPointerStoreInstruction(val pointer: IrValue, val value: IrValue) : IrInstruction
data class IrFreeInstruction(val pointer: IrValue) : IrInstruction
data class IrManagedPointerInstruction(
    val result: IrRegister,
    val initializer: IrValue,
    val pointeeType: IrType
) : IrInstruction

data class IrFieldLoadInstruction(
    val result: IrRegister,
    val receiver: IrValue,
    val field: String
) : IrInstruction

data class IrSafeFieldLoadInstruction(
    val result: IrRegister,
    val receiver: IrValue,
    val field: String
) : IrInstruction

data class IrFieldStoreInstruction(
    val receiver: IrValue,
    val field: String,
    val value: IrValue
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
