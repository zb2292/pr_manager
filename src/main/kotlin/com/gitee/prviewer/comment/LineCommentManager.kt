package com.gitee.prviewer.comment

import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.TextRange
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class LineCommentManager(private val project: Project) {
    companion object {
        private const val MAX_COMMENT_LENGTH = 200
        private const val MAX_COMMENT_HINT = "最多 200 字"
    }

    private val highlightersByEditor = WeakHashMap<Editor, MutableList<RangeHighlighter>>()
    private val commentHighlightersByEditorLine = WeakHashMap<Editor, MutableMap<Int, RangeHighlighter>>()
    private val hoverHighlighterByEditor = WeakHashMap<Editor, RangeHighlighter?>()
    private val hoveredLineByEditor = WeakHashMap<Editor, Int?>()
    private val remoteIssueLinesByFile = mutableMapOf<String, Set<Int>>()

    init {
        LineCommentStore.addListener { refreshAllEditors() }
    }

    private val editors = mutableSetOf<EditorContext>()

    fun registerEditor(editor: Editor, filePath: String, side: Side) {
        if (!DiffUtil.isDiffEditor(editor)) return

        val context = EditorContext(editor, filePath, side)
        if (editors.add(context)) {
            editor.addEditorMouseListener(CommentMouseListener(context))
            editor.addEditorMouseMotionListener(CommentMouseMotionListener(context))
            refreshEditor(context)
        }
    }

    fun updateIssueLines(filePath: String, lines: Set<Int>) {
        val normalized = normalizeFilePath(filePath)
        if (lines.isEmpty()) {
            remoteIssueLinesByFile.remove(normalized)
        } else {
            remoteIssueLinesByFile[normalized] = lines
        }
        refreshAllEditors()
    }

    private fun normalizeFilePath(path: String): String {
        return path.trim().replace('\\', '/').removePrefix("./")
    }

    private fun refreshAllEditors() {
        editors.forEach { refreshEditor(it) }
    }

    private fun refreshEditor(context: EditorContext) {
        val editor = context.editor
        updateHover(context, null)

        val existing = highlightersByEditor.remove(editor).orEmpty()
        existing.forEach { editor.markupModel.removeHighlighter(it) }
        commentHighlightersByEditorLine.remove(editor)
        hoverHighlighterByEditor.remove(editor)

        val lineMap = mutableMapOf<Int, RangeHighlighter>()
        val newHighlighters = mutableListOf<RangeHighlighter>()
        val lineCount = editor.document.lineCount
        val remoteIssueLines = remoteIssueLinesByFile[normalizeFilePath(context.filePath)].orEmpty()
        for (line in 0 until lineCount) {
            val roots = LineCommentStore.getComments(context.filePath, line, context.side)
                .filter { it.parentId == null }
            val hasRemoteIssue = remoteIssueLines.contains(line)
            if (roots.isEmpty() && !hasRemoteIssue) continue

            val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null)
            if (roots.isNotEmpty()) {
                val totalCount = roots.size
                val unresolvedCount = roots.count { !it.resolved }
                val allResolved = unresolvedCount == 0
                applyNormalLineRenderers(context, line, highlighter, unresolvedCount, totalCount, allResolved)
            } else if (context.side == Side.RIGHT) {
                highlighter.gutterIconRenderer = ExistingIssueGutterRenderer(context, line)
            }

            lineMap[line] = highlighter
            newHighlighters.add(highlighter)
        }
        if (newHighlighters.isNotEmpty()) {
            highlightersByEditor[editor] = newHighlighters
        }
        if (lineMap.isNotEmpty()) {
            commentHighlightersByEditorLine[editor] = lineMap
        }
    }

    private inner class CommentMouseListener(private val context: EditorContext) : EditorMouseListener {
        override fun mouseClicked(event: EditorMouseEvent) {
            if (context.side != Side.RIGHT) return
            if (event.area != EditorMouseEventArea.LINE_MARKERS_AREA && event.area != EditorMouseEventArea.LINE_NUMBERS_AREA) {
                return
            }
            val editor = event.editor
            val logical = editor.xyToLogicalPosition(event.mouseEvent.point)
            val line = logical.line
            showPopup(editor, context.filePath, line, context.side)
        }
    }

    private inner class CommentMouseMotionListener(private val context: EditorContext) : EditorMouseMotionListener {
        override fun mouseMoved(event: EditorMouseEvent) {
            val editor = event.editor
            if (event.area != EditorMouseEventArea.LINE_MARKERS_AREA && event.area != EditorMouseEventArea.LINE_NUMBERS_AREA) {
                updateHover(context, null)
                return
            }
            val logical = editor.xyToLogicalPosition(event.mouseEvent.point)
            val line = logical.line
            if (line < 0 || line >= editor.document.lineCount) {
                updateHover(context, null)
                return
            }
            updateHover(context, line)
        }

        override fun mouseDragged(event: EditorMouseEvent) {
        }
    }

    private fun updateHover(context: EditorContext, line: Int?) {
        val editor = context.editor
        val prev = hoveredLineByEditor[editor]
        if (prev == line) return

        if (prev != null) {
            clearHoverForLine(context, prev)
        }
        if (line != null) {
            applyHoverForLine(context, line)
        }
        hoveredLineByEditor[editor] = line
    }

    private fun clearHoverForLine(context: EditorContext, line: Int) {
        val editor = context.editor
        val commentLineMap = commentHighlightersByEditorLine[editor]
        val commentHighlighter = commentLineMap?.get(line)
        if (commentHighlighter != null) {
            val roots = LineCommentStore.getComments(context.filePath, line, context.side)
                .filter { it.parentId == null }
            if (roots.isNotEmpty()) {
                val totalCount = roots.size
                val unresolvedCount = roots.count { !it.resolved }
                val allResolved = totalCount > 0 && unresolvedCount == 0
                applyNormalLineRenderers(context, line, commentHighlighter, unresolvedCount, totalCount, allResolved)
            } else if (context.side == Side.RIGHT) {
                val hasRemoteIssue = remoteIssueLinesByFile[normalizeFilePath(context.filePath)]?.contains(line) == true
                if (hasRemoteIssue) {
                    commentHighlighter.gutterIconRenderer = ExistingIssueGutterRenderer(context, line)
                    commentHighlighter.lineMarkerRenderer = null
                }
            }
            return
        }

        val hover = hoverHighlighterByEditor.remove(editor)
        if (hover != null) {
            editor.markupModel.removeHighlighter(hover)
        }
    }

    private fun applyHoverForLine(context: EditorContext, line: Int) {
        if (context.side != Side.RIGHT) return
        val editor = context.editor
        val commentLineMap = commentHighlightersByEditorLine[editor]
        val commentHighlighter = commentLineMap?.get(line)
        if (commentHighlighter != null) {
            return
        }

        val hover = hoverHighlighterByEditor.remove(editor)
        if (hover != null) {
            editor.markupModel.removeHighlighter(hover)
        }

        val document = editor.document
        val startOffset = document.getLineStartOffset(line)
        val endOffset = document.getLineEndOffset(line)
        val lineText = document.getText(TextRange(startOffset, endOffset))
        if (lineText.isBlank()) {
            return
        }

        val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX + 1, null)
        highlighter.gutterIconRenderer = HoverAddGutterRenderer(context, line)
        hoverHighlighterByEditor[editor] = highlighter
    }

    private fun applyNormalLineRenderers(
        context: EditorContext,
        line: Int,
        highlighter: RangeHighlighter,
        unresolvedCount: Int,
        totalCount: Int,
        allResolved: Boolean
    ) {
        val icon = AllIcons.General.Balloon
        highlighter.gutterIconRenderer = LineCommentGutterRenderer(context, line, icon, unresolvedCount, totalCount, allResolved)
        highlighter.lineMarkerRenderer = CommentCountLineMarkerRenderer(icon, unresolvedCount, totalCount, allResolved)
    }

    private inner class ExistingIssueGutterRenderer(
        private val context: EditorContext,
        private val line: Int
    ) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.Balloon

        override fun getTooltipText(): String = "该行有评论"

        override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
                showPopup(editor, context.filePath, line, context.side)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExistingIssueGutterRenderer) return false
            return context.filePath == other.context.filePath &&
                context.side == other.context.side &&
                line == other.line
        }

        override fun hashCode(): Int {
            var result = context.filePath.hashCode()
            result = 31 * result + context.side.hashCode()
            result = 31 * result + line
            return result
        }
    }

    private inner class LineCommentGutterRenderer(
        private val context: EditorContext,
        private val line: Int,
        private val icon: Icon,
        private val unresolvedCount: Int,
        private val totalCount: Int,
        private val allResolved: Boolean
    ) : GutterIconRenderer() {
        override fun getIcon() = icon

        override fun getTooltipText(): String {
            val stateText = if (allResolved) "已解决" else "未解决"
            return "评论 $unresolvedCount/$totalCount - $stateText"
        }

        override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
                showPopup(editor, context.filePath, line, context.side)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LineCommentGutterRenderer) return false
            return context.filePath == other.context.filePath &&
                context.side == other.context.side &&
                line == other.line &&
                unresolvedCount == other.unresolvedCount &&
                totalCount == other.totalCount &&
                allResolved == other.allResolved
        }

        override fun hashCode(): Int {
            var result = context.filePath.hashCode()
            result = 31 * result + context.side.hashCode()
            result = 31 * result + line
            result = 31 * result + unresolvedCount
            result = 31 * result + totalCount
            result = 31 * result + allResolved.hashCode()
            return result
        }
    }

    private inner class HoverAddGutterRenderer(
        private val context: EditorContext,
        private val line: Int
    ) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.Add

        override fun getTooltipText(): String = "添加评论"

        override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
                showPopup(editor, context.filePath, line, context.side)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HoverAddGutterRenderer) return false
            return context.filePath == other.context.filePath && context.side == other.context.side && line == other.line
        }

        override fun hashCode(): Int {
            var result = context.filePath.hashCode()
            result = 31 * result + context.side.hashCode()
            result = 31 * result + line
            return result
        }
    }

    private class CommentCountLineMarkerRenderer(
        private val icon: Icon,
        private val unresolvedCount: Int,
        private val totalCount: Int,
        private val allResolved: Boolean
    ) : LineMarkerRenderer {
        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            if (totalCount <= 0) return
            val x = r.x + icon.iconWidth + JBUI.scale(4)
            val fm = g.fontMetrics
            val y = r.y + (r.height + fm.ascent - fm.descent) / 2
            g.color = if (allResolved) JBColor(Color(0x1E8E3E), Color(0x57D163)) else JBColor(Color(0xD93025), Color(0xF47067))
            g.drawString("$unresolvedCount/$totalCount", x, y)
        }
    }

    private fun showPopup(editor: Editor, filePath: String, line: Int, side: Side, openComposerOnly: Boolean = false) {
        val currentUserName = "本地用户"
        val currentUserIcon = AllIcons.General.User
        val timeFormatter = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault())

        val bgBase = JBColor(Color(0xF7F8FA), Color(0x2B2B2B))
        val sectionBg = JBColor(Color(0xFFFFFF), Color(0x3A3A3A))
        val unitBg = JBColor(Color(0xFFFFFF), Color(0x3A3A3A))
        val unitHoverBg = JBColor(Color(0xF2F4F7), Color(0x444444))
        val borderColor = JBColor(Color(0xE0E3E7), Color(0x4A4A4A))
        val textPrimary = JBColor(Color(0x1F2328), Color(0xE5EAF0))
        val textSecondary = JBColor(Color(0x6B7280), Color(0xC9CDD4))
        val textHint = JBColor(Color(0x9AA0A6), Color(0x8F959E))
        val textLink = JBColor(Color(0x2563EB), Color(0x4EA1FF))
        val successColor = JBColor(Color(0x1E8E3E), Color(0x57D163))
        val dangerColor = JBColor(Color(0xD93025), Color(0xF47067))

        val container = JPanel(BorderLayout())
        container.border = JBUI.Borders.empty(10)
        container.background = bgBase

        val headerInput = EditorTextField()
        headerInput.setOneLineMode(true)
        headerInput.isFocusable = true
        headerInput.enableInputMethods(true)
        container.add(headerInput, BorderLayout.NORTH)

        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        listPanel.isOpaque = true
        listPanel.background = bgBase

        val scrollPane = JBScrollPane(listPanel)

        scrollPane.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(150))
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.viewport.isOpaque = true
        scrollPane.viewport.background = bgBase
        scrollPane.isOpaque = false



        var popup: com.intellij.openapi.ui.popup.JBPopup? = null
        var collapseAll = false
        var showComposerOnly = openComposerOnly
        var autoOpenComposer = openComposerOnly
        lateinit var rebuild: () -> Unit

        val collapsedById = mutableMapOf<String, Boolean>()
        val replyComposerOpenById = mutableSetOf<String>()
        val editComposerOpenById = mutableSetOf<String>()

        fun summarize(text: String, maxLen: Int = 20): String {
            val trimmed = text.trim()
            return if (trimmed.length <= maxLen) trimmed else trimmed.take(maxLen) + "..."
        }

        fun createUserBadge(name: String, icon: Icon = currentUserIcon): JComponent {
            val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            panel.isOpaque = false
            val label = JBLabel(name, icon, SwingConstants.LEFT)
            label.foreground = textSecondary
            panel.add(label)
            return panel
        }

        fun addRootComment(text: String) {
            val content = text.trim()
            if (content.isBlank() || content.length > MAX_COMMENT_LENGTH) return
            LineCommentStore.addComment(filePath, line, side, content, currentUserName)
        }

        fun addReply(parentId: String, text: String) {
            val content = text.trim()
            if (content.isBlank() || content.length > MAX_COMMENT_LENGTH) return
            LineCommentStore.addReply(filePath, line, side, parentId, content, currentUserName)
        }

        fun replaceComment(comment: LineComment, text: String) {
            val content = text.trim()
            if (content.isBlank() || content.length > MAX_COMMENT_LENGTH) return
            LineCommentStore.updateComment(filePath, line, side, comment.id, content)
        }

        fun resolveAllOnLineLocal() {
            LineCommentStore.getComments(filePath, line, side).forEach { item ->
                LineCommentStore.resolveComment(filePath, line, side, item.id)
            }
        }

        fun buildActionMenu(comment: LineComment) {
            val panel = JPanel(GridLayout(2, 1, 0, JBUI.scale(4)))
            panel.border = JBUI.Borders.empty(6)
            val edit = JButton("编辑")
            val delete = JButton("删除")
            panel.add(edit)
            panel.add(delete)

            val menu = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel)
                .setRequestFocus(true)
                .createPopup()

            edit.addActionListener {
                editComposerOpenById.add(comment.id)
                menu.cancel()
                rebuild()
            }
            delete.addActionListener {
                LineCommentStore.removeComment(filePath, line, side, comment.id)
                menu.cancel()
            }

            menu.showInBestPositionFor(editor)
        }

        fun buildHeaderRow(
            comment: LineComment,
            showActions: Boolean,
            showToggle: Boolean,
            isCollapsed: Boolean,
            onToggle: (() -> Unit)?
        ): JComponent {
            val row = JPanel(BorderLayout())
            row.isOpaque = false

            val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            left.isOpaque = false
            left.add(createUserBadge(comment.author, AllIcons.General.User))
            left.add(JBLabel(" "))
            val timeLabel = JBLabel(timeFormatter.format(Date(comment.createdAt)))
            timeLabel.foreground = textHint
            left.add(timeLabel)

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            right.isOpaque = false

            if (showActions) {
                val more = JButton(AllIcons.Actions.More)
                more.isOpaque = false
                more.isContentAreaFilled = false
                more.isBorderPainted = false
                more.addActionListener { buildActionMenu(comment) }
                right.add(more)
            }

            if (showToggle && onToggle != null) {
                val toggle = JButton(if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown)
                toggle.isOpaque = false
                toggle.isContentAreaFilled = false
                toggle.isBorderPainted = false
                toggle.addActionListener { onToggle() }
                right.add(toggle)
            }

            row.add(left, BorderLayout.WEST)
            row.add(right, BorderLayout.EAST)
            return row
        }

        fun buildComposer(
            initial: String,
            onCancel: () -> Unit,
            onSubmit: (String) -> Unit,
            onBuilt: ((JComponent) -> Unit)? = null,
            rows: Int = 4
        ): JComponent {
            val wrapper = JPanel(BorderLayout())
            wrapper.isOpaque = false

            val input = JBTextArea(initial)
            input.lineWrap = true
            input.wrapStyleWord = true
            input.isEnabled = true
            input.isFocusable = true
            input.enableInputMethods(true)
            val lineHeight = input.getFontMetrics(input.font).height
            input.preferredSize = Dimension(JBUI.scale(520), lineHeight * rows + JBUI.scale(12))
            onBuilt?.invoke(input)
            SwingUtilities.invokeLater { input.requestFocusInWindow() }

            val hint = JBLabel(MAX_COMMENT_HINT)
            hint.foreground = textHint
            val cancel = JButton("取消")
            val submit = JButton("评论")

            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
            buttons.isOpaque = false
            buttons.add(hint)
            buttons.add(cancel)
            buttons.add(submit)

            val center = JPanel(BorderLayout())
            center.isOpaque = false
            center.add(input, BorderLayout.CENTER)
            center.add(buttons, BorderLayout.SOUTH)
            wrapper.add(center, BorderLayout.CENTER)

            cancel.addActionListener { onCancel() }
            submit.addActionListener { onSubmit(input.text) }
            return wrapper
        }

        fun buildCommentNode(
            comment: LineComment,
            all: List<LineComment>,
            level: Int,
            container: JPanel,
            showToggle: Boolean,
            onToggle: (() -> Unit)?
        ) {
            val indent = JBUI.scale(20 * level)
            val nodePanel = JPanel()
            nodePanel.layout = BoxLayout(nodePanel, BoxLayout.Y_AXIS)
            nodePanel.isOpaque = false
            nodePanel.border = JBUI.Borders.emptyTop(8)

            val header = buildHeaderRow(
                comment,
                showActions = true,
                showToggle = showToggle,
                isCollapsed = false,
                onToggle = onToggle
            )
            header.border = JBUI.Borders.emptyLeft(indent)
            nodePanel.add(header)

            val contentPanel = JPanel(BorderLayout())
            contentPanel.isOpaque = false
            contentPanel.border = JBUI.Borders.emptyLeft(indent + JBUI.scale(20))

            val contentBox = JPanel()
            contentBox.layout = BoxLayout(contentBox, BoxLayout.Y_AXIS)
            contentBox.isOpaque = false

            if (comment.parentId != null) {
                val parent = all.firstOrNull { it.id == comment.parentId }
                val replyTo = parent?.content?.let { summarize(it, 24) } ?: ""
                val replyLabel = JBLabel("回复：$replyTo")
                replyLabel.foreground = textHint
                contentBox.add(replyLabel)
            }

            val content = JBTextArea(comment.content)
            content.isEditable = false
            content.lineWrap = true
            content.wrapStyleWord = true
            content.isOpaque = false
            content.foreground = textPrimary
            contentBox.add(content)
            contentPanel.add(contentBox, BorderLayout.CENTER)
            nodePanel.add(contentPanel)

            val replyRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            replyRow.isOpaque = false
            replyRow.border = JBUI.Borders.emptyLeft(indent + JBUI.scale(20))
            val replyButton = JButton("回复")
            replyButton.addActionListener {
                replyComposerOpenById.add(comment.id)
                rebuild()
            }
            replyRow.add(replyButton)
            nodePanel.add(replyRow)

            if (editComposerOpenById.contains(comment.id)) {
                val editorComposer = buildComposer(comment.content, {
                    editComposerOpenById.remove(comment.id)
                    rebuild()
                }, onSubmit = { text ->
                    replaceComment(comment, text)
                    editComposerOpenById.remove(comment.id)
                    rebuild()
                })
                editorComposer.border = JBUI.Borders.emptyLeft(indent + JBUI.scale(20))
                nodePanel.add(editorComposer)
            }

            if (replyComposerOpenById.contains(comment.id)) {
                val replyComposer = buildComposer("", {
                    replyComposerOpenById.remove(comment.id)
                    rebuild()
                }, onSubmit = { text ->
                    addReply(comment.id, text)
                    replyComposerOpenById.remove(comment.id)
                    rebuild()
                })
                replyComposer.border = JBUI.Borders.emptyLeft(indent + JBUI.scale(20))
                nodePanel.add(replyComposer)
            }

            container.add(nodePanel)

                val children = all.filter { it.parentId == comment.id }.sortedBy { it.createdAt }
                children.forEach { child ->
                    buildCommentNode(child, all, level + 1, container, showToggle = false, onToggle = null)
                }

        }

        fun buildCommentUnit(root: LineComment, all: List<LineComment>, rebuild: () -> Unit): JComponent {
            val isCollapsed = if (collapseAll) true else collapsedById.getOrPut(root.id) { root.resolved }
            val wrapper = JPanel(BorderLayout())
            wrapper.isOpaque = true
            wrapper.background = unitBg
            wrapper.border = JBUI.Borders.compound(JBUI.Borders.customLine(borderColor), JBUI.Borders.empty(8))

            wrapper.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    wrapper.background = unitHoverBg
                }

                override fun mouseExited(e: MouseEvent) {
                    wrapper.background = unitBg
                }
            })

            val titleRow = JPanel(BorderLayout())
            titleRow.isOpaque = false
            val titleLeft = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            titleLeft.isOpaque = false
            titleLeft.add(JBLabel(AllIcons.General.Balloon))
            titleLeft.add(JBLabel(" "))
            titleLeft.add(JBLabel(root.author))
            titleLeft.add(JBLabel(" "))
            val timeLabel = JBLabel(timeFormatter.format(Date(root.createdAt)))
            timeLabel.foreground = textHint
            titleLeft.add(timeLabel)

            if (isCollapsed) {
                titleLeft.add(JBLabel(" "))
                val summary = JBLabel(summarize(root.content, 24))
                summary.foreground = textSecondary
                titleLeft.add(summary)
            }

            titleRow.add(titleLeft, BorderLayout.WEST)
            val toggle = JButton(if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown)
            toggle.isOpaque = false
            toggle.isContentAreaFilled = false
            toggle.isBorderPainted = false
            toggle.addActionListener {
                collapsedById[root.id] = !isCollapsed
                rebuild()
            }
            titleRow.add(toggle, BorderLayout.EAST)

            if (isCollapsed) {
                wrapper.add(titleRow, BorderLayout.CENTER)
                return wrapper
            }

            val body = JPanel()
            body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
            body.isOpaque = false

            val treePanel = JPanel()
            treePanel.layout = BoxLayout(treePanel, BoxLayout.Y_AXIS)
            treePanel.isOpaque = false
            treePanel.border = JBUI.Borders.emptyTop(4)
            buildCommentNode(root, all, 0, treePanel, showToggle = true) {
                collapsedById[root.id] = true
                rebuild()
            }
            body.add(treePanel)

            wrapper.add(titleRow, BorderLayout.NORTH)
            wrapper.add(body, BorderLayout.CENTER)
            return wrapper
        }

        fun buildPartATopBar(unresolvedCount: Int, totalCount: Int): JComponent {
            val bar = JPanel(BorderLayout())
            bar.isOpaque = true
            bar.background = sectionBg
            bar.border = JBUI.Borders.compound(JBUI.Borders.customLine(borderColor), JBUI.Borders.empty(8))

            val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            left.isOpaque = false
            val statusLabel = JBLabel(if (unresolvedCount == 0) "已解决" else "未解决")
            statusLabel.foreground = if (unresolvedCount == 0) successColor else dangerColor
            left.add(JBLabel(AllIcons.General.Balloon))
            left.add(JBLabel(" "))
            left.add(statusLabel)
            left.add(JBLabel(" "))
            left.add(JBLabel("$unresolvedCount/$totalCount"))

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            right.isOpaque = false
            val collapseBtn = JButton(AllIcons.General.ArrowRight)
            val expandBtn = JButton(AllIcons.General.ArrowDown)
            collapseBtn.isOpaque = false
            collapseBtn.isContentAreaFilled = false
            collapseBtn.isBorderPainted = false
            expandBtn.isOpaque = false
            expandBtn.isContentAreaFilled = false
            expandBtn.isBorderPainted = false
            collapseBtn.addActionListener {
                collapseAll = true
                rebuild()
            }
            expandBtn.addActionListener {
                collapseAll = false
                rebuild()
            }
            right.add(collapseBtn)
            right.add(expandBtn)

            bar.add(left, BorderLayout.WEST)
            bar.add(right, BorderLayout.EAST)
            return bar
        }

        fun buildPartCPreComment(rebuild: () -> Unit): JComponent {
            val wrapper = JPanel(BorderLayout())
            wrapper.isOpaque = true
            wrapper.background = sectionBg
            wrapper.border = JBUI.Borders.compound(JBUI.Borders.customLine(borderColor), JBUI.Borders.empty(8))

            val topRow = JPanel(BorderLayout())
            topRow.isOpaque = false

            val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            left.isOpaque = false
            left.add(createUserBadge(currentUserName))
            left.add(JBLabel(" "))
            val replyButton = JButton("回复")
            left.add(replyButton)
            topRow.add(left, BorderLayout.WEST)

            val resolveButton = JButton("问题已解决")
            resolveButton.addActionListener {
                resolveAllOnLineLocal()
            }
            topRow.add(resolveButton, BorderLayout.EAST)

            fun setEditingState(isEditing: Boolean) {
                replyButton.isVisible = !isEditing
                resolveButton.isVisible = !isEditing
            }

            val card = CardLayout()
            val composer = JPanel(card)
            composer.isOpaque = false
            composer.border = JBUI.Borders.emptyTop(8)
            composer.isVisible = false

            val collapsed = JPanel(BorderLayout())
            collapsed.isOpaque = false
            val oneLine = JBTextField()
            oneLine.emptyText.text = "新增评论..."
            collapsed.add(oneLine, BorderLayout.CENTER)

            val expanded = JPanel(BorderLayout())
            expanded.isOpaque = false
            var composerInput: JComponent? = null
            val composerBody = buildComposer("", {
                if (showComposerOnly) {
                    popup?.cancel()
                    return@buildComposer
                }
                composer.isVisible = false
                card.show(composer, "collapsed")
                setEditingState(false)
                autoOpenComposer = false
                rebuild()
            }, onSubmit = { text ->
                addRootComment(text)
                composer.isVisible = false
                card.show(composer, "collapsed")
                setEditingState(false)
                showComposerOnly = false
                autoOpenComposer = false
                rebuild()
            }, onBuilt = { input ->
                composerInput = input
            })
            expanded.add(composerBody, BorderLayout.CENTER)

            composer.add(collapsed, "collapsed")
            composer.add(expanded, "expanded")
            card.show(composer, "collapsed")

            replyButton.addActionListener {
                composer.isVisible = true
                card.show(composer, "expanded")
                setEditingState(true)
                composerInput?.requestFocusInWindow()
            }

            oneLine.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    card.show(composer, "expanded")
                    setEditingState(true)
                    composerInput?.requestFocusInWindow()
                }
            })

            if (autoOpenComposer) {
                composer.isVisible = true
                card.show(composer, "expanded")
                setEditingState(true)
                composerInput?.requestFocusInWindow()
            }

            wrapper.add(topRow, BorderLayout.NORTH)
            wrapper.add(composer, BorderLayout.CENTER)
            return wrapper
        }

        rebuild = {
            listPanel.removeAll()

            val all = LineCommentStore.getComments(filePath, line, side)
            val roots = all.filter { it.parentId == null }.sortedBy { it.createdAt }
            val unresolvedCount = roots.count { !it.resolved }
            val totalCount = roots.size

            if (!showComposerOnly) {
                listPanel.add(buildPartATopBar(unresolvedCount, totalCount))
                listPanel.add(JPanel().apply { isOpaque = false; preferredSize = Dimension(1, JBUI.scale(8)) })

                val unitsWrapper = JPanel()
                unitsWrapper.layout = BoxLayout(unitsWrapper, BoxLayout.Y_AXIS)
                unitsWrapper.isOpaque = false

                if (roots.isEmpty()) {
                    val empty = JBLabel("暂无评论")
                    empty.foreground = textHint
                    unitsWrapper.add(empty)
                } else {
                    roots.forEach { root ->
                        unitsWrapper.add(buildCommentUnit(root, all) { rebuild() })
                        unitsWrapper.add(JPanel().apply { isOpaque = false; preferredSize = Dimension(1, JBUI.scale(8)) })
                    }
                }

                val sectionB = JPanel(BorderLayout())
                sectionB.isOpaque = true
                sectionB.background = sectionBg
                sectionB.border = JBUI.Borders.compound(JBUI.Borders.customLine(borderColor), JBUI.Borders.empty(8))
                sectionB.add(unitsWrapper, BorderLayout.CENTER)

                listPanel.add(sectionB)
                listPanel.add(JPanel().apply { isOpaque = false; preferredSize = Dimension(1, JBUI.scale(8)) })
            }

            listPanel.add(buildPartCPreComment { rebuild() })

            listPanel.revalidate()
            listPanel.repaint()
        }

        container.add(scrollPane, BorderLayout.CENTER)

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(container, headerInput)
            .setRequestFocus(true)
            .setResizable(true)
            .createPopup()

        val storeListener: () -> Unit = { rebuild() }
        LineCommentStore.addListener(storeListener)
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                LineCommentStore.removeListener(storeListener)
            }
        })

        rebuild()
        popup.showInBestPositionFor(editor)
        SwingUtilities.invokeLater { headerInput.requestFocusInWindow() }
    }

    private data class EditorContext(val editor: Editor, val filePath: String, val side: Side)
}
