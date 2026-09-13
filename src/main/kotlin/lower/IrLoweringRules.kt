package me.rkt.lower

import me.rkt.ir.*
import me.rkt.semantic.*
import me.rkt.ast.Visibility

/** Representation, coercion, and call naming shared by lowering stages. */
internal object IrLoweringRules {
    fun lowerType(type: RType): IrType = when (type) {
        Int32Type -> IrI32
        BoolType -> IrI32
        UnitType -> IrVoid
        StringType -> IrString
        NullType -> IrPointerType(IrVoid)
        WildcardType -> IrVoid
        is NullableType -> if (type.underlying == Int32Type || type.underlying == BoolType) IrNullableI32 else lowerType(type.underlying)
        is ManagedPointerType -> IrPointerType(
            lowerType(type.pointee),
            when (type.kind) {
                ManagedPointerKind.POINTER -> IrPointerKind.POINTER
                ManagedPointerKind.BUFFER -> IrPointerKind.BUFFER
            }
        )
        is ClassType -> when {
            type.typeParameter -> error("unresolved type parameter '${type.name}' reached IR lowering")
            type.name in setOf("Int", "Int32", "Bool") -> IrI32
            type.name == "String" -> IrString
            type.name in setOf("Unit", "Void") -> IrVoid
            else -> {
                check(type.typeArguments.isEmpty()) { "generic type '${type.displayName}' was not specialized" }
                IrObjectType(type.name)
            }
        }
    }

    fun coerce(value: IrValue, expected: IrType, context: FunctionContext): IrValue {
        if (expected is IrObjectType && value.type is IrObjectType && expected != value.type) {
            return context.newRegister(expected).also { context.instructions += IrCastInstruction(it, value) }
        }
        if (expected != IrNullableI32 || value.type != IrI32) return value
        return context.newRegister(IrNullableI32).also { context.instructions += IrBoxInstruction(it, value) }
    }

    fun virtual(function: FunctionSymbol): Boolean = function.owner?.let {
        lowerType(it.type) is IrObjectType &&
            it.name !in setOf("Pointer", "BufferPointer") &&
            function.name != "constructor" &&
            function.visibility != Visibility.PRIVATE && function.builtinTarget == null
    } == true

    fun callName(function: FunctionSymbol, direct: Boolean = false): String =
        function.builtinTarget ?: if (!direct && virtual(function)) "dispatch_${function.generatedName}" else function.generatedName

}
