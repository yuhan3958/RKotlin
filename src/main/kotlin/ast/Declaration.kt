package me.rkt.ast

import me.rkt.source.SourceSpan

sealed interface Declaration : AstNode

enum class Visibility { PUBLIC, PROTECTED, PRIVATE }

data class ImportDeclaration(
    val path: List<String>,
    override val span: SourceSpan
) : Declaration

data class NativeTypeDeclaration(
    val name: String,
    val abi: String,
    override val span: SourceSpan,
    val visibility: Visibility = Visibility.PUBLIC
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
    val body: BlockStatement?,
    override val span: SourceSpan,
    val owner: String? = null,
    val visibility: Visibility = Visibility.PUBLIC,
    val nativeTarget: String? = null,
    val overriding: Boolean = false
) : Declaration, ClassMember

data class ConstructorDeclaration(
    val className: String,
    val parameters: List<Parameter>,
    val body: BlockStatement,
    override val span: SourceSpan,
    val visibility: Visibility = Visibility.PUBLIC
) : ClassMember

data class FieldDeclaration(
    val mutable: Boolean,
    val name: String,
    val type: TypeReference,
    override val span: SourceSpan,
    val visibility: Visibility = Visibility.PUBLIC
) : ClassMember

data class ClassDeclaration(
    val name: String,
    val members: List<ClassMember>,
    override val span: SourceSpan,
    val visibility: Visibility = Visibility.PUBLIC,
    val typeParameters: List<String> = emptyList(),
    val baseType: TypeReference? = null,
    val interfaceTypes: List<TypeReference> = emptyList(),
    val isInterface: Boolean = false
) : Declaration

data class ObjectDeclaration(
    val name: String,
    val members: List<ClassMember>,
    override val span: SourceSpan,
    val visibility: Visibility = Visibility.PUBLIC
) : Declaration
