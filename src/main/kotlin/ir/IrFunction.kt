package me.rkt.ir

data class IrFunction(
    val name: String,
    val parameters: List<IrParameter>,
    val locals: List<IrLocal>,
    val returnType: IrType,
    val instructions: List<IrInstruction>,
    val sourcePath: String = ""
)
