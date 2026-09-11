package me.rkt.ast

import me.rkt.source.SourceSpan

sealed interface Declaration : AstNode

data class ImportDeclaration(
    val path: List<String>,
    override val span: SourceSpan
) : Declaration

sealed interface ClassMember : AstNode

data class Parameter(
    val name: String,
    val type: TypeReference,
    override val span: SourceSpan
) : AstNode

data class FunctionDeclaration(
    val name: String,
    val parameters: List<Parameter>,
    val returnType: TypeReference,
    val body: BlockStatement,
    override val span: SourceSpan,
    val owner: String? = null
) : Declaration, ClassMember

data class FieldDeclaration(
    val mutable: Boolean,
    val name: String,
    val type: TypeReference,
    override val span: SourceSpan
) : ClassMember

data class ClassDeclaration(
    val name: String,
    val members: List<ClassMember>,
    override val span: SourceSpan
) : Declaration

data class ObjectDeclaration(
    val name: String,
    val members: List<ClassMember>,
    override val span: SourceSpan
) : Declaration
