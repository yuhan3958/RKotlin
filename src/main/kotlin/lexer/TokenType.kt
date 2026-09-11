package me.rkt.lexer

enum class TokenType {
    FUN,
    RETURN,
    IF,
    ELSE,
    WHILE,
    FOR,
    IN,

    IDENTIFIER,
    INTEGER,
    STRING,

    COLON,
    COMMA,

    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,

    PLUS,
    MINUS,
    STAR,
    SLASH,
    DOT_DOT,

    EQUAL,
    EQUAL_EQUAL,
    BANG_EQUAL,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    VAR,
    VAL,

    EOF
}
