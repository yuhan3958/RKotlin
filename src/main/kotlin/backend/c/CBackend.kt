package me.rkt.backend.c

import me.rkt.backend.Backend
import me.rkt.ir.*

class CBackend : Backend<String> {
    override fun generate(module: IrModule): String {
        val w = CWriter()

        w.line("#include <stdint.h>")
        w.line("#include <stdio.h>")
        w.line("#ifdef _WIN32")
        w.line("#include <windows.h>")
        w.line("#endif")
        w.line()

        for (function in module.functions) {
            w.line(prototype(function) + ";")
        }

        w.line()

        for (function in module.functions) {
            emitFunction(w, function)
            w.line()
        }

        val main = module.functions.find {
            it.name == "main" && it.parameters.isEmpty() && it.returnType == IrI32
        }

        if (main != null) {
            w.line("int main(void) {")
            w.indented {
                w.line("#ifdef _WIN32")
                w.line("SetConsoleOutputCP(CP_UTF8);")
                w.line("SetConsoleCP(CP_UTF8);")
                w.line("#endif")
                w.line("return rk_main();")
            }
            w.line("}")
        }

        return w.toString()
    }

    private fun prototype(function: IrFunction): String {
        val params = if (function.parameters.isEmpty()) {
            "void"
        } else {
            function.parameters.joinToString(", ") {
                "${cType(it.type)} ${sanitize(it.name)}"
            }
        }

        return "${cType(function.returnType)} rk_${sanitize(function.name)}($params)"
    }

    private fun emitFunction(w: CWriter, function: IrFunction) {
        w.line(prototype(function) + " {")
        w.indented {
            function.locals.forEach { local ->
                w.line("${cType(local.type)} ${localName(local)};")
            }
            registers(function).forEach { register ->
                w.line("${cType(register.type)} ${value(register)};")
            }
            function.instructions.forEach { emitInstruction(w, it) }
        }
        w.line("}")
    }

    private fun emitInstruction(w: CWriter, instruction: IrInstruction) {
        when (instruction) {
            is IrBinaryInstruction -> {
                w.line(
                    "${value(instruction.result)} = " +
                        "${value(instruction.left)} ${operator(instruction.operator)} ${value(instruction.right)};"
                )
            }

            is IrLabelInstruction -> {
                w.unindentedLine("L${instruction.id}:")
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
                if (instruction.functionName == "printInt32ln") {
                    w.line("printf(\"%d\\n\", ${value(instruction.arguments.single())});")
                    return
                }
                if (instruction.functionName == "printStringln") {
                    w.line("printf(\"%s\\n\", ${value(instruction.arguments.single())});")
                    return
                }
                if (instruction.functionName == "readInt32") {
                    val result = instruction.result
                        ?: error("readInt32 must produce a result")
                    w.line("scanf(\"%d\", &${value(result)});")
                    return
                }

                val call = "rk_${sanitize(instruction.functionName)}(" +
                    instruction.arguments.joinToString(", ") { value(it) } + ")"

                if (instruction.result == null) {
                    w.line("$call;")
                } else {
                    w.line("${value(instruction.result)} = $call;")
                }
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
        is IrStringConstant -> "\"${value.value.replace("\"", "\\\"")}\""
        is IrParameter -> sanitize(value.name)
        is IrRegister -> "r${value.id}"
        is IrLocal -> localName(value)
        IrUnitValue -> error("Unit is not a C value")
    }

    private fun registers(function: IrFunction): List<IrRegister> =
        function.instructions
            .flatMap { instruction ->
                when (instruction) {
                    is IrBinaryInstruction -> listOf(instruction.result)
                    is IrCallInstruction -> instruction.result?.let(::listOf) ?: emptyList()
                    is IrLoadInstruction -> listOf(instruction.result)
                    else -> emptyList()
                }
            }
            .distinctBy(IrRegister::id)

    private fun localName(local: IrLocal): String = "l${local.id}"

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

    private fun cType(type: IrType): String = when (type) {
        IrI32 -> "int32_t"
        IrVoid -> "void"
        IrString -> "const char*"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_]"), "_")
}
