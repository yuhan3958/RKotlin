package me.rkt.semantic

import me.rkt.ast.ConstructorDeclaration
import me.rkt.ast.FunctionDeclaration
import me.rkt.diagnostic.DiagnosticReporter
import java.nio.file.Path

class SemanticContext(
    val diagnostics: DiagnosticReporter
) {
    val globalScope = Scope()
    val classes = linkedMapOf<String, ClassSymbol>()
    val functions = linkedMapOf<FunctionDeclaration, FunctionSymbol>()
    val constructors = linkedMapOf<ConstructorDeclaration, FunctionSymbol>()
    val nativeTypes = linkedMapOf<String, RType>()
    val overloads = linkedMapOf<String, MutableList<FunctionSymbol>>()
    var typeParameters: Set<String> = emptySet()
    val libraryRoot = Path.of("src", "main", "rk", "rkotlin").toAbsolutePath().normalize()
}
