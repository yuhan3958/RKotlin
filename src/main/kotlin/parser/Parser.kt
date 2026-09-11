package me.rkt.parser

import me.rkt.ast.*
import me.rkt.diagnostic.DiagnosticReporter
import me.rkt.lexer.Token
import me.rkt.lexer.TokenType
import me.rkt.source.SourceSpan

class Parser(
    private val tokens: List<Token>,
    private val diagnostics: DiagnosticReporter
) {
    private var position = 0

    fun parseModule(): AstModule {
        val imports = mutableListOf<ImportDeclaration>()
        val declarations = mutableListOf<Declaration>()

        while (!check(TokenType.EOF)) {
            if (match(TokenType.IMPORT)) {
                imports += parseImport(previous())
            } else {
                declarations += when {
                    check(TokenType.CLASS) -> parseClassDeclaration()
                    check(TokenType.OBJECT) -> parseObjectDeclaration()
                    else -> parseFunctionDeclaration()
                }
            }
        }

        val span = if (declarations.isEmpty()) {
            current().span
        } else {
            declarations.first().span.merge(declarations.last().span)
        }

        return AstModule(declarations, span, imports)
    }

    private fun parseImport(start: Token): ImportDeclaration {
        val path = mutableListOf<String>()
        val first = expect(TokenType.IDENTIFIER)
        path += first.text
        var end = first.span
        while (match(TokenType.DOT)) {
            val part = expect(TokenType.IDENTIFIER)
            path += part.text
            end = part.span
        }
        match(TokenType.SEMICOLON)
        return ImportDeclaration(path, start.span.merge(end))
    }

    private fun parseClassDeclaration(): ClassDeclaration =
        parseTypeDeclaration(isObject = false) as ClassDeclaration

    private fun parseObjectDeclaration(): ObjectDeclaration =
        parseTypeDeclaration(isObject = true) as ObjectDeclaration

    private fun parseTypeDeclaration(isObject: Boolean): Declaration {
        val start = advance()
        val name = expect(TokenType.IDENTIFIER)
        expect(TokenType.LBRACE)
        val members = mutableListOf<ClassMember>()
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            members += when {
                check(TokenType.VAR) || check(TokenType.VAL) ->
                    parseFieldDeclaration(advance())
                check(TokenType.FUN) ->
                    parseFunctionDeclaration(name.text)
                else -> {
                    diagnostics.fail(
                        current().span,
                        "expected field or function declaration in ${if (isObject) "object" else "class"}"
                    )
                }
            }
        }
        val end = expect(TokenType.RBRACE)
        val span = start.span.merge(end.span)
        return if (isObject) {
            ObjectDeclaration(name.text, members, span)
        } else {
            ClassDeclaration(name.text, members, span)
        }
    }

    private fun parseFieldDeclaration(keyword: Token): FieldDeclaration {
        val name = expect(TokenType.IDENTIFIER)
        expect(TokenType.COLON)
        val type = parseTypeReference()
        expect(TokenType.SEMICOLON)
        return FieldDeclaration(
            keyword.type == TokenType.VAR,
            name.text,
            type,
            keyword.span.merge(type.span)
        )
    }

    private fun parseFunctionDeclaration(owner: String? = null): FunctionDeclaration {
        val start = expect(TokenType.FUN)
        val name = expect(TokenType.IDENTIFIER)

        expect(TokenType.LPAREN)
        val parameters = mutableListOf<Parameter>()

        if (!check(TokenType.RPAREN)) {
            do {
                val parameterName = expect(TokenType.IDENTIFIER)
                expect(TokenType.COLON)
                val type = parseTypeReference()

                parameters += Parameter(
                    parameterName.text,
                    type,
                    parameterName.span.merge(type.span)
                )
            } while (match(TokenType.COMMA))
        }

        expect(TokenType.RPAREN)
        expect(TokenType.COLON)
        val returnType = parseTypeReference()
        val body = parseBlock()

        return FunctionDeclaration(
            name.text,
            parameters,
            returnType,
            body,
            start.span.merge(body.span),
            owner
        )
    }

    private fun parseTypeReference(): TypeReference {
        val token = expect(TokenType.IDENTIFIER)
        return TypeReference(token.text, token.span)
    }

    private fun parseBlock(): BlockStatement {
        val start = expect(TokenType.LBRACE)
        val statements = mutableListOf<Statement>()

        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            statements += parseStatement()
        }

        val end = expect(TokenType.RBRACE)
        return BlockStatement(statements, start.span.merge(end.span))
    }

    private fun parseStatement(): Statement {
        if (match(TokenType.IF)) return parseIf(previous())
        if (match(TokenType.WHILE)) return parseWhile(previous())
        if (match(TokenType.FOR)) return parseFor(previous())

        if (match(TokenType.RETURN)) {
            val start = previous()
            val expression = parseExpression()
            match(TokenType.SEMICOLON)
            return ReturnStatement(expression, start.span.merge(expression.span))
        }

        if (check(TokenType.VAR) || check(TokenType.VAL)) {
            return parseVariableDeclaration(advance())
        }

        val expression = parseExpression()
        if (match(TokenType.EQUAL)) {
            val value = parseExpression()
            match(TokenType.SEMICOLON)
            return AssignmentStatement(
                expression,
                value,
                expression.span.merge(value.span)
            )
        }
        match(TokenType.SEMICOLON)
        return ExpressionStatement(expression, expression.span)
    }

    private fun parseIf(start: Token): IfStatement {
        expect(TokenType.LPAREN)
        val condition = parseExpression()
        expect(TokenType.RPAREN)
        val thenBranch = parseBlock()
        val elseBranch = if (match(TokenType.ELSE)) parseBlock() else null
        val end = elseBranch?.span ?: thenBranch.span
        return IfStatement(condition, thenBranch, elseBranch, start.span.merge(end))
    }

    private fun parseWhile(start: Token): WhileStatement {
        expect(TokenType.LPAREN)
        val condition = parseExpression()
        expect(TokenType.RPAREN)
        val body = parseBlock()
        return WhileStatement(condition, body, start.span.merge(body.span))
    }

    private fun parseFor(start: Token): ForStatement {
        expect(TokenType.LPAREN)
        val name = expect(TokenType.IDENTIFIER)
        expect(TokenType.IN)
        val rangeStart = parseExpression()
        expect(TokenType.DOT_DOT)
        val rangeEnd = parseExpression()
        expect(TokenType.RPAREN)
        val body = parseBlock()
        return ForStatement(
            name.text,
            rangeStart,
            rangeEnd,
            body,
            start.span.merge(body.span)
        )
    }

    private fun parseVariableDeclaration(keyword: Token): VariableDeclarationStatement {
        val mutable = keyword.type == TokenType.VAR

        val name = expect(TokenType.IDENTIFIER)

        expect(TokenType.COLON)

        val type = parseTypeReference()

        expect(TokenType.EQUAL)

        val initializer = parseExpression()
        match(TokenType.SEMICOLON)

        return VariableDeclarationStatement(
            mutable = mutable,
            name = name.text,
            type = type,
            initializer = initializer,
            span = keyword.span.merge(initializer.span)
        )
    }

    private fun parseExpression(): Expression = parseComparison()

    private fun parseComparison(): Expression {
        var expression = parseAdditive()
        while (
            check(TokenType.EQUAL_EQUAL) ||
            check(TokenType.BANG_EQUAL) ||
            check(TokenType.LESS) ||
            check(TokenType.LESS_EQUAL) ||
            check(TokenType.GREATER) ||
            check(TokenType.GREATER_EQUAL)
        ) {
            val operator = advance()
            val right = parseAdditive()
            expression = BinaryExpression(
                expression,
                when (operator.type) {
                    TokenType.EQUAL_EQUAL -> BinaryOperator.EQUALS
                    TokenType.BANG_EQUAL -> BinaryOperator.NOT_EQUALS
                    TokenType.LESS -> BinaryOperator.LESS
                    TokenType.LESS_EQUAL -> BinaryOperator.LESS_EQUALS
                    TokenType.GREATER -> BinaryOperator.GREATER
                    TokenType.GREATER_EQUAL -> BinaryOperator.GREATER_EQUALS
                    else -> error("not a comparison operator")
                },
                right,
                expression.span.merge(right.span)
            )
        }
        return expression
    }

    private fun parseAdditive(): Expression {
        var expression = parseMultiplicative()

        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            val operator = advance()
            val right = parseMultiplicative()

            expression = BinaryExpression(
                expression,
                if (operator.type == TokenType.PLUS) BinaryOperator.ADD else BinaryOperator.SUB,
                right,
                expression.span.merge(right.span)
            )
        }

        return expression
    }

    private fun parseMultiplicative(): Expression {
        var expression = parseCall()

        while (check(TokenType.STAR) || check(TokenType.SLASH)) {
            val operator = advance()
            val right = parseCall()

            expression = BinaryExpression(
                expression,
                if (operator.type == TokenType.STAR) BinaryOperator.MUL else BinaryOperator.DIV,
                right,
                expression.span.merge(right.span)
            )
        }

        return expression
    }

    private fun parseCall(): Expression {
        var expression = parsePrimary()

        while (true) {
            if (match(TokenType.LPAREN)) {
                val arguments = mutableListOf<Expression>()

                if (!check(TokenType.RPAREN)) {
                    do {
                        arguments += parseExpression()
                    } while (match(TokenType.COMMA))
                }

                val end = expect(TokenType.RPAREN)
                expression = CallExpression(
                    expression,
                    arguments,
                    expression.span.merge(end.span)
                )
            } else if (match(TokenType.DOT)) {
                val name = expect(TokenType.IDENTIFIER)
                expression = MemberAccessExpression(
                    expression,
                    name.text,
                    expression.span.merge(name.span)
                )
            } else {
                break
            }
        }

        return expression
    }

    private fun parsePrimary(): Expression {
        if (match(TokenType.INTEGER)) {
            val token = previous()
            return IntegerLiteral(token.text.toInt(), token.span)
        }

        if (match(TokenType.STRING)) {
            val token = previous()
            return StringLiteral(token.text, token.span)
        }

        if (match(TokenType.NEW)) {
            val start = previous()
            val typeToken = expect(TokenType.IDENTIFIER)
            val type = TypeReference(typeToken.text, typeToken.span)
            expect(TokenType.LPAREN)
            val arguments = mutableListOf<Expression>()
            if (!check(TokenType.RPAREN)) {
                do {
                    arguments += parseExpression()
                } while (match(TokenType.COMMA))
            }
            val end = expect(TokenType.RPAREN)
            return NewExpression(type, arguments, start.span.merge(end.span))
        }

        if (match(TokenType.IDENTIFIER)) {
            val token = previous()
            return NameExpression(token.text, token.span)
        }

        if (match(TokenType.LPAREN)) {
            val expression = parseExpression()
            expect(TokenType.RPAREN)
            return expression
        }

        diagnostics.fail(current().span, "expected expression")
    }

    private fun expect(type: TokenType): Token {
        if (check(type)) return advance()
        diagnostics.fail(current().span, "expected $type but found ${current().type}")
    }

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false
        advance()
        return true
    }

    private fun check(type: TokenType): Boolean = current().type == type

    private fun peek(offset: Int): Token {
        val index = minOf(position + offset, tokens.lastIndex)
        return tokens[index]
    }

    private fun advance(): Token {
        val token = current()
        if (position < tokens.size - 1) position++
        return token
    }

    private fun current(): Token = tokens[position]
    private fun previous(): Token = tokens[position - 1]
}
