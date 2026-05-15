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
    private val aiIssuesByFileLine = mutableMapOf<String, Map<Int, AiIssue>>()
    private val aiFocusHighlighterByEditor = WeakHashMap<Editor, RangeHighlighter?>()

    init {
        LineCommentStore.addListener { refreshAllEditors() }
    }

    interface CommentRemoteHandler {
        fun addComment(filePath: String, line: Int, side: Side, content: String)
        fun addReply(filePath: String, line: Int, side: Side, parent: LineComment, content: String)
        fun resolveThread(filePath: String, line: Int, side: Side, root: LineComment)
    }

    private var remoteHandler: CommentRemoteHandler? = null
    private var aiIssueHandler: ((issueId: Long, issueStatus: Int, onDone: (Boolean) -> Unit) -> Unit)? = null

    fun setRemoteHandler(handler: CommentRemoteHandler?) {
        remoteHandler = handler
    }

    fun setAiIssueHandler(handler: ((issueId: Long, issueStatus: Int, onDone: (Boolean) -> Unit) -> Unit)?) {
        aiIssueHandler = handler
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

    fun updateAiIssues(filePath: String, issues: List<AiIssue>) {
        val normalized = normalizeFilePath(filePath)
        if (normalized.isBlank() || issues.isEmpty()) {
            aiIssuesByFileLine.remove(normalized)
            refreshAllEditors()
            return
        }
        val lineMap = issues
            .mapNotNull { issue ->
                val line = issue.issueCodeLine
                if (line <= 0) null else (line - 1) to issue
            }
            .toMap()
        aiIssuesByFileLine[normalized] = lineMap
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
        val normalizedPath = normalizeFilePath(context.filePath)
        val remoteIssueLines = remoteIssueLinesByFile[normalizedPath].orEmpty()
        val aiIssueLines = aiIssuesByFileLine[normalizedPath].orEmpty()
        for (line in 0 until lineCount) {
            val roots = LineCommentStore.getComments(context.filePath, line, context.side)
                .filter { it.parentId == null }
            val hasRemoteIssue = remoteIssueLines.contains(line)
            val aiIssue = aiIssueLines[line]
            if (roots.isEmpty() && !hasRemoteIssue && aiIssue == null) continue

            val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null)
            if (roots.isNotEmpty()) {
                val totalCount = roots.size
                val unresolvedCount = roots.count { !it.resolved }
                val allResolved = unresolvedCount == 0
                applyNormalLineRenderers(context, line, highlighter, unresolvedCount, totalCount, allResolved)
            } else if (context.side == Side.RIGHT && aiIssue != null) {
                highlighter.gutterIconRenderer = AiIssueGutterRenderer(context, line, aiIssue)
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
            val aiIssue = aiIssuesByFileLine[normalizeFilePath(context.filePath)]?.get(line)
            if (aiIssue != null) {
                showAiIssuePopup(editor, aiIssue)
                return
            }
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
                val normalized = normalizeFilePath(context.filePath)
                val aiIssue = aiIssuesByFileLine[normalized]?.get(line)
                if (aiIssue != null) {
                    commentHighlighter.gutterIconRenderer = AiIssueGutterRenderer(context, line, aiIssue)
                    commentHighlighter.lineMarkerRenderer = null
                } else {
                    val hasRemoteIssue = remoteIssueLinesByFile[normalized]?.contains(line) == true
                    if (hasRemoteIssue) {
                        commentHighlighter.gutterIconRenderer = ExistingIssueGutterRenderer(context, line)
                        commentHighlighter.lineMarkerRenderer = null
                    }
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
        val icon = CommentBubbleIcon()
        highlighter.gutterIconRenderer = LineCommentGutterRenderer(context, line, icon, unresolvedCount, totalCount, allResolved)
        highlighter.lineMarkerRenderer = CommentCountLineMarkerRenderer(icon, unresolvedCount, totalCount, allResolved)
    }

    private inner class AiIssueGutterRenderer(
        private val context: EditorContext,
        private val line: Int,
        private val issue: AiIssue
    ) : GutterIconRenderer() {
        override fun getIcon(): Icon = AiIssueIcon(issue.issueStatus == 0)

        override fun getTooltipText(): String = "AI评审问题"

        override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
                showAiIssuePopup(editor, issue)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AiIssueGutterRenderer) return false
            return context.filePath == other.context.filePath &&
                context.side == other.context.side &&
                line == other.line &&
                issue.id == other.issue.id
        }

        override fun hashCode(): Int {
            var result = context.filePath.hashCode()
            result = 31 * result + context.side.hashCode()
            result = 31 * result + line
            result = 31 * result + issue.id.hashCode()
            return result
        }
    }

    private inner class ExistingIssueGutterRenderer(
        private val context: EditorContext,
        private val line: Int
    ) : GutterIconRenderer() {
        override fun getIcon(): Icon = CommentBubbleIcon()

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

    private class CommentBubbleIcon : Icon {
        private val width = JBUI.scale(14)
        private val height = JBUI.scale(14)
        private val fill = JBColor(Color.WHITE, Color.WHITE)
        private val outline = JBColor(Color(0xB8C1CC), Color(0x7F8790))

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                val bodyX = x + JBUI.scale(1f)
                val bodyY = y + JBUI.scale(1f)
                val bodyWidth = width - JBUI.scale(3f)
                val bodyHeight = height - JBUI.scale(5f)
                val arc = JBUI.scale(5f)
                val body = java.awt.geom.RoundRectangle2D.Float(bodyX, bodyY, bodyWidth, bodyHeight, arc, arc)
                val tail = java.awt.geom.Path2D.Float().apply {
                    moveTo(bodyX + bodyWidth * 0.32f, bodyY + bodyHeight)
                    lineTo(bodyX + bodyWidth * 0.48f, bodyY + bodyHeight)
                    lineTo(bodyX + bodyWidth * 0.24f, y + height - JBUI.scale(1f))
                    closePath()
                }

                g2.color = fill
                g2.fill(body)
                g2.fill(tail)

                g2.color = outline
                g2.stroke = BasicStroke(JBUI.scale(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(body)
                g2.draw(tail)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = width

        override fun getIconHeight(): Int = height
    }

    private class AiIssueIcon(private val unresolved: Boolean) : Icon {
        private val size = JBUI.scale(14)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val bg = if (unresolved) JBColor(Color(0xD93025), Color(0xF47067)) else JBColor(Color(0x1E8E3E), Color(0x57D163))
                g2.color = bg
                g2.fillOval(x, y, size, size)
                g2.color = bg.darker()
                g2.drawOval(x, y, size, size)

                g2.color = Color.WHITE
                val text = "AI"
                g2.font = g2.font.deriveFont(Font.BOLD, JBUI.scale(8f))
                val fm = g2.fontMetrics
                val tx = x + (size - fm.stringWidth(text)) / 2
                val ty = y + (size + fm.ascent - fm.descent) / 2
                g2.drawString(text, tx, ty)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size
    }

    private fun showAiIssuePopup(editor: Editor, issue: AiIssue) {
        highlightAiIssueRange(editor, issue)

        val bgMain = JBColor(Color(0xF7F8FA), Color(0x2B2D30))
        val bgHeader = JBColor(Color(0xF3F4F6), Color(0x313438))
        val bgCard = JBColor(Color(0xFFFFFF), Color(0x3C3F41))
        val bgCode = JBColor(Color(0xF7F8FA), Color(0x35383C))
        val textMain = JBColor(Color(0x5F6368), Color(0x9AA0A6))
        val textContent = JBColor(Color(0x202124), Color(0xDFE1E5))
        val textDim = JBColor(Color(0x80868B), Color(0x7F8790))
        val accentBlue = JBColor(Color(0x1A73E8), Color(0x6EA8FF))
        val accentGreen = JBColor(Color(0x1E8E3E), Color(0x57D163))
        val accentOrange = JBColor(Color(0xF29900), Color(0xF6C26B))
        val accentRed = JBColor(Color(0xD93025), Color(0xF47067))
        val borderColor = JBColor(Color(0xD0D7DE), Color(0x4B5563))
        val popupWidth = JBUI.scale(520)
        val popupMinHeight = JBUI.scale(240)
        val popupMaxHeight = JBUI.scale(640)

        fun alpha(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

        open class RoundedBlockPanel(
            private val fill: Color,
            private val arc: Int,
            private val leftStripe: Color? = null,
            private val leftStripeWidth: Int = JBUI.scale(4)
        ) : JPanel() {
            init {
                isOpaque = false
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val clip = g2.clip
                    g2.color = fill
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    if (leftStripe != null) {
                        g2.clipRect(0, 0, leftStripeWidth, height)
                        g2.color = leftStripe
                        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                        g2.clip = clip
                    }
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }

        fun statusText(status: Int): String = when (status) {
            0 -> "未解决"
            1 -> "已采纳"
            2 -> "误报"
            3 -> "已忽略"
            else -> "未知（$status）"
        }

        fun statusColor(status: Int): Color = when (status) {
            0 -> accentOrange
            1 -> accentGreen
            2 -> accentBlue
            3 -> textMain
            else -> textDim
        }

        fun severityText(severity: Int): String = if (severity == 1) "错误级" else "警告级"

        fun severityColor(severity: Int): Color = if (severity == 1) accentRed else accentOrange

        fun createRoundedButton(
            text: String,
            fillColor: Color,
            hoverFillColor: Color,
            foregroundColor: Color,
            outlineColor: Color? = null,
            padding: Insets,
            bold: Boolean = false,
            fontSize: Float = 12f
        ): JButton {
            val button = object : JButton(text) {
                var hovered = false

                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = if (hovered) hoverFillColor else fillColor
                        g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
                        if (outlineColor != null) {
                            g2.color = if (hovered) hoverFillColor.darker() else outlineColor
                            g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                        }
                    } finally {
                        g2.dispose()
                    }
                    super.paintComponent(g)
                }
            }
            button.font = button.font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, fontSize)
            button.foreground = foregroundColor
            button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            button.isFocusPainted = false
            button.isOpaque = false
            button.isContentAreaFilled = false
            button.isBorderPainted = false
            button.margin = JBUI.emptyInsets()
            button.border = JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right)
            button.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    button.hovered = true
                    button.repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    button.hovered = false
                    button.repaint()
                }
            })
            return button
        }

        fun createActionButton(
            text: String,
            foreground: Color = textMain,
            border: Color = borderColor,
            fill: Color = alpha(borderColor, 18),
            hoverFill: Color = alpha(borderColor, 30)
        ): JButton {
            return createRoundedButton(
                text = text,
                fillColor = fill,
                hoverFillColor = hoverFill,
                foregroundColor = foreground,
                outlineColor = border,
                padding = JBUI.insets(3, 10),
                fontSize = 11f
            )
        }

        fun createPrimaryButton(text: String, fill: Color): JButton {
            return createRoundedButton(
                text = text,
                fillColor = fill,
                hoverFillColor = fill.brighter(),
                foregroundColor = Color.WHITE,
                padding = JBUI.insets(3, 10),
                bold = true,
                fontSize = 11f
            )
        }

        fun createStatusPill(text: String, textColor: Color): JComponent {
            val horizontalPadding = JBUI.scale(8)
            val verticalPadding = JBUI.scale(2)
            val label = JBLabel(text, SwingConstants.CENTER).apply {
                foreground = textColor
                font = font.deriveFont(11f)
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
            }
            val labelSize = label.preferredSize
            val size = Dimension(
                labelSize.width + horizontalPadding * 2,
                max(labelSize.height + verticalPadding * 2, JBUI.scale(20))
            )
            return object : JPanel(BorderLayout()) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        val arc = JBUI.scale(16)
                        g2.color = alpha(textColor, 38)
                        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                        g2.color = alpha(textColor, 90)
                        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    } finally {
                        g2.dispose()
                    }
                    super.paintComponent(g)
                }
            }.apply {
                isOpaque = false
                border = JBUI.Borders.empty(verticalPadding, horizontalPadding)
                add(label, BorderLayout.CENTER)
                preferredSize = size
                minimumSize = size
                maximumSize = size
            }
        }

        fun createReadOnlyArea(text: String, wrapWidth: Int): JBTextArea {
            return JBTextArea(text.ifBlank { "-" }).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                foreground = textContent
                font = font.deriveFont(13f)
                border = JBUI.Borders.empty()
                isFocusable = false
                alignmentX = Component.LEFT_ALIGNMENT
                val measuredWidth = wrapWidth.coerceAtLeast(JBUI.scale(180))
                setSize(Dimension(measuredWidth, Int.MAX_VALUE))
                val measured = preferredSize
                preferredSize = Dimension(measuredWidth, measured.height)
                minimumSize = preferredSize
                maximumSize = Dimension(Int.MAX_VALUE, measured.height)
            }
        }

        fun createInfoCard(title: String, value: String, stripeColor: Color, codeBlock: Boolean = false): JComponent {
            val card = RoundedBlockPanel(bgCard, JBUI.scale(8), stripeColor)
            card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
            card.border = javax.swing.BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(alpha(borderColor, 140)),
                JBUI.Borders.empty(10, 10, 10, 12)
            )
            card.alignmentX = Component.LEFT_ALIGNMENT
            val contentWrapWidth = popupWidth - JBUI.scale(76)

            card.add(JBLabel(title).apply {
                foreground = textMain
                font = font.deriveFont(Font.BOLD, 12f)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(8)
            })

            if (codeBlock) {
                val codeArea = JBTextArea(value.ifBlank { "-" }).apply {
                    isEditable = false
                    lineWrap = false
                    wrapStyleWord = false
                    font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                    background = bgCode
                    foreground = textContent
                    caretColor = textContent
                    border = JBUI.Borders.empty(8)
                }
                val codeScroll = JBScrollPane(codeArea).apply {
                    border = JBUI.Borders.customLine(alpha(borderColor, 140))
                    viewport.background = bgCode
                    background = bgCode
                    preferredSize = Dimension(0, JBUI.scale(140))
                    minimumSize = Dimension(0, JBUI.scale(120))
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                }
                card.add(codeScroll)
            } else {
                card.add(createReadOnlyArea(value, contentWrapWidth))
            }

            card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
            return card
        }

        var currentIssue = issue
        val severityTextValue = severityText(issue.issueSeverity)
        val severityColorValue = severityColor(issue.issueSeverity)

        val container = JPanel(BorderLayout())
        container.background = bgMain
        container.border = JBUI.Borders.customLine(borderColor)
        container.preferredSize = Dimension(popupWidth, JBUI.scale(420))
        container.minimumSize = container.preferredSize
        container.maximumSize = container.preferredSize

        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = bgHeader
            border = javax.swing.BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(borderColor, 0, 0, 1, 0),
                JBUI.Borders.empty(0, 12)
            )
            val headerHeight = JBUI.scale(40)
            preferredSize = Dimension(0, headerHeight)
            minimumSize = Dimension(0, headerHeight)
            maximumSize = Dimension(Int.MAX_VALUE, headerHeight)
        }
        val headerIcon = JBLabel(AiIssueIcon(issue.issueStatus == 0)).apply {
            border = JBUI.Borders.emptyRight(8)
        }
        val titleLabel = JBLabel("AI评审问题 · L${issue.issueCodeLine}").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = textContent
        }
        val headerMetaPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        headerPanel.add(headerIcon)
        headerPanel.add(titleLabel)
        headerPanel.add(Box.createHorizontalGlue())
        headerPanel.add(headerMetaPanel)

        val middlePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bgMain
            border = JBUI.Borders.empty(12)
        }
        middlePanel.add(createInfoCard("问题描述", issue.issueDescription, severityColorValue))
        middlePanel.add(Box.createVerticalStrut(JBUI.scale(10)))
        middlePanel.add(createInfoCard("修复建议", issue.issueFixSuggestion, accentBlue))
        middlePanel.add(Box.createVerticalStrut(JBUI.scale(10)))
        middlePanel.add(createInfoCard("建议修复代码", issue.issueFixCode, accentBlue, codeBlock = true))

        val scrollPane = JBScrollPane(middlePanel).apply {
            border = JBUI.Borders.empty()
            viewport.background = bgMain
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }

        val footerPanel = JPanel(BorderLayout()).apply {
            background = bgHeader
            border = JBUI.Borders.customLine(borderColor, 1, 0, 0, 0)
        }
        val footerBar = JPanel(BorderLayout()).apply {
            background = bgHeader
            border = JBUI.Borders.empty(0, 12)
            val footerHeight = JBUI.scale(48)
            preferredSize = Dimension(0, footerHeight)
            minimumSize = Dimension(0, footerHeight)
            maximumSize = Dimension(Int.MAX_VALUE, footerHeight)
        }
        val footerHintLabel = JBLabel().apply {
            foreground = textDim
            font = font.deriveFont(12f)
        }
        footerBar.add(footerHintLabel, BorderLayout.WEST)

        val footerActions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        footerBar.add(footerActions, BorderLayout.EAST)

        var popup: com.intellij.openapi.ui.popup.JBPopup? = null

        fun refreshPopupSize() {
            SwingUtilities.invokeLater {
                middlePanel.revalidate()
                middlePanel.repaint()
                val targetHeight = (headerPanel.preferredSize.height + footerPanel.preferredSize.height + middlePanel.preferredSize.height)
                    .coerceIn(popupMinHeight, popupMaxHeight)
                val targetSize = Dimension(popupWidth, targetHeight)
                val window = SwingUtilities.getWindowAncestor(container) ?: return@invokeLater
                if (container.preferredSize != targetSize) {
                    container.preferredSize = targetSize
                    container.minimumSize = targetSize
                    container.maximumSize = targetSize
                    window.size = targetSize
                    window.validate()
                }
                window.background = Color(0, 0, 0, 0)
                window.shape = java.awt.geom.RoundRectangle2D.Double(
                    0.0,
                    0.0,
                    window.width.toDouble(),
                    window.height.toDouble(),
                    JBUI.scale(8).toDouble(),
                    JBUI.scale(8).toDouble()
                )
            }
        }

        fun renderIssueState() {
            val statusTextValue = statusText(currentIssue.issueStatus)
            val statusColorValue = statusColor(currentIssue.issueStatus)
            headerIcon.icon = AiIssueIcon(currentIssue.issueStatus == 0)

            headerMetaPanel.removeAll()
            headerMetaPanel.add(createStatusPill(severityTextValue, severityColorValue))
            headerMetaPanel.add(Box.createHorizontalStrut(JBUI.scale(8)))
            headerMetaPanel.add(createStatusPill(statusTextValue, statusColorValue))

            footerHintLabel.text = if (currentIssue.issueStatus == 0) {
                ""
            } else {
                "当前状态：$statusTextValue"
            }

            footerActions.removeAll()
            if (currentIssue.issueStatus == 0) {
                val actionButtons = mutableListOf<JButton>()
                fun bindHandle(button: JButton, status: Int) {
                    actionButtons += button
                    button.addActionListener {
                        val handler = aiIssueHandler ?: return@addActionListener
                        actionButtons.forEach { it.isEnabled = false }
                        handler.invoke(currentIssue.id, status) { success ->
                            SwingUtilities.invokeLater {
                                actionButtons.forEach { it.isEnabled = true }
                                if (success) {
                                    currentIssue = currentIssue.copy(issueStatus = status)
                                    renderIssueState()
                                }
                            }
                        }
                    }
                }

                val falsePositiveButton = createActionButton(
                    "误报",
                    foreground = accentBlue,
                    border = alpha(accentBlue, 90),
                    fill = alpha(accentBlue, 18),
                    hoverFill = alpha(accentBlue, 30)
                )
                val ignoreButton = createActionButton("忽略")
                val acceptButton = createPrimaryButton("采纳", accentGreen)
                bindHandle(falsePositiveButton, 2)
                bindHandle(ignoreButton, 3)
                bindHandle(acceptButton, 1)
                footerActions.add(falsePositiveButton)
                footerActions.add(Box.createHorizontalStrut(JBUI.scale(8)))
                footerActions.add(ignoreButton)
                footerActions.add(Box.createHorizontalStrut(JBUI.scale(8)))
                footerActions.add(acceptButton)
            }

            headerMetaPanel.revalidate()
            headerMetaPanel.repaint()
            footerActions.revalidate()
            footerActions.repaint()
            footerBar.revalidate()
            footerBar.repaint()
            footerPanel.revalidate()
            footerPanel.repaint()
            refreshPopupSize()
        }

        footerPanel.add(footerBar, BorderLayout.CENTER)

        container.add(headerPanel, BorderLayout.NORTH)
        container.add(scrollPane, BorderLayout.CENTER)
        container.add(footerPanel, BorderLayout.SOUTH)

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(container, null)
            .setShowBorder(false)
            .setShowShadow(false)
            .setResizable(false)
            .setRequestFocus(true)
            .createPopup()

        renderIssueState()
        popup.showInBestPositionFor(editor)
        SwingUtilities.invokeLater {
            refreshPopupSize()
            val window = SwingUtilities.getWindowAncestor(container) ?: return@invokeLater

            var lastScreenPoint: Point? = null
            val dragHandler = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    lastScreenPoint = e.locationOnScreen
                }

                override fun mouseDragged(e: MouseEvent) {
                    val prev = lastScreenPoint ?: return
                    val current = e.locationOnScreen
                    window.setLocation(window.x + (current.x - prev.x), window.y + (current.y - prev.y))
                    lastScreenPoint = current
                }
            }
            headerPanel.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            titleLabel.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            headerPanel.addMouseListener(dragHandler)
            headerPanel.addMouseMotionListener(dragHandler)
            titleLabel.addMouseListener(dragHandler)
            titleLabel.addMouseMotionListener(dragHandler)
        }
    }

    private fun highlightAiIssueRange(editor: Editor, issue: AiIssue) {
        aiFocusHighlighterByEditor.remove(editor)?.let { editor.markupModel.removeHighlighter(it) }
        val startLine = (issue.issueCodeSnippetStartLine - 1).coerceAtLeast(0)
        val endLine = (issue.issueCodeSnippetEndLine - 1).coerceAtLeast(startLine)
        if (startLine >= editor.document.lineCount) return
        val boundedEnd = endLine.coerceAtMost(editor.document.lineCount - 1)
        val startOffset = editor.document.getLineStartOffset(startLine)
        val endOffset = editor.document.getLineEndOffset(boundedEnd)
        val attrs = editor.colorsScheme.defaultBackground.let {
            val bg = JBColor(Color(0xFFE8E8), Color(0x5A2A2A))
            com.intellij.openapi.editor.markup.TextAttributes(null, bg, null, null, Font.PLAIN)
        }
        val highlighter = editor.markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.SELECTION - 1,
            attrs,
            com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
        )
        aiFocusHighlighterByEditor[editor] = highlighter
    }

    private fun showPopup(editor: Editor, filePath: String, line: Int, side: Side, openComposerOnly: Boolean = false) {
        val currentUserName = System.getenv("USERID").orEmpty().ifBlank { "本地用户" }
        val isMac = System.getProperty("os.name").contains("mac", ignoreCase = true)
        val submitShortcutText = if (isMac) "⌘Enter" else "Ctrl+Enter"

        val bgMain = JBColor(Color(0xF7F8FA), Color(0x2B2D30))
        val bgHeader = JBColor(Color(0xF3F4F6), Color(0x313438))
        val bgCard = JBColor(Color(0xFFFFFF), Color(0x3C3F41))
        val bgReply = JBColor(Color(0xF7F8FA), Color(0x35383C))
        val bgComposer = JBColor(Color(0xFFFFFF), Color(0x2F3337))
        val textMain = JBColor(Color(0x5F6368), Color(0x9AA0A6))
        val textContent = JBColor(Color(0x202124), Color(0xDFE1E5))
        val textDim = JBColor(Color(0x80868B), Color(0x7F8790))
        val accentBlue = JBColor(Color(0x1A73E8), Color(0x6EA8FF))
        val accentGreen = JBColor(Color(0x1E8E3E), Color(0x57D163))
        val accentOrange = JBColor(Color(0xF29900), Color(0xF6C26B))
        val borderColor = JBColor(Color(0xD0D7DE), Color(0x4B5563))

        fun alpha(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

        val mutedButtonFill = alpha(borderColor, 18)
        val mutedButtonHoverFill = alpha(borderColor, 30)

        fun formatRootTime(timestamp: Long): String {
            val calendar = java.util.Calendar.getInstance()
            val target = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            val isSameDay = calendar.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
                calendar.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
            if (isSameDay) {
                return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
            }
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val isYesterday = calendar.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
                calendar.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
            return if (isYesterday) {
                "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))}"
            } else {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
            }
        }

        fun formatReplyTime(timestamp: Long): String {
            val calendar = java.util.Calendar.getInstance()
            val target = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            val isSameDay = calendar.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
                calendar.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
            if (isSameDay) {
                return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
            }
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val isYesterday = calendar.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
                calendar.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
            return if (isYesterday) {
                "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))}"
            } else {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
            }
        }

        fun displayFloorNum(comment: LineComment, floorMap: Map<String, Int>): Int {
            return floorMap[comment.id] ?: comment.floorNum ?: 0
        }

        open class RoundedBlockPanel(
            private val fill: Color,
            private val arc: Int,
            private val leftStripe: Color? = null,
            private val leftStripeWidth: Int = JBUI.scale(4),
            private val drawShadow: Boolean = false,
            private val outlineColor: Color? = null,
            private val outlineWidth: Float = JBUI.scale(1f)
        ) : JPanel() {
            init {
                isOpaque = false
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                    if (drawShadow) {
                        g2.color = alpha(Color.BLACK, 26)
                        g2.fillRoundRect(JBUI.scale(1), JBUI.scale(2), width - JBUI.scale(2), height - JBUI.scale(3), arc, arc)
                    }
                    val clip = g2.clip
                    g2.color = fill
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    if (leftStripe != null) {
                        g2.clipRect(0, 0, leftStripeWidth, height)
                        g2.color = leftStripe
                        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                        g2.clip = clip
                    }
                    if (outlineColor != null) {
                        g2.color = outlineColor
                        g2.stroke = BasicStroke(outlineWidth)
                        val inset = outlineWidth / 2f
                        g2.drawRoundRect(
                            inset.toInt(),
                            inset.toInt(),
                            (width - 1 - outlineWidth).coerceAtLeast(0f).toInt(),
                            (height - 1 - outlineWidth).coerceAtLeast(0f).toInt(),
                            arc,
                            arc
                        )
                    }
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }

        class PlaceholderTextArea(private val placeholder: String) : JBTextArea() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (text.isNotEmpty()) return
                val g2 = g.create() as Graphics2D
                try {
                    g2.color = textDim
                    g2.font = font
                    val x = insets.left + JBUI.scale(2)
                    val y = insets.top + g2.fontMetrics.ascent
                    g2.drawString(placeholder, x, y)
                } finally {
                    g2.dispose()
                }
            }
        }

        fun avatarColor(name: String): Color {
            val palette = listOf(
                Color(0xE57373),
                Color(0x64B5F6),
                Color(0x81C784),
                Color(0xBA68C8),
                Color(0xF29900),
                Color(0x1A73E8)
            )
            return palette[name.trim().lowercase().hashCode().absoluteValue % palette.size]
        }

        fun createAvatar(name: String, size: Int = 24, text: String? = null, color: Color = avatarColor(name)): JComponent {
            val avatar = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = color
                        g2.fillOval(0, 0, width, height)
                        g2.color = Color.WHITE
                        val label = text ?: name.take(1).uppercase().ifBlank { "?" }
                        val fontSize = if (label.length > 1) 10f else 12f
                        g2.font = font.deriveFont(Font.BOLD, JBUI.scale(fontSize))
                        val fm = g2.fontMetrics
                        val x = (width - fm.stringWidth(label)) / 2
                        val y = (height - fm.height) / 2 + fm.ascent
                        g2.drawString(label, x, y)
                    } finally {
                        g2.dispose()
                    }
                }
            }
            avatar.isOpaque = false
            val scaled = JBUI.scale(size)
            val dimension = Dimension(scaled, scaled)
            avatar.preferredSize = dimension
            avatar.minimumSize = dimension
            avatar.maximumSize = dimension
            return avatar
        }

        fun createTextArea(text: String, background: Color, foreground: Color): JComponent {
            val area = JBTextArea(text)
            area.isEditable = false
            area.lineWrap = true
            area.wrapStyleWord = true
            area.font = area.font.deriveFont(13f)
            area.background = background
            area.foreground = foreground
            area.border = JBUI.Borders.empty()
            area.isOpaque = false
            area.isFocusable = false
            area.alignmentX = Component.LEFT_ALIGNMENT
            area.maximumSize = Dimension(Int.MAX_VALUE, area.preferredSize.height)
            return area
        }

        fun createRoundedButton(
            text: String,
            fillColor: Color,
            hoverFillColor: Color,
            foregroundColor: Color,
            outlineColor: Color? = null,
            fontSize: Float,
            bold: Boolean = false,
            padding: Insets,
            arc: Int = JBUI.scale(8)
        ): JButton {
            val button = object : JButton(text) {
                var hovered = false

                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = if (hovered) hoverFillColor else fillColor
                        g2.fillRoundRect(0, 0, width, height, arc, arc)
                        if (outlineColor != null) {
                            g2.color = if (hovered) hoverFillColor.darker() else outlineColor
                            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                        }
                    } finally {
                        g2.dispose()
                    }
                    super.paintComponent(g)
                }
            }
            button.font = button.font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, fontSize)
            button.foreground = foregroundColor
            button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            button.isFocusPainted = false
            button.isOpaque = false
            button.isContentAreaFilled = false
            button.isBorderPainted = false
            button.margin = JBUI.emptyInsets()
            button.border = JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right)
            button.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    button.hovered = true
                    button.repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    button.hovered = false
                    button.repaint()
                }
            })
            return button
        }

        fun createActionButton(
            text: String,
            foreground: Color = textMain,
            border: Color = borderColor,
            fill: Color = mutedButtonFill,
            hoverFill: Color = mutedButtonHoverFill
        ): JButton {
            return createRoundedButton(
                text = text,
                fillColor = fill,
                hoverFillColor = hoverFill,
                foregroundColor = foreground,
                outlineColor = border,
                fontSize = 11f,
                padding = JBUI.insets(1, 3),
                arc = JBUI.scale(8)
            )
        }

        fun createPrimaryButton(text: String, compact: Boolean = false): JButton {
            return createRoundedButton(
                text = text,
                fillColor = accentBlue,
                hoverFillColor = accentBlue.brighter(),
                foregroundColor = Color.WHITE,
                fontSize = if (compact) 11f else 13f,
                bold = true,
                padding = if (compact) JBUI.insets(2, 3) else JBUI.insets(2, 5),
                arc = JBUI.scale(8)
            )
        }

        fun createToggleButton(text: String, onClick: () -> Unit): JButton {
            return JButton(text).apply {
                font = font.deriveFont(11f)
                foreground = accentBlue
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                margin = JBUI.emptyInsets()
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { onClick() }
            }
        }

        fun createStatusPill(text: String, textColor: Color): JComponent {
            val label = JBLabel(text)
            label.foreground = textColor
            label.font = label.font.deriveFont(11f)
            return RoundedBlockPanel(alpha(textColor, 38), JBUI.scale(20)).apply {
                layout = BorderLayout()
                border = javax.swing.BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(alpha(textColor, 90)),
                    JBUI.Borders.empty(2, 8)
                )
                add(label, BorderLayout.CENTER)
                preferredSize = Dimension(preferredSize.width, JBUI.scale(20))
            }
        }

        fun createStatusText(text: String, textColor: Color): JComponent {
            return JBLabel(text).apply {
                foreground = textColor
                font = font.deriveFont(11f)
            }
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

        fun addRootComment(text: String) {
            val content = text.trim()
            if (content.isBlank() || content.length > MAX_COMMENT_LENGTH) return
            val handler = remoteHandler
            if (handler != null) {
                handler.addComment(filePath, line, side, content)
            } else {
                LineCommentStore.addComment(filePath, line, side, content, currentUserName)
            }
        }

        fun addReply(target: LineComment, text: String) {
            val content = text.trim()
            if (content.isBlank() || content.length > MAX_COMMENT_LENGTH) return
            val handler = remoteHandler
            if (handler != null) {
                handler.addReply(filePath, line, side, target, content)
            } else {
                LineCommentStore.addReply(
                    filePath = filePath,
                    line = line,
                    side = side,
                    parentId = target.id,
                    content = content,
                    author = currentUserName,
                    rootId = target.rootId,
                    replyFloorNum = target.floorNum ?: target.replyFloorNum
                )
            }
        }

        fun resolveThread(root: LineComment, onDone: () -> Unit) {
            val handler = remoteHandler
            if (handler != null) {
                handler.resolveThread(filePath, line, side, root)
            } else {
                LineCommentStore.getComments(filePath, line, side)
                    .filter { it.rootId == root.rootId }
                    .forEach { item -> LineCommentStore.resolveComment(filePath, line, side, item.id) }
                onDone()
            }
        }

        fun findThreadReplies(root: LineComment, all: List<LineComment>): List<LineComment> {
            return all.filter { it.parentId != null && it.rootId == root.id }
                .sortedWith(compareBy<LineComment> { it.floorNum ?: Int.MAX_VALUE }.thenBy { it.createdAt })
        }

        fun buildDisplayFloorMap(roots: List<LineComment>, all: List<LineComment>): Map<String, Int> {
            val usedFloorNums = all.mapNotNull { it.floorNum }.toMutableSet()
            val displayMap = linkedMapOf<String, Int>()
            var nextFloorNum = 1

            fun nextAvailableFloorNum(): Int {
                while (nextFloorNum in usedFloorNums) {
                    nextFloorNum++
                }
                return nextFloorNum++
            }

            roots.forEach { root ->
                displayMap[root.id] = root.floorNum ?: nextAvailableFloorNum()
                findThreadReplies(root, all).forEach { reply ->
                    displayMap[reply.id] = reply.floorNum ?: nextAvailableFloorNum()
                }
            }
            return displayMap
        }

        val popupWidth = JBUI.scale(450)
        val popupMinHeight = JBUI.scale(80)
        val popupMaxHeight = JBUI.scale(600)

        val container = JPanel(BorderLayout())
        container.background = bgMain
        container.border = JBUI.Borders.customLine(borderColor)
        container.preferredSize = Dimension(popupWidth, JBUI.scale(300))
        container.minimumSize = container.preferredSize
        container.maximumSize = container.preferredSize
        container.isFocusable = true

        val focusAnchor = EditorTextField().apply {
            setOneLineMode(true)
            border = JBUI.Borders.empty()
            isOpaque = false
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
        }
        container.add(focusAnchor, BorderLayout.WEST)

        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.X_AXIS)
        headerPanel.background = bgHeader
        headerPanel.border = javax.swing.BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(borderColor, 0, 0, 1, 0),
            JBUI.Borders.empty(0, 12)
        )
        val headerHeight = JBUI.scale(40)
        val headerSize = Dimension(0, headerHeight)
        headerPanel.preferredSize = headerSize
        headerPanel.minimumSize = headerSize
        headerPanel.maximumSize = Dimension(Int.MAX_VALUE, headerHeight)

        val headerIconLabel = JBLabel(CommentBubbleIcon()).apply {
            border = JBUI.Borders.emptyRight(8)
        }
        val titleLabel = JBLabel("行评论 · L${line + 1}")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
        titleLabel.foreground = textContent
        val countLabel = JBLabel("0个评论单元")
        countLabel.foreground = textContent
        countLabel.font = countLabel.font.deriveFont(11f)

        headerPanel.add(headerIconLabel)
        headerPanel.add(titleLabel)
        headerPanel.add(Box.createHorizontalGlue())
        headerPanel.add(countLabel)

        val middlePanel = JPanel()
        middlePanel.layout = BoxLayout(middlePanel, BoxLayout.Y_AXIS)
        middlePanel.background = bgMain
        middlePanel.border = JBUI.Borders.empty(12, 12, 12, 12)

        val scrollPane = JBScrollPane(middlePanel)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.viewport.background = bgMain
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBar.unitIncrement = JBUI.scale(16)

        val footerPanel = JPanel(BorderLayout())
        footerPanel.background = bgHeader
        footerPanel.border = JBUI.Borders.customLine(borderColor, 1, 0, 0, 0)

        container.add(headerPanel, BorderLayout.NORTH)
        container.add(scrollPane, BorderLayout.CENTER)
        container.add(footerPanel, BorderLayout.SOUTH)

        var popup: com.intellij.openapi.ui.popup.JBPopup? = null
        var activeReplyTargetId: String? = null
        var preCommentVisible = openComposerOnly
        var pendingScrollToBottom = false
        val launchedComposerOnly = openComposerOnly
        val collapsedByRootId = mutableMapOf<String, Boolean>()
        lateinit var rebuild: () -> Unit

        fun closePopupIfNeeded() {
            if (launchedComposerOnly && !LineCommentStore.hasComments(filePath, line, side)) {
                popup?.cancel()
            }
        }

        fun updatePopupWindowShape(window: Window) {
            window.shape = java.awt.geom.RoundRectangle2D.Double(
                0.0,
                0.0,
                window.width.toDouble(),
                window.height.toDouble(),
                JBUI.scale(8).toDouble(),
                JBUI.scale(8).toDouble()
            )
        }

        fun buildComposerPanel(
            onCancel: () -> Unit,
            onSubmit: (String) -> Unit
        ): JComponent {
            val panel = RoundedBlockPanel(
                fill = bgComposer,
                arc = JBUI.scale(10),
                outlineColor = accentBlue,
                outlineWidth = JBUI.scale(1f)
            )
            panel.layout = BorderLayout(0, JBUI.scale(5))
            panel.border = JBUI.Borders.empty(8)
            panel.alignmentX = Component.LEFT_ALIGNMENT

            val area = PlaceholderTextArea("输入回复内容...")
            area.lineWrap = true
            area.wrapStyleWord = true
            area.rows = 3
            area.isOpaque = false
            area.background = bgComposer
            area.foreground = textContent
            area.caretColor = textContent
            area.font = area.font.deriveFont(13f)
            area.border = JBUI.Borders.empty()
            area.margin = JBUI.insets(0)

            val scroll = JBScrollPane(area)
            scroll.isOpaque = false
            scroll.border = JBUI.Borders.empty()
            scroll.viewport.isOpaque = false
            scroll.viewport.background = bgComposer
            scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            scroll.preferredSize = Dimension(0, JBUI.scale(60))
            scroll.minimumSize = Dimension(0, JBUI.scale(60))

            val cancelButton = JButton("取消")
            cancelButton.font = cancelButton.font.deriveFont(11f)
            cancelButton.foreground = textMain
            cancelButton.isOpaque = false
            cancelButton.isContentAreaFilled = false
            cancelButton.isBorderPainted = false
            cancelButton.isFocusPainted = false
            cancelButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            cancelButton.addActionListener { onCancel() }

            val submitButton = createPrimaryButton("发送 $submitShortcutText", compact = true)
            submitButton.addActionListener { onSubmit(area.text) }

            val modifierMask = if (isMac) java.awt.event.InputEvent.META_DOWN_MASK else java.awt.event.InputEvent.CTRL_DOWN_MASK
            val keyStroke = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, modifierMask)
            area.inputMap.put(keyStroke, "submit-comment")
            area.actionMap.put("submit-comment", object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    onSubmit(area.text)
                }
            })

            val actionRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0))
            actionRow.isOpaque = false
            actionRow.alignmentX = Component.LEFT_ALIGNMENT
            actionRow.add(cancelButton)
            actionRow.add(submitButton)

            panel.add(scroll, BorderLayout.CENTER)
            panel.add(actionRow, BorderLayout.SOUTH)
            panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)

            SwingUtilities.invokeLater { area.requestFocusInWindow() }
            return panel
        }

        fun buildReplyCard(reply: LineComment, floorMap: Map<String, Int>): JComponent {
            val replyCard = RoundedBlockPanel(
                fill = bgReply,
                arc = JBUI.scale(10),
                outlineColor = alpha(borderColor, 140),
                outlineWidth = JBUI.scale(1f)
            )
            replyCard.layout = BoxLayout(replyCard, BoxLayout.Y_AXIS)
            replyCard.border = JBUI.Borders.empty(8)
            replyCard.alignmentX = Component.LEFT_ALIGNMENT

            val headerRow = JPanel()
            headerRow.layout = BoxLayout(headerRow, BoxLayout.X_AXIS)
            headerRow.isOpaque = false
            headerRow.alignmentX = Component.LEFT_ALIGNMENT
            headerRow.add(createAvatar(reply.author, size = 24))
            headerRow.add(Box.createHorizontalStrut(JBUI.scale(8)))
            headerRow.add(JBLabel(reply.author).apply {
                font = font.deriveFont(Font.BOLD, 13f)
                foreground = textContent
                border = JBUI.Borders.emptyRight(6)
            })
            headerRow.add(JBLabel("#${displayFloorNum(reply, floorMap)}").apply {
                foreground = textDim
                border = JBUI.Borders.emptyRight(6)
            })
            headerRow.add(JBLabel(formatReplyTime(reply.createdAt)).apply {
                foreground = textDim
                font = font.deriveFont(11f)
            })
            headerRow.add(Box.createHorizontalGlue())
            replyCard.add(headerRow)
            replyCard.add(Box.createVerticalStrut(JBUI.scale(8)))

            replyCard.add(createTextArea(reply.content, bgReply, textContent))
            replyCard.add(Box.createVerticalStrut(JBUI.scale(10)))

            val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            actionRow.isOpaque = false
            actionRow.alignmentX = Component.LEFT_ALIGNMENT
            val replyButton = createActionButton("↩ 回复")
            replyButton.addActionListener {
                activeReplyTargetId = reply.id
                preCommentVisible = false
                rebuild()
            }
            actionRow.add(replyButton)
            replyCard.add(actionRow)

            if (activeReplyTargetId == reply.id) {
                replyCard.add(Box.createVerticalStrut(JBUI.scale(8)))
                replyCard.add(buildComposerPanel(
                    onCancel = {
                        activeReplyTargetId = null
                        rebuild()
                    },
                    onSubmit = { text ->
                        addReply(reply, text)
                        activeReplyTargetId = null
                        pendingScrollToBottom = true
                        rebuild()
                    }
                ))
            }

            replyCard.maximumSize = Dimension(Int.MAX_VALUE, replyCard.preferredSize.height)
            return replyCard
        }

        fun buildReplySection(root: LineComment, replies: List<LineComment>, floorMap: Map<String, Int>): JComponent {
            val section = JPanel()
            section.layout = BoxLayout(section, BoxLayout.Y_AXIS)
            section.isOpaque = false
            section.border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createCompoundBorder(
                    JBUI.Borders.emptyTop(10),
                    JBUI.Borders.customLine(borderColor, 1, 0, 0, 0)
                ),
                JBUI.Borders.empty(10, 12, 0, 0)
            )

            replies.forEachIndexed { index, reply ->
                section.add(buildReplyCard(reply, floorMap))
                if (index < replies.lastIndex) {
                    section.add(Box.createVerticalStrut(JBUI.scale(8)))
                }
            }

            if (activeReplyTargetId == root.id) {
                if (replies.isNotEmpty()) {
                    section.add(Box.createVerticalStrut(JBUI.scale(8)))
                }
                section.add(buildComposerPanel(
                    onCancel = {
                        activeReplyTargetId = null
                        rebuild()
                    },
                    onSubmit = { text ->
                        addReply(root, text)
                        activeReplyTargetId = null
                        pendingScrollToBottom = true
                        rebuild()
                    }
                ))
            }

            section.maximumSize = Dimension(Int.MAX_VALUE, section.preferredSize.height)
            return section
        }

        fun buildCommentUnit(root: LineComment, all: List<LineComment>, floorMap: Map<String, Int>): JComponent {
            val replies = findThreadReplies(root, all)
            val resolved = root.resolved
            val statusColor = if (resolved) accentGreen else accentOrange
            val statusText = if (resolved) "已解决" else "待解决"
            val isCollapsed = collapsedByRootId.getOrPut(root.id) { resolved }
            val fillColor = if (resolved) alpha(bgCard, 220) else bgCard

            val card = RoundedBlockPanel(fillColor, JBUI.scale(6), statusColor)
            card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
            card.border = javax.swing.BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(alpha(borderColor, 140)),
                JBUI.Borders.empty(10, 6, 10, 10)
            )
            card.alignmentX = Component.LEFT_ALIGNMENT

            val headerRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyLeft(4)
            }

            val leftMeta = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(createAvatar(root.author, size = 24))
                add(Box.createHorizontalStrut(JBUI.scale(8)))
                add(JBLabel(root.author).apply {
                    font = font.deriveFont(Font.BOLD, 13f)
                    foreground = if (resolved) textMain else textContent
                    border = JBUI.Borders.emptyRight(6)
                })
                add(JBLabel("#${displayFloorNum(root, floorMap)}").apply {
                    foreground = textDim
                    border = JBUI.Borders.emptyRight(6)
                })
                add(JBLabel(formatRootTime(root.createdAt)).apply {
                    foreground = textDim
                    font = font.deriveFont(11f)
                })
            }

            val rightMeta = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(if (resolved) createStatusPill(statusText, statusColor) else createStatusText(statusText, statusColor))
                if (replies.isNotEmpty()) {
                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                    add(createToggleButton(
                        if (isCollapsed) "🔽 展开 ${replies.size} 条回复" else "🔼 收起回复"
                    ) {
                        collapsedByRootId[root.id] = !isCollapsed
                        rebuild()
                    })
                }
            }

            headerRow.add(leftMeta)
            headerRow.add(Box.createHorizontalGlue())
            headerRow.add(rightMeta)
            headerRow.maximumSize = Dimension(Int.MAX_VALUE, headerRow.preferredSize.height)
            card.add(headerRow)
            card.add(Box.createVerticalStrut(JBUI.scale(8)))

            card.add(createTextArea(root.content, fillColor, if (resolved) textMain else textContent).apply {
                border = JBUI.Borders.emptyLeft(4)
            })
            card.add(Box.createVerticalStrut(JBUI.scale(10)))

            val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0))
            actionRow.isOpaque = false
            actionRow.alignmentX = Component.LEFT_ALIGNMENT
            val replyButton = createActionButton("↩ 回复")
            replyButton.addActionListener {
                activeReplyTargetId = root.id
                preCommentVisible = false
                collapsedByRootId[root.id] = false
                rebuild()
            }
            actionRow.add(replyButton)
            if (!resolved) {
                val resolveButton = createActionButton(
                    "✓ 标记已解决",
                    foreground = accentGreen,
                    border = alpha(accentGreen, 90),
                    fill = alpha(accentGreen, 18),
                    hoverFill = alpha(accentGreen, 30)
                )
                resolveButton.addActionListener {
                    resolveThread(root) { rebuild() }
                }
                actionRow.add(resolveButton)
            }
            card.add(actionRow)

            if (!isCollapsed || activeReplyTargetId == root.id) {
                if (replies.isNotEmpty() || activeReplyTargetId == root.id) {
                    card.add(buildReplySection(root, replies, floorMap))
                }
            }

            card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
            return card
        }

        ensureRemoteIssueRoot()

        rebuild = {
            val all = LineCommentStore.getComments(filePath, line, side)
            val roots = all.filter { it.parentId == null }.sortedBy { it.createdAt }
            val displayFloorMap = buildDisplayFloorMap(roots, all)
            countLabel.text = "${roots.size}个评论单元"

            middlePanel.removeAll()
            if (roots.isEmpty()) {
                val empty = JBLabel(emptyCommentText, SwingConstants.CENTER)
                empty.foreground = textDim
                empty.border = JBUI.Borders.empty(12)
                empty.alignmentX = Component.CENTER_ALIGNMENT
                middlePanel.add(empty)
            } else {
                roots.forEachIndexed { index, root ->
                    middlePanel.add(buildCommentUnit(root, all, displayFloorMap))
                    if (index < roots.lastIndex) {
                        middlePanel.add(Box.createVerticalStrut(JBUI.scale(12)))
                    }
                }
            }

            footerPanel.removeAll()
            val footerBar = JPanel(BorderLayout())
            footerBar.background = bgHeader
            val footerBarHeight = JBUI.scale(48)
            footerBar.preferredSize = Dimension(0, footerBarHeight)
            footerBar.minimumSize = Dimension(0, footerBarHeight)
            footerBar.maximumSize = Dimension(Int.MAX_VALUE, footerBarHeight)
            footerBar.border = JBUI.Borders.empty(0, 12)

            val footerLeft = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(createAvatar(currentUserName, size = 24, text = "ME", color = accentBlue))
                add(Box.createHorizontalStrut(JBUI.scale(8)))
                add(JBLabel("$currentUserName (当前用户)").apply {
                    foreground = textContent
                    font = font.deriveFont(Font.PLAIN, 13f)
                })
            }

            val footerRight = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
            }
            if (!preCommentVisible) {
                val newCommentButton = createPrimaryButton("➕ 新建评论")
                newCommentButton.addActionListener {
                    preCommentVisible = true
                    activeReplyTargetId = null
                    rebuild()
                }
                footerRight.add(newCommentButton)
            }

            footerBar.add(footerLeft, BorderLayout.WEST)
            if (footerRight.componentCount > 0) {
                footerBar.add(footerRight, BorderLayout.EAST)
            }
            footerPanel.add(footerBar, BorderLayout.NORTH)

            if (preCommentVisible) {
                val composerHost = JPanel(BorderLayout())
                composerHost.isOpaque = true
                composerHost.background = bgHeader
                composerHost.border = javax.swing.BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(borderColor, 1, 0, 0, 0),
                    JBUI.Borders.empty(12)
                )
                composerHost.add(buildComposerPanel(
                    onCancel = {
                        preCommentVisible = false
                        rebuild()
                        closePopupIfNeeded()
                    },
                    onSubmit = { text ->
                        addRootComment(text)
                        preCommentVisible = false
                        pendingScrollToBottom = true
                        rebuild()
                    }
                ), BorderLayout.CENTER)
                footerPanel.add(composerHost, BorderLayout.SOUTH)
            }

            middlePanel.revalidate()
            middlePanel.repaint()
            headerPanel.revalidate()
            headerPanel.repaint()
            footerPanel.revalidate()
            footerPanel.repaint()
            container.revalidate()
            container.repaint()

            SwingUtilities.invokeLater {
                val targetHeight = (headerPanel.preferredSize.height + footerPanel.preferredSize.height + middlePanel.preferredSize.height)
                    .coerceIn(popupMinHeight, popupMaxHeight)
                val targetSize = Dimension(popupWidth, targetHeight)
                val window = SwingUtilities.getWindowAncestor(container)
                if (container.preferredSize != targetSize) {
                    container.preferredSize = targetSize
                    container.minimumSize = targetSize
                    container.maximumSize = targetSize
                    if (window != null) {
                        window.size = targetSize
                        window.validate()
                    }
                }
                if (window != null) {
                    updatePopupWindowShape(window)
                }
            }

            if (pendingScrollToBottom) {
                SwingUtilities.invokeLater {
                    val bar = scrollPane.verticalScrollBar
                    bar.value = bar.maximum
                }
                pendingScrollToBottom = false
            }
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(container, focusAnchor)
            .setRequestFocus(true)
            .setShowBorder(false)
            .setResizable(false)
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
        SwingUtilities.invokeLater {
            focusAnchor.requestFocusInWindow()
            val window = SwingUtilities.getWindowAncestor(container) ?: return@invokeLater
            updatePopupWindowShape(window)
        }
    }

    data class AiIssue(
        val id: Long,
        val issueStatus: Int,
        val issueSeverity: Int,
        val issueDescription: String,
        val issueFixSuggestion: String,
        val issueFixCode: String,
        val issueCodeLine: Int,
        val issueCodeSnippetStartLine: Int,
        val issueCodeSnippetEndLine: Int
    )

    private data class EditorContext(val editor: Editor, val filePath: String, val side: Side)
}
