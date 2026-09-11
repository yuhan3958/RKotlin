package me.rkt.ir

sealed interface IrType

data object IrI32 : IrType
data object IrVoid : IrType
data object IrString : IrType

data class IrObjectType(val name: String) : IrType
