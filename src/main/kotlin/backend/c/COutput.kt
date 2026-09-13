package me.rkt.backend.c

/** Supporting paths are relative to the entry file's dedicated module directory. */
data class COutput(
    val entrySource: String,
    val files: Map<String, String>
)
