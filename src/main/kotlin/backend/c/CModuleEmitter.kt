package me.rkt.backend.c

import me.rkt.ir.*

/** Owns one module's files, declarations, dispatchers, and entry point. */
internal class CModuleEmitter(private val moduleDirectory: String, private val module: IrModule) {
    private val classes = module.classes.associateBy { it.name }
    private val typeIds = module.classes.mapIndexed { index, type -> type.name to index + 1 }.toMap()
    private val representation = CRepresentation(classes)

    fun generate(): COutput {
        val layout = CModuleLayout(module)
        val files = linkedMapOf<String, String>()
        fun add(path: String, emit: (CWriter) -> Unit) {
            require(files.keys.none { it.equals(path, ignoreCase = true) }) { "generated C path collision: $path" }
            val writer = CWriter()
            emit(writer)
            files[path] = layout.guarded(path, writer.toString())
        }

        add("runtime/system.h") { w ->
            CRuntimeEmitter.emitSystemHeaders(w)
            w.line("typedef struct rk_pointer rk_pointer;")
        }
        add("runtime/memory.c") { w ->
            w.line(layout.include("runtime/memory.c", "runtime/system.h"))
            CRuntimeEmitter.emitRuntime(w, typeIds)
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
            module.classes.filterNot { it.isInterface }
                .forEach { w.line("struct rk_${representation.sanitize(it.name)};") }
        }
        for (type in module.classes.filterNot { it.isInterface }) {
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
                    if (!type.isInterface) {
                        w.line(layout.include(header, layout.typeHeader(type.name)))
                        if (type.objectLike) w.line("extern struct rk_${representation.sanitize(type.name)} rk_object_${representation.sanitize(type.name)};")
                    }
                    type.methods.filter { it.ownerName == type.name && it.virtual }.forEach { w.line(dispatchPrototype(type, it) + ";") }
                }
                ownedFunctions.forEach { w.line(CFunctionEmitter(classes, typeIds, representation).prototype(it) + ";") }
            }
            add(implementation) { w ->
                w.line(layout.include(implementation, "declarations.h"))
                w.line(layout.include(implementation, "runtime/runtime.c"))
                source.imports.distinct().forEach { dependency ->
                    w.line(layout.include(implementation, layout.implementation(layout.sources.getValue(dependency))))
                }
                w.line()
                ownedClasses.filter { !it.isInterface && it.objectLike }.forEach { emitObject(w, it) }
                emitDispatchers(w, ownedClasses)
                ownedFunctions.forEach { function ->
                    CFunctionEmitter(classes, typeIds, representation).emitFunction(w, function)
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

    private fun emitClass(w: CWriter, type: IrClass) {
        val (name, fields) = type
        w.line("struct rk_${representation.sanitize(name)} {")
        w.indented {
            if (type.baseName != null) {
                w.line("struct rk_${representation.sanitize(type.baseName)} rk_base;")
            } else {
                w.line("int32_t rk_type;")
            }
            fields.forEach { field ->
                w.line("${representation.cType(field.type)} ${representation.fieldName(field.name)};")
            }
        }
        w.line("};")
    }

    private fun emitObject(w: CWriter, type: IrClass) {
        val (name, fields) = type
        val defaults = listOf(".rk_type = ${typeIds.getValue(name)}") +
            fields.filter { it.type == IrString }.map { ".${representation.fieldName(it.name)} = \"\"" }
        w.line(
            "struct rk_${representation.sanitize(name)} " +
                "rk_object_${representation.sanitize(name)} = { ${defaults.joinToString(", ")} };"
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

    private fun dispatchPrototype(type: IrClass, method: IrMethod): String {
        val receiver = if (type.isInterface) "void *self" else "struct rk_${representation.sanitize(type.name)} *self"
        val parameters = listOf(receiver) +
            method.parameters.mapIndexed { index, parameter -> "${representation.cType(parameter)} a$index" }
        return "${representation.cType(method.returnType)} rk_dispatch_${representation.sanitize(method.functionName)}(${parameters.joinToString(", ")})"
    }

    private fun emitDispatchers(w: CWriter, ownedClasses: List<IrClass>) {
        for (type in ownedClasses) {
            for (method in type.methods.filter { it.ownerName == type.name && it.virtual }) {
                w.line(dispatchPrototype(type, method) + " {")
                w.indented {
                    w.line("if (self == NULL) abort();")
                    val actualTypes = if (type.isInterface) {
                        classes.values.filter { actual ->
                            !actual.isInterface && implementsInterface(actual, type)
                        }
                    } else {
                        classes.values.filter { candidate -> representation.ancestry(candidate).any { it.name == type.name } }
                    }
                    val switchValue = if (type.isInterface) {
                        "((struct rk_Type*)self)->rk_type"
                    } else {
                        representation.typeTag("self", type)
                    }
                    w.line("switch ($switchValue) {")
                    w.indented {
                        for (actual in actualTypes) {
                            val implementation = actual.methods.single { it.name == method.name }
                            val arguments = listOf(
                                "(struct rk_${representation.sanitize(implementation.ownerName)}*)self"
                            ) +
                                method.parameters.indices.map { "a$it" }
                            val call = "rk_${representation.sanitize(implementation.functionName)}(${arguments.joinToString(", ")})"
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

    private fun implementsInterface(actual: IrClass, expected: IrClass): Boolean =
        actual.interfaceNames.any { it == expected.name || classes[it]?.let { parent ->
            implementsInterface(parent, expected)
        } == true } ||
            actual.baseName?.let { classes[it]?.let { parent -> implementsInterface(parent, expected) } } == true

}
