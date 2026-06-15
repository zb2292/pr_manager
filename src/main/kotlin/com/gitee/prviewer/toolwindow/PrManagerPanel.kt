package com.gitee.prviewer.toolwindow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gitee.prviewer.comment.DiffEditorBinder
import com.gitee.prviewer.comment.IssueItem
import com.gitee.prviewer.comment.IssueStats
import com.gitee.prviewer.comment.LineComment
import com.gitee.prviewer.comment.LineCommentManager
import com.gitee.prviewer.comment.LineCommentStore
import com.gitee.prviewer.comment.PrIssueCache
import com.intellij.diff.util.Side
import com.gitee.prviewer.model.ChangeItem
import com.gitee.prviewer.service.BranchCompareService
import com.gitee.prviewer.service.HttpRequestClient
import com.gitee.prviewer.service.PluginAuthorHeaderEncryptor
import com.gitee.prviewer.service.PrApiService
import com.gitee.prviewer.service.PrManagerFileLogger
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.IconManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicTabbedPaneUI
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class StatusBadgeLabel : JBLabel() {
    private var badgeColor: Color = JBColor.GRAY

    init {
        isOpaque = false
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.empty(2, 10)
        foreground = Color.WHITE
    }

    fun setBadge(text: String, color: Color) {
        this.text = text
        badgeColor = color
        foreground = Color.WHITE
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        if (text.isNullOrBlank()) {
            super.paintComponent(g)
            return
        }
        val g2 = g.create() as Graphics2D
        try {
            val lineWidth = JBUI.scale(1f)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val shape = createCapsuleShape(width, height, lineWidth)
            g2.color = badgeColor
            g2.fill(shape)
            g2.color = badgeColor
            g2.stroke = BasicStroke(lineWidth)
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private fun withAlpha(color: Color, alpha: Int): Color {
    return Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
}

private fun createCapsuleShape(width: Int, height: Int, lineWidth: Float = JBUI.scale(1f)): RoundRectangle2D.Float {
    val inset = lineWidth / 2f
    val shapeWidth = (width - lineWidth).coerceAtLeast(0f)
    val shapeHeight = (height - lineWidth).coerceAtLeast(0f)
    val arc = shapeHeight.coerceAtLeast(0f)
    return RoundRectangle2D.Float(inset, inset, shapeWidth, shapeHeight, arc, arc)
}

class OutlinedPillLabel(
    private val minHeight: Int = JBUI.scale(20)
) : JBLabel("", SwingConstants.CENTER) {
    private var pillColor: Color = JBColor(Color(0x5F6368), Color(0x9AA0A6))

    init {
        isOpaque = false
        isVisible = false
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.empty(2, 8)
        foreground = pillColor
    }

    fun setPill(text: String, color: Color = pillColor) {
        this.text = text
        pillColor = color
        foreground = color
        isVisible = text.isNotBlank()
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width, size.height.coerceAtLeast(minHeight))
    }

    override fun getMinimumSize(): Dimension {
        val size = preferredSize
        return Dimension(0, size.height)
    }

    override fun paintComponent(g: Graphics) {
        if (text.isNullOrBlank()) {
            super.paintComponent(g)
            return
        }
        val g2 = g.create() as Graphics2D
        try {
            val lineWidth = JBUI.scale(1f)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val shape = createCapsuleShape(width, height, lineWidth)
            g2.color = withAlpha(pillColor, 38)
            g2.fill(shape)
            g2.color = withAlpha(pillColor, 90)
            g2.stroke = BasicStroke(lineWidth)
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

class RoundedOutlinePanel(
    fillColor: Color,
    outlineColor: Color,
    private val arc: Int = JBUI.scale(14),
    private val lineWidth: Float = JBUI.scale(1f)
) : JPanel() {
    private var fillColor: Color = fillColor
    private var outlineColor: Color = outlineColor
    private var fillColorProvider: (() -> Color)? = null
    private var outlineColorProvider: (() -> Color)? = null

    init {
        isOpaque = false
    }

    fun bindTheme(fillColorProvider: (() -> Color)?, outlineColorProvider: (() -> Color)?): RoundedOutlinePanel {
        this.fillColorProvider = fillColorProvider
        this.outlineColorProvider = outlineColorProvider
        repaint()
        return this
    }

    fun updateColors(fillColor: Color, outlineColor: Color) {
        this.fillColor = fillColor
        this.outlineColor = outlineColor
        fillColorProvider = null
        outlineColorProvider = null
        repaint()
    }

    override fun paint(g: Graphics) {
        if (width <= 0 || height <= 0) {
            super.paint(g)
            return
        }
        val resolvedFillColor = fillColorProvider?.invoke() ?: fillColor
        val resolvedOutlineColor = outlineColorProvider?.invoke() ?: outlineColor
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val inset = lineWidth / 2f
            val shape = RoundRectangle2D.Float(
                inset,
                inset,
                (width - lineWidth).coerceAtLeast(0f),
                (height - lineWidth).coerceAtLeast(0f),
                arc.toFloat(),
                arc.toFloat()
            )
            g2.color = resolvedFillColor
            g2.fill(shape)
            val originalClip = g2.clip
            g2.clip(shape)
            super.paint(g2)
            g2.clip = originalClip
            g2.color = resolvedOutlineColor
            g2.stroke = BasicStroke(lineWidth)
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
    }
}

private class SegmentedFilterButton(text: String) : JToggleButton(text) {
    private val arc = JBUI.scale(8)
    private val horizontalPadding = JBUI.scale(12)
    private val verticalPadding = JBUI.scale(6)

    init {
        isOpaque = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
        horizontalAlignment = SwingConstants.CENTER
    }

    fun preferredSizeFor(font: Font): Dimension {
        val metrics = getFontMetrics(font)
        val textWidth = metrics.stringWidth(text.orEmpty())
        val width = textWidth + horizontalPadding * 2
        val height = maxOf(metrics.height + verticalPadding * 2, JBUI.scale(30))
        return Dimension(width, height)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (isSelected) {
                g2.color = background
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class TimelineMarkerPanel(
    private val dotColor: Color,
    private val lineColor: Color,
    private val highlight: Boolean,
    private val isLast: Boolean
) : JComponent() {
    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(28), JBUI.scale(84))
        minimumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val centerX = width / 2f
            val dotRadius = JBUI.scale(if (highlight) 5 else 4).toFloat()
            val dotCenterY = JBUI.scale(18).toFloat()
            if (!isLast) {
                g2.color = lineColor
                g2.stroke = BasicStroke(1f)
                g2.drawLine(centerX.toInt(), (dotCenterY + dotRadius + JBUI.scale(4)).toInt(), centerX.toInt(), height)
            }
            if (highlight) {
                g2.color = withAlpha(dotColor, 64)
                val haloRadius = dotRadius + JBUI.scale(4)
                g2.fillOval(
                    (centerX - haloRadius).toInt(),
                    (dotCenterY - haloRadius).toInt(),
                    (haloRadius * 2).toInt(),
                    (haloRadius * 2).toInt()
                )
            }
            g2.color = dotColor
            g2.fillOval(
                (centerX - dotRadius).toInt(),
                (dotCenterY - dotRadius).toInt(),
                (dotRadius * 2).toInt(),
                (dotRadius * 2).toInt()
            )
        } finally {
            g2.dispose()
        }
    }
}


private class ViewportWidthPanel(
    private val tracksViewportWidth: Boolean = true
) : JPanel(), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
        return if (orientation == SwingConstants.VERTICAL) {
            (visibleRect.height - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))
        } else {
            (visibleRect.width - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))
        }
    }

    override fun getScrollableTracksViewportWidth(): Boolean = tracksViewportWidth

    override fun getScrollableTracksViewportHeight(): Boolean = false
}

private class ResponsiveGridPanel(
    private val expandedColumns: Int,
    private val collapseWidth: Int,
    private val expandedHorizontalGap: Int = JBUI.scale(12),
    private val collapsedVerticalGap: Int = JBUI.scale(12)
) : JPanel() {
    private var collapsed = false

    init {
        isOpaque = false
        refreshLayout(Int.MAX_VALUE)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                refreshLayout(width, revalidateAfterChange = true)
            }
        })
    }

    override fun doLayout() {
        refreshLayout(width.takeIf { it > 0 } ?: parent?.width ?: Int.MAX_VALUE)
        super.doLayout()
    }

    override fun getPreferredSize(): Dimension {
        refreshLayout(width.takeIf { it > 0 } ?: parent?.width ?: Int.MAX_VALUE)
        return super.getPreferredSize()
    }

    private fun refreshLayout(availableWidth: Int, revalidateAfterChange: Boolean = false) {
        val shouldCollapse = availableWidth in 1 until collapseWidth
        if (shouldCollapse == collapsed && layout is GridLayout) {
            return
        }
        collapsed = shouldCollapse
        layout = if (collapsed) {
            GridLayout(0, 1, 0, collapsedVerticalGap)
        } else {
            GridLayout(1, expandedColumns.coerceAtLeast(1), expandedHorizontalGap, 0)
        }
        if (revalidateAfterChange) {
            revalidate()
            repaint()
        }
    }
}

private class ShrinkableLabel(text: String = "") : JBLabel(text) {
    override fun getMinimumSize(): Dimension {
        val size = preferredSize
        return Dimension(0, size.height)
    }
}

class PrManagerPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val detailAuthorPillColor = JBColor(Color(0x1A73E8), Color(0x6EA8FF))
    private val detailCreateTimePillColor = JBColor(Color(0x8E24AA), Color(0xC77DFF))
    private val detailBranchPillColor = JBColor(Color(0xF29900), Color(0xF6C26B))
    private val detailIssuePillColor = JBColor(Color(0xD93025), Color(0xF47067))

    private val objectMapper = ObjectMapper()
    private val config = Properties().apply {
        val stream = PrManagerPanel::class.java.getResourceAsStream("/prviewer.properties")
        if (stream != null) {
            stream.use { load(it) }
        }
    }
    private val pluginAuthorPublicKey = config.getProperty("prviewer.security.pluginAuthor.publicKey", "").trim()
    private val pluginAuthorUsernameEnv = config.getProperty("prviewer.security.pluginAuthor.usernameEnv", "USERID")
        .trim()
        .ifBlank { "USERID" }
    private val httpClient = HttpRequestClient(connectTimeoutSeconds = 8) {
        val username = System.getenv(pluginAuthorUsernameEnv).orEmpty().trim()
        if (username.isBlank() || pluginAuthorPublicKey.isBlank()) {
            PrManagerFileLogger.error("username is empty.")
            null
        } else {
            PluginAuthorHeaderEncryptor.encrypt(username, pluginAuthorPublicKey)
        }
    }

    private val mockEnabled = config.getProperty("prviewer.mock.enabled", "false").toBoolean()
    private val globalUiFontSize = config.getProperty("prviewer.ui.font.size", "13").toFloatOrNull() ?: 13f
    private val mockDir = config.getProperty("prviewer.mock.dir", "mock").trim().trimEnd('/')
    private val mockListFile = config.getProperty("prviewer.mock.list.file", "pr-list.json").trim()
    private val mockDetailFile = config.getProperty("prviewer.mock.detail.file", "pr-detail.json").trim()
    private val mockIssuesFile = config.getProperty("prviewer.mock.issues.file", "pr-issues.json").trim()
    private val mockRepoMemberRoleFile = config.getProperty("prviewer.mock.repoMemberRole.file", "repo-member-role.json").trim()
    private val mockCanCreatePrFile = config.getProperty("prviewer.mock.canCreatePr.file", "can-create-pr.json").trim()
    private val mockDevelopersFile = config.getProperty("prviewer.mock.developers.file", "developers.json").trim()
    private val mockCreatePrFile = config.getProperty("prviewer.mock.createPr.file", "create-pr.json").trim()

    private val listUrl = buildUrl(config.getProperty("prviewer.api.list.path", "/pset/api/gitee-api/pull-request-reviews/pullRequestsList"))
    private val detailUrl = buildUrl(config.getProperty("prviewer.api.detail.path", "/pset/api/gitee/selectPullRequestInfos"))
    private val noteListUrl = buildUrl(config.getProperty("prviewer.api.noteList.path", "/pset/api/gitee/noteList"))
    private val noteUrl = buildUrl(config.getProperty("prviewer.api.note.path", "/pset/api/gitee/note"))
    private val replyUrl = buildUrl(config.getProperty("prviewer.api.reply.path", "/pset/api/gitee/replyNote"))
    private val resolveUrl = buildUrl(config.getProperty("prviewer.api.resolve.path", "/pset/api/gitee/resoveNote"))
    private val reviewUrl = buildUrl(config.getProperty("prviewer.api.review.path", "/pset/api/gitee/reviewsPr"))
    private val mergeUrl = buildUrl(config.getProperty("prviewer.api.merge.path", "/api/pr/merge"))
    private val deletePrUrl = buildUrl(config.getProperty("prviewer.api.deletePr.path", "/pset/api/gitee/deletePRByUser"))
    private val closePrUrl = buildUrl(config.getProperty("prviewer.api.closePr.path", "/pset/api/gitee/closePRByUser"))
    private val updatePrUrl = buildUrl(config.getProperty("prviewer.api.updatePr.path", "/pset/api/gitee/updatePRByUser"))
    private val aiReviewPrDetailUrl = buildUrl(config.getProperty("prviewer.api.aiReviewPrDetail.path", "/pset/api/gitee/queryAiReviewPrDetailData"))
    private val aiReviewFileDetailUrl = buildUrl(config.getProperty("prviewer.api.aiReviewFileDetail.path", "/pset/api/gitee/queryAiReviewFileIssueDetailData"))
    private val aiHandleIssueUrl = buildUrl(config.getProperty("prviewer.api.aiHandleIssue.path", "/pset/api/gitee/handleAiReviewIssue"))
    private val triggerAiReviewUrl = buildUrl(config.getProperty("prviewer.api.triggerAiReview.path", "/pset/api/gitee/allPRllmStream.json"))
    private val createPrUrl = buildUrl(config.getProperty("prviewer.api.createPr.path", "/pset/api/gitee/createPRByUser"))
    private val developersUrl = buildUrl(config.getProperty("prviewer.api.developers.path", "/pset/api/gitee/getDevelopers"))
    private val repoMemberRoleUrl = buildUrl(config.getProperty("prviewer.api.repoMemberRole.path", "/pset/api/gitee/getRepoMemberRole"))
    private val canCreatePrUrl = buildUrl(config.getProperty("prviewer.api.canCreatePr.path", "/pset/api/gitee/canCreatePR"))

    private val apiService = PrApiService(
        httpClient = httpClient,
        objectMapper = objectMapper,
        listUrl = listUrl,
        detailUrl = detailUrl,
        noteListUrl = noteListUrl,
        noteUrl = noteUrl,
        replyUrl = replyUrl,
        resolveUrl = resolveUrl,
        reviewUrl = reviewUrl,
        mergeUrl = mergeUrl,
        deletePrUrl = deletePrUrl,
        closePrUrl = closePrUrl,
        updatePrUrl = updatePrUrl,
        aiReviewPrDetailUrl = aiReviewPrDetailUrl,
        aiReviewFileDetailUrl = aiReviewFileDetailUrl,
        aiHandleIssueUrl = aiHandleIssueUrl,
        triggerAiReviewUrl = triggerAiReviewUrl,
        createPrUrl = createPrUrl,
        developersUrl = developersUrl,
        repoMemberRoleUrl = repoMemberRoleUrl,
        canCreatePrUrl = canCreatePrUrl
    )

    private val branchService = BranchCompareService(project)
    private val commentManager = LineCommentManager(project)
    private val diffBinder = DiffEditorBinder(project, commentManager)
    private val commitLogMarker = "__PRVIEWER_COMMIT__"
    private val pullRequestCacheTtlMillis = 30L * 60 * 1000
    private val pullRequestCommitCache = object : LinkedHashMap<BranchSnapshotKey, TimedCommitList>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BranchSnapshotKey, TimedCommitList>?): Boolean {
            return size > 24
        }
    }
    private val missingCommitCache = object : LinkedHashMap<MissingCommitCacheKey, TimedMissingCommitSet>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MissingCommitCacheKey, TimedMissingCommitSet>?): Boolean {
            return size > 24
        }
    }

    private val statusLabel = JBLabel("正在加载 PR 列表...")
    private val tableModel = PrTableModel()
    private val prListContent = ViewportWidthPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty()
    }
    private var prListScrollPane: JBScrollPane? = null
    private val prCardMap = mutableMapOf<Long, PrCardPanel>()
    private val prListSupplementCache = mutableMapOf<Long, PrListSupplement>()
    private val prListSupplementLoading = mutableSetOf<Long>()
    private val aiReviewPollingQueue = LinkedBlockingQueue<Long>()
    private val aiReviewPollingTrackedPrIds = ConcurrentHashMap.newKeySet<Long>()
    private val aiReviewPollingScheduledPrIds = ConcurrentHashMap.newKeySet<Long>()
    private val aiReviewPollingScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "PrViewer-AiReviewPollingScheduler").apply { isDaemon = true }
    }
    private val aiReviewPollingWorker = Thread({ runAiReviewPollingWorker() }, "PrViewer-AiReviewPollingWorker").apply {
        isDaemon = true
    }
    private var selectedPrId: Long? = null
    private val loadMoreLabel = JBLabel("加载更多中...", SwingConstants.CENTER)
    private val searchField = JBTextField()
    private var allowHomepageSearchFocus = false
    private val refreshButton = JButton()
    private val createPrButton = createPrimaryActionButton("+ 新建 PR", compact = true).apply {
        toolTipText = "创建新的 Pull Request"
    }
    private var canCreatePr: Boolean = false
    private var createPrPermissionLoaded: Boolean = false
    private var createPrRoleName: String = ""
    private var isCreatePrViewActive: Boolean = false
    private val statusFilterButtons = listOf(
        SegmentedFilterButton("开启的"),
        SegmentedFilterButton("已合并"),
        SegmentedFilterButton("已关闭")
    )
    private val roleFilterButtons = listOf(
        SegmentedFilterButton("全部"),
        SegmentedFilterButton("我创建的"),
        SegmentedFilterButton("我评审的")
    )
    private val statusFilterPanel = RoundedOutlinePanel(
        fillColor = JBColor(Color(0xE5E7EB), Color(0x3A3D41)),
        outlineColor = JBColor(Color(0xE5E7EB), Color(0x3A3D41)),
        arc = JBUI.scale(10)
    ).apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(4)
    }
    private val roleFilterPanel = RoundedOutlinePanel(
        fillColor = JBColor(Color(0xE5E7EB), Color(0x3A3D41)),
        outlineColor = JBColor(Color(0xE5E7EB), Color(0x3A3D41)),
        arc = JBUI.scale(10)
    ).apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(4)
    }

    private var activeFilter = PrFilter.OPEN
    private var activeRoleFilter = PrRoleFilter.ALL
    private var currentPage = 1
    private var totalPage = 0
    private var totalCount = 0
    private var isLoading = false
    private var userTriggeredListScroll = false
    private var lastListScrollValue = 0
    private var hasMorePrs = false
    private var prListQueryVersion = 0L
    private var prListLoadSequence = 0L
    private var activePrListLoadId = 0L
    private val pageSize = config.getProperty("prviewer.api.pageSize", "10").toIntOrNull() ?: 10

    private val detailAccentColor = JBColor(Color(0x3574F0), Color(0x4C8DFF))
    private val detailCard = JPanel(java.awt.CardLayout()).apply { isOpaque = true }
    private val detailEmpty = JPanel(BorderLayout()).apply { isOpaque = true }
    private val detailPanel = JPanel(BorderLayout()).apply { isOpaque = true }
    private val detailCreatePanel = JPanel(BorderLayout()).apply { isOpaque = true }
    private var createPrView: CreatePrView? = null
    private val detailHeaderTitle = ShrinkableLabel("-")
    private val detailStatus: StatusBadgeLabel = StatusBadgeLabel()
    private val detailRefreshButton = JButton(AllIcons.Actions.Refresh).apply {
        text = ""
        toolTipText = "刷新当前 PR 详情"
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        background = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))
        border = JBUI.Borders.empty(4)
        val buttonSize = Dimension(JBUI.scale(28), JBUI.scale(28))
        preferredSize = buttonSize
        minimumSize = buttonSize
        maximumSize = buttonSize
        addActionListener {
            currentDetailId?.let { showDetail(it) }
        }
    }
    private val detailMoreButton = JButton(AllIcons.Actions.More).apply {
        text = ""
        toolTipText = "更多操作"
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        background = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))
        border = JBUI.Borders.empty(4)
        val buttonSize = Dimension(JBUI.scale(28), JBUI.scale(28))
        preferredSize = buttonSize
        minimumSize = buttonSize
        maximumSize = buttonSize
        isEnabled = false
        addActionListener { showDetailMoreActionMenu() }
    }
    private var detailMorePopup: com.intellij.openapi.ui.popup.JBPopup? = null
    private val detailAuthorLabel = OutlinedPillLabel()
    private val detailCreateTimeLabel = OutlinedPillLabel()
    private val detailBranchLabel = OutlinedPillLabel()
    private val issueCountLabel = OutlinedPillLabel()
    private val aiReviewBadgeLabel = OutlinedPillLabel().apply {
        isOpaque = false
        toolTipText = "当前未发起AI评审"
        cursor = Cursor.getDefaultCursor()
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val state = aiReviewBadgeState
                if (state == AiReviewBadgeState.NO_DATA) return
                showAiOverviewPopup()
            }
        })
    }
    private val detailConflictLabel = OutlinedPillLabel().apply { isVisible = false }
    private val detailConflictResolvedLabel = OutlinedPillLabel().apply { isVisible = false }
    private val detailMetaRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(6))).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(detailAuthorLabel)
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(detailBranchLabel)
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(detailCreateTimeLabel)
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(issueCountLabel)
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(aiReviewBadgeLabel)
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(detailConflictLabel)
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(detailConflictResolvedLabel)
    }
    private val detailAiReviewButton = createRoundedActionButton(
        text = "AI评审",
        fillColorProvider = { JBColor(Color(0x1A73E8), Color(0x3574F0)) },
        hoverFillColorProvider = { JBColor(Color(0x1557B0), Color(0x4682F2)) },
        foregroundColorProvider = { Color.WHITE },
        disabledFillColorProvider = {
            JBColor(Color(0xE5E7EB), Color(0x3C3F41))
        },
        disabledForegroundColorProvider = {
            JBColor(Color(0x9CA3AF), Color(0x7D8694))
        },
        padding = JBUI.insets(2, 2),
        fontSize = (globalUiFontSize - 1f).coerceAtLeast(11f),
        bold = true
    ).apply {
        isEnabled = false
    }
    private val detailReviewButton = createPrimaryActionButton("评审", compact = true).apply {
        isEnabled = false
    }
    private val detailAcceptButton = createPrimaryActionButton(
        text = "接受PR",
        compact = true,
        fillColorProvider = { JBColor(Color(0xCFEFDC), Color(0x245C3B)) },
        hoverFillColorProvider = { JBColor(Color(0xB8E5CC), Color(0x2D7148)) },
        foregroundColorProvider = { JBColor(Color(0x166534), Color.WHITE) }
    ).apply {
        isEnabled = false
    }
    private val detailCloseButton = createPrimaryActionButton(
        text = "关闭PR",
        compact = true,
        fillColorProvider = { JBColor(Color(0xFDE6D0), Color(0x6B3F1D)) },
        hoverFillColorProvider = { JBColor(Color(0xFBD6B4), Color(0x84502A)) },
        foregroundColorProvider = { JBColor(Color(0x9A3412), Color.WHITE) }
    ).apply {
        isEnabled = false
    }
    private val detailTabs = JBTabbedPane()
    private val fileChangeTabTitleLabel = JBLabel("文件改动")
    private val fileChangeTabCountLabel = OutlinedPillLabel(JBUI.scale(16)).apply {
        setPill("0", JBColor(Color(0x5F6368), Color(0x9AA0A6)))
    }
    private val commitTabCountLabel = OutlinedPillLabel(JBUI.scale(16)).apply {
        setPill("0", JBColor(Color(0x5F6368), Color(0x9AA0A6)))
    }
    private val detailTabHeaders = mutableListOf<DetailTabHeader>()
    private var detailTabHeaderListenerBound = false
    private val fileChangeWarningButton = JBLabel(IconManager.getInstance().getIcon("/icons/file-change-warning.svg", javaClass)).apply {
        isVisible = false
        toolTipText = null
        isOpaque = false
        border = JBUI.Borders.empty()
        val iconSize = icon?.let { Dimension(it.iconWidth, it.iconHeight) }
        if (iconSize != null) {
            preferredSize = iconSize
            minimumSize = iconSize
            maximumSize = iconSize
        }
    }
    private val commitWarningLabel = JBLabel(IconManager.getInstance().getIcon("/icons/file-change-warning.svg", javaClass)).apply {
        isVisible = false
        toolTipText = null
        isOpaque = false
        border = JBUI.Borders.empty()
        val iconSize = icon?.let { Dimension(it.iconWidth, it.iconHeight) }
        if (iconSize != null) {
            preferredSize = iconSize
            minimumSize = iconSize
            maximumSize = iconSize
        }
    }
    private var fileChangeWarningText: String? = null
    private var commitWarningText: String? = null
    private var commitWarningBalloon: Balloon? = null
    private var fileChangeWarningBalloon: Balloon? = null

    private var detailOverviewScrollPaneRef: JBScrollPane? = null
    private var detailOverviewDescScrollPaneRef: JBScrollPane? = null
    private val overviewDesc = JBTextArea()
    private val reviewStatusCardsPanel = JPanel().apply {
        isOpaque = false
    }
    private val keyReviewersField = JBTextField()
    private val keyReviewerHint = JBLabel("-")
    private val reviewersField = JBTextField()
    private val reviewerHint = JBLabel("-")
    private val mergeTypeField = JBTextField()
    private val deleteBranchCheck = JBCheckBox("合并后删除源分支")

    private val changeSearchField = JBTextField()
    private val changeSummaryLabel = JBLabel("0 个文件变更")
    private val changeAdditionsLabel = JBLabel("+0")
    private val changeDeletionsLabel = JBLabel("-0")
    private val changeTreeToggleButton = SegmentedFilterButton("树状").apply {
        isFocusable = false
        isFocusPainted = false
        isSelected = true
        margin = JBUI.emptyInsets()
    }
    private val changeFlatToggleButton = SegmentedFilterButton("平铺").apply {
        isFocusable = false
        isFocusPainted = false
        margin = JBUI.emptyInsets()
    }
    private val changeViewModeGroup = ButtonGroup().apply {
        add(changeTreeToggleButton)
        add(changeFlatToggleButton)
    }
    private val changeTreeRoot = DefaultMutableTreeNode("ROOT")
    private val changeTreeModel = DefaultTreeModel(changeTreeRoot)
    private val changeTree = Tree(changeTreeModel)
    private val commitTimelineContent = ViewportWidthPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty()
    }
    private var commitTimelineScrollPane: JBScrollPane? = null
    private val commitTableModel = CommitTableModel()

    private var currentDetail: PrDetail? = null
    private var currentDetailId: Long? = null
    private var currentAiOverview: AiReviewOverview? = null
    private var aiReviewBadgeState: AiReviewBadgeState = AiReviewBadgeState.NO_DATA
    private var aiIssueCountByFileMap: Map<String, Pair<Int, Int>> = emptyMap()
    private var reviewIssueCountByFileMap: Map<String, Pair<Int, Int>> = emptyMap()
    private var currentFileChanges: List<ChangeItem> = emptyList()
    private var changeTreeFlatMode = false
    private var currentDiffFilePath: String? = null
    private val mockAiIssueStatusOverrides = mutableMapOf<Long, Int>()
    private var uiComponentsReady = false

    init {
        if (!aiReviewPollingWorker.isAlive) {
            aiReviewPollingWorker.start()
        }
        applyGlobalFontSettings()
        setContent(buildMainPanel())
        bindActions()
        bindCommentActions()
        installHomepageSearchFocusGuard()
        uiComponentsReady = true
        applyDetailThemeColors()
        PrManagerFileLogger.info("PR Manager panel initialized, mockEnabled=$mockEnabled")
        resetAndLoad()
    }

    override fun updateUI() {
        super.updateUI()
        if (!uiComponentsReady) return
        SwingUtilities.invokeLater {
            installDetailTabsUi()
            applyDetailThemeColors()
            updateFilterButtonStyles()
            updateChangeModeToggleStyle()
            rebuildPrListCards()
            renderReviewStatusCards(currentDetail)
            renderCommitTimeline(commitTableModel.getRows(), commitTableModel.getMissingHashes())
            updateDetailTabHeaderStates()
            changeTree.revalidate()
            changeTree.repaint()
            detailTabs.revalidate()
            detailTabs.repaint()
        }
    }

    private fun buildUrl(path: String): String {
        val scheme = config.getProperty("prviewer.remote.scheme", "http").trim()
        val host = config.getProperty("prviewer.remote.host", "localhost").trim()
        val port = config.getProperty("prviewer.remote.port", "8080").trim()
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "$scheme://$host:$port$normalized"
    }

    private fun readMockJson(fileName: String): String? {
        val resourcePath = if (mockDir.startsWith("/")) "$mockDir/$fileName" else "/$mockDir/$fileName"
        val stream = PrManagerPanel::class.java.getResourceAsStream(resourcePath) ?: return null
        return stream.bufferedReader().use { it.readText() }
    }

    private fun createRoundedActionButton(
        text: String,
        fillColorProvider: () -> Color,
        hoverFillColorProvider: () -> Color,
        foregroundColorProvider: () -> Color,
        disabledFillColorProvider: () -> Color,
        disabledForegroundColorProvider: () -> Color,
        outlineColorProvider: (() -> Color)? = null,
        disabledOutlineColorProvider: (() -> Color)? = outlineColorProvider,
        padding: Insets = JBUI.insets(4, 10),
        fontSize: Float = 11f,
        bold: Boolean = false,
        arc: Int = JBUI.scale(8)
    ): JButton {
        val button = object : JButton(text) {
            var hovered = false

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val fillColor = when {
                        !isEnabled -> disabledFillColorProvider()
                        hovered -> hoverFillColorProvider()
                        else -> fillColorProvider()
                    }
                    val outlineColor = when {
                        !isEnabled -> disabledOutlineColorProvider?.invoke()
                        hovered -> outlineColorProvider?.invoke()?.darker()
                        else -> outlineColorProvider?.invoke()
                    }
                    g2.color = fillColor
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                    if (outlineColor != null) {
                        g2.color = outlineColor
                        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    }
                } finally {
                    g2.dispose()
                }
                foreground = if (isEnabled) foregroundColorProvider() else disabledForegroundColorProvider()
                super.paintComponent(g)
            }
        }
        button.font = button.font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, fontSize)
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

    private fun createPrimaryActionButton(
        text: String,
        compact: Boolean = false,
        fillColorProvider: () -> Color = { JBColor(Color(0x1A73E8), Color(0x3574F0)) },
        hoverFillColorProvider: () -> Color = { JBColor(Color(0x1557B0), Color(0x4682F2)) },
        foregroundColorProvider: () -> Color = { Color.WHITE }
    ): JButton {
        val padding = if (compact) JBUI.insets(2, 2) else JBUI.insets(5, 12)
        return createRoundedActionButton(
            text = text,
            fillColorProvider = fillColorProvider,
            hoverFillColorProvider = hoverFillColorProvider,
            foregroundColorProvider = foregroundColorProvider,
            disabledFillColorProvider = { JBColor(Color(0xE5E7EB), Color(0x3C3F41)) },
            disabledForegroundColorProvider = { JBColor(Color(0x9CA3AF), Color(0x7D8694)) },
            padding = padding,
            fontSize = if (compact) (globalUiFontSize - 1f).coerceAtLeast(11f) else globalUiFontSize,
            bold = true
        )
    }

    private fun createSecondaryActionButton(text: String): JButton {
        return createRoundedActionButton(
            text = text,
            fillColorProvider = { JBColor(Color(0xF0F2F5), Color(0x2B2D30)) },
            hoverFillColorProvider = { JBColor(Color(0xE5E7EB), Color(0x3A3D41)) },
            foregroundColorProvider = { JBColor(Color(0x374151), Color(0xD1D5DB)) },
            disabledFillColorProvider = { JBColor(Color(0xF3F4F6), Color(0x2B2D30)) },
            disabledForegroundColorProvider = { JBColor(Color(0x9CA3AF), Color(0x7D8694)) },
            outlineColorProvider = { JBColor(Color(0xD1D5DB), Color(0x43454A)) },
            padding = JBUI.insets(6, 14),
            fontSize = globalUiFontSize,
            bold = true
        )
    }

    private fun applyGlobalFontSettings() {
        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, globalUiFontSize)
        loadMoreLabel.font = loadMoreLabel.font.deriveFont(Font.PLAIN, globalUiFontSize)
        searchField.font = searchField.font.deriveFont(Font.PLAIN, globalUiFontSize)
        statusFilterButtons.forEach { button ->
            button.font = button.font.deriveFont(Font.PLAIN, globalUiFontSize)
        }
        roleFilterButtons.forEach { button ->
            button.font = button.font.deriveFont(Font.PLAIN, globalUiFontSize)
        }

        detailHeaderTitle.font = detailHeaderTitle.font.deriveFont(Font.BOLD, globalUiFontSize + 1f)
        detailStatus.font = detailStatus.font.deriveFont(Font.PLAIN, globalUiFontSize)
        detailRefreshButton.font = detailRefreshButton.font.deriveFont(Font.PLAIN, globalUiFontSize)
        listOf(
            detailAuthorLabel,
            detailCreateTimeLabel,
            detailBranchLabel,
            issueCountLabel,
            aiReviewBadgeLabel,
            detailConflictLabel,
            detailConflictResolvedLabel,
            fileChangeTabCountLabel,
            commitTabCountLabel
        ).forEach {
            it.font = it.font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
        }
        val compactActionFontSize = (globalUiFontSize - 1f).coerceAtLeast(11f)
        listOf(createPrButton, detailAiReviewButton, detailReviewButton, detailAcceptButton, detailCloseButton).forEach {
            it.font = it.font.deriveFont(Font.BOLD, compactActionFontSize)
        }

        detailTabs.font = detailTabs.font.deriveFont(Font.PLAIN, globalUiFontSize)
        overviewDesc.font = overviewDesc.font.deriveFont(Font.PLAIN, globalUiFontSize)
        keyReviewersField.font = keyReviewersField.font.deriveFont(Font.PLAIN, globalUiFontSize)
        keyReviewerHint.font = keyReviewerHint.font.deriveFont(Font.PLAIN, globalUiFontSize)
        reviewersField.font = reviewersField.font.deriveFont(Font.PLAIN, globalUiFontSize)
        reviewerHint.font = reviewerHint.font.deriveFont(Font.PLAIN, globalUiFontSize)
        mergeTypeField.font = mergeTypeField.font.deriveFont(Font.PLAIN, globalUiFontSize)
        deleteBranchCheck.font = deleteBranchCheck.font.deriveFont(Font.PLAIN, globalUiFontSize)
        changeSearchField.font = changeSearchField.font.deriveFont(Font.PLAIN, globalUiFontSize)
        listOf(changeSummaryLabel, changeAdditionsLabel, changeDeletionsLabel).forEach {
            it.font = it.font.deriveFont(Font.PLAIN, globalUiFontSize)
        }
        listOf(changeTreeToggleButton, changeFlatToggleButton).forEach {
            it.font = it.font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
        }
        updateDetailMetaRowIndent()

        changeTree.font = changeTree.font.deriveFont(Font.PLAIN, globalUiFontSize)
    }

    private fun buildMainPanel(): JPanel {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(8)
        root.isOpaque = false
        val content = ViewportWidthPanel(tracksViewportWidth = true).apply {
            layout = BorderLayout()
            isOpaque = false
            minimumSize = Dimension(0, 0)
            add(buildContentPanel(), BorderLayout.CENTER)
        }
        val contentScroll = JBScrollPane(
            content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
            isWheelScrollingEnabled = false
        }
        root.add(contentScroll, BorderLayout.CENTER)
        root.add(buildStatusPanel(), BorderLayout.SOUTH)
        installSearchFieldBlur(root)
        return root
    }

    private fun buildContentPanel(): JPanel {
        val leftPanel = JPanel(BorderLayout()).apply {
            minimumSize = Dimension(0, 0)
        }
        leftPanel.add(buildTopBar(), BorderLayout.NORTH)
        leftPanel.add(buildTablePanel(), BorderLayout.CENTER)

        buildDetailPanel()
        val rightPanel = JPanel(BorderLayout()).apply {
            minimumSize = Dimension(0, 0)
        }
        rightPanel.border = JBUI.Borders.emptyLeft(8)
        rightPanel.add(detailCard, BorderLayout.CENTER)

        val splitter = OnePixelSplitter(false, 0.45f)
        splitter.minimumSize = Dimension(0, 0)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            minimumSize = Dimension(0, 0)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun buildTopBar(): JPanel {
        ensureListFilterGroupsInitialized()

        refreshButton.icon = AllIcons.Actions.Refresh
        refreshButton.text = ""
        refreshButton.toolTipText = "刷新"
        refreshButton.isOpaque = false
        refreshButton.isContentAreaFilled = false
        refreshButton.isBorderPainted = false
        refreshButton.isFocusPainted = false
        refreshButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        refreshButton.preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))

        searchField.emptyText.text = "搜索 PR 标题..."
        searchField.isOpaque = false
        searchField.border = JBUI.Borders.empty(0, 0, 0, 0)
        searchField.isRequestFocusEnabled = false
        searchField.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                if (searchField.hasFocus()) return
                allowHomepageSearchFocus = true
                SwingUtilities.invokeLater {
                    searchField.requestFocusInWindow()
                }
            }
        })
        searchField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                if (allowHomepageSearchFocus) {
                    allowHomepageSearchFocus = false
                    return
                }
                SwingUtilities.invokeLater {
                    if (searchField.hasFocus()) {
                        dismissSearchFieldFocus()
                    }
                }
            }

            override fun focusLost(e: java.awt.event.FocusEvent?) {
                allowHomepageSearchFocus = false
            }
        })

        val searchWrapper = RoundedOutlinePanel(
            fillColor = searchFieldSurfaceFill(),
            outlineColor = searchFieldOutlineColor(),
            arc = JBUI.scale(12)
        ).bindTheme(::searchFieldSurfaceFill, ::searchFieldOutlineColor).apply {
            layout = BorderLayout(JBUI.scale(8), 0)
            border = JBUI.Borders.empty(8, 12)
            add(JBLabel(AllIcons.Actions.Search).apply {
                foreground = JBColor(Color(0x9CA3AF), Color(0x9CA3AF))
            }, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(320), JBUI.scale(36))
            minimumSize = Dimension(JBUI.scale(220), JBUI.scale(36))
            maximumSize = Dimension(JBUI.scale(420), JBUI.scale(36))
        }

        val titleLabel = JBLabel("Pull Requests").apply {
            font = font.deriveFont(Font.BOLD, globalUiFontSize + 9f)
            foreground = JBColor(Color(0x1F2937), Color(0xF3F4F6))
        }

        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.WEST)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(searchWrapper)
                add(Box.createHorizontalStrut(JBUI.scale(8)))
                add(refreshButton)
            }, BorderLayout.EAST)
        }

        val filterBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(statusFilterPanel)
            add(Box.createHorizontalStrut(JBUI.scale(12)))
            add(roleFilterPanel)
        }

        val filterRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(JBUI.scale(16))
            add(filterBar, BorderLayout.WEST)
            add(createPrButton, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8, 8, 8)
            add(headerBar, BorderLayout.NORTH)
            add(filterRow, BorderLayout.CENTER)
        }
    }

    private fun buildTablePanel(): JPanel {
        val pane = JBScrollPane(prListContent).apply {
            border = JBUI.Borders.emptyTop(6)
            viewportBorder = null
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            isWheelScrollingEnabled = false
            verticalScrollBar.unitIncrement = JBUI.scale(24)
            verticalScrollBar.blockIncrement = JBUI.scale(96)
        }
        prListScrollPane = pane

        val markUserScroll = { userTriggeredListScroll = true }
        pane.addMouseWheelListener { event ->
            markUserScroll()
            scrollPrListByWheel(event)
        }
        pane.viewport.addMouseWheelListener { event ->
            markUserScroll()
            scrollPrListByWheel(event)
        }
        prListContent.addMouseWheelListener { event ->
            markUserScroll()
            scrollPrListByWheel(event)
        }
        pane.verticalScrollBar.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                markUserScroll()
            }
        })
        pane.verticalScrollBar.addAdjustmentListener {
            val bar = pane.verticalScrollBar
            val currentValue = bar.value
            val scrollingDown = currentValue > lastListScrollValue
            lastListScrollValue = currentValue
            if (!userTriggeredListScroll || !scrollingDown || isLoading || !hasMorePrs) return@addAdjustmentListener
            if (bar.maximum <= bar.visibleAmount) return@addAdjustmentListener
            val reachedBottom = bar.maximum - (bar.value + bar.visibleAmount) <= JBUI.scale(30)
            if (reachedBottom) {
                loadPrs(append = true)
            }
        }

        loadMoreLabel.isVisible = false
        loadMoreLabel.border = JBUI.Borders.empty(6, 8)
        val loadMoreHeight = JBUI.scale(28)
        loadMoreLabel.preferredSize = Dimension(0, loadMoreHeight)
        loadMoreLabel.minimumSize = Dimension(0, loadMoreHeight)
        loadMoreLabel.maximumSize = Dimension(Int.MAX_VALUE, loadMoreHeight)

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(pane, BorderLayout.CENTER)
            add(loadMoreLabel, BorderLayout.SOUTH)
        }
    }

    private fun ensureListFilterGroupsInitialized() {
        if (statusFilterPanel.componentCount == 0) {
            val group = ButtonGroup()
            statusFilterButtons.forEachIndexed { index, button ->
                configureListFilterButton(button)
                group.add(button)
                statusFilterPanel.add(button)
                if (index < statusFilterButtons.lastIndex) {
                    statusFilterPanel.add(Box.createHorizontalStrut(JBUI.scale(4)))
                }
            }
            statusFilterButtons.first().isSelected = true
        }
        if (roleFilterPanel.componentCount == 0) {
            val group = ButtonGroup()
            roleFilterButtons.forEachIndexed { index, button ->
                configureListFilterButton(button)
                group.add(button)
                roleFilterPanel.add(button)
                if (index < roleFilterButtons.lastIndex) {
                    roleFilterPanel.add(Box.createHorizontalStrut(JBUI.scale(4)))
                }
            }
            roleFilterButtons.first().isSelected = true
        }
        updateFilterButtonStyles()
    }

    private fun configureListFilterButton(button: JToggleButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isBorderPainted = false
        button.isContentAreaFilled = false
        button.isOpaque = false
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.margin = JBUI.emptyInsets()
        updateListFilterButtonSize(button)
    }

    private fun updateListFilterButtonSize(button: JToggleButton) {
        val plainFont = button.font.deriveFont(Font.PLAIN, globalUiFontSize)
        val boldFont = button.font.deriveFont(Font.BOLD, globalUiFontSize)
        val selected = button.isSelected
        val size = if (button is SegmentedFilterButton) {
            val plainSize = button.preferredSizeFor(plainFont)
            val boldSize = button.preferredSizeFor(boldFont)
            Dimension(
                maxOf(plainSize.width, boldSize.width),
                maxOf(plainSize.height, boldSize.height, JBUI.scale(30))
            )
        } else {
            val width = maxOf(
                button.getFontMetrics(plainFont).stringWidth(button.text.orEmpty()),
                button.getFontMetrics(boldFont).stringWidth(button.text.orEmpty())
            ) + JBUI.scale(24)
            Dimension(width, JBUI.scale(30))
        }

        button.font = if (selected) boldFont else plainFont
        button.preferredSize = size
        button.minimumSize = size
        button.maximumSize = size
        button.revalidate()
    }

    private fun buildStatusPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.emptyTop(6)
        panel.add(statusLabel, BorderLayout.WEST)
        return panel
    }

    private fun rebuildPrListCards() {
        prListContent.removeAll()
        prCardMap.clear()
        val rows = tableModel.getRows()
        if (rows.isEmpty()) {
            prListContent.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(28, 8)
                add(JBLabel("暂无 PR，点击刷新按钮重试", SwingConstants.CENTER).apply {
                    foreground = detailMutedColor()
                    font = font.deriveFont(Font.PLAIN, globalUiFontSize + 1f)
                }, BorderLayout.CENTER)
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        } else {
            rows.forEachIndexed { index, item ->
                val card = PrCardPanel(item).apply {
                    setLastCard(index == rows.lastIndex)
                    setSelectedState(item.id == selectedPrId)
                    prListSupplementCache[item.id]?.let { applySupplement(it) }
                }
                prCardMap[item.id] = card
                prListContent.add(card)
            }
        }
        prListContent.revalidate()
        prListContent.repaint()
    }

    private fun selectPrCard(prId: Long?) {
        if (selectedPrId == prId) {
            prId?.let { prCardMap[it]?.setSelectedState(true) }
            return
        }
        selectedPrId?.let { prCardMap[it]?.setSelectedState(false) }
        selectedPrId = prId
        prId?.let { prCardMap[it]?.setSelectedState(true) }
    }

    private fun preloadPrListSupplements(items: List<PrItem>) {
        items.forEach { item -> loadPrListSupplement(item) }
    }

    private fun loadPrListSupplement(item: PrItem) {
        if (prListSupplementCache.containsKey(item.id) || !prListSupplementLoading.add(item.id)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val reviewStats = runCatching {
                    if (mockEnabled) {
                        val mockJson = readMockJson(mockIssuesFile)
                        if (mockJson.isNullOrBlank()) null else parseNoteList(mockJson).stats
                    } else {
                        val response = apiService.fetchNoteList(resolveGitAddress(), item.iid)
                        if (response.statusCode() !in 200..299) null else parseNoteList(response.body()).stats
                    }
                }.getOrNull()
                val aiState = runCatching {
                    val overview = if (mockEnabled) {
                        val mockJson = readMockJson("ai-review-overview.json")
                        if (mockJson.isNullOrBlank()) null else parseAiReviewOverview(mockJson)
                    } else {
                        val response = apiService.fetchAiReviewOverview(item.id)
                        if (response.statusCode() !in 200..299) null else parseAiReviewOverview(response.body())
                    }
                    resolveAiReviewState(overview)
                }.getOrDefault(AiReviewBadgeState.NO_DATA)
                SwingUtilities.invokeLater {
                    prListSupplementLoading.remove(item.id)
                    val supplement = PrListSupplement(reviewStats = reviewStats, aiState = aiState)
                    prListSupplementCache[item.id] = supplement
                    prCardMap[item.id]?.applySupplement(supplement)
                    syncAiReviewPollingState(item.id, aiState)
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load PR list supplement error: prId=${item.id} iid=${item.iid}", e)
                SwingUtilities.invokeLater {
                    prListSupplementLoading.remove(item.id)
                }
            }
        }
    }

    private fun updatePrListAiState(prId: Long, aiState: AiReviewBadgeState) {
        val current = prListSupplementCache[prId]
        val updated = PrListSupplement(
            reviewStats = current?.reviewStats,
            aiState = aiState
        )
        prListSupplementCache[prId] = updated
        prCardMap[prId]?.applySupplement(updated)
        syncAiReviewPollingState(prId, aiState)
    }

    private fun syncAiReviewPollingState(prId: Long, aiState: AiReviewBadgeState) {
        if (aiState == AiReviewBadgeState.IN_PROGRESS) {
            enqueueAiReviewPolling(prId)
        } else {
            stopAiReviewPolling(prId)
        }
    }

    private fun enqueueAiReviewPolling(prId: Long, delayMillis: Long = AI_REVIEW_POLL_INTERVAL_MILLIS) {
        if (!aiReviewPollingTrackedPrIds.add(prId)) return
        scheduleAiReviewPolling(prId, delayMillis)
    }

    private fun scheduleAiReviewPolling(prId: Long, delayMillis: Long = AI_REVIEW_POLL_INTERVAL_MILLIS) {
        if (!aiReviewPollingTrackedPrIds.contains(prId)) return
        if (!aiReviewPollingScheduledPrIds.add(prId)) return
        aiReviewPollingScheduler.schedule({
            if (project.isDisposed || !aiReviewPollingTrackedPrIds.contains(prId)) {
                aiReviewPollingScheduledPrIds.remove(prId)
                return@schedule
            }
            aiReviewPollingQueue.offer(prId)
        }, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun stopAiReviewPolling(prId: Long) {
        aiReviewPollingTrackedPrIds.remove(prId)
        aiReviewPollingScheduledPrIds.remove(prId)
        while (aiReviewPollingQueue.remove(prId)) {
            // 清理队列中尚未消费的相同 PR，确保停止轮询后不会再被处理。
        }
    }

    private fun runAiReviewPollingWorker() {
        while (!project.isDisposed) {
            try {
                val prId = aiReviewPollingQueue.take()
                aiReviewPollingScheduledPrIds.remove(prId)
                if (!aiReviewPollingTrackedPrIds.contains(prId)) continue
                pollAiReviewState(prId)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                PrManagerFileLogger.error("AI review polling worker error", e)
            }
        }
    }

    private fun pollAiReviewState(prId: Long) {
        try {
            val overview = fetchAiReviewOverview(prId)
            if (overview == null) {
                scheduleAiReviewPolling(prId, AI_REVIEW_POLL_INTERVAL_MILLIS)
                return
            }
            val aiState = resolveAiReviewState(overview)
            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                applyAiOverviewState(
                    prId = prId,
                    overview = overview,
                    aiState = aiState,
                    updateCurrentDetail = currentDetail?.id == prId
                )
            }
            if (aiState == AiReviewBadgeState.IN_PROGRESS) {
                scheduleAiReviewPolling(prId, AI_REVIEW_POLL_INTERVAL_MILLIS)
            } else {
                stopAiReviewPolling(prId)
            }
        } catch (e: Exception) {
            PrManagerFileLogger.error("Poll AI review state error: prId=$prId", e)
            scheduleAiReviewPolling(prId, AI_REVIEW_POLL_INTERVAL_MILLIS)
        }
    }

    private fun resolveAiReviewState(overview: AiReviewOverview?): AiReviewBadgeState {
        return when (overview?.reviewFlag ?: AiReviewProgressFlag.NOT_STARTED) {
            AiReviewProgressFlag.NOT_STARTED -> AiReviewBadgeState.NO_DATA
            AiReviewProgressFlag.IN_PROGRESS -> AiReviewBadgeState.IN_PROGRESS
            AiReviewProgressFlag.COMPLETED -> when {
                overview == null -> AiReviewBadgeState.NO_DATA
                !overview.validFlag -> AiReviewBadgeState.STALE
                overview.unhandledCount == 0 -> AiReviewBadgeState.PASS
                else -> AiReviewBadgeState.FAIL
            }
        }
    }

    private fun isAiReviewCompleted(overview: AiReviewOverview?): Boolean = overview?.reviewFlag == AiReviewProgressFlag.COMPLETED

    private fun isAiReviewResultAvailable(overview: AiReviewOverview?): Boolean = isAiReviewCompleted(overview) && overview?.validFlag == true

    private fun formatPrListTime(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.contains('T')) {
            return runCatching {
                val instant = java.time.Instant.parse(trimmed)
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(instant)
            }.getOrElse { trimmed }
        }
        return trimmed.replace('T', ' ').let {
            if (it.length >= 19) it.substring(0, 19) else it
        }
    }

    private fun listAuthorColor(username: String): Color {
        val palette = listOf(
            JBColor(Color(0x1A73E8), Color(0x6EA8FF)),
            JBColor(Color(0x0B8043), Color(0x57D163)),
            JBColor(Color(0x8E24AA), Color(0xC77DFF)),
            JBColor(Color(0xD93025), Color(0xF47067)),
            JBColor(Color(0xF29900), Color(0xF6C26B))
        )
        val index = username.hashCode().let { kotlin.math.abs(it % palette.size) }
        return palette[index]
    }

    private fun buildListPill(text: String, color: Color, icon: Icon? = null): OutlinedPillLabel {
        return OutlinedPillLabel().apply {
            font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
            iconTextGap = JBUI.scale(6)
            horizontalTextPosition = SwingConstants.RIGHT
            verticalTextPosition = SwingConstants.CENTER
            this.icon = icon
            setPill(text, color)
        }
    }

    private fun buildDetailPanel() {
        applyDetailThemeColors()
        detailEmpty.border = JBUI.Borders.empty(12)
        detailCard.add(detailEmpty, "empty")

        val detailRoot = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        detailRoot.add(buildDetailHeader(), BorderLayout.NORTH)
        detailRoot.add(buildDetailTabs(), BorderLayout.CENTER)
        detailPanel.add(detailRoot, BorderLayout.CENTER)
        detailCard.add(detailPanel, "detail")

        createPrView = CreatePrView().also { detailCreatePanel.add(it.rootComponent, BorderLayout.CENTER) }
        detailCard.add(detailCreatePanel, "create")

        (detailCard.layout as java.awt.CardLayout).show(detailCard, "empty")
        renderEmptyDetail()
    }

    private fun buildDetailHeader(): JComponent {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(10, 0, 16, 0)
        }

        val titleRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            val inset = detailHeaderRowSideInset()
            border = JBUI.Borders.empty(0, inset, 14, inset)
            add(detailHeaderTitle)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(detailStatus)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(detailRefreshButton)
            add(Box.createHorizontalGlue())
            add(detailAiReviewButton)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(detailReviewButton)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(detailAcceptButton)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(detailCloseButton)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(detailMoreButton)
        }
        detailHeaderTitle.toolTipText = detailHeaderTitle.text
        detailHeaderTitle.font = detailHeaderTitle.font.deriveFont(Font.BOLD, globalUiFontSize + 2f)

        updateDetailMetaRowIndent()

        header.add(titleRow)
        header.add(detailMetaRow)
        return header
    }

    private fun installDetailTabsUi() {
        detailTabs.setUI(object : BasicTabbedPaneUI() {
            override fun installDefaults() {
                super.installDefaults()
                tabAreaInsets = Insets(0, 0, 0, 0)
                contentBorderInsets = Insets(JBUI.scale(4), 0, JBUI.scale(4), 0)
                selectedTabPadInsets = Insets(0, 0, 0, 0)
            }

            override fun calculateTabHeight(tabPlacement: Int, tabIndex: Int, fontHeight: Int): Int {
                val baseHeight = super.calculateTabHeight(tabPlacement, tabIndex, fontHeight)
                val customHeight = tabPane.getTabComponentAt(tabIndex)?.preferredSize?.height ?: 0
                return maxOf(baseHeight, customHeight)
            }

            override fun getTabInsets(tabPlacement: Int, tabIndex: Int): Insets = Insets(0, 0, 0, 0)

            override fun getTabLabelShiftX(tabPlacement: Int, tabIndex: Int, isSelected: Boolean): Int = 0

            override fun getTabLabelShiftY(tabPlacement: Int, tabIndex: Int, isSelected: Boolean): Int = 0

            override fun paintTabArea(g: Graphics, tabPlacement: Int, selectedIndex: Int) {
                val g2 = g.create() as Graphics2D
                try {
                    val tabAreaHeight = calculateTabAreaHeight(tabPlacement, runCount, maxTabHeight)
                    g2.color = detailTabPaneFill()
                    g2.fillRect(0, 0, tabPane.width, tabAreaHeight)
                } finally {
                    g2.dispose()
                }
                super.paintTabArea(g, tabPlacement, selectedIndex)
                if (tabPane.tabCount <= 0) return
                val underlineGraphics = g.create() as Graphics2D
                try {
                    val (leftInset, rightInset) = detailTabUnderlineSideInsets()
                    val lineHeight = detailTabUnderlineHeight()
                    val y = calculateTabAreaHeight(tabPlacement, runCount, maxTabHeight) - lineHeight
                    val width = (tabPane.width - leftInset - rightInset).coerceAtLeast(0)
                    underlineGraphics.color = detailTabUnderlineColor()
                    underlineGraphics.fillRect(leftInset, y, width, lineHeight)
                } finally {
                    underlineGraphics.dispose()
                }
            }

            override fun paintTabBackground(g: Graphics, tabPlacement: Int, tabIndex: Int, x: Int, y: Int, w: Int, h: Int, isSelected: Boolean) = Unit

            override fun paintTabBorder(g: Graphics, tabPlacement: Int, tabIndex: Int, x: Int, y: Int, w: Int, h: Int, isSelected: Boolean) = Unit

            override fun paintContentBorder(g: Graphics, tabPlacement: Int, selectedIndex: Int) = Unit

            override fun paintFocusIndicator(g: Graphics, tabPlacement: Int, rects: Array<Rectangle>, tabIndex: Int, iconRect: Rectangle, textRect: Rectangle, isSelected: Boolean) = Unit
        })
    }

    private fun buildDetailTabs(): JComponent {
        detailTabs.border = JBUI.Borders.empty()
        detailTabs.isOpaque = true
        detailTabs.background = detailTabPaneFill()
        installDetailTabsUi()
        detailTabs.addTab("概览", buildOverviewPanel())
        detailTabs.addTab("文件改动", buildFileChangePanel())
        detailTabs.addTab("提交记录", buildCommitPanel())
        setupDetailTabsHeader()
        return detailTabs
    }

    private fun detailPanelFill(): Color = JBColor(Color(0xFFFFFF), Color(0x2B2D30))

    private fun detailTabPaneFill(): Color = detailPanelFill()

    private fun detailSurfaceFill(): Color = JBColor(Color(0xF7F8FA), Color(0x313438))

    private fun createPrOuterFill(): Color = detailPanelFill()

    private fun createPrHeaderFill(): Color = detailPanelFill()

    private fun createPrSectionFill(): Color = detailTabPaneFill()

    private fun createPrSubtleFill(): Color = detailSurfaceFill()

    private fun createPrInputFill(): Color = detailSurfaceFill()

    private fun createPrBorderColor(): Color = detailOutlineColor()

    private fun createPrPrimaryTextColor(): Color = detailPrimaryTextColor()

    private fun createPrSecondaryTextColor(): Color = detailMutedColor()

    private fun createPrTabSelectedTextColor(): Color = detailAccentColor

    private fun commitCardFill(): Color = JBColor(Color(0xFCFCFD), Color(0x363A3F))

    private fun commitCardOutlineColor(): Color = JBColor(Color(0xD7DDE6), Color(0x59606A))

    private fun commitHashBadgeFill(): Color = JBColor(Color(0xFFFFFF), Color(0x3B4047))

    private fun searchFieldSurfaceFill(): Color = JBColor(Color.WHITE, Color(0x2B2D30))

    private fun searchFieldOutlineColor(): Color = JBColor(Color(0xD1D5DB), Color(0x4B5563))

    private fun detailOutlineColor(): Color = JBColor(Color(0xD6DAE1), Color(0x4B515A))

    private fun detailTabUnderlineColor(): Color = if (UIUtil.isUnderDarcula()) {
        withAlpha(UIUtil.getLabelForeground(), 235)
    } else {
        Color(0xC4CAD4)
    }

    private fun detailTabUnderlineHeight(): Int = if (UIUtil.isUnderDarcula()) JBUI.scale(2) else JBUI.scale(1)

    private fun detailPrimaryTextColor(): Color = JBColor(Color(0x1F2937), Color(0xF3F4F6))

    private fun detailMutedColor(): Color = JBColor(Color(0x5F6368), Color(0xF3F4F6))

    private fun detailTabHeaderMutedTextColor(): Color = JBColor(Color(0x5F6368), Color(0x9AA0A6))

    private fun detailTabHeaderSelectedTextColor(): Color = if (UIUtil.isUnderDarcula()) Color.WHITE else detailPrimaryTextColor()

    private fun detailTabHeaderSelectedFill(): Color = if (UIUtil.isUnderDarcula()) {
        withAlpha(detailAccentColor, 88)
    } else {
        withAlpha(detailAccentColor, 20)
    }

    private fun detailTabHeaderSelectedOutline(): Color = if (UIUtil.isUnderDarcula()) {
        withAlpha(detailAccentColor, 182)
    } else {
        withAlpha(detailAccentColor, 88)
    }

    private fun detailSectionTitleFontSize(): Float = globalUiFontSize + 2f

    private fun overviewSectionTitleFontSize(): Float = detailSectionTitleFontSize()

    private fun createMergeFieldFrameWidth(): Int = JBUI.scale(240)

    private fun createMergeFieldFrameHeight(): Int = JBUI.scale(34)

    private fun detailHorizontalInset(): Int {
        val metricsOwner = if (detailHeaderTitle.font != null) detailHeaderTitle else detailTabs
        return metricsOwner.getFontMetrics(metricsOwner.font).charWidth('中').coerceAtLeast(JBUI.scale(12))
    }

    private fun createPrHorizontalInset(): Int = (detailHorizontalInset() / 2).coerceAtLeast(JBUI.scale(6))

    private fun detailHeaderInsetDelta(): Int = JBUI.scale(16)

    private fun detailHeaderRowInsetDelta(): Int = JBUI.scale(10)

    private fun detailHeaderSideInset(): Int = detailHorizontalInset() + detailHeaderInsetDelta()

    private fun detailHeaderRowSideInset(): Int = detailHorizontalInset() + detailHeaderRowInsetDelta()

    private fun detailContentSideInsets(component: Component?): Pair<Int, Int> {
        fun borderInsets(target: JComponent?): Pair<Int, Int>? {
            val insets = target?.border?.getBorderInsets(target) ?: return null
            return insets.left to insets.right
        }

        val defaultInset = detailHorizontalInset()
        return when (component) {
            is JBScrollPane -> {
                val view = component.viewport?.view as? JComponent
                borderInsets(view) ?: (defaultInset to defaultInset)
            }
            is JComponent -> borderInsets(component) ?: (defaultInset to defaultInset)
            else -> defaultInset to defaultInset
        }
    }

    private fun detailTabUnderlineSideInsets(): Pair<Int, Int> {
        val overviewComponent = if (detailTabs.tabCount > 0) detailTabs.getComponentAt(0) else null
        return detailContentSideInsets(overviewComponent)
    }

    private fun buildDetailTabBody(
        topInset: Int = 12,
        bottomInset: Int = 10,
        tracksViewportWidth: Boolean = true
    ): ViewportWidthPanel {
        return ViewportWidthPanel(tracksViewportWidth).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(topInset, detailHorizontalInset(), bottomInset, detailHorizontalInset())
        }
    }

    private fun configureCommitTimelineContent() {
        commitTimelineContent.apply {
            isOpaque = true
            background = detailSurfaceFill()
            border = JBUI.Borders.empty()
        }
    }

    private fun repaintCommitTimelineViewport() {
        commitTimelineContent.background = detailSurfaceFill()
        val viewport = commitTimelineScrollPane?.viewport ?: return
        viewport.background = detailSurfaceFill()
        viewport.repaint()
        commitTimelineContent.repaint()
    }

    private fun stretchDetailTabChild(component: JComponent, stretchVertically: Boolean = false): JComponent {
        component.alignmentX = Component.LEFT_ALIGNMENT
        component.maximumSize = Dimension(Int.MAX_VALUE, if (stretchVertically) Int.MAX_VALUE else component.preferredSize.height)
        return component
    }

    private fun normalizeFilePathKey(path: String): String {
        return path.trim()
            .replace('\\', '/')
            .removePrefix("./")
            .trim('/')
    }

    private fun buildReviewIssueCountByFileMap(stats: IssueStats): Map<String, Pair<Int, Int>> {
        return stats.issues
            .groupBy { normalizeFilePathKey(it.file) }
            .mapValues { (_, items) ->
                items.count { it.status.trim().lowercase() == "open" } to items.size
            }
    }

    private fun detailTabBadgeColor(): Color = JBColor(Color(0x5F6368), Color(0xF3F4F6))

    private fun aiReviewBadgeText(state: AiReviewBadgeState): String = when (state) {
        AiReviewBadgeState.NO_DATA -> "AI评审：未发起"
        AiReviewBadgeState.IN_PROGRESS -> "AI评审：评审中"
        AiReviewBadgeState.STALE -> "AI评审：待更新"
        AiReviewBadgeState.PASS -> "AI评审：通过"
        AiReviewBadgeState.FAIL -> "AI评审：不通过"
    }

    private fun aiReviewBadgeTooltip(state: AiReviewBadgeState): String = when (state) {
        AiReviewBadgeState.NO_DATA -> "当前未发起AI评审"
        AiReviewBadgeState.IN_PROGRESS -> "AI评审进行中，点击查看详情"
        AiReviewBadgeState.STALE -> "AI评审结果已过期，点击查看详情"
        else -> "查看AI评审总览"
    }

    private fun updateDetailMetaRowIndent() {
        val inset = detailHeaderRowSideInset()
        detailMetaRow.border = JBUI.Borders.empty(0, inset, JBUI.scale(20), inset)
        detailMetaRow.revalidate()
        detailMetaRow.repaint()
    }

    private fun applyDetailThemeColors() {
        val panelFill = detailPanelFill()
        val tabFill = detailTabPaneFill()
        detailCard.background = panelFill
        detailEmpty.background = panelFill
        detailPanel.background = panelFill
        detailCreatePanel.background = createPrOuterFill()
        detailTabs.background = tabFill
        overviewDesc.background = detailSurfaceFill()
        commitTimelineContent.background = detailSurfaceFill()
        commitTimelineScrollPane?.background = detailSurfaceFill()
        commitTimelineScrollPane?.viewport?.background = detailSurfaceFill()
        repeat(detailTabs.tabCount) { index ->
            when (val component = detailTabs.getComponentAt(index)) {
                is JBScrollPane -> {
                    component.background = tabFill
                    component.viewport?.background = tabFill
                }
                is JComponent -> component.background = tabFill
            }
        }
        createPrView?.applyTheme()
    }

    private fun createDetailScrollPane(
        view: Component,
        verticalPolicy: Int,
        horizontalPolicy: Int,
        fillColorProvider: () -> Color
    ): JBScrollPane {
        fun createScrollCorner(fill: Color): JComponent = JPanel().apply {
            isOpaque = true
            background = fill
            border = JBUI.Borders.empty()
        }

        return object : JBScrollPane(view, verticalPolicy, horizontalPolicy) {
            override fun updateUI() {
                super.updateUI()
                val fill = fillColorProvider()
                border = JBUI.Borders.empty()
                viewportBorder = null
                isOpaque = true
                viewport?.isOpaque = true
                viewport?.scrollMode = JViewport.SIMPLE_SCROLL_MODE
                background = fill
                viewport?.background = fill
                verticalScrollBar?.border = JBUI.Borders.empty()
                verticalScrollBar?.background = fill
                horizontalScrollBar?.border = JBUI.Borders.empty()
                horizontalScrollBar?.background = fill
                setCorner(ScrollPaneConstants.UPPER_RIGHT_CORNER, createScrollCorner(fill))
                setCorner(ScrollPaneConstants.LOWER_RIGHT_CORNER, createScrollCorner(fill))
                setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, createScrollCorner(fill))
                setCorner(ScrollPaneConstants.LOWER_LEFT_CORNER, createScrollCorner(fill))
            }
        }.apply {
            val fill = fillColorProvider()
            border = JBUI.Borders.empty()
            viewportBorder = null
            isOpaque = true
            minimumSize = Dimension(0, 0)
            viewport.isOpaque = true
            viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
            background = fill
            viewport.background = fill
            verticalScrollBar.border = JBUI.Borders.empty()
            verticalScrollBar.background = fill
            horizontalScrollBar.border = JBUI.Borders.empty()
            horizontalScrollBar.background = fill
            setCorner(ScrollPaneConstants.UPPER_RIGHT_CORNER, createScrollCorner(fill))
            setCorner(ScrollPaneConstants.LOWER_RIGHT_CORNER, createScrollCorner(fill))
            setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, createScrollCorner(fill))
            setCorner(ScrollPaneConstants.LOWER_LEFT_CORNER, createScrollCorner(fill))
            verticalScrollBar.unitIncrement = JBUI.scale(16)
            horizontalScrollBar.unitIncrement = JBUI.scale(16)
        }
    }

    private fun styleSegmentedToggle(button: JToggleButton, selected: Boolean) {
        val darkTheme = UIUtil.isUnderDarcula()
        val selectedBackground = if (darkTheme) withAlpha(detailAccentColor, 92) else withAlpha(detailAccentColor, 32)
        val selectedForeground = if (darkTheme) Color.WHITE else UIUtil.getLabelForeground()
        val normalForeground = detailMutedColor()
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = false
        button.border = JBUI.Borders.empty()
        button.background = if (selected) selectedBackground else Color(0, 0, 0, 0)
        button.foreground = if (selected) selectedForeground else normalForeground
        button.font = button.font.deriveFont(if (selected) Font.BOLD else Font.PLAIN, globalUiFontSize - 1f)
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        updateListFilterButtonSize(button)
    }

    private fun updateChangeModeToggleStyle() {
        styleSegmentedToggle(changeTreeToggleButton, !changeTreeFlatMode)
        styleSegmentedToggle(changeFlatToggleButton, changeTreeFlatMode)
    }

    private fun updateDetailTabCounters(fileCount: Int = currentFileChanges.size, commitCount: Int = commitTableModel.rowCount) {
        fileChangeTabCountLabel.setPill(fileCount.toString(), detailTabBadgeColor())
        commitTabCountLabel.setPill(commitCount.toString(), detailTabBadgeColor())
        detailTabs.revalidate()
        detailTabs.repaint()
    }

    private fun wrapDetailSurface(
        component: JComponent,
        fillColor: Color = detailSurfaceFill(),
        outlineColor: Color = detailOutlineColor(),
        fillColorProvider: (() -> Color)? = ::detailSurfaceFill,
        outlineColorProvider: (() -> Color)? = ::detailOutlineColor,
        padding: Insets = JBUI.insets(12)
    ): JComponent {
        return RoundedOutlinePanel(
            fillColor = fillColor,
            outlineColor = outlineColor,
            arc = JBUI.scale(14)
        ).bindTheme(fillColorProvider, outlineColorProvider).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right)
            add(component, BorderLayout.CENTER)
        }
    }

    private fun installDetailViewportRepaintSync(scrollPane: JBScrollPane) {
        scrollPane.viewport.addChangeListener {
            detailTabs.revalidate()
            detailTabs.repaint()
            detailPanel.revalidate()
            detailPanel.repaint()
            detailCard.repaint()
        }
    }

    private fun buildOverviewPanel(): JComponent {
        val panel = buildDetailTabBody().apply {
            isOpaque = true
            background = detailTabPaneFill()
        }

        overviewDesc.lineWrap = true
        overviewDesc.wrapStyleWord = true
        overviewDesc.rows = 6
        overviewDesc.isEditable = false
        overviewDesc.isOpaque = false
        overviewDesc.border = JBUI.Borders.empty()
        overviewDesc.margin = JBUI.emptyInsets()
        overviewDesc.background = detailSurfaceFill()
        overviewDesc.foreground = detailPrimaryTextColor()
        overviewDesc.alignmentX = Component.LEFT_ALIGNMENT

        val descScroll = createDetailScrollPane(
            overviewDesc,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            fillColorProvider = ::detailSurfaceFill
        ).apply {
            detailOverviewDescScrollPaneRef = this
            val descLineHeight = overviewDesc.getFontMetrics(overviewDesc.font).height
            val descHeight = descLineHeight * 6 + JBUI.scale(20)
            preferredSize = Dimension(JBUI.scale(320), descHeight)
            minimumSize = Dimension(0, descHeight)
            maximumSize = Dimension(Int.MAX_VALUE, descHeight)
            installDetailViewportRepaintSync(this)
        }

        reviewStatusCardsPanel.alignmentX = Component.LEFT_ALIGNMENT
        renderReviewStatusCards(null)

        val overviewDescCard = stretchDetailTabChild(
            wrapDetailSurface(descScroll, padding = JBUI.insets(12, 8, 12, 8))
        )
        val reviewStatusSectionBody = stretchDetailTabChild(reviewStatusCardsPanel, stretchVertically = true)

        panel.add(buildOverviewSectionTitle("PR 描述"))
        panel.add(overviewDescCard)
        panel.add(Box.createVerticalStrut(JBUI.scale(16)))
        panel.add(reviewStatusSectionBody)
        panel.add(Box.createVerticalGlue())

        val overviewScroll = createDetailScrollPane(
            panel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            fillColorProvider = ::detailTabPaneFill
        ).apply {
            detailOverviewScrollPaneRef = this
            verticalScrollBar.unitIncrement = JBUI.scale(16)
            installDetailViewportRepaintSync(this)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = detailTabPaneFill()
            add(overviewScroll, BorderLayout.CENTER)
        }
    }

    private fun renderReviewStatusCards(detail: PrDetail?) {
        reviewStatusCardsPanel.removeAll()
        reviewStatusCardsPanel.layout = BoxLayout(reviewStatusCardsPanel, BoxLayout.Y_AXIS)

        val primaryReviewers = detail?.primaryReviewerInfos.orEmpty()
        val generalReviewers = detail?.generalReviewerInfos.orEmpty()

        keyReviewersField.text = primaryReviewers.joinToString(",").ifBlank { "无" }
        keyReviewersField.toolTipText = primaryReviewers.joinToString(",").ifBlank { null }
        keyReviewerHint.text = ""

        reviewersField.text = generalReviewers.joinToString(",").ifBlank { "无" }
        reviewersField.toolTipText = generalReviewers.joinToString(",").ifBlank { null }
        reviewerHint.text = ""

        reviewStatusCardsPanel.add(
            buildReviewerStatusGroup(
                "关键评审人审查状态（至少${detail?.overview?.needKeyReviewers ?: 0}人评审）",
                primaryReviewers,
                keyReviewersField
            )
        )
        reviewStatusCardsPanel.add(Box.createVerticalStrut(JBUI.scale(16)))
        reviewStatusCardsPanel.add(
            buildReviewerStatusGroup(
                "评审人审查状态（至少${detail?.overview?.needReviewers ?: 0}人评审）",
                generalReviewers,
                reviewersField
            )
        )
        reviewStatusCardsPanel.add(Box.createVerticalStrut(JBUI.scale(16)))
        reviewStatusCardsPanel.add(
            buildReviewStatusFooter(
                detail?.overview?.mergedType.orEmpty(),
                detail?.overview?.deleteBranchAfterMerged ?: false
            )
        )
        reviewStatusCardsPanel.revalidate()
        reviewStatusCardsPanel.repaint()
    }

    private fun buildReviewerStatusGroup(
        title: String,
        reviewers: List<ReviewerInfo>,
        field: JBTextField
    ): JComponent {
        val columnsPerRow = 4
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(buildOverviewSectionTitle(title))
        }
        if (reviewers.isEmpty()) {
            content.add(
                JBLabel(field.text).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    font = font.deriveFont(Font.PLAIN, globalUiFontSize)
                    foreground = detailMutedColor()
                    border = JBUI.Borders.emptyTop(2)
                }
            )
        } else {
            val rowCount = (reviewers.size + columnsPerRow - 1) / columnsPerRow
            val cards = JPanel(GridLayout(rowCount, columnsPerRow, JBUI.scale(12), JBUI.scale(12))).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            reviewers.forEach { cards.add(buildReviewStatusCard(it)) }
            repeat((columnsPerRow - reviewers.size % columnsPerRow) % columnsPerRow) {
                cards.add(JPanel().apply { isOpaque = false })
            }
            content.add(cards)
        }
        return content
    }

    private fun buildReviewStatusFooter(mergeType: String, deleteSourceBranch: Boolean): JComponent {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        content.add(buildOverviewSectionTitle("合并方式"))
        content.add(buildReadonlyMergeTypeField(mergeType))
        content.add(Box.createVerticalStrut(JBUI.scale(14)))
        deleteBranchCheck.isSelected = deleteSourceBranch
        deleteBranchCheck.isEnabled = false
        deleteBranchCheck.isOpaque = false
        deleteBranchCheck.alignmentX = Component.LEFT_ALIGNMENT
        deleteBranchCheck.foreground = detailPrimaryTextColor()
        content.add(deleteBranchCheck)
        return content
    }

    private fun buildReadonlyMergeTypeField(mergeType: String): JComponent {
        val displayText = detailMergeTypeDisplayText(mergeType)
        val fieldWidth = createMergeFieldFrameWidth()
        val fieldHeight = createMergeFieldFrameHeight()
        val textLabel = JBLabel(displayText).apply {
            foreground = createPrPrimaryTextColor()
            font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
            border = JBUI.Borders.empty(0, 0, 0, JBUI.scale(8))
        }
        val arrowLabel = JBLabel().apply {
            icon = object : Icon {
                private val size = JBUI.scale(12)

                override fun getIconWidth(): Int = size
                override fun getIconHeight(): Int = size

                override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = createPrPrimaryTextColor()
                        g2.stroke = BasicStroke(JBUI.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        val centerX = x + size / 2f
                        val centerY = y + size / 2f
                        val half = JBUI.scale(3).toFloat()
                        g2.drawLine((centerX - half).toInt(), (centerY - 1).toInt(), centerX.toInt(), (centerY + 2).toInt())
                        g2.drawLine(centerX.toInt(), (centerY + 2).toInt(), (centerX + half).toInt(), (centerY - 1).toInt())
                    } finally {
                        g2.dispose()
                    }
                }
            }
        }
        return RoundedOutlinePanel(
            fillColor = createPrInputFill(),
            outlineColor = createPrBorderColor(),
            arc = JBUI.scale(10)
        ).bindTheme(::createPrInputFill, ::createPrBorderColor).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(3, 10, 3, 10)
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(fieldWidth, fieldHeight)
            minimumSize = Dimension(fieldWidth, fieldHeight)
            maximumSize = Dimension(fieldWidth, fieldHeight)
            add(textLabel, BorderLayout.CENTER)
            add(arrowLabel, BorderLayout.EAST)
        }
    }

    private fun detailMergeTypeDisplayText(value: String): String = when (value.trim().lowercase()) {
        "merge" -> "Merge"
        "fast_forward", "squash" -> "Merge(Fast-Forward-Only)"
        else -> "合并时选择"
    }

    private fun mergeMethodOptionText(value: String): String = when (value.trim().lowercase()) {
        "merge" -> "Merge(总是创建一个合并节点，记录合并信息)"
        "fast_forward", "squash" -> "Merge(Fast-Forward-Only)(不创建合并节点，采用Fast-Forward-Only方式合并)"
        else -> "合并时选择"
    }

    private fun buildReviewStatusCard(reviewer: ReviewerInfo): JComponent {
        val statusColor = reviewerStatusColor(reviewer.approveStatus)
        val statusText = reviewerStatusText(reviewer.approveStatus)
        val normalizedStatus = reviewer.approveStatus.trim().lowercase()
        val isPendingReview = normalizedStatus !in setOf("approved", "rejected", "commented")
        val statusIcon = when (normalizedStatus) {
            "approved" -> AllIcons.Actions.Checked
            "rejected" -> AllIcons.General.Error
            else -> AllIcons.Actions.Pause
        }
        val avatarBackground = when (normalizedStatus) {
            "approved" -> withAlpha(statusColor, 56)
            "rejected" -> withAlpha(statusColor, 34)
            "commented" -> withAlpha(statusColor, 30)
            else -> if (isPendingReview) withAlpha(statusColor, 40) else detailSurfaceFill()
        }

        val avatar = RoundedOutlinePanel(
            fillColor = avatarBackground,
            outlineColor = withAlpha(statusColor, 120),
            arc = JBUI.scale(18)
        ).apply {
            preferredSize = Dimension(JBUI.scale(32), JBUI.scale(32))
            minimumSize = preferredSize
            maximumSize = preferredSize
            layout = GridBagLayout()
            add(JBLabel(ReviewerAvatarIcon(reviewer.username, statusColor)))
        }

        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(reviewer.username.ifBlank { "未知评审人" }).apply {
                font = font.deriveFont(Font.BOLD, globalUiFontSize)
                foreground = detailPrimaryTextColor()
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(JBLabel(statusText, statusIcon, SwingConstants.LEFT).apply {
                foreground = statusColor
                iconTextGap = JBUI.scale(6)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        val content = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(avatar, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
        }

        return wrapDetailSurface(content, padding = JBUI.insets(14))
    }

    private fun reviewerStatusText(status: String): String = when (status.trim().lowercase()) {
        "approved" -> "已通过审查"
        "rejected" -> "已拒绝"
        else -> "等待审查..."
    }

    private fun buildOverviewSectionTitle(title: String): JComponent {
        return JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD, overviewSectionTitleFontSize())
            foreground = detailPrimaryTextColor()
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun buildOverviewInfoCard(title: String, field: JBTextField, hint: JBLabel): JComponent {
        hint.foreground = detailMutedColor()
        hint.border = JBUI.Borders.emptyTop(8)
        hint.toolTipText = hint.text
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, globalUiFontSize + 1f)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(field.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(hint.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }
        return wrapDetailSurface(content, padding = JBUI.insets(14))
    }

    private fun buildSingleFieldRow(field: JBTextField): JComponent {
        val row = JPanel(BorderLayout())
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.isOpaque = false

        val fieldHeight = field.getFontMetrics(field.font).height + JBUI.scale(2)
        val fieldSize = Dimension(JBUI.scale(347), fieldHeight)
        field.preferredSize = fieldSize
        field.minimumSize = fieldSize
        field.maximumSize = fieldSize

        row.add(field, BorderLayout.WEST)
        return row
    }

    private fun section(title: String, component: JComponent): JComponent {
        val wrapper = JPanel(BorderLayout())
        wrapper.alignmentX = Component.LEFT_ALIGNMENT
        wrapper.isOpaque = false
        val label = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD, globalUiFontSize + 1f)
            border = JBUI.Borders.emptyBottom(8)
            foreground = detailMutedColor()
        }
        wrapper.add(label, BorderLayout.NORTH)
        wrapper.add(component, BorderLayout.CENTER)
        wrapper.border = JBUI.Borders.emptyBottom(12)
        return wrapper
    }

    private fun dialogTitleLabel(title: String): JComponent {
        return JBLabel(title).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = detailPrimaryTextColor()
        }
    }

    private fun dialogSectionLabel(title: String): JComponent {
        return JBLabel(title).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = detailMutedColor()
            border = JBUI.Borders.emptyBottom(8)
        }
    }

    private fun dialogCard(
        content: JComponent,
        fillColorProvider: () -> Color = ::detailPanelFill,
        outlineColorProvider: () -> Color = ::detailOutlineColor,
        padding: Insets = JBUI.insets(10)
    ): RoundedOutlinePanel {
        return RoundedOutlinePanel(
            fillColor = fillColorProvider(),
            outlineColor = outlineColorProvider(),
            arc = JBUI.scale(10)
        ).bindTheme(fillColorProvider, outlineColorProvider).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right)
            alignmentX = Component.LEFT_ALIGNMENT
            add(content, BorderLayout.CENTER)
        }
    }

    private fun dialogInputFill(): Color = JBColor(Color(0xFFFFFF), Color(0x2F3337))

    private fun dialogInputOutlineColor(): Color = JBColor(withAlpha(detailAccentColor, 110), withAlpha(detailAccentColor, 168))

    private fun createDialogRootPanel(title: String?, preferredWidth: Int, preferredHeight: Int? = null, body: JComponent): JComponent {
        val headerHeight = JBUI.scale(40)
        val headerPanel = title?.takeIf { it.isNotBlank() }?.let { headerTitle ->
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = true
                background = detailSurfaceFill()
                border = javax.swing.BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(detailOutlineColor(), 0, 0, 1, 0),
                    JBUI.Borders.empty(0, 12)
                )
                val size = Dimension(0, headerHeight)
                preferredSize = size
                minimumSize = size
                maximumSize = Dimension(Int.MAX_VALUE, headerHeight)
                isFocusable = true
                add(dialogTitleLabel(headerTitle))
                add(Box.createHorizontalGlue())
            }
        }

        val bodyWrapper = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = detailSurfaceFill()
            border = JBUI.Borders.empty(12)
            isFocusable = true
            add(body, BorderLayout.CENTER)
        }

        val focusTransferListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                (e.component as? JComponent)?.requestFocusInWindow()
            }
        }

        val targetHeight = preferredHeight ?: (
            (headerPanel?.preferredSize?.height ?: 0) +
                bodyWrapper.preferredSize.height
            ).coerceAtLeast(JBUI.scale(120))

        return RoundedOutlinePanel(
            fillColor = detailSurfaceFill(),
            outlineColor = detailOutlineColor(),
            arc = JBUI.scale(8)
        ).bindTheme(::detailSurfaceFill, ::detailOutlineColor).apply {
            layout = BorderLayout()
            preferredSize = Dimension(preferredWidth, targetHeight)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isFocusable = true
            addMouseListener(focusTransferListener)
            bodyWrapper.addMouseListener(focusTransferListener)
            headerPanel?.addMouseListener(focusTransferListener)
            if (headerPanel != null) {
                add(headerPanel, BorderLayout.NORTH)
            }
            add(bodyWrapper, BorderLayout.CENTER)
        }
    }

    private fun createDialogTextArea(placeholder: String, rows: Int): JBTextArea {
        return object : JBTextArea() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (text.isNotEmpty()) return
                val g2 = g.create() as Graphics2D
                try {
                    g2.color = detailMutedColor()
                    g2.font = font
                    val x = insets.left + JBUI.scale(2)
                    val y = insets.top + g2.fontMetrics.ascent
                    g2.drawString(placeholder, x, y)
                } finally {
                    g2.dispose()
                }
            }
        }.apply {
            lineWrap = true
            wrapStyleWord = true
            this.rows = rows
            isOpaque = false
            background = dialogInputFill()
            foreground = detailPrimaryTextColor()
            caretColor = detailPrimaryTextColor()
            font = font.deriveFont(13f)
            border = JBUI.Borders.empty()
            margin = JBUI.emptyInsets()
        }
    }

    private fun createDialogTextAreaCard(area: JBTextArea, preferredHeight: Int): JComponent {
        val scrollPane = JBScrollPane(area).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            viewport.background = dialogInputFill()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(16)
            preferredSize = Dimension(0, preferredHeight)
            minimumSize = Dimension(0, preferredHeight)
        }
        return dialogCard(
            scrollPane,
            fillColorProvider = ::dialogInputFill,
            outlineColorProvider = ::dialogInputOutlineColor,
            padding = JBUI.insets(8)
        )
    }

    private fun createDialogChoiceCard(
        description: String,
        radioButton: javax.swing.JRadioButton,
        alignTop: Boolean = true,
        padding: Insets = JBUI.insets(12),
        minHeight: Int? = null
    ): JComponent {
        val descriptionLabel = JBLabel("<html>${description}</html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(12f)
            foreground = detailMutedColor()
            verticalAlignment = SwingConstants.CENTER
        }
        val textPanel = if (alignTop) {
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentY = Component.TOP_ALIGNMENT
                border = JBUI.Borders.emptyTop(3)
                add(descriptionLabel)
            }
        } else {
            JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentY = Component.CENTER_ALIGNMENT
                add(descriptionLabel, BorderLayout.CENTER)
            }
        }

        radioButton.isOpaque = false
        radioButton.foreground = detailPrimaryTextColor()
        radioButton.font = radioButton.font.deriveFont(13f)
        radioButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        radioButton.alignmentY = if (alignTop) Component.TOP_ALIGNMENT else Component.CENTER_ALIGNMENT
        if (!alignTop) {
            radioButton.margin = JBUI.emptyInsets()
            radioButton.border = JBUI.Borders.empty()
        }

        val row = JPanel().apply {
            layout = if (alignTop) BoxLayout(this, BoxLayout.X_AXIS) else BorderLayout(JBUI.scale(10), 0)
            isOpaque = false
            if (alignTop) {
                add(radioButton)
                add(Box.createHorizontalStrut(JBUI.scale(10)))
                add(textPanel)
                add(Box.createHorizontalGlue())
            } else {
                add(radioButton, BorderLayout.WEST)
                add(textPanel, BorderLayout.CENTER)
            }
        }

        val card = dialogCard(row, padding = padding).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val preferredWidth = card.preferredSize.width
        val compactHeight = card.preferredSize.height.coerceAtLeast(minHeight?.let(JBUI::scale) ?: 0)
        card.preferredSize = Dimension(preferredWidth, compactHeight)
        if (!alignTop) {
            card.minimumSize = Dimension(0, compactHeight)
            card.maximumSize = Dimension(Int.MAX_VALUE, compactHeight)
        } else {
            card.maximumSize = Dimension(Int.MAX_VALUE, compactHeight)
        }
        fun updateCardColors(hovered: Boolean) {
            val fill = when {
                radioButton.isSelected -> JBColor(withAlpha(detailAccentColor, 18), withAlpha(detailAccentColor, 72))
                hovered -> JBColor(Color(0xFCFDFF), Color(0x3A3F45))
                else -> detailPanelFill()
            }
            val outline = when {
                radioButton.isSelected -> dialogInputOutlineColor()
                hovered -> JBColor(Color(0xC8D2E1), Color(0x5B6470))
                else -> detailOutlineColor()
            }
            card.updateColors(fill, outline)
        }
        card.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                radioButton.isSelected = true
            }

            override fun mouseEntered(e: MouseEvent) {
                updateCardColors(true)
            }

            override fun mouseExited(e: MouseEvent) {
                updateCardColors(false)
            }
        })
        radioButton.addItemListener {
            updateCardColors(card.mousePosition != null)
        }
        updateCardColors(false)
        return card
    }

    private fun createDialogInfoCard(title: String, description: String): JComponent {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(title).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.BOLD, 13f)
                foreground = detailPrimaryTextColor()
            })
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(JBLabel("<html>${description}</html>").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(12f)
                foreground = detailMutedColor()
            })
        }
        return dialogCard(content, padding = JBUI.insets(12))
    }

    private fun buildFileChangePanel(): JComponent {
        val changeSearchHint = "按名称搜索文件..."
        changeSearchField.emptyText.text = changeSearchHint
        changeSearchField.isOpaque = false
        changeSearchField.border = JBUI.Borders.empty()
        val changeSearchWidth = (changeSearchField.getFontMetrics(changeSearchField.font).stringWidth(changeSearchHint) + JBUI.scale(32)) * 2
        val changeSearchSize = Dimension(changeSearchWidth, changeSearchField.preferredSize.height)
        changeSearchField.preferredSize = changeSearchSize
        changeSearchField.minimumSize = changeSearchSize
        changeSummaryLabel.foreground = detailMutedColor()
        changeAdditionsLabel.foreground = JBColor(Color(0x1E8E3E), Color(0x57D163))
        changeDeletionsLabel.foreground = JBColor(Color(0xD93025), Color(0xF47067))

        changeSearchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyChangeTreeFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyChangeTreeFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyChangeTreeFilter()
        })

        configureListFilterButton(changeTreeToggleButton)
        configureListFilterButton(changeFlatToggleButton)

        changeTreeToggleButton.addActionListener {
            if (changeTreeFlatMode) {
                changeTreeFlatMode = false
                updateChangeModeToggleStyle()
                applyChangeTreeFilter()
            }
        }
        changeFlatToggleButton.addActionListener {
            if (!changeTreeFlatMode) {
                changeTreeFlatMode = true
                updateChangeModeToggleStyle()
                applyChangeTreeFilter()
            }
        }
        updateChangeModeToggleStyle()

        changeTree.emptyText.text = "暂无对比结果"
        changeTree.cellRenderer = ChangeTreeCellRenderer()
//        changeTree.setUI(object : BasicTreeUI() {
//            override fun installDefaults() {
//                super.installDefaults()
//                leftChildIndent = JBUI.scale(4)
//                rightChildIndent = JBUI.scale(2)
//            }
//        })
        changeTree.rowHeight = 0
        changeTree.isRootVisible = false
        changeTree.showsRootHandles = true
        changeTree.toggleClickCount = 0
        changeTree.isOpaque = false
        changeTree.border = JBUI.Borders.empty(6, 6)
        changeTree.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val path = resolveTreePathAtPoint(changeTree, e.x, e.y)
                changeTree.toolTipText = (changeTree.cellRenderer as? ChangeTreeCellRenderer)?.tooltipAt(changeTree, path, e.x, e.y)
            }
        })
        changeTree.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                changeTree.toolTipText = null
            }

            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dismissSearchFieldFocus()
                    resolveTreePathAtPoint(changeTree, e.x, e.y)?.let { changeTree.selectionPath = it }
                }
                showChangeTreePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                showChangeTreePopup(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val path = resolveTreePathAtPoint(changeTree, e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val userObject = node.userObject) {
                    is ChangeItem -> {
                        currentDetail ?: return
                        openDiff(userObject)
                    }
                    is String -> if (!changeTreeFlatMode) {
                        if (changeTree.isExpanded(path)) {
                            changeTree.collapsePath(path)
                        } else {
                            changeTree.expandPath(path)
                        }
                    }
                }
            }
        })

        val togglePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(changeTreeToggleButton)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(changeFlatToggleButton)
        }

        val toolbar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(
                wrapDetailSurface(
                    changeSearchField,
                    fillColor = searchFieldSurfaceFill(),
                    outlineColor = searchFieldOutlineColor(),
                    fillColorProvider = ::searchFieldSurfaceFill,
                    outlineColorProvider = ::searchFieldOutlineColor,
                    padding = JBUI.insets(5, 10)
                ),
                BorderLayout.WEST
            )
            add(wrapDetailSurface(togglePanel, padding = JBUI.insets(2)), BorderLayout.EAST)
        }

        val treeScroll = createDetailScrollPane(
            changeTree,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
            fillColorProvider = ::detailSurfaceFill
        )

        val footer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(14), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.customLineTop(detailOutlineColor())
            add(changeSummaryLabel)
            add(changeAdditionsLabel)
            add(changeDeletionsLabel)
        }

        val treeWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(treeScroll, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(8)
                add(footer, BorderLayout.WEST)
            }, BorderLayout.SOUTH)
        }

        val body = buildDetailTabBody().apply {
            add(stretchDetailTabChild(toolbar))
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(stretchDetailTabChild(wrapDetailSurface(treeWrapper, padding = JBUI.insets(8, 8, 0, 8)), stretchVertically = true))
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = detailTabPaneFill()
            add(body, BorderLayout.CENTER)
        }
    }

    private fun applyChangeTreeFilter() {
        val keyword = changeSearchField.text?.trim().orEmpty().lowercase()
        val visibleChanges = if (keyword.isBlank()) {
            currentFileChanges
        } else {
            currentFileChanges.filter {
                it.filePath.lowercase().contains(keyword) || it.filePath.substringAfterLast('/').lowercase().contains(keyword)
            }
        }
        changeTree.emptyText.text = when {
            currentFileChanges.isEmpty() -> "暂无对比结果"
            visibleChanges.isEmpty() -> "未找到匹配文件"
            else -> ""
        }
        renderChangeTreeNodes(visibleChanges)
    }

    private fun renderChangeTreeNodes(changes: List<ChangeItem>) {
        changeTreeRoot.removeAllChildren()
        var insertedFiles = 0

        if (changeTreeFlatMode) {
            changes.sortedBy { it.filePath.lowercase() }.forEach { change ->
                changeTreeRoot.add(DefaultMutableTreeNode(change))
                insertedFiles++
            }
        } else {
            changes.forEach { change ->
                if (insertChangeNode(change)) {
                    insertedFiles++
                }
            }
            sortTree(changeTreeRoot)
            compactDirectoryTree(changeTreeRoot)
        }

        changeTree.showsRootHandles = !changeTreeFlatMode
        changeTreeModel.reload()
        if (!changeTreeFlatMode) {
            expandAllFromRoot()
            SwingUtilities.invokeLater { expandAllFromRoot() }
        }

        val additions = changes.sumOf { it.additions }
        val deletions = changes.sumOf { it.deletions }
        changeSummaryLabel.text = "${changes.size} 个文件变更"
        changeAdditionsLabel.text = "+$additions additions"
        changeDeletionsLabel.text = "-$deletions deletions"
        updateDetailTabCounters(fileCount = currentFileChanges.size)

        if (insertedFiles < changes.size) {
            updateStatus("文件树构建异常: 期望${changes.size}，实际$insertedFiles")
        }
    }

    private fun buildCommitPanel(): JComponent {
        configureCommitTimelineContent()
        val pane = createDetailScrollPane(
            commitTimelineContent,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            ::detailSurfaceFill
        ).apply {
            border = JBUI.Borders.emptyTop(6)
            viewportBorder = null
            isWheelScrollingEnabled = false
            isDoubleBuffered = true
            viewport.isDoubleBuffered = true
            viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
            verticalScrollBar.unitIncrement = JBUI.scale(24)
            verticalScrollBar.blockIncrement = JBUI.scale(96)
        }
        commitTimelineScrollPane = pane

        pane.addMouseWheelListener { event -> scrollCommitTimelineByWheel(event) }
        pane.viewport.addMouseWheelListener { event -> scrollCommitTimelineByWheel(event) }
        commitTimelineContent.addMouseWheelListener { event -> scrollCommitTimelineByWheel(event) }

        val body = buildDetailTabBody().apply {
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(stretchDetailTabChild(wrapDetailSurface(pane, padding = JBUI.insets(6, 8, 6, 8)), stretchVertically = true))
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = detailTabPaneFill()
            add(body, BorderLayout.CENTER)
        }
    }

    private fun renderCommitTimeline(commits: List<CommitItem>, missingHashes: Set<String> = emptySet()) {
        updateDetailTabCounters(commitCount = commits.size)
        commitTableModel.setRows(commits, missingHashes)
        commitTimelineContent.removeAll()

        if (commits.isEmpty()) {
            commitTimelineContent.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(28, 8)
                add(JBLabel("暂无提交记录", SwingConstants.CENTER).apply {
                    foreground = detailMutedColor()
                    font = font.deriveFont(Font.PLAIN, globalUiFontSize + 1f)
                }, BorderLayout.CENTER)
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        } else {
            commits.forEachIndexed { index, commit ->
                val card = createCommitTimelineItem(commit, index == commits.lastIndex, missingHashes.contains(commit.hash)).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                }
                bindCommitTimelineMouseWheelRecursively(card)
                commitTimelineContent.add(card)
            }
        }

        commitTimelineContent.revalidate()
        repaintCommitTimelineViewport()
    }

    private fun createCommitTimelineItem(
        commit: CommitItem,
        isLast: Boolean,
        missing: Boolean
    ): JComponent {
        val markerBaseColor = if (UIUtil.isUnderDarcula()) Color.WHITE else Color.BLACK
        val lineColor = markerBaseColor
        val dangerColor = JBColor(Color(0xD93025), Color(0xF47067))
        val primaryTextColor = detailPrimaryTextColor()
        val markerColor = markerBaseColor
        val hashColor = primaryTextColor
        val cardFillColor = commitCardFill()
        val cardOutlineColor = commitCardOutlineColor()
        val title = JBLabel(commit.message.ifBlank { "(无提交信息)" }).apply {
            font = font.deriveFont(Font.BOLD, globalUiFontSize + 0.5f)
            foreground = primaryTextColor
        }
        val hashBadge = buildCommitHashBadge(commit.hash, hashColor)

        val metaLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = true
            isDoubleBuffered = true
            background = cardFillColor
            add(JBLabel(commit.author.ifBlank { "未知作者" }, ReviewerAvatarIcon(commit.author.ifBlank { "?" }, detailAccentColor), SwingConstants.LEFT).apply {
                foreground = detailMutedColor()
                iconTextGap = JBUI.scale(6)
            })
            add(JBLabel("|").apply { foreground = detailMutedColor() })
            add(JBLabel(commit.time.ifBlank { "-" }).apply { foreground = detailMutedColor() })
            if (missing) {
                add(JBLabel("（当前分支缺少该提交）").apply { foreground = dangerColor })
            }
        }

        val statsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = true
            isDoubleBuffered = true
            background = cardFillColor
            if (commit.additions > 0) {
                add(JBLabel("+${commit.additions}").apply {
                    foreground = JBColor(Color(0x1E8E3E), Color(0x57D163))
                    font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                })
            }
            if (commit.deletions > 0) {
                add(JBLabel("-${commit.deletions}").apply {
                    foreground = dangerColor
                    font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                })
            }
        }

        val metaRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = true
            isDoubleBuffered = true
            background = cardFillColor
            add(metaLeft, BorderLayout.WEST)
            if (statsPanel.componentCount > 0) {
                add(statsPanel, BorderLayout.EAST)
            }
        }

        val headerRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = true
            isDoubleBuffered = true
            background = cardFillColor
            add(title, BorderLayout.CENTER)
            add(hashBadge, BorderLayout.EAST)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            isDoubleBuffered = true
            background = cardFillColor
            border = JBUI.Borders.empty(12)
            add(headerRow)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(metaRow)
        }

        val card = JPanel(BorderLayout()).apply {
            isOpaque = false
            isDoubleBuffered = true
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.border.LineBorder(cardOutlineColor, JBUI.scale(1), true),
                JBUI.Borders.empty(3)
            )
            add(content, BorderLayout.CENTER)
        }
        val marker = TimelineMarkerPanel(markerColor, lineColor, false, isLast)

        val rowPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = true
            isDoubleBuffered = true
            background = detailSurfaceFill()
            add(marker, BorderLayout.WEST)
            add(card, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            isDoubleBuffered = true
            background = detailSurfaceFill()
            border = JBUI.Borders.empty(0, 0, if (isLast) 0 else JBUI.scale(10), 0)
            add(rowPanel, BorderLayout.CENTER)
        }
    }

    private fun buildCommitHashBadge(hash: String, color: Color): JComponent {
        val shortHash = if (hash.length > 7) hash.take(7) else hash
        val clickListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || hash.isBlank()) return
                copyToClipboard(hash)
                updateStatus("已复制提交编号: ${hash.take(7)}")
            }
        }
        val badgeFill = commitHashBadgeFill()
        val badge = JPanel(BorderLayout()).apply {
            isOpaque = false
            isDoubleBuffered = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = hash
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.border.LineBorder(withAlpha(color, if (UIUtil.isUnderDarcula()) 118 else 92), JBUI.scale(1), true),
                JBUI.Borders.empty(2)
            )
            addMouseListener(clickListener)
        }
        badge.add(JBLabel(shortHash).apply {
            isOpaque = true
            isDoubleBuffered = true
            background = badgeFill
            foreground = color
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            horizontalAlignment = SwingConstants.CENTER
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = hash
            border = JBUI.Borders.empty(1, 6)
            addMouseListener(clickListener)
        }, BorderLayout.CENTER)
        return badge
    }

    private fun setupDetailTabsHeader() {
        detailTabHeaders.clear()
        val headers = listOf(
            createDetailTabHeader("概览", null, null, isFirst = true),
            createDetailTabHeader("文件改动", fileChangeTabCountLabel, fileChangeWarningButton),
            createDetailTabHeader("提交记录", commitTabCountLabel, commitWarningLabel, isLast = true)
        )
        headers.forEachIndexed { index, header ->
            detailTabs.setTabComponentAt(index, header)
            detailTabHeaders.add(header)
        }
        if (!detailTabHeaderListenerBound) {
            detailTabs.addChangeListener {
                updateDetailTabHeaderStates()
                SwingUtilities.invokeLater { refreshDetailTabDisplay() }
            }
            detailTabHeaderListenerBound = true
        }
        updateDetailTabCounters(0, 0)
        updateDetailTabHeaderStates()
    }

    private fun createDetailTabHeader(
        title: String,
        badge: JComponent?,
        tail: JComponent?,
        isFirst: Boolean = false,
        isLast: Boolean = false
    ): DetailTabHeader {
        val titleLabel = if (title == "文件改动") {
            fileChangeTabTitleLabel.text = title
            fileChangeTabTitleLabel
        } else {
            JBLabel(title)
        }
        titleLabel.font = titleLabel.font.deriveFont(Font.PLAIN, detailSectionTitleFontSize())
        return DetailTabHeader(titleLabel, badge, tail, isFirst, isLast)
    }

    private fun updateDetailTabHeaderStates() {
        detailTabHeaders.forEachIndexed { index, header ->
            header.setSelectedState(index == detailTabs.selectedIndex)
        }
    }

    private fun selectDetailTab(index: Int) {
        if (index !in 0 until detailTabs.tabCount) return
        dismissSearchFieldFocus()
        if (detailTabs.selectedIndex != index) {
            detailTabs.selectedIndex = index
        }
    }

    private fun isPointerInside(component: Component): Boolean {
        val pointerLocation = MouseInfo.getPointerInfo()?.location ?: return false
        val localPoint = Point(pointerLocation)
        SwingUtilities.convertPointFromScreen(localPoint, component)
        return component.contains(localPoint)
    }

    private fun resolveTreePathAtPoint(tree: javax.swing.JTree, x: Int, y: Int): TreePath? {
        tree.getPathForLocation(x, y)?.let { return it }
        val closest = tree.getClosestPathForLocation(x, y) ?: return null
        val bounds = tree.getPathBounds(closest) ?: return null
        return closest.takeIf { y >= bounds.y && y < bounds.y + bounds.height }
    }

    private inner class DetailTabHeader(
        private val titleLabel: JBLabel,
        badge: JComponent?,
        tail: JComponent?,
        private val isFirst: Boolean,
        private val isLast: Boolean
    ) : JPanel(BorderLayout()) {
        private val contentPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
        }
        private var selectedState = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val sideInset = detailHeaderSideInset()
            border = JBUI.Borders.empty(
                JBUI.scale(2),
                if (isFirst) sideInset else JBUI.scale(12),
                JBUI.scale(12),
                if (isLast) sideInset else JBUI.scale(12)
            )
            contentPanel.add(titleLabel)
            if (badge != null) {
                contentPanel.add(Box.createHorizontalStrut(JBUI.scale(6)))
                contentPanel.add(badge)
            }
            if (tail != null) {
                contentPanel.add(Box.createHorizontalStrut(JBUI.scale(6)))
                contentPanel.add(tail)
            }
            add(contentPanel, BorderLayout.WEST)
            bindClickHandlerRecursively(this)
        }

        private fun bindClickHandlerRecursively(component: Component) {
            component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            component.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    val index = detailTabs.indexOfTabComponent(this@DetailTabHeader)
                    selectDetailTab(index)
                    when (component) {
                        fileChangeWarningButton -> toggleFileChangeWarningBalloon()
                        commitWarningLabel -> toggleCommitWarningBalloon()
                    }
                }
            })
            if (component is Container) {
                component.components.forEach { child -> bindClickHandlerRecursively(child) }
            }
        }

        override fun paintComponent(g: Graphics) {
            if (selectedState) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val bounds = contentPanel.bounds
                    if (bounds.width > 0 && bounds.height > 0) {
                        val insetX = JBUI.scale(8)
                        val insetY = JBUI.scale(4)
                        val x = (bounds.x - insetX).coerceAtLeast(0)
                        val y = (bounds.y - insetY).coerceAtLeast(0)
                        val fillWidth = (bounds.width + insetX * 2).coerceAtMost(width - x)
                        val fillHeight = (bounds.height + insetY * 2).coerceAtMost(height - y)
                        if (fillWidth > 0 && fillHeight > 0) {
                            val arc = JBUI.scale(12)
                            g2.color = detailTabHeaderSelectedFill()
                            g2.fillRoundRect(x, y, fillWidth, fillHeight, arc, arc)
                            g2.color = detailTabHeaderSelectedOutline()
                            g2.drawRoundRect(x, y, fillWidth - 1, fillHeight - 1, arc, arc)
                        }
                    }
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }

        fun setSelectedState(selected: Boolean) {
            selectedState = selected
            titleLabel.foreground = if (selected) detailTabHeaderSelectedTextColor() else detailTabHeaderMutedTextColor()
            titleLabel.font = titleLabel.font.deriveFont(if (selected) Font.BOLD else Font.PLAIN, detailSectionTitleFontSize())
            revalidate()
            repaint()
        }
    }

    private fun ensureOriginBranch(target: String): String {
        val raw = target.trim()
        if (raw.isBlank()) return raw
        if (raw.startsWith("origin/")) return raw
        if (raw.startsWith("refs/remotes/origin/")) return "origin/${raw.removePrefix("refs/remotes/origin/")}"
        if (raw.startsWith("refs/heads/")) return "origin/${raw.removePrefix("refs/heads/")}"
        if (raw.startsWith("refs/")) return raw
        return "origin/$raw"
    }

    private fun normalizeLocalBranchName(sourceBranch: String): String {
        val raw = sourceBranch.trim()
        if (raw.isBlank()) return raw
        return raw
            .removePrefix("refs/heads/")
            .removePrefix("refs/remotes/origin/")
            .removePrefix("origin/")
    }

    private fun resolveRefHash(repo: git4idea.repo.GitRepository, ref: String): String? {
        val handler = GitLineHandler(project, repo.root, GitCommand.REV_PARSE)
        handler.addParameters("--verify", ref)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        return result.output.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
    }

    private fun fetchRemoteBranches() {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return
        val remote = repo.remotes.firstOrNull() ?: return
        val handler = GitLineHandler(project, repo.root, GitCommand.FETCH)
        handler.addParameters(remote.name)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            val error = result.errorOutput.joinToString("\n").ifBlank { "unknown" }
            PrManagerFileLogger.warn("Git fetch failed: remote=${remote.name} error=$error")
        }
    }

    private fun updateFileChangeBranchWarning(sourceBranch: String) {
        val srBranch = normalizeLocalBranchName(sourceBranch)
        if (srBranch.isBlank()) {
            updateFileChangeWarning(false, null)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
                if (repo == null) {
                    updateFileChangeWarning(false, null)
                    return@executeOnPooledThread
                }

                val currentBranch = repo.currentBranch?.name.orEmpty()
                val isSourceBranch = currentBranch == srBranch

                val hasWarning = !isSourceBranch
                val tip = if (hasWarning) {
                    buildFileChangeWarningText(isSourceBranch)
                } else {
                    null
                }
                updateFileChangeWarning(hasWarning, tip)
            } catch (e: Exception) {
                PrManagerFileLogger.error("Check source branch failed: sourceBranch=$sourceBranch", e)
                updateFileChangeWarning(false, null)
            }
        }
    }

    private fun buildFileChangeWarningText(isSourceBranch: Boolean): String {
        val warningColor = JBColor(Color(0xD93025), Color(0xF47067))
        val noText = "<span style='color:${toHex(warningColor)};'>否</span>"
        val sourceText = if (isSourceBranch) "是" else noText
        return "<html>检查点：<br>" +
                "- 当前分支是否为源分支：$sourceText （影响：不是源分支，文件对比中上下文关联可能不准确）" +
                "</html>"
    }

    private fun updateFileChangeWarning(visible: Boolean, tooltip: String?) {
        SwingUtilities.invokeLater {
            fileChangeWarningText = tooltip
            fileChangeWarningButton.isVisible = visible
            fileChangeWarningButton.toolTipText = tooltip
            fileChangeTabTitleLabel.toolTipText = null
            if (!visible) {
                hideFileChangeWarningBalloon()
            }
            detailTabs.revalidate()
            detailTabs.repaint()
        }
    }

    private fun updateCommitWarning(visible: Boolean) {
        SwingUtilities.invokeLater {
            commitWarningText = if (visible) {
                "当前分支缺少如下提交记录，可能会影响文件对比中的上下文查看的准确性"
            } else {
                null
            }
            commitWarningLabel.isVisible = visible
            commitWarningLabel.toolTipText = commitWarningText
            if (!visible) {
                hideCommitWarningBalloon()
            }
            detailTabs.revalidate()
            detailTabs.repaint()
        }
    }

    private fun toggleCommitWarningBalloon() {
        if (commitWarningBalloon != null) {
            hideCommitWarningBalloon()
            return
        }
        showCommitWarningBalloon()
    }

    private fun showCommitWarningBalloon() {
        val text = commitWarningText?.takeIf { it.isNotBlank() } ?: return
        commitWarningBalloon?.hide()
        val fgColor = UIUtil.getToolTipForeground()
        val bgColor = UIUtil.getToolTipBackground()
        val styledText = wrapHtmlWithColor(text, fgColor)
        val balloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(
                styledText,
                null,
                bgColor,
                UIUtil.getBoundsColor(),
                null
            )
            .setHideOnClickOutside(true)
            .setHideOnKeyOutside(true)
            .setAnimationCycle(80)
            .createBalloon()
        commitWarningBalloon = balloon
        balloon.show(RelativePoint.getSouthOf(commitWarningLabel), Balloon.Position.below)
    }

    private fun hideCommitWarningBalloon() {
        commitWarningBalloon?.hide()
        commitWarningBalloon = null
    }

    private fun toggleFileChangeWarningBalloon() {
        if (fileChangeWarningBalloon != null) {
            hideFileChangeWarningBalloon()
            return
        }
        showFileChangeWarningBalloon()
    }

    private fun showFileChangeWarningBalloon() {
        val text = fileChangeWarningText?.takeIf { it.isNotBlank() } ?: return
        fileChangeWarningBalloon?.hide()
        val fgColor = UIUtil.getToolTipForeground()
        val bgColor = UIUtil.getToolTipBackground()
        val styledText = wrapHtmlWithColor(text, fgColor)
        val balloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(
                styledText,
                null,
                bgColor,
                UIUtil.getBoundsColor(),
                null
            )
            .setHideOnClickOutside(true)
            .setHideOnKeyOutside(true)
            .setAnimationCycle(80)
            .createBalloon()
        fileChangeWarningBalloon = balloon
        balloon.show(RelativePoint.getSouthOf(fileChangeWarningButton), Balloon.Position.below)
    }

    private fun hideFileChangeWarningBalloon() {
        fileChangeWarningBalloon?.hide()
        fileChangeWarningBalloon = null
    }

    private fun wrapHtmlWithColor(html: String, color: Color): String {
        val body = if (html.startsWith("<html>") && html.endsWith("</html>")) {
            html.removePrefix("<html>").removeSuffix("</html>")
        } else {
            html
        }
        return "<html><div style='color:${toHex(color)};'>$body</div></html>"
    }

    private fun toHex(color: Color): String {
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }

    private fun bindActions() {
        statusFilterButtons.forEachIndexed { index, button ->
            button.addActionListener {
                try {
                    dismissSearchFieldFocus()
                    activeFilter = when (index) {
                        0 -> PrFilter.OPEN
                        1 -> PrFilter.MERGED
                        else -> PrFilter.CLOSED
                    }
                    PrManagerFileLogger.info("Status filter changed: index=$index filter=$activeFilter roleFilter=$activeRoleFilter")
                    updateFilterButtonStyles()
                    resetAndLoad()
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Failed to handle status filter change", e)
                }
            }
        }

        roleFilterButtons.forEachIndexed { index, button ->
            button.addActionListener {
                try {
                    dismissSearchFieldFocus()
                    activeRoleFilter = when (index) {
                        1 -> PrRoleFilter.CREATED_BY_ME
                        2 -> PrRoleFilter.REVIEWED_BY_ME
                        else -> PrRoleFilter.ALL
                    }
                    PrManagerFileLogger.info("Role filter changed: index=$index filter=$activeFilter roleFilter=$activeRoleFilter")
                    updateFilterButtonStyles()
                    resetAndLoad()
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Failed to handle role filter change", e)
                }
            }
        }

        searchField.addActionListener {
            try {
                val keyword = searchField.text?.trim().orEmpty()
                PrManagerFileLogger.info("Search triggered by Enter: keyword=$keyword filter=$activeFilter roleFilter=$activeRoleFilter")
                resetAndLoad()
            } catch (e: Exception) {
                PrManagerFileLogger.error("Failed to search PR list", e)
            }
        }

        refreshButton.addActionListener {
            try {
                dismissSearchFieldFocus()
                PrManagerFileLogger.info("Refresh button clicked")
                resetAndLoad()
            } catch (e: Exception) {
                PrManagerFileLogger.error("Failed to refresh PR list", e)
            }
        }

        createPrButton.addActionListener {
            try {
                if (!canCreatePr) {
                    updateStatus("无权限创建 PR")
                    return@addActionListener
                }
                if (isCreatePrViewActive) {
                    updateStatus(if (createPrView?.activeMode == InlinePrMode.EDIT) "请先处理当前编辑PR" else "请先处理当前新建PR")
                    return@addActionListener
                }
                showCreatePrView()
            } catch (e: Exception) {
                PrManagerFileLogger.error("Failed to open create PR dialog", e)
                updateStatus("打开创建窗口失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun refreshCreatePermission(retryCount: Int = 0) {
        val sshPath = resolveGitAddress()
        val currentUser = System.getenv(pluginAuthorUsernameEnv).orEmpty().trim()
        if (sshPath.isBlank()) {
            if (retryCount < 6) {
                PrManagerFileLogger.info("Create PR permission deferred: sshPath is blank, retry=${retryCount + 1}")
                createPrPermissionLoaded = false
                canCreatePr = false
                createPrRoleName = ""
                applyCreatePrButtonState()
                scheduleCreatePrPermissionRetry(retryCount + 1)
            } else {
                createPrPermissionLoaded = true
                canCreatePr = false
                createPrRoleName = ""
                applyCreatePrButtonState()
            }
            return
        }
        if (currentUser.isBlank()) {
            createPrPermissionLoaded = true
            canCreatePr = false
            createPrRoleName = ""
            applyCreatePrButtonState()
            return
        }
        try {
            val role = if (mockEnabled) {
                val mockJson = readMockJson(mockRepoMemberRoleFile)
                    ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockRepoMemberRoleFile")
                parseRepoMemberRole(mockJson)
            } else {
                val response = apiService.fetchRepoMemberRole(sshPath = sshPath, userName = currentUser)
                if (response.statusCode() !in 200..299) {
                    PrManagerFileLogger.warn("Fetch repo member role failed, status=${response.statusCode()}")
                    ""
                } else {
                    parseRepoMemberRole(response.body())
                }
            }
            createPrPermissionLoaded = true
            createPrRoleName = role
            canCreatePr = role in setOf("拥有者", "配置", "管理", "协作", "owner", "maintainer", "master", "admin", "developer")
            applyCreatePrButtonState()
        } catch (e: Exception) {
            PrManagerFileLogger.error("Fetch repo member role failed", e)
            createPrPermissionLoaded = true
            canCreatePr = false
            createPrRoleName = ""
            applyCreatePrButtonState()
        }
    }

    private fun scheduleCreatePrPermissionRetry(retryCount: Int) {
        SwingUtilities.invokeLater {
            javax.swing.Timer(400) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    refreshCreatePermission(retryCount)
                }
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    private fun applyCreatePrButtonState() {
        SwingUtilities.invokeLater {
            val enabled = createPrPermissionLoaded && canCreatePr && !isCreatePrViewActive
            val activeViewHint = if (createPrView?.activeMode == InlinePrMode.EDIT) {
                "请先处理当前编辑PR"
            } else {
                "请先处理当前新建PR"
            }
            createPrButton.isEnabled = enabled
            createPrButton.toolTipText = when {
                !createPrPermissionLoaded || !canCreatePr -> "无权限"
                isCreatePrViewActive -> activeViewHint
                else -> "创建新的 Pull Request"
            }
            createPrButton.repaint()
        }
    }

    private fun setCreatePrViewActive(active: Boolean) {
        if (isCreatePrViewActive == active) return
        isCreatePrViewActive = active
        applyCreatePrButtonState()
    }

    private fun parseRepoMemberRole(body: String): String {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull() ?: return ""
        val result = root.get("result") ?: root
        return result.readText("role_name", "roleName")
    }

    private fun dismissSearchFieldFocus() {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focusOwner = focusManager.focusOwner
        val shouldDismiss = focusOwner === searchField || focusOwner === changeSearchField
        if (!shouldDismiss) return

        val focusTransferred = when (focusOwner) {
            searchField -> refreshButton.requestFocusInWindow() || detailTabs.requestFocusInWindow() || requestFocusInWindow()
            changeSearchField -> changeTree.requestFocusInWindow() || detailTabs.requestFocusInWindow() || requestFocusInWindow()
            else -> requestFocusInWindow()
        }
        if (!focusTransferred) {
            SwingUtilities.invokeLater {
                focusManager.clearGlobalFocusOwner()
            }
        }
    }

    private fun installSearchFieldBlur(component: Component) {
        if (component is javax.swing.text.JTextComponent || component is javax.swing.JTree) {
            return
        }
        component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dismissSearchFieldFocus()
                }
            }
        })
        if (component is Container) {
            component.components.forEach { child -> installSearchFieldBlur(child) }
        }
    }

    private fun installHomepageSearchFocusGuard() {
        addHierarchyListener { event ->
            if (event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() == 0L || !isShowing) {
                return@addHierarchyListener
            }
            SwingUtilities.invokeLater {
                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (focusOwner === searchField && !refreshButton.requestFocusInWindow()) {
                    dismissSearchFieldFocus()
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            val mouseEvent = event as? MouseEvent ?: return@addAWTEventListener
            if (mouseEvent.id != MouseEvent.MOUSE_PRESSED || !SwingUtilities.isLeftMouseButton(mouseEvent)) {
                return@addAWTEventListener
            }
            if (!isShowing) return@addAWTEventListener
            val sourceComponent = mouseEvent.component ?: return@addAWTEventListener
            if (!SwingUtilities.isDescendingFrom(sourceComponent, this)) return@addAWTEventListener
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focusOwner !== searchField) return@addAWTEventListener
            if (SwingUtilities.isDescendingFrom(sourceComponent, searchField)) return@addAWTEventListener
            dismissSearchFieldFocus()
        }, AWTEvent.MOUSE_EVENT_MASK)
    }

    private fun scrollPrListByWheel(event: MouseWheelEvent) {
        val scrollPane = prListScrollPane ?: return
        val scrollBar = scrollPane.verticalScrollBar ?: return
        val maxValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(scrollBar.minimum)
        if (maxValue <= scrollBar.minimum) return
        val unitIncrement = scrollBar.unitIncrement.takeIf { it > 0 } ?: JBUI.scale(24)
        val delta = (event.preciseWheelRotation * unitIncrement * 3).toInt()
        if (delta == 0) return
        userTriggeredListScroll = true
        scrollBar.value = (scrollBar.value + delta).coerceIn(scrollBar.minimum, maxValue)
        event.consume()
    }

    private fun scrollCommitTimelineByWheel(event: MouseWheelEvent) {
        val scrollPane = commitTimelineScrollPane ?: return
        val scrollBar = scrollPane.verticalScrollBar ?: return
        val maxValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(scrollBar.minimum)
        if (maxValue <= scrollBar.minimum) return
        val unitIncrement = scrollBar.unitIncrement.takeIf { it > 0 } ?: JBUI.scale(24)
        val delta = (event.preciseWheelRotation * unitIncrement * 3).toInt()
        if (delta == 0) return
        scrollBar.value = (scrollBar.value + delta).coerceIn(scrollBar.minimum, maxValue)
        event.consume()
    }

    private fun bindCommentActions() {
        commentManager.setRemoteHandler(object : LineCommentManager.CommentRemoteHandler {
            override fun addComment(filePath: String, line: Int, side: com.intellij.diff.util.Side, content: String) {
                val detail = currentDetail ?: return
                if (mockEnabled) {
                    LineCommentStore.addComment(filePath, line, side, content, System.getenv("USERID").orEmpty())
                    return
                }
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val response = apiService.createNote(
                            prId = detail.id,
                            commitId = detail.headCommitSha,
                            filePath = filePath,
                            context = content,
                            codeLine = line + 1
                        )
                        if (response.statusCode() !in 200..299) {
                            updateStatus("评论失败: ${response.statusCode()}")
                            return@executeOnPooledThread
                        }
                        loadNotes(detail)
                    } catch (e: Exception) {
                        PrManagerFileLogger.error("Add comment failed: prId=${detail.id} filePath=$filePath line=${line + 1}", e)
                        updateStatus("评论失败: ${e.message ?: "未知错误"}")
                    }
                }
            }

            override fun addReply(filePath: String, line: Int, side: com.intellij.diff.util.Side, parent: LineComment, content: String) {
                val detail = currentDetail ?: return
                if (mockEnabled) {
                    LineCommentStore.addReply(
                        filePath = filePath,
                        line = line,
                        side = side,
                        parentId = parent.id,
                        content = content,
                        author = System.getenv("USERID").orEmpty(),
                        rootId = parent.rootId,
                        replyFloorNum = parent.floorNum
                    )
                    return
                }
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val response = apiService.replyNote(
                            prId = detail.id,
                            context = content,
                            nodeId = parent.rootId.takeIf { it.isNotBlank() },
                            replyNoteId = parent.id.takeIf { it.isNotBlank() },
                            replyUserId = parent.authorId
                        )
                        if (response.statusCode() !in 200..299) {
                            updateStatus("回复失败: ${response.statusCode()}")
                            return@executeOnPooledThread
                        }
                        loadNotes(detail)
                    } catch (e: Exception) {
                        PrManagerFileLogger.error("Reply comment failed: prId=${detail.id} filePath=$filePath line=${line + 1} parentId=${parent.id}", e)
                        updateStatus("回复失败: ${e.message ?: "未知错误"}")
                    }
                }
            }

            override fun resolveThread(filePath: String, line: Int, side: com.intellij.diff.util.Side, root: LineComment) {
                val detail = currentDetail ?: return
                if (mockEnabled) {
                    LineCommentStore.getComments(filePath, line, side)
                        .filter { it.rootId == root.rootId }
                        .forEach { LineCommentStore.resolveComment(filePath, line, side, it.id) }
                    return
                }
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val response = apiService.resolveNote(
                            prIid = detail.iid,
                            nodeId = root.rootId,
                            sshPath = resolveGitAddress()
                        )
                        if (response.statusCode() !in 200..299) {
                            updateStatus("问题已解决提交失败: ${response.statusCode()}")
                            return@executeOnPooledThread
                        }
                        loadNotes(detail)
                    } catch (e: Exception) {
                        PrManagerFileLogger.error("Resolve thread failed: prIid=${detail.iid} rootId=${root.rootId}", e)
                        updateStatus("问题已解决提交失败: ${e.message ?: "未知错误"}")
                    }
                }
            }
        })
    }

    private fun resetAndLoad() {
        PrManagerFileLogger.info("Reset list state and reload")
        createPrPermissionLoaded = false
        canCreatePr = false
        applyCreatePrButtonState()
        prListQueryVersion += 1
        activePrListLoadId = 0L
        isLoading = false
        currentPage = 1
        totalPage = 0
        totalCount = 0
        userTriggeredListScroll = false
        lastListScrollValue = 0
        hasMorePrs = false
        updateLoadMoreState(loading = false, hasMore = false)
        tableModel.setRows(emptyList(), append = false)
        prListSupplementCache.clear()
        prListSupplementLoading.clear()
        prCardMap.clear()
        selectedPrId = null
        rebuildPrListCards()
        SwingUtilities.invokeLater {
            prListScrollPane?.verticalScrollBar?.value = 0
        }
        renderEmptyDetail()
        loadPrs(append = false)
    }

    private fun loadPrs(append: Boolean = false, keywordOverride: String? = null) {
        val queryVersion = prListQueryVersion
        if (isLoading) return
        if (append && totalPage > 0 && currentPage >= totalPage) return
        if (append && totalCount > 0 && tableModel.rowCount >= totalCount) return
        val loadId = ++prListLoadSequence
        activePrListLoadId = loadId
        isLoading = true
        statusLabel.text = "加载中..."
        updateLoadMoreState(loading = append, hasMore = false)
        PrManagerFileLogger.info("Start loading PR list: append=$append currentPage=$currentPage totalPage=$totalPage queryVersion=$queryVersion loadId=$loadId")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val sshPath = resolveGitAddress()
                val result = if (mockEnabled) {
                    val mockJson = readMockJson(mockListFile) ?: ""
                    if (mockJson.isBlank()) {
                        PrListResult(0, emptyList(), 0, 0)
                    } else {
                        buildMockPrListResult(mockJson, append, keywordOverride)
                    }
                } else {
                    val requestBody = buildListRequestBody(append, sshPath, keywordOverride)
                    val response = apiService.fetchPrList(requestBody)
                    if (response.statusCode() !in 200..299) {
                        PrManagerFileLogger.warn("Load PR list failed, status=${response.statusCode()}")
                        PrListResult(0, emptyList(), 0, 0)
                    } else {
                        parsePrList(response.body())
                    }
                }

                SwingUtilities.invokeLater {
                    if (!append) {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            refreshCreatePermission()
                        }
                    }
                    if (queryVersion != prListQueryVersion || loadId != activePrListLoadId) {
                        PrManagerFileLogger.info("Ignore stale PR list result: append=$append page=${result.page} queryVersion=$queryVersion activeQueryVersion=$prListQueryVersion loadId=$loadId activeLoadId=$activePrListLoadId")
                        return@invokeLater
                    }
                    totalCount = result.total
                    totalPage = result.totalPage
                    currentPage = result.page
                    tableModel.setRows(result.items, append = append)
                    rebuildPrListCards()
                    val loaded = tableModel.rowCount
                    val hasMore = (totalPage > 0 && currentPage < totalPage) || (totalCount > 0 && loaded < totalCount)
                    hasMorePrs = hasMore
                    userTriggeredListScroll = false
                    lastListScrollValue = prListScrollPane?.verticalScrollBar?.value ?: 0
                    statusLabel.text = if (totalCount > 0) "已加载 $loaded/$totalCount 条 PR" else "暂无 PR"
                    updateLoadMoreState(loading = false, hasMore = hasMore)
                    preloadPrListSupplements(result.items)
                }
                PrManagerFileLogger.info("Finish loading PR list: append=$append page=${result.page} loaded=${result.items.size} total=${result.total}")
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load PR list error", e)
                SwingUtilities.invokeLater {
                    if (queryVersion != prListQueryVersion || loadId != activePrListLoadId) return@invokeLater
                    if (!append) {
                        tableModel.setRows(emptyList(), append = false)
                        rebuildPrListCards()
                    }
                    statusLabel.text = "暂无 PR"
                    hasMorePrs = false
                    userTriggeredListScroll = false
                    lastListScrollValue = prListScrollPane?.verticalScrollBar?.value ?: 0
                    updateLoadMoreState(loading = false, hasMore = false)
                }
            } finally {
                if (activePrListLoadId == loadId) {
                    isLoading = false
                }
            }
        }
    }

    private fun buildMockPrListResult(mockJson: String, append: Boolean, keywordOverride: String? = null): PrListResult {
        val parsed = parsePrList(mockJson)
        val keyword = keywordOverride?.trim().orEmpty().ifBlank { searchField.text?.trim().orEmpty() }.lowercase()
        val currentUser = System.getenv("USERID").orEmpty().trim()

        val filtered = parsed.items.filter { item ->
            val statusMatched = when (activeFilter) {
                PrFilter.OPEN -> item.state == PrState.OPEN
                PrFilter.MERGED -> item.state == PrState.MERGED
                PrFilter.CLOSED -> item.state == PrState.CLOSED
                PrFilter.ALL -> true
            }
            if (!statusMatched) return@filter false

            val roleMatched = when (activeRoleFilter) {
                PrRoleFilter.ALL -> true
                PrRoleFilter.CREATED_BY_ME -> currentUser.isNotBlank() && item.author == currentUser
                PrRoleFilter.REVIEWED_BY_ME -> currentUser.isNotBlank() && (
                    item.keyReviewers.contains(currentUser) ||
                        item.generalReviewers.contains(currentUser) ||
                        item.reviewers.any { it.username == currentUser }
                    )
            }
            if (!roleMatched) return@filter false

            if (keyword.isBlank()) return@filter true
            item.title.lowercase().contains(keyword) ||
                item.sourceBranch.lowercase().contains(keyword) ||
                item.targetBranch.lowercase().contains(keyword) ||
                item.author.lowercase().contains(keyword)
        }

        val total = filtered.size
        val totalPage = if (total == 0) 0 else (total + pageSize - 1) / pageSize
        val requestedPage = if (append) currentPage + 1 else 1
        val page = if (totalPage == 0) 1 else requestedPage.coerceAtMost(totalPage)
        val fromIndex = ((page - 1) * pageSize).coerceAtLeast(0)
        val toIndex = (fromIndex + pageSize).coerceAtMost(total)
        val pageItems = if (fromIndex in 0 until toIndex) filtered.subList(fromIndex, toIndex) else emptyList()

        return PrListResult(
            total = total,
            items = pageItems,
            page = page,
            totalPage = totalPage
        )
    }

    private fun buildListRequestBody(append: Boolean, sshPath: String, keywordOverride: String? = null): String {
        val status = when (activeFilter) {
            PrFilter.OPEN -> "opened"
            PrFilter.CLOSED -> "closed"
            PrFilter.MERGED -> "merged"
            PrFilter.ALL -> "all"
        }
        val pageValue = if (append) currentPage + 1 else 1
        val currentUser = System.getenv("USERID").orEmpty().trim()
        val payload = linkedMapOf(
            "sshPath" to sshPath,
            "page" to pageValue,
            "perPage" to pageSize,
            "states" to listOf(status),
            "sourceBranch" to "",
            "targetBranch" to "",
            "keywords" to (keywordOverride?.trim().orEmpty().ifBlank { searchField.text?.trim().orEmpty() })
        )
        if (currentUser.isNotBlank()) {
            when (activeRoleFilter) {
                PrRoleFilter.ALL -> Unit
                PrRoleFilter.CREATED_BY_ME -> payload["authorName"] = currentUser
                PrRoleFilter.REVIEWED_BY_ME -> payload["reviewerName"] = currentUser
            }
        }
        return objectMapper.writeValueAsString(payload)
    }

    private fun resolveGitAddress(): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return ""
        val remote = repo.remotes.firstOrNull { it.name == "origin" } ?: repo.remotes.firstOrNull()
        return remote?.urls?.firstOrNull().orEmpty()
    }

    private fun renderEmptyDetail() {
        setCreatePrViewActive(false)
        currentDetail = null
        currentDetailId = null
        currentAiOverview = null
        aiIssueCountByFileMap = emptyMap()
        reviewIssueCountByFileMap = emptyMap()
        currentFileChanges = emptyList()
        changeTreeFlatMode = false
        currentDiffFilePath = null
        mockAiIssueStatusOverrides.clear()
        updateAiReviewBadge(AiReviewBadgeState.NO_DATA)
        commentManager.updateAiIssues("", emptyList())
        updateFileChangeWarning(false, null)
        updateCommitWarning(false)
        (detailCard.layout as java.awt.CardLayout).show(detailCard, "empty")

        detailHeaderTitle.text = "未选择 PR"
        detailStatus.isVisible = false
        detailStatus.setBadge("", JBColor.GRAY)
        updateDetailMetaRowIndent()
        detailAuthorLabel.icon = null
        detailAuthorLabel.setPill("")
        detailCreateTimeLabel.icon = null
        detailCreateTimeLabel.setPill("")
        detailBranchLabel.icon = null
        detailBranchLabel.setPill("")
        issueCountLabel.setPill("")
        issueCountLabel.toolTipText = null
        resetDetailActionButtons()

        overviewDesc.text = ""
        renderReviewStatusCards(null)
        keyReviewersField.text = ""
        keyReviewersField.toolTipText = null
        keyReviewerHint.text = "-"
        reviewersField.text = ""
        reviewersField.toolTipText = null
        reviewerHint.text = "-"
        mergeTypeField.text = ""
        deleteBranchCheck.isSelected = false
        changeSearchField.text = ""
        changeSummaryLabel.text = "0 个文件变更"
        changeAdditionsLabel.text = "+0 additions"
        changeDeletionsLabel.text = "-0 deletions"
        updateChangeModeToggleStyle()

        changeTreeRoot.removeAllChildren()
        changeTree.emptyText.text = "暂无对比结果"
        changeTreeModel.reload()
        commitTableModel.setRows(emptyList())
        renderCommitTimeline(emptyList())
        updateDetailTabCounters(0, 0)
    }

    private fun showCreatePrView() {
        setCreatePrViewActive(true)
        createPrView?.prepareForCreate()
        (detailCard.layout as java.awt.CardLayout).show(detailCard, "create")
    }

    private fun showEditPrView(detail: PrDetail) {
        val source = detail.sourceBranch.trim()
        val target = detail.targetBranch.trim()
        if (source.isBlank() || target.isBlank()) {
            Messages.showErrorDialog(project, "当前 PR 的源分支或目标分支为空，无法编辑", "编辑 PR")
            return
        }
        if (source == target) {
            Messages.showErrorDialog(project, "源分支和目标分支不能相同", "编辑 PR")
            return
        }
        updateStatus("正在校验编辑 PR 条件...")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val check = createPrView?.requestPreCreateCheck(source, target)
                    ?: throw IllegalStateException("编辑窗口未初始化")
                if (check.code != 200 && !isEditExistingBranchPairCheck(check)) {
                    throw IllegalStateException(check.message.ifBlank { "分支不满足编辑条件，code=${check.code}" })
                }
                SwingUtilities.invokeLater {
                    setCreatePrViewActive(true)
                    createPrView?.prepareForEdit(detail, check)
                    (detailCard.layout as java.awt.CardLayout).show(detailCard, "create")
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Open edit PR view failed", e)
                SwingUtilities.invokeLater {
                    val message = e.message ?: "编辑前分支校验失败"
                    updateStatus("编辑 PR 前校验失败")
                    Messages.showErrorDialog(project, message, "编辑 PR")
                }
            }
        }
    }

    private fun isEditExistingBranchPairCheck(check: PreCreateCheck): Boolean {
        val normalized = check.message.trim().lowercase()
        if (normalized.isBlank()) return false
        val duplicateKeywords = listOf("已存在", "已经存在", "重复", "exists", "duplicate")
        val prKeywords = listOf("pr", "pull request", "合并请求")
        return duplicateKeywords.any { normalized.contains(it) } && prKeywords.any { normalized.contains(it) }
    }

    private fun exitCreatePrView() {
        setCreatePrViewActive(false)
        if (currentDetail != null && currentDetailId != null) {
            (detailCard.layout as java.awt.CardLayout).show(detailCard, "detail")
        } else {
            renderEmptyDetail()
        }
    }

    private fun confirmCloseCreatePrViewIfNeeded(): Boolean {
        if (!isCreatePrViewActive) return true
        val activeMode = createPrView?.activeMode ?: InlinePrMode.CREATE
        val contentText = if (activeMode == InlinePrMode.EDIT) {
            "当前存在 PR 编辑页面，确认关闭后继续查看该 PR 吗？"
        } else {
            "当前存在新建 PR 页面，确认关闭后继续查看该 PR 吗？"
        }
        val titleText = if (activeMode == InlinePrMode.EDIT) "关闭 PR 编辑页面" else "关闭新建 PR 页面"
        val choice = Messages.showYesNoDialog(
            project,
            contentText,
            titleText,
            "确认",
            "取消",
            null
        )
        return choice == Messages.YES
    }

    private fun showDetailMoreActionMenu() {
        val detail = currentDetail ?: return
        detailMorePopup?.cancel()

        val popupContent = RoundedOutlinePanel(
            fillColor = detailSurfaceFill(),
            outlineColor = detailOutlineColor(),
            arc = JBUI.scale(12)
        ).bindTheme(::detailSurfaceFill, ::detailOutlineColor).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
            add(createDetailMoreActionCard(
                title = "编辑",
                enabled = detail.canEdit,
                destructive = false
            ) {
                detailMorePopup?.cancel()
                showEditPrView(detail)
            })
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createDetailMoreActionCard(
                title = "删除",
                enabled = detail.canDelete,
                destructive = true
            ) {
                detailMorePopup?.cancel()
                confirmAndDeletePr(detail)
            })
        }

        detailMorePopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupContent, null)
            .setRequestFocus(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .setShowBorder(false)
            .setShowShadow(true)
            .createPopup()
        detailMorePopup?.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                detailMorePopup = null
            }
        })
        detailMorePopup?.show(RelativePoint.getSouthWestOf(detailMoreButton))
    }

    private fun createDetailMoreActionCard(
        title: String,
        enabled: Boolean,
        destructive: Boolean,
        onClick: () -> Unit
    ): JComponent {
        val button = createRoundedActionButton(
            text = title,
            fillColorProvider = {
                if (destructive) JBColor(Color(0xF9D2C2), Color(0x6E2F1A))
                else JBColor(Color(0x1A73E8), Color(0x3574F0))
            },
            hoverFillColorProvider = {
                if (destructive) JBColor(Color(0xF4B8A0), Color(0x8A3A20))
                else JBColor(Color(0x1557B0), Color(0x4682F2))
            },
            foregroundColorProvider = {
                if (destructive) JBColor(Color(0xA63118), Color.WHITE)
                else Color.WHITE
            },
            disabledFillColorProvider = { JBColor(Color(0xE5E7EB), Color(0x3C3F41)) },
            disabledForegroundColorProvider = { JBColor(Color(0x9CA3AF), Color(0x7D8694)) },
            outlineColorProvider = if (destructive) {
                { JBColor(Color(0xE99C7F), Color(0xA65435)) }
            } else {
                null
            },
            padding = JBUI.insets(2, 2),
            fontSize = detailCloseButton.font.size2D,
            bold = detailCloseButton.font.isBold,
            arc = JBUI.scale(8)
        ).apply {
            isEnabled = enabled
            addActionListener { onClick() }
            alignmentX = Component.LEFT_ALIGNMENT
            horizontalAlignment = SwingConstants.CENTER
        }
        val targetSize = detailCloseButton.preferredSize
        button.preferredSize = targetSize
        button.minimumSize = targetSize
        button.maximumSize = targetSize
        return button
    }

    private fun refreshDetailTabDisplay() {
        (detailTabs.getComponentAt(0) as? JComponent)?.let { overviewComponent ->
            overviewComponent.revalidate()
            overviewComponent.repaint()
        }
        (detailTabs.selectedComponent as? JComponent)?.let { selectedComponent ->
            selectedComponent.revalidate()
            selectedComponent.repaint()
        }
        overviewDesc.revalidate()
        overviewDesc.repaint()
        detailTabs.revalidate()
        detailTabs.repaint()
        detailCard.revalidate()
        detailCard.repaint()
    }

    private fun resetOverviewScrollPosition() {
        fun resetScrollPane(scrollPane: JBScrollPane?) {
            val target = scrollPane ?: return
            target.viewport?.viewPosition = Point(0, 0)
            target.verticalScrollBar?.value = 0
            target.horizontalScrollBar?.value = 0
            target.viewport?.revalidate()
            target.viewport?.repaint()
        }

        resetScrollPane(detailOverviewDescScrollPaneRef)
        resetScrollPane(detailOverviewScrollPaneRef)
    }

    private fun showDetail(prId: Long) {
        setCreatePrViewActive(false)
        selectPrCard(prId)
        currentDetailId = prId
        changeTreeFlatMode = false
        (detailCard.layout as java.awt.CardLayout).show(detailCard, "detail")
        resetOverviewScrollPosition()
        detailHeaderTitle.text = "加载中..."
        detailStatus.isVisible = false
        detailStatus.setBadge("", JBColor.GRAY)
        updateDetailMetaRowIndent()
        detailAuthorLabel.icon = null
        detailAuthorLabel.setPill("")
        detailCreateTimeLabel.icon = null
        detailCreateTimeLabel.setPill("")
        detailBranchLabel.icon = null
        detailBranchLabel.setPill("")
        issueCountLabel.setPill("")
        issueCountLabel.toolTipText = null
        detailConflictLabel.isVisible = false
        detailConflictResolvedLabel.isVisible = false
        currentAiOverview = null
        aiIssueCountByFileMap = emptyMap()
        reviewIssueCountByFileMap = emptyMap()
        currentFileChanges = emptyList()
        currentDiffFilePath = null
        mockAiIssueStatusOverrides.clear()
        updateAiReviewBadge(AiReviewBadgeState.NO_DATA)
        resetDetailActionButtons()
        changeSearchField.text = ""
        overviewDesc.text = ""
        overviewDesc.caretPosition = 0
        changeSummaryLabel.text = "0 个文件变更"
        changeAdditionsLabel.text = "+0 additions"
        changeDeletionsLabel.text = "-0 deletions"
        updateChangeModeToggleStyle()
        renderReviewStatusCards(null)
        renderCommitTimeline(emptyList())
        resetOverviewScrollPosition()
        refreshDetailTabDisplay()
        PrManagerFileLogger.info("Start loading PR detail: prId=$prId")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val detail = if (mockEnabled) {
                    val mockJson = readMockJson(mockDetailFile)
                        ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockDetailFile")
                    parseDetail(mockJson)
                } else {
                    val response = apiService.fetchPrDetail(prId)
                    if (response.statusCode() !in 200..299) {
                        PrManagerFileLogger.warn("Load PR detail failed: prId=$prId status=${response.statusCode()}")
                        updateStatus("详情加载失败: ${response.statusCode()}")
                        return@executeOnPooledThread
                    }
                    parseDetail(response.body())
                }
                SwingUtilities.invokeLater {
                    currentDetail = detail
                    renderDetail(detail)
                }
                PrManagerFileLogger.info("PR detail loaded: prId=$prId iid=${detail.iid}, srBranch=${detail.sourceBranch}, trBranch=${detail.targetBranch}")
                loadNotes(detail)
                loadAiReviewOverview(detail)
                ApplicationManager.getApplication().executeOnPooledThread {
                    fetchRemoteBranches()
                }
                updateFileChangeBranchWarning(detail.sourceBranch)
                loadDetailDiffAndCommits(detail)
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load PR detail error: prId=$prId", e)
                updateStatus("详情加载失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun renderDetail(detail: PrDetail) {
        val number = if (detail.iid > 0) detail.iid else detail.id
        detailHeaderTitle.text = "#${number} ${detail.title}"
        detailHeaderTitle.toolTipText = detailHeaderTitle.text
        val badge = statusBadge(detail.status)
        detailStatus.isVisible = true
        detailStatus.setBadge(badge.text, badge.color)
        updateDetailMetaRowIndent()

        detailAuthorLabel.icon = ReviewerAvatarIcon(detail.author.ifBlank { "?" }, detailAuthorPillColor)
        detailAuthorLabel.iconTextGap = JBUI.scale(6)
        detailAuthorLabel.setPill(detail.author.ifBlank { "未知作者" }, detailAuthorPillColor)
        detailCreateTimeLabel.icon = ClockMetaIcon(detailCreateTimePillColor)
        detailCreateTimeLabel.iconTextGap = JBUI.scale(6)
        detailCreateTimeLabel.setPill(detail.createTime.ifBlank { "时间未知" }, detailCreateTimePillColor)
        detailBranchLabel.icon = null
        detailBranchLabel.setPill("${detail.sourceBranch} → ${detail.targetBranch}", detailBranchPillColor)
        issueCountLabel.setPill("评审问题 0/0", detailIssuePillColor)
        issueCountLabel.toolTipText = "评审未解决问题/总问题 = 0/0"
        updateDetailConflictIndicators(detail)

        overviewDesc.text = detail.overview.desc.ifBlank { "暂无描述" }
        overviewDesc.caretPosition = 0
        renderReviewStatusCards(detail)
        updateDetailTabCounters(0, 0)
        updateDetailActionButtons(detail)
        resetOverviewScrollPosition()
        refreshDetailTabDisplay()
    }

    private fun resetDetailActionButtons() {
        detailAiReviewButton.isEnabled = false
        detailAiReviewButton.toolTipText = "当前 PR 不可发起AI评审"
        detailAiReviewButton.actionListeners.forEach { detailAiReviewButton.removeActionListener(it) }

        detailReviewButton.isEnabled = false
        detailReviewButton.toolTipText = "当前 PR 不可评审"
        detailReviewButton.actionListeners.forEach { detailReviewButton.removeActionListener(it) }

        detailAcceptButton.isEnabled = false
        detailAcceptButton.toolTipText = "当前 PR 不可接受"
        detailAcceptButton.actionListeners.forEach { detailAcceptButton.removeActionListener(it) }

        detailCloseButton.isEnabled = false
        detailCloseButton.toolTipText = "当前 PR 不可关闭"
        detailCloseButton.actionListeners.forEach { detailCloseButton.removeActionListener(it) }

        detailMoreButton.isEnabled = false
        detailMoreButton.toolTipText = "无更多可用操作"
    }

    private fun updateDetailActionButtons(detail: PrDetail) {
        resetDetailActionButtons()

        updateAiReviewActionButton(detail)

        detailReviewButton.isEnabled = detail.canReview
        detailReviewButton.toolTipText = if (detail.canReview) "评审当前 PR" else "当前 PR 不可评审"
        detailReviewButton.addActionListener { openReviewDialog(detail) }

        val canAccept = detail.canBeMerge && detail.canMerge
        detailAcceptButton.isEnabled = canAccept
        detailAcceptButton.toolTipText = if (canAccept) "接受当前 PR" else "当前 PR 不可接受"
        detailAcceptButton.addActionListener { openAcceptPrDialog(detail) }

        detailCloseButton.isEnabled = detail.canClose
        detailCloseButton.toolTipText = if (detail.canClose) "关闭当前 PR" else "当前 PR 不可关闭"
        detailCloseButton.addActionListener { confirmAndClosePr(detail) }

        val hasMoreActions = detail.canEdit || detail.canDelete
        detailMoreButton.isEnabled = hasMoreActions
        detailMoreButton.toolTipText = if (hasMoreActions) "更多操作" else "无更多可用操作"
    }

    private fun updateAiReviewActionButton(detail: PrDetail) {
        val prOpened = parseState(detail.status) == PrState.OPEN
        val inProgress = aiReviewBadgeState == AiReviewBadgeState.IN_PROGRESS
        val canTrigger = prOpened && !inProgress

        detailAiReviewButton.isEnabled = canTrigger
        detailAiReviewButton.toolTipText = when {
            inProgress -> "AI评审进行中"
            !prOpened -> "仅开启状态的 PR 可发起AI评审"
            else -> "发起AI评审"
        }
        if (canTrigger) {
            detailAiReviewButton.addActionListener { triggerAiReview(detail) }
        }
        detailAiReviewButton.repaint()
    }

    private fun updateDetailConflictIndicators(detail: PrDetail) {
        if (detail.showConflict) {
            detailConflictLabel.setPill("存在冲突", conflictPillColor())
            detailConflictLabel.toolTipText = "当前 PR 存在冲突"
            detailConflictLabel.isVisible = true
        } else {
            detailConflictLabel.toolTipText = null
            detailConflictLabel.isVisible = false
        }
        if (detail.hasResolvedConflictCommits) {
            detailConflictResolvedLabel.setPill("源分支解决过冲突", resolvedConflictPillColor())
            detailConflictResolvedLabel.toolTipText = "源分支存在解决过冲突的提交"
            detailConflictResolvedLabel.isVisible = true
        } else {
            detailConflictResolvedLabel.toolTipText = null
            detailConflictResolvedLabel.isVisible = false
        }
    }

    private fun openReviewDialog(detail: PrDetail) {
        val dialog = ReviewDialog(project) { reviewState, comment ->
            submitPrReview(detail, reviewState, comment)
        }
        dialog.show()
    }

    private fun submitPrReview(detail: PrDetail, reviewState: String, comment: String) {
        if (mockEnabled) {
            updateStatus("Mock模式：评审成功")
            refreshPrListAndCurrentDetail(detail.id)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val response = apiService.submitPrReview(
                    sshPath = resolveGitAddress(),
                    iid = detail.iid,
                    comment = comment,
                    state = reviewState
                )
                if (response.statusCode() !in 200..299 || !isBooleanSuccessResponse(response.body())) {
                    val message = extractApiMessage(response.body(), "评审失败")
                    PrManagerFileLogger.warn("Submit review failed: prId=${detail.id} iid=${detail.iid} status=${response.statusCode()} message=$message")
                    updateStatus(message)
                    return@executeOnPooledThread
                }
                PrManagerFileLogger.info("Submit review success: prId=${detail.id} iid=${detail.iid} state=$reviewState")
                updateStatus("评审成功")
                SwingUtilities.invokeLater { refreshPrListAndCurrentDetail(detail.id) }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Submit review error: prId=${detail.id} iid=${detail.iid}", e)
                updateStatus("评审失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun triggerAiReview(detail: PrDetail) {
        val currentUser = System.getenv(pluginAuthorUsernameEnv).orEmpty().trim()
        if (currentUser.isBlank()) {
            updateStatus("AI评审失败: 未获取到当前用户")
            return
        }
        if (currentFileChanges.isEmpty()) {
            updateStatus("AI评审失败: 当前未加载到文件改动")
            return
        }

        detailAiReviewButton.isEnabled = false
        detailAiReviewButton.toolTipText = "AI评审发起中"
        detailAiReviewButton.repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (mockEnabled) {
                    PrManagerFileLogger.info("Trigger AI review in mock mode: prId=${detail.id}")
                    updateStatus("Mock模式：AI评审已发起")
                    SwingUtilities.invokeLater {
                        markAiReviewInProgress(detail)
                    }
                    return@executeOnPooledThread
                }

                val fileDiffInfos = buildAiReviewFileDiffInfos(detail)
                if (fileDiffInfos.isEmpty()) {
                    SwingUtilities.invokeLater {
                        updateDetailActionButtons(detail)
                    }
                    updateStatus("AI评审失败: 当前没有可提交的文件改动")
                    return@executeOnPooledThread
                }

                val response = apiService.triggerAiReview(
                    prId = detail.id,
                    userOA = currentUser,
                    userName = currentUser,
                    fileDiffInfos = fileDiffInfos
                )
                if (response.statusCode() !in 200..299 || !isBooleanSuccessResponse(response.body())) {
                    val message = extractApiMessage(response.body(), "AI评审发起失败")
                    PrManagerFileLogger.warn("Trigger AI review failed: prId=${detail.id} status=${response.statusCode()} message=$message")
                    SwingUtilities.invokeLater {
                        updateDetailActionButtons(detail)
                    }
                    updateStatus(message)
                    return@executeOnPooledThread
                }

                PrManagerFileLogger.info("Trigger AI review success: prId=${detail.id} files=${fileDiffInfos.size}")
                updateStatus(extractApiMessage(response.body(), "AI评审已发起"))
                SwingUtilities.invokeLater {
                    markAiReviewInProgress(detail)
                }
                scheduleAiReviewOverviewRefresh(detail, 5_000L, preserveInProgressOnMissing = true)
            } catch (e: Exception) {
                PrManagerFileLogger.error("Trigger AI review error: prId=${detail.id}", e)
                SwingUtilities.invokeLater {
                    updateDetailActionButtons(detail)
                }
                updateStatus("AI评审发起失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun scheduleAiReviewOverviewRefresh(
        detail: PrDetail,
        delayMillis: Long = 3_000L,
        preserveInProgressOnMissing: Boolean = false
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@executeOnPooledThread
                }
            }
            loadAiReviewOverview(detail, preserveInProgressOnMissing)
        }
    }

    private fun buildAiReviewFileDiffInfos(detail: PrDetail): List<Map<String, String>> {
        val targetRef = ensureOriginBranch(detail.targetBranch)
        val sourceRef = ensureOriginBranch(detail.sourceBranch)
        if (targetRef.isBlank() || sourceRef.isBlank()) {
            throw IllegalStateException("源分支或目标分支为空")
        }

        return currentFileChanges.mapNotNull { change ->
            val filePath = change.filePath.trim().replace('\\', '/')
            if (filePath.isBlank()) {
                null
            } else {
                val diffResult = branchService.loadFileDiff(targetRef, sourceRef, filePath)
                if (diffResult.error != null) {
                    throw IllegalStateException("获取文件diff失败: $filePath, ${diffResult.error}")
                }
                mapOf(
                    "filePath" to filePath,
                    "diff" to diffResult.text
                )
            }
        }
    }

    private fun markAiReviewInProgress(detail: PrDetail) {
        currentAiOverview = (currentAiOverview?.takeIf { it.prId == detail.id } ?: AiReviewOverview(
            prId = detail.id,
            reviewFlag = AiReviewProgressFlag.NOT_STARTED,
            validFlag = false,
            errorCount = 0,
            warnCount = 0,
            unhandledCount = 0,
            adoptedCount = 0,
            ignoredCount = 0,
            misreportedCount = 0,
            fileTreeNodes = emptyList()
        )).copy(
            reviewFlag = AiReviewProgressFlag.IN_PROGRESS,
            validFlag = false,
            errorCount = 0,
            warnCount = 0,
            unhandledCount = 0,
            adoptedCount = 0,
            ignoredCount = 0,
            misreportedCount = 0,
            fileTreeNodes = emptyList()
        )
        aiIssueCountByFileMap = emptyMap()
        updateAiReviewBadge(AiReviewBadgeState.IN_PROGRESS)
        updatePrListAiState(detail.id, AiReviewBadgeState.IN_PROGRESS)
        updateDetailActionButtons(detail)
        changeTree.repaint()
    }

    private fun openAcceptPrDialog(detail: PrDetail) {
        val mergeMethod = detail.overview.mergedType.trim().lowercase()
        if (mergeMethod.isNotBlank()) {
            showMergeConfirmDialog(detail, mergeMethod)
            return
        }

        val picker = MergeMethodPickerDialog(project)
        if (!picker.showAndGet()) return
        val selectedMethod = picker.selectedMethod ?: return
        showMergeConfirmDialog(detail, selectedMethod)
    }

    private fun showMergeConfirmDialog(detail: PrDetail, mergeMethod: String) {
        val dialog = MergeConfirmDialog(
            project = project,
            detail = detail,
            mergeMethod = mergeMethod,
            defaultDelete = detail.overview.deleteBranchAfterMerged
        ) { commitMessage, extMessage, pruneBranch ->
            submitPrMerge(detail, mergeMethod, commitMessage, extMessage, pruneBranch)
        }
        dialog.show()
    }

    private fun submitPrMerge(
        detail: PrDetail,
        mergeMethod: String,
        commitMessage: String,
        extMessage: String,
        pruneBranch: Boolean
    ) {
        if (mockEnabled) {
            updateStatus("Mock模式：合并成功")
            refreshPrListAndCurrentDetail(detail.id)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val response = apiService.mergePrByUser(
                    sshPath = resolveGitAddress(),
                    number = detail.iid,
                    mergeMethod = mergeMethod,
                    commitMessage = commitMessage,
                    extMessage = extMessage,
                    pruneBranch = pruneBranch
                )
                if (response.statusCode() !in 200..299 || !isBooleanSuccessResponse(response.body())) {
                    val message = extractApiMessage(response.body(), "接受PR失败")
                    PrManagerFileLogger.warn("Merge PR failed: prId=${detail.id} iid=${detail.iid} status=${response.statusCode()} message=$message")
                    updateStatus(message)
                    return@executeOnPooledThread
                }
                PrManagerFileLogger.info("Merge PR success: prId=${detail.id} iid=${detail.iid} mergeMethod=$mergeMethod pruneBranch=$pruneBranch")
                updateStatus("接受PR成功")
                SwingUtilities.invokeLater { refreshPrListAndCurrentDetail(detail.id) }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Merge PR error: prId=${detail.id} iid=${detail.iid}", e)
                updateStatus("接受PR失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun buildMergeDefaultCommitMessage(detail: PrDetail): String {
        val number = if (detail.iid > 0) detail.iid else detail.id
        return "!$number ${detail.title.trim()}".trim()
    }

    private fun buildMergeDefaultExtMessage(detail: PrDetail): String {
        val number = if (detail.iid > 0) detail.iid else detail.id
        val authorOa = detail.authorUsername.trim().ifBlank { detail.author.trim() }
        val sourceBranch = detail.sourceBranch.trim()
        return "Merge pull request !$number from $authorOa $sourceBranch".trim()
    }

    private fun fastForwardMergeNoticeHtml(): String {
        return """
            使用Fast-Forward-Only方式合并，在目标分支上不创建合并节点。<br>
            当目标分支上有区别于源分支的差异Commit，不满足Fast-Forward-Only Merge条件时，会自动切换合并类型为Merge(创建合并节点）
        """.trimIndent()
    }

    private fun confirmAndClosePr(detail: PrDetail) {
        val choice = Messages.showYesNoDialog(
            project,
            "确认关闭当前 PR 吗？",
            "关闭 PR",
            "确认",
            "取消",
            null
        )
        if (choice != Messages.YES) return
        submitClosePr(detail)
    }

    private fun submitClosePr(detail: PrDetail) {
        if (mockEnabled) {
            updateStatus("Mock模式：关闭PR成功")
            refreshCurrentFilterAndDetail(detail.id)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val response = apiService.closePrByUser(
                    sshPath = resolveGitAddress(),
                    iid = detail.iid
                )
                if (response.statusCode() !in 200..299 || !isBooleanSuccessResponse(response.body())) {
                    val message = extractApiMessage(response.body(), "关闭PR失败")
                    PrManagerFileLogger.warn("Close PR failed: prId=${detail.id} iid=${detail.iid} status=${response.statusCode()} message=$message")
                    updateStatus(message)
                    return@executeOnPooledThread
                }
                PrManagerFileLogger.info("Close PR success: prId=${detail.id} iid=${detail.iid}")
                updateStatus("关闭PR成功")
                SwingUtilities.invokeLater { refreshCurrentFilterAndDetail(detail.id) }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Close PR error: prId=${detail.id} iid=${detail.iid}", e)
                updateStatus("关闭PR失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun refreshCurrentFilterAndDetail(prId: Long) {
        val preservedKeyword = searchField.text?.trim()
        refreshPrListAndCurrentDetail(prId, preservedKeyword)
    }

    private fun confirmAndDeletePr(detail: PrDetail) {
        val choice = Messages.showYesNoDialog(
            project,
            "确认删除当前 PR 吗？",
            "删除 PR",
            "确认",
            "取消",
            null
        )
        if (choice != Messages.YES) return
        submitDeletePr(detail)
    }

    private fun submitDeletePr(detail: PrDetail) {
        if (mockEnabled) {
            updateStatus("Mock模式：删除PR成功")
            renderEmptyDetail()
            resetAndLoad()
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val response = apiService.deletePrByUser(
                    sshPath = resolveGitAddress(),
                    iid = detail.iid
                )
                if (response.statusCode() !in 200..299 || !isBooleanSuccessResponse(response.body())) {
                    val message = extractApiMessage(response.body(), "删除PR失败")
                    PrManagerFileLogger.warn("Delete PR failed: prId=${detail.id} iid=${detail.iid} status=${response.statusCode()} message=$message")
                    updateStatus(message)
                    return@executeOnPooledThread
                }
                PrManagerFileLogger.info("Delete PR success: prId=${detail.id} iid=${detail.iid}")
                SwingUtilities.invokeLater {
                    updateStatus("删除PR成功")
                    renderEmptyDetail()
                    resetAndLoad()
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Delete PR error: prId=${detail.id} iid=${detail.iid}", e)
                updateStatus("删除PR失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun refreshPrListAndCurrentDetail(prId: Long, keywordOverride: String? = null) {
        refreshPrListPreservingDetail(prId, keywordOverride)
        showDetail(prId)
    }

    private fun refreshPrListPreservingDetail(prIdToKeep: Long?, keywordOverride: String? = null) {
        createPrPermissionLoaded = false
        canCreatePr = false
        applyCreatePrButtonState()
        prListQueryVersion += 1
        activePrListLoadId = 0L
        isLoading = false
        currentPage = 1
        totalPage = 0
        totalCount = 0
        userTriggeredListScroll = false
        lastListScrollValue = 0
        hasMorePrs = false
        updateLoadMoreState(loading = false, hasMore = false)
        tableModel.setRows(emptyList(), append = false)
        prListSupplementCache.clear()
        prListSupplementLoading.clear()
        prCardMap.clear()
        selectedPrId = prIdToKeep
        rebuildPrListCards()
        SwingUtilities.invokeLater {
            prListScrollPane?.verticalScrollBar?.value = 0
        }
        loadPrs(append = false, keywordOverride = keywordOverride)
    }

    private fun fetchAiReviewOverview(prId: Long): AiReviewOverview? {
        return if (mockEnabled) {
            val mockJson = readMockJson("ai-review-overview.json")
            if (mockJson.isNullOrBlank()) null else parseAiReviewOverview(mockJson)
        } else {
            val response = apiService.fetchAiReviewOverview(prId)
            if (response.statusCode() !in 200..299) {
                PrManagerFileLogger.warn("Load AI overview failed: prId=$prId status=${response.statusCode()}")
                null
            } else {
                parseAiReviewOverview(response.body())
            }
        }
    }

    private fun applyAiOverviewState(
        prId: Long,
        overview: AiReviewOverview?,
        aiState: AiReviewBadgeState,
        updateCurrentDetail: Boolean
    ) {
        if (updateCurrentDetail && currentDetail?.id == prId) {
            currentAiOverview = overview
            aiIssueCountByFileMap =
                overview?.takeIf { isAiReviewResultAvailable(it) }?.let { flattenAiTreeIssueCount(it.fileTreeNodes) }.orEmpty()
            updateAiReviewBadge(aiState)
            currentDetail?.let { updateDetailActionButtons(it) }
            changeTree.repaint()
        }
        updatePrListAiState(prId, aiState)
    }

    private fun isBooleanSuccessResponse(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return runCatching {
            val root = objectMapper.readTree(body)
            val result = root.get("result")
            when {
                result?.isBoolean == true -> result.asBoolean()
                result?.get("success")?.asBoolean() == true -> true
                result?.get("result")?.asBoolean() == true -> true
                root.get("success")?.asBoolean() == true -> true
                root.get("result")?.asBoolean() == true -> true
                root.get("type")?.asText()?.equals("S", ignoreCase = true) == true -> true
                result?.get("type")?.asText()?.equals("S", ignoreCase = true) == true -> true
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun extractApiMessage(body: String?, fallback: String): String {
        if (body.isNullOrBlank()) return fallback
        return runCatching {
            val root = objectMapper.readTree(body)
            val result = root.get("result")
            result?.readText("message", "msg", "code").orEmpty()
                .ifBlank { root.readText("message", "msg", "code") }
                .ifBlank { fallback }
        }.getOrDefault(fallback)
    }

    private fun parseCreatePrSubmitResult(body: String?): Pair<Boolean, Long?> {
        if (body.isNullOrBlank()) return false to null
        return runCatching {
            val root = objectMapper.readTree(body)
            val result = root.get("result") ?: return@runCatching (false to null)
            val created = result.get("create")?.asBoolean(false) == true
            val prId = result.get("id")?.asLong()
            created to prId
        }.getOrDefault(false to null)
    }

    private fun loadNotes(detail: PrDetail) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = if (mockEnabled) {
                    val mockJson = readMockJson(mockIssuesFile)
                        ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockIssuesFile")
                    parseNoteList(mockJson)
                } else {
                    val response = apiService.fetchNoteList(
                        sshPath = resolveGitAddress(),
                        iid = detail.iid
                    )
                    if (response.statusCode() !in 200..299) {
                        PrManagerFileLogger.warn("Load notes failed: prId=${detail.id} iid=${detail.iid} status=${response.statusCode()}")
                        return@executeOnPooledThread
                    }
                    parseNoteList(response.body())
                }
                PrIssueCache.replaceAll(detail.id, result.stats)
                LineCommentStore.replaceAll(result.comments)
                PrManagerFileLogger.info("Notes loaded: prId=${detail.id} total=${result.stats.total} unresolved=${result.stats.unresolved}")
                SwingUtilities.invokeLater {
                    reviewIssueCountByFileMap = buildReviewIssueCountByFileMap(result.stats)
                    issueCountLabel.setPill("评审问题 ${result.stats.unresolved}/${result.stats.total}", detailIssuePillColor)
                    issueCountLabel.toolTipText = "评审未解决问题/总问题=${result.stats.unresolved}/${result.stats.total}"
                    changeTree.repaint()
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load notes error: prId=${detail.id} iid=${detail.iid}", e)
            }
        }
    }

    private fun loadAiReviewOverview(detail: PrDetail, preserveInProgressOnMissing: Boolean = false) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val overview = fetchAiReviewOverview(detail.id)
                SwingUtilities.invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val isCurrentDetail = currentDetail?.id == detail.id
                    val keepInProgress =
                        preserveInProgressOnMissing &&
                            overview == null &&
                            isCurrentDetail &&
                            aiReviewBadgeState == AiReviewBadgeState.IN_PROGRESS
                    if (keepInProgress) {
                        updatePrListAiState(detail.id, AiReviewBadgeState.IN_PROGRESS)
                        if (isCurrentDetail) {
                            currentDetail?.let { updateDetailActionButtons(it) }
                            changeTree.repaint()
                        }
                        return@invokeLater
                    }
                    val aiState = resolveAiReviewState(overview)
                    applyAiOverviewState(
                        prId = detail.id,
                        overview = overview,
                        aiState = aiState,
                        updateCurrentDetail = isCurrentDetail
                    )
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load AI overview error: prId=${detail.id}", e)
                SwingUtilities.invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val isCurrentDetail = currentDetail?.id == detail.id
                    val keepInProgress =
                        preserveInProgressOnMissing &&
                            isCurrentDetail &&
                            aiReviewBadgeState == AiReviewBadgeState.IN_PROGRESS
                    if (keepInProgress) {
                        updatePrListAiState(detail.id, AiReviewBadgeState.IN_PROGRESS)
                        if (isCurrentDetail) {
                            currentDetail?.let { updateDetailActionButtons(it) }
                            changeTree.repaint()
                        }
                        return@invokeLater
                    }
                    if (isCurrentDetail) {
                        currentAiOverview = null
                        aiIssueCountByFileMap = emptyMap()
                        updateAiReviewBadge(AiReviewBadgeState.NO_DATA)
                        currentDetail?.let { updateDetailActionButtons(it) }
                        changeTree.repaint()
                    }
                    updatePrListAiState(detail.id, AiReviewBadgeState.NO_DATA)
                }
            }
        }
    }

    private fun updateAiReviewBadge(state: AiReviewBadgeState) {
        aiReviewBadgeState = state
        aiReviewBadgeLabel.setPill(aiReviewBadgeText(state), state.color)
        aiReviewBadgeLabel.cursor = if (state == AiReviewBadgeState.NO_DATA) Cursor.getDefaultCursor() else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        aiReviewBadgeLabel.toolTipText = aiReviewBadgeTooltip(state)
        aiReviewBadgeLabel.repaint()
    }

    private fun showAiOverviewPopup() {
        val overview = currentAiOverview ?: return
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(16, 16, 12, 16)
        panel.isOpaque = true
        panel.background = JBColor(Color(0xECEFF3), Color(0x24272B))

        val accentRed = JBColor(Color(0xD93025), Color(0xF47067))
        val accentOrange = JBColor(Color(0xF29900), Color(0xF6C26B))
        val accentGreen = JBColor(Color(0x1E8E3E), Color(0x57D163))
        val accentBlue = JBColor(Color(0x1A73E8), Color(0x6EA8FF))
        val textMain = JBColor(Color(0x202124), Color(0xDFE1E5))
        val textMuted = JBColor(Color(0x5F6368), Color(0x9AA0A6))
        val textHint = JBColor(Color(0x80868B), Color(0x7F8790))
        val borderColor = JBColor(Color(0xD0D7DE), Color(0x4B5563))

        val titleLabel = JBLabel("智能代码评审 - 总览")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, globalUiFontSize + 2f)
        titleLabel.foreground = textMain

        val headerPanel = JPanel(BorderLayout())
        headerPanel.isOpaque = false
        headerPanel.add(titleLabel, BorderLayout.WEST)
        panel.add(headerPanel)
        panel.add(Box.createVerticalStrut(12))

        val pass = overview.unhandledCount == 0
        val (bannerColor, bannerTitle, bannerDesc) = when (aiReviewBadgeState) {
            AiReviewBadgeState.IN_PROGRESS -> Triple(
                accentBlue,
                "评审计算中",
                "AI正在对变更代码进行智能化安全与质量审查..."
            )
            AiReviewBadgeState.STALE -> Triple(
                accentOrange,
                "数据已过期",
                "PR涉及分支有新代码提交，评审结论可能已经不准确"
            )
            AiReviewBadgeState.PASS, AiReviewBadgeState.FAIL -> {
                if (pass) {
                    Triple(accentGreen, "评审已通过", "恭喜！所有发现的 AI 评审问题均已处理完毕")
                } else {
                    Triple(accentRed, "评审未通过", "共发现 ${overview.unhandledCount} 个未处理问题，请及时处理")
                }
            }
            AiReviewBadgeState.NO_DATA -> Triple(
                textMuted,
                "未发起评审",
                "当前PR尚无智能评审数据"
            )
        }

        val statusBanner = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(bannerColor.red, bannerColor.green, bannerColor.blue, 25)
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
                    g2.color = Color(bannerColor.red, bannerColor.green, bannerColor.blue, 80)
                    g2.stroke = BasicStroke(JBUI.scale(1f))
                    g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                } finally {
                    g2.dispose()
                }
            }
        }
        statusBanner.isOpaque = false
        statusBanner.layout = BorderLayout()
        statusBanner.border = JBUI.Borders.empty(8, 12)

        val statusTitleLabel = JBLabel(bannerTitle).apply {
            foreground = bannerColor
            font = font.deriveFont(Font.BOLD, globalUiFontSize + 1f)
        }
        val statusDescLabel = JBLabel(bannerDesc).apply {
            foreground = textMuted
            font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
        }

        val textContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(statusTitleLabel)
            add(Box.createVerticalStrut(2))
            add(statusDescLabel)
        }
        statusBanner.add(textContainer, BorderLayout.CENTER)
        panel.add(statusBanner)
        panel.add(Box.createVerticalStrut(16))

        fun createMetricCard(title: String, count: Int, baseColor: Color, bgAlpha: Int = 18): JComponent {
            val card = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, bgAlpha)
                        g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
                        g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, 60)
                        g2.stroke = BasicStroke(JBUI.scale(1f))
                        g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                    } finally {
                        g2.dispose()
                    }
                }
            }
            card.isOpaque = false
            card.layout = BorderLayout(0, JBUI.scale(2))
            card.border = JBUI.Borders.empty(12, 10)

            val countLabel = JBLabel(count.toString(), SwingConstants.CENTER).apply {
                foreground = baseColor
                font = font.deriveFont(Font.BOLD, globalUiFontSize + 7f)
            }
            val cardTitleLabel = JBLabel(title, SwingConstants.CENTER).apply {
                foreground = textMuted
                font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
            }

            card.add(countLabel, BorderLayout.CENTER)
            card.add(cardTitleLabel, BorderLayout.SOUTH)
            return card
        }

        val severitySectionTitle = JBLabel("发现的安全与质量隐患", SwingConstants.CENTER).apply {
            foreground = textMuted
            font = font.deriveFont(Font.BOLD, globalUiFontSize)
            border = JBUI.Borders.emptyBottom(6)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        panel.add(severitySectionTitle)

        val severityGrid = JPanel(GridLayout(1, 2, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(createMetricCard("错误级问题数", overview.errorCount, accentRed))
            add(createMetricCard("警告级问题数", overview.warnCount, accentOrange))
        }
        panel.add(severityGrid)
        panel.add(Box.createVerticalStrut(16))

        val resolutionSectionTitle = JBLabel("分类处理进度状态", SwingConstants.CENTER).apply {
            foreground = textMuted
            font = font.deriveFont(Font.BOLD, globalUiFontSize)
            border = JBUI.Borders.emptyBottom(6)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        panel.add(resolutionSectionTitle)

        val resolutionGrid = JPanel(GridLayout(1, 4, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(createMetricCard("待处理", overview.unhandledCount, if (overview.unhandledCount > 0) accentRed else textMuted, if (overview.unhandledCount > 0) 18 else 10))
            add(createMetricCard("已采纳", overview.adoptedCount, accentGreen))
            add(createMetricCard("已忽略", overview.ignoredCount, textMuted, 10))
            add(createMetricCard("已误报", overview.misreportedCount, accentBlue))
        }
        panel.add(resolutionGrid)
        panel.add(Box.createVerticalStrut(16))

        val formulaPanel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(borderColor.red, borderColor.green, borderColor.blue, 30)
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                } finally {
                    g2.dispose()
                }
            }
        }
        formulaPanel.isOpaque = false
        formulaPanel.layout = BorderLayout()
        formulaPanel.border = JBUI.Borders.empty(6, 10)

        val formulaLabel = JBLabel("数据平衡关系：错误数 + 警告数 = 待处理 + 采纳 + 忽略 + 误报", SwingConstants.CENTER).apply {
            foreground = textHint
            font = font.deriveFont(Font.ITALIC, globalUiFontSize - 1.5f)
        }
        formulaPanel.add(formulaLabel, BorderLayout.CENTER)
        panel.add(formulaPanel)

        val preferredWidth = JBUI.scale(420)
        val preferredHeight = JBUI.scale(365)
        panel.preferredSize = Dimension(preferredWidth, preferredHeight)
        panel.minimumSize = panel.preferredSize
        panel.maximumSize = panel.preferredSize

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setShowBorder(false)
            .setShowShadow(true)
            .setResizable(false)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .show(RelativePoint.getSouthOf(aiReviewBadgeLabel))
    }

    private fun loadAiReviewFileIssues(detail: PrDetail, filePath: String): List<AiReviewIssue> {
        if (!isAiReviewResultAvailable(currentAiOverview)) return emptyList()
        return if (mockEnabled) {
            val mockJson = readMockJson("ai-review-detail.json")
            if (mockJson.isNullOrBlank()) emptyList() else parseAiReviewDetail(mockJson)
        } else {
            val response = apiService.fetchAiReviewDetail(detail.id, filePath)
            if (response.statusCode() !in 200..299) {
                PrManagerFileLogger.warn("Load AI file detail failed: prId=${detail.id} filePath=$filePath status=${response.statusCode()}")
                emptyList()
            } else {
                parseAiReviewDetail(response.body())
            }
        }
    }

    private fun loadDetailDiffAndCommits(detail: PrDetail) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fallbackCommits = detail.commits.sortedByDescending { it.time }
                SwingUtilities.invokeLater {
                    commitTableModel.setRows(fallbackCommits)
                    renderCommitTimeline(fallbackCommits)
                    clearDetailChangeTree("正在加载文件改动...", fallbackCommits.size)
                    updateCommitWarning(false)
                }

                val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
                if (repo == null) {
                    PrManagerFileLogger.warn("Load detail diff/commits fallback: repository not found")
                    SwingUtilities.invokeLater {
                        clearDetailChangeTree("未找到 Git 仓库", fallbackCommits.size)
                        commitTableModel.setRows(fallbackCommits)
                        renderCommitTimeline(fallbackCommits)
                        updateCommitWarning(false)
                    }
                    return@executeOnPooledThread
                }

                val sourceRef = toRemoteBranchRef(repo, detail.sourceBranch)
                val targetRef = toRemoteBranchRef(repo, detail.targetBranch)
                if (sourceRef.isBlank() || targetRef.isBlank()) {
                    PrManagerFileLogger.warn("Load detail diff/commits fallback: invalid refs sourceRef=$sourceRef targetRef=$targetRef")
                    SwingUtilities.invokeLater {
                        clearDetailChangeTree("分支引用无效", fallbackCommits.size)
                        commitTableModel.setRows(fallbackCommits)
                        renderCommitTimeline(fallbackCommits)
                        updateCommitWarning(false)
                    }
                    return@executeOnPooledThread
                }

                val prCommitsFuture = CompletableFuture.supplyAsync {
                    loadPullRequestCommits(repo, targetRef, sourceRef)
                }
                val changesFuture = CompletableFuture.supplyAsync {
                    branchService.comparePullRequest(targetRef, sourceRef)
                }
                val prCommits = prCommitsFuture.join()
                val commits = prCommits.ifEmpty { fallbackCommits }
                val changesResult = changesFuture.join()
                val changes = if (changesResult.error == null) changesResult.changes else emptyList()
                val missingHashes = if (prCommits.isEmpty()) {
                    emptySet()
                } else {
                    findMissingCommitsInCurrentBranch(repo, targetRef, sourceRef, prCommits)
                }

                PrManagerFileLogger.info(
                    "Detail diff/commits loaded: prId=${detail.id} commits=${commits.size} files=${changes.size} missing=${missingHashes.size}"
                )
                SwingUtilities.invokeLater {
                    buildChangeTree(changes)
                    commitTableModel.setRows(commits, missingHashes)
                    renderCommitTimeline(commits, missingHashes)
                    updateCommitWarning(missingHashes.isNotEmpty())
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load detail diff/commits error: prId=${detail.id}", e)
                SwingUtilities.invokeLater {
                    val fallbackCommits = detail.commits.sortedByDescending { it.time }
                    clearDetailChangeTree("文件改动加载失败", fallbackCommits.size)
                    commitTableModel.setRows(fallbackCommits)
                    renderCommitTimeline(fallbackCommits)
                    updateCommitWarning(false)
                }
            }
        }
    }

    private fun buildChangeTree(changes: List<ChangeItem>) {
        currentFileChanges = changes
        changeTree.emptyText.text = if (changes.isEmpty()) "暂无对比结果" else "未找到匹配文件"
        applyChangeTreeFilter()
    }

    private fun clearDetailChangeTree(message: String, commitCount: Int = commitTableModel.rowCount) {
        currentFileChanges = emptyList()
        currentDiffFilePath = null
        changeTree.clearSelection()
        changeTreeRoot.removeAllChildren()
        changeSummaryLabel.text = "0 个文件变更"
        changeAdditionsLabel.text = "+0 additions"
        changeDeletionsLabel.text = "-0 deletions"
        changeTree.emptyText.text = message
        changeTreeModel.reload()
        updateDetailTabCounters(fileCount = 0, commitCount = commitCount)
    }

    private fun insertChangeNode(change: ChangeItem): Boolean {
        val normalizedPath = change.filePath.trim().replace('\\', '/').trim('/')
        if (normalizedPath.isBlank()) return false
        val parts = normalizedPath.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return false

        var parent = changeTreeRoot
        parts.dropLast(1).forEach { dirName ->
            parent = findOrCreateDirectoryNode(parent, dirName)
        }
        parent.add(DefaultMutableTreeNode(change))
        return true
    }

    private fun findOrCreateDirectoryNode(parent: DefaultMutableTreeNode, dirName: String): DefaultMutableTreeNode {
        val children = parent.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as? DefaultMutableTreeNode ?: continue
            if (child.userObject is String && child.userObject == dirName) {
                return child
            }
        }
        val created = DefaultMutableTreeNode(dirName)
        parent.add(created)
        return created
    }

    private fun sortTree(node: DefaultMutableTreeNode) {
        val children = node.children().toList().filterIsInstance<DefaultMutableTreeNode>()
        if (children.isEmpty()) return

        children.forEach { node.remove(it) }
        val sorted = children.sortedWith(compareBy<DefaultMutableTreeNode> {
            if (it.userObject is ChangeItem) 1 else 0
        }.thenBy {
            when (val value = it.userObject) {
                is ChangeItem -> value.filePath.substringAfterLast('/').lowercase()
                is String -> value.lowercase()
                else -> value.toString().lowercase()
            }
        })
        sorted.forEach {
            node.add(it)
            sortTree(it)
        }
    }

    private fun compactDirectoryTree(node: DefaultMutableTreeNode) {
        val children = node.children().toList().filterIsInstance<DefaultMutableTreeNode>()
        children.forEach { compactDirectoryTree(it) }

        if (node.userObject !is String) return

        while (node.childCount == 1) {
            val onlyChild = node.getChildAt(0) as? DefaultMutableTreeNode ?: break
            val childName = onlyChild.userObject as? String ?: break
            node.userObject = "${node.userObject as String}/$childName"
            node.removeAllChildren()
            onlyChild.children().toList().filterIsInstance<DefaultMutableTreeNode>().forEach { grandChild ->
                node.add(grandChild)
            }
        }
    }

    private fun expandAllFromRoot() {
        val rootPath = TreePath(changeTreeRoot.path)
        expandTreePathRecursively(rootPath)
    }

    private fun expandTreePathRecursively(path: TreePath) {
        changeTree.expandPath(path)
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val children = node.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as? DefaultMutableTreeNode ?: continue
            expandTreePathRecursively(path.pathByAddingChild(child))
        }
    }

    private fun showChangeTreePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = resolveTreePathAtPoint(changeTree, e.x, e.y) ?: return
        changeTree.selectionPath = path
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        if (node.userObject !is String) return

        val menu = JPopupMenu()
        val expandItem = JMenuItem("展开目录")
        expandItem.addActionListener { expandTreePathRecursively(path) }
        menu.add(expandItem)
        menu.show(changeTree, e.x, e.y)
    }

    private fun issueCountByFile(filePath: String): Pair<Int, Int>? {
        val fileIssues = issueItemsByFile(filePath)
        if (fileIssues.isEmpty()) return null
        val unresolved = fileIssues.count { it.status.trim().lowercase() == "open" }
        return unresolved to fileIssues.size
    }

    private fun aiIssueCountByFile(filePath: String): Pair<Int, Int> {
        if (!isAiReviewResultAvailable(currentAiOverview)) return 0 to 0
        val normalized = normalizeFilePath(filePath)
        return aiIssueCountByFileMap.entries.firstOrNull { (path, _) ->
            val mapped = normalizeFilePath(path)
            mapped == normalized || mapped.endsWith(normalized) || normalized.endsWith(mapped)
        }?.value ?: (0 to 0)
    }

    private fun issueLineSetByFile(filePath: String): Set<Int> {
        return issueItemsByFile(filePath)
            .mapNotNull { issue ->
                val line = issue.line
                if (line <= 0) null else line - 1
            }
            .toSet()
    }

    private fun issueItemsByFile(filePath: String): List<IssueItem> {
        val prId = currentDetail?.id ?: return emptyList()
        val stats = PrIssueCache.get(prId) ?: return emptyList()
        val normalized = normalizeFilePath(filePath)
        return stats.issues.filter {
            val issuePath = normalizeFilePath(it.file)
            issuePath == normalized || issuePath.endsWith(normalized) || normalized.endsWith(issuePath)
        }
    }

    private fun normalizeFilePath(path: String): String {
        return path.trim().replace('\\', '/').removePrefix("./")
    }

    private fun changeTypeFullText(changeType: String): String {
        val normalized = changeType.trim().uppercase()
        return when {
            normalized.startsWith("A") -> "ADDED"
            normalized.startsWith("D") -> "DELETED"
            normalized.startsWith("M") -> "MODIFIED"
            normalized.startsWith("R") -> "RENAMED"
            normalized.startsWith("C") -> "COPIED"
            else -> changeType
        }
    }

    private fun changeTypeInlineCode(changeType: String): String {
        val normalized = changeType.trim().uppercase()
        return when {
            normalized.startsWith("A") -> "A"
            normalized.startsWith("D") -> "D"
            normalized.startsWith("M") -> "M"
            normalized.startsWith("R") -> "R"
            normalized.startsWith("C") -> "C"
            else -> normalized.firstOrNull()?.toString().orEmpty()
        }
    }

    private fun escapeHtml(text: String): String {
        return buildString(text.length) {
            text.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }
    }

    private fun formatFileNameWithChangeType(fileName: String, changeType: String): String {
        val code = changeTypeInlineCode(changeType)
        val escapedName = escapeHtml(fileName)
        if (code.isBlank()) return escapedName
        val suffixColor = toHex(changeTypeColor(changeType))
        return "<html>$escapedName <span style='color:$suffixColor;font-weight:bold;'>($code)</span></html>"
    }

    private fun reviewIssuePillText(unresolved: Int, total: Int): String {
        if (total <= 0) return ""
        val reviewLabelColor = if (unresolved > 0) "#D93025" else toHex(detailMutedColor())
        val reviewTotalColor = toHex(UIUtil.getLabelForeground())
        return "<html><span style='color:${toHex(detailMutedColor())};'>评审问题</span><span style='color:$reviewLabelColor;'>$unresolved</span><span style='color:$reviewTotalColor;'>/$total</span></html>"
    }

    private fun reviewIssueColumnWidth(font: Font): Int {
        val metrics = changeTree.getFontMetrics(font)
        val maxTextWidth = reviewIssueCountByFileMap.values
            .ifEmpty { listOf(0 to 0) }
            .maxOf { (unresolved, total) ->
                metrics.stringWidth("评审问题$unresolved/$total")
            }
        return maxTextWidth + JBUI.scale(24)
    }

    private fun changeTypeTooltip(change: ChangeItem): String {
        val normalized = change.changeType.trim().uppercase()
        val targetPath = normalizeFilePath(change.filePath)
        val sourcePath = change.fromFilePath?.let(::normalizeFilePath).orEmpty()
        return when {
            normalized.startsWith("R") && sourcePath.isNotBlank() -> "重命名：$sourcePath → $targetPath"
            normalized.startsWith("C") && sourcePath.isNotBlank() -> "复制：$sourcePath → $targetPath"
            normalized.startsWith("A") -> "新增文件：$targetPath"
            normalized.startsWith("D") -> "删除文件：$targetPath"
            normalized.startsWith("M") -> "修改文件：$targetPath"
            normalized.startsWith("R") -> "重命名文件：$targetPath"
            normalized.startsWith("C") -> "复制文件：$targetPath"
            else -> "${changeTypeFullText(change.changeType)}：$targetPath"
        }
    }

    private fun openDiff(change: ChangeItem) {
        val detail = currentDetail ?: return
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        val targetRef = ensureOriginBranch(target = detail.targetBranch)
        val headRef = ensureOriginBranch(target = detail.sourceBranch)
        val baseRef = repo?.let { resolveMergeBase(it, targetRef, headRef) }
            ?: detail.baseCommitSha.trim().takeIf { it.isNotBlank() }
            ?: targetRef
        PrManagerFileLogger.info("Open diff: file=${change.filePath} base=$baseRef head=$headRef")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val sourceContent = branchService.loadFileContent(headRef, change.filePath)
                val targetContent = branchService.loadFileContent(baseRef, change.filePath)
                if (sourceContent == null && targetContent == null) {
                    updateStatus("无法加载文件内容")
                    PrManagerFileLogger.warn("Open diff failed, content empty: file=${change.filePath}")
                    return@executeOnPooledThread
                }

                val aiIssues = runCatching {
                    currentDiffFilePath = change.filePath
                    loadAiReviewFileIssues(detail, change.filePath)
                }.getOrElse {
                    PrManagerFileLogger.error("Load AI issues failed before diff: file=${change.filePath}", it)
                    emptyList()
                }

                SwingUtilities.invokeLater {
                    try {
                        if (project.isDisposed) return@invokeLater
                        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(change.filePath)
                        val contentFactory = DiffContentFactory.getInstance()
                        val left = contentFactory.create(project, targetContent ?: "", fileType)
                        val right = contentFactory.create(project, sourceContent ?: "", fileType)
                        val request = SimpleDiffRequest(
                            "${change.filePath} ($baseRef..$headRef)",
                            left,
                            right,
                            baseRef,
                            headRef
                        )
                        commentManager.updateIssueLines(change.filePath, issueLineSetByFile(change.filePath))
                        commentManager.updateIssueDetails(change.filePath, issueItemsByFile(change.filePath))
                        commentManager.updateAiIssues(change.filePath, aiIssues.map { issue ->
                            LineCommentManager.AiIssue(
                                id = issue.id,
                                issueStatus = issue.issueStatus,
                                issueSeverity = issue.issueSeverity,
                                issueDescription = issue.issueDescription,
                                issueFixSuggestion = issue.issueFixSuggestion,
                                issueFixCode = issue.issueFixCode,
                                issueCodeLine = issue.issueCodeLine,
                                issueCodeSnippetStartLine = issue.issueCodeSnippetStartLine,
                                issueCodeSnippetEndLine = issue.issueCodeSnippetEndLine
                            )
                        })
                        commentManager.setAiIssueHandler { issueId, status, issueRemark, onDone ->
                            handleAiIssue(detail, change.filePath, issueId, status, issueRemark, onDone)
                        }
                        diffBinder.bindNextDiff(change.filePath)
                        DiffManager.getInstance().showDiff(project, request)
                    } catch (e: Exception) {
                        PrManagerFileLogger.error("Open diff error on UI thread: file=${change.filePath}", e)
                        updateStatus("打开Diff失败: ${e.message ?: "未知错误"}")
                    }
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Open diff error: file=${change.filePath}", e)
                updateStatus("打开Diff失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun findMissingCommitsInCurrentBranch(
        repo: git4idea.repo.GitRepository,
        targetRef: String,
        sourceRef: String,
        commits: List<CommitItem>
    ): Set<String> {
        val prCommitHashes = commits
            .map { it.hash.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (prCommitHashes.isEmpty()) return emptySet()

        val cacheKey = buildMissingCommitCacheKey(repo, targetRef, sourceRef)
        if (cacheKey != null) {
            synchronized(missingCommitCache) {
                val cacheEntry = missingCommitCache[cacheKey]
                if (cacheEntry != null) {
                    if (isPullRequestCacheExpired(cacheEntry.cachedAtMillis)) {
                        missingCommitCache.remove(cacheKey)
                    } else {
                        return cacheEntry.missingHashes
                    }
                }
            }
        }

        val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
        handler.addParameters(
            "--right-only",
            "--topo-order",
            "--pretty=format:%H",
            "HEAD...$sourceRef"
        )
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            val error = result.errorOutput.joinToString("\n").ifBlank { "计算缺失提交失败" }
            PrManagerFileLogger.warn("Load missing commits failed: source=$sourceRef error=$error")
            return prCommitHashes
        }

        val missing = result.output
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it in prCommitHashes }
            .toSet()
        if (cacheKey != null) {
            synchronized(missingCommitCache) {
                missingCommitCache[cacheKey] = TimedMissingCommitSet(missing, System.currentTimeMillis())
            }
        }
        return missing
    }

    private fun toRemoteBranchRef(repo: git4idea.repo.GitRepository, branch: String): String {
        val raw = branch.trim()
        if (raw.isBlank()) return ""
        if (raw.startsWith("refs/")) return raw
        if (raw.startsWith("origin/")) return "refs/remotes/$raw"

        if (raw.contains('/')) {
            val remoteName = raw.substringBefore('/')
            val isRemote = repo.remotes.any { it.name == remoteName }
            if (isRemote) return "refs/remotes/$raw"
        }

        return "refs/remotes/origin/$raw"
    }

    private fun resolveMergeBase(repo: git4idea.repo.GitRepository, targetBranch: String, sourceBranch: String): String? {
        val handler = GitLineHandler(project, repo.root, GitCommand.MERGE_BASE)
        handler.addParameters(targetBranch, sourceBranch)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        return result.output.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
    }

    private fun loadCommitsByRange(repo: git4idea.repo.GitRepository, range: String): List<CommitItem> {
        val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
        handler.addParameters(
            "--topo-order",
            "--numstat",
            "--date=iso",
            "--pretty=format:${commitLogMarker}%H%x09%an%x09%ad%x09%s",
            range
        )
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()
        return parseCommitLogWithStats(result.output)
    }

    private fun loadPullRequestCommits(
        repo: git4idea.repo.GitRepository,
        targetRef: String,
        sourceRef: String
    ): List<CommitItem> {
        val cacheKey = buildBranchSnapshotKey(repo, targetRef, sourceRef)
        if (cacheKey != null) {
            synchronized(pullRequestCommitCache) {
                val cacheEntry = pullRequestCommitCache[cacheKey]
                if (cacheEntry != null) {
                    if (isPullRequestCacheExpired(cacheEntry.cachedAtMillis)) {
                        pullRequestCommitCache.remove(cacheKey)
                    } else {
                        return cacheEntry.commits
                    }
                }
            }
        }

        val commits = loadPullRequestCommitsDirect(repo, targetRef, sourceRef) ?: return emptyList()
        if (cacheKey != null) {
            synchronized(pullRequestCommitCache) {
                pullRequestCommitCache[cacheKey] = TimedCommitList(commits, System.currentTimeMillis())
            }
        }
        return commits
    }

    private fun loadPullRequestCommitsDirect(
        repo: git4idea.repo.GitRepository,
        targetRef: String,
        sourceRef: String
    ): List<CommitItem>? {
        val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
        handler.addParameters(
            "--right-only",
            "--cherry-pick",
            "--topo-order",
            "--numstat",
            "--date=iso",
            "--pretty=format:${commitLogMarker}%H%x09%an%x09%ad%x09%s",
            "$targetRef...$sourceRef"
        )
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            val error = result.errorOutput.joinToString("\n").ifBlank { "加载提交记录失败" }
            PrManagerFileLogger.warn("Load PR commits failed: target=$targetRef source=$sourceRef error=$error")
            return null
        }

        return parseCommitLogWithStats(result.output)
    }

    private fun parseCommitLogWithStats(lines: List<String>): List<CommitItem> {
        if (lines.isEmpty()) return emptyList()

        val commits = mutableListOf<CommitItem>()
        var currentCommit: CommitItem? = null
        var additions = 0
        var deletions = 0

        fun flushCurrentCommit() {
            val commit = currentCommit ?: return
            commits += if (additions == 0 && deletions == 0) {
                commit
            } else {
                commit.copy(additions = additions, deletions = deletions)
            }
            currentCommit = null
            additions = 0
            deletions = 0
        }

        lines.forEach { line ->
            when {
                line.startsWith(commitLogMarker) -> {
                    flushCurrentCommit()
                    currentCommit = parseCommitLine(line.removePrefix(commitLogMarker))
                }

                line.isBlank() -> Unit

                else -> {
                    val stat = parseCommitStatLine(line) ?: return@forEach
                    additions += stat.first
                    deletions += stat.second
                }
            }
        }
        flushCurrentCommit()
        return commits.sortedByDescending { it.time }
    }

    private fun parseCommitStatLine(line: String): Pair<Int, Int>? {
        val columns = line.split('\t')
        if (columns.size < 3) return null
        val additions = columns[0].toIntOrNull() ?: 0
        val deletions = columns[1].toIntOrNull() ?: 0
        return additions to deletions
    }

    private fun buildBranchSnapshotKey(
        repo: git4idea.repo.GitRepository,
        targetRef: String,
        sourceRef: String
    ): BranchSnapshotKey? {
        val targetCommitId = resolveRefHash(repo, targetRef) ?: return null
        val sourceCommitId = resolveRefHash(repo, sourceRef) ?: return null
        return BranchSnapshotKey(targetCommitId, sourceCommitId)
    }

    private fun buildMissingCommitCacheKey(
        repo: git4idea.repo.GitRepository,
        targetRef: String,
        sourceRef: String
    ): MissingCommitCacheKey? {
        val branchSnapshot = buildBranchSnapshotKey(repo, targetRef, sourceRef) ?: return null
        val currentHeadCommitId = resolveRefHash(repo, "HEAD") ?: return null
        return MissingCommitCacheKey(branchSnapshot.targetCommitId, branchSnapshot.sourceCommitId, currentHeadCommitId)
    }

    private fun isPullRequestCacheExpired(cachedAtMillis: Long): Boolean {
        return System.currentTimeMillis() - cachedAtMillis >= pullRequestCacheTtlMillis
    }

    private fun parseCommitLine(line: String): CommitItem? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val displayTime = parts[2].replace(Regex("\\s[+-]\\d{4}$"), "")
        return CommitItem(
            hash = parts[0],
            author = parts[1],
            time = displayTime,
            message = parts.drop(3).joinToString("\t")
        )
    }

    private fun parseState(value: String?): PrState {
        val normalized = value?.trim()?.lowercase() ?: return PrState.OPEN
        return when {
            normalized in setOf("open", "opened", "opening") -> PrState.OPEN
            normalized in setOf("merged", "merge") -> PrState.MERGED
            normalized in setOf("closed", "close") -> PrState.CLOSED
            else -> PrState.OPEN
        }
    }

    private data class StatusBadge(val text: String, val color: JBColor)

    private fun statusBadge(status: String): StatusBadge {
        return when (parseState(status)) {
            PrState.OPEN -> StatusBadge("开启的", JBColor(Color(0x3B7F59), Color(0x3B7F59)))
            PrState.MERGED -> StatusBadge("已合并", JBColor(Color(0x8663B1), Color(0xAF94D1)))
            PrState.CLOSED -> StatusBadge("已关闭", JBColor(Color(0xC2675E), Color(0xD89288)))
        }
    }

    private fun conflictPillColor(): JBColor = JBColor(Color(0xD93025), Color(0xF47067))

    private fun resolvedConflictPillColor(): JBColor = JBColor(Color(0xF29900), Color(0xF6C26B))

    private fun mergeReadyPillColor(): JBColor = JBColor(Color(0x5FAF7E), Color(0x4F9C6C))

    private fun parsePrList(body: String): PrListResult {
        val root = objectMapper.readTree(body) ?: return PrListResult(0, emptyList(), 0, 0)
        val data = root.get("data")
        val listNode = if (data != null && data.isArray) data else data?.get("list")
        if (listNode == null || !listNode.isArray) {
            return PrListResult(0, emptyList(), 0, 0)
        }

        val totalSize = root.get("totalSize")?.asInt() ?: listNode.size()
        val page = root.get("page")?.asInt() ?: 1
        val totalPage = root.get("totalPage")?.asInt() ?: 0

        fun parseReviewerUsers(node: JsonNode?): List<ReviewerInfo> {
            if (node == null || !node.isArray) return emptyList()
            return node.mapNotNull { reviewerNode ->
                val username = reviewerNode.readText("username", "login", "name", "userName")
                if (username.isBlank()) return@mapNotNull null
                val approveStatus = reviewerNode.readText("approve_status", "approveStatus", "approval_status")
                ReviewerInfo(
                    id = reviewerNode.get("id")?.asLong() ?: -1L,
                    username = username,
                    name = reviewerNode.readText("name", "username", "login", "userName").ifBlank { username },
                    approveStatus = approveStatus
                )
            }
        }

        fun parseReviewerNames(node: JsonNode?): List<String> {
            if (node == null || !node.isArray) return emptyList()
            return node.map { it.readText("username", "login", "name") }.filter { it.isNotBlank() }
        }

        val list = mutableListOf<PrItem>()
        for (node in listNode) {
            val id = node.readText("id").toLongOrNull() ?: -1L
            val iid = node.readText("iid").toLongOrNull() ?: -1L
            val title = node.readText("title", "name")
            val source = node.readText("sourceBranch", "source_branch", "source")
            val target = node.readText("targetBranch", "target_branch", "target")
            val authorNode = node.get("author")
            val author = authorNode?.readText("username", "login", "name")
                ?.takeIf { it.isNotBlank() }
                ?: node.readText("author", "creator", "createdBy", "created_by")
            val statusText = node.readText("status", "state")
            val state = parseState(statusText)
            val createdAt = node.readText("createdAt", "created_at", "createTime", "createdTime")
            val tableReviewers = parseReviewerUsers(node.get("reviewers"))
            val keyReviewers = parseReviewerNames(node.get("primary_reviewers"))
            val overviewReviewers = parseReviewerNames(node.get("general_reviewers"))
            val needKeyReviewers = node.get("primary_reviewer_num")?.asInt() ?: keyReviewers.size
            val needReviewers = node.get("general_reviewer_num")?.asInt() ?: overviewReviewers.size
            val canBeMerge = node.get("can_be_merge")?.asBoolean() ?: false
            val showConflict = node.get("show_conflict")?.asBoolean() ?: false
            val hasResolvedConflictCommits = node.get("has_resolved_conflict_commits")?.asBoolean() ?: false
            list.add(
                PrItem(
                    id = id,
                    iid = iid,
                    title = title,
                    sourceBranch = source,
                    targetBranch = target,
                    author = author,
                    createdAt = createdAt,
                    state = state,
                    keyReviewers = keyReviewers,
                    reviewers = tableReviewers,
                    generalReviewers = overviewReviewers,
                    needKeyReviewers = needKeyReviewers,
                    needReviewers = needReviewers,
                    canBeMerge = canBeMerge,
                    showConflict = showConflict,
                    hasResolvedConflictCommits = hasResolvedConflictCommits
                )
            )
        }
        return PrListResult(
            total = totalSize,
            items = list,
            page = page,
            totalPage = totalPage
        )
    }

    private fun parseDetail(body: String): PrDetail {
        val root = objectMapper.readTree(body)
        val result = root.get("result") ?: root
        val data = result.get("data")
        val baseInfo = data?.get("pullRequestsBaseInfo") ?: data
        val detailInfo = data?.get("pullRequestDetailsEntity") ?: data

        val id = detailInfo?.get("id")?.asLong() ?: baseInfo?.get("id")?.asLong() ?: -1L
        val iid = detailInfo?.get("iid")?.asLong() ?: baseInfo?.get("iid")?.asLong() ?: -1L
        val projectId = detailInfo?.get("project_id")?.asLong()
            ?: detailInfo?.get("projectId")?.asLong()
            ?: baseInfo?.get("projectId")?.asLong()
            ?: baseInfo?.get("project_id")?.asLong()
        val title = detailInfo?.readText("title").orEmpty().ifBlank { baseInfo?.readText("title").orEmpty() }
        val status = detailInfo?.readText("state", "status").orEmpty().ifBlank { baseInfo?.readText("state", "status").orEmpty() }
        val sourceBranch = detailInfo?.readText("source_branch", "sourceBranch").orEmpty().ifBlank { baseInfo?.readText("sourceBranch", "source_branch").orEmpty() }
        val targetBranch = detailInfo?.readText("target_branch", "targetBranch").orEmpty().ifBlank { baseInfo?.readText("targetBranch", "target_branch").orEmpty() }
        val author = detailInfo?.get("author")?.readText("name", "username", "login", "userName").orEmpty()
            .ifBlank { baseInfo?.readText("userName").orEmpty() }
        val authorUsername = detailInfo?.get("author")?.readText("username", "login", "userName").orEmpty()
            .ifBlank { baseInfo?.readText("userName").orEmpty() }
        val createTime = detailInfo?.readText("created_at", "createdAt").orEmpty().ifBlank { baseInfo?.readText("createdAt").orEmpty() }
        val headCommitSha = baseInfo?.get("headCommitSha")?.asText() ?: ""
        val baseCommitSha = baseInfo?.get("baseCommitSha")?.asText() ?: ""
        val primaryReviewerInfos = parseDetailReviewerUsers(detailInfo?.get("primary_reviewers"))
        val generalReviewerInfos = parseDetailReviewerUsers(detailInfo?.get("general_reviewers"))

        val overview = PrOverview(
            desc = detailInfo?.readText("body", "description", "desc").orEmpty(),
            keyReviewers = primaryReviewerInfos.map { it.username },
            needKeyReviewers = detailInfo?.get("primary_reviewer_num")?.asInt() ?: 0,
            reviewers = generalReviewerInfos.map { it.username },
            needReviewers = detailInfo?.get("general_reviewer_num")?.asInt() ?: 0,
            mergedType = detailInfo?.readText("default_merge_type", "defaultMergeType").orEmpty(),
            deleteBranchAfterMerged = detailInfo?.get("prune_branch")?.asBoolean() ?: false
        )

        val commitNode = data?.get("pullRequestCommit") ?: data?.get("commits")
        val commits = commitNode?.map {
            CommitItem(
                author = it.readText("commitBy", "author", "committer"),
                hash = it.readText("commitId", "hash", "id"),
                message = it.readText("commitMsg", "message", "msg"),
                time = it.readText("commitTime", "time", "date")
            )
        } ?: emptyList()

        return PrDetail(
            id = id,
            iid = iid,
            title = title,
            status = status,
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            author = author,
            authorUsername = authorUsername,
            createTime = createTime,
            headCommitSha = headCommitSha,
            baseCommitSha = baseCommitSha,
            projectId = projectId,
            canReview = detailInfo?.get("can_review")?.asBoolean() ?: false,
            canMerge = detailInfo?.get("can_merge")?.asBoolean() ?: false,
            canClose = detailInfo?.get("can_close")?.asBoolean() ?: false,
            canEdit = detailInfo?.get("can_edit")?.asBoolean() ?: false,
            canDelete = detailInfo?.get("can_delete")?.asBoolean() ?: false,
            canBeMerge = detailInfo?.get("can_be_merge")?.asBoolean() ?: false,
            showConflict = detailInfo?.get("show_conflict")?.asBoolean()
                ?: baseInfo?.get("show_conflict")?.asBoolean()
                ?: false,
            hasResolvedConflictCommits = detailInfo?.get("has_resolved_conflict_commits")?.asBoolean()
                ?: baseInfo?.get("has_resolved_conflict_commits")?.asBoolean()
                ?: false,
            overview = overview,
            primaryReviewerInfos = primaryReviewerInfos,
            generalReviewerInfos = generalReviewerInfos,
            commits = commits
        )
    }

    private fun parseDetailReviewerUsers(node: JsonNode?): List<ReviewerInfo> {
        if (node == null || !node.isArray) return emptyList()
        return node.mapNotNull { reviewerNode ->
            val id = reviewerNode.get("id")?.asLong() ?: return@mapNotNull null
            val username = reviewerNode.readText("username", "login", "userName", "name")
            val name = reviewerNode.readText("name", "username", "login", "userName").ifBlank { username }
            val finalUsername = username.ifBlank { name }
            if (finalUsername.isBlank()) return@mapNotNull null
            ReviewerInfo(
                id = id,
                username = finalUsername,
                name = name,
                approveStatus = reviewerNode.readText("approve_status", "approveStatus", "approval_status")
            )
        }
    }

    private fun parseNoteList(body: String): NoteListResult {
        val root = objectMapper.readTree(body)
        val result = root.get("result") ?: root
        val data = result.get("data")

        val comments = mutableListOf<LineComment>()
        val issues = mutableListOf<IssueItem>()

        if (data != null && data.isArray) {
            data.forEach { entry ->
                val diff = entry.get("diff_position")
                val filePath = diff?.get("new_path")?.asText()?.trim().orEmpty()
                val newLine = diff?.get("new_line")?.asInt() ?: 0
                if (filePath.isBlank() || newLine <= 0) return@forEach
                val lineIndex = newLine - 1

                val notes = entry.get("notes")
                if (notes == null || !notes.isArray) return@forEach

                val noteList = notes.toList()

                noteList.forEach { note ->
                    val noteId = note.readText("id", "nodeId")
                    val rootId = note.readText("root_id").ifBlank { noteId }
                    val authorNode = note.get("author")
                    val author = authorNode?.readText("username", "login", "name").orEmpty()
                    val authorId = authorNode?.get("id")?.asInt()
                    val content = note.readText("note", "content", "body")
                    val createdAt = parseEpoch(note.readText("created_at", "createdAt"))
                    val floorNum = note.get("floor_num")?.asInt()
                    val replyFloorNum = note.get("reply_floor_num")?.asInt()
                    val resolved = note.get("resolved_enabled")?.asBoolean()
                        ?: note.get("resolved")?.asBoolean()
                        ?: false
                    val commentId = noteId.ifBlank { rootId.ifBlank { "${filePath}#$newLine#${createdAt}" } }

                    comments.add(
                        LineComment(
                            commentId,
                            filePath,
                            lineIndex,
                            Side.RIGHT,
                            content,
                            author,
                            createdAt,
                            null,
                            rootId.ifBlank { commentId },
                            floorNum,
                            replyFloorNum,
                            resolved,
                            authorId
                        )
                    )

                    issues.add(
                        IssueItem(
                            id = commentId,
                            number = floorNum?.toString().orEmpty(),
                            createBy = author,
                            msg = content,
                            createTime = note.readText("created_at", "createdAt"),
                            file = filePath,
                            line = newLine,
                            status = if (resolved) "fixed" else "open",
                            replies = emptyList()
                        )
                    )

                    val children = note.get("children")
                    if (children != null && children.isArray) {
                        children.forEach { child ->
                            val childId = child.readText("id", "nodeId")
                            val childRootId = child.readText("root_id").ifBlank { rootId }
                            val childAuthorNode = child.get("author")
                            val childAuthor = childAuthorNode?.readText("username", "login", "name").orEmpty()
                            val childAuthorId = childAuthorNode?.get("id")?.asInt()
                            val childContent = child.readText("note", "content", "body")
                            val childCreatedAt = parseEpoch(child.readText("created_at", "createdAt"))
                            val childFloorNum = child.get("floor_num")?.asInt()
                            val childReplyFloorNum = child.get("reply_floor_num")?.asInt()
                            val childResolved = child.get("resolved_enabled")?.asBoolean()
                                ?: child.get("resolved")?.asBoolean()
                                ?: false
                            val childCommentId = childId.ifBlank {
                                "${childRootId.ifBlank { rootId }}#${childFloorNum ?: childCreatedAt}"
                            }

                            comments.add(
                                LineComment(
                                    childCommentId,
                                    filePath,
                                    lineIndex,
                                    Side.RIGHT,
                                    childContent,
                                    childAuthor,
                                    childCreatedAt,
                                    rootId.ifBlank { commentId },
                                    childRootId.ifBlank { rootId.ifBlank { commentId } },
                                    childFloorNum,
                                    childReplyFloorNum,
                                    childResolved,
                                    childAuthorId
                                )
                            )
                        }
                    }
                }

            }
        }

        val total = issues.size
        val unresolved = issues.count { it.status.trim().lowercase() == "open" }
        return NoteListResult(IssueStats(total, unresolved, issues), comments)
    }

    private fun parseEpoch(value: String): Long {
        if (value.isBlank()) return System.currentTimeMillis()
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
    }

    private fun parseAiReviewOverview(body: String): AiReviewOverview? {
        val root = objectMapper.readTree(body)
        val result = root.get("result") ?: root
        if (result.isMissingNode || result.isNull) return null
        if (result.isArray) return null
        val payload = result.get("data")?.takeIf { it.isObject } ?: result
        val prId = payload.get("prId")?.asLong() ?: return null
        val reviewFlag = AiReviewProgressFlag.fromCode(payload.get("reviewFlag")?.asInt() ?: AiReviewProgressFlag.NOT_STARTED.code)
        val validFlag = payload.get("validFlag")?.asBoolean() == true
        val fileTree = payload.get("fileTreeNode")
        return AiReviewOverview(
            prId = prId,
            reviewFlag = reviewFlag,
            validFlag = validFlag,
            errorCount = payload.get("aiCodeReviewIssueErrorCount")?.asInt() ?: 0,
            warnCount = payload.get("aiCodeReviewIssueWarnCount")?.asInt() ?: 0,
            unhandledCount = payload.get("aiCodeReviewIssueUnhandledCount")?.asInt() ?: 0,
            adoptedCount = payload.get("aiCodeReviewIssueAdoptedCount")?.asInt() ?: 0,
            ignoredCount = payload.get("aiCodeReviewIssueIgnoredCount")?.asInt() ?: 0,
            misreportedCount = payload.get("aiCodeReviewIssueMisreportedCount")?.asInt() ?: 0,
            fileTreeNodes = parseAiTreeNodes(fileTree)
        )
    }

    private fun parseAiTreeNodes(node: JsonNode?): List<AiTreeNode> {
        if (node == null || !node.isArray) return emptyList()
        return node.mapNotNull { item ->
            val nodeName = item.readText("nodeName")
            if (nodeName.isBlank()) return@mapNotNull null
            AiTreeNode(
                nodeName = nodeName,
                issueErrorCount = item.get("issueErrorCount")?.asInt() ?: 0,
                issueWarnCount = item.get("issueWarnCount")?.asInt() ?: 0,
                type = item.readText("type"),
                children = parseAiTreeNodes(item.get("children"))
            )
        }
    }

    private fun flattenAiTreeIssueCount(nodes: List<AiTreeNode>): Map<String, Pair<Int, Int>> {
        val result = mutableMapOf<String, Pair<Int, Int>>()
        fun walk(node: AiTreeNode, parentPath: String) {
            val fullPath = normalizeFilePathKey(
                listOf(parentPath, node.nodeName)
                    .filter { it.isNotBlank() }
                    .joinToString("/")
                    .replace("//", "/")
                    .trim('/')
            )
            val isFolder = node.type.equals("FOLDER", true) || node.type.equals("FOLDERS", true)
            if (!isFolder) {
                result[fullPath] = node.issueErrorCount to node.issueWarnCount
            }
            node.children.forEach { walk(it, fullPath) }
        }
        nodes.forEach { walk(it, "") }
        return result
    }

    private fun parseAiReviewDetail(body: String): List<AiReviewIssue> {
        val root = objectMapper.readTree(body)
        val result = root.get("result") ?: root
        if (result == null || !result.isArray) return emptyList()
        return result.mapNotNull { item ->
            val id = item.get("id")?.asLong() ?: return@mapNotNull null
            val issueLine = item.get("issueCodeLine")?.asInt() ?: 0
            if (issueLine <= 0) return@mapNotNull null
            AiReviewIssue(
                id = id,
                filePath = item.readText("filePath"),
                issueStatus = mockAiIssueStatusOverrides[id] ?: (item.get("issueStatus")?.asInt() ?: 0),
                issueSeverity = item.get("issueSeverity")?.asInt() ?: 0,
                issueDescription = item.readText("issueDescription"),
                issueFixSuggestion = item.readText("issueFixSuggestion"),
                issueFixCode = item.readText("issueFixCode"),
                issueCodeLine = issueLine,
                issueCodeSnippetStartLine = item.get("issueCodeSnippetStartLine")?.asInt() ?: issueLine,
                issueCodeSnippetEndLine = item.get("issueCodeSnippetEndLine")?.asInt() ?: issueLine
            )
        }
    }

    private fun handleAiIssue(detail: PrDetail, filePath: String, issueId: Long, issueStatus: Int, issueRemark: String?, onDone: (Boolean) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            var handled = true
            try {
                if (mockEnabled) {
                    mockAiIssueStatusOverrides[issueId] = issueStatus
                    updateStatus("Mock模式：仅刷新AI评审数据")
                } else {
                    val currentUser = System.getenv(pluginAuthorUsernameEnv).orEmpty().trim()
                    if (currentUser.isBlank()) {
                        handled = false
                        updateStatus("处理失败: 未获取到当前用户")
                    } else {
                        val response = apiService.handleAiReviewIssue(issueId, issueStatus, currentUser, issueRemark)
                        if (response.statusCode() !in 200..299) {
                            handled = false
                            updateStatus("处理失败: ${response.statusCode()}")
                        }
                    }
                }
            } catch (e: Exception) {
                handled = false
                PrManagerFileLogger.error("Handle AI issue failed: issueId=$issueId status=$issueStatus", e)
                updateStatus("处理失败: ${e.message ?: "未知错误"}")
            }

            loadAiReviewOverview(detail)

            val refreshed = try {
                if (mockEnabled) {
                    val mockJson = readMockJson("ai-review-detail.json")
                    if (mockJson.isNullOrBlank()) emptyList() else parseAiReviewDetail(mockJson)
                } else {
                    val detailResponse = apiService.fetchAiReviewDetail(detail.id, filePath)
                    if (detailResponse.statusCode() !in 200..299) {
                        PrManagerFileLogger.warn("Refresh AI file detail failed: prId=${detail.id} filePath=$filePath status=${detailResponse.statusCode()}")
                        emptyList()
                    } else {
                        parseAiReviewDetail(detailResponse.body())
                    }
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Refresh AI file detail error: prId=${detail.id} filePath=$filePath", e)
                emptyList()
            }

            SwingUtilities.invokeLater {
                commentManager.updateAiIssues(filePath, refreshed.map { issue ->
                    LineCommentManager.AiIssue(
                        id = issue.id,
                        issueStatus = issue.issueStatus,
                        issueSeverity = issue.issueSeverity,
                        issueDescription = issue.issueDescription,
                        issueFixSuggestion = issue.issueFixSuggestion,
                        issueFixCode = issue.issueFixCode,
                        issueCodeLine = issue.issueCodeLine,
                        issueCodeSnippetStartLine = issue.issueCodeSnippetStartLine,
                        issueCodeSnippetEndLine = issue.issueCodeSnippetEndLine
                    )
                })
                onDone(handled)
            }
        }
    }

    private fun Boolean?.orFalse(): Boolean = this == true

    private fun statusColor(status: String): JBColor {
        return when (status.trim().lowercase()) {
            "open" -> JBColor(Color(0x4B8CCB), Color(0x7FAFDA))
            "merged" -> JBColor(Color(0x2F8F57), Color(0x6EB88A))
            "closed" -> JBColor(Color(0xC2675E), Color(0xD89288))
            else -> JBColor.GRAY
        }
    }

    private fun readUserList(node: JsonNode?): List<String> {
        if (node == null || node.isNull) return emptyList()
        if (node.isArray) {
            return node.mapNotNull { item ->
                val value = when {
                    item.isTextual -> item.asText()
                    item.isObject -> item.readText("username", "login", "name", "userName")
                    else -> item.asText()
                }
                value.takeIf { it.isNotBlank() }
            }
        }
        val value = when {
            node.isTextual -> node.asText()
            node.isObject -> node.readText("username", "login", "name", "userName")
            else -> node.asText()
        }
        return if (value.isBlank()) emptyList() else listOf(value)
    }

    private fun JsonNode.readText(vararg keys: String): String {
        for (key in keys) {
            val node = get(key)
            if (node != null && !node.isNull) {
                return node.asText()
            }
        }
        return ""
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val selection = java.awt.datatransfer.StringSelection(text)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }

    private fun updateStatus(text: String) {
        SwingUtilities.invokeLater { statusLabel.text = text }
    }

    private fun updateFilterButtonStyles() {
        val darkTheme = UIUtil.isUnderDarcula()
        val selectedBackground = if (darkTheme) withAlpha(detailAccentColor, 92) else Color.WHITE
        val normalBackground = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))
        val selectedForeground = if (darkTheme) Color.WHITE else Color(0x111827)
        val normalForeground = JBColor(Color(0x6B7280), Color(0xD1D5DB))
        (statusFilterButtons + roleFilterButtons).forEach { button ->
            button.background = if (button.isSelected) selectedBackground else normalBackground
            button.foreground = if (button.isSelected) selectedForeground else normalForeground
            val style = if (button.isSelected) Font.BOLD else Font.PLAIN
            button.font = button.font.deriveFont(style, globalUiFontSize)
            updateListFilterButtonSize(button)
            button.repaint()
        }
    }

    private fun updateLoadMoreState(loading: Boolean, hasMore: Boolean) {
        SwingUtilities.invokeLater {
            when {
                loading -> {
                    loadMoreLabel.text = "加载中..."
                    loadMoreLabel.isVisible = true
                }
                else -> {
                    loadMoreLabel.text = ""
                    loadMoreLabel.isVisible = false
                }
            }
        }
    }

    private inner class ChangeTreeCellRenderer : javax.swing.tree.TreeCellRenderer {
        private val fallbackRenderer = DefaultTreeCellRenderer()
        private val statsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
        }
        private val rowPanel = object : JPanel(BorderLayout(JBUI.scale(10), 0)) {
            override fun paintComponent(g: Graphics) {
                val outlineColor = getClientProperty("outlineColor") as? Color
                if (background.alpha > 0 || outlineColor != null) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        val shape = RoundRectangle2D.Float(0.5f, 0.5f, (width - 1f).coerceAtLeast(0f), (height - 1f).coerceAtLeast(0f), JBUI.scale(10).toFloat(), JBUI.scale(10).toFloat())
                        if (background.alpha > 0) {
                            g2.color = background
                            g2.fill(shape)
                        }
                        if (outlineColor != null) {
                            g2.color = outlineColor
                            g2.draw(shape)
                        }
                    } finally {
                        g2.dispose()
                    }
                }
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 4, 6)
        }
        private val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
        }
        private val typeGap = Box.createHorizontalStrut(JBUI.scale(12))
        private val reviewGap = Box.createHorizontalStrut(JBUI.scale(16))
        private val aiGap = Box.createHorizontalStrut(JBUI.scale(16))
        private val mainLabel = javax.swing.JLabel()
        private val changeTypeLabel = javax.swing.JLabel()
        private val reviewIssueLabel = OutlinedPillLabel(JBUI.scale(20))
        private val aiIssueLabel = OutlinedPillLabel(JBUI.scale(20))
        private val additionLabel = javax.swing.JLabel()
        private val deletionLabel = javax.swing.JLabel()

        init {
            infoPanel.add(mainLabel)
            infoPanel.add(typeGap)
            infoPanel.add(changeTypeLabel)
            infoPanel.add(reviewGap)
            infoPanel.add(reviewIssueLabel)
            infoPanel.add(aiGap)
            infoPanel.add(aiIssueLabel)
            rowPanel.add(infoPanel, BorderLayout.CENTER)
            statsPanel.add(additionLabel)
            statsPanel.add(deletionLabel)
            rowPanel.add(statsPanel, BorderLayout.EAST)
        }

        fun tooltipAt(tree: javax.swing.JTree, path: TreePath?, mouseX: Int, mouseY: Int): String? {
            if (path == null) return null
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
            val row = tree.getRowForPath(path)
            if (row < 0) return null
            val component = getTreeCellRendererComponent(tree, node, false, tree.isExpanded(path), node.isLeaf, row, false) as? JComponent ?: return null
            val bounds = tree.getPathBounds(path) ?: return null
            val visibleRect = tree.visibleRect
            val rendererWidth = (visibleRect.x + visibleRect.width - bounds.x).coerceAtLeast(bounds.width)
            component.setBounds(0, 0, rendererWidth, bounds.height)
            component.doLayout()
            val point = Point(mouseX - bounds.x, mouseY - bounds.y)

            fun tooltipFor(target: Component, tooltip: String?, extraX: Int = 0, extraY: Int = JBUI.scale(1)): String? {
                if (!target.isVisible || tooltip.isNullOrBlank()) return null
                val parent = target.parent ?: return null
                val rect = SwingUtilities.convertRectangle(parent, target.bounds, component).apply { grow(extraX, extraY) }
                return tooltip.takeIf { rect.contains(point) }
            }

            val nameTooltip = tooltipFor(mainLabel, mainLabel.toolTipText, JBUI.scale(4), JBUI.scale(2))
            if (nameTooltip != null) return nameTooltip

            val reviewTooltip = tooltipFor(reviewIssueLabel, reviewIssueLabel.toolTipText, JBUI.scale(4), JBUI.scale(2))
            if (reviewTooltip != null) return reviewTooltip

            return tooltipFor(aiIssueLabel, aiIssueLabel.toolTipText, JBUI.scale(4), JBUI.scale(2))
        }

        override fun getTreeCellRendererComponent(
            tree: javax.swing.JTree?,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            val base = fallbackRenderer.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode
            val userObject = node?.userObject
            if (userObject !is ChangeItem && userObject !is String) {
                if (base is javax.swing.JComponent) {
                    base.font = base.font.deriveFont(Font.PLAIN, globalUiFontSize)
                }
                return base
            }

            rowPanel.background = if (sel) withAlpha(detailAccentColor, 22) else Color(0, 0, 0, 0)
            rowPanel.putClientProperty(
                "outlineColor",
                if (sel) withAlpha(detailAccentColor, 92) else null
            )

            val font = fallbackRenderer.font.deriveFont(Font.PLAIN, globalUiFontSize)
            val metaFont = font.deriveFont(Font.PLAIN, (globalUiFontSize - 1f).coerceAtLeast(11f))
            val statFont = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            mainLabel.font = font
            mainLabel.foreground = detailPrimaryTextColor()
            mainLabel.verticalAlignment = SwingConstants.CENTER
            changeTypeLabel.font = metaFont
            changeTypeLabel.verticalAlignment = SwingConstants.CENTER
            reviewIssueLabel.font = metaFont
            reviewIssueLabel.verticalAlignment = SwingConstants.CENTER
            reviewIssueLabel.horizontalAlignment = SwingConstants.LEFT
            reviewIssueLabel.border = JBUI.Borders.empty(2, 8, 3, 8)
            aiIssueLabel.font = metaFont
            aiIssueLabel.verticalAlignment = SwingConstants.CENTER
            aiIssueLabel.horizontalAlignment = SwingConstants.LEFT
            aiIssueLabel.border = JBUI.Borders.empty(2, 8, 3, 8)
            additionLabel.font = statFont
            deletionLabel.font = statFont
            additionLabel.verticalAlignment = SwingConstants.CENTER
            deletionLabel.verticalAlignment = SwingConstants.CENTER
            additionLabel.foreground = JBColor(Color(0x1E8E3E), Color(0x57D163))
            deletionLabel.foreground = JBColor(Color(0xD93025), Color(0xF47067))

            if (userObject is String) {
                mainLabel.icon = AllIcons.Nodes.Folder
                mainLabel.iconTextGap = JBUI.scale(8)
                mainLabel.text = userObject
                mainLabel.toolTipText = null
                changeTypeLabel.text = ""
                changeTypeLabel.toolTipText = null
                changeTypeLabel.isVisible = false
                reviewIssueLabel.setPill("", detailMutedColor())
                reviewIssueLabel.toolTipText = null
                aiIssueLabel.setPill("", detailMutedColor())
                aiIssueLabel.toolTipText = null
                typeGap.isVisible = false
                reviewGap.isVisible = false
                aiGap.isVisible = false
                additionLabel.text = ""
                deletionLabel.text = ""
                additionLabel.isVisible = false
                deletionLabel.isVisible = false
                statsPanel.isVisible = false
                return rowPanel
            }

            val change = userObject as ChangeItem
            val normalizedFilePath = normalizeFilePathKey(change.filePath)
            val fileName = change.filePath.substringAfterLast('/')
            val reviewStats = reviewIssueCountByFileMap[normalizedFilePath]
            val unresolved = reviewStats?.first ?: 0
            val total = reviewStats?.second ?: 0
            val aiStats = aiIssueCountByFile(change.filePath)
            val showAiStats = aiStats.first > 0 || aiStats.second > 0
            val reserveReviewSpace = total > 0 || showAiStats
            val reviewPillColor = if (unresolved > 0) JBColor(Color(0xD93025), Color(0xF47067)) else detailMutedColor()
            reviewIssueLabel.setPill(reviewIssuePillText(unresolved, total), reviewPillColor)
            if (reserveReviewSpace) {
                val reviewSize = Dimension(reviewIssueColumnWidth(metaFont), reviewIssueLabel.preferredSize.height)
                reviewIssueLabel.preferredSize = reviewSize
                reviewIssueLabel.minimumSize = reviewSize
                reviewIssueLabel.maximumSize = reviewSize
                reviewIssueLabel.isVisible = true
            }
            reviewIssueLabel.toolTipText = if (total > 0) "评审未解决问题/总问题=$unresolved/$total" else null

            val aiErrorColor = toHex(JBColor(Color(0xD93025), Color(0xF47067)))
            val aiWarnColor = toHex(JBColor(Color(0xF29900), Color(0xF6C26B)))
            val aiSlashColor = toHex(detailMutedColor())
            val aiPillColor = JBColor(Color(0xF6C26B), Color(0xE0A458))
            aiIssueLabel.setPill(
                if (showAiStats) {
                    "<html><span style='color:${toHex(detailMutedColor())};'>AI评审问题</span><span style='color:$aiErrorColor;'>${aiStats.first}</span><span style='color:$aiSlashColor;'>/</span><span style='color:$aiWarnColor;'>${aiStats.second}</span></html>"
                } else {
                    ""
                },
                aiPillColor
            )
            aiIssueLabel.toolTipText = if (showAiStats) "AI错误问题数/警告问题数 = ${aiStats.first}/${aiStats.second}" else null

            changeTypeLabel.text = ""
            changeTypeLabel.toolTipText = null
            changeTypeLabel.isVisible = false
            typeGap.isVisible = false
            reviewGap.isVisible = reserveReviewSpace
            aiGap.isVisible = aiIssueLabel.isVisible

            mainLabel.icon = changeTypeIcon(change.changeType)
            mainLabel.iconTextGap = JBUI.scale(8)
            mainLabel.toolTipText = changeTypeTooltip(change)
            additionLabel.text = if (change.additions > 0) "+${change.additions}" else ""
            deletionLabel.text = if (change.deletions > 0) "-${change.deletions}" else ""
            additionLabel.isVisible = additionLabel.text.isNotBlank()
            deletionLabel.isVisible = deletionLabel.text.isNotBlank()
            statsPanel.isVisible = additionLabel.isVisible || deletionLabel.isVisible
            mainLabel.text = formatFileNameWithChangeType(fileName, change.changeType)
            return rowPanel
        }
    }

    private fun changeTypeIcon(changeType: String) = when {
        changeType.startsWith("A") -> AllIcons.General.Add
        changeType.startsWith("D") -> AllIcons.General.Remove
        changeType.startsWith("M") -> AllIcons.Actions.Edit
        changeType.startsWith("R") -> AllIcons.Actions.Refresh
        changeType.startsWith("C") -> AllIcons.Actions.Copy
        else -> AllIcons.General.Information
    }

    private fun changeTypeColor(changeType: String) = when {
        changeType.startsWith("A") -> JBColor(Color(0x1E8E3E), Color(0x57D163))
        changeType.startsWith("D") -> JBColor(Color(0xD93025), Color(0xF47067))
        changeType.startsWith("M") -> JBColor(Color(0x1A73E8), Color(0x6EA8FF))
        changeType.startsWith("R") -> JBColor(Color(0xF29900), Color(0xF6C26B))
        changeType.startsWith("C") -> JBColor(Color(0x8E24AA), Color(0xC77DFF))
        else -> JBColor(Color(0x5F6368), Color(0x9AA0A6))
    }

    private fun reviewerStatusColor(status: String): JBColor = when (status.trim().lowercase()) {
        "approved" -> JBColor(Color(0x3B7F59), Color(0x3B7F59))
        "commented" -> JBColor(Color(0xF29900), Color(0xF6C26B))
        "rejected" -> JBColor(Color(0xD93025), Color(0xF47067))
        else -> JBColor(Color(0x1A73E8), Color(0x6EA8FF))
    }

    private inner class ReviewerCellRenderer : TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: javax.swing.JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val iconSize = JBUI.scale(16)
            val gap = JBUI.scale(4)
            val panel = JPanel(FlowLayout(FlowLayout.LEFT, gap, 0))
            panel.isOpaque = true
            panel.background = if (isSelected) table.selectionBackground else table.background
            val extra = (table.rowHeight - iconSize).coerceAtLeast(0)
            panel.border = JBUI.Borders.empty(extra / 2, 0, extra - extra / 2, 0)

            val reviewers = (value as? List<*>)?.filterIsInstance<ReviewerInfo>().orEmpty()
            if (reviewers.isEmpty()) {
                val empty = JBLabel("-")
                empty.foreground = if (isSelected) table.selectionForeground else JBColor.GRAY
                panel.add(empty)
                return panel
            }

            reviewers.forEach { reviewer ->
                val color = reviewerStatusColor(reviewer.approveStatus)
                val avatar = JBLabel(ReviewerAvatarIcon(reviewer.username, color))
                val statusText = reviewer.approveStatus.ifBlank { "unknown" }
                avatar.toolTipText = "${reviewer.username} (${statusText})"
                panel.add(avatar)
            }
            return panel
        }
    }

    private inner class PrCardPanel(private val item: PrItem) : JPanel() {
        private val titleLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD, globalUiFontSize + 3f)
            foreground = JBColor(Color(0x111827), Color(0xF9FAFB))
        }
        private val stateBadge = StatusBadgeLabel().apply {
            font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
        }
        private val authorColor = listAuthorColor(item.author.ifBlank { "?" })
        private val authorPill = buildListPill(
            item.author.ifBlank { "未知作者" },
            authorColor,
            ReviewerAvatarIcon(item.author.ifBlank { "?" }, authorColor)
        )
        private val branchPill = buildListPill(
            "${item.sourceBranch} → ${item.targetBranch}",
            detailBranchPillColor
        )
        private val timePill = buildListPill(
            formatPrListTime(item.createdAt),
            detailCreateTimePillColor,
            ClockMetaIcon(detailCreateTimePillColor)
        ).apply {
            isVisible = text.isNotBlank()
        }
        private val conflictPill = buildListPill("存在冲突", conflictPillColor()).apply {
            toolTipText = "当前 PR 存在冲突"
            isVisible = item.showConflict
        }
        private val resolvedConflictPill = buildListPill("源分支解决过冲突", resolvedConflictPillColor()).apply {
            toolTipText = "源分支存在解决过冲突的提交"
            isVisible = item.hasResolvedConflictCommits
        }
        private val mergeReadyPill = buildListPill("合并条件就绪", mergeReadyPillColor()).apply {
            toolTipText = "当前 PR 合并条件就绪"
            isVisible = item.canBeMerge
        }
        private val reviewPill = buildListPill("", detailIssuePillColor).apply { isVisible = false }
        private val aiPill = buildListPill("", AiReviewBadgeState.NO_DATA.color).apply { isVisible = false }
        private var hovered = false
        private var selectedState = false
        private var isLastCard = false

        init {
            isOpaque = false
            layout = BorderLayout()
            border = JBUI.Borders.empty(0, 8, 0, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            val headerRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                titleLabel.text = "#${if (item.iid > 0) item.iid else item.id} ${item.title}"
                val badge = statusBadge(item.state.name.lowercase())
                stateBadge.setBadge(badge.text, badge.color)
                add(titleLabel)
                add(Box.createHorizontalStrut(JBUI.scale(12)))
                add(stateBadge)
                if (conflictPill.isVisible) {
                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                    add(conflictPill)
                }
                if (resolvedConflictPill.isVisible) {
                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                    add(resolvedConflictPill)
                }
                if (mergeReadyPill.isVisible) {
                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                    add(mergeReadyPill)
                }
                add(Box.createHorizontalGlue())
            }

            val metaRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(8))).apply {
                isOpaque = false
                add(authorPill)
                add(branchPill)
                if (timePill.isVisible) add(timePill)
                add(reviewPill)
                add(aiPill)
            }

            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(16, 12, 16, 12)
                add(headerRow)
                add(Box.createVerticalStrut(JBUI.scale(10)))
                add(metaRow)
            }, BorderLayout.CENTER)

            val listener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    val pointer = MouseInfo.getPointerInfo()?.location ?: run {
                        hovered = false
                        repaint()
                        return
                    }
                    SwingUtilities.convertPointFromScreen(pointer, this@PrCardPanel)
                    if (!this@PrCardPanel.contains(pointer)) {
                        hovered = false
                        repaint()
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    dismissSearchFieldFocus()
                    if (!confirmCloseCreatePrViewIfNeeded()) return
                    selectPrCard(item.id)
                    showDetail(item.id)
                }
            }
            bindCardMouseListenerRecursively(this, listener)
            bindCardMouseWheelRecursively(this)
        }

        fun setLastCard(value: Boolean) {
            isLastCard = value
            repaint()
        }

        fun setSelectedState(value: Boolean) {
            selectedState = value
            repaint()
        }

        fun applySupplement(supplement: PrListSupplement) {
            supplement.reviewStats?.let { stats ->
                val reviewColor = when {
                    stats.total <= 0 -> JBColor(Color(0x5F6368), Color(0x9AA0A6))
                    stats.unresolved > 0 -> detailIssuePillColor
                    else -> JBColor(Color(0x1E8E3E), Color(0x57D163))
                }
                reviewPill.toolTipText = "评审未解决问题/总问题=${stats.unresolved}/${stats.total}"
                reviewPill.setPill("评审问题 ${stats.unresolved}/${stats.total}", reviewColor)
                reviewPill.isVisible = true
            }
            aiPill.toolTipText = when (supplement.aiState) {
                AiReviewBadgeState.NO_DATA -> "当前未发起AI评审"
                AiReviewBadgeState.IN_PROGRESS -> "AI评审进行中"
                AiReviewBadgeState.STALE -> "AI评审结果已过期"
                else -> "查看AI评审总览"
            }
            aiPill.setPill(aiReviewBadgeText(supplement.aiState), supplement.aiState.color)
            aiPill.isVisible = true
            revalidate()
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val insetX = JBUI.scale(2)
                val insetY = JBUI.scale(2)
                val width = (width - insetX * 2).coerceAtLeast(0)
                val height = (height - insetY * 2).coerceAtLeast(0)
                val arc = JBUI.scale(12)
                if (hovered || selectedState) {
                    g2.color = withAlpha(Color.BLACK, if (selectedState) 18 else 12)
                    g2.fillRoundRect(insetX, insetY + JBUI.scale(2), width, height - JBUI.scale(2), arc, arc)
                    g2.color = JBColor(Color.WHITE, Color(0x2B2D30))
                    g2.fillRoundRect(insetX, insetY, width, height - JBUI.scale(2), arc, arc)
                    if (selectedState) {
                        g2.color = withAlpha(detailAccentColor, 90)
                        g2.drawRoundRect(insetX, insetY, width - 1, height - JBUI.scale(2) - 1, arc, arc)
                    }
                }
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
            if (!hovered && !selectedState && !isLastCard) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.color = JBColor(Color(0xE5E7EB), Color(0x6B7280))
                    val y = height - 1
                    g2.drawLine(JBUI.scale(8), y, width - JBUI.scale(8), y)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    private fun bindCardMouseListenerRecursively(component: Component, listener: MouseAdapter) {
        component.addMouseListener(listener)
        if (component is Container) {
            component.components.forEach { child -> bindCardMouseListenerRecursively(child, listener) }
        }
    }

    private fun bindCardMouseWheelRecursively(component: Component) {
        component.addMouseWheelListener { event -> scrollPrListByWheel(event) }
        if (component is Container) {
            component.components.forEach { child -> bindCardMouseWheelRecursively(child) }
        }
    }

    private fun bindCommitTimelineMouseWheelRecursively(component: Component) {
        component.addMouseWheelListener { event -> scrollCommitTimelineByWheel(event) }
        if (component is Container) {
            component.components.forEach { child -> bindCommitTimelineMouseWheelRecursively(child) }
        }
    }

    private class CircleWarningIcon : Icon {
        private val size = JBUI.scale(14)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(Color(0xF59E0B), Color(0xF6A623))
                g2.fillOval(x, y, size, size)

                g2.color = JBColor(Color(0xD97706), Color(0xE08E2E))
                g2.stroke = BasicStroke(JBUI.scale(1f))
                g2.drawOval(x, y, size, size)

                g2.color = Color.WHITE
                val barWidth = (size * 0.22f).coerceAtLeast(2f)
                val barHeight = (size * 0.48f).coerceAtLeast(5f)
                val barX = x + (size - barWidth) / 2f
                val barY = y + size * 0.22f
                g2.fillRoundRect(barX.toInt(), barY.toInt(), barWidth.toInt(), barHeight.toInt(), JBUI.scale(2), JBUI.scale(2))

                val dotSize = (size * 0.16f).coerceAtLeast(2f)
                val dotX = x + (size - dotSize) / 2f
                val dotY = y + size * 0.74f
                g2.fillOval(dotX.toInt(), dotY.toInt(), dotSize.toInt(), dotSize.toInt())
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size
    }

    private class AiBadgeIcon(private val color: Color) : Icon {
        private val size = JBUI.scale(16)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val inset = JBUI.scale(0.5f)
                val circle = java.awt.geom.Ellipse2D.Float(
                    x + inset,
                    y + inset,
                    size - inset * 2,
                    size - inset * 2
                )
                g2.color = color
                g2.fill(circle)
                g2.color = color.darker()
                g2.stroke = BasicStroke(JBUI.scale(1f))
                g2.draw(circle)

                val text = "AI"
                val font = g2.font.deriveFont(Font.BOLD, JBUI.scale(8f))
                g2.font = font
                val fm = g2.fontMetrics
                val tx = x + (size - fm.stringWidth(text)) / 2
                val ty = y + (size + fm.ascent - fm.descent) / 2
                g2.color = Color.WHITE
                g2.drawString(text, tx, ty)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size
    }

    private class BranchMetaIcon(private val color: Color) : Icon {
        private val size = JBUI.scale(14)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.stroke = BasicStroke(JBUI.scale(1.2f))
                val startX = x + JBUI.scale(2)
                val centerY = y + size / 2
                g2.drawLine(startX, centerY, x + size - JBUI.scale(4), centerY)
                g2.drawLine(x + size - JBUI.scale(6), centerY - JBUI.scale(3), x + size - JBUI.scale(2), centerY)
                g2.drawLine(x + size - JBUI.scale(6), centerY + JBUI.scale(3), x + size - JBUI.scale(2), centerY)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size
    }

    private class ClockMetaIcon(private val color: Color) : Icon {
        private val size = JBUI.scale(14)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.stroke = BasicStroke(JBUI.scale(1.2f))
                g2.drawOval(x + 1, y + 1, size - 3, size - 3)
                val centerX = x + size / 2
                val centerY = y + size / 2
                g2.drawLine(centerX, centerY, centerX, y + JBUI.scale(4))
                g2.drawLine(centerX, centerY, x + size - JBUI.scale(4), centerY + JBUI.scale(2))
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size
    }

    private class ReviewerAvatarIcon(
        private val username: String,
        private val color: Color
    ) : Icon {
        private val size = JBUI.scale(16)

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

    private enum class AiReviewProgressFlag(val code: Int) {
        NOT_STARTED(0),
        IN_PROGRESS(1),
        COMPLETED(2);

        companion object {
            fun fromCode(code: Int): AiReviewProgressFlag = values().firstOrNull { it.code == code } ?: COMPLETED
        }
    }

    private enum class AiReviewBadgeState(val color: Color) {
        NO_DATA(JBColor(Color(0x9AA0A6), Color(0x6B7280))),
        IN_PROGRESS(JBColor(Color(0x1A73E8), Color(0x6CB6FF))),
        STALE(JBColor(Color(0xF29900), Color(0xF6C26B))),
        PASS(JBColor(Color(0x1E8E3E), Color(0x57D163))),
        FAIL(JBColor(Color(0xD93025), Color(0xF47067)))
    }

    private companion object {
        private const val AI_REVIEW_POLL_INTERVAL_MILLIS = 30_000L
    }

    private data class AiReviewOverview(
        val prId: Long,
        val reviewFlag: AiReviewProgressFlag,
        val validFlag: Boolean,
        val errorCount: Int,
        val warnCount: Int,
        val unhandledCount: Int,
        val adoptedCount: Int,
        val ignoredCount: Int,
        val misreportedCount: Int,
        val fileTreeNodes: List<AiTreeNode>
    )

    private data class AiTreeNode(
        val nodeName: String,
        val issueErrorCount: Int,
        val issueWarnCount: Int,
        val type: String,
        val children: List<AiTreeNode>
    )

    private data class AiReviewIssue(
        val id: Long,
        val filePath: String,
        val issueStatus: Int,
        val issueSeverity: Int,
        val issueDescription: String,
        val issueFixSuggestion: String,
        val issueFixCode: String,
        val issueCodeLine: Int,
        val issueCodeSnippetStartLine: Int,
        val issueCodeSnippetEndLine: Int
    )

    private data class PrListResult(
        val total: Int,
        val items: List<PrItem>,
        val page: Int,
        val totalPage: Int
    )

    private data class ReviewerInfo(
        val id: Long,
        val username: String,
        val name: String,
        val approveStatus: String
    )

    private data class PrItem(
        val id: Long,
        val iid: Long,
        val title: String,
        val sourceBranch: String,
        val targetBranch: String,
        val author: String,
        val createdAt: String,
        val state: PrState,
        val keyReviewers: List<String>,
        val reviewers: List<ReviewerInfo>,
        val generalReviewers: List<String>,
        val needKeyReviewers: Int,
        val needReviewers: Int,
        val canBeMerge: Boolean,
        val showConflict: Boolean,
        val hasResolvedConflictCommits: Boolean
    )

    private data class PrDetail(
        val id: Long,
        val iid: Long,
        val title: String,
        val status: String,
        val sourceBranch: String,
        val targetBranch: String,
        val author: String,
        val authorUsername: String,
        val createTime: String,
        val headCommitSha: String,
        val baseCommitSha: String,
        val projectId: Long?,
        val canReview: Boolean,
        val canMerge: Boolean,
        val canClose: Boolean,
        val canEdit: Boolean,
        val canDelete: Boolean,
        val canBeMerge: Boolean,
        val showConflict: Boolean,
        val hasResolvedConflictCommits: Boolean,
        val overview: PrOverview,
        val primaryReviewerInfos: List<ReviewerInfo>,
        val generalReviewerInfos: List<ReviewerInfo>,
        val commits: List<CommitItem>
    )

    private enum class InlinePrMode {
        CREATE,
        EDIT
    }

    private data class NoteListResult(
        val stats: IssueStats,
        val comments: List<LineComment>
    )

    private data class PrOverview(
        val desc: String,
        val keyReviewers: List<String>,
        val needKeyReviewers: Int,
        val reviewers: List<String>,
        val needReviewers: Int,
        val mergedType: String,
        val deleteBranchAfterMerged: Boolean
    )

    private data class CommitItem(
        val author: String,
        val hash: String,
        val message: String,
        val time: String,
        val additions: Int = 0,
        val deletions: Int = 0
    )

    private data class BranchSnapshotKey(
        val targetCommitId: String,
        val sourceCommitId: String
    )

    private data class TimedCommitList(
        val commits: List<CommitItem>,
        val cachedAtMillis: Long
    )

    private data class MissingCommitCacheKey(
        val targetCommitId: String,
        val sourceCommitId: String,
        val currentHeadCommitId: String
    )

    private data class TimedMissingCommitSet(
        val missingHashes: Set<String>,
        val cachedAtMillis: Long
    )

    private enum class PrState {
        OPEN,
        MERGED,
        CLOSED
    }

    private enum class PrFilter {
        OPEN,
        MERGED,
        CLOSED,
        ALL
    }

    private enum class PrRoleFilter {
        ALL,
        CREATED_BY_ME,
        REVIEWED_BY_ME
    }

    private data class PrListSupplement(
        val reviewStats: IssueStats?,
        val aiState: AiReviewBadgeState
    )

    private data class DeveloperCandidate(
        val id: Long,
        val username: String,
        val name: String
    )

    private data class PreCreateCheck(
        val code: Int,
        val message: String,
        val canBeAutomerge: Boolean,
        val primaryReviewers: List<DeveloperCandidate>,
        val generalReviewers: List<DeveloperCandidate>,
        val primaryReviewerNum: Int,
        val generalReviewerNum: Int,
        val primaryReviewerNumProvided: Boolean,
        val generalReviewerNumProvided: Boolean
    )

    private fun hasLockedPrimaryReviewerConstraint(check: PreCreateCheck): Boolean {
        return check.primaryReviewers.isNotEmpty() && check.primaryReviewerNumProvided && check.primaryReviewerNum > 0
    }

    private fun hasInitialGeneralReviewerSuggestion(check: PreCreateCheck): Boolean {
        return check.generalReviewers.isNotEmpty() && check.generalReviewerNumProvided && check.generalReviewerNum > 0
    }

    private fun currentPluginAuthorUsername(): String = System.getenv(pluginAuthorUsernameEnv).orEmpty().trim()

    private fun excludeCreatorCandidates(
        items: List<DeveloperCandidate>,
        creatorUsername: String = currentPluginAuthorUsername()
    ): List<DeveloperCandidate> {
        if (creatorUsername.isBlank()) return items
        return items.filterNot { it.username.trim().equals(creatorUsername, ignoreCase = true) }
    }

    private fun setReviewerCountSpinnerEnabled(spinner: javax.swing.JSpinner, enabled: Boolean) {
        val inputFill = createPrInputFill()
        val textColor = createPrPrimaryTextColor()
        spinner.isEnabled = enabled
        spinner.background = inputFill
        spinner.foreground = textColor
        (spinner.editor as? javax.swing.JSpinner.DefaultEditor)?.textField?.apply {
            isEnabled = enabled
            isEditable = enabled
            isFocusable = enabled
            background = inputFill
            foreground = textColor
            disabledTextColor = textColor
            caretColor = textColor
        }
        spinner.components.filterIsInstance<JComponent>().forEach { child ->
            child.isEnabled = enabled
            child.background = inputFill
            child.foreground = textColor
        }
        spinner.revalidate()
        spinner.repaint()
    }

    private class PrTableModel : AbstractTableModel() {
        private val columns = arrayOf("标题", "源分支", "目标分支", "创建人", "评审人")
        private var rows: List<PrItem> = emptyList()

        fun setRows(items: List<PrItem>, append: Boolean) {
            rows = if (append) rows + items else items
            fireTableDataChanged()
        }

        fun getItemAt(row: Int): PrItem = rows[row]

        fun getRows(): List<PrItem> = rows

        fun findById(id: Long): PrItem? = rows.firstOrNull { it.id == id }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = rows[rowIndex]
            return when (columnIndex) {
                0 -> item.title
                1 -> item.sourceBranch
                2 -> item.targetBranch
                3 -> item.author
                4 -> item.reviewers
                else -> ""
            }
        }
    }

    private class CommitTableModel : AbstractTableModel() {
        private val columns = arrayOf("提交信息", "提交人", "提交日期", "提交编号", "变更")
        private var rows: List<CommitItem> = emptyList()
        private var missingHashes: Set<String> = emptySet()

        fun setRows(items: List<CommitItem>, missingHashes: Set<String> = emptySet()) {
            rows = items
            this.missingHashes = missingHashes
            fireTableDataChanged()
        }

        fun getRows(): List<CommitItem> = rows

        fun getMissingHashes(): Set<String> = missingHashes

        fun getFullHashAt(row: Int): String = rows.getOrNull(row)?.hash.orEmpty()

        fun isMissingAt(row: Int): Boolean {
            val hash = rows.getOrNull(row)?.hash.orEmpty()
            return hash.isNotBlank() && missingHashes.contains(hash)
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = rows[rowIndex]
            return when (columnIndex) {
                0 -> item.message.ifBlank { "(无提交信息)" }
                1 -> item.author.ifBlank { "未知作者" }
                2 -> item.time.ifBlank { "-" }
                3 -> if (item.hash.length > 7) item.hash.take(7) else item.hash
                4 -> when {
                    item.additions > 0 && item.deletions > 0 -> "+${item.additions} / -${item.deletions}"
                    item.additions > 0 -> "+${item.additions}"
                    item.deletions > 0 -> "-${item.deletions}"
                    else -> "-"
                }
                else -> ""
            }
        }
    }

    private inner class CreatePrView {
        private val mergeTypeChooseOption = "__merge_type_choose__"
        var activeMode: InlinePrMode = InlinePrMode.CREATE
            private set
        val rootPanel = JPanel(BorderLayout())
        private val scrollContentPanel = JPanel(BorderLayout())
        private val rootScrollPane = createDetailScrollPane(
            scrollContentPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            ::createPrOuterFill
        ).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
            isOpaque = false
            background = createPrOuterFill()
            viewport.isOpaque = true
            viewport.background = createPrOuterFill()
        }
        val rootComponent: JComponent = rootPanel
        private val headerPanel = JPanel(BorderLayout())
        private val branchPanel = JPanel()
        private val branchStatusPanel = JPanel(BorderLayout())
        private val createTabs = object : JBTabbedPane() {
            override fun getPreferredSize(): Dimension {
                return computeCreateTabsPreferredSize(super.getPreferredSize(), this)
            }

            override fun getMinimumSize(): Dimension {
                return computeCreateTabsPreferredSize(super.getMinimumSize(), this)
            }
        }
        private val tabsWrapper = JPanel(BorderLayout())
        private val footerPanel = JPanel(BorderLayout())

        private val titleLabel = JBLabel("创建 Pull Request")
        private val newBadge = OutlinedPillLabel(JBUI.scale(16))
        private val branchArrowLabel = JBLabel("→", SwingConstants.CENTER)

        private val sourceBranchBox = BranchSelectorField("请选择源分支")
        private val targetBranchBox = BranchSelectorField("请选择目标分支")
        private val titleField = JBTextField()
        private val descField = JBTextArea()
        private val primaryReviewerPicker = ReviewerPickerField("请选择关键评审人") { updateReviewerPickerLinkState() }
        private val generalReviewerPicker = ReviewerPickerField("请选择普通评审人") { updateReviewerPickerLinkState() }
        private val primaryNumSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(0, 0, 999, 1))
        private val generalNumSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(0, 0, 999, 1))
        private val deleteSourceBranchCheck = JBCheckBox("合并后删除源分支", false)
        private val mergeTypeBox = javax.swing.JComboBox(arrayOf(mergeTypeChooseOption, "merge", "fast_forward"))
        private val diffTabCountLabel = OutlinedPillLabel(JBUI.scale(16))
        private val commitTabCountLabel = OutlinedPillLabel(JBUI.scale(16))
        private val createFileChangeWarningButton = JBLabel(IconManager.getInstance().getIcon("/icons/file-change-warning.svg", javaClass)).apply {
            isVisible = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        private val createCommitWarningLabel = JBLabel(IconManager.getInstance().getIcon("/icons/file-change-warning.svg", javaClass)).apply {
            isVisible = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        private val branchStatusIconLabel = JBLabel("·")
        private val branchStatusTextLabel = JBLabel("请选择源分支和目标分支")
        private val createChangeSearchField = JBTextField()
        private val createChangeSummaryLabel = JBLabel("0 个文件变更")
        private val createChangeAdditionsLabel = JBLabel("+0")
        private val createChangeDeletionsLabel = JBLabel("-0")
        private val createChangeTreeToggleButton = SegmentedFilterButton("树状")
        private val createChangeFlatToggleButton = SegmentedFilterButton("平铺")
        private val createChangeTreeRoot = DefaultMutableTreeNode("ROOT")
        private val createChangeTreeModel = DefaultTreeModel(createChangeTreeRoot)
        private val createChangeTree = Tree(createChangeTreeModel)
        private var createChangeTreeFlatMode = false
        private var createChangeTreeScrollPane: JBScrollPane? = null
        private var createChangeCard: JComponent? = null

        private val createCommitTimelineContent = ViewportWidthPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        private var createCommitTimelineScrollPane: JBScrollPane? = null
        private var createCommitCard: JComponent? = null

        private val submitButton = createPrimaryActionButton("提交")
        private val cancelButton = createSecondaryActionButton("取消")

        private val developerByKey = mutableMapOf<String, DeveloperCandidate>()
        private val createTabHeaders = mutableListOf<CreateTabHeader>()
        private var createTabHeaderListenerBound = false
        private var mandatoryPrimaryUsers: Set<String> = emptySet()
        private var minimumPrimary = 0
        private var minimumGeneral = 0
        private var initialGeneralReviewersApplied = false
        private var precheckBlockedReason: String? = null
        private var latestChanges: List<ChangeItem> = emptyList()
        private var latestCommits: List<CommitItem> = emptyList()
        private var latestPreCreateCheck: PreCreateCheck? = null
        private var createFileChangeWarningText: String? = null
        private var createCommitWarningText: String? = null
        private var createCommitWarningBalloon: Balloon? = null
        private var createFileChangeWarningBalloon: Balloon? = null
        private var latestMissingCommitHashes: Set<String> = emptySet()
        private var createChangesLoading = false
        private var createCommitsLoading = false
        private var createMissingCommitLoading = false
        private var branchRefreshVersion = 0
        private var availableCreateBranches: List<String> = emptyList()
        private var updatingCreateBranchCombo = false
        private var editingDetail: PrDetail? = null
        private var pendingEditPrecheck: PreCreateCheck? = null
        private var autoFilledCreateTitle: String? = null
        private var autoFilledCreateDesc: String? = null

        init {
            configureStaticComponents()
            buildUi()
            bindActions()
            applyTheme()
            if (createTabs.tabCount > 0) {
                createTabs.selectedIndex = 0
                updateCreateTabHeaderStates()
            }
        }

        fun prepareForCreate() {
            activeMode = InlinePrMode.CREATE
            editingDetail = null
            prepareForDisplay()
        }

        fun prepareForEdit(detail: PrDetail, initialCheck: PreCreateCheck? = null) {
            activeMode = InlinePrMode.EDIT
            editingDetail = detail
            pendingEditPrecheck = initialCheck
            prepareForDisplay()
        }

        private fun prepareForDisplay() {
            branchRefreshVersion += 1
            developerByKey.clear()
            mandatoryPrimaryUsers = emptySet()
            minimumPrimary = 0
            minimumGeneral = 0
            initialGeneralReviewersApplied = false
            latestChanges = emptyList()
            latestCommits = emptyList()
            latestPreCreateCheck = null
            latestMissingCommitHashes = emptySet()
            autoFilledCreateTitle = null
            autoFilledCreateDesc = null
            createChangesLoading = false
            createCommitsLoading = false
            createMissingCommitLoading = false
            updateCreateFileChangeWarning(false, null)
            updateCreateCommitWarning(false)
            precheckBlockedReason = "正在加载分支信息..."
            availableCreateBranches = emptyList()
            applyModePresentation()

            val detail = editingDetail
            titleField.text = if (activeMode == InlinePrMode.EDIT) detail?.title.orEmpty() else ""
            descField.text = if (activeMode == InlinePrMode.EDIT) detail?.overview?.desc.orEmpty() else ""
            primaryReviewerPicker.clearSelection()
            generalReviewerPicker.clearSelection()
            primaryReviewerPicker.setSelectionEditable(true)
            generalReviewerPicker.setSelectionEditable(true)
            primaryNumSpinner.value = detail?.overview?.needKeyReviewers ?: 0
            generalNumSpinner.value = detail?.overview?.needReviewers ?: 0
            setReviewerCountSpinnerEnabled(primaryNumSpinner, true)
            setReviewerCountSpinnerEnabled(generalNumSpinner, true)
            deleteSourceBranchCheck.isSelected = detail?.overview?.deleteBranchAfterMerged ?: false
            val mergeType = detail?.overview?.mergedType.orEmpty()
            mergeTypeBox.selectedItem = normalizeMergeTypeSelection(mergeType, mergeTypeChooseOption)

            sourceBranchBox.setAvailableBranches(emptyList())
            targetBranchBox.setAvailableBranches(emptyList())
            sourceBranchBox.setSelectedBranch(null)
            targetBranchBox.setSelectedBranch(null)
            sourceBranchBox.isEnabled = activeMode == InlinePrMode.CREATE
            targetBranchBox.isEnabled = activeMode == InlinePrMode.CREATE
            refreshReviewerRequirementControls()
            refreshDiffAndCommitView()
            refreshStatusBanner()
            updateCreateTabsVisibility()
            if (createTabs.tabCount > 0) {
                createTabs.selectedIndex = 0
                updateCreateTabHeaderStates()
            }
            setSubmitEnabled(false)
            loadInitialData(
                initialSourceBranch = detail?.sourceBranch,
                initialTargetBranch = detail?.targetBranch
            )
        }

        private fun applyCreateDefaultTitle(sourceBranch: String) {
            if (activeMode != InlinePrMode.CREATE) return
            val source = sourceBranch.trim()
            if (source.isBlank()) return
            val currentTitle = titleField.text.trim()
            if (currentTitle.isNotBlank() && currentTitle != autoFilledCreateTitle) return
            titleField.text = source
            autoFilledCreateTitle = source
        }

        private fun buildCreateDefaultDesc(commits: List<CommitItem>): String {
            return commits
                .asReversed()
                .map { it.message.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        private fun applyCreateDefaultDesc(commits: List<CommitItem>) {
            if (activeMode != InlinePrMode.CREATE) return
            val defaultDesc = buildCreateDefaultDesc(commits)
            val currentDesc = descField.text.trim()
            if (currentDesc.isNotBlank() && currentDesc != autoFilledCreateDesc) return
            descField.text = defaultDesc
            autoFilledCreateDesc = defaultDesc
        }

        private fun applyModePresentation() {
            when (activeMode) {
                InlinePrMode.CREATE -> {
                    titleLabel.text = "创建 Pull Request"
                    newBadge.setPill("NEW", createPrTabSelectedTextColor())
                    submitButton.text = "提交"
                }
                InlinePrMode.EDIT -> {
                    titleLabel.text = "编辑 Pull Request"
                    newBadge.setPill("EDIT", detailAccentColor)
                    submitButton.text = "提交"
                }
            }
            listOf(cancelButton, submitButton).forEach { button ->
                val metrics = button.getFontMetrics(button.font)
                val width = metrics.stringWidth(button.text.orEmpty()) + JBUI.scale(32)
                val height = maxOf(JBUI.scale(28), metrics.height + JBUI.scale(6))
                val size = Dimension(width, height)
                button.preferredSize = size
                button.minimumSize = size
                button.maximumSize = size
                button.revalidate()
            }
        }

        fun applyTheme() {
            val panelFill = createPrOuterFill()
            rootPanel.background = panelFill
            scrollContentPanel.background = panelFill
            rootScrollPane.background = panelFill
            rootScrollPane.viewport.background = panelFill
            headerPanel.background = createPrHeaderFill()
            branchPanel.background = panelFill
            branchStatusPanel.background = panelFill
            footerPanel.background = createPrHeaderFill()
            createTabs.background = createPrSectionFill()
            titleLabel.foreground = createPrPrimaryTextColor()
            branchStatusTextLabel.foreground = createPrPrimaryTextColor()
            createChangeSummaryLabel.foreground = createPrSecondaryTextColor()
            createChangeAdditionsLabel.foreground = JBColor(Color(0x1E8E3E), Color(0x57D163))
            createChangeDeletionsLabel.foreground = JBColor(Color(0xD93025), Color(0xF47067))
            createFileChangeWarningButton.foreground = detailMutedColor()
            createCommitWarningLabel.foreground = detailMutedColor()
            configureInputColors()
            updateCreateTabHeaderStates()
            refreshStatusBanner()
            createChangeTree.revalidate()
            createChangeTree.repaint()
            createCommitTimelineContent.revalidate()
            createCommitTimelineContent.repaint()
            updateCreateTabsVisibility()
            createTabs.revalidate()
            createTabs.repaint()
        }

        private fun configureStaticComponents() {
            newBadge.setPill("NEW", createPrTabSelectedTextColor())
            newBadge.font = newBadge.font.deriveFont(Font.BOLD, 10f)
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, globalUiFontSize + 2f)
            branchArrowLabel.font = branchArrowLabel.font.deriveFont(Font.PLAIN, globalUiFontSize + 2f)

            titleField.isOpaque = true
            titleField.border = JBUI.Borders.empty()
            titleField.font = titleField.font.deriveFont(globalUiFontSize.toFloat())
            titleField.emptyText.text = "请输入 Pull Request 标题..."

            descField.rows = 3
            descField.lineWrap = true
            descField.wrapStyleWord = true
            descField.border = JBUI.Borders.empty()
            descField.isOpaque = true
            descField.font = descField.font.deriveFont(globalUiFontSize.toFloat())

            val textFieldFocusListener = object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    repaintCreateTextInputs()
                }

                override fun focusGained(e: java.awt.event.FocusEvent?) {
                    repaintCreateTextInputs()
                }
            }
            titleField.addFocusListener(textFieldFocusListener)
            descField.addFocusListener(textFieldFocusListener)

            primaryReviewerPicker.configureEditor(globalUiFontSize.toFloat())
            generalReviewerPicker.configureEditor(globalUiFontSize.toFloat())

            mergeTypeBox.border = JBUI.Borders.empty()
            mergeTypeBox.font = mergeTypeBox.font.deriveFont(globalUiFontSize - 1f)
            mergeTypeBox.maximumRowCount = 3
            mergeTypeBox.isEditable = false
            configureFlatComboBox(mergeTypeBox)
            sourceBranchBox.configureField(globalUiFontSize - 1f)
            targetBranchBox.configureField(globalUiFontSize - 1f)
            sourceBranchBox.preferredSize = Dimension(JBUI.scale(280), JBUI.scale(28))
            sourceBranchBox.minimumSize = Dimension(JBUI.scale(214), JBUI.scale(28))
            targetBranchBox.preferredSize = Dimension(JBUI.scale(280), JBUI.scale(28))
            targetBranchBox.minimumSize = Dimension(JBUI.scale(214), JBUI.scale(28))
            mergeTypeBox.preferredSize = Dimension(JBUI.scale(220), JBUI.scale(28))
            mergeTypeBox.minimumSize = Dimension(JBUI.scale(160), JBUI.scale(28))

            listOf(primaryNumSpinner, generalNumSpinner).forEach { spinner ->
                spinner.border = JBUI.Borders.empty()
                spinner.preferredSize = Dimension(JBUI.scale(64), JBUI.scale(30))
                spinner.minimumSize = spinner.preferredSize
                spinner.maximumSize = spinner.preferredSize
                (spinner.editor as? javax.swing.JSpinner.DefaultEditor)?.textField?.apply {
                    border = JBUI.Borders.empty()
                    horizontalAlignment = javax.swing.JTextField.CENTER
                    isOpaque = false
                    font = font.deriveFont(globalUiFontSize - 1f)
                    addFocusListener(object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            runCatching { spinner.commitEdit() }
                        }
                    })
                }
            }

            deleteSourceBranchCheck.isOpaque = false
            deleteSourceBranchCheck.border = JBUI.Borders.empty()
            deleteSourceBranchCheck.margin = JBUI.insets(0)
            deleteSourceBranchCheck.font = deleteSourceBranchCheck.font.deriveFont(globalUiFontSize - 1f)
            listOf(cancelButton, submitButton).forEach { button ->
                button.border = JBUI.Borders.empty(3, 10, 3, 10)
                button.margin = JBUI.emptyInsets()
                button.alignmentY = Component.CENTER_ALIGNMENT
                val metrics = button.getFontMetrics(button.font)
                val width = metrics.stringWidth(button.text.orEmpty()) + JBUI.scale(32)
                val height = maxOf(JBUI.scale(28), metrics.height + JBUI.scale(6))
                val size = Dimension(width, height)
                button.preferredSize = size
                button.minimumSize = size
                button.maximumSize = size
            }

            createFileChangeWarningButton.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    toggleCreateFileChangeWarningBalloon()
                }
            })
            createCommitWarningLabel.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    toggleCreateCommitWarningBalloon()
                }
            })
            diffTabCountLabel.setPill("0", detailTabBadgeColor())
            diffTabCountLabel.setPill("0", detailTabBadgeColor())
            commitTabCountLabel.setPill("0", detailTabBadgeColor())
            val changeSearchHint = "按名称搜索文件..."
            createChangeSearchField.emptyText.text = changeSearchHint
            createChangeSearchField.isOpaque = false
            createChangeSearchField.border = JBUI.Borders.empty()
            val changeSearchWidth = (createChangeSearchField.getFontMetrics(createChangeSearchField.font).stringWidth(changeSearchHint) + JBUI.scale(32)) * 2
            val changeSearchSize = Dimension(changeSearchWidth, createChangeSearchField.preferredSize.height)
            createChangeSearchField.preferredSize = changeSearchSize
            createChangeSearchField.minimumSize = changeSearchSize

            configureListFilterButton(createChangeTreeToggleButton)
            configureListFilterButton(createChangeFlatToggleButton)
            createChangeTree.emptyText.text = "暂无对比结果"
            createChangeTree.cellRenderer = CreateChangeTreeCellRenderer()
            createChangeTree.rowHeight = 0
            createChangeTree.isRootVisible = false
            createChangeTree.showsRootHandles = true
            createChangeTree.toggleClickCount = 0
            createChangeTree.isOpaque = false
            createChangeTree.border = JBUI.Borders.empty(6, 6)
            updateCreateChangeModeToggleStyle()
            createChangeTree.addTreeSelectionListener {
                val node = createChangeTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
                val selected = node.userObject as? ChangeItem ?: return@addTreeSelectionListener
                openCreateDiff(selected)
            }

            createCommitTimelineContent.isOpaque = true
            createCommitTimelineContent.background = detailSurfaceFill()
            createCommitTimelineContent.border = JBUI.Borders.empty()
            refreshReviewerRequirementControls()
        }

        private fun configureInputColors() {
            val inputFill = createPrInputFill()
            val textColor = createPrPrimaryTextColor()
            val secondaryText = createPrSecondaryTextColor()

            titleField.background = inputFill
            titleField.foreground = textColor
            titleField.caretColor = textColor
            descField.background = inputFill
            descField.foreground = textColor
            descField.caretColor = textColor
            primaryReviewerPicker.applyTheme()
            generalReviewerPicker.applyTheme()
            createChangeSearchField.background = searchFieldSurfaceFill()
            createChangeSearchField.foreground = textColor
            createChangeSearchField.caretColor = textColor
            branchArrowLabel.foreground = secondaryText

            mergeTypeBox.background = inputFill
            mergeTypeBox.foreground = textColor
            sourceBranchBox.applyTheme()
            targetBranchBox.applyTheme()
            listOf(primaryNumSpinner, generalNumSpinner).forEach { spinner ->
                spinner.background = inputFill
                spinner.foreground = textColor
                (spinner.editor as? javax.swing.JSpinner.DefaultEditor)?.textField?.apply {
                    background = inputFill
                    foreground = textColor
                    caretColor = textColor
                }
                setReviewerCountSpinnerEnabled(spinner, spinner.isEnabled)
            }
            deleteSourceBranchCheck.foreground = secondaryText
        }

        private fun buildUi() {
            val contentInset = createPrHorizontalInset()
            val overviewContentInset = detailHorizontalInset()

            rootPanel.isOpaque = true
            rootPanel.isFocusable = true
            rootPanel.background = createPrOuterFill()
            rootPanel.minimumSize = Dimension(0, 0)
            rootPanel.border = JBUI.Borders.empty(10, contentInset, 12, contentInset)
            scrollContentPanel.isOpaque = false
            scrollContentPanel.minimumSize = Dimension(0, 0)
            branchPanel.minimumSize = Dimension(0, 0)
            branchStatusPanel.minimumSize = Dimension(0, 0)
            createTabs.minimumSize = Dimension(0, 0)
            footerPanel.minimumSize = Dimension(0, 0)

            val topContainer = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(10, 0, 0, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }

            headerPanel.isOpaque = false
            headerPanel.alignmentX = Component.LEFT_ALIGNMENT
            headerPanel.border = JBUI.Borders.empty(0, contentInset + overviewContentInset, 0, contentInset + JBUI.scale(18))
            headerPanel.layout = GridBagLayout()
            headerPanel.add(newBadge, GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.BASELINE_LEADING
            })
            headerPanel.add(Box.createHorizontalStrut(JBUI.scale(8)), GridBagConstraints().apply {
                gridx = 1
                gridy = 0
            })
            headerPanel.add(titleLabel, GridBagConstraints().apply {
                gridx = 2
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.BASELINE_LEADING
            })
            headerPanel.add(cancelButton, GridBagConstraints().apply {
                gridx = 3
                gridy = 0
                anchor = GridBagConstraints.BASELINE_TRAILING
                insets = JBUI.insetsRight(12)
            })
            headerPanel.add(submitButton, GridBagConstraints().apply {
                gridx = 4
                gridy = 0
                anchor = GridBagConstraints.BASELINE_TRAILING
                insets = JBUI.insetsRight(JBUI.scale(6))
            })
            rootPanel.add(headerPanel, BorderLayout.NORTH)

            branchPanel.layout = BoxLayout(branchPanel, BoxLayout.Y_AXIS)
            branchPanel.isOpaque = false
            branchPanel.alignmentX = Component.LEFT_ALIGNMENT
            branchPanel.border = JBUI.Borders.empty(20, contentInset + overviewContentInset, 0, contentInset + overviewContentInset)
            branchPanel.add(buildBranchSection())
            topContainer.add(branchPanel)

            branchStatusPanel.isOpaque = false
            branchStatusPanel.alignmentX = Component.LEFT_ALIGNMENT
            branchStatusPanel.border = JBUI.Borders.empty(12, contentInset + overviewContentInset, 0, contentInset + overviewContentInset)
            branchStatusPanel.add(buildBranchStatusContent(), BorderLayout.CENTER)
            topContainer.add(branchStatusPanel)

            createTabs.border = JBUI.Borders.empty()
            createTabs.isOpaque = true
            createTabs.background = createPrSectionFill()
            installCreateTabsUi()
            createTabs.addTab("概览", buildOverviewTab())
            createTabs.addTab("文件改动", buildDiffTab())
            createTabs.addTab("提交记录", buildCommitTab())
            setupCreateTabsHeader()

            tabsWrapper.apply {
                isOpaque = false
                border = JBUI.Borders.empty(22, 0, 0, 0)
                removeAll()
                add(createTabs, BorderLayout.CENTER)
            }

            val cardContent = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(topContainer, BorderLayout.NORTH)
                add(tabsWrapper, BorderLayout.CENTER)
            }
            scrollContentPanel.add(cardContent, BorderLayout.CENTER)
            rootPanel.add(rootScrollPane, BorderLayout.CENTER)
            bindCreateBackgroundClickDismiss(rootPanel)
        }

        private fun buildBranchSection(): JComponent {
            val selectorMinWidth = JBUI.scale(200)
            val gap = JBUI.scale(12)
            val arrowSlotWidth = branchArrowLabel.preferredSize.width
            val labelArrowSlotWidth = arrowSlotWidth + JBUI.scale(4)

            val sourceSelector = wrapCreateInput(sourceBranchBox, JBUI.insets(3, 10)).apply {
                preferredSize = Dimension(JBUI.scale(240), preferredSize.height)
                minimumSize = Dimension(selectorMinWidth, preferredSize.height)
            }
            val targetSelector = wrapCreateInput(targetBranchBox, JBUI.insets(3, 10)).apply {
                preferredSize = Dimension(JBUI.scale(240), preferredSize.height)
                minimumSize = Dimension(selectorMinWidth, preferredSize.height)
            }

            val labelsRow = JPanel().apply {
                layout = GridBagLayout()
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(buildBranchColumnLabel("源分支"), GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insetsRight(gap)
                })
                add(Box.createHorizontalStrut(labelArrowSlotWidth), GridBagConstraints().apply {
                    gridx = 1
                    gridy = 0
                    weightx = 0.0
                    insets = JBUI.insetsRight(gap)
                })
                add(buildBranchColumnLabel("目标分支"), GridBagConstraints().apply {
                    gridx = 2
                    gridy = 0
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                })
            }

            val selectorsRow = JPanel().apply {
                layout = GridBagLayout()
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(sourceSelector, GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insetsRight(gap)
                })
                add(branchArrowLabel, GridBagConstraints().apply {
                    gridx = 1
                    gridy = 0
                    weightx = 0.0
                    anchor = GridBagConstraints.CENTER
                    insets = JBUI.insetsRight(gap)
                })
                add(targetSelector, GridBagConstraints().apply {
                    gridx = 2
                    gridy = 0
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                })
            }

            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(labelsRow)
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(selectorsRow)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }

            return JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(content, BorderLayout.CENTER)
            }
        }

        private fun buildBranchColumnLabel(text: String): JComponent {
            return JBLabel(text).apply {
                font = font.deriveFont(Font.BOLD, globalUiFontSize - 1f)
                foreground = createPrSecondaryTextColor()
            }
        }

        private fun createBranchConstraints(
            gridx: Int,
            weightx: Double,
            fill: Int = GridBagConstraints.HORIZONTAL,
            left: Int = 0,
            right: Int = 0,
            bottom: Int = 0
        ): GridBagConstraints {
            return GridBagConstraints().apply {
                this.gridx = gridx
                this.gridy = 0
                this.weightx = weightx
                this.fill = fill
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(0, left, bottom, right)
            }
        }

        private fun buildBranchStatusContent(): JComponent {
            return JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(branchStatusIconLabel)
                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                    add(branchStatusTextLabel)
                }, BorderLayout.CENTER)
            }
        }

        private fun buildOverviewTab(): JComponent {
            val content = buildDetailTabBody(topInset = 24, bottomInset = 20, tracksViewportWidth = true).apply {
                isOpaque = true
                background = createPrSectionFill()
            }
            content.add(buildFieldSection("标题", wrapCreateInput(titleField, JBUI.insets(8, 12)), required = true, bottomGap = 20))
            content.add(buildFieldSection("描述", wrapCreateInput(createTextAreaScroll(descField, 80), JBUI.insets(8, 12)), required = true, bottomGap = 20))
            content.add(buildReviewerFieldSection("关键评审人", primaryReviewerPicker, primaryNumSpinner, "名关键评审成员审查通过后可合并。"))
            content.add(buildReviewerFieldSection("普通评审人", generalReviewerPicker, generalNumSpinner, "名评审成员审查通过后可合并。"))
            content.add(Box.createVerticalStrut(JBUI.scale(20)))
            content.add(buildMergeSection())
            content.add(Box.createVerticalGlue())
            bindCreateBackgroundClickDismiss(content)
            return wrapCreateTabContent(
                createDetailScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, ::createPrSectionFill)
            )
        }

        private fun buildMergeSection(): JComponent {
            val mergeInputRow = JPanel(GridLayout(1, 2, JBUI.scale(16), 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(wrapCreateInput(mergeTypeBox as JComponent, JBUI.insets(3, 10)))
                add(JPanel().apply { isOpaque = false })
            }
            val deleteRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(deleteSourceBranchCheck)
            }
            return buildFieldSection("合并方式", JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(mergeInputRow)
                add(Box.createVerticalStrut(JBUI.scale(18)))
                add(deleteRow)
            }, bottomGap = 20)
        }

        private fun buildInlineFieldRow(left: JComponent, right: JComponent): JComponent {
            return stretchDetailTabChild(ResponsiveGridPanel(
                expandedColumns = 2,
                collapseWidth = JBUI.scale(760),
                expandedHorizontalGap = JBUI.scale(16),
                collapsedVerticalGap = JBUI.scale(16)
            ).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                add(left)
                add(right)
            })
        }

        private fun buildReviewerFieldSection(title: String, picker: ReviewerPickerField, spinner: javax.swing.JSpinner, hintSuffix: String): JComponent {
            val reviewerFieldPreferredWidth = JBUI.scale(520)
            val reviewerFieldMinWidth = JBUI.scale(360)
            val reviewerInput = wrapCreateInput(picker, JBUI.insets(3, 10), allowVerticalExpand = true).apply {
                preferredSize = Dimension(reviewerFieldPreferredWidth, preferredSize.height)
                minimumSize = Dimension(reviewerFieldMinWidth, minimumSize.height)
            }
            val hintRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(JBLabel("至少需要").apply {
                    font = font.deriveFont(globalUiFontSize - 2f)
                    foreground = createPrSecondaryTextColor()
                })
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(wrapCreateInput(spinner, JBUI.insets(2, 6)))
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(JBLabel(hintSuffix).apply {
                    font = font.deriveFont(globalUiFontSize - 2f)
                    foreground = createPrSecondaryTextColor()
                })
            }
            return buildFieldSection(title, JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(leftAlignCreateSectionContent(reviewerInput, reviewerFieldPreferredWidth, reviewerFieldMinWidth, stretchVertically = true))
                add(Box.createVerticalStrut(JBUI.scale(10)))
                add(leftAlignCreateSectionContent(hintRow, reviewerFieldPreferredWidth, reviewerFieldMinWidth))
            }, bottomGap = 20)
        }

        private fun leftAlignCreateSectionContent(
            content: JComponent,
            preferredWidth: Int,
            minWidth: Int = preferredWidth,
            stretchVertically: Boolean = false
        ): JComponent {
            return JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(content, BorderLayout.WEST)
                maximumSize = Dimension(Int.MAX_VALUE, if (stretchVertically) Int.MAX_VALUE else content.preferredSize.height)
                preferredSize = Dimension(preferredWidth, content.preferredSize.height)
                minimumSize = Dimension(minWidth, content.minimumSize.height.coerceAtLeast(content.preferredSize.height))
            }
        }

        private fun buildFieldSection(title: String, component: JComponent, required: Boolean = false, bottomGap: Int = 18): JComponent {
            return JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(JBLabel(title).apply {
                        font = font.deriveFont(Font.BOLD, overviewSectionTitleFontSize())
                        foreground = createPrPrimaryTextColor()
                    })
                    if (required) {
                        add(JBLabel(" *").apply {
                            font = font.deriveFont(Font.BOLD, overviewSectionTitleFontSize())
                            foreground = JBColor(Color(0xC75450), Color(0xF47067))
                        })
                    }
                })
                add(Box.createVerticalStrut(JBUI.scale(8)))
                component.alignmentX = Component.LEFT_ALIGNMENT
                add(stretchDetailTabChild(component))
                if (bottomGap > 0) {
                    add(Box.createVerticalStrut(JBUI.scale(bottomGap)))
                }
            }
        }

        private fun buildDiffTab(): JComponent = wrapCreateTabContent(buildCreateFileChangePanel())

        private fun buildCommitTab(): JComponent = wrapCreateTabContent(buildCreateCommitPanel())

        private fun wrapCreateTabContent(component: JComponent, showTopBorder: Boolean = true): JComponent {
            return JPanel(BorderLayout()).apply {
                isOpaque = true
                background = createPrSectionFill()
                border = if (showTopBorder) JBUI.Borders.customLineTop(detailTabUnderlineColor()) else JBUI.Borders.empty()
                add(component, BorderLayout.CENTER)
            }
        }

        private fun buildCreateFileChangePanel(): JComponent {
            val togglePanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(createChangeTreeToggleButton)
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(createChangeFlatToggleButton)
            }
            val toolbar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(
                    wrapDetailSurface(
                        createChangeSearchField,
                        fillColor = searchFieldSurfaceFill(),
                        outlineColor = searchFieldOutlineColor(),
                        fillColorProvider = ::searchFieldSurfaceFill,
                        outlineColorProvider = ::searchFieldOutlineColor,
                        padding = JBUI.insets(5, 10)
                    ),
                    BorderLayout.WEST
                )
                add(wrapDetailSurface(togglePanel, padding = JBUI.insets(2)), BorderLayout.EAST)
            }
            val treeScroll = createDetailScrollPane(
                createChangeTree,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                fillColorProvider = ::detailSurfaceFill
            )
            createChangeTreeScrollPane = treeScroll
            val footer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(14), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.customLineTop(detailOutlineColor())
                add(createChangeSummaryLabel)
                add(createChangeAdditionsLabel)
                add(createChangeDeletionsLabel)
            }
            val treeWrapper = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(treeScroll, BorderLayout.CENTER)
                add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(8)
                    add(footer, BorderLayout.WEST)
                }, BorderLayout.SOUTH)
            }
            val changeCard = wrapDetailSurface(treeWrapper, padding = JBUI.insets(8, 8, 0, 8))
            createChangeCard = changeCard
            val body = buildDetailTabBody().apply {
                add(stretchDetailTabChild(toolbar))
                add(Box.createVerticalStrut(JBUI.scale(10)))
                add(stretchDetailTabChild(changeCard))
            }
            return JPanel(BorderLayout()).apply {
                isOpaque = true
                background = createPrSectionFill()
                add(body, BorderLayout.CENTER)
            }
        }

        private fun buildCreateCommitPanel(): JComponent {
            val pane = createDetailScrollPane(
                createCommitTimelineContent,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                ::detailSurfaceFill
            ).apply {
                border = JBUI.Borders.emptyTop(6)
                viewportBorder = null
                verticalScrollBar.unitIncrement = JBUI.scale(24)
                verticalScrollBar.blockIncrement = JBUI.scale(96)
            }
            createCommitTimelineScrollPane = pane
            val commitCard = wrapDetailSurface(pane, padding = JBUI.insets(6, 8, 6, 8))
            createCommitCard = commitCard
            val body = buildDetailTabBody().apply {
                add(Box.createVerticalStrut(JBUI.scale(10)))
                add(stretchDetailTabChild(commitCard))
            }
            return JPanel(BorderLayout()).apply {
                isOpaque = true
                background = createPrSectionFill()
                add(body, BorderLayout.CENTER)
            }
        }

        private fun refreshReviewerRequirementControls() {
            val primaryHasSelection = primaryReviewerPicker.getSelectedCandidates().isNotEmpty()
            val generalHasSelection = generalReviewerPicker.getSelectedCandidates().isNotEmpty()
            val primaryRequired = if (primaryHasSelection) maxOf(minimumPrimary, 1) else 0
            val generalRequired = if (generalHasSelection) maxOf(minimumGeneral, 1) else 0
            (primaryNumSpinner.model as javax.swing.SpinnerNumberModel).minimum = primaryRequired
            (generalNumSpinner.model as javax.swing.SpinnerNumberModel).minimum = generalRequired
            primaryNumSpinner.value = if (primaryHasSelection) {
                maxOf((primaryNumSpinner.value as? Int) ?: 0, primaryRequired)
            } else {
                0
            }
            generalNumSpinner.value = if (generalHasSelection) {
                maxOf((generalNumSpinner.value as? Int) ?: 0, generalRequired)
            } else {
                0
            }
        }

        private fun updateReviewerPickerLinkState() {
            primaryReviewerPicker.syncMirroredCandidates(generalReviewerPicker.getSelectedCandidates())
            generalReviewerPicker.syncMirroredCandidates(primaryReviewerPicker.getSelectedCandidates())
            refreshReviewerRequirementControls()
        }

        private fun mergeTypeDisplayText(value: String?, inPopup: Boolean): String {
            return when (value?.trim().orEmpty()) {
                "" -> if (inPopup) "合并时选择" else "请用户选择（非必填）"
                mergeTypeChooseOption -> "合并时选择"
                "merge" -> if (inPopup) "Merge（合并所有提交）" else "Merge"
                "fast_forward", "squash" -> if (inPopup) {
                    "Merge(Fast-Forward-Only)（不创建合并节点，采用Fast-Forward-Only方式合并）"
                } else {
                    "Merge(Fast-Forward-Only)"
                }
                else -> value.orEmpty()
            }
        }

        private fun resolveMergeTypeValue(selected: String?): String {
            return when (selected?.trim().orEmpty()) {
                "", mergeTypeChooseOption -> ""
                "squash", "fast_forward" -> "fast_forward"
                else -> selected.orEmpty().trim()
            }
        }

        private fun normalizeMergeTypeSelection(value: String?, emptyValue: String): String {
            return when (value?.trim().orEmpty().lowercase()) {
                "" -> emptyValue
                "squash", "fast_forward" -> "fast_forward"
                else -> value.orEmpty().trim()
            }
        }

        private fun mergeTypePopupWidth(): Int {
            @Suppress("UNCHECKED_CAST")
            val renderer = mergeTypeBox.renderer as? javax.swing.ListCellRenderer<Any?>
            val measureList = javax.swing.JList<Any?>().apply {
                font = mergeTypeBox.font
            }
            val maxRowWidth = sequenceOf(mergeTypeChooseOption, "merge", "fast_forward")
                .mapIndexed { index, value ->
                    val rendered = renderer?.getListCellRendererComponent(
                        measureList,
                        value,
                        index,
                        false,
                        false
                    ) as? JComponent
                    rendered?.preferredSize?.width ?: 0
                }
                .maxOrNull()
                ?: 0
            return maxRowWidth + JBUI.scale(20)
        }

        private fun configureFlatComboBox(comboBox: javax.swing.JComboBox<String>) {
            val textInset = JBUI.scale(14)
            val rowVerticalInset = JBUI.scale(8)
            val popupRowHeight = JBUI.scale(40)
            val popupArc = JBUI.scale(14)
            val popupTopInset = JBUI.scale(8)
            val popupBottomInset = JBUI.scale(2)
            val popupHorizontalInset = JBUI.scale(6)
            val popupOutlineWidth = JBUI.scale(1)
            val popupFill = JBColor(Color(0xFFFFFF), Color(0x2E333B))
            val popupHoverFill = JBColor(Color(0xF5F7FA), Color(0x3A404A))
            val popupSelectedFill = JBColor(Color(0xEAF2FF), Color(0x253A5A))
            val popupSelectedStripe = JBColor(Color(0x4C8DFF), Color(0x4C8DFF))
            val popupOutline = JBColor(Color(0xD0D7E2), Color(0x6E7787))
            comboBox.isOpaque = false
            comboBox.renderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as javax.swing.JLabel
                    label.border = JBUI.Borders.empty(rowVerticalInset, textInset, rowVerticalInset, JBUI.scale(6))
                    label.font = label.font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
                    val isMergeTypeBox = comboBox === mergeTypeBox
                    label.text = if (isMergeTypeBox) {
                        mergeTypeDisplayText(value as? String, index >= 0)
                    } else {
                        value?.toString().orEmpty()
                    }
                    label.foreground = if (isMergeTypeBox && index < 0 && value == null) {
                        createPrSecondaryTextColor()
                    } else {
                        createPrPrimaryTextColor()
                    }
                    if (index < 0) {
                        label.isOpaque = false
                        label.background = comboBox.background
                        return label
                    }
                    label.isOpaque = false
                    return object : JPanel(BorderLayout()) {
                        override fun paintComponent(g: Graphics) {
                            val g2 = g.create() as Graphics2D
                            try {
                                g2.color = if (isSelected) popupSelectedFill else if (cellHasFocus) popupHoverFill else popupFill
                                g2.fillRect(0, 0, width, height)
                                if (isSelected) {
                                    g2.color = popupSelectedStripe
                                    g2.fillRoundRect(0, JBUI.scale(6), JBUI.scale(3), height - JBUI.scale(12), JBUI.scale(6), JBUI.scale(6))
                                }
                            } finally {
                                g2.dispose()
                            }
                            super.paintComponent(g)
                        }
                    }.apply {
                        isOpaque = true
                        background = if (isSelected) popupSelectedFill else if (cellHasFocus) popupHoverFill else popupFill
                        border = JBUI.Borders.empty()
                        add(label, BorderLayout.CENTER)
                    }
                }
            }
            comboBox.setUI(object : javax.swing.plaf.basic.BasicComboBoxUI() {
                override fun createArrowButton(): JButton {
                    return object : JButton() {
                        init {
                            text = ""
                            isOpaque = false
                            isContentAreaFilled = false
                            isBorderPainted = false
                            isFocusable = false
                            border = JBUI.Borders.empty()
                            horizontalAlignment = SwingConstants.CENTER
                            verticalAlignment = SwingConstants.CENTER
                            iconTextGap = 0
                            val size = Dimension(JBUI.scale(16), JBUI.scale(16))
                            preferredSize = size
                            minimumSize = size
                            maximumSize = size
                        }

                        override fun paintComponent(g: Graphics) {
                            val g2 = g.create() as Graphics2D
                            try {
                                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                g2.color = resolveCreateComboIndicatorColor(comboBox)
                                g2.stroke = BasicStroke(JBUI.scale(1.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                                val centerX = width / 2f
                                val centerY = height / 2f
                                val half = JBUI.scale(4).toFloat()
                                g2.drawLine((centerX - half).toInt(), (centerY - 2).toInt(), centerX.toInt(), (centerY + 2).toInt())
                                g2.drawLine(centerX.toInt(), (centerY + 2).toInt(), (centerX + half).toInt(), (centerY - 2).toInt())
                            } finally {
                                g2.dispose()
                            }
                        }
                    }
                }

                override fun createPopup(): javax.swing.plaf.basic.ComboPopup {
                    @Suppress("UNCHECKED_CAST")
                    val targetComboBox = comboBox as javax.swing.JComboBox<Any?>
                    return object : javax.swing.plaf.basic.BasicComboPopup(targetComboBox) {
                        init {
                            scroller.verticalScrollBarPolicy = if (comboBox === mergeTypeBox) {
                                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                            } else {
                                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                            }
                            isOpaque = false
                            background = popupFill
                            border = JBUI.Borders.empty()
                            scroller.border = JBUI.Borders.empty(popupTopInset, popupHorizontalInset, popupBottomInset, popupHorizontalInset)
                            scroller.viewportBorder = null
                            scroller.isOpaque = false
                            scroller.background = popupFill
                            scroller.viewport.isOpaque = true
                            scroller.viewport.background = popupFill
                            list.background = popupFill
                            list.selectionBackground = popupSelectedFill
                            list.selectionForeground = createPrPrimaryTextColor()
                            list.fixedCellHeight = popupRowHeight
                            list.border = JBUI.Borders.empty()
                        }

                        override fun paintComponent(g: Graphics) {
                            val g2 = g.create() as Graphics2D
                            try {
                                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                g2.color = popupFill
                                g2.fillRoundRect(0, 0, width - 1, height - 1, popupArc, popupArc)
                                g2.color = popupOutline
                                g2.stroke = BasicStroke(JBUI.scale(1f))
                                val inset = JBUI.scale(0.5f)
                                g2.draw(RoundRectangle2D.Float(
                                    inset,
                                    inset,
                                    width - JBUI.scale(1f),
                                    height - JBUI.scale(1f),
                                    popupArc.toFloat(),
                                    popupArc.toFloat()
                                ))
                            } finally {
                                g2.dispose()
                            }
                        }

                        override fun computePopupBounds(px: Int, py: Int, pw: Int, ph: Int): Rectangle {
                            val targetWidth = if (comboBox === mergeTypeBox) {
                                mergeTypePopupWidth().coerceAtLeast(comboBox.width)
                            } else {
                                pw
                            }
                            return super.computePopupBounds(px, py, targetWidth, ph)
                        }

                        override fun show() {
                            val maxVisibleRows = if (comboBox === mergeTypeBox) {
                                comboBox.itemCount.coerceAtLeast(1)
                            } else {
                                comboBox.maximumRowCount.coerceAtLeast(2)
                            }
                            val visibleRowCount = comboBox.itemCount.coerceAtMost(maxVisibleRows).coerceAtLeast(1)
                            list.visibleRowCount = visibleRowCount
                            val popupHeight = (list.fixedCellHeight.takeIf { it > 0 } ?: popupRowHeight) * visibleRowCount +
                                scroller.insets.top + scroller.insets.bottom + popupOutlineWidth * 2 + JBUI.scale(2)
                            val popupWidth = if (comboBox === mergeTypeBox) {
                                mergeTypePopupWidth().coerceAtLeast(comboBox.width)
                            } else {
                                comboBox.width.coerceAtLeast(preferredSize?.width ?: 0)
                            }
                            val popupSize = Dimension(popupWidth, popupHeight)
                            scroller.preferredSize = popupSize
                            scroller.minimumSize = popupSize
                            if (comboBox === mergeTypeBox) {
                                scroller.maximumSize = popupSize
                            }
                            preferredSize = popupSize
                            size = popupSize
                            super.show()
                            val adjustedWidth = if (comboBox === mergeTypeBox) popupWidth else comboBox.width
                            val adjustedSize = Dimension(adjustedWidth, popupHeight)
                            scroller.preferredSize = adjustedSize
                            scroller.minimumSize = adjustedSize
                            if (comboBox === mergeTypeBox) {
                                scroller.maximumSize = adjustedSize
                            }
                            preferredSize = adjustedSize
                            size = adjustedSize
                            revalidate()
                            repaint()
                        }
                    }
                }

                override fun paintCurrentValue(g: Graphics, bounds: Rectangle, hasFocus: Boolean) {
                    val renderer = comboBox.renderer ?: return
                    @Suppress("UNCHECKED_CAST")
                    val component = (renderer as javax.swing.ListCellRenderer<Any?>).getListCellRendererComponent(
                        listBox as javax.swing.JList<Any?>,
                        comboBox.selectedItem,
                        -1,
                        false,
                        false
                    )
                    currentValuePane.paintComponent(g, component, comboBox, bounds.x, bounds.y, bounds.width, bounds.height, false)
                }

                override fun paintCurrentValueBackground(g: Graphics, bounds: Rectangle, hasFocus: Boolean) = Unit
            })
            comboBox.putClientProperty("JComponent.roundRect", true)
        }

        // #region debug-point A:combo-debug-reporter
        private fun reportCreateBranchComboDebug(
            runId: String,
            hypothesisId: String,
            location: String,
            msg: String,
            data: Map<String, Any?> = emptyMap()
        ) {
            runCatching {
                val envFile = File(project.basePath ?: ".", ".dbg/branch-combo-ui.env")
                var serverUrl = "http://127.0.0.1:7777/event"
                var sessionId = "branch-combo-ui"
                if (envFile.exists()) {
                    envFile.readLines().forEach { line ->
                        when {
                            line.startsWith("DEBUG_SERVER_URL=") -> serverUrl = line.substringAfter("=")
                            line.startsWith("DEBUG_SESSION_ID=") -> sessionId = line.substringAfter("=")
                        }
                    }
                }
                val payload = linkedMapOf<String, Any?>(
                    "sessionId" to sessionId,
                    "runId" to runId,
                    "hypothesisId" to hypothesisId,
                    "location" to location,
                    "msg" to msg,
                    "data" to data,
                    "ts" to System.currentTimeMillis()
                )
                (URL(serverUrl).openConnection() as? HttpURLConnection)?.let { connection ->
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { output -> output.write(objectMapper.writeValueAsBytes(payload)) }
                    runCatching { connection.inputStream.use { it.readBytes() } }
                    connection.disconnect()
                }
            }
        }
        // #endregion

        private fun resolveCreateComboIndicatorColor(comboBox: javax.swing.JComboBox<String>): Color {
            if (!comboBox.isEnabled) {
                return createPrSecondaryTextColor()
            }
            val value = (comboBox.selectedItem as? String).orEmpty().trim()
            val isPlaceholder = comboBox === mergeTypeBox && (value.isBlank() || value == mergeTypeChooseOption)
            return if (isPlaceholder || value.isBlank()) {
                createPrSecondaryTextColor()
            } else {
                createPrPrimaryTextColor()
            }
        }

        private fun selectedCreateBranch(selector: BranchSelectorField): String = selector.getSelectedBranch()

        private fun isCreateBranchEditor(component: Component?): Boolean {
            return sourceBranchBox.containsFocus(component) || targetBranchBox.containsFocus(component)
        }

        private fun clearCreateBranchInputState() {
            sourceBranchBox.hidePopup()
            targetBranchBox.hidePopup()
        }

        private fun bindCreateBackgroundClickDismiss(component: Component) {
            if ((component is CreateInputSurface || component is JBScrollPane || component is JViewport) &&
                findNestedTextComponent(component) != null
            ) {
                return
            }
            when (component) {
                is JButton,
                is JToggleButton,
                is javax.swing.JComboBox<*>,
                is BranchSelectorField,
                is javax.swing.JSpinner,
                is javax.swing.text.JTextComponent,
                is Tree,
                is ReviewerPickerField -> return
            }
            component.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        dismissCreateInputFocus()
                    }
                }
            })
            if (component is Container) {
                component.components.forEach { bindCreateBackgroundClickDismiss(it) }
            }
        }

        private fun dismissCreateInputFocus(): Boolean {
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
            val shouldDismiss = focusOwner === titleField ||
                focusOwner === descField ||
                isCreateBranchEditor(focusOwner) ||
                isReviewerSpinnerEditor(focusOwner) ||
                primaryReviewerPicker.containsFocus(focusOwner) ||
                generalReviewerPicker.containsFocus(focusOwner)
            // #region debug-point C:dismiss-focus
            reportCreateBranchComboDebug("post-fix", "C", "PrManagerPanel.kt:6806", "[DEBUG] dismiss create input focus", mapOf("focusOwner" to focusOwner.javaClass.simpleName, "shouldDismiss" to shouldDismiss, "sourcePopupVisible" to sourceBranchBox.isPopupVisible, "targetPopupVisible" to targetBranchBox.isPopupVisible))
            // #endregion
            if (!shouldDismiss) return false
            if (focusOwner === titleField) {
                titleField.select(titleField.caretPosition, titleField.caretPosition)
            }
            if (focusOwner === descField) {
                descField.select(descField.caretPosition, descField.caretPosition)
            }
            commitReviewerSpinnerEditors()
            clearCreateBranchInputState()
            primaryReviewerPicker.hidePopup()
            generalReviewerPicker.hidePopup()
            if (!createTabs.requestFocusInWindow()) {
                rootPanel.requestFocusInWindow()
            }
            flushCreateTextInputPainting()
            SwingUtilities.invokeLater { flushCreateTextInputPainting() }
            return true
        }

        private fun isReviewerSpinnerEditor(component: Component?): Boolean {
            return component != null && (
                SwingUtilities.isDescendingFrom(component, primaryNumSpinner) ||
                    SwingUtilities.isDescendingFrom(component, generalNumSpinner)
                )
        }

        private fun commitReviewerSpinnerEditors() {
            listOf(primaryNumSpinner, generalNumSpinner).forEach { spinner ->
                runCatching { spinner.commitEdit() }
                (spinner.editor as? javax.swing.JSpinner.DefaultEditor)?.textField?.apply {
                    select(selectionEnd, selectionEnd)
                    caret.isVisible = false
                }
            }
        }

        private fun flushCreateTextInputPainting() {
            repaintCreateTextInputs()
            listOf<JComponent>(titleField, descField).forEach { field ->
                val visibleRect = field.visibleRect
                val textComponent = field as? javax.swing.text.JTextComponent
                textComponent?.caret?.isVisible = false
                textComponent?.caret?.isSelectionVisible = false
                if (field.isShowing && visibleRect.width > 0 && visibleRect.height > 0) {
                    field.repaint(visibleRect.x, visibleRect.y, visibleRect.width, visibleRect.height)
                }
            }
        }

        private fun repaintCreateTextInput(field: JComponent) {
            val visibleRect = field.visibleRect
            val textComponent = field as? javax.swing.text.JTextComponent
            textComponent?.caret?.isVisible = false
            textComponent?.caret?.isSelectionVisible = false
            field.revalidate()
            if (field.isShowing && visibleRect.width > 0 && visibleRect.height > 0) {
                field.repaint(visibleRect.x, visibleRect.y, visibleRect.width, visibleRect.height)
            } else {
                field.repaint()
            }
            (field.parent as? JComponent)?.repaint()
            (SwingUtilities.getAncestorOfClass(JViewport::class.java, field) as? JViewport)?.repaint()
            (SwingUtilities.getAncestorOfClass(CreateInputSurface::class.java, field) as? JComponent)?.repaint()
        }

        private fun repaintCreateTextInputs() {
            listOf<JComponent>(titleField, descField).forEach(::repaintCreateTextInput)
            rootPanel.repaint()
        }

        private inner class CreateInputSurface(
            private val fillColorProvider: () -> Color,
            private val outlineColorProvider: () -> Color,
            private val arc: Int = JBUI.scale(10)
        ) : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                background = fillColorProvider()
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = fillColorProvider()
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }

            override fun paintBorder(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                    g2.color = outlineColorProvider()
                    val lineWidth = JBUI.scale(1f)
                    g2.stroke = BasicStroke(lineWidth)
                    val inset = lineWidth / 2f
                    g2.draw(RoundRectangle2D.Float(
                        inset,
                        inset,
                        width - lineWidth,
                        height - lineWidth,
                        arc.toFloat(),
                        arc.toFloat()
                    ))
                } finally {
                    g2.dispose()
                }
            }
        }

        private fun wrapCreateInput(
            component: JComponent,
            padding: Insets,
            fillColorProvider: () -> Color = ::createPrInputFill,
            allowVerticalExpand: Boolean = false
        ): JComponent {
            return CreateInputSurface(fillColorProvider, ::createPrBorderColor).apply {
                layout = BorderLayout()
                border = JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right)
                maximumSize = Dimension(
                    Int.MAX_VALUE,
                    if (allowVerticalExpand) Int.MAX_VALUE else preferredSize.height.coerceAtLeast(component.preferredSize.height + padding.top + padding.bottom)
                )
                add(component, BorderLayout.CENTER)
                findNestedTextComponent(component)?.let { textComponent ->
                    installTextInputFocusBridge(this, textComponent)
                }
            }
        }

        private fun findNestedTextComponent(component: Component): javax.swing.text.JTextComponent? {
            if (component is javax.swing.text.JTextComponent) return component
            if (component is Container) {
                component.components.forEach { child ->
                    findNestedTextComponent(child)?.let { return it }
                }
            }
            return null
        }

        private fun installTextInputFocusBridge(component: Component, target: javax.swing.text.JTextComponent) {
            if (component === target || component is javax.swing.text.JTextComponent) return
            component.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (!target.isEnabled || !target.isEditable || !target.isFocusable) return
                    SwingUtilities.invokeLater {
                        target.requestFocusInWindow()
                        target.caret.isVisible = true
                        if (target.document.length == 0) {
                            target.caretPosition = 0
                        }
                    }
                }
            })
            if (component is Container) {
                component.components.forEach { child -> installTextInputFocusBridge(child, target) }
            }
        }

        private fun setReviewerCountSpinnerEnabled(spinner: javax.swing.JSpinner, enabled: Boolean) {
            val inputFill = createPrInputFill()
            val textColor = createPrPrimaryTextColor()
            spinner.isEnabled = enabled
            spinner.background = inputFill
            spinner.foreground = textColor
            (spinner.editor as? javax.swing.JSpinner.DefaultEditor)?.textField?.apply {
                isEnabled = enabled
                isEditable = enabled
                isFocusable = enabled
                background = inputFill
                foreground = textColor
                disabledTextColor = textColor
                caretColor = textColor
            }
            spinner.components.filterIsInstance<JComponent>().forEach { child ->
                child.isEnabled = enabled
                child.background = inputFill
                child.foreground = textColor
            }
            spinner.revalidate()
            spinner.repaint()
        }

        private inner class BranchSelectorField(
            private val placeholderText: String
        ) : JPanel(BorderLayout()) {
            var onSelectionChanged: (() -> Unit)? = null

            private val searchOutlineColor = JBColor(Color(0x59B7FF), Color(0x59B7FF))
            private val valueLabel = JBLabel(placeholderText)
            private val arrowLabel = JBLabel()
            private val searchField = JBTextField()
            private val searchWrapper = RoundedOutlinePanel(
                fillColor = searchFieldSurfaceFill(),
                outlineColor = searchOutlineColor,
                arc = JBUI.scale(12)
            ).bindTheme(::searchFieldSurfaceFill, { searchOutlineColor }).apply {
                layout = BorderLayout()
                border = JBUI.Borders.empty(0)
            }
            private val resultListPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = true
                background = detailSurfaceFill()
            }
            private val popupScrollPane = JBScrollPane(resultListPanel).apply {
                isOpaque = true
                border = JBUI.Borders.empty()
                viewportBorder = null
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = JBUI.scale(16)
                background = detailSurfaceFill()
                viewport.isOpaque = true
                viewport.background = detailSurfaceFill()
            }
            private val popupContent = JPanel(BorderLayout()).apply {
                isOpaque = true
                border = JBUI.Borders.empty(8)
                background = detailSurfaceFill()
            }
            private var popup: com.intellij.openapi.ui.popup.JBPopup? = null
            private var allBranches: List<String> = emptyList()
            private var filteredBranches: List<String> = emptyList()
            private var selectedBranch: String = ""
            private var suppressSearchEvents = false

            init {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                valueLabel.border = JBUI.Borders.emptyRight(JBUI.scale(8))
                searchField.border = JBUI.Borders.empty(6, 10)
                searchField.emptyText.text = "搜索分支"
                searchWrapper.add(searchField, BorderLayout.CENTER)
                popupContent.add(searchWrapper, BorderLayout.NORTH)
                popupContent.add(Box.createVerticalStrut(JBUI.scale(8)), BorderLayout.CENTER)
                popupContent.add(popupScrollPane, BorderLayout.SOUTH)
                add(valueLabel, BorderLayout.CENTER)
                add(arrowLabel, BorderLayout.EAST)

                val openPopupListener = object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (!this@BranchSelectorField.isEnabled) return
                        if (!SwingUtilities.isLeftMouseButton(e)) return
                        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                        val switchingFromTextInput = focusOwner === titleField || focusOwner === descField
                        if (switchingFromTextInput) {
                            dismissCreateInputFocus()
                            SwingUtilities.invokeLater { openPopup() }
                            return
                        }
                        dismissCreateInputFocus()
                        openPopup()
                    }
                }
                addMouseListener(openPopupListener)
                valueLabel.addMouseListener(openPopupListener)
                arrowLabel.addMouseListener(openPopupListener)
                searchField.document.addDocumentListener(object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = if (suppressSearchEvents) Unit else applyFilter()
                    override fun removeUpdate(e: DocumentEvent?) = if (suppressSearchEvents) Unit else applyFilter()
                    override fun changedUpdate(e: DocumentEvent?) = if (suppressSearchEvents) Unit else applyFilter()
                })
                searchField.addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        when (e.keyCode) {
                            KeyEvent.VK_ENTER -> filteredBranches.firstOrNull()?.let { selectBranch(it, true) }
                            KeyEvent.VK_ESCAPE -> hidePopup()
                        }
                    }
                })
                refreshDisplay()
                refreshPopupResults()
            }

            val isPopupVisible: Boolean
                get() = popup != null

            fun configureField(fontSize: Float) {
                valueLabel.font = valueLabel.font.deriveFont(Font.PLAIN, fontSize)
                searchField.font = searchField.font.deriveFont(Font.PLAIN, fontSize)
            }

            fun applyTheme() {
                val enabled = isEnabled
                valueLabel.foreground = when {
                    !enabled -> createPrSecondaryTextColor()
                    selectedBranch.isBlank() -> createPrSecondaryTextColor()
                    else -> createPrPrimaryTextColor()
                }
                arrowLabel.icon = createArrowIcon()
                searchField.background = searchFieldSurfaceFill()
                searchField.foreground = createPrPrimaryTextColor()
                searchField.caretColor = createPrPrimaryTextColor()
                searchWrapper.updateColors(searchFieldSurfaceFill(), searchOutlineColor)
                resultListPanel.background = detailSurfaceFill()
                popupContent.background = detailSurfaceFill()
                popupScrollPane.background = detailSurfaceFill()
                popupScrollPane.viewport.background = detailSurfaceFill()
                refreshPopupResults()
                refreshDisplay()
            }

            fun setAvailableBranches(branches: List<String>) {
                allBranches = branches
                filteredBranches = branches
                if (selectedBranch.isNotBlank() && selectedBranch !in allBranches) {
                    selectedBranch = ""
                }
                applyFilter("")
                refreshDisplay()
            }

            fun setSelectedBranch(branch: String?, notifyChange: Boolean = false) {
                val normalized = branch?.trim().orEmpty()
                val next = normalized.takeIf { it.isNotBlank() && (allBranches.isEmpty() || it in allBranches) }.orEmpty()
                if (selectedBranch == next) {
                    refreshDisplay()
                    return
                }
                selectedBranch = next
                refreshDisplay()
                if (notifyChange) {
                    onSelectionChanged?.invoke()
                }
            }

            fun getSelectedBranch(): String = selectedBranch

            fun containsFocus(component: Component?): Boolean {
                return component != null && (
                    SwingUtilities.isDescendingFrom(component, this) ||
                        SwingUtilities.isDescendingFrom(component, popupContent)
                    )
            }

            fun hidePopup() {
                popup?.cancel()
            }

            private fun openPopup() {
                if (!isEnabled) return
                clearSearchKeyword()
                applyFilter("")
                ensurePopupVisible()
                SwingUtilities.invokeLater {
                    searchField.requestFocusInWindow()
                    searchField.selectAll()
                }
            }

            private fun ensurePopupVisible() {
                if (!isShowing || popup != null) return
                updatePopupSize()
                popup = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(popupContent, searchField)
                    .setRequestFocus(true)
                    .setResizable(false)
                    .setMovable(false)
                    .setCancelOnClickOutside(true)
                    .setCancelOnWindowDeactivation(true)
                    .setShowBorder(false)
                    .setShowShadow(true)
                    .createPopup()
                popup?.addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        popup = null
                        clearSearchKeyword()
                    }
                })
                popup?.show(RelativePoint.getSouthWestOf(this))
            }

            private fun applyFilter(keywordOverride: String? = null) {
                val keyword = (keywordOverride ?: searchField.text).trim()
                filteredBranches = if (keyword.isBlank()) {
                    allBranches
                } else {
                    allBranches.filter { it.contains(keyword, ignoreCase = true) }
                }
                refreshPopupResults()
            }

            private fun refreshPopupResults() {
                resultListPanel.removeAll()
                if (filteredBranches.isEmpty()) {
                    resultListPanel.add(buildHintRow("未找到匹配分支"))
                } else {
                    filteredBranches.forEach { branch ->
                        resultListPanel.add(buildBranchRow(branch))
                    }
                }
                updatePopupSize()
                resultListPanel.revalidate()
                resultListPanel.repaint()
                popupContent.revalidate()
                popupContent.repaint()
            }

            private fun buildHintRow(text: String): JComponent {
                val rowHeight = JBUI.scale(36)
                return JPanel(BorderLayout()).apply {
                    isOpaque = true
                    background = detailSurfaceFill()
                    border = JBUI.Borders.empty(8, 10)
                    add(JBLabel(text).apply {
                        foreground = createPrSecondaryTextColor()
                        font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
                    }, BorderLayout.CENTER)
                    preferredSize = Dimension(JBUI.scale(280), rowHeight)
                    minimumSize = Dimension(0, rowHeight)
                    maximumSize = Dimension(Int.MAX_VALUE, rowHeight)
                }
            }

            private fun buildBranchRow(branch: String): JComponent {
                val selected = branch == selectedBranch
                val rowHeight = JBUI.scale(36)
                return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = true
                    background = if (selected) withAlpha(detailAccentColor, if (UIUtil.isUnderDarcula()) 52 else 28) else detailSurfaceFill()
                    border = JBUI.Borders.empty(8, 10)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    add(JBLabel(branch).apply {
                        foreground = createPrPrimaryTextColor()
                        font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
                    }, BorderLayout.CENTER)
                    add(JBLabel(if (selected) "已选中" else "").apply {
                        foreground = detailAccentColor
                        font = font.deriveFont(Font.PLAIN, globalUiFontSize - 2f)
                        isVisible = selected
                    }, BorderLayout.EAST)
                    preferredSize = Dimension(JBUI.scale(280), rowHeight)
                    minimumSize = Dimension(0, rowHeight)
                    maximumSize = Dimension(Int.MAX_VALUE, rowHeight)
                    addMouseListener(object : MouseAdapter() {
                        override fun mousePressed(e: MouseEvent) {
                            if (!SwingUtilities.isLeftMouseButton(e)) return
                            selectBranch(branch, true)
                        }
                    })
                }
            }

            private fun selectBranch(branch: String, notifyChange: Boolean) {
                setSelectedBranch(branch, notifyChange)
                hidePopup()
                if (!createTabs.requestFocusInWindow()) {
                    rootPanel.requestFocusInWindow()
                }
            }

            private fun clearSearchKeyword() {
                suppressSearchEvents = true
                try {
                    if (searchField.text.isNotEmpty()) {
                        searchField.text = ""
                    }
                } finally {
                    suppressSearchEvents = false
                }
            }

            private fun refreshDisplay() {
                valueLabel.text = if (selectedBranch.isBlank()) placeholderText else selectedBranch
                valueLabel.foreground = when {
                    !isEnabled -> createPrSecondaryTextColor()
                    selectedBranch.isBlank() -> createPrSecondaryTextColor()
                    else -> createPrPrimaryTextColor()
                }
                arrowLabel.icon = createArrowIcon()
                cursor = if (isEnabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                repaint()
            }

            override fun setEnabled(enabled: Boolean) {
                super.setEnabled(enabled)
                if (!enabled) {
                    hidePopup()
                }
                refreshDisplay()
            }

            private fun updatePopupSize() {
                val rowHeight = JBUI.scale(36)
                val popupWidth = width.coerceAtLeast(JBUI.scale(280))
                val bodyHeight = (maxOf(1, minOf(5, maxOf(filteredBranches.size, 1))) * rowHeight)
                popupScrollPane.preferredSize = Dimension(popupWidth, bodyHeight)
                popupScrollPane.minimumSize = Dimension(popupWidth, rowHeight)
                popupContent.preferredSize = Dimension(popupWidth, bodyHeight + searchField.preferredSize.height + JBUI.scale(26))
                popupContent.minimumSize = popupContent.preferredSize
                popup?.setSize(popupContent.preferredSize)
            }

            private fun createArrowIcon(): Icon {
                val color = if (selectedBranch.isBlank()) createPrSecondaryTextColor() else createPrPrimaryTextColor()
                val size = JBUI.scale(12)
                return object : Icon {
                    override fun getIconWidth(): Int = size
                    override fun getIconHeight(): Int = size

                    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                        val g2 = g.create() as Graphics2D
                        try {
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            g2.color = color
                            g2.stroke = BasicStroke(JBUI.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                            val centerX = x + size / 2f
                            val centerY = y + size / 2f
                            val half = JBUI.scale(3).toFloat()
                            g2.drawLine((centerX - half).toInt(), (centerY - 1).toInt(), centerX.toInt(), (centerY + 2).toInt())
                            g2.drawLine(centerX.toInt(), (centerY + 2).toInt(), (centerX + half).toInt(), (centerY - 1).toInt())
                        } finally {
                            g2.dispose()
                        }
                    }
                }
            }
        }

        private fun createTextAreaScroll(area: JBTextArea, preferredHeight: Int): JComponent {
            val inputFill = createPrInputFill()
            return JBScrollPane(area).apply {
                border = JBUI.Borders.empty()
                viewportBorder = null
                isOpaque = true
                background = inputFill
                viewport.isOpaque = true
                viewport.background = inputFill
                preferredSize = Dimension(0, JBUI.scale(preferredHeight))
                minimumSize = Dimension(0, JBUI.scale(preferredHeight))
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                installTextInputFocusBridge(this, area)
                installTextInputFocusBridge(viewport, area)
            }
        }

        private inner class ReviewerPickerField(
            private val placeholderText: String,
            private val onSelectionChanged: () -> Unit
        ) : JPanel(BorderLayout()) {
            private val localSelectedCandidates = linkedMapOf<Long, DeveloperCandidate>()
            private val mirroredSelectedCandidates = linkedMapOf<Long, DeveloperCandidate>()
            private val selectedCandidates = linkedMapOf<Long, DeveloperCandidate>()
            private val mandatoryLockedCandidateIds = mutableSetOf<Long>()
            private var selectionEditable = true
            private var inputModeActive = false
            private val searchOutlineColor = JBColor(Color(0x59B7FF), Color(0x59B7FF))
            private val editorField = JBTextField()
            private val inactiveInputHint = JBLabel()
            private val chipContainer = JPanel(object : FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6)) {
                override fun layoutContainer(target: Container) {
                    super.layoutContainer(target)
                    val visibleComponents = target.components.filter { it.isVisible }
                    if (visibleComponents.isEmpty()) return
                    val insets = target.insets
                    val minY = visibleComponents.minOf { it.y }
                    val maxBottom = visibleComponents.maxOf { it.y + it.height }
                    val contentHeight = maxBottom - minY
                    val availableHeight = target.height - insets.top - insets.bottom
                    if (contentHeight <= 0 || availableHeight <= 0) return
                    val centeredY = insets.top + ((availableHeight - contentHeight) / 2)
                    val offsetY = centeredY - minY
                    if (offsetY != 0) {
                        visibleComponents.forEach { component ->
                            component.setLocation(component.x, component.y + offsetY)
                        }
                    }
                }
            }).apply {
                isOpaque = true
                isDoubleBuffered = true
                background = createPrInputFill()
                alignmentX = Component.LEFT_ALIGNMENT
            }
            private val resultListPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = true
                background = detailSurfaceFill()
            }
            private val searchWrapper = RoundedOutlinePanel(
                fillColor = searchFieldSurfaceFill(),
                outlineColor = searchOutlineColor,
                arc = JBUI.scale(12)
            ).bindTheme(::searchFieldSurfaceFill, { searchOutlineColor }).apply {
                layout = BorderLayout()
                border = JBUI.Borders.empty(0)
            }
            private val popupContent = JPanel(BorderLayout()).apply {
                isOpaque = true
                border = JBUI.Borders.empty(8)
                background = detailSurfaceFill()
            }
            private val popupScrollPane = JBScrollPane(resultListPanel).apply {
                isOpaque = true
                border = JBUI.Borders.empty()
                viewportBorder = null
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = JBUI.scale(16)
                preferredSize = Dimension(JBUI.scale(320), JBUI.scale(220))
                background = detailSurfaceFill()
                viewport.isOpaque = true
                viewport.background = detailSurfaceFill()
            }
            private var popup: com.intellij.openapi.ui.popup.JBPopup? = null
            private var searchTimer: javax.swing.Timer? = null
            private var requestToken = 0
            private var currentKeyword = ""
            private var loading = false
            private var errorMessage: String? = null
            private var currentResults: List<DeveloperCandidate> = emptyList()
            private var suppressEditorDocumentEvents = false

            init {
                isOpaque = true
                isDoubleBuffered = true
                background = createPrInputFill()
                editorField.isOpaque = false
                editorField.border = JBUI.Borders.empty(6, 10)
                editorField.emptyText.text = "输入姓名或 OA 搜索评审人"
                editorField.columns = 12
                inactiveInputHint.border = JBUI.Borders.empty(4, 2)
                inactiveInputHint.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                add(chipContainer, BorderLayout.CENTER)
                popupScrollPane.setViewportView(resultListPanel)
                searchWrapper.add(editorField, BorderLayout.CENTER)
                popupContent.add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyBottom(8)
                    add(searchWrapper, BorderLayout.CENTER)
                }, BorderLayout.NORTH)
                popupContent.add(popupScrollPane, BorderLayout.CENTER)
                popupContent.preferredSize = popupScrollPane.preferredSize
                popupContent.minimumSize = popupScrollPane.preferredSize
                refreshPopupResults()

                val openPopupListener = object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(e)) return
                        if (!selectionEditable) return
                        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                        val switchingFromTextInput = focusOwner === titleField || focusOwner === descField
                        if (switchingFromTextInput) {
                            dismissCreateInputFocus()
                            SwingUtilities.invokeLater {
                                openReviewerPopup()
                            }
                            return
                        }
                        dismissCreateInputFocus()
                        openReviewerPopup()
                    }
                }
                addMouseListener(openPopupListener)
                chipContainer.addMouseListener(openPopupListener)
                inactiveInputHint.addMouseListener(openPopupListener)
                editorField.document.addDocumentListener(object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = if (suppressEditorDocumentEvents) Unit else queueSearch()
                    override fun removeUpdate(e: DocumentEvent?) = if (suppressEditorDocumentEvents) Unit else queueSearch()
                    override fun changedUpdate(e: DocumentEvent?) = if (suppressEditorDocumentEvents) Unit else queueSearch()
                })
                editorField.addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        when (e.keyCode) {
                            KeyEvent.VK_ENTER -> currentResults.firstOrNull()?.let { candidate ->
                                if (!isCandidateLocked(candidate.id) && selectionEditable) {
                                    localSelectedCandidates[candidate.id] = candidate
                                    rebuildSelectedCandidates()
                                    clearSearchKeyword()
                                    renderChips()
                                    refreshPopupResults()
                                    onSelectionChanged()
                                    editorField.requestFocusInWindow()
                                }
                            }
                            KeyEvent.VK_ESCAPE -> hidePopup()
                        }
                    }
                })
                updateEditorInputMode()
            }

            fun configureEditor(fontSize: Float) {
                editorField.font = editorField.font.deriveFont(fontSize)
                updateInputComponentMetrics()
            }

            fun setSelectionEditable(editable: Boolean) {
                selectionEditable = editable
                if (!editable) {
                    hidePopup()
                }
                if (!editable) {
                    inputModeActive = false
                }
                updateEditorInputMode()
                renderChips()
                refreshPopupResults()
            }

            fun applyTheme() {
                background = createPrInputFill()
                chipContainer.background = createPrInputFill()
                editorField.background = searchFieldSurfaceFill()
                editorField.foreground = createPrPrimaryTextColor()
                editorField.caretColor = createPrPrimaryTextColor()
                inactiveInputHint.foreground = createPrSecondaryTextColor()
                inactiveInputHint.font = editorField.font
                searchWrapper.updateColors(searchFieldSurfaceFill(), searchOutlineColor)
                updateInputComponentMetrics()
                resultListPanel.background = detailSurfaceFill()
                popupContent.background = detailSurfaceFill()
                popupScrollPane.background = detailSurfaceFill()
                popupScrollPane.isOpaque = true
                popupScrollPane.viewport.isOpaque = true
                popupScrollPane.viewport.background = detailSurfaceFill()
                renderChips()
                refreshPopupResults()
            }

            fun clearSelection() {
                localSelectedCandidates.clear()
                mirroredSelectedCandidates.clear()
                selectedCandidates.clear()
                mandatoryLockedCandidateIds.clear()
                inputModeActive = false
                currentKeyword = ""
                currentResults = emptyList()
                errorMessage = null
                loading = false
                requestToken++
                clearSearchKeyword()
                hidePopup()
                renderChips()
            }

            fun setSelectedCandidates(items: List<DeveloperCandidate>, lockedIds: Set<Long> = emptySet()) {
                localSelectedCandidates.clear()
                mirroredSelectedCandidates.clear()
                selectedCandidates.clear()
                mandatoryLockedCandidateIds.clear()
                inputModeActive = false
                currentResults = emptyList()
                errorMessage = null
                loading = false
                requestToken++
                items.distinctBy { it.id }.forEach { localSelectedCandidates[it.id] = it }
                mandatoryLockedCandidateIds.addAll(lockedIds)
                rebuildSelectedCandidates()
                clearSearchKeyword()
                renderChips()
                refreshPopupResults()
                onSelectionChanged()
            }

            fun getSelectedCandidates(): List<DeveloperCandidate> = localSelectedCandidates.values.toList()

            fun syncMirroredCandidates(items: List<DeveloperCandidate>) {
                val newMirrored = linkedMapOf<Long, DeveloperCandidate>()
                items.distinctBy { it.id }.forEach { candidate ->
                    if (!localSelectedCandidates.containsKey(candidate.id)) {
                        newMirrored[candidate.id] = candidate
                    }
                }
                if (mirroredSelectedCandidates.keys == newMirrored.keys && mirroredSelectedCandidates.values.toList() == newMirrored.values.toList()) {
                    return
                }
                mirroredSelectedCandidates.clear()
                mirroredSelectedCandidates.putAll(newMirrored)
                rebuildSelectedCandidates()
                refreshPopupResults()
            }

            fun containsFocus(component: Component?): Boolean {
                return component != null && (
                    SwingUtilities.isDescendingFrom(component, this) ||
                        SwingUtilities.isDescendingFrom(component, popupContent)
                    )
            }

            fun hidePopup() {
                val currentPopup = popup
                if (currentPopup != null) {
                    currentPopup.cancel()
                } else {
                    clearSearchKeyword()
                    deactivateInputMode()
                }
            }

            private fun openReviewerPopup() {
                ensurePopupVisible()
                SwingUtilities.invokeLater {
                    editorField.requestFocusInWindow()
                    editorField.selectAll()
                }
                if (currentResults.isEmpty() && !loading) {
                    queueSearch(immediate = true)
                }
            }

            private fun ensurePopupVisible() {
                if (!isShowing || popup != null) return
                popupContent.revalidate()
                popupContent.repaint()
                popup = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(popupContent, editorField)
                    .setRequestFocus(true)
                    .setResizable(false)
                    .setMovable(false)
                    .setCancelOnClickOutside(true)
                    .setCancelOnWindowDeactivation(true)
                    .setShowBorder(false)
                    .setShowShadow(true)
                    .createPopup()
                popup?.addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        popup = null
                        clearSearchKeyword()
                        deactivateInputMode()
                    }
                })
                popup?.show(RelativePoint.getSouthWestOf(this))
            }

            private fun queueSearch(immediate: Boolean = false) {
                val keyword = editorField.text.trim()
                currentKeyword = keyword
                if (immediate) {
                    searchTimer?.stop()
                    performSearch(keyword)
                    return
                }
                if (searchTimer == null) {
                    searchTimer = javax.swing.Timer(220) {
                        performSearch(currentKeyword)
                    }.apply {
                        isRepeats = false
                    }
                }
                searchTimer?.restart()
            }

            private fun performSearch(keyword: String) {
                loading = true
                errorMessage = null
                currentKeyword = keyword
                refreshPopupResults()
                ensurePopupVisible()
                val token = ++requestToken
                ApplicationManager.getApplication().executeOnPooledThread {
                    val result = runCatching { loadDevelopersFromApi(keyword) }
                    SwingUtilities.invokeLater {
                        if (token != requestToken) return@invokeLater
                        loading = false
                        result.onSuccess { items ->
                            indexDevelopers(items)
                            currentResults = excludeCreatorCandidates(
                                (selectedCandidates.values + items).distinctBy { it.id }
                            )
                            errorMessage = null
                        }.onFailure { throwable ->
                            currentResults = excludeCreatorCandidates(selectedCandidates.values.toList())
                            errorMessage = throwable.message ?: "人员查询失败"
                        }
                        refreshPopupResults()
                    }
                }
            }

            private fun refreshPopupResults() {
                resultListPanel.removeAll()
                val results = currentResults
                when {
                    loading -> {
                        resultListPanel.add(buildPopupHintLabel("正在查询人员..."))
                    }
                    !errorMessage.isNullOrBlank() -> {
                        resultListPanel.add(buildPopupHintLabel(errorMessage.orEmpty()))
                    }
                    results.isEmpty() && currentKeyword.isBlank() -> {
                        resultListPanel.add(buildPopupHintLabel("输入姓名或 OA 搜索评审人"))
                    }
                    results.isEmpty() -> {
                        resultListPanel.add(buildPopupHintLabel("未找到匹配人员"))
                    }
                    else -> {
                        results.forEach { resultListPanel.add(buildPopupRow(it)) }
                    }
                }
                updatePopupSize()
                resultListPanel.revalidate()
                resultListPanel.repaint()
                popupContent.revalidate()
                popupContent.repaint()
            }

            private fun buildPopupHintLabel(text: String): JComponent {
                val label = JBLabel(text).apply {
                    foreground = createPrSecondaryTextColor()
                    font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
                    border = JBUI.Borders.empty(6, 8)
                }
                return JPanel(BorderLayout()).apply {
                    isOpaque = true
                    background = detailSurfaceFill()
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.empty(2, 0)
                    add(label, BorderLayout.CENTER)
                    val rowHeight = JBUI.scale(34)
                    preferredSize = Dimension(JBUI.scale(280), rowHeight)
                    minimumSize = Dimension(0, rowHeight)
                    maximumSize = Dimension(Int.MAX_VALUE, rowHeight)
                }
            }

            private fun buildPopupRow(candidate: DeveloperCandidate): JComponent {
                val isLocked = isCandidateLocked(candidate.id)
                val checkBox = JBCheckBox().apply {
                    isOpaque = false
                    isSelected = selectedCandidates.containsKey(candidate.id)
                    isEnabled = selectionEditable && !isLocked
                }
                val displayName = if (candidate.name.isNotBlank() && candidate.name != candidate.username) {
                    "${candidate.name}（${candidate.username}）"
                } else {
                    candidate.username
                }
                val nameLabel = JBLabel(displayName).apply {
                    foreground = createPrPrimaryTextColor()
                    font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
                }
                val row = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = true
                    background = detailSurfaceFill()
                    border = JBUI.Borders.empty(4, 8)
                    alignmentX = Component.LEFT_ALIGNMENT
                    cursor = if (selectionEditable && !isLocked) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                    add(checkBox, BorderLayout.WEST)
                    add(nameLabel, BorderLayout.CENTER)
                    val rowHeight = JBUI.scale(34)
                    preferredSize = Dimension(JBUI.scale(280), rowHeight)
                    minimumSize = Dimension(0, rowHeight)
                    maximumSize = Dimension(Int.MAX_VALUE, rowHeight)
                }
                val toggleSelection = {
                    if (isLocked) {
                        checkBox.isSelected = true
                    } else if (checkBox.isSelected) {
                        localSelectedCandidates[candidate.id] = candidate
                    } else {
                        localSelectedCandidates.remove(candidate.id)
                    }
                    rebuildSelectedCandidates()
                    clearSearchKeyword()
                    renderChips()
                    refreshPopupResults()
                    onSelectionChanged()
                    editorField.requestFocusInWindow()
                }
                checkBox.addActionListener { toggleSelection() }
                row.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(e)) return
                        if (!selectionEditable || isLocked) return
                        checkBox.isSelected = !checkBox.isSelected
                        toggleSelection()
                    }
                })
                return row
            }

            private fun renderChips() {
                chipContainer.removeAll()
                localSelectedCandidates.values.forEach { candidate ->
                    val displayName = if (candidate.name.isNotBlank() && candidate.name != candidate.username) {
                        "${candidate.name}（${candidate.username}）"
                    } else {
                        candidate.username
                    }
                    val chip = RoundedOutlinePanel(
                        fillColor = withAlpha(detailAccentColor, if (UIUtil.isUnderDarcula()) 36 else 20),
                        outlineColor = withAlpha(detailAccentColor, if (UIUtil.isUnderDarcula()) 108 else 88),
                        arc = JBUI.scale(14)
                    ).apply {
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        alignmentY = Component.CENTER_ALIGNMENT
                        border = JBUI.Borders.empty(4, 8, 4, 4)
                        add(JBLabel(displayName).apply {
                            foreground = createPrPrimaryTextColor()
                            font = font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
                        })
                        if (selectionEditable && !isCandidateLocked(candidate.id)) {
                            add(Box.createHorizontalStrut(JBUI.scale(4)))
                            add(JButton("×").apply {
                                isOpaque = false
                                isContentAreaFilled = false
                                isBorderPainted = false
                                isFocusable = false
                                border = JBUI.Borders.empty()
                                margin = JBUI.emptyInsets()
                                preferredSize = Dimension(JBUI.scale(12), JBUI.scale(12))
                                minimumSize = preferredSize
                                maximumSize = preferredSize
                                foreground = createPrSecondaryTextColor()
                                font = font.deriveFont(Font.PLAIN, globalUiFontSize - 3f)
                                addActionListener {
                                    localSelectedCandidates.remove(candidate.id)
                                    rebuildSelectedCandidates()
                                    renderChips()
                                    refreshPopupResults()
                                    onSelectionChanged()
                                    hidePopup()
                                    if (!createTabs.requestFocusInWindow()) {
                                        rootPanel.requestFocusInWindow()
                                    }
                                }
                            })
                        }
                    }
                    chipContainer.add(chip)
                }
                if (selectionEditable) {
                    inactiveInputHint.alignmentY = Component.CENTER_ALIGNMENT
                    inactiveInputHint.text = if (localSelectedCandidates.isEmpty()) placeholderText else ""
                    chipContainer.add(inactiveInputHint)
                }
                updateEditorInputMode()
                chipContainer.revalidate()
                chipContainer.repaint()
                revalidate()
                repaint()
            }

            private fun activateInputMode() {
                if (!selectionEditable || inputModeActive) return
                inputModeActive = true
                updateEditorInputMode()
                chipContainer.revalidate()
                chipContainer.repaint()
            }

            private fun deactivateInputMode() {
                if (!inputModeActive) return
                inputModeActive = false
                updateEditorInputMode()
                chipContainer.revalidate()
                chipContainer.repaint()
            }

            private fun updateEditorInputMode() {
                val active = selectionEditable
                editorField.isEnabled = selectionEditable
                editorField.isEditable = active
                editorField.isFocusable = active
                editorField.cursor = if (selectionEditable) Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR) else Cursor.getDefaultCursor()
                inactiveInputHint.cursor = if (selectionEditable) Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR) else Cursor.getDefaultCursor()
                updateInputComponentMetrics()
            }

            private fun updateInputComponentMetrics() {
                val inputWidth = JBUI.scale(120)
                val inputHeight = maxOf(editorField.preferredSize.height, JBUI.scale(24))
                val inputSize = Dimension(inputWidth, inputHeight)
                editorField.minimumSize = inputSize
                editorField.preferredSize = Dimension(JBUI.scale(280), inputHeight)
                inactiveInputHint.minimumSize = inputSize
                inactiveInputHint.preferredSize = inputSize
                val containerHeight = inputHeight + JBUI.scale(6)
                minimumSize = Dimension(0, containerHeight)
                preferredSize = Dimension(inputWidth, containerHeight)
            }

            private fun rebuildSelectedCandidates() {
                selectedCandidates.clear()
                localSelectedCandidates.values.forEach { selectedCandidates[it.id] = it }
                mirroredSelectedCandidates.values.forEach { selectedCandidates.putIfAbsent(it.id, it) }
            }

            private fun isCandidateLocked(candidateId: Long): Boolean {
                return candidateId in mandatoryLockedCandidateIds || candidateId in mirroredSelectedCandidates
            }

            private fun clearSearchKeyword() {
                currentKeyword = ""
                suppressEditorDocumentEvents = true
                try {
                    if (editorField.text.isNotEmpty()) {
                        editorField.text = ""
                    }
                } finally {
                    suppressEditorDocumentEvents = false
                }
            }

            private fun updatePopupSize() {
                val rowHeight = JBUI.scale(34)
                val popupWidth = maxOf((width * 2) / 3, JBUI.scale(214))
                val maxBodyHeight = JBUI.scale(220)
                val bodyHeight = (maxOf(1, resultListPanel.componentCount) * rowHeight).coerceAtMost(maxBodyHeight)
                popupScrollPane.preferredSize = Dimension(popupWidth, bodyHeight)
                popupScrollPane.minimumSize = Dimension(popupWidth, rowHeight)
                popupContent.preferredSize = Dimension(popupWidth, bodyHeight + editorField.preferredSize.height + JBUI.scale(24))
                popupContent.minimumSize = popupContent.preferredSize
                popup?.setSize(popupContent.preferredSize)
            }
        }

        private fun installCreateTabsUi() {
            createTabs.setUI(object : BasicTabbedPaneUI() {
                override fun installDefaults() {
                    super.installDefaults()
                    tabAreaInsets = Insets(0, 0, 0, 0)
                    contentBorderInsets = Insets(0, 0, 0, 0)
                    selectedTabPadInsets = Insets(0, 0, 0, 0)
                }

                override fun getTabInsets(tabPlacement: Int, tabIndex: Int): Insets = Insets(0, 0, 0, 0)

                override fun getTabLabelShiftX(tabPlacement: Int, tabIndex: Int, isSelected: Boolean): Int = 0

                override fun getTabLabelShiftY(tabPlacement: Int, tabIndex: Int, isSelected: Boolean): Int = 0

                override fun paintTabArea(g: Graphics, tabPlacement: Int, selectedIndex: Int) {
                    val g2 = g.create() as Graphics2D
                    try {
                        val tabAreaHeight = calculateTabAreaHeight(tabPlacement, runCount, maxTabHeight)
                        g2.color = detailTabPaneFill()
                        g2.fillRect(0, 0, tabPane.width, tabAreaHeight)
                    } finally {
                        g2.dispose()
                    }
                    super.paintTabArea(g, tabPlacement, selectedIndex)
                }

                override fun paintTabBackground(g: Graphics, tabPlacement: Int, tabIndex: Int, x: Int, y: Int, w: Int, h: Int, isSelected: Boolean) = Unit
                override fun paintTabBorder(g: Graphics, tabPlacement: Int, tabIndex: Int, x: Int, y: Int, w: Int, h: Int, isSelected: Boolean) = Unit
                override fun paintContentBorder(g: Graphics, tabPlacement: Int, selectedIndex: Int) = Unit
                override fun paintFocusIndicator(g: Graphics, tabPlacement: Int, rects: Array<Rectangle>, tabIndex: Int, iconRect: Rectangle, textRect: Rectangle, isSelected: Boolean) = Unit
            })
        }

        private fun createTabUnderlineSideInsets(): Pair<Int, Int> {
            val overviewComponent = if (createTabs.tabCount > 0) createTabs.getComponentAt(0) else null
            return detailContentSideInsets(overviewComponent)
        }

        private fun setupCreateTabsHeader() {
            createTabHeaders.clear()
            val diffMeta = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(diffTabCountLabel)
                add(createFileChangeWarningButton)
            }
            val commitMeta = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(commitTabCountLabel)
                add(createCommitWarningLabel)
            }
            val headers = listOf(
                CreateTabHeader(JBLabel("概览"), null, isFirst = true),
                CreateTabHeader(JBLabel("文件改动"), diffMeta),
                CreateTabHeader(JBLabel("提交记录"), commitMeta, isLast = true)
            )
            headers.forEach { header ->
                header.titleFontSize = detailSectionTitleFontSize()
            }
            headers.forEachIndexed { index, header ->
                createTabs.setTabComponentAt(index, header)
                createTabHeaders.add(header)
            }
            if (!createTabHeaderListenerBound) {
                createTabs.addChangeListener {
                    updateCreateTabHeaderStates()
                    refreshCreateScrollForActiveTab()
                }
                createTabHeaderListenerBound = true
            }
            updateCreateTabHeaderStates()
        }

        private fun updateCreateTabHeaderStates() {
            createTabHeaders.forEachIndexed { index, header ->
                header.setSelectedState(index == createTabs.selectedIndex)
            }
        }

        private fun bindActions() {
            cancelButton.addActionListener { exitCreatePrView() }
            submitButton.addActionListener { submit() }
            sourceBranchBox.onSelectionChanged = { triggerBranchRefresh() }
            targetBranchBox.onSelectionChanged = { triggerBranchRefresh() }

            createChangeSearchField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = applyCreateChangeTreeFilter()
                override fun removeUpdate(e: DocumentEvent?) = applyCreateChangeTreeFilter()
                override fun changedUpdate(e: DocumentEvent?) = applyCreateChangeTreeFilter()
            })
            createChangeTreeToggleButton.addActionListener {
                if (createChangeTreeFlatMode) {
                    createChangeTreeFlatMode = false
                    updateCreateChangeModeToggleStyle()
                    applyCreateChangeTreeFilter()
                }
            }
            createChangeFlatToggleButton.addActionListener {
                if (!createChangeTreeFlatMode) {
                    createChangeTreeFlatMode = true
                    updateCreateChangeModeToggleStyle()
                    applyCreateChangeTreeFilter()
                }
            }
            createChangeTree.addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val path = resolveTreePathAtPoint(createChangeTree, e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val change = node.userObject as? ChangeItem
                    createChangeTree.toolTipText = change?.let { changeTypeTooltip(it) }
                }
            })
            createChangeTree.addMouseListener(object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    createChangeTree.toolTipText = null
                }

                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        dismissCreateInputFocus()
                        dismissSearchFieldFocus()
                        resolveTreePathAtPoint(createChangeTree, e.x, e.y)?.let { createChangeTree.selectionPath = it }
                    }
                    showCreateChangeTreePopup(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    showCreateChangeTreePopup(e)
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                    val path = resolveTreePathAtPoint(createChangeTree, e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    if (node.userObject !is String || createChangeTreeFlatMode) return
                    if (createChangeTree.isExpanded(path)) createChangeTree.collapsePath(path) else createChangeTree.expandPath(path)
                }
            })
        }

        private fun openCreateDiff(change: ChangeItem) {
            val sourceBranch = selectedCreateBranch(sourceBranchBox)
            val targetBranch = selectedCreateBranch(targetBranchBox)
            if (sourceBranch.isBlank() || targetBranch.isBlank()) return

            val baseRef = ensureOriginBranch(targetBranch)
            val headRef = ensureOriginBranch(sourceBranch)
            PrManagerFileLogger.info("Open create diff: file=${change.filePath} base=$baseRef head=$headRef")

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val sourceContent = branchService.loadFileContent(headRef, change.filePath)
                    val targetContent = branchService.loadFileContent(baseRef, change.filePath)
                    if (sourceContent == null && targetContent == null) {
                        updateStatus("无法加载文件内容")
                        PrManagerFileLogger.warn("Open create diff failed, content empty: file=${change.filePath}")
                        return@executeOnPooledThread
                    }

                    SwingUtilities.invokeLater {
                        try {
                            if (project.isDisposed) return@invokeLater
                            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(change.filePath)
                            val contentFactory = DiffContentFactory.getInstance()
                            val left = contentFactory.create(project, targetContent ?: "", fileType)
                            val right = contentFactory.create(project, sourceContent ?: "", fileType)
                            val request = SimpleDiffRequest(
                                "${change.filePath} ($baseRef..$headRef)",
                                left,
                                right,
                                baseRef,
                                headRef
                            )
                            DiffManager.getInstance().showDiff(project, request)
                        } catch (e: Exception) {
                            PrManagerFileLogger.error("Open create diff error on UI thread: file=${change.filePath}", e)
                            updateStatus("打开Diff失败: ${e.message ?: "未知错误"}")
                        }
                    }
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Open create diff error: file=${change.filePath}", e)
                    updateStatus("打开Diff失败: ${e.message ?: "未知错误"}")
                }
            }
        }

        private fun showCreateChangeTreePopup(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            val path = resolveTreePathAtPoint(createChangeTree, e.x, e.y) ?: return
            createChangeTree.selectionPath = path
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
            if (node.userObject !is String) return

            val menu = JPopupMenu()
            val expandItem = JMenuItem("展开目录")
            expandItem.addActionListener { expandCreateChangeTree(path) }
            menu.add(expandItem)
            menu.show(createChangeTree, e.x, e.y)
        }

        private fun setSubmitEnabled(enabled: Boolean) {
            submitButton.isEnabled = enabled
            submitButton.repaint()
        }

        private fun refreshStatusBanner() {
            val reason = precheckBlockedReason
            val check = latestPreCreateCheck
            val diffCount = latestChanges.size
            val commitCount = latestCommits.size
            val loadingDiff = createChangesLoading
            val loadingCommits = createCommitsLoading
            val loadingMissing = createMissingCommitLoading
            if (activeMode == InlinePrMode.EDIT) {
                branchStatusIconLabel.text = ""
                branchStatusTextLabel.text = ""
                return
            }
            when {
                reason == "正在加载分支信息..." || reason == "正在校验分支..." -> {
                    branchStatusIconLabel.text = "…"
                    branchStatusIconLabel.foreground = createPrSecondaryTextColor()
                    branchStatusTextLabel.text = reason ?: "正在校验分支..."
                }
                !reason.isNullOrBlank() -> {
                    branchStatusIconLabel.text = "⚠"
                    branchStatusIconLabel.foreground = JBColor(Color(0xB28C00), Color(0xF6C26B))
                    branchStatusTextLabel.text = reason
                }
                else -> {
                    val canAutoMerge = check?.canBeAutomerge == true
                    branchStatusIconLabel.text = if (canAutoMerge) "✔" else "•"
                    branchStatusIconLabel.foreground = if (canAutoMerge) JBColor(Color(0x5C962C), Color(0x57D163)) else JBColor(Color(0xB28C00), Color(0xF6C26B))
                    branchStatusTextLabel.text = buildString {
                        append(if (canAutoMerge) "分支间代码可自动合并。" else "分支可创建 PR，但需人工确认合并。")
                        when {
                            loadingDiff && loadingCommits -> append("正在加载文件改动和提交记录。")
                            loadingDiff -> append("正在加载文件改动。")
                            loadingCommits -> append("正在加载提交记录。")
                            else -> {
                                append("共包含 ")
                                append(diffCount)
                                append(" 个文件修改与 ")
                                append(commitCount)
                                append(" 条提交记录。")
                            }
                        }
                        if (!loadingDiff && !loadingCommits && loadingMissing) {
                            append("正在补充缺失提交检测。")
                        }
                    }
                }
            }
        }

        private fun refreshDiffAndCommitView() {
            diffTabCountLabel.setPill(latestChanges.size.toString(), detailTabBadgeColor())
            commitTabCountLabel.setPill(latestCommits.size.toString(), detailTabBadgeColor())
            applyCreateChangeTreeFilter()
            renderCreateCommitTimeline()
            updateCreateWarningStates()
            refreshStatusBanner()
            updateCreateTabsVisibility()
            updateCreateTabHeaderStates()
            refreshCreateScrollForActiveTab()
        }

        private fun updateCreateWarningStates() {
            val source = selectedCreateBranch(sourceBranchBox)
            updateCreateFileChangeBranchWarning(source)
            updateCreateCommitWarning(latestMissingCommitHashes.isNotEmpty())
        }

        private fun updateCreateTabsVisibility() {
            val sourceSelected = selectedCreateBranch(sourceBranchBox).isNotBlank()
            val targetSelected = selectedCreateBranch(targetBranchBox).isNotBlank()
            val showTabs = sourceSelected && targetSelected && (activeMode == InlinePrMode.EDIT || latestPreCreateCheck?.code == 200)
            tabsWrapper.isVisible = showTabs
            if (!showTabs && createTabs.tabCount > 0 && createTabs.selectedIndex != 0) {
                createTabs.selectedIndex = 0
            }
            tabsWrapper.revalidate()
            tabsWrapper.repaint()
            refreshCreateScrollForActiveTab()
        }

        private fun computeCreateTabsPreferredSize(base: Dimension, tabbedPane: javax.swing.JTabbedPane): Dimension {
            val selected = tabbedPane.selectedComponent as? JComponent ?: return base
            val selectedSize = selected.preferredSize
            val headerHeight = resolveCreateTabHeaderHeight(tabbedPane)
            val insets = tabbedPane.insets
            val minContentHeight = JBUI.scale(120)
            val totalHeight = headerHeight + maxOf(selectedSize.height, minContentHeight) + insets.top + insets.bottom
            return Dimension(base.width, totalHeight)
        }

        private fun resolveCreateTabHeaderHeight(tabbedPane: javax.swing.JTabbedPane): Int {
            if (tabbedPane.tabCount <= 0) return 0
            val selectedIndex = tabbedPane.selectedIndex.takeIf { it in 0 until tabbedPane.tabCount } ?: 0
            val boundsHeight = runCatching { tabbedPane.getBoundsAt(selectedIndex).height }.getOrDefault(0)
            if (boundsHeight > 0) return boundsHeight
            val componentHeight = (0 until tabbedPane.tabCount)
                .mapNotNull { index -> tabbedPane.getTabComponentAt(index)?.preferredSize?.height?.takeIf { height: Int -> height > 0 } }
                .maxOrNull()
                ?: 0
            return componentHeight.coerceAtLeast(JBUI.scale(38))
        }

        private fun refreshCreateScrollForActiveTab() {
            SwingUtilities.invokeLater {
                val activeTab = createTabs.selectedComponent as? JComponent
                activeTab?.revalidate()
                activeTab?.repaint()
                createTabs.invalidate()
                createTabs.revalidate()
                createTabs.repaint()
                tabsWrapper.revalidate()
                tabsWrapper.repaint()
                scrollContentPanel.revalidate()
                scrollContentPanel.repaint()
                rootScrollPane.viewport.revalidate()
                rootScrollPane.viewport.repaint()
                rootScrollPane.revalidate()
                rootScrollPane.repaint()
                rootPanel.revalidate()
                rootPanel.repaint()
            }
        }

        private fun updateCreateFileChangeBranchWarning(sourceBranch: String) {
            val srBranch = normalizeLocalBranchName(sourceBranch)
            if (srBranch.isBlank()) {
                updateCreateFileChangeWarning(false, null)
                return
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
                    if (repo == null) {
                        updateCreateFileChangeWarning(false, null)
                        return@executeOnPooledThread
                    }

                    val currentBranch = repo.currentBranch?.name.orEmpty()
                    val isSourceBranch = currentBranch == srBranch
                    val hasWarning = !isSourceBranch
                    val tip = if (hasWarning) buildFileChangeWarningText(isSourceBranch) else null
                    updateCreateFileChangeWarning(hasWarning, tip)
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Check create PR source branch failed: sourceBranch=$sourceBranch", e)
                    updateCreateFileChangeWarning(false, null)
                }
            }
        }

        private fun updateCreateFileChangeWarning(visible: Boolean, tooltip: String?) {
            SwingUtilities.invokeLater {
                createFileChangeWarningText = tooltip
                createFileChangeWarningButton.isVisible = visible
                createFileChangeWarningButton.toolTipText = tooltip
                if (!visible) {
                    hideCreateFileChangeWarningBalloon()
                }
                createTabs.revalidate()
                createTabs.repaint()
            }
        }

        private fun updateCreateCommitWarning(visible: Boolean) {
            SwingUtilities.invokeLater {
                createCommitWarningText = if (visible) {
                    "当前分支缺少如下提交记录，可能会影响文件对比中的上下文查看的准确性"
                } else {
                    null
                }
                createCommitWarningLabel.isVisible = visible
                createCommitWarningLabel.toolTipText = createCommitWarningText
                if (!visible) {
                    hideCreateCommitWarningBalloon()
                }
                createTabs.revalidate()
                createTabs.repaint()
            }
        }

        private fun toggleCreateCommitWarningBalloon() {
            if (createCommitWarningBalloon != null) {
                hideCreateCommitWarningBalloon()
                return
            }
            showCreateCommitWarningBalloon()
        }

        private fun showCreateCommitWarningBalloon() {
            val text = createCommitWarningText?.takeIf { it.isNotBlank() } ?: return
            createCommitWarningBalloon?.hide()
            val fgColor = UIUtil.getToolTipForeground()
            val bgColor = UIUtil.getToolTipBackground()
            val styledText = wrapHtmlWithColor(text, fgColor)
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(
                    styledText,
                    null,
                    bgColor,
                    UIUtil.getBoundsColor(),
                    null
                )
                .setHideOnClickOutside(true)
                .setHideOnKeyOutside(true)
                .setAnimationCycle(80)
                .createBalloon()
            createCommitWarningBalloon = balloon
            balloon.show(RelativePoint.getSouthOf(createCommitWarningLabel), Balloon.Position.below)
        }

        private fun hideCreateCommitWarningBalloon() {
            createCommitWarningBalloon?.hide()
            createCommitWarningBalloon = null
        }

        private fun toggleCreateFileChangeWarningBalloon() {
            if (createFileChangeWarningBalloon != null) {
                hideCreateFileChangeWarningBalloon()
                return
            }
            showCreateFileChangeWarningBalloon()
        }

        private fun showCreateFileChangeWarningBalloon() {
            val text = createFileChangeWarningText?.takeIf { it.isNotBlank() } ?: return
            createFileChangeWarningBalloon?.hide()
            val fgColor = UIUtil.getToolTipForeground()
            val bgColor = UIUtil.getToolTipBackground()
            val styledText = wrapHtmlWithColor(text, fgColor)
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(
                    styledText,
                    null,
                    bgColor,
                    UIUtil.getBoundsColor(),
                    null
                )
                .setHideOnClickOutside(true)
                .setHideOnKeyOutside(true)
                .setAnimationCycle(80)
                .createBalloon()
            createFileChangeWarningBalloon = balloon
            balloon.show(RelativePoint.getSouthOf(createFileChangeWarningButton), Balloon.Position.below)
        }

        private fun hideCreateFileChangeWarningBalloon() {
            createFileChangeWarningBalloon?.hide()
            createFileChangeWarningBalloon = null
        }

        private fun updateCreateChangeModeToggleStyle() {
            styleSegmentedToggle(createChangeTreeToggleButton, !createChangeTreeFlatMode)
            styleSegmentedToggle(createChangeFlatToggleButton, createChangeTreeFlatMode)
        }

        private fun applyCreateChangeTreeFilter() {
            val keyword = createChangeSearchField.text?.trim().orEmpty().lowercase()
            val visibleChanges = if (keyword.isBlank()) {
                latestChanges
            } else {
                latestChanges.filter {
                    it.filePath.lowercase().contains(keyword) || it.filePath.substringAfterLast('/').lowercase().contains(keyword)
                }
            }
            createChangeTree.emptyText.text = when {
                createChangesLoading && latestChanges.isEmpty() -> "正在加载文件改动..."
                latestChanges.isEmpty() -> "暂无对比结果"
                visibleChanges.isEmpty() -> "未找到匹配文件"
                else -> ""
            }
            renderCreateChangeTreeNodes(visibleChanges)
        }

        private fun renderCreateChangeTreeNodes(changes: List<ChangeItem>) {
            createChangeTreeRoot.removeAllChildren()
            var insertedFiles = 0
            if (createChangeTreeFlatMode) {
                changes.sortedBy { it.filePath.lowercase() }.forEach { change ->
                    createChangeTreeRoot.add(DefaultMutableTreeNode(change))
                    insertedFiles++
                }
            } else {
                changes.forEach { change ->
                    if (insertCreateChangeNode(change)) {
                        insertedFiles++
                    }
                }
                sortTree(createChangeTreeRoot)
                compactDirectoryTree(createChangeTreeRoot)
            }
            createChangeTree.showsRootHandles = !createChangeTreeFlatMode
            createChangeTreeModel.reload()
            if (!createChangeTreeFlatMode) {
                val rootPath = TreePath(createChangeTreeRoot.path)
                expandCreateChangeTree(rootPath)
                SwingUtilities.invokeLater {
                    expandCreateChangeTree(rootPath)
                    updateCreateChangeTreePreferredHeight()
                }
            }
            val additions = changes.sumOf { it.additions }
            val deletions = changes.sumOf { it.deletions }
            createChangeSummaryLabel.text = "${changes.size} 个文件变更"
            createChangeAdditionsLabel.text = "+$additions additions"
            createChangeDeletionsLabel.text = "-$deletions deletions"
            updateCreateChangeTreePreferredHeight()
            if (insertedFiles < changes.size) {
                updateStatus("文件树构建异常: 期望${changes.size}，实际$insertedFiles")
            }
        }

        private fun insertCreateChangeNode(change: ChangeItem): Boolean {
            val normalizedPath = change.filePath.trim().replace('\\', '/').trim('/')
            if (normalizedPath.isBlank()) return false
            val parts = normalizedPath.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) return false
            var parent = createChangeTreeRoot
            parts.dropLast(1).forEach { dirName ->
                parent = findOrCreateDirectoryNode(parent, dirName)
            }
            parent.add(DefaultMutableTreeNode(change))
            return true
        }

        private fun expandCreateChangeTree(path: TreePath) {
            createChangeTree.expandPath(path)
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
            val children = node.children()
            while (children.hasMoreElements()) {
                val child = children.nextElement() as? DefaultMutableTreeNode ?: continue
                expandCreateChangeTree(path.pathByAddingChild(child))
            }
        }

        private fun renderCreateCommitTimeline() {
            createCommitTimelineContent.removeAll()
            if (createCommitsLoading && latestCommits.isEmpty()) {
                createCommitTimelineContent.add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(28, 8)
                    add(JBLabel("正在加载提交记录...", SwingConstants.CENTER).apply {
                        foreground = detailMutedColor()
                        font = font.deriveFont(Font.PLAIN, globalUiFontSize + 1f)
                    }, BorderLayout.CENTER)
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                })
            } else if (latestCommits.isEmpty()) {
                createCommitTimelineContent.add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(28, 8)
                    add(JBLabel("暂无提交记录", SwingConstants.CENTER).apply {
                        foreground = detailMutedColor()
                        font = font.deriveFont(Font.PLAIN, globalUiFontSize + 1f)
                    }, BorderLayout.CENTER)
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                })
            } else {
                latestCommits.forEachIndexed { index, commit ->
                    createCommitTimelineContent.add(createCommitTimelineItem(commit, index == latestCommits.lastIndex, latestMissingCommitHashes.contains(commit.hash)).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    })
                }
            }
            createCommitTimelineContent.revalidate()
            createCommitTimelineContent.repaint()
            createCommitTimelineScrollPane?.viewport?.repaint()
            updateCreateCommitTimelinePreferredHeight()
        }

        private fun updateCreateChangeTreePreferredHeight() {
            val minHeight = JBUI.scale(200)
            val contentHeight = if (createChangeTree.rowCount > 0) {
                createChangeTree.getRowBounds(createChangeTree.rowCount - 1)?.maxY?.toInt() ?: 0
            } else {
                0
            }
            val footerHeight = JBUI.scale(44)
            val targetHeight = maxOf(minHeight, contentHeight + footerHeight)
            createChangeTreeScrollPane?.apply {
                preferredSize = Dimension(preferredSize.width.coerceAtLeast(0), targetHeight)
                minimumSize = Dimension(0, minHeight)
                revalidate()
            }
            createChangeCard?.apply {
                val cardHeight = targetHeight + JBUI.scale(8)
                preferredSize = Dimension(preferredSize.width.coerceAtLeast(0), cardHeight)
                minimumSize = Dimension(0, cardHeight)
                maximumSize = Dimension(Int.MAX_VALUE, cardHeight)
                revalidate()
            }
            rootPanel.revalidate()
            rootPanel.repaint()
        }

        private fun updateCreateCommitTimelinePreferredHeight() {
            val minHeight = JBUI.scale(240)
            val contentHeight = createCommitTimelineContent.preferredSize.height
            val targetHeight = maxOf(minHeight, contentHeight + JBUI.scale(12))
            createCommitTimelineScrollPane?.apply {
                preferredSize = Dimension(preferredSize.width.coerceAtLeast(0), targetHeight)
                minimumSize = Dimension(0, minHeight)
                revalidate()
            }
            createCommitCard?.apply {
                val cardHeight = targetHeight + JBUI.scale(12)
                preferredSize = Dimension(preferredSize.width.coerceAtLeast(0), cardHeight)
                minimumSize = Dimension(0, cardHeight)
                maximumSize = Dimension(Int.MAX_VALUE, cardHeight)
                revalidate()
            }
            rootPanel.revalidate()
            rootPanel.repaint()
        }

        private inner class CreateTabHeader(
            private val titleLabel: JBLabel,
            badge: JComponent?,
            private val isFirst: Boolean = false,
            private val isLast: Boolean = false
        ) : JPanel(BorderLayout()) {
            private val contentPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
            private var selectedState = false
            var titleFontSize: Float = detailSectionTitleFontSize()
                set(value) {
                    field = value
                    titleLabel.font = titleLabel.font.deriveFont(Font.PLAIN, value)
                }

            init {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                val sideInset = detailHeaderSideInset()
                border = JBUI.Borders.empty(
                    JBUI.scale(2),
                    if (isFirst) sideInset else JBUI.scale(12),
                    JBUI.scale(12),
                    if (isLast) sideInset else JBUI.scale(12)
                )
                titleLabel.font = titleLabel.font.deriveFont(Font.PLAIN, titleFontSize)
                contentPanel.add(titleLabel)
                if (badge != null) {
                    contentPanel.add(Box.createHorizontalStrut(JBUI.scale(6)))
                    contentPanel.add(badge)
                }
                add(contentPanel, BorderLayout.WEST)
                bindClickHandlerRecursively(this)
            }

            private fun bindClickHandlerRecursively(component: Component) {
                component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                component.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(e)) return
                        dismissCreateInputFocus()
                        val index = createTabs.indexOfTabComponent(this@CreateTabHeader)
                        if (index in 0 until createTabs.tabCount && createTabs.selectedIndex != index) {
                            createTabs.selectedIndex = index
                        }
                    }
                })
                if (component is Container) {
                    component.components.forEach { child -> bindClickHandlerRecursively(child) }
                }
            }

            override fun paintComponent(g: Graphics) {
                if (selectedState) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        val bounds = contentPanel.bounds
                        if (bounds.width > 0 && bounds.height > 0) {
                            val insetX = JBUI.scale(8)
                            val insetY = JBUI.scale(4)
                            val x = (bounds.x - insetX).coerceAtLeast(0)
                            val y = (bounds.y - insetY).coerceAtLeast(0)
                            val fillWidth = (bounds.width + insetX * 2).coerceAtMost(width - x)
                            val fillHeight = (bounds.height + insetY * 2).coerceAtMost(height - y)
                            if (fillWidth > 0 && fillHeight > 0) {
                                val arc = JBUI.scale(12)
                                g2.color = detailTabHeaderSelectedFill()
                                g2.fillRoundRect(x, y, fillWidth, fillHeight, arc, arc)
                                g2.color = detailTabHeaderSelectedOutline()
                                g2.drawRoundRect(x, y, fillWidth - 1, fillHeight - 1, arc, arc)
                            }
                        }
                    } finally {
                        g2.dispose()
                    }
                }
                super.paintComponent(g)
            }

            fun setSelectedState(selected: Boolean) {
                selectedState = selected
                titleLabel.foreground = if (selected) detailTabHeaderSelectedTextColor() else detailTabHeaderMutedTextColor()
                repaint()
            }
        }

        private inner class CreateChangeTreeCellRenderer : javax.swing.tree.TreeCellRenderer {
            private val fallbackRenderer = DefaultTreeCellRenderer()
            private val statsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false }
            private val rowPanel = object : JPanel(BorderLayout(JBUI.scale(10), 0)) {
                override fun paintComponent(g: Graphics) {
                    val outlineColor = getClientProperty("outlineColor") as? Color
                    if (background.alpha > 0 || outlineColor != null) {
                        val g2 = g.create() as Graphics2D
                        try {
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                            val shape = RoundRectangle2D.Float(0.5f, 0.5f, (width - 1f).coerceAtLeast(0f), (height - 1f).coerceAtLeast(0f), JBUI.scale(10).toFloat(), JBUI.scale(10).toFloat())
                            if (background.alpha > 0) {
                                g2.color = background
                                g2.fill(shape)
                            }
                            if (outlineColor != null) {
                                g2.color = outlineColor
                                g2.draw(shape)
                            }
                        } finally {
                            g2.dispose()
                        }
                    }
                    super.paintComponent(g)
                }
            }.apply {
                isOpaque = false
                border = JBUI.Borders.empty(3, 0, 3, 6)
            }
            private val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
            private val mainLabel = JBLabel()
            private val additionLabel = JBLabel()
            private val deletionLabel = JBLabel()

            init {
                infoPanel.add(mainLabel)
                rowPanel.add(infoPanel, BorderLayout.CENTER)
                statsPanel.add(additionLabel)
                statsPanel.add(deletionLabel)
                rowPanel.add(statsPanel, BorderLayout.EAST)
            }

            override fun getTreeCellRendererComponent(
                tree: javax.swing.JTree?,
                value: Any?,
                sel: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): Component {
                val base = fallbackRenderer.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val node = value as? DefaultMutableTreeNode
                val userObject = node?.userObject
                if (userObject !is ChangeItem && userObject !is String) {
                    if (base is JComponent) base.font = base.font.deriveFont(Font.PLAIN, globalUiFontSize)
                    return base
                }
                rowPanel.background = if (sel) withAlpha(detailAccentColor, 22) else Color(0, 0, 0, 0)
                rowPanel.putClientProperty("outlineColor", if (sel) withAlpha(detailAccentColor, 92) else null)
                val font = fallbackRenderer.font.deriveFont(Font.PLAIN, globalUiFontSize)
                val statFont = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                mainLabel.font = font
                mainLabel.foreground = detailPrimaryTextColor()
                additionLabel.font = statFont
                deletionLabel.font = statFont
                additionLabel.foreground = JBColor(Color(0x1E8E3E), Color(0x57D163))
                deletionLabel.foreground = JBColor(Color(0xD93025), Color(0xF47067))
                if (userObject is String) {
                    mainLabel.icon = AllIcons.Nodes.Folder
                    mainLabel.iconTextGap = JBUI.scale(8)
                    mainLabel.text = userObject
                    mainLabel.toolTipText = null
                    additionLabel.text = ""
                    deletionLabel.text = ""
                    additionLabel.isVisible = false
                    deletionLabel.isVisible = false
                    statsPanel.isVisible = false
                    return rowPanel
                }
                val change = userObject as ChangeItem
                val fileName = change.filePath.substringAfterLast('/')
                mainLabel.icon = changeTypeIcon(change.changeType)
                mainLabel.iconTextGap = JBUI.scale(8)
                mainLabel.text = formatFileNameWithChangeType(fileName, change.changeType)
                mainLabel.toolTipText = changeTypeTooltip(change)
                additionLabel.text = if (change.additions > 0) "+${change.additions}" else ""
                deletionLabel.text = if (change.deletions > 0) "-${change.deletions}" else ""
                additionLabel.isVisible = additionLabel.text.isNotBlank()
                deletionLabel.isVisible = deletionLabel.text.isNotBlank()
                statsPanel.isVisible = additionLabel.isVisible || deletionLabel.isVisible
                return rowPanel
            }
        }

        private fun loadInitialData(
            initialSourceBranch: String? = null,
            initialTargetBranch: String? = null
        ) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    fetchRemoteBranches()
                    val branches = collectBranchNames()
                    SwingUtilities.invokeLater {
                        availableCreateBranches = branches
                        sourceBranchBox.setAvailableBranches(branches)
                        targetBranchBox.setAvailableBranches(branches)
                        sourceBranchBox.setSelectedBranch(initialSourceBranch)
                        targetBranchBox.setSelectedBranch(initialTargetBranch)
                        precheckBlockedReason = null
                        triggerBranchRefresh()
                    }
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Load create PR inline view initial data failed", e)
                    SwingUtilities.invokeLater {
                        precheckBlockedReason = e.message ?: "初始化失败"
                        refreshStatusBanner()
                        setSubmitEnabled(false)
                    }
                }
            }
        }

        private fun collectBranchNames(): List<String> {
            val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return emptyList()
            val handler = GitLineHandler(project, repo.root, GitCommand.BRANCH)
            handler.addParameters("-a", "--no-color")
            val result = Git.getInstance().runCommand(handler)
            if (!result.success()) return emptyList()
            return result.output.mapNotNull { rawLine ->
                val line = rawLine.trim().removePrefix("*").trim()
                if (line.isBlank() || line.contains("->")) return@mapNotNull null
                val normalized = line.removePrefix("remotes/")
                    .removePrefix("origin/")
                    .removePrefix("refs/heads/")
                    .removePrefix("refs/remotes/origin/")
                normalized.takeIf { it.isNotBlank() }
            }.distinct().sorted()
        }

        private fun triggerBranchRefresh() {
            val refreshVersion = ++branchRefreshVersion
            val source = selectedCreateBranch(sourceBranchBox)
            val target = selectedCreateBranch(targetBranchBox)
            applyCreateDefaultTitle(source)
            if (source.isBlank() || target.isBlank()) {
                precheckBlockedReason = "请选择源分支和目标分支"
                latestPreCreateCheck = null
                latestChanges = emptyList()
                latestCommits = emptyList()
                latestMissingCommitHashes = emptySet()
                createChangesLoading = false
                createCommitsLoading = false
                createMissingCommitLoading = false
                refreshDiffAndCommitView()
                setSubmitEnabled(false)
                return
            }
            if (activeMode == InlinePrMode.EDIT) {
                applyEditModeBranchState(source, target, refreshVersion)
                return
            }
            if (source == target) {
                precheckBlockedReason = "源分支和目标分支不能相同"
                latestPreCreateCheck = null
                latestChanges = emptyList()
                latestCommits = emptyList()
                latestMissingCommitHashes = emptySet()
                createChangesLoading = false
                createCommitsLoading = false
                createMissingCommitLoading = false
                refreshDiffAndCommitView()
                setSubmitEnabled(false)
                return
            }
            precheckBlockedReason = "正在校验分支..."
            latestPreCreateCheck = null
            latestChanges = emptyList()
            latestCommits = emptyList()
            latestMissingCommitHashes = emptySet()
            createChangesLoading = false
            createCommitsLoading = false
            createMissingCommitLoading = false
            refreshDiffAndCommitView()
            setSubmitEnabled(false)
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val check = requestPreCreateCheck(source, target)
                    if (refreshVersion != branchRefreshVersion) return@executeOnPooledThread
                    latestPreCreateCheck = check
                    applyPrecheckResult(check, refreshVersion)
                    SwingUtilities.invokeLater {
                        if (refreshVersion != branchRefreshVersion) return@invokeLater
                        refreshDiffAndCommitView()
                        setSubmitEnabled(precheckBlockedReason.isNullOrBlank())
                    }
                    if (check.code == 200) {
                        startCreateDiffAndCommitRefresh(source, target, refreshVersion)
                    }
                } catch (e: Exception) {
                    if (refreshVersion != branchRefreshVersion) return@executeOnPooledThread
                    PrManagerFileLogger.error("Refresh create PR inline branch state failed", e)
                    precheckBlockedReason = e.message ?: "校验失败"
                    SwingUtilities.invokeLater {
                        if (refreshVersion != branchRefreshVersion) return@invokeLater
                        refreshDiffAndCommitView()
                        setSubmitEnabled(precheckBlockedReason.isNullOrBlank())
                    }
                }
            }
        }

        private fun applyEditModeBranchState(source: String, target: String, refreshVersion: Int) {
            val detail = editingDetail
            val primarySelection = detail?.primaryReviewerInfos?.map(::reviewerInfoToCandidate).orEmpty()
            val generalSelection = detail?.generalReviewerInfos?.map(::reviewerInfoToCandidate).orEmpty()
            indexDevelopers(primarySelection + generalSelection)
            precheckBlockedReason = null
            setSubmitEnabled(false)
            pendingEditPrecheck?.let { preloaded ->
                pendingEditPrecheck = null
                latestPreCreateCheck = preloaded
                applyPrecheckResult(preloaded, refreshVersion)
                startCreateDiffAndCommitRefresh(source, target, refreshVersion)
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val check = requestPreCreateCheck(source, target)
                    if (refreshVersion != branchRefreshVersion) return@executeOnPooledThread
                    latestPreCreateCheck = check
                    applyPrecheckResult(check, refreshVersion)
                } catch (e: Exception) {
                    if (refreshVersion != branchRefreshVersion) return@executeOnPooledThread
                    PrManagerFileLogger.error("Refresh edit PR inline branch state failed", e)
                    SwingUtilities.invokeLater {
                        if (refreshVersion != branchRefreshVersion) return@invokeLater
                        latestPreCreateCheck = null
                        mandatoryPrimaryUsers = emptySet()
                        minimumPrimary = 0
                        minimumGeneral = 0
                        primaryReviewerPicker.setSelectionEditable(true)
                        generalReviewerPicker.setSelectionEditable(true)
                        primaryReviewerPicker.setSelectedCandidates(primarySelection)
                        generalReviewerPicker.setSelectedCandidates(generalSelection)
                        setReviewerCountSpinnerEnabled(primaryNumSpinner, true)
                        setReviewerCountSpinnerEnabled(generalNumSpinner, true)
                        refreshReviewerRequirementControls()
                        if (detail != null) {
                            primaryNumSpinner.value = detail.overview.needKeyReviewers
                            generalNumSpinner.value = detail.overview.needReviewers
                        }
                        refreshDiffAndCommitView()
                        setSubmitEnabled(true)
                    }
                }
                startCreateDiffAndCommitRefresh(source, target, refreshVersion)
            }
        }

        fun requestPreCreateCheck(source: String, target: String): PreCreateCheck {
            if (mockEnabled) {
                val mockJson = readMockJson(mockCanCreatePrFile)
                    ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockCanCreatePrFile")
                return parsePreCreateCheck(mockJson)
            }
            val response = apiService.canCreatePr(
                sshPath = resolveGitAddress(),
                sourceBranch = source,
                targetBranch = target
            )
            if (response.statusCode() !in 200..299) {
                return PreCreateCheck(response.statusCode(), "分支校验失败", false, emptyList(), emptyList(), 0, 0, false, false)
            }
            return parsePreCreateCheck(response.body())
        }

        private fun parsePreCreateCheck(body: String): PreCreateCheck {
            val root = objectMapper.readTree(body)
            val result = root.get("result") ?: root
            val data = result.get("data")
            val code = data?.get("code")?.asInt() ?: result.get("code")?.asInt() ?: 500
            val message = data?.readText("message", "warningMessage", "warning_message").orEmpty()
                .ifBlank { result.readText("message") }
            val canBeAutomerge = data?.get("canBeAutomerge")?.asBoolean()
                ?: data?.get("can_be_automerge")?.asBoolean()
                ?: false
            val primary = parseDeveloperCandidates(data?.get("primaryReviewers") ?: data?.get("primary_reviewers"))
            val general = parseDeveloperCandidates(data?.get("generalReviewers") ?: data?.get("general_reviewers"))
            val primaryNumNode = data?.get("primaryReviewerNum") ?: data?.get("primary_reviewer_num")
            val generalNumNode = data?.get("generalReviewerNum") ?: data?.get("general_reviewer_num")
            val primaryNum = primaryNumNode?.asInt() ?: 0
            val generalNum = generalNumNode?.asInt() ?: 0
            return PreCreateCheck(
                code = code,
                message = message,
                canBeAutomerge = canBeAutomerge,
                primaryReviewers = primary,
                generalReviewers = general,
                primaryReviewerNum = primaryNum,
                generalReviewerNum = generalNum,
                primaryReviewerNumProvided = primaryNumNode != null && !primaryNumNode.isNull,
                generalReviewerNumProvided = generalNumNode != null && !generalNumNode.isNull
            )
        }

        private fun parseDeveloperCandidates(node: JsonNode?): List<DeveloperCandidate> {
            if (node == null || !node.isArray) return emptyList()
            return node.mapNotNull { item ->
                val id = item.get("id")?.asLong() ?: return@mapNotNull null
                val username = item.readText("username", "login", "userName")
                val name = item.readText("name", "login", "username", "userName")
                val finalUsername = username.ifBlank { name }
                if (finalUsername.isBlank()) return@mapNotNull null
                DeveloperCandidate(id = id, username = finalUsername, name = name)
            }
        }

        private fun reviewerInfoToCandidate(info: ReviewerInfo): DeveloperCandidate {
            return DeveloperCandidate(
                id = info.id,
                username = info.username,
                name = info.name.ifBlank { info.username }
            )
        }

        private fun applyPrecheckResult(check: PreCreateCheck, refreshVersion: Int) {
            if (refreshVersion != branchRefreshVersion) return
            val editDetail = editingDetail
            val filteredCheck = if (activeMode == InlinePrMode.CREATE) {
                check.copy(
                    primaryReviewers = excludeCreatorCandidates(check.primaryReviewers),
                    generalReviewers = excludeCreatorCandidates(check.generalReviewers)
                )
            } else {
                check
            }
            val lockPrimaryReviewers = hasLockedPrimaryReviewerConstraint(filteredCheck)
            val applyGeneralSuggestion = activeMode == InlinePrMode.CREATE &&
                !initialGeneralReviewersApplied &&
                hasInitialGeneralReviewerSuggestion(filteredCheck)
            if (applyGeneralSuggestion) {
                initialGeneralReviewersApplied = true
            }
            precheckBlockedReason = if (activeMode == InlinePrMode.CREATE && filteredCheck.code != 200) {
                filteredCheck.message.ifBlank { "分支不满足创建条件，code=${filteredCheck.code}" }
            } else {
                null
            }
            SwingUtilities.invokeLater {
                if (refreshVersion != branchRefreshVersion) return@invokeLater
                val primarySelection = when {
                    lockPrimaryReviewers -> filteredCheck.primaryReviewers
                    activeMode == InlinePrMode.EDIT && editDetail != null -> editDetail.primaryReviewerInfos.map(::reviewerInfoToCandidate)
                    else -> primaryReviewerPicker.getSelectedCandidates()
                }
                val generalSelection = when {
                    activeMode == InlinePrMode.EDIT && editDetail != null -> editDetail.generalReviewerInfos.map(::reviewerInfoToCandidate)
                    applyGeneralSuggestion -> filteredCheck.generalReviewers
                    else -> generalReviewerPicker.getSelectedCandidates()
                }
                mandatoryPrimaryUsers = if (lockPrimaryReviewers) primarySelection.map { it.username }.toSet() else emptySet()
                minimumPrimary = if (lockPrimaryReviewers) filteredCheck.primaryReviewerNum.coerceAtLeast(1) else 0
                minimumGeneral = 0
                primaryReviewerPicker.setSelectionEditable(!lockPrimaryReviewers)
                generalReviewerPicker.setSelectionEditable(true)
                primaryReviewerPicker.setSelectedCandidates(
                    primarySelection,
                    if (lockPrimaryReviewers) primarySelection.map { it.id }.toSet() else emptySet()
                )
                generalReviewerPicker.setSelectedCandidates(generalSelection)
                this@PrManagerPanel.setReviewerCountSpinnerEnabled(primaryNumSpinner, !lockPrimaryReviewers)
                this@PrManagerPanel.setReviewerCountSpinnerEnabled(generalNumSpinner, true)
                refreshReviewerRequirementControls()
                when {
                    lockPrimaryReviewers -> primaryNumSpinner.value = filteredCheck.primaryReviewerNum
                    activeMode == InlinePrMode.EDIT && editDetail != null -> primaryNumSpinner.value = editDetail.overview.needKeyReviewers
                }
                when {
                    activeMode == InlinePrMode.EDIT && editDetail != null -> generalNumSpinner.value = editDetail.overview.needReviewers
                    applyGeneralSuggestion -> generalNumSpinner.value = filteredCheck.generalReviewerNum
                }
                if (activeMode == InlinePrMode.EDIT) {
                    setSubmitEnabled(true)
                }
                refreshStatusBanner()
            }
            val detailSelections = if (editDetail != null) {
                editDetail.primaryReviewerInfos.map(::reviewerInfoToCandidate) +
                    editDetail.generalReviewerInfos.map(::reviewerInfoToCandidate)
            } else {
                emptyList()
            }
            indexDevelopers((filteredCheck.primaryReviewers + filteredCheck.generalReviewers + detailSelections).distinctBy { it.id })
        }

        private fun loadDevelopersFromApi(keywords: String): List<DeveloperCandidate> {
            if (mockEnabled) {
                val mockJson = readMockJson(mockDevelopersFile)
                    ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockDevelopersFile")
                val root = objectMapper.readTree(mockJson)
                val result = root.get("result") ?: root
                val data = result.get("data")
                val list = data?.get("developers")
                val items = parseDeveloperCandidates(list)
                return if (activeMode == InlinePrMode.CREATE) excludeCreatorCandidates(items) else items
            }
            val response = apiService.fetchDevelopers(
                sshPath = resolveGitAddress(),
                keyword = keywords
            )
            if (response.statusCode() !in 200..299) return emptyList()
            val root = objectMapper.readTree(response.body())
            val result = root.get("result") ?: root
            val data = result.get("data")
            val list = data?.get("developers")
            val items = parseDeveloperCandidates(list)
            return if (activeMode == InlinePrMode.CREATE) excludeCreatorCandidates(items) else items
        }

        private fun indexDevelopers(items: List<DeveloperCandidate>) {
            if (items.isEmpty()) return
            items.forEach { item ->
                developerByKey[item.username.trim().lowercase()] = item
                if (item.name.isNotBlank()) {
                    developerByKey[item.name.trim().lowercase()] = item
                }
            }
        }

        private fun startCreateDiffAndCommitRefresh(sourceBranch: String, targetBranch: String, refreshVersion: Int) {
            createChangesLoading = true
            createCommitsLoading = true
            createMissingCommitLoading = false
            latestChanges = emptyList()
            latestCommits = emptyList()
            latestMissingCommitHashes = emptySet()
            SwingUtilities.invokeLater {
                if (refreshVersion != branchRefreshVersion) return@invokeLater
                refreshDiffAndCommitView()
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
                if (repo == null) {
                    SwingUtilities.invokeLater {
                        if (refreshVersion != branchRefreshVersion) return@invokeLater
                        createChangesLoading = false
                        createCommitsLoading = false
                        createMissingCommitLoading = false
                        latestChanges = emptyList()
                        latestCommits = emptyList()
                        latestMissingCommitHashes = emptySet()
                        refreshDiffAndCommitView()
                    }
                    return@executeOnPooledThread
                }

                val sourceRef = toRemoteBranchRef(repo, sourceBranch)
                val targetRef = toRemoteBranchRef(repo, targetBranch)
                val prCommitsFuture = CompletableFuture.supplyAsync {
                    loadPullRequestCommits(repo, targetRef, sourceRef)
                }
                val changesFuture = CompletableFuture.supplyAsync {
                    branchService.comparePullRequest(targetRef, sourceRef)
                }
                val prCommits = prCommitsFuture.join()
                SwingUtilities.invokeLater {
                    if (refreshVersion != branchRefreshVersion) return@invokeLater
                    latestCommits = prCommits
                    applyCreateDefaultDesc(prCommits)
                    createCommitsLoading = false
                    createMissingCommitLoading = prCommits.isNotEmpty()
                    refreshDiffAndCommitView()
                }

                if (refreshVersion != branchRefreshVersion) return@executeOnPooledThread

                val changesResult = changesFuture.join()
                SwingUtilities.invokeLater {
                    if (refreshVersion != branchRefreshVersion) return@invokeLater
                    latestChanges = if (changesResult.error == null) changesResult.changes else emptyList()
                    createChangesLoading = false
                    refreshDiffAndCommitView()
                }
                if (prCommits.isEmpty() || refreshVersion != branchRefreshVersion) {
                    SwingUtilities.invokeLater {
                        if (refreshVersion != branchRefreshVersion) return@invokeLater
                        latestMissingCommitHashes = emptySet()
                        createMissingCommitLoading = false
                        refreshDiffAndCommitView()
                    }
                    return@executeOnPooledThread
                }

                val missingHashes = runCatching { findMissingCommitsInCurrentBranch(repo, targetRef, sourceRef, prCommits) }
                    .getOrElse {
                        PrManagerFileLogger.error("Load create PR missing commit hashes failed", it)
                        emptySet()
                    }
                SwingUtilities.invokeLater {
                    if (refreshVersion != branchRefreshVersion) return@invokeLater
                    latestMissingCommitHashes = missingHashes
                    createMissingCommitLoading = false
                    refreshDiffAndCommitView()
                }
            }
        }

        private fun computeBranchDiffAndCommits(sourceBranch: String, targetBranch: String) {
            val sourceRef = ensureOriginBranch(sourceBranch)
            val targetRef = ensureOriginBranch(targetBranch)
            val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            if (repo == null) {
                latestChanges = emptyList()
                latestCommits = emptyList()
                latestMissingCommitHashes = emptySet()
            } else {
                val source = toRemoteBranchRef(repo, sourceBranch)
                val target = toRemoteBranchRef(repo, targetBranch)
                val commitFuture = CompletableFuture.supplyAsync {
                    loadPullRequestCommits(repo, target, source)
                }
                val changeFuture = CompletableFuture.supplyAsync {
                    branchService.comparePullRequest(targetRef, sourceRef)
                }
                val compare = changeFuture.join()
                latestChanges = if (compare.error == null) compare.changes else emptyList()
                latestCommits = commitFuture.join()
                findMissingCommitsInCurrentBranch(repo, target, source, latestCommits)
            }
            SwingUtilities.invokeLater { refreshDiffAndCommitView() }
        }

        private fun resolveReviewerSelection(picker: ReviewerPickerField): Pair<List<String>, List<Long>> {
            val candidates = picker.getSelectedCandidates()
            return candidates.map { it.username } to candidates.map { it.id }.distinct()
        }

        private fun currentDialogTitle(): String = if (activeMode == InlinePrMode.EDIT) "编辑 PR" else "创建 PR"

        private fun submit() {
            val dialogTitle = currentDialogTitle()
            val title = titleField.text.trim()
            if (title.isBlank()) {
                Messages.showErrorDialog(project, "标题不能为空", dialogTitle)
                return
            }
            val desc = descField.text.trim()
            if (desc.isBlank()) {
                Messages.showErrorDialog(project, "描述不能为空", dialogTitle)
                return
            }
            val source = selectedCreateBranch(sourceBranchBox)
            val target = selectedCreateBranch(targetBranchBox)
            if (source.isBlank() || target.isBlank()) {
                Messages.showErrorDialog(project, "请选择源分支和目标分支", dialogTitle)
                return
            }
            if (activeMode != InlinePrMode.EDIT && source == target) {
                Messages.showErrorDialog(project, "源分支和目标分支不能相同", dialogTitle)
                return
            }
            if (activeMode != InlinePrMode.EDIT && !precheckBlockedReason.isNullOrBlank()) {
                Messages.showErrorDialog(project, precheckBlockedReason, dialogTitle)
                return
            }

            val (primaryNames, primaryIds) = resolveReviewerSelection(primaryReviewerPicker)
            val (generalNames, generalIds) = resolveReviewerSelection(generalReviewerPicker)

            if (activeMode == InlinePrMode.CREATE) {
                val creatorUsername = currentPluginAuthorUsername()
                val creatorSelected = creatorUsername.isNotBlank() &&
                    (primaryNames + generalNames).any { it.equals(creatorUsername, ignoreCase = true) }
                if (creatorSelected) {
                    Messages.showErrorDialog(project, "创建人不能添加为评审人或关键评审人", dialogTitle)
                    return
                }
            }

            if (mandatoryPrimaryUsers.isNotEmpty()) {
                val missingPrimary = mandatoryPrimaryUsers.filterNot { primaryNames.contains(it) }
                if (missingPrimary.isNotEmpty()) {
                    Messages.showErrorDialog(project, "关键评审人不可删除，缺失: ${missingPrimary.joinToString(",")}", dialogTitle)
                    return
                }
            }

            val rawPrimaryNum = (primaryNumSpinner.value as? Int) ?: 0
            val rawGeneralNum = (generalNumSpinner.value as? Int) ?: 0
            val primaryNum = if (primaryIds.isEmpty()) 0 else rawPrimaryNum
            val generalNum = if (generalIds.isEmpty()) 0 else rawGeneralNum
            val finalMinPrimary = if (primaryIds.isNotEmpty()) maxOf(minimumPrimary, 1) else 0
            val finalMinGeneral = if (generalIds.isNotEmpty()) maxOf(minimumGeneral, 1) else 0
            if (primaryNum < finalMinPrimary) {
                Messages.showErrorDialog(project, "关键评审最少通过人数不能小于 $finalMinPrimary", dialogTitle)
                return
            }
            if (primaryNum > primaryIds.size) {
                Messages.showErrorDialog(project, "关键评审最少通过人数不能大于当前已选关键评审人数", dialogTitle)
                return
            }
            if (generalNum < finalMinGeneral) {
                Messages.showErrorDialog(project, "普通评审最少通过人数不能小于 $finalMinGeneral", dialogTitle)
                return
            }
            if (generalNum > generalIds.size) {
                Messages.showErrorDialog(project, "普通评审最少通过人数不能大于当前已选评审人数", dialogTitle)
                return
            }

            val mergeType = resolveMergeTypeValue(mergeTypeBox.selectedItem as? String)
            val editDetailSnapshot = editingDetail
            setSubmitEnabled(false)
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val body = if (mockEnabled) {
                        readMockJson(mockCreatePrFile)
                            ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockCreatePrFile")
                    } else {
                        val response = if (activeMode == InlinePrMode.EDIT) {
                            val targetDetail = editDetailSnapshot
                                ?: throw IllegalStateException("当前编辑 PR 信息缺失")
                            apiService.updatePrByUser(
                                sshPath = resolveGitAddress(),
                                iid = targetDetail.iid,
                                title = title,
                                body = desc,
                                assigneesIds = generalIds,
                                assigneesNum = generalNum,
                                primaryAssigneesIds = primaryIds,
                                primaryAssigneesNum = primaryNum,
                                pruneBranch = deleteSourceBranchCheck.isSelected,
                                defaultMergeType = mergeType
                            )
                        } else {
                            apiService.createPr(
                                sshPath = resolveGitAddress(),
                                title = title,
                                head = source,
                                base = target,
                                body = desc,
                                assigneesIds = generalIds,
                                assigneesNum = generalNum,
                                primaryAssigneesIds = primaryIds,
                                primaryAssigneesNum = primaryNum,
                                pruneBranch = deleteSourceBranchCheck.isSelected,
                                defaultMergeType = mergeType
                            )
                        }
                        if (response.statusCode() !in 200..299) {
                            SwingUtilities.invokeLater {
                                setSubmitEnabled(true)
                                Messages.showErrorDialog(
                                    project,
                                    "${if (activeMode == InlinePrMode.EDIT) "编辑失败" else "创建失败"}: ${response.statusCode()}",
                                    dialogTitle
                                )
                            }
                            return@executeOnPooledThread
                        }
                        response.body()
                    }
                    val root = objectMapper.readTree(body)
                    val result = root.get("result") ?: root
                    val (createSucceeded, createdPrId) = parseCreatePrSubmitResult(body)
                    val success = if (activeMode == InlinePrMode.EDIT) {
                        when {
                            result.isBoolean -> result.asBoolean(false)
                            result.isObject -> result.get("success")?.asBoolean(false) ?: (result.get("code")?.asInt() == 200)
                            else -> false
                        }
                    } else {
                        createSucceeded
                    }
                    if (!success) {
                        val message = result.readText("message", "code").ifBlank {
                            if (activeMode == InlinePrMode.EDIT) "编辑失败" else "创建失败"
                        }
                        SwingUtilities.invokeLater {
                            setSubmitEnabled(true)
                            Messages.showErrorDialog(project, message, dialogTitle)
                        }
                        return@executeOnPooledThread
                    }
                    SwingUtilities.invokeLater {
                        val successMessage = if (activeMode == InlinePrMode.EDIT) "编辑 PR 成功" else "创建 PR 成功"
                        updateStatus(successMessage)
                        exitCreatePrView()
                        if (activeMode == InlinePrMode.EDIT) {
                            editDetailSnapshot?.id?.let { refreshPrListAndCurrentDetail(it) } ?: resetAndLoad()
                        } else {
                            createdPrId?.let { refreshPrListAndCurrentDetail(it) } ?: resetAndLoad()
                        }
                    }
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Submit inline PR view failed: mode=$activeMode", e)
                    SwingUtilities.invokeLater {
                        setSubmitEnabled(true)
                        Messages.showErrorDialog(
                            project,
                            e.message ?: if (activeMode == InlinePrMode.EDIT) "编辑失败" else "创建失败",
                            dialogTitle
                        )
                    }
                }
            }
        }
    }

    private inner class CreatePrDialog(project: Project) : com.intellij.openapi.ui.DialogWrapper(project) {
        private val sourceBranchBox = javax.swing.JComboBox<String>()
        private val targetBranchBox = javax.swing.JComboBox<String>()
        private val titleField = JBTextField()
        private val descField = JBTextArea()
        private val primaryReviewersField = JBTextField()
        private val generalReviewersField = JBTextField()
        private val primaryNumSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(0, 0, 999, 1))
        private val generalNumSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(0, 0, 999, 1))
        private val deleteSourceBranchCheck = JBCheckBox("合并后删除源分支", false)
        private val mergeTypeBox = javax.swing.JComboBox(arrayOf("", "merge", "fast_forward"))
        private val autoMergeLabel = JBLabel("自动合并：-")
        private val validationLabel = JBLabel(" ")
        private val diffSummaryArea = JBTextArea()
        private val commitSummaryArea = JBTextArea()

        private val developerByKey = mutableMapOf<String, DeveloperCandidate>()
        private var mandatoryPrimaryUsers: Set<String> = emptySet()
        private var minimumPrimary = 0
        private var minimumGeneral = 0
        private var initialGeneralReviewersApplied = false
        private var precheckBlockedReason: String? = null
        private var latestChanges: List<ChangeItem> = emptyList()
        private var latestCommits: List<CommitItem> = emptyList()

        init {
            title = "创建 Pull Request"
            setOKButtonText("提交 Pull Request")
            setCancelButtonText("取消")
            init()
            validationLabel.foreground = JBColor(Color(0xD93025), Color(0xF47067))
            autoMergeLabel.foreground = detailMutedColor()
            descField.rows = 5
            descField.lineWrap = true
            descField.wrapStyleWord = true
            listOf(diffSummaryArea, commitSummaryArea).forEach {
                it.isEditable = false
                it.rows = 8
                it.lineWrap = false
                it.font = Font(Font.MONOSPACED, Font.PLAIN, (globalUiFontSize - 1f).coerceAtLeast(11f).toInt())
            }
            val listener = object : java.awt.event.ItemListener {
                override fun itemStateChanged(e: java.awt.event.ItemEvent?) {
                    if (e?.stateChange == java.awt.event.ItemEvent.SELECTED) {
                        triggerBranchRefresh()
                    }
                }
            }
            sourceBranchBox.addItemListener(listener)
            targetBranchBox.addItemListener(listener)
            loadInitialData()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(12)
                preferredSize = Dimension(JBUI.scale(920), JBUI.scale(700))
            }

            val branchRow = JPanel(GridLayout(1, 2, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(section("源分支", sourceBranchBox as JComponent))
                add(section("目标分支", targetBranchBox as JComponent))
            }

            val reviewerCard = JPanel(GridLayout(2, 2, JBUI.scale(8), JBUI.scale(8))).apply {
                isOpaque = false
                add(section("关键评审人（逗号分隔，OA或姓名）", primaryReviewersField))
                add(section("关键评审最少通过人数", primaryNumSpinner as JComponent))
                add(section("普通评审人（逗号分隔，OA或姓名）", generalReviewersField))
                add(section("普通评审最少通过人数", generalNumSpinner as JComponent))
            }

            val mergeSettingRow = JPanel(GridLayout(1, 2, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(section("合并方式（可选）", mergeTypeBox as JComponent))
                add(section("其他", JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(deleteSourceBranchCheck)
                }))
            }

            val tabs = JBTabbedPane().apply {
                addTab("概览", createDetailScrollPane(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    border = JBUI.Borders.empty(8)
                    add(section("标题", titleField))
                    add(section("描述", JBScrollPane(descField)))
                    add(section("分支预检", autoMergeLabel))
                    add(reviewerCard)
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                    add(mergeSettingRow)
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                    add(validationLabel)
                }, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) { detailTabPaneFill() })
                addTab("文件改动", section("分支差异", JBScrollPane(diffSummaryArea)))
                addTab("提交记录", section("将合并的提交", JBScrollPane(commitSummaryArea)))
            }

            panel.add(branchRow)
            panel.add(Box.createVerticalStrut(JBUI.scale(10)))
            panel.add(tabs)
            return panel
        }

        private fun loadInitialData() {
            setOKActionEnabled(false)
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    fetchRemoteBranches()
                    val branches = collectBranchNames()
                    val developers = loadDevelopersFromApi("")
                    SwingUtilities.invokeLater {
                        sourceBranchBox.removeAllItems()
                        targetBranchBox.removeAllItems()
                        branches.forEach {
                            sourceBranchBox.addItem(it)
                            targetBranchBox.addItem(it)
                        }
                        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
                        val currentBranch = repo?.currentBranchName?.trim().orEmpty()
                        if (currentBranch.isNotBlank() && branches.contains(currentBranch)) {
                            sourceBranchBox.selectedItem = currentBranch
                        }
                        val target = when {
                            branches.contains("main") -> "main"
                            branches.contains("master") -> "master"
                            else -> branches.firstOrNull().orEmpty()
                        }
                        if (target.isNotBlank()) {
                            targetBranchBox.selectedItem = target
                        }
                        indexDevelopers(developers)
                        triggerBranchRefresh()
                    }
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Load create PR dialog initial data failed", e)
                    SwingUtilities.invokeLater {
                        precheckBlockedReason = e.message ?: "初始化失败"
                        updateValidationState()
                    }
                }
            }
        }

        private fun collectBranchNames(): List<String> {
            val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return emptyList()
            val handler = GitLineHandler(project, repo.root, GitCommand.BRANCH)
            handler.addParameters("-a", "--no-color")
            val result = Git.getInstance().runCommand(handler)
            if (!result.success()) return emptyList()
            return result.output.mapNotNull { rawLine ->
                val line = rawLine.trim().removePrefix("*").trim()
                if (line.isBlank() || line.contains("->")) return@mapNotNull null
                val normalized = line.removePrefix("remotes/")
                    .removePrefix("origin/")
                    .removePrefix("refs/heads/")
                    .removePrefix("refs/remotes/origin/")
                normalized.takeIf { it.isNotBlank() }
            }.distinct().sorted()
        }

        private fun triggerBranchRefresh() {
            val source = (sourceBranchBox.selectedItem as? String).orEmpty().trim()
            val target = (targetBranchBox.selectedItem as? String).orEmpty().trim()
            if (source.isBlank() || target.isBlank()) {
                precheckBlockedReason = "请选择源分支和目标分支"
                updateValidationState()
                return
            }
            if (source == target) {
                precheckBlockedReason = "源分支和目标分支不能相同"
                latestChanges = emptyList()
                latestCommits = emptyList()
                refreshDiffAndCommitText()
                updateValidationState()
                return
            }
            setOKActionEnabled(false)
            precheckBlockedReason = "正在校验分支..."
            updateValidationState()
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    fetchRemoteBranches()
                    val check = runPreCreateCheck(source, target)
                    applyPrecheckResult(check)
                    loadDevelopersByCurrentSelections(source, target)
                    computeBranchDiffAndCommits(source, target)
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Refresh create PR branch state failed", e)
                    precheckBlockedReason = e.message ?: "校验失败"
                }
                SwingUtilities.invokeLater { updateValidationState() }
            }
        }

        private fun runPreCreateCheck(source: String, target: String): PreCreateCheck {
            if (mockEnabled) {
                val mockJson = readMockJson(mockCanCreatePrFile)
                    ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockCanCreatePrFile")
                return parsePreCreateCheck(mockJson)
            }
            val response = apiService.canCreatePr(
                sshPath = resolveGitAddress(),
                sourceBranch = source,
                targetBranch = target
            )
            if (response.statusCode() !in 200..299) {
                return PreCreateCheck(response.statusCode(), "分支校验失败", false, emptyList(), emptyList(), 0, 0, false, false)
            }
            return parsePreCreateCheck(response.body())
        }

        private fun parsePreCreateCheck(body: String): PreCreateCheck {
            val root = objectMapper.readTree(body)
            val result = root.get("result") ?: root
            val data = result.get("data")
            val code = data?.get("code")?.asInt() ?: result.get("code")?.asInt() ?: 500
            val message = data?.readText("message", "warningMessage", "warning_message").orEmpty()
                .ifBlank { result.readText("message") }
            val canBeAutomerge = data?.get("canBeAutomerge")?.asBoolean()
                ?: data?.get("can_be_automerge")?.asBoolean()
                ?: false
            val primary = parseDeveloperCandidates(data?.get("primaryReviewers") ?: data?.get("primary_reviewers"))
            val general = parseDeveloperCandidates(data?.get("generalReviewers") ?: data?.get("general_reviewers"))
            val primaryNumNode = data?.get("primaryReviewerNum") ?: data?.get("primary_reviewer_num")
            val generalNumNode = data?.get("generalReviewerNum") ?: data?.get("general_reviewer_num")
            val primaryNum = primaryNumNode?.asInt() ?: 0
            val generalNum = generalNumNode?.asInt() ?: 0
            return PreCreateCheck(
                code = code,
                message = message,
                canBeAutomerge = canBeAutomerge,
                primaryReviewers = primary,
                generalReviewers = general,
                primaryReviewerNum = primaryNum,
                generalReviewerNum = generalNum,
                primaryReviewerNumProvided = primaryNumNode != null && !primaryNumNode.isNull,
                generalReviewerNumProvided = generalNumNode != null && !generalNumNode.isNull
            )
        }

        private fun parseDeveloperCandidates(node: JsonNode?): List<DeveloperCandidate> {
            if (node == null || !node.isArray) return emptyList()
            return node.mapNotNull { item ->
                val id = item.get("id")?.asLong() ?: return@mapNotNull null
                val username = item.readText("username", "login", "userName")
                val name = item.readText("name", "login", "username", "userName")
                val finalUsername = username.ifBlank { name }
                if (finalUsername.isBlank()) return@mapNotNull null
                DeveloperCandidate(id = id, username = finalUsername, name = name)
            }
        }

        private fun applyPrecheckResult(check: PreCreateCheck) {
            val filteredCheck = check.copy(
                primaryReviewers = excludeCreatorCandidates(check.primaryReviewers),
                generalReviewers = excludeCreatorCandidates(check.generalReviewers)
            )
            val lockPrimaryReviewers = hasLockedPrimaryReviewerConstraint(filteredCheck)
            val applyGeneralSuggestion = !initialGeneralReviewersApplied && hasInitialGeneralReviewerSuggestion(filteredCheck)
            if (applyGeneralSuggestion) {
                initialGeneralReviewersApplied = true
            }
            mandatoryPrimaryUsers = if (lockPrimaryReviewers) filteredCheck.primaryReviewers.map { it.username }.toSet() else emptySet()
            minimumPrimary = if (lockPrimaryReviewers) filteredCheck.primaryReviewerNum.coerceAtLeast(1) else 0
            minimumGeneral = 0
            precheckBlockedReason = if (filteredCheck.code == 200) null else filteredCheck.message.ifBlank { "分支不满足创建条件，code=${filteredCheck.code}" }
            SwingUtilities.invokeLater {
                autoMergeLabel.text = "自动合并：" + if (filteredCheck.canBeAutomerge) "可自动合并" else "不可自动合并"
                autoMergeLabel.foreground = if (filteredCheck.canBeAutomerge) {
                    JBColor(Color(0x1E8E3E), Color(0x57D163))
                } else {
                    JBColor(Color(0xF29900), Color(0xF6C26B))
                }
                primaryReviewersField.isEditable = !lockPrimaryReviewers
                setReviewerCountSpinnerEnabled(primaryNumSpinner, !lockPrimaryReviewers)
                generalReviewersField.isEditable = true
                setReviewerCountSpinnerEnabled(generalNumSpinner, true)
                if (lockPrimaryReviewers) {
                    primaryReviewersField.text = filteredCheck.primaryReviewers.joinToString(",") { it.username }
                    primaryNumSpinner.value = filteredCheck.primaryReviewerNum
                }
                if (applyGeneralSuggestion) {
                    generalReviewersField.text = filteredCheck.generalReviewers.joinToString(",") { it.username }
                    generalNumSpinner.value = filteredCheck.generalReviewerNum
                }
                (primaryNumSpinner.model as javax.swing.SpinnerNumberModel).minimum = minimumPrimary
                (generalNumSpinner.model as javax.swing.SpinnerNumberModel).minimum = minimumGeneral
                primaryNumSpinner.value = maxOf((primaryNumSpinner.value as? Int) ?: 0, minimumPrimary)
                generalNumSpinner.value = maxOf((generalNumSpinner.value as? Int) ?: 0, minimumGeneral)
            }
            indexDevelopers(filteredCheck.primaryReviewers + filteredCheck.generalReviewers)
        }

        private fun loadDevelopersByCurrentSelections(source: String, target: String) {
            if (mandatoryPrimaryUsers.isNotEmpty() || minimumGeneral > 0 || minimumPrimary > 0) return
            val developers = loadDevelopersFromApi("")
            indexDevelopers(developers)
            val currentPrimary = parseReviewerInput(primaryReviewersField.text)
            val currentGeneral = parseReviewerInput(generalReviewersField.text)
            if (currentPrimary.isNotEmpty()) {
                minimumPrimary = maxOf(minimumPrimary, 1)
            }
            if (currentGeneral.isNotEmpty()) {
                minimumGeneral = maxOf(minimumGeneral, 1)
            }
            SwingUtilities.invokeLater {
                (primaryNumSpinner.model as javax.swing.SpinnerNumberModel).minimum = minimumPrimary
                (generalNumSpinner.model as javax.swing.SpinnerNumberModel).minimum = minimumGeneral
                primaryNumSpinner.value = maxOf((primaryNumSpinner.value as? Int) ?: 0, minimumPrimary)
                generalNumSpinner.value = maxOf((generalNumSpinner.value as? Int) ?: 0, minimumGeneral)
                if (source == target) {
                    precheckBlockedReason = "源分支和目标分支不能相同"
                }
            }
        }

        private fun loadDevelopersFromApi(keywords: String): List<DeveloperCandidate> {
            if (mockEnabled) {
                val mockJson = readMockJson(mockDevelopersFile)
                    ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockDevelopersFile")
                val root = objectMapper.readTree(mockJson)
                val result = root.get("result") ?: root
                val data = result.get("data")
                val list = data?.get("developers")
                return excludeCreatorCandidates(parseDeveloperCandidates(list))
            }
            val response = apiService.fetchDevelopers(
                sshPath = resolveGitAddress(),
                keyword = keywords
            )
            if (response.statusCode() !in 200..299) return emptyList()
            val root = objectMapper.readTree(response.body())
            val result = root.get("result") ?: root
            val data = result.get("data")
            val list = data?.get("developers")
            return excludeCreatorCandidates(parseDeveloperCandidates(list))
        }

        private fun indexDevelopers(items: List<DeveloperCandidate>) {
            if (items.isEmpty()) return
            items.forEach { item ->
                developerByKey[item.username.trim().lowercase()] = item
                if (item.name.isNotBlank()) {
                    developerByKey[item.name.trim().lowercase()] = item
                }
            }
        }

        private fun computeBranchDiffAndCommits(sourceBranch: String, targetBranch: String) {
            val sourceRef = ensureOriginBranch(sourceBranch)
            val targetRef = ensureOriginBranch(targetBranch)
            val compare = branchService.comparePullRequest(targetRef, sourceRef)
            latestChanges = if (compare.error == null) compare.changes else emptyList()

            val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            latestCommits = if (repo == null) {
                emptyList()
            } else {
                val source = toRemoteBranchRef(repo, sourceBranch)
                val target = toRemoteBranchRef(repo, targetBranch)
                loadPullRequestCommits(repo, target, source)
            }
            SwingUtilities.invokeLater { refreshDiffAndCommitText() }
        }

        private fun refreshDiffAndCommitText() {
            diffSummaryArea.text = if (latestChanges.isEmpty()) {
                "暂无文件改动"
            } else {
                latestChanges.joinToString("\n") { item ->
                    val stats = buildString {
                        if (item.additions > 0) append("+${item.additions} ")
                        if (item.deletions > 0) append("-${item.deletions}")
                    }.trim()
                    "${item.changeType.padEnd(2, ' ')}  ${item.filePath}${if (stats.isBlank()) "" else "  ($stats)"}"
                }
            }
            commitSummaryArea.text = if (latestCommits.isEmpty()) {
                "暂无提交记录"
            } else {
                latestCommits.joinToString("\n") { commit ->
                    "${if (commit.hash.length > 7) commit.hash.take(7) else commit.hash}  ${commit.author}  ${commit.time}  ${commit.message}"
                }
            }
        }

        private fun parseReviewerInput(raw: String): List<String> {
            return raw.split(',', '，', ';', '；', '\n', '\t', ' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }

        private fun resolveReviewerIds(rawInput: String): Pair<List<String>, List<Long>> {
            val names = parseReviewerInput(rawInput)
            val resolved = names.mapNotNull { name -> developerByKey[name.lowercase()] }
            if (names.size != resolved.size) {
                val resolvedKeys = resolved.flatMap { listOf(it.username.lowercase(), it.name.lowercase()) }.toSet()
                val missing = names.filterNot { resolvedKeys.contains(it.lowercase()) }
                throw IllegalArgumentException("评审人未匹配到仓库成员: ${missing.joinToString(",")}")
            }
            return resolved.map { it.username }.distinct() to resolved.map { it.id }.distinct()
        }

        private fun updateValidationState() {
            val msg = precheckBlockedReason
            val blocked = !msg.isNullOrBlank() && msg != "正在校验分支..."
            validationLabel.text = if (msg.isNullOrBlank()) "" else msg
            validationLabel.foreground = if (blocked) {
                JBColor(Color(0xD93025), Color(0xF47067))
            } else {
                JBColor(Color(0x5F6368), Color(0x9AA0A6))
            }
            setOKActionEnabled(msg.isNullOrBlank() || msg == "")
        }

        override fun doOKAction() {
            val title = titleField.text.trim()
            if (title.isBlank()) {
                Messages.showErrorDialog(project, "标题不能为空", "创建 PR")
                return
            }
            val source = (sourceBranchBox.selectedItem as? String).orEmpty().trim()
            val target = (targetBranchBox.selectedItem as? String).orEmpty().trim()
            if (source.isBlank() || target.isBlank()) {
                Messages.showErrorDialog(project, "请选择源分支和目标分支", "创建 PR")
                return
            }
            if (source == target) {
                Messages.showErrorDialog(project, "源分支和目标分支不能相同", "创建 PR")
                return
            }
            if (!precheckBlockedReason.isNullOrBlank()) {
                Messages.showErrorDialog(project, precheckBlockedReason, "创建 PR")
                return
            }

            val (primaryNames, primaryIds) = try {
                resolveReviewerIds(primaryReviewersField.text)
            } catch (e: Exception) {
                Messages.showErrorDialog(project, e.message ?: "关键评审人解析失败", "创建 PR")
                return
            }
            val (generalNames, generalIds) = try {
                resolveReviewerIds(generalReviewersField.text)
            } catch (e: Exception) {
                Messages.showErrorDialog(project, e.message ?: "普通评审人解析失败", "创建 PR")
                return
            }

            val creatorUsername = currentPluginAuthorUsername()
            val creatorSelected = creatorUsername.isNotBlank() &&
                (primaryNames + generalNames).any {
                    it.equals(creatorUsername, ignoreCase = true)
                }
            if (creatorSelected) {
                Messages.showErrorDialog(project, "创建人不能添加为评审人或关键评审人", "创建 PR")
                return
            }

            if (mandatoryPrimaryUsers.isNotEmpty()) {
                val missingPrimary = mandatoryPrimaryUsers.filterNot { primaryNames.contains(it) }
                if (missingPrimary.isNotEmpty()) {
                    Messages.showErrorDialog(project, "关键评审人不可删除，缺失: ${missingPrimary.joinToString(",")}", "创建 PR")
                    return
                }
            }

            val primaryNum = (primaryNumSpinner.value as? Int) ?: 0
            val generalNum = (generalNumSpinner.value as? Int) ?: 0
            val finalMinPrimary = if (minimumPrimary == 0 && primaryIds.isNotEmpty()) 1 else minimumPrimary
            val finalMinGeneral = if (minimumGeneral == 0 && generalIds.isNotEmpty()) 1 else minimumGeneral
            if (primaryNum < finalMinPrimary) {
                Messages.showErrorDialog(project, "关键评审最少通过人数不能小于 $finalMinPrimary", "创建 PR")
                return
            }
            if (primaryNum > primaryIds.size) {
                Messages.showErrorDialog(project, "关键评审最少通过人数不能大于当前已选关键评审人数", "创建 PR")
                return
            }
            if (generalNum < finalMinGeneral) {
                Messages.showErrorDialog(project, "普通评审最少通过人数不能小于 $finalMinGeneral", "创建 PR")
                return
            }
            if (generalNum > generalIds.size) {
                Messages.showErrorDialog(project, "普通评审最少通过人数不能大于当前已选评审人数", "创建 PR")
                return
            }

            val desc = descField.text.trim().ifBlank {
                latestCommits.joinToString("\n") { it.message }.ifBlank { title }
            }
            val mergeType = (mergeTypeBox.selectedItem as? String).orEmpty().trim()
            setOKActionEnabled(false)

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val body = if (mockEnabled) {
                        readMockJson(mockCreatePrFile)
                            ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockCreatePrFile")
                    } else {
                        val response = apiService.createPr(
                            sshPath = resolveGitAddress(),
                            title = title,
                            head = source,
                            base = target,
                            body = desc,
                            assigneesIds = generalIds,
                            assigneesNum = generalNum,
                            primaryAssigneesIds = primaryIds,
                            primaryAssigneesNum = primaryNum,
                            pruneBranch = deleteSourceBranchCheck.isSelected,
                            defaultMergeType = mergeType
                        )
                        if (response.statusCode() !in 200..299) {
                            SwingUtilities.invokeLater {
                                setOKActionEnabled(true)
                                Messages.showErrorDialog(project, "创建失败: ${response.statusCode()}", "创建 PR")
                            }
                            return@executeOnPooledThread
                        }
                        response.body()
                    }
                    val root = objectMapper.readTree(body)
                    val result = root.get("result") ?: root
                    val success = when {
                        result.isBoolean -> result.asBoolean(false)
                        result.isObject -> result.get("success")?.asBoolean(false) ?: (result.get("code")?.asInt() == 200)
                        else -> false
                    }
                    if (!success) {
                        val message = result.readText("message", "code").ifBlank { "创建失败" }
                        SwingUtilities.invokeLater {
                            setOKActionEnabled(true)
                            Messages.showErrorDialog(project, message, "创建 PR")
                        }
                        return@executeOnPooledThread
                    }
                    SwingUtilities.invokeLater {
                        updateStatus("创建 PR 成功")
                        close(OK_EXIT_CODE)
                        resetAndLoad()
                    }
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Create PR failed", e)
                    SwingUtilities.invokeLater {
                        setOKActionEnabled(true)
                        Messages.showErrorDialog(project, e.message ?: "创建失败", "创建 PR")
                    }
                }
            }
        }
    }

    private inner class ReviewDialog(
        project: Project,
        private val onSubmit: (String, String) -> Unit
    ) : com.intellij.openapi.ui.DialogWrapper(project) {
        private val commentRadio = javax.swing.JRadioButton("评论", true)
        private val approveRadio = javax.swing.JRadioButton("允许合并")
        private val rejectRadio = javax.swing.JRadioButton("拒绝")
        private val commentField = createDialogTextArea("请输入评审说明", 4)

        init {
            title = "评审"
            setOKButtonText("提交")
            setCancelButtonText("取消")
            init()
        }

        override fun createCenterPanel(): JComponent {
            ButtonGroup().apply {
                add(commentRadio)
                add(approveRadio)
                add(rejectRadio)
            }

            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(dialogSectionLabel("评审操作"))
                add(createDialogChoiceCard("仅发表评论说明，不改变当前评审结论。", commentRadio))
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(createDialogChoiceCard("标记当前 PR 审查通过。", approveRadio))
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(createDialogChoiceCard("标记当前 PR 审查不通过。", rejectRadio))
                add(Box.createVerticalStrut(JBUI.scale(12)))
                add(dialogSectionLabel("评审说明"))
                add(createDialogTextAreaCard(commentField, JBUI.scale(88)))
            }
            return createDialogRootPanel(null, JBUI.scale(500), JBUI.scale(360), content)
        }

        override fun doOKAction() {
            val comment = commentField.text.trim()
            val state = when {
                approveRadio.isSelected -> "approved"
                rejectRadio.isSelected -> "rejected"
                else -> "commented"
            }
            if (state == "commented" && comment.isBlank()) {
                Messages.showErrorDialog(project, "选择“评论”时，输入框内容不能为空", "评审")
                return
            }
            onSubmit(state, comment)
            super.doOKAction()
        }
    }

    private inner class MergeMethodPickerDialog(project: Project) : com.intellij.openapi.ui.DialogWrapper(project) {
        private val mergeRadio = javax.swing.JRadioButton("Merge", true)
        private val fastForwardRadio = javax.swing.JRadioButton("Merge(Fast-Forward-Only)")

        val selectedMethod: String?
            get() = when {
                mergeRadio.isSelected -> "merge"
                fastForwardRadio.isSelected -> "fast_forward"
                else -> null
            }

        init {
            title = "选择合并方式"
            setOKButtonText("确定")
            setCancelButtonText("取消")
            init()
        }

        override fun createCenterPanel(): JComponent {
            ButtonGroup().apply {
                add(mergeRadio)
                add(fastForwardRadio)
            }

            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(createDialogChoiceCard("总是创建一个合并节点，记录合并信息。", mergeRadio, alignTop = false, padding = JBUI.insets(8, 12), minHeight = 58))
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(createDialogChoiceCard("不创建合并节点，采用Fast-Forward-Only方式合并。", fastForwardRadio, alignTop = false, padding = JBUI.insets(8, 12), minHeight = 58))
            }
            return createDialogRootPanel("选择合并方式", JBUI.scale(520), JBUI.scale(196), content)
        }
    }

    private inner class MergeConfirmDialog(
        project: Project,
        detail: PrDetail,
        private val mergeMethod: String,
        defaultDelete: Boolean,
        private val onSubmit: (String, String, Boolean) -> Unit
    ) : com.intellij.openapi.ui.DialogWrapper(project) {
        private val commitField = createDialogTextArea("请输入提交信息", 3)
        private val extField = createDialogTextArea("请输入扩展信息（可选）", 3)
        private val deleteCheck = JBCheckBox("合并后是否删除源分支", defaultDelete)
        private val usesFastForward = mergeMethod.trim().lowercase() in setOf("fast_forward", "squash")

        init {
            title = "接受PR"
            setOKButtonText("提交")
            setCancelButtonText("取消")
            if (!usesFastForward) {
                commitField.text = buildMergeDefaultCommitMessage(detail)
                extField.text = buildMergeDefaultExtMessage(detail)
            }
            init()
        }

        override fun createCenterPanel(): JComponent {
            deleteCheck.isOpaque = false
            deleteCheck.foreground = detailPrimaryTextColor()
            deleteCheck.font = deleteCheck.font.deriveFont(13f)
            val deleteRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(deleteCheck)
                add(Box.createHorizontalGlue())
            }
            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(dialogSectionLabel("合并方式"))
                add(createDialogInfoCard(detailMergeTypeDisplayText(mergeMethod), mergeMethodOptionText(mergeMethod)))
                add(Box.createVerticalStrut(JBUI.scale(12)))
                if (usesFastForward) {
                    add(createDialogInfoCard("说明", fastForwardMergeNoticeHtml()))
                    add(Box.createVerticalStrut(JBUI.scale(12)))
                } else {
                    add(dialogSectionLabel("提交信息"))
                    add(createDialogTextAreaCard(commitField, JBUI.scale(72)))
                    add(Box.createVerticalStrut(JBUI.scale(12)))
                    add(dialogSectionLabel("扩展信息"))
                    add(createDialogTextAreaCard(extField, JBUI.scale(72)))
                    add(Box.createVerticalStrut(JBUI.scale(12)))
                }
                add(deleteRow.apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
            val preferredHeight = if (usesFastForward) JBUI.scale(312) else JBUI.scale(432)
            return createDialogRootPanel(null, JBUI.scale(520), preferredHeight, content)
        }

        override fun doOKAction() {
            val commitMsg = commitField.text.trim()
            val extMsg = extField.text.trim()
            if (!usesFastForward && commitMsg.isBlank()) {
                Messages.showErrorDialog(project, "提交信息不能为空", "接受PR")
                return
            }
            onSubmit(
                if (usesFastForward) "" else commitMsg,
                if (usesFastForward) "" else extMsg,
                deleteCheck.isSelected
            )
            super.doOKAction()
        }
    }
}
