package com.gitee.prviewer.comment

import com.intellij.diff.util.Side
import java.util.UUID

object LineCommentStore {
    private data class Key(val filePath: String, val line: Int, val side: Side)

    private val comments = mutableMapOf<Key, MutableList<LineComment>>()
    private val listeners = mutableSetOf<() -> Unit>()

    fun getComments(filePath: String, line: Int, side: Side): List<LineComment> {
        return comments[Key(filePath, line, side)]?.toList() ?: emptyList()
    }

    fun hasComments(filePath: String, line: Int, side: Side): Boolean {
        return comments[Key(filePath, line, side)]?.isNotEmpty() == true
    }

    fun addComment(filePath: String, line: Int, side: Side, content: String, author: String): LineComment {
        val comment = LineComment(
            id = UUID.randomUUID().toString(),
            filePath = filePath,
            line = line,
            side = side,
            content = content,
            author = author,
            createdAt = System.currentTimeMillis(),
            parentId = null,
            resolved = false
        )
        val key = Key(filePath, line, side)
        val list = comments.getOrPut(key) { mutableListOf() }
        list.add(comment)
        notifyChanged()
        return comment
    }

    fun removeComment(filePath: String, line: Int, side: Side, commentId: String) {
        val key = Key(filePath, line, side)
        val list = comments[key] ?: return
        val toRemove = mutableSetOf(commentId)
        var changed = true
        while (changed) {
            changed = false
            list.forEach { item ->
                if (item.parentId != null && item.parentId in toRemove && item.id !in toRemove) {
                    toRemove.add(item.id)
                    changed = true
                }
            }
        }
        list.removeIf { it.id in toRemove }
        if (list.isEmpty()) {
            comments.remove(key)
        }
        notifyChanged()
    }

    fun addReply(filePath: String, line: Int, side: Side, parentId: String, content: String, author: String): LineComment {
        val comment = LineComment(
            id = UUID.randomUUID().toString(),
            filePath = filePath,
            line = line,
            side = side,
            content = content,
            author = author,
            createdAt = System.currentTimeMillis(),
            parentId = parentId,
            resolved = false
        )
        val key = Key(filePath, line, side)
        val list = comments.getOrPut(key) { mutableListOf() }
        list.add(comment)
        notifyChanged()
        return comment
    }

    fun resolveComment(filePath: String, line: Int, side: Side, commentId: String) {
        val key = Key(filePath, line, side)
        val list = comments[key] ?: return
        val index = list.indexOfFirst { it.id == commentId }
        if (index < 0) return
        val old = list[index]
        if (old.resolved) return
        list[index] = old.copy(resolved = true)
        notifyChanged()
    }

    fun resolveAllOnLine(filePath: String, line: Int, side: Side) {
        val key = Key(filePath, line, side)
        val list = comments[key] ?: return
        var changed = false
        for (i in list.indices) {
            val item = list[i]
            if (!item.resolved) {
                list[i] = item.copy(resolved = true)
                changed = true
            }
        }
        if (changed) {
            notifyChanged()
        }
    }

    fun updateComment(filePath: String, line: Int, side: Side, commentId: String, content: String) {
        val key = Key(filePath, line, side)
        val list = comments[key] ?: return
        val index = list.indexOfFirst { it.id == commentId }
        if (index < 0) return
        val old = list[index]
        if (old.content == content) return
        list[index] = old.copy(content = content)
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it.invoke() }
    }
}
