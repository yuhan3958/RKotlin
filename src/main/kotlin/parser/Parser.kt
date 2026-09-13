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
                    check(TokenType.PUBLIC) || check(TokenType.PRIVATE) -> {
                        val visibility = parseVisibility()
                        when {
                            check(TokenType.NATIVE) -> parseNativeDeclaration(visibility)
                            check(TokenType.CLASS) -> parseClassDeclaration(visibility)
                            check(TokenType.OBJECT) -> parseObjectDeclaration(visibility)
                            else -> parseFunctionDeclaration(visibility = visibility)
                        }
                    }
                    check(TokenType.CLASS) -> parseClassDeclaration()
                    check(TokenType.OBJECT) -> parseObjectDeclaration()
                    check(TokenType.NATIVE) -> parseNativeDeclaration(Visibility.PUBLIC)
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

    private fun parseNativeDeclaration(visibility: Visibility): Declaration {
        val start = expect(TokenType.NATIVE)
        if (match(TokenType.TYPE)) {
            diagnostics.fail(start.span, "native types are not part of RKotlin")
        }
        return parseFunctionDeclaration(visibility = visibility, nativeStart = start)
    }

    private fun parseImport(start: Token): ImportDeclaration {
        val path = mutableListOf<String>()
        val first = expect(TokenType.IDENTIFIER)
        path += first.text
        var end = first.span
        while (match(TokenType.DOT)) {
            if (match(TokenType.STAR)) {
                path += "*"
                end = previous().span
                break
            }
            val part = expect(TokenType.IDENTIFIER)
            path += part.text
            end = part.span
        }
        match(TokenType.SEMICOLON)
        return ImportDeclaration(path, start.span.merge(end))
    }

    private fun parseClassDeclaration(visibility: Visibility = Visibility.PUBLIC): ClassDeclaration =
        parseTypeDeclaration(isObject = false, visibility = visibility) as ClassDeclaration

    private fun parseObjectDeclaration(visibility: Visibility = Visibility.PUBLIC): ObjectDeclaration =
        parseTypeDeclaration(isObject = true, visibility = visibility) as ObjectDeclaration

    private fun parseTypeDeclaration(isObject: Boolean, visibility: Visibility): Declaration {
        val start = advance()
        val name = expect(TokenType.IDENTIFIER)
        val typeParameters = mutableListOf<String>()
        if (match(TokenType.LESS)) {
            do { typeParameters += expect(TokenType.IDENTIFIER).text } while (match(TokenType.COMMA))
            expect(TokenType.GREATER)
        }
        val baseType = if (match(TokenType.COLON)) parseTypeReference() else null
        if (isObject && baseType != null) diagnostics.fail(baseType.span, "objects cannot declare a superclass")
        expect(TokenType.LBRACE)
        val members = mutableListOf<ClassMember>()
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            val memberVisibility = parseVisibility()
            val overriding = match(TokenType.OVERRIDE)
            if (overriding && !check(TokenType.FUN)) diagnostics.fail(current().span, "override requires a method")
            members += when {
                check(TokenType.VAR) || check(TokenType.VAL) ->
                    parseFieldDeclaration(advance(), memberVisibility)
                check(TokenType.FUN) ->
                    parseFunctionDeclaration(name.text, memberVisibility, overriding = overriding)
                check(TokenType.NATIVE) -> {
                    val nativeStart = advance()
                    parseFunctionDeclaration(name.text, memberVisibility, nativeStart)
                }
                !isObject && check(TokenType.IDENTIFIER) && current().text == name.text &&
                    peek(1).type == TokenType.LPAREN ->
                    parseConstructorDeclaration(name.text, memberVisibility)
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
            ObjectDeclaration(name.text, members, span, visibility)
        } else {
            ClassDeclaration(name.text, members, span, visibility, typeParameters, baseType)
        }
    }

    private fun parseConstructorDeclaration(
        className: String,
        visibility: Visibility
    ): ConstructorDeclaration {
        val start = expect(TokenType.IDENTIFIER)
        expect(TokenType.LPAREN)
        val parameters = parseParameters()
        expect(TokenType.RPAREN)
        val body = parseBlock()
        return ConstructorDeclaration(
            className,
            parameters,
            body,
            start.span.merge(body.span),
            visibility
        )
    }

    private fun parseFieldDeclaration(keyword: Token, visibility: Visibility): FieldDeclaration {
        val name = expect(TokenType.IDENTIFIER)
        expect(TokenType.COLON)
        val type = parseTypeReference()
        expect(TokenType.SEMICOLON)
        return FieldDeclaration(
            keyword.type == TokenType.VAR,
            name.text,
            type,
            keyword.span.merge(type.span),
            visibility
        )
    }

    private fun parseFunctionDeclaration(
        owner: String? = null,
        visibility: Visibility = Visibility.PUBLIC,
        nativeStart: Token? = null,
        overriding: Boolean = false
    ): FunctionDeclaration {
        val start = nativeStart ?: current()
        expect(TokenType.FUN)
        val name = expect(TokenType.IDENTIFIER)

        expect(TokenType.LPAREN)
        val parameters = parseParameters()
        expect(TokenType.RPAREN)
        val returnType = if (match(TokenType.COLON)) parseTypeReference()
            else TypeReference("Void", name.span)
        val body = if (nativeStart == null) parseBlock() else null
        val nativeTarget = if (nativeStart != null) {
            expect(TokenType.EQUAL)
            expect(TokenType.STRING).also { match(TokenType.SEMICOLON) }.text
        } else null
        val end = body?.span ?: previous().span

        return FunctionDeclaration(
            name.text,
            parameters,
            returnType,
            body,
            start.span.merge(end),
            owner,
            visibility,
            nativeTarget,
            overriding
        )
    }

    private fun parseParameters(): List<Parameter> {
        val parameters = mutableListOf<Parameter>()
        if (!check(TokenType.RPAREN)) {
            do {
                val name = expect(TokenType.IDENTIFIER)
                expect(TokenType.COLON)
                val type = parseTypeReference()
                parameters += Parameter(name.text, type, name.span.merge(type.span))
            } while (match(TokenType.COMMA))
        }
        return parameters
    }

    private fun parseTypeReference(): TypeReference {
        if (match(TokenType.STAR)) {
            return TypeReference("*", previous().span)
        }
        val token = expect(TokenType.IDENTIFIER)
        val arguments = mutableListOf<TypeReference>()
        if (match(TokenType.LESS)) {
            do {
                arguments += parseTypeReference()
            } while (match(TokenType.COMMA))
            expect(TokenType.GREATER)
        }
        val nullable = match(TokenType.QUESTION)
        val end = previous().span
        return TypeReference(token.text, token.span.merge(end), nullable, arguments)
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
            val expression = if (check(TokenType.RBRACE) || check(TokenType.SEMICOLON)) {
                VoidLiteral(start.span)
            } else parseExpression()
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
        val iterable = if (match(TokenType.DOT_DOT)) null else rangeStart
        val rangeEnd = if (iterable == null) parseExpression() else rangeStart
        expect(TokenType.RPAREN)
        val body = parseBlock()
        return ForStatement(
            name.text,
            rangeStart,
            rangeEnd,
            body,
            start.span.merge(body.span),
            iterable
        )
    }

    private fun parseVariableDeclaration(keyword: Token): VariableDeclarationStatement {
        val mutable = keyword.type == TokenType.VAR

        val name = expect(TokenType.IDENTIFIER)

        val type = if (match(TokenType.COLON)) parseTypeReference() else null

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

    private fun parseExpression(): Expression = parseElvis()

    private fun parseElvis(): Expression {
        val expression = parseLogicalOr()
        if (!match(TokenType.ELVIS)) return expression
        val fallback = parseElvis()
        return ElvisExpression(expression, fallback, expression.span.merge(fallback.span))
    }

    private fun parseLogicalOr(): Expression {
        var expression = parseLogicalAnd()
        while (match(TokenType.OR_OR)) {
            val right = parseLogicalAnd()
            expression = BinaryExpression(expression, BinaryOperator.OR, right, expression.span.merge(right.span))
        }
        return expression
    }

    private fun parseLogicalAnd(): Expression {
        var expression = parseLogicalXor()
        while (match(TokenType.AND_AND)) {
            val right = parseLogicalXor()
            expression = BinaryExpression(expression, BinaryOperator.AND, right, expression.span.merge(right.span))
        }
        return expression
    }

    private fun parseLogicalXor(): Expression {
        var expression = parseComparison()
        while (match(TokenType.CARET)) {
            val right = parseComparison()
            expression = BinaryExpression(expression, BinaryOperator.XOR, right, expression.span.merge(right.span))
        }
        return expression
    }

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
        var expression = parseUnary()

        while (check(TokenType.STAR) || check(TokenType.SLASH)) {
            val operator = advance()
            val right = parseUnary()

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
            if (expression is NameExpression && expression.name == "alloc" && match(TokenType.LESS)) {
                val elementType = parseTypeReference()
                expect(TokenType.GREATER)
                expect(TokenType.LPAREN)
                val length = parseExpression()
                val end = expect(TokenType.RPAREN)
                expression = AllocationExpression(
                    elementType,
                    length,
                    expression.span.merge(end.span)
                )
            } else if (match(TokenType.LPAREN)) {
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
            } else if (match(TokenType.LBRACKET)) {
                val index = parseExpression()
                val end = expect(TokenType.RBRACKET)
                expression = IndexExpression(
                    expression,
                    index,
                    expression.span.merge(end.span)
                )
            } else if (check(TokenType.DOT) || check(TokenType.SAFE_DOT)) {
                val safe = advance().type == TokenType.SAFE_DOT
                val name = expect(TokenType.IDENTIFIER)
                expression = MemberAccessExpression(
                    expression,
                    name.text,
                    expression.span.merge(name.span),
                    safe
                )
            } else {
                break
            }
        }

        return expression
    }

    private fun parseUnary(): Expression {
        if (match(TokenType.BANG)) {
            val start = previous()
            val operand = parseUnary()
            return UnaryExpression(UnaryOperator.NOT, operand, start.span.merge(operand.span))
        }
        if (match(TokenType.MINUS)) {
            val start = previous()
            if (check(TokenType.INTEGER) && peek(1).type !in setOf(TokenType.DOT, TokenType.SAFE_DOT, TokenType.LPAREN)) {
                val number = advance()
                val value = ("-" + number.text).toIntOrNull()
                    ?: diagnostics.fail(number.span, "integer literal is outside Int32 range")
                return IntegerLiteral(value, start.span.merge(number.span))
            }
            val operand = parseUnary()
            return BinaryExpression(IntegerLiteral(0, start.span), BinaryOperator.SUB, operand, start.span.merge(operand.span))
        }
        return parseCall()
    }

    private fun parsePrimary(): Expression {
        if (match(TokenType.TRUE)) return BooleanLiteral(true, previous().span)
        if (match(TokenType.FALSE)) return BooleanLiteral(false, previous().span)
        if (match(TokenType.INTEGER)) {
            val token = previous()
            return IntegerLiteral(token.text.toIntOrNull()
                ?: diagnostics.fail(token.span, "integer literal is outside Int32 range"), token.span)
        }

        if (match(TokenType.STRING)) {
            val token = previous()
            return StringLiteral(token.text, token.span)
        }

        if (match(TokenType.NULL)) return NullLiteral(previous().span)

        if (match(TokenType.THIS)) {
            val token = previous()
            return NameExpression("this", token.span)
        }

        if (match(TokenType.SUPER)) return NameExpression("super", previous().span)

        if (match(TokenType.NEW)) {
            val start = previous()
            val type = parseTypeReference()
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

    private fun parseVisibility(): Visibility = when {
        match(TokenType.PRIVATE) -> Visibility.PRIVATE
        match(TokenType.PROTECTED) -> Visibility.PROTECTED
        match(TokenType.PUBLIC) -> Visibility.PUBLIC
        else -> Visibility.PUBLIC
    }
}
