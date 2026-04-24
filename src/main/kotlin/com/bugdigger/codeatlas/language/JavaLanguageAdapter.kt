package com.bugdigger.codeatlas.language

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod

/**
 * Extracts Java classes, interfaces, enums, annotations, constructors, and methods as [CodeChunk]s.
 * Inner classes are walked recursively.
 */
class JavaLanguageAdapter : LanguageAdapter {
    override val languageId: String = "java"

    override fun supports(file: PsiFile): Boolean = file is PsiJavaFile

    override fun extract(file: PsiFile): List<CodeChunk> {
        if (file !is PsiJavaFile) return emptyList()
        val url = file.virtualFile?.url ?: return emptyList()
        val out = mutableListOf<CodeChunk>()
        for (top in file.classes) collectClass(top, url, out)
        return out
    }

    private fun collectClass(clazz: PsiClass, url: String, out: MutableList<CodeChunk>) {
        // Skip synthetic/compiler-generated declarations (e.g., an enum's implicit values()/valueOf()),
        // identified by a missing textRange.
        if (clazz.textRange == null) return
        out += classChunk(clazz, url)
        for (method in clazz.methods) {
            if (method.textRange != null) out += methodChunk(method, clazz, url)
        }
        for (inner in clazz.innerClasses) collectClass(inner, url, out)
    }

    private fun classChunk(clazz: PsiClass, url: String): CodeChunk {
        val kind = when {
            clazz.isAnnotationType -> ChunkKind.ANNOTATION
            clazz.isInterface -> ChunkKind.INTERFACE
            clazz.isEnum -> ChunkKind.ENUM
            else -> ChunkKind.CLASS
        }
        val fqName = clazz.qualifiedName ?: clazz.name.orEmpty()
        val signature = firstLine(clazz.text) ?: fqName
        val containerFqn = clazz.containingClass?.qualifiedName
            ?: (clazz.containingFile as? PsiJavaFile)?.packageName?.takeIf { it.isNotEmpty() }
        return CodeChunk(
            id = "java:$fqName",
            qualifiedName = fqName,
            kind = kind,
            signature = signature,
            docComment = clazz.docComment?.text,
            language = languageId,
            virtualFileUrl = url,
            startOffset = clazz.textRange.startOffset,
            endOffset = clazz.textRange.endOffset,
            containerFqn = containerFqn,
            contentHash = sha256Hex(clazz.text),
        )
    }

    private fun methodChunk(method: PsiMethod, owner: PsiClass, url: String): CodeChunk {
        val ownerFq = owner.qualifiedName ?: owner.name.orEmpty()
        val fqName = "$ownerFq.${method.name}"
        val kind = if (method.isConstructor) ChunkKind.CONSTRUCTOR else ChunkKind.METHOD
        return CodeChunk(
            id = "java:$fqName${paramSuffix(method)}",
            qualifiedName = fqName,
            kind = kind,
            signature = buildSignature(method),
            docComment = method.docComment?.text,
            language = languageId,
            virtualFileUrl = url,
            startOffset = method.textRange.startOffset,
            endOffset = method.textRange.endOffset,
            containerFqn = ownerFq,
            contentHash = sha256Hex(method.text),
        )
    }

    private fun buildSignature(method: PsiMethod): String {
        val ret = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        return if (method.isConstructor) "${method.name}($params)" else "$ret ${method.name}($params)"
    }

    private fun paramSuffix(method: PsiMethod): String =
        method.parameterList.parameters.joinToString(",", "(", ")") { it.type.presentableText }

    private fun firstLine(text: String): String? =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
}
