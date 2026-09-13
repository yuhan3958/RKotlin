package me.rkt.backend.c

import me.rkt.backend.Backend
import me.rkt.ir.*

class CBackend(private val moduleDirectory: String = "rkotlin.modules") : Backend<COutput> {
    private var addressedLocals: Set<Int> = emptySet()
    private var classes: Map<String, IrClass> = emptyMap()
    private var typeIds: Map<String, Int> = emptyMap()

    override fun generate(module: IrModule): COutput {
        classes = module.classes.associateBy { it.name }
        typeIds = module.classes.mapIndexed { index, type -> type.name to index + 1 }.toMap()
        val layout = CModuleLayout(module)
        val files = linkedMapOf<String, String>()
        fun add(path: String, emit: (CWriter) -> Unit) {
            require(files.keys.none { it.equals(path, ignoreCase = true) }) { "generated C path collision: $path" }
            val writer = CWriter()
            emit(writer)
            files[path] = layout.guarded(path, writer.toString())
        }

        add("runtime/system.h") { w ->
            emitSystemHeaders(w)
            w.line("typedef struct rk_pointer rk_pointer;")
        }
        add("runtime/memory.c") { w ->
            w.line(layout.include("runtime/memory.c", "runtime/system.h"))
            emitRuntime(w)
        }
        for (intrinsic in CIntrinsics.modules) {
            add(intrinsic.path) { w ->
                intrinsic.dependencies.forEach { w.line(layout.include(intrinsic.path, it)) }
                intrinsic.code.lineSequence().forEach { w.line(it) }
            }
        }
        add("runtime/runtime.c") { w ->
            CIntrinsics.modules.forEach { w.line(layout.include("runtime/runtime.c", it.path)) }
        }
        add("forward.h") { w ->
            w.line(layout.include("forward.h", "runtime/system.h"))
            module.classes.forEach { w.line("struct rk_${sanitize(it.name)};") }
        }
        for (type in module.classes) {
            val path = layout.typeHeader(type.name)
            add(path) { w ->
                w.line(layout.include(path, "forward.h"))
                type.baseName?.let { w.line(layout.include(path, layout.typeHeader(it))) }
                emitClass(w, type)
            }
        }
        for (source in module.sources) {
            val header = layout.header(source)
            val implementation = layout.implementation(source)
            val ownedClasses = module.classes.filter { it.sourcePath == source.sourcePath }
            val ownedFunctions = module.functions.filter { it.sourcePath == source.sourcePath }
            add(header) { w ->
                w.line(layout.include(header, "forward.h"))
                ownedClasses.forEach { type ->
                    w.line(layout.include(header, layout.typeHeader(type.name)))
                    if (type.objectLike) w.line("extern struct rk_${sanitize(type.name)} rk_object_${sanitize(type.name)};")
                    type.methods.filter { it.ownerName == type.name }.forEach { w.line(dispatchPrototype(type, it) + ";") }
                }
                ownedFunctions.forEach { w.line(prototype(it) + ";") }
            }
            add(implementation) { w ->
                w.line(layout.include(implementation, "declarations.h"))
                w.line(layout.include(implementation, "runtime/runtime.c"))
                source.imports.distinct().forEach { dependency ->
                    w.line(layout.include(implementation, layout.implementation(layout.sources.getValue(dependency))))
                }
                w.line()
                ownedClasses.filter { it.objectLike }.forEach { emitObject(w, it) }
                emitDispatchers(w, ownedClasses)
                ownedFunctions.forEach { function ->
                    emitFunction(w, function)
                    w.line()
                }
            }
        }
        add("declarations.h") { w ->
            module.sources.forEach { w.line(layout.include("declarations.h", layout.header(it))) }
        }
        val entry = CWriter()
        entry.line("#include \"$moduleDirectory/${layout.implementation(layout.root)}\"")
        entry.line()
        emitMain(entry, module)
        return COutput(entry.toString(), files)
    }

    private fun emitSystemHeaders(w: CWriter) {
        w.line("#include <stdint.h>")
        w.line("#include <inttypes.h>")
        w.line("#include <string.h>")
        w.line("#include <stdio.h>")
        w.line("#include <stdlib.h>")
        w.line("#ifdef _WIN32")
        w.line("#include <windows.h>")
        w.line("#endif")
        w.line()
    }

    private fun emitClass(w: CWriter, type: IrClass) {
        val (name, fields) = type
        w.line("struct rk_${sanitize(name)} {")
        w.indented {
            if (type.baseName != null) {
                w.line("struct rk_${sanitize(type.baseName)} rk_base;")
            } else {
                w.line("int32_t rk_type;")
            }
            fields.forEach { field ->
                w.line("${cType(field.type)} ${fieldName(field.name)};")
            }
        }
        w.line("};")
    }

