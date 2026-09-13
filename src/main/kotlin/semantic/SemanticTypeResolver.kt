package me.rkt.semantic

import me.rkt.ast.TypeReference
import me.rkt.ast.Visibility

class SemanticTypeResolver(
    private val context: SemanticContext
) {
    private val diagnostics get() = context.diagnostics
    private val classes get() = context.classes
    private val nativeTypes get() = context.nativeTypes
    private val typeParameters get() = context.typeParameters

    fun resolveClassType(type: TypeReference): ClassType =
        classes[type.name]?.let { symbol ->
            if (symbol.visibility == Visibility.PRIVATE &&
                symbol.declarationSpan?.source != type.span.source
            ) {
                diagnostics.fail(type.span, "type '${type.name}' is private")
            }
            if (type.arguments.isEmpty()) {
                if (symbol.typeParameters.isNotEmpty()) {
                    diagnostics.fail(type.span, "type '${type.name}' requires ${symbol.typeParameters.size} type arguments")
                }
                symbol.type
            } else {
                if (type.arguments.size != symbol.typeParameters.size) {
                    diagnostics.fail(type.span, "type '${type.name}' does not accept these type arguments")
                }
                ClassType(
                    symbol.name,
                    symbol.type.objectLike,
                    type.arguments.map { argument ->
                        if (argument.name == "*" && argument.arguments.isEmpty() && !argument.nullable) {
                            WildcardType
                        } else {
                            resolveType(argument)
                        }
                    }
                )
            }
        } ?: diagnostics.fail(type.span, "unknown type '${type.name}'")

    fun resolveType(type: TypeReference): RType = when (type.name) {
        "Pointer", "BufferPointer" -> {
            if (type.name !in classes) diagnostics.fail(type.span, "${type.name} is not imported")
            if (type.arguments.size != 1) {
                diagnostics.fail(type.span, "${type.name} requires exactly one type argument")
            }
            val argument = type.arguments.single()
            val pointee = if (argument.name == "*" && argument.arguments.isEmpty() && !argument.nullable) {
                WildcardType
            } else {
                resolveType(argument)
            }
            if (pointee == UnitType) diagnostics.fail(type.span, "${type.name} requires a value type")
            ManagedPointerType(
                pointee,
                kind = if (type.name == "BufferPointer") {
                    ManagedPointerKind.BUFFER
                } else {
                    ManagedPointerKind.POINTER
                }
            )
        }
        else -> {
            if (type.arguments.isNotEmpty() && (type.name !in classes || type.name in nativeTypes)) {
                diagnostics.fail(type.span, "type '${type.name}' does not accept type arguments")
            }
            nativeTypes[type.name] ?: if (type.name in typeParameters) {
                ClassType(type.name, typeParameter = true)
            } else {
                resolveClassType(type)
            }
        }
    }.let { base ->
        var result = base
        if (type.nullable) {
            if (!isNullableCapable(result)) {
                diagnostics.fail(type.span, "only object, String, and pointer types may be nullable")
            }
            NullableType(result)
        } else {
            result
        }
    }

    fun unwrapNullable(type: RType): RType =
        if (type is NullableType) type.underlying else type

    fun isNullableCapable(type: RType): Boolean =
        type == Int32Type || type == BoolType || type == StringType ||
            type is ClassType || type is ManagedPointerType || type is NullableType
}
