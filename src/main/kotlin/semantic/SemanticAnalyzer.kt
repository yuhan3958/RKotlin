package me.rkt.semantic

import me.rkt.ast.AstModule
import me.rkt.diagnostic.DiagnosticReporter

class SemanticAnalyzer(
    diagnostics: DiagnosticReporter
) {
    private val context = SemanticContext(diagnostics)
    private val typeResolver = SemanticTypeResolver(context)
    private val registration = SemanticRegistration(context, typeResolver)
    private val binder = SemanticBinder(context, typeResolver)

    fun analyze(module: AstModule): SemanticModule {
        registration.registerDeclarations(module)
        context.diagnostics.throwIfErrors()
        registration.complete(module)

        val boundFunctions = binder.bind(module)

        context.diagnostics.throwIfErrors()
        return SemanticModule(boundFunctions, context.classes.values.toList(), module.imports)
    }
}
