package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.SimpleListCellRenderer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Resolves a [CodeChunk] back to its declaring PSI element and runs
 * [ReferencesSearch] against it, then shows the hits in a navigable popup.
 *
 * Resolution uses `findElementAt(chunk.startOffset)` plus
 * [PsiTreeUtil.getParentOfType] to walk up to the nearest named declaration
 * (Java class/method, Kotlin class-or-object/named function). Search runs in a
 * [Task.Backgroundable] under a read action; results are shown on the EDT.
 */
class FindUsagesController(private val project: Project) {

    fun findUsagesFor(chunk: CodeChunk) {
        val task = object : Task.Backgroundable(
            project,
            "CodeAtlas: finding usages of ${chunk.qualifiedName}",
            true,
        ) {
            private var resolved = false
            private var rows: List<UsageRow> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                val element = ReadAction.compute<PsiElement?, RuntimeException> {
                    resolveTarget(chunk)
                } ?: return
                resolved = true
                rows = ReadAction.compute<List<UsageRow>, RuntimeException> {
                    ReferencesSearch
                        .search(element, GlobalSearchScope.projectScope(project))
                        .findAll()
                        .mapNotNull { rowFor(it.element) }
                }
            }

            override fun onSuccess() {
                when {
                    !resolved -> Messages.showWarningDialog(
                        project,
                        "Could not resolve a PSI target for ${chunk.qualifiedName}.",
                        "CodeAtlas",
                    )
                    rows.isEmpty() -> Messages.showInfoMessage(
                        project,
                        "No usages of ${chunk.qualifiedName} found in project sources.",
                        "CodeAtlas",
                    )
                    else -> showPopup(chunk, rows)
                }
            }
        }
        task.queue()
    }

    private fun resolveTarget(chunk: CodeChunk): PsiElement? {
        val vfile = VirtualFileManager.getInstance().findFileByUrl(chunk.virtualFileUrl) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vfile) ?: return null
        val leaf = psiFile.findElementAt(chunk.startOffset) ?: return null
        return PsiTreeUtil.getParentOfType(
            leaf,
            PsiClass::class.java,
            PsiMethod::class.java,
            KtClassOrObject::class.java,
            KtNamedFunction::class.java,
        )
    }

    private fun rowFor(element: PsiElement): UsageRow? {
        val vfile = element.containingFile?.virtualFile ?: return null
        val offset = element.textOffset
        val doc = element.containingFile?.viewProvider?.document
        val line = doc?.getLineNumber(offset)?.plus(1) ?: 0
        val snippet = element.text?.replace('\n', ' ')?.take(80).orEmpty()
        return UsageRow(vfile, offset, line, snippet)
    }

    private fun showPopup(chunk: CodeChunk, rows: List<UsageRow>) {
        ApplicationManager.getApplication().invokeLater {
            val renderer = SimpleListCellRenderer.create<UsageRow>("") { row ->
                "${row.file.name}:${row.line} — ${row.snippet}"
            }
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(rows)
                .setTitle("Usages of ${chunk.qualifiedName}")
                .setRenderer(renderer)
                .setMovable(true)
                .setResizable(true)
                .setItemChosenCallback { row ->
                    OpenFileDescriptor(project, row.file, row.offset).navigate(true)
                }
                .createPopup()
                .showInFocusCenter()
        }
    }

    private data class UsageRow(
        val file: VirtualFile,
        val offset: Int,
        val line: Int,
        val snippet: String,
    )
}
