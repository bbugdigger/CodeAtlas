package com.bugdigger.codeatlas.language

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Extracts Kotlin classes, objects, interfaces, and named functions as [CodeChunk]s.
 * One level of nested declarations is walked; deeper nesting is ignored in Phase 1.
 */
class KotlinLanguageAdapter : LanguageAdapter {
    override val languageId: String = "kotlin"

    override fun supports(file: PsiFile): Boolean = file is KtFile

    override fun extract(file: PsiFile): List<CodeChunk> {
        if (file !is KtFile) return emptyList()
        val url = file.virtualFile?.url ?: return emptyList()
        val out = mutableListOf<CodeChunk>()
        for (decl in file.declarations) {
            when (decl) {
                is KtClassOrObject -> {
                    out += classChunk(decl, url)
                    for (member in decl.declarations) {
                        if (member is KtNamedFunction) out += functionChunk(member, url)
                    }
                }
                is KtNamedFunction -> out += functionChunk(decl, url)
            }
        }
        return out
    }

    private fun classChunk(decl: KtClassOrObject, url: String): CodeChunk {
        val kind = when {
            decl is KtObjectDeclaration -> ChunkKind.OBJECT
            decl is KtClass && decl.isInterface() -> ChunkKind.INTERFACE
            decl is KtClass && decl.isEnum() -> ChunkKind.ENUM
            decl is KtClass && decl.isAnnotation() -> ChunkKind.ANNOTATION
            else -> ChunkKind.CLASS
        }
        val fqName = decl.fqName?.asString() ?: decl.name.orEmpty()
        val signature = firstLine(decl.text) ?: fqName
        val containerFqn = decl.containingKtFile.packageFqName.asString().takeIf { it.isNotEmpty() }
        return CodeChunk(
            id = "kotlin:$fqName",
            qualifiedName = fqName,
            kind = kind,
            signature = signature,
            docComment = findKDoc(decl)?.text,
            language = languageId,
            virtualFileUrl = url,
            startOffset = decl.textRange.startOffset,
            endOffset = decl.textRange.endOffset,
            containerFqn = containerFqn,
            contentHash = sha256Hex(decl.text),
        )
    }

    private fun functionChunk(fn: KtNamedFunction, url: String): CodeChunk {
        val owner = fn.containingClassOrObject
        val ownerFq = owner?.fqName?.asString()
        val pkg = fn.containingKtFile.packageFqName.asString().takeIf { it.isNotEmpty() }
        val name = fn.name.orEmpty()
        val fqName = when {
            ownerFq != null -> "$ownerFq.$name"
            pkg != null -> "$pkg.$name"
            else -> name
        }
        val kind = if (owner != null) ChunkKind.METHOD else ChunkKind.FUNCTION
        return CodeChunk(
            id = "kotlin:$fqName${paramSuffix(fn)}",
            qualifiedName = fqName,
            kind = kind,
            signature = buildSignature(fn),
            docComment = findKDoc(fn)?.text,
            language = languageId,
            virtualFileUrl = url,
            startOffset = fn.textRange.startOffset,
            endOffset = fn.textRange.endOffset,
            containerFqn = ownerFq ?: pkg,
            contentHash = sha256Hex(fn.text),
        )
    }

    private fun buildSignature(fn: KtNamedFunction): String {
        val name = fn.name ?: "<anonymous>"
        val params = fn.valueParameters.joinToString(", ") { p ->
            val pn = p.name ?: "_"
            val pt = p.typeReference?.text ?: "?"
            "$pn: $pt"
        }
        val ret = fn.typeReference?.text
        return if (ret != null) "fun $name($params): $ret" else "fun $name($params)"
    }

    private fun paramSuffix(fn: KtNamedFunction): String =
        fn.valueParameters.mapNotNull { it.typeReference?.text }.joinToString(",", "(", ")")

    private fun firstLine(text: String): String? =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()

    private fun findKDoc(decl: KtDeclaration): KDoc? =
        PsiTreeUtil.findChildOfType(decl, KDoc::class.java)
}
