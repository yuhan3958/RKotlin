package me.rkt

import me.rkt.ast.AstModule
import me.rkt.diagnostic.DiagnosticReporter
import me.rkt.lexer.Lexer
import me.rkt.parser.Parser
import me.rkt.source.SourceFile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class LoadedModules(
    val modules: List<AstModule>,
    val imports: Map<Path, List<Path>>
)

class ModuleLoader(private val standardRoot: Path) {
    fun load(root: Path, diagnostics: DiagnosticReporter): LoadedModules {
        val visited = linkedSetOf<Path>()
        val modules = mutableListOf<AstModule>()
        val imports = linkedMapOf<Path, MutableList<Path>>()

        fun loadModule(path: Path) {
            val normalized = path.toAbsolutePath().normalize()
            if (!visited.add(normalized)) return
            val source = if (Files.exists(normalized)) SourceFile.load(normalized) else {
                val resource = standardResource(normalized) ?: throw IOException("cannot find source module '$normalized'")
                val text = javaClass.classLoader.getResourceAsStream(resource)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    ?: throw IOException("cannot find source module '$normalized'")
                SourceFile(normalized, text)
            }
            val ast = Parser(Lexer(source, diagnostics).lex(), diagnostics).parseModule()
            modules += ast
            val dependencies = imports.getOrPut(normalized) { mutableListOf() }
            for (declaration in ast.imports) {
                if (declaration.path.lastOrNull() == "*") {
                    val directory = resolveImportDirectory(normalized, declaration.path.dropLast(1))
                    if (directory == null) {
                        diagnostics.error(
                            declaration.span,
                            "cannot find import directory '${declaration.path.dropLast(1).joinToString(".")}'"
                        )
                    } else {
                        Files.list(directory).use { files ->
                            files.filter { it.fileName.toString().endsWith(".rk") }
                                .sorted()
                                .forEach {
                                    val imported = it.toAbsolutePath().normalize()
                                    dependencies.add(imported)
                                    loadModule(imported)
                                }
                        }
                    }
                    continue
                }
                val imported = resolveImport(normalized, declaration.path)
                if (imported == null) {
                    diagnostics.error(declaration.span, "cannot find imported module '${declaration.path.joinToString(".")}'")
                } else {
                    dependencies.add(imported)
                    loadModule(imported)
                }
            }
        }

        val normalizedRoot = root.toAbsolutePath().normalize()
        loadModule(normalizedRoot)
        val core = resolveImport(normalizedRoot, listOf("rkotlin", "core"))
            ?: throw IOException("cannot find standard library module 'rkotlin.core'")
        loadModule(core)
        // Preserve implicit dependencies as well as explicit imports for code generation.
        imports.forEach { (path, dependencies) ->
            if (path != core && core !in dependencies) dependencies.add(core)
        }
        diagnostics.throwIfErrors()
        return LoadedModules(modules, imports.mapValues { it.value.distinct() })
    }

    private fun resolveImport(source: Path, path: List<String>): Path? {
        val relative = path.joinToString(java.io.File.separator) + ".rk"
        return sequenceOf(source.parent, standardRoot)
            .map { it.resolve(relative).toAbsolutePath().normalize() }
            .firstOrNull { candidate ->
                Files.exists(candidate) || standardResource(candidate)?.let {
                    javaClass.classLoader.getResource(it) != null
                } == true
            }
    }

    private fun resolveImportDirectory(source: Path, path: List<String>): Path? {
        val relative = path.joinToString(java.io.File.separator)
        return sequenceOf(source.parent, standardRoot)
            .map { it.resolve(relative).toAbsolutePath().normalize() }
            .firstOrNull { Files.isDirectory(it) }
    }

    private fun standardResource(path: Path): String? {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(standardRoot)) return null
        return standardRoot.relativize(normalized).joinToString("/")
    }
}
