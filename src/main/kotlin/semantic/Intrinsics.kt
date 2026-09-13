package me.rkt.semantic

/** Machine operations available only to declarations in rkotlin. */
object Intrinsics {
    val targets = setOf(
        "address", "pointer.read", "pointer.write", "pointer.free", "pointer.isFreed",
        "pointer.add", "pointer.toString",
        "buffer.get", "buffer.set", "buffer.length",
        "pointer.addressString",
        "type.free", "type.isFreed",
        "array.toString",
        "readLine", "printString", "panic",
        "int.add", "int.subtract", "int.multiply", "int.divide", "int.remainder",
        "int.less", "int.equal", "int.toString", "bool.equal",
        "string.length", "string.codeAt", "string.slice", "string.concat", "string.compare"
    )
}
