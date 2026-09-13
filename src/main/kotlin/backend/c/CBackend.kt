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
        w.line("static const char *rk_object_type_name(int32_t type) {")
        w.indented {
            w.line("switch (type) {")
            w.indented {
                classes.values.forEach { type ->
                    w.line("case ${typeIds.getValue(type.name)}: return \"${type.name}\";")
                }
                w.line("default: return NULL;")
            }
            w.line("}")
        }
        w.line("}")
        w.line()
        val runtime = """
            typedef struct rk_allocation {
                void *value;
                struct rk_allocation *next;
                const char *type_name;
                int released;
            } rk_allocation;
            static rk_allocation *rk_allocations;

            static void rk_cleanup(void) {
                while (rk_allocations != NULL) {
                    rk_allocation *entry = rk_allocations;
                    rk_allocations = entry->next;
                    if (!entry->released) free(entry->value);
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
                entry->type_name = NULL;
                entry->released = 0;
                rk_allocations = entry;
                return entry->value;
            }

            static void *rk_allocate_typed(size_t size, const char *type_name) {
                void *value = rk_allocate(size);
                for (rk_allocation *entry = rk_allocations; entry != NULL; entry = entry->next) {
                    if (entry->value == value) {
                        entry->type_name = type_name;
                        return value;
                    }
                }
                abort();
            }

            static void rk_release(void *value) {
                for (rk_allocation *entry = rk_allocations; entry != NULL; entry = entry->next) {
                    if (entry->value == value && !entry->released) {
                        free(value);
                        entry->released = 1;
                        return;
                    }
                }
            }

            static const char *rk_allocation_type_name(void *value) {
                for (rk_allocation *entry = rk_allocations; entry != NULL; entry = entry->next) {
                    if (entry->value == value) return entry->type_name;
                }
                return NULL;
            }

            static int rk_allocation_released(void *value) {
                for (rk_allocation *entry = rk_allocations; entry != NULL; entry = entry->next) {
                    if (entry->value == value) return entry->released;
                }
                return 0;
            }

            typedef struct rk_buffer_control {
                void *base_address;
                size_t length;
                size_t stride;
                int released;
            } rk_buffer_control;

            struct rk_pointer {
                void *address;
                void *base_address;
                int owned;
                int disposed;
                const char *type_name;
                int object_slot;
                int buffer;
                int buffer_view;
                rk_buffer_control *buffer_control;
                size_t length;
                size_t stride;
                intptr_t index;
            };

            static rk_pointer *rk_pointer_create(
                void *address, int owned, const char *type_name, int object_slot, int buffer
            ) {
                rk_pointer *pointer = rk_allocate(sizeof(*pointer));
                pointer->address = address;
                pointer->base_address = address;
                pointer->owned = owned;
                pointer->type_name = type_name;
                pointer->object_slot = object_slot;
                pointer->buffer = buffer;
                pointer->buffer_view = 0;
                pointer->buffer_control = NULL;
                pointer->length = 0;
                pointer->stride = 0;
                pointer->index = 0;
                return pointer;
            }

            static rk_pointer *rk_buffer_create(
                int32_t length, size_t stride, const char *type_name
            ) {
                if (length < 0 || stride == 0) abort();
                size_t count = (size_t)length;
                if (count > SIZE_MAX / stride) abort();
                void *address = rk_allocate_typed(count * stride, type_name);
                rk_pointer *pointer = rk_pointer_create(address, 1, type_name, 0, 1);
                rk_buffer_control *control = rk_allocate(sizeof(*control));
                control->base_address = address;
                control->length = count;
                control->stride = stride;
                control->released = 0;
                pointer->buffer_control = control;
                pointer->length = count;
                pointer->stride = stride;
                return pointer;
            }

            static rk_pointer *rk_pointer_add(
                rk_pointer *pointer, int32_t offset, size_t stride,
                const char *type_name, int object_slot
            ) {
                if (pointer == NULL || pointer->disposed || !pointer->buffer ||
                    pointer->buffer_control == NULL || pointer->buffer_control->released ||
                    pointer->stride != stride) abort();
                intptr_t next = pointer->index + (intptr_t)offset;
                if (next < 0 || (size_t)next > pointer->length) abort();
                uintptr_t address = (uintptr_t)pointer->base_address +
                    (uintptr_t)next * stride;
                rk_pointer *result = rk_pointer_create(
                    (void*)address, 0, type_name, object_slot, 1
                );
                result->base_address = pointer->base_address;
                result->buffer_view = 1;
                result->buffer_control = pointer->buffer_control;
                result->length = pointer->length;
                result->stride = pointer->stride;
                result->index = next;
                return result;
            }

            static void *rk_buffer_element(rk_pointer *pointer, int32_t index) {
                if (pointer == NULL || pointer->disposed || !pointer->buffer ||
                    pointer->buffer_control == NULL || pointer->buffer_control->released ||
                    index < 0) abort();
                size_t relative = (size_t)index;
                if (relative >= pointer->length - (size_t)pointer->index) abort();
                return (char*)pointer->address + relative * pointer->stride;
            }

            static int32_t rk_buffer_length(rk_pointer *pointer) {
                if (pointer == NULL || pointer->disposed || !pointer->buffer ||
                    pointer->buffer_control == NULL || pointer->buffer_control->released) abort();
                size_t remaining = pointer->length - (size_t)pointer->index;
                if (remaining > INT32_MAX) abort();
                return (int32_t)remaining;
            }

            static void *rk_pointer_read(rk_pointer *pointer) {
                if (pointer == NULL || pointer->disposed ||
                    (pointer->buffer &&
                        (pointer->buffer_control == NULL || pointer->buffer_control->released ||
                         (size_t)pointer->index >= pointer->length))) abort();
                return pointer->address;
            }

            static void rk_pointer_free(rk_pointer *pointer) {
                if (pointer == NULL || pointer->disposed) abort();
                if (pointer->buffer) {
                    if (pointer->buffer_view || pointer->buffer_control == NULL) {
                        fputs("cannot free a derived BufferPointer view\n", stderr);
                        abort();
                    }
                    if (pointer->buffer_control->released) abort();
                    rk_release(pointer->buffer_control->base_address);
                    pointer->buffer_control->released = 1;
                    pointer->disposed = 1;
                    return;
                }
                if (pointer->owned) rk_release(pointer->address);
                pointer->disposed = 1;
            }

            static int32_t rk_pointer_is_freed(rk_pointer *pointer) {
                return pointer == NULL || pointer->disposed ||
                    (pointer->buffer && pointer->buffer_control != NULL &&
                        pointer->buffer_control->released);
            }

            static void rk_type_free(void *value) {
                if (value == NULL) abort();
                rk_release(value);
            }

            static int32_t rk_type_is_freed(void *value) {
                if (value == NULL) return 0;
                return rk_allocation_released(value);
            }

            static const char *rk_pointer_address_string(rk_pointer *pointer) {
                if (pointer == NULL || rk_pointer_is_freed(pointer)) abort();
                size_t capacity = 2 + sizeof(uintptr_t) * 2 + 1;
                char *result = rk_allocate(capacity);
                snprintf(result, capacity, "0x%" PRIxPTR, (uintptr_t)pointer->address);
                return result;
            }

            static const char *rk_pointer_to_string(rk_pointer *pointer) {
                if (pointer == NULL) abort();
                const char *type_name = pointer->type_name == NULL ? "Pointer" : pointer->type_name;
                const char *pointer_name = pointer->buffer ? "BufferPointer" : "Pointer";
                if (rk_pointer_is_freed(pointer)) {
                    size_t released_capacity = strlen(pointer_name) + strlen(type_name) + 18;
                    char *released = rk_allocate(released_capacity);
                    snprintf(released, released_capacity, "%s<%s>(freed)", pointer_name, type_name);
                    return released;
                }
                if (pointer->object_slot && pointer->address != NULL) {
                    void *object = *(void**)pointer->address;
                    if (object != NULL) {
                        const char *dynamic_name = rk_allocation_type_name(object);
                        if (dynamic_name != NULL) type_name = dynamic_name;
                        if (rk_allocation_released(object)) {
                            size_t released_capacity = strlen(pointer_name) + strlen(type_name) + 18;
                            char *released = rk_allocate(released_capacity);
                            snprintf(released, released_capacity, "%s<%s>(freed)", pointer_name, type_name);
                            return released;
                        }
                        if (dynamic_name == NULL) {
                            dynamic_name = rk_object_type_name(*(int32_t*)object);
                            if (dynamic_name != NULL) type_name = dynamic_name;
                        }
                    }
                }
                size_t capacity = strlen(pointer_name) + strlen(type_name) + 30;
                char *result = rk_allocate(capacity);
                snprintf(result, capacity, "%s<%s>(0x%" PRIxPTR ")", pointer_name, type_name, (uintptr_t)pointer->address);
                return result;
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
                        "rk_allocate_typed(sizeof(struct rk_${sanitize(instruction.type.name)}), " +
                        "\"${instruction.type.name}\");"
                )
                val type = classes.getValue(instruction.type.name)
                w.line("${typeTag(value(instruction.result), type)} = ${typeIds.getValue(type.name)};")
            }

            is IrArrayInitializeInstruction -> {
                w.line("${value(instruction.receiver)}->f_count = ${value(instruction.length)};")
                w.line(
                    "${value(instruction.receiver)}->f_data = rk_buffer_create(" +
                        "${value(instruction.length)}, sizeof(${cType(instruction.elementType)}), " +
                        "\"${pointerTypeName(instruction.elementType)}\");"
                )
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
                w.line(
                    "${value(instruction.result)} = rk_pointer_create(&${localName(instruction.local)}, 0, " +
                        "\"${pointerTypeName(instruction.local.type)}\", " +
                        "${if (instruction.local.type is IrObjectType) 1 else 0}, 0);"
                )
            }

            is IrAddressValueInstruction -> {
                w.line(
                    "${value(instruction.result)} = rk_pointer_create(&${value(instruction.value)}, 0, " +
                        "\"${pointerTypeName(instruction.value.type)}\", " +
                        "${if (instruction.value.type is IrObjectType) 1 else 0}, 0);"
                )
            }

            is IrPointerLoadInstruction -> {
                w.line("${value(instruction.result)} = *(${cType(instruction.result.type)}*)rk_pointer_read(${value(instruction.pointer)});")
            }

            is IrPointerAddInstruction -> {
                w.line(
                    "${value(instruction.result)} = rk_pointer_add(" +
                        "${value(instruction.pointer)}, " +
                        "${if (instruction.direction < 0) "-(${value(instruction.offset)})" else value(instruction.offset)}, " +
                        "sizeof(${cType(instruction.pointeeType)}), " +
                        "\"${pointerTypeName(instruction.pointeeType)}\", " +
                        "${if (instruction.pointeeType is IrObjectType) 1 else 0});"
                )
            }

            is IrBufferGetInstruction -> {
                w.line(
                    "${value(instruction.result)} = *(${cType(instruction.pointeeType)}*)" +
                        "rk_buffer_element(${value(instruction.pointer)}, ${value(instruction.index)});"
                )
            }

            is IrBufferSetInstruction -> {
                w.line(
                    "*(${cType(instruction.pointeeType)}*)" +
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
                        "${value(instruction.length)}, sizeof(${cType(instruction.pointeeType)}), " +
                        "\"${pointerTypeName(instruction.pointeeType)}\");"
                )
            }

            is IrPointerStoreInstruction -> {
                val pointee = (instruction.pointer.type as IrPointerType).pointee
                w.line("*(${cType(pointee)}*)rk_pointer_read(${value(instruction.pointer)}) = ${value(instruction.value)};")
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
                        "rk_allocate_typed(sizeof(${cType(instruction.pointeeType)}), " +
                        "\"${pointerTypeName(instruction.pointeeType)}\"), 1, " +
                        "${if (instruction.pointeeType is IrObjectType) 1 else 0}, " +
                        "${if (instruction.result.type is IrPointerType && instruction.result.type.kind == IrPointerKind.BUFFER) 1 else 0});"
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

    private fun pointerTypeName(type: IrType): String = when (type) {
        is IrObjectType -> type.name
        is IrPointerType -> "${type.kind.displayName}<${pointerTypeName(type.pointee)}>"
        IrI32 -> "Int32"
        IrNullableI32 -> "Int32"
        IrString -> "String"
        IrVoid -> "Void"
    }

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
