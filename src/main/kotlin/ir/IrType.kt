package me.rkt.ir

sealed interface IrType

data object IrI32 : IrType
data object IrNullableI32 : IrType
data object IrVoid : IrType
data object IrString : IrType
enum class IrPointerKind(val displayName: String) {
    POINTER("Pointer"),
    BUFFER("BufferPointer")
}
data class IrPointerType(
    val pointee: IrType,
    val kind: IrPointerKind = IrPointerKind.POINTER
) : IrType

data class IrObjectType(val name: String) : IrType
