package me.rkt.ir

sealed interface IrValue {
    val type: IrType
}

data class IrIntConstant(
    val value: Int,
    override val type: IrType = IrI32
) : IrValue

data class IrStringConstant(
    val value: String,
    override val type: IrType = IrString
) : IrValue

data class IrParameter(
    val index: Int,
    val name: String,
    override val type: IrType
) : IrValue

data class IrRegister(
    val id: Int,
    override val type: IrType
) : IrValue

data class IrLocal(
    val id: Int,
    val name: String,
    override val type: IrType
) : IrValue

data object IrUnitValue : IrValue {
    override val type: IrType = IrVoid
}
