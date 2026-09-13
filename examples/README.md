# RKotlin examples

These are source fixtures, not an automatically executed test suite. Repository policy prohibits builds, compiler execution, and test execution. The new examples have been inspected statically only.

| File | Coverage | Intended successful output |
| --- | --- | --- |
| `inheritance.rk` | Single inheritance, named and synthesized constructors, `super`, protected methods, virtual calls through a base type, property accessors | `inheritance: ok` |
| `standard-library.rk` | Int/Bool methods, Unicode strings, parsing and overflow rejection, StringBuilder, IntList | `standard library: ok` |
| `nullable-and-pointer.rk` | Nullable scalars, lazy safe calls and Elvis, borrowed/owned/escaping pointers, disposed state | `nullable and pointer: ok` |
| `modules/main.rk` | Explicit and implicit imports, duplicate and cyclic imports, inheritance and generated accessors across source files | `modules: ok` |

`errors/` contains independent negative fixtures. Each file states its intended diagnostic or runtime failure. Do not combine them with successful examples or execute them automatically.

Existing examples are preserved and may use earlier language syntax.

## C output modules

Code generation preserves source module boundaries. Each loaded `.rk` module produces its own `.c` implementation and `.h` declarations, including modules reached by the automatic `rkotlin.core` import. An output named `integer-overflow.c` has a dedicated sibling directory named `integer-overflow.modules`.

Expected layout (not generated or executed as part of these changes):

```text
integer-overflow.c                     entry include and C main wrapper
integer-overflow.modules/
    declarations.h                    includes module declaration headers
    forward.h                         class forward declarations
    app/integer-overflow.c            source function implementations
    app/integer-overflow.h
    rkotlin/core.c                    includes its imported modules
    rkotlin/core.h
    rkotlin/Int.c                     includes Int32.c; Int inherits Int32
    rkotlin/Int.h
    rkotlin/Int32.c                   Int32 method implementations
    rkotlin/Int32.h
    ...                              other imported core modules
    types/                           one layout header per ordinary class
    runtime/system.h
    runtime/memory.c
    runtime/integer.c
    runtime/string.c
    runtime/io.c
    runtime/panic.c
    runtime/runtime.c                 includes the runtime fragments
```

The entry includes the root module implementation. Module implementations include their explicit and implicit dependency implementations using relative paths. Include guards ensure repeated or cyclic imports produce one definition. Declaration headers are available before implementations; class layout headers include their superclass layout, so cross-module inheritance does not depend on import order. Synthesized constructors and accessors stay in their declaring class's source module.

These `.c` files are include fragments forming one translation unit. Only the entry `.c` is passed to the native compiler; do not compile the fragments separately or glob every `.c`. Keep the entry and its `.modules` directory together when moving the output. Different entry filenames receive separate module directories. Files from older output generations are not deleted and are not included unless part of the current import graph.

## Class syntax

```kotlin
class Parent {
    public Parent(value: Int) { }
    public fun describe(): String { return "parent" }
}

class Child : Parent {
    public Child(value: Int) { super(value) }
    public override fun describe(): String { return super.describe() + ":child" }
}

fun main(): Int {
    val value: Parent = new Child(1)
    println(value.describe())
    return 0
}
```

Classes support one superclass. Public and protected methods dispatch virtually; private methods and `super.method()` calls are direct. An override keeps parameter and return types and cannot reduce visibility. Fields cannot hide inherited fields. `super(...)` is the first constructor statement; omitting it invokes the accessible no-argument parent constructor. A missing constructor is synthesized when this is possible.

Property reads and writes use `getX()`/`setX(value)`. Missing accessors are synthesized with the field's visibility. An explicit public getter/setter can expose a private backing field. Inside its own accessor the field denotes storage, avoiding recursion.

## Standard library

`rkotlin.core` is imported automatically. Optional classes use explicit imports, for example `import rkotlin.IntList`.

| Type/module | Methods |
| --- | --- |
| `Int`, `Int32` | `plus`, `minus`, `times`, `div`, `rem`, `equals`, `notEquals`, `compareTo`, `lessThan`, `lessOrEqual`, `greaterThan`, `greaterOrEqual`, `abs`, `sign`, `min`, `max`, `coerceIn`, `pow`, `isEven`, `isOdd`, `toString`, `address` |
| `Bool` | `not`, `and`, `or`, `xor`, `equals`, `notEquals`, `toInt`, `toString`, `address` |
| `String` | `length`, `codeAt`, `substring`, `plus`, `compareTo`, comparison methods, `isEmpty`, `isNotEmpty`, `startsWith`, `endsWith`, `indexOf`, `contains`, `replace`, `trim`, `trimEnd`, `repeat`, `toInt`, `toIntOrNull`, `toString`, `address` |
| `Pointer<T>` | `read`, `write`, `free`, `isFreed` |
| `StringBuilder` | `append`, `appendInt`, `appendBool`, `appendLine`, `length`, `isEmpty`, `clear`, `toString` |
| `IntList` | `add`, `get`, `set`, `removeAt`, `size`, `isEmpty`, `indexOf`, `contains`, `clear` |
| `io` | `print`, `println`, `readLine`, `readInt32`, `input` |
| `contracts` | `require`, `check` |

Integer overflow and division by zero terminate with a diagnostic. `toIntOrNull` returns null for invalid input or overflow. String indexes count Unicode scalar values, not bytes or grapheme clusters; substring ends are exclusive. `trim` recognizes ASCII whitespace. String does not accept embedded NUL. Bool method arguments are evaluated eagerly, as ordinary method arguments are; `?.` and `?:` evaluate only the selected branch.

Borrowed pointer disposal invalidates the handle without freeing the variable. Owned pointer disposal frees its storage. Handles, escaped local storage, ordinary objects, and allocated strings are retained until process exit; this is not a tracing garbage collector. `IntList` is a linked list and `StringBuilder` currently concatenates immutable strings.

The implemented collection is `IntList`; arbitrary generic classes, interfaces, multiple inheritance, downcasts, floating-point types, and a general collection framework are not implemented. Machine arithmetic, UTF-8 storage operations, memory allocation, and OS I/O remain native bridges. Public algorithms are defined in `.rk` files.
