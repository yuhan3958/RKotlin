package me.rkt.lexer

import me.rkt.source.SourceSpan

data class Token(
    val type: TokenType,
    val text: String,
    val span: SourceSpan
)
