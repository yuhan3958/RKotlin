package me.rkt.lexer

enum class TokenType {
    IMPORT,
    CLASS,
    OBJECT,
    NEW,
    FUN,
    RETURN,
    IF,
    ELSE,
    WHILE,
    FOR,
    IN,

    PUBLIC,
    PRIVATE,
    PROTECTED,
    OVERRIDE,
    SUPER,
    NATIVE,
    TYPE,
    NULL,
    THIS,
    TRUE,
    FALSE,

    IDENTIFIER,
    INTEGER,
    STRING,

    COLON,
    COMMA,
    SEMICOLON,
    DOT,
    SAFE_DOT,
    QUESTION,
    ELVIS,

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
