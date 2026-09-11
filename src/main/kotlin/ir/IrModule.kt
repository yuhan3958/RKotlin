package me.rkt.ir

data class IrModule(
    val functions: List<IrFunction>,
    val classes: List<IrClass> = emptyList()
)

data class IrClass(
    val name: String,
    val fields: List<IrField>,
    val objectLike: Boolean = false
)

data class IrField(
    val name: String,
    val type: IrType
)
