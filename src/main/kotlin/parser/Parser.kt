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
        val declarations = mutableListOf<Declaration>()

        while (!check(TokenType.EOF)) {
            declarations += parseFunctionDeclaration()
        }

        val span = if (declarations.isEmpty()) {
            current().span
        } else {
            declarations.first().span.merge(declarations.last().span)
        }

        return AstModule(declarations, span)
    }

    private fun parseFunctionDeclaration(): FunctionDeclaration {
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
            start.span.merge(body.span)
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
            return ReturnStatement(expression, start.span.merge(expression.span))
        }

        if (check(TokenType.VAR) || check(TokenType.VAL)) {
            return parseVariableDeclaration(advance())
        }

        if (
            check(TokenType.IDENTIFIER) &&
            peek(1).type == TokenType.EQUAL
        ) {
            return parseAssignment()
        }

        val expression = parseExpression()
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

        return VariableDeclarationStatement(
            mutable = mutable,
            name = name.text,
            type = type,
            initializer = initializer,
            span = keyword.span.merge(initializer.span)
        )
    }

    private fun parseAssignment(): AssignmentStatement {
        val name = expect(TokenType.IDENTIFIER)
        expect(TokenType.EQUAL)
        val expression = parseExpression()
        return AssignmentStatement(
            name.text,
            expression,
            name.span.merge(expression.span)
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

        while (match(TokenType.LPAREN)) {
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
