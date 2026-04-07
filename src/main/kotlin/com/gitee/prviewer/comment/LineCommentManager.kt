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
import kotlin.math.absoluteValue
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.Nls
import java.awt.*
import kotlin.math.max
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.JTextPane
import javax.swing.text.JTextComponent
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class LineCommentManager(private val project: Project) {
    companion object {
        private const val MAX_COMMENT_LENGTH = 200
        private const val MAX_COMMENT_HINT = "最多 200 字"
        @Nls(capitalization = Nls.Capitalization.NotSpecified)
        private val emptyReplyText = "暂无回复"
        @Nls(capitalization = Nls.Capitalization.NotSpecified)
        private val emptyCommentText = "暂无评论"
    }

    private val highlightersByEditor = WeakHashMap<Editor, MutableList<RangeHighlighter>>()
    private val commentHighlightersByEditorLine = WeakHashMap<Editor, MutableMap<Int, RangeHighlighter>>()
    private val hoverHighlighterByEditor = WeakHashMap<Editor, RangeHighlighter?>()
    private val hoveredLineByEditor = WeakHashMap<Editor, Int?>()
    private val remoteIssueLinesByFile = mutableMapOf<String, Set<Int>>()
    private val remoteIssuesByFileLine = mutableMapOf<String, Map<Int, IssueItem>>()
    private val remoteIssueCommentKeys = mutableSetOf<String>()

    init {
        LineCommentStore.addListener { refreshAllEditors() }
    }

    interface CommentRemoteHandler {
        fun addComment(filePath: String, line: Int, side: Side, content: String)
        fun addReply(filePath: String, line: Int, side: Side, parent: LineComment, content: String)
        fun resolveThread(filePath: String, line: Int, side: Side, root: LineComment)
    }

    private var remoteHandler: CommentRemoteHandler? = null

    fun setRemoteHandler(handler: CommentRemoteHandler?) {
        remoteHandler = handler
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

    fun updateIssueDetails(filePath: String, issues: List<IssueItem>) {
        val normalized = normalizeFilePath(filePath)
        if (issues.isEmpty()) {
            remoteIssuesByFileLine.remove(normalized)
            return
        }
        val lineMap = issues
            .mapNotNull { issue ->
                val line = issue.line
                if (line <= 0) null else (line - 1) to issue
            }
            .toMap()
        remoteIssuesByFileLine[normalized] = lineMap
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
                showPopup(editor, context.filePath, line, context.side, openComposerOnly = true)
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
        val currentUserName = System.getenv("USERID").orEmpty().ifBlank { "本地用户" }
        val currentUserIcon = AllIcons.General.User
        val timeFormatter = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault())

        val bgBase = UIUtil.getPanelBackground()
        val sectionBg = UIUtil.getPanelBackground()
        val commentUnitBg = UIUtil.getListBackground()
        val replyUnitBg = UIUtil.getTextFieldBackground()
        val commentUnitHoverBg = UIUtil.getDecoratedRowColor()
        val borderColor = UIUtil.getSeparatorColor()
        val contentMaxWidth = JBUI.scale(520)
        val leftInset = JBUI.scale(4)
        val actionTopGap = JBUI.scale(6)
        val unitGap = JBUI.scale(2)
        val textPrimary = UIUtil.getLabelForeground()
        val textSecondary = UIUtil.getInactiveTextColor()
        val textHint = UIUtil.getContextHelpForeground()
        val textLink = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val successColor = JBColor(Color(0x1E8E3E), Color(0x57D163))
        val dangerColor = JBColor(Color(0xD93025), Color(0xF47067))

        val container = JPanel(BorderLayout())
        container.border = JBUI.Borders.empty(10)
        container.background = bgBase

        val headerInput = EditorTextField()
        headerInput.setOneLineMode(true)
        headerInput.isFocusable = true
        headerInput.enableInputMethods(true)
        headerInput.isVisible = false
        headerInput.preferredSize = Dimension(0, 0)

        val middlePanel = JPanel()
        middlePanel.layout = BoxLayout(middlePanel, BoxLayout.Y_AXIS)
        middlePanel.isOpaque = true
        middlePanel.background = bgBase

        val footerPanel = JPanel(BorderLayout())
        footerPanel.isOpaque = true
        footerPanel.background = bgBase
        footerPanel.border = JBUI.Borders.empty()

        container.add(headerInput, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(middlePanel)

        scrollPane.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(150))
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.viewport.isOpaque = true
        scrollPane.viewport.background = bgBase
        scrollPane.isOpaque = false



        var popup: com.intellij.openapi.ui.popup.JBPopup? = null
        var collapseAll = false
        var showComposerOnly = openComposerOnly
        var autoOpenComposer = openComposerOnly
        var pendingScrollToLatest = false
        var lastCommentCount = 0
        lateinit var rebuild: () -> Unit

        val collapsedById = mutableMapOf<String, Boolean>()
        val replyComposerOpenById = mutableSetOf<String>()
        val editComposerOpenById = mutableSetOf<String>()

        fun summarize(text: String, maxLen: Int = 20): String {
            val trimmed = text.trim()
            return if (trimmed.length <= maxLen) trimmed else trimmed.take(maxLen) + "..."
        }

        fun ensureRemoteIssueRoot() {
            if (side != Side.RIGHT) return
            if (LineCommentStore.hasComments(filePath, line, side)) return
            val normalized = normalizeFilePath(filePath)
            val issue = remoteIssuesByFileLine[normalized]?.get(line) ?: return
            val key = "$normalized#$line#${side.name}#${issue.id}"
            if (remoteIssueCommentKeys.contains(key)) return
            val content = listOf(issue.number, issue.msg).filter { it.isNotBlank() }.joinToString(" ")
            if (content.isNotBlank()) {
                val added = LineCommentStore.addComment(filePath, line, side, content, issue.createBy.ifBlank { "系统" })
                if (issue.status.trim().lowercase() == "fixed" || issue.status.trim().lowercase() == "resolved") {
                    LineCommentStore.resolveComment(filePath, line, side, added.id)
                }
                remoteIssueCommentKeys.add(key)
            }
        }

        fun createUserBadge(name: String, icon: Icon = currentUserIcon): JComponent {
            val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            panel.isOpaque = false
            val label = JBLabel(name, icon, SwingConstants.LEFT)
            label.foreground = textSecondary
            panel.add(label)
            return panel
        }

        fun buildActionLinkLabel(text: String, onClick: () -> Unit): JBLabel {
            val label = JBLabel(text)
            label.isOpaque = false
            label.foreground = textLink
            label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onClick()
                }
            })
            return label
        }

        fun avatarColor(name: String): JBColor {
            val palette = listOf(
                JBColor(Color(0x1A73E8), Color(0x6EA8FF)),
                JBColor(Color(0x1E8E3E), Color(0x57D163)),
                JBColor(Color(0x8E24AA), Color(0xC77DFF)),
                JBColor(Color(0xF29900), Color(0xF6C26B)),
                JBColor(Color(0xD93025), Color(0xF47067))
            )
            val idx = (name.trim().lowercase().hashCode().absoluteValue) % palette.size
            return palette[idx]
        }

        class UserAvatarIcon(
            private val username: String,
            private val color: Color,
            private val size: Int
        ) : Icon {
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = color
                    g2.fillOval(x, y, size, size)
                    val initials = username.trim().take(1).ifBlank { "?" }.uppercase()
                    g2.color = Color.WHITE
                    val fm = g2.fontMetrics
                    val tx = x + (size - fm.stringWidth(initials)) / 2
                    val ty = y + (size + fm.ascent - fm.descent) / 2
                    g2.drawString(initials, tx, ty)
                } finally {
                    g2.dispose()
                }
            }

            override fun getIconWidth(): Int = size

            override fun getIconHeight(): Int = size
        }

        fun buildHeaderLeftPanel(
            seqText: String,
            author: String,
            createdAt: Long,
            replyFloorText: String?
        ): JPanel {
            val left = JPanel()
            left.layout = BoxLayout(left, BoxLayout.X_AXIS)
            left.isOpaque = false

            val charWidth = left.getFontMetrics(left.font).charWidth('中')
            val slotWidth = charWidth * 3
            val avatarSize = (charWidth * 1.6).toInt().coerceAtLeast(JBUI.scale(12))
            val avatarIcon = UserAvatarIcon(author, avatarColor(author), avatarSize)
            val avatarLabel = JBLabel(avatarIcon)
            val avatarSlot = JPanel(BorderLayout()).apply {
                isOpaque = false
                preferredSize = Dimension(slotWidth, avatarSize)
                minimumSize = Dimension(slotWidth, avatarSize)
                maximumSize = Dimension(slotWidth, avatarSize)
                add(avatarLabel, BorderLayout.CENTER)
            }
            left.add(avatarSlot)

            val nameLabel = JBLabel(author)
            nameLabel.foreground = textSecondary
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)
            left.add(nameLabel)
            left.add(JBLabel(" "))

            val timeLabel = JBLabel(timeFormatter.format(Date(createdAt)))
            timeLabel.foreground = textHint
            left.add(timeLabel)

            val seqDisplay = if (replyFloorText.isNullOrBlank()) seqText else "$seqText ->$replyFloorText"
            left.add(Box.createHorizontalStrut(JBUI.scale(4)))
            val seqLabel = JBLabel(seqDisplay)
            seqLabel.foreground = textSecondary
            left.add(seqLabel)
            return left
        }

        fun addRootComment(text: String) {
            val content = text.trim()
            if (content.isBlank() || content.length > MAX_COMMENT_LENGTH) return
            val handler = remoteHandler
            if (handler != null) {
                handler.addComment(filePath, line, side, content)
                return
            }
            LineCommentStore.addComment(filePath, line, side, content, currentUserName)
        }

        fun addReply(parent: LineComment, text: String) {
            val content = text.trim()
            if (content.isBlank() || content.length > MAX_COMMENT_LENGTH) return
            val handler = remoteHandler
            if (handler != null) {
                handler.addReply(filePath, line, side, parent, content)
                return
            }
            LineCommentStore.addReply(
                filePath = filePath,
                line = line,
                side = side,
                parentId = parent.id,
                content = content,
                author = currentUserName,
                rootId = parent.rootId,
                replyFloorNum = parent.floorNum
            )
        }

        fun replaceComment(comment: LineComment, text: String) {
            val content = text.trim()
            if (content.isBlank() || content.length > MAX_COMMENT_LENGTH) return
            LineCommentStore.updateComment(filePath, line, side, comment.id, content)
        }

        fun resolveThreadLocal(rootId: String) {
            LineCommentStore.getComments(filePath, line, side)
                .filter { it.rootId == rootId }
                .forEach { item ->
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
            rows: Int = 4,
            topPadding: Int = JBUI.scale(1),
            bottomPadding: Int = JBUI.scale(3),
            lineSpacing: Float = 0f
        ): JComponent {
            val wrapper = JPanel(BorderLayout())
            wrapper.isOpaque = false

            val input: JTextComponent
            val inputComponent: JComponent
            if (lineSpacing > 0f) {
                val pane = JTextPane()
                pane.isEditable = true
                pane.isEnabled = true
                pane.isFocusable = true
                pane.enableInputMethods(true)
                val attrs = SimpleAttributeSet()
                StyleConstants.setLineSpacing(attrs, lineSpacing)
                pane.text = initial
                SwingUtilities.invokeLater {
                    val doc = pane.styledDocument
                    doc.setParagraphAttributes(0, doc.length, attrs, false)
                }
                input = pane
                inputComponent = pane
            } else {
                val area = JBTextArea(initial)
                area.lineWrap = true
                area.wrapStyleWord = true
                area.isEnabled = true
                area.isFocusable = true
                area.enableInputMethods(true)
                area.rows = rows
                input = area
                inputComponent = area
            }
            val lineHeight = input.getFontMetrics(input.font).height
            val effectiveLineHeight = if (lineSpacing > 0f) (lineHeight * (1 + lineSpacing)).toInt() else lineHeight
            input.margin = JBUI.insets(topPadding, 6, bottomPadding, 6)
            val fixedHeight = effectiveLineHeight * rows + topPadding + bottomPadding
            val fixedSize = Dimension(JBUI.scale(520), fixedHeight)
            inputComponent.preferredSize = fixedSize
            inputComponent.minimumSize = fixedSize
            inputComponent.maximumSize = fixedSize
            onBuilt?.invoke(inputComponent)
            SwingUtilities.invokeLater { input.requestFocusInWindow() }

            val hint = JBLabel(MAX_COMMENT_HINT)
            hint.foreground = textHint
            val cancel = com.intellij.ui.components.ActionLink("取消") { onCancel() }
            cancel.isOpaque = false
            cancel.border = JBUI.Borders.empty()
            cancel.foreground = textLink
            cancel.isFocusable = false

            val submit = com.intellij.ui.components.ActionLink("评论") { onSubmit(input.text) }
            submit.isOpaque = false
            submit.border = JBUI.Borders.empty()
            submit.foreground = textLink
            submit.isFocusable = false

            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
            buttons.isOpaque = false
            buttons.border = JBUI.Borders.empty(JBUI.scale(4), 0, JBUI.scale(2), 0)
            buttons.add(hint)
            buttons.add(cancel)
            buttons.add(submit)

            val center = JPanel(BorderLayout())
            center.isOpaque = false
            center.add(inputComponent, BorderLayout.CENTER)
            center.add(buttons, BorderLayout.SOUTH)
            wrapper.add(center, BorderLayout.CENTER)

            return wrapper
        }

        fun isDescendant(comment: LineComment, rootId: String, commentById: Map<String, LineComment>): Boolean {
            var parentId = comment.parentId
            while (parentId != null) {
                val parent = commentById[parentId] ?: return false
                if (parent.id == rootId) return true
                parentId = parent.parentId
            }
            return false
        }

        fun threadForRoot(root: LineComment, all: List<LineComment>, commentById: Map<String, LineComment>): List<LineComment> {
            val replies = all.filter { it.parentId != null && isDescendant(it, root.id, commentById) }
                .sortedWith(compareBy<LineComment> { it.floorNum ?: Int.MAX_VALUE }.thenBy { it.createdAt })
            return listOf(root) + replies
        }

        fun buildUnitHeaderRow(
            comment: LineComment,
            seq: Int,
            statusText: String?,
            statusColor: Color,
            isCollapsed: Boolean,
            onToggle: () -> Unit
        ): JComponent {
            val row = JPanel(GridBagLayout())
            row.isOpaque = false

            val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            left.isOpaque = false
            left.border = JBUI.Borders.emptyLeft(4)
            val seqLabel = JBLabel("#$seq")
            seqLabel.foreground = textSecondary
            left.add(seqLabel)
            left.add(JBLabel("  "))
            val nameLabel = JBLabel(comment.author)
            nameLabel.foreground = textSecondary
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)
            left.add(nameLabel)
            left.add(JBLabel(" "))
            val timeLabel = JBLabel(timeFormatter.format(Date(comment.createdAt)))
            timeLabel.foreground = textHint
            left.add(timeLabel)

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            right.isOpaque = false
            if (statusText != null) {
                val statusLabel = JBLabel(statusText)
                statusLabel.foreground = statusColor
                statusLabel.border = JBUI.Borders.emptyRight(JBUI.scale(2))
                right.add(statusLabel)
            }
            val toggle = JButton(if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown)
            toggle.isOpaque = false
            toggle.isContentAreaFilled = false
            toggle.isBorderPainted = false
            toggle.margin = JBUI.emptyInsets()
            toggle.iconTextGap = 0
            toggle.addActionListener { onToggle() }
            right.add(toggle)

            val leftConstraints = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
            }
            val rightConstraints = GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                weightx = 0.0
                anchor = GridBagConstraints.EAST
                insets = JBUI.emptyInsets()
            }
            row.add(left, leftConstraints)
            row.add(right, rightConstraints)
            return row
        }

        fun buildContentRow(text: String): JComponent {
            val content = JBTextArea(text)
            content.isEditable = false
            content.lineWrap = true
            content.wrapStyleWord = true
            content.isOpaque = false
            content.foreground = textPrimary
            val textWidth = contentMaxWidth - JBUI.scale(12)
            content.size = Dimension(textWidth, Int.MAX_VALUE)
            val pref = content.preferredSize
            content.preferredSize = Dimension(textWidth, pref.height)
            return content
        }

        @Suppress("DuplicatedCode")
        fun buildActionRow(onReply: () -> Unit, onResolve: (() -> Unit)? = null): JComponent {
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            row.isOpaque = false
            val indent = row.getFontMetrics(row.font).charWidth('中') * 2
            row.border = JBUI.Borders.emptyLeft(indent)
            val charWidth = row.getFontMetrics(row.font).charWidth('0')
            val replyLink = buildActionLinkLabel("回复") { onReply() }
            val replyHeight = replyLink.preferredSize.height
            val replyWidth = charWidth * 4
            val replySize = Dimension(replyWidth, replyHeight)
            replyLink.preferredSize = replySize
            replyLink.minimumSize = replySize
            replyLink.maximumSize = replySize
            row.add(replyLink)

            if (onResolve != null) {
                row.add(Box.createHorizontalStrut(charWidth * 2))
                val resolveLink = buildActionLinkLabel("问题已解决") { onResolve() }
                row.add(resolveLink)
            }
            return row
        }

        @Suppress("DuplicatedCode")
        fun buildReplyUnit(reply: LineComment, seq: Int, rebuild: () -> Unit): JComponent {
            val replyCard = JPanel()
            replyCard.layout = BoxLayout(replyCard, BoxLayout.Y_AXIS)
            replyCard.isOpaque = false
            replyCard.border = JBUI.Borders.empty()

            val header = JPanel(BorderLayout())
            header.isOpaque = false
            val replyFloorText = reply.replyFloorNum?.takeIf { it > 0 }?.let { "#$it" }
            val left = buildHeaderLeftPanel("#$seq", reply.author, reply.createdAt, replyFloorText)
            header.add(left, BorderLayout.WEST)
            header.maximumSize = Dimension(Int.MAX_VALUE, header.preferredSize.height)
            val rowGap = JBUI.scale(4)
            header.border = JBUI.Borders.emptyBottom(rowGap)
            replyCard.add(header)
            replyCard.add(Box.createVerticalStrut(rowGap))

            val contentRow = JPanel(BorderLayout())
            contentRow.isOpaque = false

            val replyIndent = contentRow.getFontMetrics(contentRow.font).charWidth('中') * 3
            contentRow.border = JBUI.Borders.emptyLeft(replyIndent)
            contentRow.add(buildContentRow(reply.content), BorderLayout.CENTER)
            replyCard.add(contentRow)
            replyCard.add(Box.createVerticalStrut(actionTopGap))

            val actions = buildActionRow(onReply = {
                replyComposerOpenById.add(reply.id)
                rebuild()
            })
            val actionsHeight = actions.preferredSize.height
            actions.maximumSize = Dimension(contentMaxWidth, actionsHeight)
            actions.preferredSize = Dimension(contentMaxWidth, actionsHeight)
            replyCard.add(actions)

            if (replyComposerOpenById.contains(reply.id)) {
                val replyComposer = buildComposer(
                    "",
                    {
                        replyComposerOpenById.remove(reply.id)
                        rebuild()
                    },
                    onSubmit = { text ->
                        addReply(reply, text)
                        collapsedById[reply.rootId] = false
                        pendingScrollToLatest = true
                        replyComposerOpenById.remove(reply.id)
                        rebuild()
                    },
                    onBuilt = { input ->
                        val charWidth = input.getFontMetrics(input.font).charWidth('中')
                        val targetWidth = (input.preferredSize.width - charWidth * 4).coerceAtLeast(JBUI.scale(200))
                        val targetSize = Dimension(targetWidth, input.preferredSize.height)
                        input.preferredSize = targetSize
                        input.minimumSize = targetSize
                        input.maximumSize = targetSize
                    },
                    topPadding = JBUI.scale(5),
                    bottomPadding = JBUI.scale(3),
                    lineSpacing = 0.1f
                )
                val replyIndent = replyComposer.getFontMetrics(replyComposer.font).charWidth('中') * 3
                replyComposer.border = JBUI.Borders.empty(4, replyIndent, 0, 0)
                replyCard.add(replyComposer)
            }

            replyCard.maximumSize = Dimension(Int.MAX_VALUE, replyCard.preferredSize.height)
            return replyCard
        }

        @Suppress("DuplicatedCode")
        fun buildCommentUnit(root: LineComment, all: List<LineComment>, commentById: Map<String, LineComment>, rebuild: () -> Unit): JComponent {
            val thread = threadForRoot(root, all, commentById)
            val seqMap = thread.mapIndexed { index, item -> item.id to (item.floorNum ?: (index + 1)) }.toMap()
            val replies = thread.drop(1)
            val unitResolved = root.resolved
            val isCollapsed = collapsedById.getOrPut(root.id) { true }

            val wrapper = JPanel()
            wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
            wrapper.isOpaque = true
            wrapper.background = commentUnitBg
            wrapper.border = JBUI.Borders.compound(JBUI.Borders.customLine(borderColor), JBUI.Borders.empty(4))

            wrapper.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    wrapper.background = commentUnitHoverBg
                }

                override fun mouseExited(e: MouseEvent) {
                    wrapper.background = commentUnitBg
                }
            })

            val header = JPanel(BorderLayout())
            header.isOpaque = false
            val headerLeft = buildHeaderLeftPanel(
                "#${seqMap[root.id] ?: 1}",
                root.author,
                root.createdAt,
                null
            )

            val headerRight = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            headerRight.isOpaque = false
            val statusLabel = JBLabel(if (unitResolved) "已解决" else "未解决")
            statusLabel.foreground = if (unitResolved) successColor else dangerColor
            headerRight.add(statusLabel)
            headerRight.add(Box.createHorizontalStrut(JBUI.scale(4)))
            val toggle = JButton(if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown)
            toggle.isOpaque = false
            toggle.isContentAreaFilled = false
            toggle.isBorderPainted = false
            val toggleCharWidth = headerRight.getFontMetrics(headerRight.font).charWidth('0')
            val toggleSize = Dimension(toggleCharWidth * 3, toggle.preferredSize.height)
            toggle.preferredSize = toggleSize
            toggle.minimumSize = toggleSize
            toggle.maximumSize = toggleSize
            toggle.addActionListener {
                collapsedById[root.id] = !isCollapsed
                rebuild()
            }
            headerRight.add(toggle)

            header.add(headerLeft, BorderLayout.WEST)
            header.add(headerRight, BorderLayout.EAST)
            val rowGap = JBUI.scale(4)
            header.border = JBUI.Borders.emptyBottom(rowGap)
            header.maximumSize = Dimension(Int.MAX_VALUE, header.preferredSize.height)
            wrapper.add(header)

            val contentRow = JPanel(BorderLayout())
            contentRow.isOpaque = false
            val rootIndent = contentRow.getFontMetrics(contentRow.font).charWidth('中') * 3
            contentRow.border = JBUI.Borders.empty(0, rootIndent, rowGap, 0)
            contentRow.add(buildContentRow(root.content), BorderLayout.CENTER)
            wrapper.add(contentRow)
            wrapper.add(Box.createVerticalStrut(actionTopGap))

            val actions = buildActionRow(onReply = {
                replyComposerOpenById.add(root.id)
                rebuild()
            }, onResolve = {
                val handler = remoteHandler
                if (handler != null) {
                    handler.resolveThread(filePath, line, side, root)
                } else {
                    resolveThreadLocal(root.rootId)
                    rebuild()
                }
            })
            val actionsHeight = actions.preferredSize.height
            actions.maximumSize = Dimension(contentMaxWidth, actionsHeight)
            actions.preferredSize = Dimension(contentMaxWidth, actionsHeight)
            wrapper.add(actions)

            if (replyComposerOpenById.contains(root.id)) {
                val replyComposer = buildComposer(
                    "",
                    {
                        replyComposerOpenById.remove(root.id)
                        rebuild()
                    },
                    onSubmit = { text ->
                        addReply(root, text)
                        collapsedById[root.id] = false
                        pendingScrollToLatest = true
                        replyComposerOpenById.remove(root.id)
                        rebuild()
                    },
                    onBuilt = { input ->
                        val charWidth = input.getFontMetrics(input.font).charWidth('中')
                        val targetWidth = (input.preferredSize.width - charWidth * 4).coerceAtLeast(JBUI.scale(200))
                        val targetSize = Dimension(targetWidth, input.preferredSize.height)
                        input.preferredSize = targetSize
                        input.minimumSize = targetSize
                        input.maximumSize = targetSize
                    },
                    topPadding = JBUI.scale(5),
                    bottomPadding = JBUI.scale(3),
                    lineSpacing = 0.1f
                )
                val replyIndent = replyComposer.getFontMetrics(replyComposer.font).charWidth('中') * 3
                replyComposer.border = JBUI.Borders.empty(4, replyIndent, 0, 0)
                wrapper.add(replyComposer)
            }

            if (!isCollapsed) {
                val repliesPanel = JPanel()
                repliesPanel.layout = BoxLayout(repliesPanel, BoxLayout.Y_AXIS)
                repliesPanel.isOpaque = false
                repliesPanel.border = JBUI.Borders.emptyTop(4)

                if (replies.isEmpty()) {
                    val empty = JBLabel(emptyReplyText)
                    empty.foreground = textHint
                    repliesPanel.add(empty)
                } else {
                    replies.forEachIndexed { index, reply ->
                        repliesPanel.add(buildReplyUnit(reply, seqMap[reply.id] ?: (index + 2), rebuild))
                        if (index < replies.lastIndex) {
                            repliesPanel.add(Box.createVerticalStrut(unitGap))
                        }
                    }
                }

                repliesPanel.maximumSize = Dimension(Int.MAX_VALUE, repliesPanel.preferredSize.height)
                wrapper.add(repliesPanel)
            }

            return wrapper
        }

        fun buildPartCPreComment(rebuild: () -> Unit): JComponent {
            val wrapper = JPanel(BorderLayout())
            wrapper.isOpaque = true
            wrapper.background = sectionBg
            wrapper.border = JBUI.Borders.empty(2, 0, 0, 0)

            val topRow = JPanel(BorderLayout())
            topRow.isOpaque = false
            topRow.border = JBUI.Borders.emptyBottom(6)

            val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            left.isOpaque = false
            val replyButton = JButton("评论")
            replyButton.isOpaque = false
            replyButton.isContentAreaFilled = false
            replyButton.isBorderPainted = false
            replyButton.border = JBUI.Borders.empty()
            replyButton.foreground = textLink
            replyButton.margin = JBUI.emptyInsets()
            replyButton.horizontalAlignment = SwingConstants.LEFT
            left.add(replyButton)
            topRow.add(left, BorderLayout.WEST)

            fun setEditingState(isEditing: Boolean) {
                replyButton.isVisible = !isEditing
            }

            val card = CardLayout()
            val composer = JPanel(card)
            composer.isOpaque = true
            composer.background = replyUnitBg
            composer.border = JBUI.Borders.emptyTop(12)
            composer.isVisible = false

            val collapsed = JPanel(BorderLayout())
            collapsed.isOpaque = true
            collapsed.background = replyUnitBg
            val oneLine = JBTextField()
            oneLine.emptyText.text = "新增评论..."
            collapsed.add(oneLine, BorderLayout.CENTER)

            val expanded = JPanel(BorderLayout())
            expanded.isOpaque = true
            expanded.background = replyUnitBg
            expanded.border = JBUI.Borders.empty(0, JBUI.scale(6), 0, JBUI.scale(6))
            var composerInput: JComponent? = null
            val composerBody = buildComposer("", {
                if (showComposerOnly) {
                    val hasComments = LineCommentStore.hasComments(filePath, line, side)
                    if (!hasComments) {
                        popup?.cancel()
                        return@buildComposer
                    }
                    showComposerOnly = false
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
            }, lineSpacing = 0.1f)
            expanded.add(composerBody, BorderLayout.CENTER)

            composer.add(collapsed, "collapsed")
            composer.add(expanded, "expanded")
            card.show(composer, "collapsed")

            replyButton.addActionListener {
                autoOpenComposer = true
                rebuild()
            }

            oneLine.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    autoOpenComposer = true
                    rebuild()
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

        ensureRemoteIssueRoot()

        rebuild = {
            middlePanel.removeAll()
            footerPanel.removeAll()

            val all = LineCommentStore.getComments(filePath, line, side)
            val roots = all.filter { it.parentId == null }.sortedBy { it.createdAt }
            val commentById = all.associateBy { it.id }

            if (!showComposerOnly) {
                val unitsWrapper = JPanel()
                unitsWrapper.layout = BoxLayout(unitsWrapper, BoxLayout.Y_AXIS)
                unitsWrapper.isOpaque = false
                unitsWrapper.border = JBUI.Borders.empty(6, 0, 6, 0)

                if (roots.isEmpty()) {
                    val empty = JBLabel(emptyCommentText)
                    empty.foreground = textHint
                    unitsWrapper.add(empty)
                } else {
                    roots.forEachIndexed { index, root ->
                        unitsWrapper.add(buildCommentUnit(root, all, commentById) { rebuild() })
                        if (index < roots.lastIndex) {
                            unitsWrapper.add(Box.createVerticalStrut(unitGap))
                        }
                    }
                }

                middlePanel.add(unitsWrapper)
            }

            footerPanel.add(buildPartCPreComment { rebuild() }, BorderLayout.CENTER)

            val baseWidth = JBUI.scale(560)
            middlePanel.revalidate()
            middlePanel.setSize(baseWidth, Int.MAX_VALUE)
            middlePanel.doLayout()
            footerPanel.revalidate()
            footerPanel.setSize(baseWidth, Int.MAX_VALUE)
            footerPanel.doLayout()

            val firstAreaContentHeight = middlePanel.preferredSize.height
            val screenHeight = Toolkit.getDefaultToolkit().screenSize.height
            val firstAreaMaxHeight = (screenHeight * 0.6).toInt()
            val firstAreaHeight = if (firstAreaContentHeight > firstAreaMaxHeight) firstAreaMaxHeight else firstAreaContentHeight
            scrollPane.preferredSize = Dimension(baseWidth, firstAreaHeight)
            scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED

            middlePanel.repaint()
            footerPanel.repaint()

            if (pendingScrollToLatest && all.size > lastCommentCount) {
                SwingUtilities.invokeLater {
                    val bar = scrollPane.verticalScrollBar
                    bar.value = bar.maximum
                }
                pendingScrollToLatest = false
            }
            lastCommentCount = all.size

            SwingUtilities.invokeLater {
                val popupSize = popup?.size
                val preferred = container.preferredSize
                if (popupSize != null) {
                    val width = max(popupSize.width, preferred.width)
                    val height = preferred.height
                    if (width != popupSize.width || height != popupSize.height) {
                        popup?.size = Dimension(width, height)
                    }
                }
            }
        }

        container.add(scrollPane, BorderLayout.CENTER)
        container.add(footerPanel, BorderLayout.SOUTH)

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
