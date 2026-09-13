package me.rkt.backend.c

import me.rkt.ir.*

/** Emits one function's instructions; addressed-local state is isolated per emitter. */
internal class CFunctionEmitter(
    private val classes: Map<String, IrClass>,
    private val typeIds: Map<String, Int>,
    private val representation: CRepresentation
) {
    private var addressedLocals: Set<Int> = emptySet()

    fun prototype(function: IrFunction): String {
        val params = if (function.parameters.isEmpty()) {
            "void"
        } else {
            function.parameters.joinToString(", ") {
                "${representation.cType(it.type)} ${value(it)}"
            }
        }

        return "${representation.cType(function.returnType)} rk_${representation.sanitize(function.name)}($params)"
    }

    fun emitFunction(w: CWriter, function: IrFunction) {
        addressedLocals = function.instructions.filterIsInstance<IrAddressInstruction>()
            .map { it.local.id }.toSet()
        w.line(prototype(function) + " {")
        w.indented {
            function.locals.forEach { local ->
                if (local.id in addressedLocals) {
                    w.line("${representation.cType(local.type)} *l${local.id} = rk_allocate(sizeof(${representation.cType(local.type)}));")
                } else {
                    w.line("${representation.cType(local.type)} ${localName(local)};")
                }
            }
            registers(function).forEach { register ->
                w.line("${representation.cType(register.type)} ${value(register)};")
            }
            function.instructions.forEach { emitInstruction(w, it) }
        }
        w.line("}")
    }

    private fun emitInstruction(w: CWriter, instruction: IrInstruction) {
        when (instruction) {
            is IrCastInstruction -> {
                w.line("${value(instruction.result)} = (${representation.cType(instruction.result.type)})${value(instruction.value)};")
            }
            is IrBoxInstruction -> {
                w.line("${value(instruction.result)} = rk_box_i32(${value(instruction.value)});")
            }

            is IrUnboxInstruction -> {
                w.line("${value(instruction.result)} = *${value(instruction.value)};")
            }

            is IrBinaryInstruction -> {
                w.line(
                    "${value(instruction.result)} = " +
                        "${value(instruction.left)} ${operator(instruction.operator)} ${value(instruction.right)};"
                )
            }

            is IrLabelInstruction -> {
                w.unindentedLine("L${instruction.id}:;")
            }

            is IrJumpInstruction -> {
                w.line("goto L${instruction.target};")
            }

            is IrBranchInstruction -> {
                w.line(
                    "if (${value(instruction.condition)} != 0) " +
                        "goto L${instruction.thenTarget};"
                )
                w.line("goto L${instruction.elseTarget};")
            }

            is IrCallInstruction -> {
                CIntrinsics.functions[instruction.functionName]?.let { name ->
                    val call = "$name(${instruction.arguments.joinToString(", ") { value(it) }})"
                    w.line(instruction.result?.let { "${value(it)} = $call;" } ?: "$call;")
                    return
                }
                val call = "rk_${representation.sanitize(instruction.functionName)}(" +
                    instruction.arguments.joinToString(", ") { value(it) } + ")"

                if (instruction.safe) {
                    val receiver = value(instruction.arguments.first())
                    if (instruction.result == null) {
                        w.line("if ($receiver != NULL) $call;")
                    } else {
                        val present = if (instruction.returnType == IrI32) "rk_box_i32($call)" else call
                        w.line("${value(instruction.result)} = $receiver != NULL ? $present : NULL;")
                    }
                } else if (instruction.result == null) {
                    w.line("$call;")
                } else {
                    w.line("${value(instruction.result)} = $call;")
                }
            }

            is IrNewObjectInstruction -> {
                w.line(
                    "${value(instruction.result)} = " +
                        "rk_allocate_typed(sizeof(struct rk_${representation.sanitize(instruction.type.name)}), " +
                        "\"${instruction.type.name}\");"
                )
                val type = classes.getValue(instruction.type.name)
                w.line("${representation.typeTag(value(instruction.result), type)} = ${typeIds.getValue(type.name)};")
            }

            is IrArrayInitializeInstruction -> {
                w.line("${value(instruction.receiver)}->f_count = ${value(instruction.length)};")
                w.line(
                    "${value(instruction.receiver)}->f_data = rk_buffer_create(" +
                        "${value(instruction.length)}, sizeof(${representation.cType(instruction.elementType)}), " +
                        "\"${representation.pointerTypeName(instruction.elementType)}\");"
                )
            }

            is IrFieldLoadInstruction -> {
                w.line(
                    "${value(instruction.result)} = " +
                        "${value(instruction.receiver)}->${representation.fieldName(instruction.field)};"
                )
            }

            is IrSafeFieldLoadInstruction -> {
                val field = "${value(instruction.receiver)}->${representation.fieldName(instruction.field)}"
                val present = if (instruction.result.type == IrNullableI32) "rk_box_i32($field)" else field
                w.line(
                    "${value(instruction.result)} = ${value(instruction.receiver)} != NULL ? " +
                        "$present : NULL;"
                )
            }

            is IrSelectNonNullInstruction -> {
                w.line(
                    "${value(instruction.result)} = ${value(instruction.nullable)} != NULL ? " +
                        "${value(instruction.nullable)} : ${value(instruction.fallback)};"
                )
            }

            is IrAddressInstruction -> {
                w.line(
                    "${value(instruction.result)} = rk_pointer_create(&${localName(instruction.local)}, 0, " +
                        "\"${representation.pointerTypeName(instruction.local.type)}\", " +
                        "${if (instruction.local.type is IrObjectType) 1 else 0}, 0);"
                )
            }

            is IrAddressValueInstruction -> {
                w.line(
                    "${value(instruction.result)} = rk_pointer_create(&${value(instruction.value)}, 0, " +
                        "\"${representation.pointerTypeName(instruction.value.type)}\", " +
                        "${if (instruction.value.type is IrObjectType) 1 else 0}, 0);"
                )
            }

            is IrPointerLoadInstruction -> {
                w.line("${value(instruction.result)} = *(${representation.cType(instruction.result.type)}*)rk_pointer_read(${value(instruction.pointer)});")
            }

            is IrPointerAddInstruction -> {
                w.line(
                    "${value(instruction.result)} = rk_pointer_add(" +
                        "${value(instruction.pointer)}, " +
                        "${if (instruction.direction < 0) "-(${value(instruction.offset)})" else value(instruction.offset)}, " +
                        "sizeof(${representation.cType(instruction.pointeeType)}), " +
                        "\"${representation.pointerTypeName(instruction.pointeeType)}\", " +
                        "${if (instruction.pointeeType is IrObjectType) 1 else 0});"
                )
            }

            is IrBufferGetInstruction -> {
                w.line(
                    "${value(instruction.result)} = *(${representation.cType(instruction.pointeeType)}*)" +
                        "rk_buffer_element(${value(instruction.pointer)}, ${value(instruction.index)});"
                )
            }

            is IrBufferSetInstruction -> {
                w.line(
                    "*(${representation.cType(instruction.pointeeType)}*)" +
                        "rk_buffer_element(${value(instruction.pointer)}, ${value(instruction.index)}) = " +
                        "${value(instruction.value)};"
                )
            }

            is IrBufferLengthInstruction -> {
                w.line(
                    "${value(instruction.result)} = rk_buffer_length(${value(instruction.pointer)});"
                )
            }

            is IrBufferAllocationInstruction -> {
                w.line(
                    "${value(instruction.result)} = rk_buffer_create(" +
                        "${value(instruction.length)}, sizeof(${representation.cType(instruction.pointeeType)}), " +
                        "\"${representation.pointerTypeName(instruction.pointeeType)}\");"
                )
            }

            is IrPointerStoreInstruction -> {
                val pointee = (instruction.pointer.type as IrPointerType).pointee
                w.line("*(${representation.cType(pointee)}*)rk_pointer_read(${value(instruction.pointer)}) = ${value(instruction.value)};")
            }

            is IrFreeInstruction -> {
                w.line("rk_pointer_free(${value(instruction.pointer)});")
            }

            is IrTypeIsFreedInstruction -> {
                when (instruction.value.type) {
                    IrI32, IrNullableI32 -> w.line("${value(instruction.result)} = 0;")
                    is IrPointerType -> w.line(
                        "${value(instruction.result)} = rk_pointer_is_freed(${value(instruction.value)});"
                    )
                    IrString, is IrObjectType -> w.line(
                        "${value(instruction.result)} = rk_type_is_freed((void*)${value(instruction.value)});"
                    )
                    IrVoid -> w.line("${value(instruction.result)} = 0;")
                }
            }

            is IrManagedPointerInstruction -> {
                w.line(
                    "${value(instruction.result)} = rk_pointer_create(" +
                        "rk_allocate_typed(sizeof(${representation.cType(instruction.pointeeType)}), " +
                        "\"${representation.pointerTypeName(instruction.pointeeType)}\"), 1, " +
                        "${if (instruction.pointeeType is IrObjectType) 1 else 0}, " +
                        "${if (instruction.result.type is IrPointerType && instruction.result.type.kind == IrPointerKind.BUFFER) 1 else 0});"
                )
                w.line("*(${representation.cType(instruction.pointeeType)}*)rk_pointer_read(${value(instruction.result)}) = ${value(instruction.initializer)};")
            }

            is IrFieldStoreInstruction -> {
                w.line(
                    "${value(instruction.receiver)}->${representation.fieldName(instruction.field)} = " +
                        "${value(instruction.value)};"
                )
            }

            is IrLoadInstruction -> {
                w.line(
                    "${value(instruction.result)} = " +
                        "${localName(instruction.local)};"
                )
            }

            is IrStoreInstruction -> {
                w.line("${localName(instruction.local)} = ${value(instruction.value)};")
            }

            is IrReturnInstruction -> {
                if (instruction.value == null) {
                    w.line("return;")
                } else {
                    w.line("return ${value(instruction.value)};")
                }
            }
        }
    }

    private fun value(value: IrValue): String = when (value) {
        is IrIntConstant -> value.value.toString()
        is IrStringConstant -> "\"${representation.escapeString(value.value)}\""
        is IrNullConstant -> "NULL"
        is IrParameter -> "p${value.index}_${representation.sanitize(value.name)}"
        is IrRegister -> "r${value.id}"
        is IrLocal -> localName(value)
        is IrObjectReference -> "&rk_object_${representation.sanitize(value.name)}"
        IrUnitValue -> error("Unit is not a C value")
    }

    private fun registers(function: IrFunction): List<IrRegister> =
        function.instructions
            .flatMap { instruction ->
                when (instruction) {
                    is IrCastInstruction -> listOf(instruction.result)
                    is IrBoxInstruction -> listOf(instruction.result)
                    is IrUnboxInstruction -> listOf(instruction.result)
                    is IrBinaryInstruction -> listOf(instruction.result)
                    is IrCallInstruction -> instruction.result?.let(::listOf) ?: emptyList()
                    is IrLoadInstruction -> listOf(instruction.result)
                    is IrNewObjectInstruction -> listOf(instruction.result)
                    is IrFieldLoadInstruction -> listOf(instruction.result)
                    is IrSafeFieldLoadInstruction -> listOf(instruction.result)
                    is IrSelectNonNullInstruction -> listOf(instruction.result)
                    is IrPointerLoadInstruction -> listOf(instruction.result)
                    is IrPointerAddInstruction -> listOf(instruction.result)
                    is IrBufferGetInstruction -> listOf(instruction.result)
                    is IrBufferLengthInstruction -> listOf(instruction.result)
                    is IrBufferAllocationInstruction -> listOf(instruction.result)
                    is IrTypeIsFreedInstruction -> listOf(instruction.result)
                    is IrAddressInstruction -> listOf(instruction.result)
                    is IrAddressValueInstruction -> listOf(instruction.result)
                    is IrManagedPointerInstruction -> listOf(instruction.result)
                    else -> emptyList()
                }
            }
            .distinctBy(IrRegister::id)

    private fun localName(local: IrLocal): String =
        if (local.id in addressedLocals) "(*l${local.id})" else "l${local.id}"

    private fun operator(operator: IrBinaryOperator): String = when (operator) {
        IrBinaryOperator.ADD_I32 -> "+"
        IrBinaryOperator.SUB_I32 -> "-"
        IrBinaryOperator.MUL_I32 -> "*"
        IrBinaryOperator.DIV_I32 -> "/"
        IrBinaryOperator.EQ_I32 -> "=="
        IrBinaryOperator.NE_I32 -> "!="
        IrBinaryOperator.LT_I32 -> "<"
        IrBinaryOperator.LE_I32 -> "<="
        IrBinaryOperator.GT_I32 -> ">"
        IrBinaryOperator.GE_I32 -> ">="
    }

}
