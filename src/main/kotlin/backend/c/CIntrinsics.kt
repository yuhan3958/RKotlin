package me.rkt.backend.c

/** The C ABI boundary. Public algorithms and policies live in rkotlin source files. */
internal object CIntrinsics {
    val functions = mapOf(
        "pointer.isFreed" to "rk_pointer_is_freed",
        "pointer.toString" to "rk_pointer_to_string",
        "pointer.addressString" to "rk_pointer_address_string",
        "type.free" to "rk_type_free",
        "int.add" to "rk_int_add", "int.subtract" to "rk_int_subtract",
        "int.multiply" to "rk_int_multiply", "int.divide" to "rk_int_divide",
        "int.remainder" to "rk_int_remainder", "int.less" to "rk_int_less",
        "int.equal" to "rk_int_equal", "bool.equal" to "rk_int_equal",
        "int.toString" to "rk_int_string", "string.length" to "rk_string_length",
        "string.codeAt" to "rk_string_code", "string.slice" to "rk_string_slice",
        "string.concat" to "rk_string_concat", "string.compare" to "rk_string_compare",
        "readLine" to "rk_read_line", "printString" to "rk_print_string",
        "panic" to "rk_panic"
    )

    private val panicRuntime = """
        static void rk_panic(const char *message) {
            fputs(message, stderr);
            fputc('\n', stderr);
            abort();
        }
    """.trimIndent()

    private val integerRuntime = """
        static int32_t rk_int_checked(int64_t value) {
            if (value < INT32_MIN || value > INT32_MAX) rk_panic("integer overflow");
            return (int32_t)value;
        }
        static int32_t rk_int_add(int32_t a, int32_t b) { return rk_int_checked((int64_t)a + b); }
        static int32_t rk_int_subtract(int32_t a, int32_t b) { return rk_int_checked((int64_t)a - b); }
        static int32_t rk_int_multiply(int32_t a, int32_t b) { return rk_int_checked((int64_t)a * b); }
        static int32_t rk_int_divide(int32_t a, int32_t b) {
            if (b == 0) rk_panic("division by zero");
            return rk_int_checked((int64_t)a / b);
        }
        static int32_t rk_int_remainder(int32_t a, int32_t b) {
            if (b == 0) rk_panic("division by zero");
            return (int32_t)((int64_t)a % b);
        }
        static int32_t rk_int_less(int32_t a, int32_t b) { return a < b; }
        static int32_t rk_int_equal(int32_t a, int32_t b) { return a == b; }
        static const char *rk_int_string(int32_t value) {
            char *result = rk_allocate(12);
            snprintf(result, 12, "%" PRId32, value);
            return result;
        }
    """.trimIndent()

    private val stringRuntime = """
        static int32_t rk_utf8_next(const unsigned char **cursor) {
            const unsigned char *p = *cursor;
            uint32_t code = *p++;
            int remaining;
            uint32_t minimum;
            if (code < 0x80) { *cursor = p; return (int32_t)code; }
            if (code >= 0xc2 && code <= 0xdf) { code &= 0x1f; remaining = 1; minimum = 0x80; }
            else if (code >= 0xe0 && code <= 0xef) { code &= 0x0f; remaining = 2; minimum = 0x800; }
            else if (code >= 0xf0 && code <= 0xf4) { code &= 7; remaining = 3; minimum = 0x10000; }
            else { rk_panic("invalid UTF-8"); return 0; }
            while (remaining-- > 0) {
                if ((*p & 0xc0) != 0x80) rk_panic("invalid UTF-8");
                code = (code << 6) | (*p++ & 0x3f);
            }
            if (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) rk_panic("invalid UTF-8");
            *cursor = p;
            return (int32_t)code;
        }
        static int32_t rk_string_length(const char *value) {
            const unsigned char *p = (const unsigned char*)value;
            int64_t count = 0;
            while (*p) { rk_utf8_next(&p); ++count; }
            return rk_int_checked(count);
        }
        static const unsigned char *rk_string_offset(const char *value, int32_t index) {
            if (index < 0) rk_panic("negative string index");
            const unsigned char *p = (const unsigned char*)value;
            while (index-- > 0) {
                if (*p == 0) rk_panic("string index out of bounds");
                rk_utf8_next(&p);
            }
            return p;
        }
        static int32_t rk_string_code(const char *value, int32_t index) {
            const unsigned char *p = rk_string_offset(value, index);
            if (*p == 0) rk_panic("string index out of bounds");
            return rk_utf8_next(&p);
        }
        static const char *rk_string_slice(const char *value, int32_t start, int32_t end) {
            if (end < start) rk_panic("invalid substring range");
            const unsigned char *first = rk_string_offset(value, start);
            const unsigned char *last = rk_string_offset(value, end);
            size_t size = (size_t)(last - first);
            char *result = rk_allocate(size + 1);
            memcpy(result, first, size);
            return result;
        }
        static const char *rk_string_concat(const char *left, const char *right) {
            size_t a = strlen(left), b = strlen(right);
            if (a > SIZE_MAX - b - 1) rk_panic("string too large");
            char *result = rk_allocate(a + b + 1);
            memcpy(result, left, a);
            memcpy(result + a, right, b + 1);
            return result;
        }
        static int32_t rk_string_compare(const char *left, const char *right) {
            int result = strcmp(left, right);
            return (result > 0) - (result < 0);
        }
    """.trimIndent()

    private val ioRuntime = """
        static void rk_print_string(const char *value) { fputs(value, stdout); fflush(stdout); }
        static const char *rk_read_line(void) {
            size_t capacity = 128, size = 0;
            char *buffer = malloc(capacity);
            if (buffer == NULL) abort();
            int ch;
            while ((ch = getchar()) != EOF && ch != '\n') {
                if (ch == 0) rk_panic("NUL is not supported in String");
                if (size + 1 == capacity) {
                    if (capacity > SIZE_MAX / 2) abort();
                    capacity *= 2;
                    char *larger = realloc(buffer, capacity);
                    if (larger == NULL) abort();
                    buffer = larger;
                }
                buffer[size++] = (char)ch;
            }
            if (ferror(stdin)) rk_panic("input error");
            if (ch == EOF && size == 0) { free(buffer); return NULL; }
            if (size > 0 && buffer[size - 1] == '\r') --size;
            char *result = rk_allocate(size + 1);
            memcpy(result, buffer, size);
            free(buffer);
            rk_string_length(result);
            return result;
        }
    """.trimIndent()

    val modules = listOf(
        CIntrinsicModule("runtime/panic.c", listOf("runtime/system.h"), panicRuntime),
        CIntrinsicModule("runtime/integer.c", listOf("runtime/memory.c", "runtime/panic.c"), integerRuntime),
        CIntrinsicModule("runtime/string.c", listOf("runtime/integer.c"), stringRuntime),
        CIntrinsicModule("runtime/io.c", listOf("runtime/string.c"), ioRuntime)
    )
}

internal data class CIntrinsicModule(val path: String, val dependencies: List<String>, val code: String)
