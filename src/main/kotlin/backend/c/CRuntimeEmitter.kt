package me.rkt.backend.c

/** Emits the C runtime implementation and its platform headers. */
internal object CRuntimeEmitter {
    fun emitSystemHeaders(w: CWriter) {
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

    fun emitRuntime(w: CWriter, typeIds: Map<String, Int>) {
        w.line("static const char *rk_object_type_name(int32_t type) {")
        w.indented {
            w.line("switch (type) {")
            w.indented {
                typeIds.forEach { (name, id) ->
                    w.line("case $id: return \"$name\";")
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

}
