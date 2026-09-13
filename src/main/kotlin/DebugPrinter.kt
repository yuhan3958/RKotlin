package me.rkt

import me.rkt.ir.IrModule
import me.rkt.semantic.SemanticModule
import me.rkt.semantic.generatedName

internal object DebugPrinter {
    fun render(semantic: SemanticModule, ir: IrModule): String = buildString {
        appendSemantic(this, semantic)
        appendIr(this, ir)
    }

    private fun appendSemantic(out: StringBuilder, module: SemanticModule) {
        out.appendLine("=== semantic ===")
        module.classes.forEach { clazz ->
            out.appendLine("class ${clazz.name}" + (clazz.baseClass?.let { " : ${it.name}" } ?: ""))
            clazz.fields.values
                .filter { it.ownerName == clazz.name }
                .forEach { field ->
                    out.appendLine("  field ${field.name}: ${field.type.displayName}")
                }
            clazz.methods.values
                .filter { it.owner === clazz }
                .forEach { method ->
                    out.appendLine(
                        "  fun ${method.name}(" +
                            method.parameters.joinToString(", ") { "${it.name}: ${it.type.displayName}" } +
                            "): ${method.returnType.displayName}" +
                            if (method.overriding) " [override]" else ""
                    )
                }
        }
        module.functions.forEach { function ->
            out.appendLine(
                "fun ${function.symbol.generatedName}(" +
                    function.parameters.joinToString(", ") { "${it.name}: ${it.type.displayName}" } +
                    "): ${function.symbol.returnType.displayName}"
            )
            out.appendLine("  ${function.body}")
        }
        out.appendLine()
    }

    private fun appendIr(out: StringBuilder, module: IrModule) {
        out.appendLine("=== ir ===")
        module.classes.forEach { clazz ->
            out.appendLine("class ${clazz.name}" + (clazz.baseName?.let { " : $it" } ?: ""))
            clazz.fields.forEach { out.appendLine("  field ${it.name}: ${it.type}") }
            clazz.methods.forEach {
                out.appendLine("  method ${it.functionName}: ${it.parameters} -> ${it.returnType}")
            }
        }
        module.functions.forEach { function ->
            out.appendLine(
                "function ${function.name}(" +
                    function.parameters.joinToString { "${it.name}: ${it.type}" } +
                    "): ${function.returnType}"
            )
            function.locals.forEach { out.appendLine("  local ${it.name}: ${it.type}") }
            function.instructions.forEachIndexed { index, instruction ->
                out.appendLine("  $index: $instruction")
            }
        }
        out.appendLine()
    }
}