    private fun emitObject(w: CWriter, type: IrClass) {
        val (name, fields) = type
        val defaults = listOf(".rk_type = ${typeIds.getValue(name)}") +
            fields.filter { it.type == IrString }.map { ".${fieldName(it.name)} = \"\"" }
        w.line(
            "struct rk_${sanitize(name)} " +
                "rk_object_${sanitize(name)} = { ${defaults.joinToString(", ")} };"
        )
    }

    private fun emitMain(w: CWriter, module: IrModule) {
        val main = module.functions.find {
            it.name == "main" && it.parameters.isEmpty() && it.returnType in setOf(IrI32, IrVoid)
        }

        if (main != null) {
            w.line("int main(void) {")
            w.indented {
                w.line("#ifdef _WIN32")
                w.line("SetConsoleOutputCP(CP_UTF8);")
                w.line("SetConsoleCP(CP_UTF8);")
                w.line("#endif")
                if (main.returnType == IrVoid) {
                    w.line("rk_main();")
                    w.line("return 0;")
                } else {
                    w.line("return rk_main();")
                }
            }
            w.line("}")
        }
    }

    private fun emitRuntime(w: CWriter) {
        val runtime = """
            typedef struct rk_allocation {
                void *value;
                struct rk_allocation *next;
            } rk_allocation;
            static rk_allocation *rk_allocations;

            static void rk_cleanup(void) {
                while (rk_allocations != NULL) {
                    rk_allocation *entry = rk_allocations;
                    rk_allocations = entry->next;
                    free(entry->value);
                    free(entry);
                }
            }

            static void *rk_allocate(size_t size) {
                static int initialized;
                if (!initialized) {
                    if (atexit(rk_cleanup) != 0) abort();
                    initialized = 1;
                }
                rk_allocation *entry = malloc(sizeof(*entry));
                if (entry == NULL) abort();
                entry->value = calloc(1, size);
                if (entry->value == NULL) abort();
                entry->next = rk_allocations;
                rk_allocations = entry;
                return entry->value;
            }

            static void rk_release(void *value) {
                for (rk_allocation *entry = rk_allocations; entry != NULL; entry = entry->next) {
                    if (entry->value == value) {
                        free(value);
                        entry->value = NULL;
                        return;
                    }
                }
            }

            struct rk_pointer {
                void *address;
                int owned;
                int disposed;
            };

            static rk_pointer *rk_pointer_create(void *address, int owned) {
                rk_pointer *pointer = rk_allocate(sizeof(*pointer));
                pointer->address = address;
                pointer->owned = owned;
                return pointer;
            }

            static void *rk_pointer_read(rk_pointer *pointer) {
                if (pointer == NULL || pointer->disposed) abort();
                return pointer->address;
            }

            static void rk_pointer_free(rk_pointer *pointer) {
                if (pointer == NULL || pointer->disposed) abort();
                if (pointer->owned) rk_release(pointer->address);
                pointer->disposed = 1;
            }

            static int32_t rk_pointer_is_freed(rk_pointer *pointer) {
                return pointer == NULL || pointer->disposed;
            }

            static int32_t *rk_box_i32(int32_t value) {
                int32_t *result = rk_allocate(sizeof(*result));
                *result = value;
                return result;
            }
        """.trimIndent()
        runtime.lineSequence().forEach { w.line(it) }
        w.line()
    }

    private fun ancestry(type: IrClass): List<IrClass> =
        generateSequence(type) { it.baseName?.let(classes::getValue) }.toList()

    private fun typeTag(receiver: String, type: IrClass): String =
        "$receiver->" + "rk_base.".repeat(ancestry(type).size - 1) + "rk_type"

    private fun dispatchPrototype(type: IrClass, method: IrMethod): String {
        val parameters = listOf("struct rk_${sanitize(type.name)} *self") +
            method.parameters.mapIndexed { index, parameter -> "${cType(parameter)} a$index" }
        return "${cType(method.returnType)} rk_dispatch_${sanitize(method.functionName)}(${parameters.joinToString(", ")})"
    }

