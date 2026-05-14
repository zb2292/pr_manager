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
        val key = Key(filePath, line, side)
        val list = comments.getOrPut(key) { mutableListOf() }
        val id = UUID.randomUUID().toString()
        val nextFloorNum = (list.maxOfOrNull { it.floorNum ?: 0 } ?: 0) + 1
        val comment = LineComment(
            id = id,
            filePath = filePath,
            line = line,
            side = side,
            content = content,
            author = author,
            createdAt = System.currentTimeMillis(),
            parentId = null,
            rootId = id,
            floorNum = nextFloorNum,
            resolved = false
        )
        list.add(comment)
        notifyChanged()
        return comment
    }

    fun addReply(
        filePath: String,
        line: Int,
        side: Side,
        parentId: String,
        content: String,
        author: String,
        rootId: String = parentId,
        replyFloorNum: Int? = null
    ): LineComment {
        val key = Key(filePath, line, side)
        val list = comments.getOrPut(key) { mutableListOf() }
        val id = UUID.randomUUID().toString()
        val nextFloorNum = (list.maxOfOrNull { it.floorNum ?: 0 } ?: 0) + 1
        val comment = LineComment(
            id = id,
            filePath = filePath,
            line = line,
            side = side,
            content = content,
            author = author,
            createdAt = System.currentTimeMillis(),
            parentId = parentId,
            rootId = rootId,
            floorNum = nextFloorNum,
            replyFloorNum = replyFloorNum,
            resolved = false
        )
        list.add(comment)
        notifyChanged()
        return comment
    }

    fun replaceForFile(filePath: String, side: Side, newComments: List<LineComment>) {
        val keysToRemove = comments.keys.filter { it.filePath == filePath && it.side == side }
        keysToRemove.forEach { comments.remove(it) }
        newComments.forEach { comment ->
            val key = Key(comment.filePath, comment.line, comment.side)
            val list = comments.getOrPut(key) { mutableListOf() }
            list.add(comment)
        }
        notifyChanged()
    }

    fun replaceForSide(side: Side, newComments: List<LineComment>) {
        val keysToRemove = comments.keys.filter { it.side == side }
        keysToRemove.forEach { comments.remove(it) }
        val grouped = newComments.groupBy { Key(it.filePath, it.line, it.side) }
        grouped.forEach { (key, items) ->
            comments[key] = items.toMutableList()
        }
        notifyChanged()
    }

    fun replaceAll(newComments: List<LineComment>) {
        comments.clear()
        val grouped = newComments.groupBy { Key(it.filePath, it.line, it.side) }
        grouped.forEach { (key, items) ->
            comments[key] = items.toMutableList()
        }
        notifyChanged()
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
