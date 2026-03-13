package com.gitee.prviewer.comment

import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

class DiffEditorBinder(
    private val project: Project,
    private val commentManager: LineCommentManager
) {
    fun bindNextDiff(filePath: String) {
        val editorFactory = EditorFactory.getInstance()
        var boundCount = 0

        val disposable = Disposer.newDisposable("prviewer.diffEditorBinder")
        val listener = object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (!DiffUtil.isDiffEditor(editor)) return
                if (editor.project != project) return

                val side = resolveSide(editor)
                commentManager.registerEditor(editor, filePath, side)
                boundCount += 1
                if (boundCount >= 2) {
                    Disposer.dispose(disposable)
                }
            }
        }

        editorFactory.addEditorFactoryListener(listener, disposable)
    }

    private fun resolveSide(editor: Editor): Side {
        return editor.getUserData(DiffUserDataKeys.MASTER_SIDE) ?: Side.LEFT
    }
}