    private fun emitDispatchers(w: CWriter, ownedClasses: List<IrClass>) {
        for (type in ownedClasses) {
            for (method in type.methods.filter { it.ownerName == type.name }) {
                w.line(dispatchPrototype(type, method) + " {")
                w.indented {
                    w.line("if (self == NULL) abort();")
                    w.line("switch (${typeTag("self", type)}) {")
                    w.indented {
                        for (actual in classes.values.filter { candidate -> ancestry(candidate).any { it.name == type.name } }) {
                            val implementation = actual.methods.single { it.name == method.name }
                            val arguments = listOf("(struct rk_${sanitize(implementation.ownerName)}*)self") +
                                method.parameters.indices.map { "a$it" }
                            val call = "rk_${sanitize(implementation.functionName)}(${arguments.joinToString(", ")})"
                            w.line("case ${typeIds.getValue(actual.name)}:")
                            w.indented {
                                if (method.returnType == IrVoid) {
                                    w.line("$call;")
                                    w.line("return;")
                                } else {
                                    w.line("return $call;")
                                }
                            }
                        }
                        w.line("default: abort();")
                    }
                    w.line("}")
                }
                w.line("}")
            }
        }
    }

    private fun prototype(function: IrFunction): String {
        val params = if (function.parameters.isEmpty()) {
            "void"
        } else {
            function.parameters.joinToString(", ") {
                "${cType(it.type)} ${value(it)}"
            }
        }

        return "${cType(function.returnType)} rk_${sanitize(function.name)}($params)"
    }

    private fun emitFunction(w: CWriter, function: IrFunction) {
        addressedLocals = function.instructions.filterIsInstance<IrAddressInstruction>()
            .map { it.local.id }.toSet()
        w.line(prototype(function) + " {")
        w.indented {
            function.locals.forEach { local ->
                if (local.id in addressedLocals) {
                    w.line("${cType(local.type)} *l${local.id} = rk_allocate(sizeof(${cType(local.type)}));")
                } else {
                    w.line("${cType(local.type)} ${localName(local)};")
                }
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
            is IrCastInstruction -> {
                w.line("${value(instruction.result)} = (${cType(instruction.result.type)})${value(instruction.value)};")
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
                val call = "rk_${sanitize(instruction.functionName)}(" +
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
                        "rk_allocate(sizeof(struct rk_${sanitize(instruction.type.name)}));"
                )
                val type = classes.getValue(instruction.type.name)
                w.line("${typeTag(value(instruction.result), type)} = ${typeIds.getValue(type.name)};")
            }

            is IrFieldLoadInstruction -> {
                w.line(
                    "${value(instruction.result)} = " +
                        "${value(instruction.receiver)}->${fieldName(instruction.field)};"
                )
            }

            is IrSafeFieldLoadInstruction -> {
                val field = "${value(instruction.receiver)}->${fieldName(instruction.field)}"
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
                w.line("${value(instruction.result)} = rk_pointer_create(&${localName(instruction.local)}, 0);")
            }

            is IrPointerLoadInstruction -> {
                w.line("${value(instruction.result)} = *(${cType(instruction.result.type)}*)rk_pointer_read(${value(instruction.pointer)});")
            }

            is IrPointerStoreInstruction -> {
                val pointee = (instruction.pointer.type as IrPointerType).pointee
                w.line("*(${cType(pointee)}*)rk_pointer_read(${value(instruction.pointer)}) = ${value(instruction.value)};")
            }

            is IrFreeInstruction -> {
                w.line("rk_pointer_free(${value(instruction.pointer)});")
            }

            is IrManagedPointerInstruction -> {
                w.line(
                    "${value(instruction.result)} = rk_pointer_create(rk_allocate(sizeof(${cType(instruction.pointeeType)})), 1);"
                )
                w.line("*(${cType(instruction.pointeeType)}*)rk_pointer_read(${value(instruction.result)}) = ${value(instruction.initializer)};")
            }

            is IrFieldStoreInstruction -> {
                w.line(
                    "${value(instruction.receiver)}->${fieldName(instruction.field)} = " +
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
        is IrStringConstant -> "\"${escapeString(value.value)}\""
        is IrNullConstant -> "NULL"
        is IrParameter -> "p${value.index}_${sanitize(value.name)}"
        is IrRegister -> "r${value.id}"
        is IrLocal -> localName(value)
        is IrObjectReference -> "&rk_object_${sanitize(value.name)}"
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
                    is IrAddressInstruction -> listOf(instruction.result)
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

    private fun cType(type: IrType): String = when (type) {
        IrI32 -> "int32_t"
        IrNullableI32 -> "int32_t*"
        IrVoid -> "void"
        IrString -> "const char*"
        is IrPointerType -> "rk_pointer*"
        is IrObjectType -> "struct rk_${sanitize(type.name)}*"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun fieldName(name: String): String = "f_${sanitize(name)}"

    private fun escapeString(value: String): String = buildString {
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
