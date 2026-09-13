package me.rkt.backend.c

import me.rkt.ir.*

/** Shared C type, identifier, string, and object-layout representation. */
internal class CRepresentation(private val classes: Map<String, IrClass>) {
    fun ancestry(type: IrClass): List<IrClass> =
        generateSequence(type) { it.baseName?.let(classes::getValue) }.toList()

    fun typeTag(receiver: String, type: IrClass): String =
        "$receiver->" + "rk_base.".repeat(ancestry(type).size - 1) + "rk_type"

    fun cType(type: IrType): String = when (type) {
        IrI32 -> "int32_t"
        IrNullableI32 -> "int32_t*"
        IrVoid -> "void"
        IrString -> "const char*"
        is IrPointerType -> "rk_pointer*"
        is IrObjectType -> if (classes[type.name]?.isInterface == true) {
            "void*"
        } else {
            "struct rk_${sanitize(type.name)}*"
        }
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_]"), "_")

    fun fieldName(name: String): String = "f_${sanitize(name)}"

    fun pointerTypeName(type: IrType): String = when (type) {
        is IrObjectType -> type.name
        is IrPointerType -> "${type.kind.displayName}<${pointerTypeName(type.pointee)}>"
        IrI32 -> "Int32"
        IrNullableI32 -> "Int32"
        IrString -> "String"
        IrVoid -> "Void"
    }

    fun escapeString(value: String): String = buildString {
        for (character in value) append(when (character) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            '?' -> "\\?"
            else -> character.toString()
        })
    }
}
