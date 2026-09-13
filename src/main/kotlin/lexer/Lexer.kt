package me.rkt.lexer

import me.rkt.diagnostic.DiagnosticReporter
import me.rkt.source.SourceFile
import me.rkt.source.SourceSpan

class Lexer(
    private val source: SourceFile,
    private val diagnostics: DiagnosticReporter
) {
    private var position = 0

    fun lex(): List<Token> {
        val result = mutableListOf<Token>()

        while (!isAtEnd()) {
            skipWhitespaceAndComments()
            if (isAtEnd()) break
            result += nextToken()
        }

        result += Token(
            TokenType.EOF,
            "",
            SourceSpan(source, position, position)
        )

        return result
    }

    private fun nextToken(): Token {
        val start = position
        val c = advance()

        return when {
            c.isLetter() || c == '_' -> lexIdentifier(start)
            c.isDigit() -> lexInteger(start)
            c == '"' -> lexString(start)

            c == ':' -> simple(TokenType.COLON, start)
            c == '?' && peek() == ':' -> {
                position++
                simple(TokenType.ELVIS, start)
            }
            c == '?' && peek() == '.' -> {
                position++
                simple(TokenType.SAFE_DOT, start)
            }
            c == '?' -> simple(TokenType.QUESTION, start)
            c == ',' -> simple(TokenType.COMMA, start)
            c == ';' -> simple(TokenType.SEMICOLON, start)
            c == '(' -> simple(TokenType.LPAREN, start)
            c == ')' -> simple(TokenType.RPAREN, start)
            c == '{' -> simple(TokenType.LBRACE, start)
            c == '}' -> simple(TokenType.RBRACE, start)
            c == '[' -> simple(TokenType.LBRACKET, start)
            c == ']' -> simple(TokenType.RBRACKET, start)
            c == '+' -> simple(TokenType.PLUS, start)
            c == '-' -> simple(TokenType.MINUS, start)
            c == '*' -> simple(TokenType.STAR, start)
            c == '/' -> simple(TokenType.SLASH, start)
            c == '&' && peek() == '&' -> {
                position++
                simple(TokenType.AND_AND, start)
            }
            c == '|' && peek() == '|' -> {
                position++
                simple(TokenType.OR_OR, start)
            }
            c == '^' -> simple(TokenType.CARET, start)
            c == '=' && peek() == '=' -> {
                position++
                simple(TokenType.EQUAL_EQUAL, start)
            }
            c == '=' -> simple(TokenType.EQUAL, start)
            c == '!' && peek() == '=' -> {
                position++
                simple(TokenType.BANG_EQUAL, start)
            }
            c == '!' -> simple(TokenType.BANG, start)
            c == '<' && peek() == '=' -> {
                position++
                simple(TokenType.LESS_EQUAL, start)
            }
            c == '<' -> simple(TokenType.LESS, start)
            c == '>' && peek() == '=' -> {
                position++
                simple(TokenType.GREATER_EQUAL, start)
            }
            c == '>' -> simple(TokenType.GREATER, start)
            c == '.' && peek() == '.' -> {
                position++
                simple(TokenType.DOT_DOT, start)
            }
            c == '.' -> simple(TokenType.DOT, start)

            else -> diagnostics.fail(
                SourceSpan(source, start, position),
                "unexpected character '$c'"
            )
        }
    }

    private fun lexIdentifier(start: Int): Token {
        while (!isAtEnd()) {
            val c = peek()
            if (!c.isLetterOrDigit() && c != '_') break
            position++
        }

        val text = source.content.substring(start, position)
        val type = when (text) {
            "import" -> TokenType.IMPORT
            "class" -> TokenType.CLASS
            "object" -> TokenType.OBJECT
            "new" -> TokenType.NEW
            "fun" -> TokenType.FUN
            "return" -> TokenType.RETURN
            "if" -> TokenType.IF
            "else" -> TokenType.ELSE
            "while" -> TokenType.WHILE
            "for" -> TokenType.FOR
            "in" -> TokenType.IN
            "var" -> TokenType.VAR
            "val" -> TokenType.VAL
            "public" -> TokenType.PUBLIC
            "private" -> TokenType.PRIVATE
            "protected" -> TokenType.PROTECTED
            "override" -> TokenType.OVERRIDE
            "super" -> TokenType.SUPER
            "native" -> TokenType.NATIVE
            "type" -> TokenType.TYPE
            "null" -> TokenType.NULL
            "this" -> TokenType.THIS
            "true" -> TokenType.TRUE
            "false" -> TokenType.FALSE
            else -> TokenType.IDENTIFIER
        }

        return Token(type, text, SourceSpan(source, start, position))
    }

    private fun lexInteger(start: Int): Token {
        while (!isAtEnd() && peek().isDigit()) {
            position++
        }

        val text = source.content.substring(start, position)
        return Token(TokenType.INTEGER, text, SourceSpan(source, start, position))
    }

    private fun lexString(start: Int): Token {
        val value = StringBuilder()
        while (!isAtEnd() && peek() != '"') {
            if (peek() == '\\') {
                advance()
                if (isAtEnd()) diagnostics.fail(SourceSpan(source, start, position), "unterminated escape")
                value.append(when (val escaped = advance()) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    '\\' -> '\\'
                    '"' -> '"'
                    else -> diagnostics.fail(SourceSpan(source, start, position), "unknown string escape '$escaped'")
                })
            } else {
                val character = advance()
                if (character == '\u0000') diagnostics.fail(SourceSpan(source, start, position), "NUL is not supported in String")
                value.append(character)
            }
        }
        if (isAtEnd()) {
            diagnostics.fail(
                SourceSpan(source, start, position),
                "unterminated string literal"
            )
        }
        position++
        return Token(TokenType.STRING, value.toString(), SourceSpan(source, start, position))
    }

    private fun skipWhitespaceAndComments() {
        while (!isAtEnd()) {
            when {
                peek().isWhitespace() -> position++
                peek() == '/' && peek(1) == '/' -> {
                    position += 2
                    while (!isAtEnd() && peek() != '\n' && peek() != '\r') {
                        position++
                    }
                }
                else -> return
            }
        }
    }

    private fun simple(type: TokenType, start: Int): Token =
        Token(type, source.content.substring(start, position), SourceSpan(source, start, position))

    private fun advance(): Char = source.content[position++]

    private fun peek(offset: Int = 0): Char {
        val index = position + offset
        return if (index < source.content.length) source.content[index] else '\u0000'
    }

    private fun isAtEnd(): Boolean = position >= source.content.length
}
