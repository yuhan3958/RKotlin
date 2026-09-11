package me.rkt.backend.c

class CWriter {
    private val out = StringBuilder()
    private var indent = 0

    fun line(text: String = "") {
        repeat(indent) { out.append("    ") }
        out.appendLine(text)
    }

    fun unindentedLine(text: String = "") {
        out.appendLine(text)
    }

    fun indented(block: () -> Unit) {
        indent++
        try {
            block()
        } finally {
            indent--
        }
    }

    override fun toString(): String = out.toString()
}
