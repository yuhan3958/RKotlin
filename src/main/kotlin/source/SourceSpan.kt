package me.rkt.source

data class SourceSpan(
    val source: SourceFile,
    val start: Int,
    val end: Int
) {
    init {
        require(start <= end)
    }

    fun merge(other: SourceSpan): SourceSpan {
        require(source == other.source)
        return SourceSpan(source, minOf(start, other.start), maxOf(end, other.end))
    }
}
