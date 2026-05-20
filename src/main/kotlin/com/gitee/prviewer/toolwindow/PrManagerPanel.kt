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
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.geom.RoundRectangle2D
import java.util.Properties
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
            g2.clip = shape
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


private class ViewportWidthPanel : JPanel(), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
        return if (orientation == SwingConstants.VERTICAL) {
            (visibleRect.height - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))
        } else {
            (visibleRect.width - JBUI.scale(16)).coerceAtLeast(JBUI.scale(16))
        }
    }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
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

    private val listUrl = buildUrl(config.getProperty("prviewer.api.list.path", "/pset/api/gitee-api/pull-request-reviews/pullRequestsList"))
    private val detailUrl = buildUrl(config.getProperty("prviewer.api.detail.path", "/pset/api/gitee/selectPullRequestInfos"))
    private val noteListUrl = buildUrl(config.getProperty("prviewer.api.noteList.path", "/pset/api/gitee/noteList"))
    private val noteUrl = buildUrl(config.getProperty("prviewer.api.note.path", "/pset/api/gitee/note"))
    private val replyUrl = buildUrl(config.getProperty("prviewer.api.reply.path", "/pset/api/gitee/replyNote"))
    private val resolveUrl = buildUrl(config.getProperty("prviewer.api.resolve.path", "/pset/api/gitee/resoveNote"))
    private val reviewUrl = buildUrl(config.getProperty("prviewer.api.review.path", "/api/pr/review"))
    private val mergeUrl = buildUrl(config.getProperty("prviewer.api.merge.path", "/api/pr/merge"))
    private val aiReviewPrDetailUrl = buildUrl(config.getProperty("prviewer.api.aiReviewPrDetail.path", "/pset/api/gitee/queryAiReviewPrDetailData"))
    private val aiReviewFileDetailUrl = buildUrl(config.getProperty("prviewer.api.aiReviewFileDetail.path", "/pset/api/gitee/queryAiReviewFileIssueDetailData"))
    private val aiHandleIssueUrl = buildUrl(config.getProperty("prviewer.api.aiHandleIssue.path", "/pset/api/gitee/handleAiReviewIssue"))

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
        aiReviewPrDetailUrl = aiReviewPrDetailUrl,
        aiReviewFileDetailUrl = aiReviewFileDetailUrl,
        aiHandleIssueUrl = aiHandleIssueUrl
    )

    private val branchService = BranchCompareService(project)
    private val commentManager = LineCommentManager(project)
    private val diffBinder = DiffEditorBinder(project, commentManager)

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
    private var selectedPrId: Long? = null
    private val loadMoreLabel = JBLabel("加载更多中...", SwingConstants.CENTER)
    private val searchField = JBTextField()
    private val refreshButton = JButton()
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
    private val detailHeaderTitle = JBLabel("-")
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
    private val detailAuthorLabel = OutlinedPillLabel()
    private val detailCreateTimeLabel = OutlinedPillLabel()
    private val detailBranchLabel = OutlinedPillLabel()
    private val issueCountLabel = OutlinedPillLabel()
    private val aiReviewBadgeLabel = OutlinedPillLabel().apply {
        isOpaque = false
        toolTipText = "当前未发起AI评审"
        cursor = Cursor.getDefaultCursor()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val state = aiReviewBadgeState
                if (state == AiReviewBadgeState.NO_DATA) return
                showAiOverviewPopup()
            }
        })
    }
    private val detailMetaRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
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
    }
    private val reviewActionButton = JButton()
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

    private val overviewDesc = JBTextArea()
    private val reviewStatusCardsPanel = JPanel(GridLayout(0, 2, JBUI.scale(12), JBUI.scale(12))).apply {
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
    private val changeTreeToggleButton = JToggleButton("树状").apply {
        isFocusable = false
        isFocusPainted = false
        isSelected = true
        margin = JBUI.insets(0, 10, 0, 10)
    }
    private val changeFlatToggleButton = JToggleButton("平铺").apply {
        isFocusable = false
        isFocusPainted = false
        margin = JBUI.insets(0, 10, 0, 10)
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
        listOf(detailAuthorLabel, detailCreateTimeLabel, detailBranchLabel, issueCountLabel, aiReviewBadgeLabel, fileChangeTabCountLabel, commitTabCountLabel).forEach {
            it.font = it.font.deriveFont(Font.PLAIN, globalUiFontSize - 1f)
        }
        reviewActionButton.font = reviewActionButton.font.deriveFont(Font.PLAIN, globalUiFontSize)

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
        val content = buildContentPanel()
        val contentScroll = JBScrollPane(
            content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
            isWheelScrollingEnabled = false
            horizontalScrollBar.unitIncrement = JBUI.scale(24)
        }
        root.add(contentScroll, BorderLayout.CENTER)
        root.add(buildStatusPanel(), BorderLayout.SOUTH)
        installSearchFieldBlur(root)
        return root
    }

    private fun buildContentPanel(): JPanel {
        val leftPanel = JPanel(BorderLayout())
        leftPanel.add(buildTopBar(), BorderLayout.NORTH)
        leftPanel.add(buildTablePanel(), BorderLayout.CENTER)

        buildDetailPanel()
        val rightPanel = JPanel(BorderLayout())
        rightPanel.border = JBUI.Borders.emptyLeft(8)
        rightPanel.add(detailCard, BorderLayout.CENTER)

        val splitter = OnePixelSplitter(false, 0.45f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel
        return JPanel(BorderLayout()).apply { add(splitter, BorderLayout.CENTER) }
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

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8, 8, 8)
            add(headerBar, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(JBUI.scale(16))
                add(filterBar, BorderLayout.WEST)
            }, BorderLayout.CENTER)
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
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load PR list supplement error: prId=${item.id} iid=${item.iid}", e)
                SwingUtilities.invokeLater {
                    prListSupplementLoading.remove(item.id)
                }
            }
        }
    }

    private fun resolveAiReviewState(overview: AiReviewOverview?): AiReviewBadgeState {
        return when {
            overview == null -> AiReviewBadgeState.NO_DATA
            !overview.validFlag -> AiReviewBadgeState.STALE
            overview.unhandledCount == 0 -> AiReviewBadgeState.PASS
            else -> AiReviewBadgeState.FAIL
        }
    }

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

        (detailCard.layout as java.awt.CardLayout).show(detailCard, "empty")
        renderEmptyDetail()
    }

    private fun buildDetailHeader(): JComponent {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 12, 0)
        }

        val titleRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            val inset = detailHeaderRowSideInset()
            border = JBUI.Borders.empty(0, inset, 10, inset)
            add(detailHeaderTitle)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(detailStatus)
            add(Box.createHorizontalGlue())
            add(detailRefreshButton)
        }
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

    private fun detailSectionTitleFontSize(): Float = globalUiFontSize + 1f

    private fun detailHorizontalInset(): Int {
        val metricsOwner = if (detailHeaderTitle.font != null) detailHeaderTitle else detailTabs
        return metricsOwner.getFontMetrics(metricsOwner.font).charWidth('中').coerceAtLeast(JBUI.scale(12))
    }

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

    private fun buildDetailTabBody(topInset: Int = 12, bottomInset: Int = 10): ViewportWidthPanel {
        return ViewportWidthPanel().apply {
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
        AiReviewBadgeState.STALE -> "AI评审：待更新"
        AiReviewBadgeState.PASS -> "AI评审：通过"
        AiReviewBadgeState.FAIL -> "AI评审：不通过"
    }

    private fun updateDetailMetaRowIndent() {
        val inset = detailHeaderRowSideInset()
        detailMetaRow.border = JBUI.Borders.empty(0, inset, 0, inset)
        detailMetaRow.revalidate()
        detailMetaRow.repaint()
    }

    private fun applyDetailThemeColors() {
        val panelFill = detailPanelFill()
        val tabFill = detailTabPaneFill()
        detailCard.background = panelFill
        detailEmpty.background = panelFill
        detailPanel.background = panelFill
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
    }

    private fun createDetailScrollPane(
        view: Component,
        verticalPolicy: Int,
        horizontalPolicy: Int,
        fillColorProvider: () -> Color
    ): JBScrollPane {
        return object : JBScrollPane(view, verticalPolicy, horizontalPolicy) {
            override fun updateUI() {
                super.updateUI()
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport?.isOpaque = true
                background = fillColorProvider()
                viewport?.background = fillColorProvider()
            }
        }.apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = true
            background = fillColorProvider()
            viewport.background = fillColorProvider()
        }
    }

    private fun styleSegmentedToggle(button: JToggleButton, selected: Boolean) {
        val darkTheme = UIUtil.isUnderDarcula()
        val selectedBackground = if (darkTheme) withAlpha(detailAccentColor, 88) else withAlpha(detailAccentColor, 34)
        val selectedForeground = if (darkTheme) Color.WHITE else UIUtil.getLabelForeground()
        val selectedBorder = if (darkTheme) withAlpha(detailAccentColor, 220) else withAlpha(detailAccentColor, 150)
        button.background = if (selected) selectedBackground else detailSurfaceFill()
        button.foreground = if (selected) selectedForeground else detailMutedColor()
        button.font = button.font.deriveFont(if (selected) Font.BOLD else Font.PLAIN, globalUiFontSize - 1f)
        button.isOpaque = true
        button.isBorderPainted = true
        button.border = if (selected) {
            javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(selectedBorder, JBUI.scale(1)),
                JBUI.Borders.empty(3, 11)
            )
        } else {
            JBUI.Borders.empty(4, 12)
        }
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
        overviewDesc.background = detailSurfaceFill()
        overviewDesc.foreground = detailPrimaryTextColor()
        overviewDesc.alignmentX = Component.LEFT_ALIGNMENT

        val descScroll = createDetailScrollPane(
            overviewDesc,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            fillColorProvider = ::detailSurfaceFill
        ).apply {
            val descLineHeight = overviewDesc.getFontMetrics(overviewDesc.font).height
            val descHeight = descLineHeight * 6 + JBUI.scale(20)
            preferredSize = Dimension(JBUI.scale(320), descHeight)
            minimumSize = Dimension(0, descHeight)
            maximumSize = Dimension(Int.MAX_VALUE, descHeight)
        }

        reviewStatusCardsPanel.alignmentX = Component.LEFT_ALIGNMENT
        renderReviewStatusCards(null)

        panel.add(buildOverviewSection("PR 描述", wrapDetailSurface(descScroll, padding = JBUI.insets(18))))
        panel.add(Box.createVerticalStrut(JBUI.scale(16)))
        panel.add(buildOverviewSection("审查状态", reviewStatusCardsPanel))
        panel.add(Box.createVerticalGlue())

        return createDetailScrollPane(
            panel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            fillColorProvider = ::detailTabPaneFill
        ).apply {
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }
    }

    private fun renderReviewStatusCards(detail: PrDetail?) {
        reviewStatusCardsPanel.removeAll()
        val reviewers = when {
            detail == null -> emptyList()
            detail.reviewerInfos.isNotEmpty() -> detail.reviewerInfos
            else -> (detail.overview.keyReviewers + detail.overview.reviewers)
                .distinct()
                .map { ReviewerInfo(it, "pending") }
        }

        if (reviewers.isEmpty()) {
            reviewStatusCardsPanel.layout = GridLayout(1, 1, 0, 0)
            reviewStatusCardsPanel.add(
                wrapDetailSurface(
                    JBLabel("暂无审查人信息").apply {
                        foreground = detailMutedColor()
                        border = JBUI.Borders.empty(4, 2)
                    },
                    padding = JBUI.insets(14)
                )
            )
        } else {
            reviewStatusCardsPanel.layout = GridLayout((reviewers.size + 1) / 2, 2, JBUI.scale(12), JBUI.scale(12))
            reviewers.forEach { reviewStatusCardsPanel.add(buildReviewStatusCard(it)) }
            if (reviewers.size % 2 != 0) {
                reviewStatusCardsPanel.add(JPanel().apply { isOpaque = false })
            }
        }
        reviewStatusCardsPanel.revalidate()
        reviewStatusCardsPanel.repaint()
    }

    private fun buildReviewStatusCard(reviewer: ReviewerInfo): JComponent {
        val statusColor = reviewerStatusColor(reviewer.approveStatus)
        val statusText = reviewerStatusText(reviewer.approveStatus)
        val normalizedStatus = reviewer.approveStatus.trim().lowercase()
        val statusIcon = when (normalizedStatus) {
            "approved" -> AllIcons.Actions.Checked
            "rejected" -> AllIcons.General.Error
            else -> AllIcons.Actions.Pause
        }
        val avatarBackground = if (reviewer.approveStatus.trim().equals("approved", true)) {
            withAlpha(statusColor, 38)
        } else {
            detailSurfaceFill()
        }

        val avatar = RoundedOutlinePanel(
            fillColor = avatarBackground,
            outlineColor = withAlpha(statusColor, 90),
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

    private fun buildOverviewSection(title: String, body: JComponent): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            val titleLabel = JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, detailSectionTitleFontSize())
                foreground = detailMutedColor()
                border = JBUI.Borders.emptyBottom(8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            body.alignmentX = Component.LEFT_ALIGNMENT
            add(titleLabel)
            add(body)
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
        changeTree.addTreeSelectionListener {
            val node = changeTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val selected = node.userObject as? ChangeItem ?: return@addTreeSelectionListener
            currentDetail ?: return@addTreeSelectionListener
            openDiff(selected)
        }
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
                if (SwingUtilities.isLeftMouseButton(e)) {
                    openChangeFromMouseEvent(e)
                }
                showChangeTreePopup(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val path = resolveTreePathAtPoint(changeTree, e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                if (node.userObject !is String || changeTreeFlatMode) return
                if (changeTree.isExpanded(path)) {
                    changeTree.collapsePath(path)
                } else {
                    changeTree.expandPath(path)
                }
            }
        })

        val togglePanel = JPanel(GridLayout(1, 2, 0, 0)).apply {
            isOpaque = false
            add(changeTreeToggleButton)
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
        val lineColor = withAlpha(UIUtil.getBoundsColor(), 140)
        val dangerColor = JBColor(Color(0xD93025), Color(0xF47067))
        val primaryTextColor = detailPrimaryTextColor()
        val markerColor = lineColor
        val hashColor = primaryTextColor
        val cardFillColor = detailSurfaceFill()
        val cardOutlineColor = detailOutlineColor()
        val title = JBLabel(commit.message.ifBlank { "(无提交信息)" }).apply {
            font = font.deriveFont(Font.BOLD, globalUiFontSize + 0.5f)
            foreground = primaryTextColor
        }
        val hashBadge = buildCommitHashBadge(commit.hash, hashColor)

        val metaLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = true
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
            background = cardFillColor
            add(metaLeft, BorderLayout.WEST)
            if (statsPanel.componentCount > 0) {
                add(statsPanel, BorderLayout.EAST)
            }
        }

        val headerRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = true
            background = cardFillColor
            add(title, BorderLayout.CENTER)
            add(hashBadge, BorderLayout.EAST)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = cardFillColor
            add(headerRow)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(metaRow)
        }

        val card = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = cardFillColor
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(cardOutlineColor, JBUI.scale(1)),
                JBUI.Borders.empty(14)
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
        val label = JBLabel(if (hash.length > 7) hash.take(7) else hash).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            foreground = color
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = hash
            border = JBUI.Borders.empty(2, 8)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e) || hash.isBlank()) return
                    copyToClipboard(hash)
                    updateStatus("已复制提交编号: ${hash.take(7)}")
                }
            })
        }
        return wrapDetailSurface(
            label,
            fillColor = detailSurfaceFill(),
            outlineColor = withAlpha(color, 110),
            fillColorProvider = ::detailSurfaceFill,
            outlineColorProvider = { withAlpha(color, 110) },
            padding = JBUI.insets(0)
        )
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
            detailTabs.addChangeListener { updateDetailTabHeaderStates() }
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
    }

    private fun dismissSearchFieldFocus() {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (focusOwner === searchField || focusOwner === changeSearchField) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
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

    private fun loadPrs(append: Boolean = false) {
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
                val result = if (mockEnabled) {
                    val mockJson = readMockJson(mockListFile) ?: ""
                    if (mockJson.isBlank()) {
                        PrListResult(0, emptyList(), 0, 0)
                    } else {
                        buildMockPrListResult(mockJson, append)
                    }
                } else {
                    val requestBody = buildListRequestBody(append)
                    val response = apiService.fetchPrList(requestBody)
                    if (response.statusCode() !in 200..299) {
                        PrManagerFileLogger.warn("Load PR list failed, status=${response.statusCode()}")
                        PrListResult(0, emptyList(), 0, 0)
                    } else {
                        parsePrList(response.body())
                    }
                }

                SwingUtilities.invokeLater {
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

    private fun buildMockPrListResult(mockJson: String, append: Boolean): PrListResult {
        val parsed = parsePrList(mockJson)
        val keyword = searchField.text?.trim().orEmpty().lowercase()
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

    private fun buildListRequestBody(append: Boolean): String {
        val status = when (activeFilter) {
            PrFilter.OPEN -> "opened"
            PrFilter.CLOSED -> "closed"
            PrFilter.MERGED -> "merged"
            PrFilter.ALL -> "all"
        }
        val pageValue = if (append) currentPage + 1 else 1
        val currentUser = System.getenv("USERID").orEmpty().trim()
        val payload = linkedMapOf(
            "sshPath" to resolveGitAddress(),
            "page" to pageValue,
            "perPage" to pageSize,
            "states" to listOf(status),
            "sourceBranch" to "",
            "targetBranch" to "",
            "keywords" to (searchField.text?.trim() ?: "")
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
        reviewActionButton.isVisible = false

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

    private fun showDetail(prId: Long) {
        selectPrCard(prId)
        currentDetailId = prId
        changeTreeFlatMode = false
        (detailCard.layout as java.awt.CardLayout).show(detailCard, "detail")
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
        currentAiOverview = null
        aiIssueCountByFileMap = emptyMap()
        reviewIssueCountByFileMap = emptyMap()
        currentFileChanges = emptyList()
        currentDiffFilePath = null
        mockAiIssueStatusOverrides.clear()
        updateAiReviewBadge(AiReviewBadgeState.NO_DATA)
        reviewActionButton.isVisible = false
        changeSearchField.text = ""
        changeSummaryLabel.text = "0 个文件变更"
        changeAdditionsLabel.text = "+0 additions"
        changeDeletionsLabel.text = "-0 deletions"
        updateChangeModeToggleStyle()
        renderReviewStatusCards(null)
        renderCommitTimeline(emptyList())
        PrManagerFileLogger.info("Start loading PR detail: prId=$prId")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val listItem = tableModel.findById(prId)
                val detail = if (mockEnabled) {
                    val mockJson = readMockJson(mockDetailFile)
                        ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockDetailFile")
                    parseDetail(mockJson, listItem)
                } else {
                    val response = apiService.fetchPrDetail(prId)
                    if (response.statusCode() !in 200..299) {
                        PrManagerFileLogger.warn("Load PR detail failed: prId=$prId status=${response.statusCode()}")
                        updateStatus("详情加载失败: ${response.statusCode()}")
                        return@executeOnPooledThread
                    }
                    parseDetail(response.body(), listItem)
                }
                SwingUtilities.invokeLater {
                    currentDetail = detail
                    renderDetail(detail)
                }
                PrManagerFileLogger.info("PR detail loaded: prId=$prId iid=${detail.iid}, srBranch=${detail.sourceBranch}, trBranch=${detail.targetBranch}")
                loadNotes(detail)
                loadAiReviewOverview(detail)
                fetchRemoteBranches()
                updateFileChangeBranchWarning(detail.sourceBranch)
                loadFileChanges(detail)
                loadCommitRecords(detail)
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load PR detail error: prId=$prId", e)
                updateStatus("详情加载失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun renderDetail(detail: PrDetail) {
        val number = if (detail.iid > 0) detail.iid else detail.id
        detailHeaderTitle.text = "#${number} ${detail.title}"
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

        overviewDesc.text = detail.overview.desc.ifBlank { "暂无描述" }
        renderReviewStatusCards(detail)
        keyReviewersField.text = detail.overview.keyReviewers.joinToString(",").ifBlank { "暂无关键评审人员" }
        keyReviewersField.toolTipText = detail.overview.keyReviewers.joinToString(",").ifBlank { null }
        keyReviewerHint.text = "至少需要 ${detail.overview.needKeyReviewers} 名关键评审成员评审通过后可合并"

        reviewersField.text = detail.overview.reviewers.joinToString(",").ifBlank { "暂无普通评审人员" }
        reviewersField.toolTipText = detail.overview.reviewers.joinToString(",").ifBlank { null }
        reviewerHint.text = "至少需要 ${detail.overview.needReviewers} 名普通评审成员评审通过后可合并"
        updateDetailTabCounters(0, 0)

//        setupReviewAction(detail)
    }

    private fun setupReviewAction(detail: PrDetail) {
        val currentUser = System.getenv("USERID").orEmpty()
        val isAuthor = currentUser.isNotBlank() && currentUser == detail.author
        val isReviewer = detail.overview.keyReviewers.contains(currentUser) || detail.overview.reviewers.contains(currentUser)

        reviewActionButton.isVisible = false
        reviewActionButton.actionListeners.forEach { reviewActionButton.removeActionListener(it) }

        when {
            isAuthor && detail.reviewPass -> {
                reviewActionButton.text = "接受PR"
                reviewActionButton.isVisible = true
                reviewActionButton.addActionListener { openMergeDialog(detail) }
            }
            isReviewer -> {
                reviewActionButton.text = "评审通过"
                reviewActionButton.isVisible = true
                reviewActionButton.addActionListener { confirmReviewPass(detail.id) }
            }
        }
    }

    private fun confirmReviewPass(prId: Long) {
        val ok = Messages.showYesNoDialog(project, "确认通过此PR？", "评审通过", "确定", "取消", null)
        if (ok != Messages.YES) return
        if (mockEnabled) {
            updateStatus("Mock模式：评审通过")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val response = apiService.reviewPass(prId)
                if (response.statusCode() !in 200..299) {
                    PrManagerFileLogger.warn("Review pass failed: prId=$prId status=${response.statusCode()}")
                    updateStatus("评审失败: ${response.statusCode()}")
                    return@executeOnPooledThread
                }
                PrManagerFileLogger.info("Review pass success: prId=$prId")
                updateStatus("评审通过")
            } catch (e: Exception) {
                PrManagerFileLogger.error("Review pass error: prId=$prId", e)
                updateStatus("评审失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun openMergeDialog(detail: PrDetail) {
        val dialog = MergeDialog(project, detail.overview.deleteBranchAfterMerged) { commitMsg, extMsg, deleteBranch ->
            requestMerge(detail.id, commitMsg, extMsg, deleteBranch)
        }
        dialog.show()
    }

    private fun requestMerge(prId: Long, commitMsg: String, extMsg: String, deleteBranch: Boolean) {
        if (mockEnabled) {
            updateStatus("Mock模式：已提交合并")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val response = apiService.mergePr(
                    id = prId,
                    commitMsg = commitMsg,
                    extMsg = extMsg,
                    deleteBranchAfterMerged = deleteBranch
                )
                if (response.statusCode() !in 200..299) {
                    PrManagerFileLogger.warn("Merge failed: prId=$prId status=${response.statusCode()}")
                    updateStatus("合并失败: ${response.statusCode()}")
                    return@executeOnPooledThread
                }
                PrManagerFileLogger.info("Merge submitted: prId=$prId deleteBranch=$deleteBranch")
                updateStatus("已提交合并")
            } catch (e: Exception) {
                PrManagerFileLogger.error("Merge error: prId=$prId", e)
                updateStatus("合并失败: ${e.message ?: "未知错误"}")
            }
        }
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

    private fun loadAiReviewOverview(detail: PrDetail) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val overview = if (mockEnabled) {
                    val mockJson = readMockJson("ai-review-overview.json")
                    if (mockJson.isNullOrBlank()) null else parseAiReviewOverview(mockJson)
                } else {
                    val response = apiService.fetchAiReviewOverview(detail.id)
                    if (response.statusCode() !in 200..299) {
                        PrManagerFileLogger.warn("Load AI overview failed: prId=${detail.id} status=${response.statusCode()}")
                        null
                    } else {
                        parseAiReviewOverview(response.body())
                    }
                }
                SwingUtilities.invokeLater {
                    currentAiOverview = overview
                    aiIssueCountByFileMap = overview?.takeIf { it.validFlag }?.let { flattenAiTreeIssueCount(it.fileTreeNodes) }.orEmpty()
                    val state = when {
                        overview == null -> AiReviewBadgeState.NO_DATA
                        !overview.validFlag -> AiReviewBadgeState.STALE
                        overview.unhandledCount == 0 -> AiReviewBadgeState.PASS
                        else -> AiReviewBadgeState.FAIL
                    }
                    updateAiReviewBadge(state)
                    changeTree.repaint()
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load AI overview error: prId=${detail.id}", e)
                SwingUtilities.invokeLater {
                    currentAiOverview = null
                    aiIssueCountByFileMap = emptyMap()
                    updateAiReviewBadge(AiReviewBadgeState.NO_DATA)
                    changeTree.repaint()
                }
            }
        }
    }

    private fun updateAiReviewBadge(state: AiReviewBadgeState) {
        aiReviewBadgeState = state
        aiReviewBadgeLabel.setPill(aiReviewBadgeText(state), state.color)
        aiReviewBadgeLabel.cursor = if (state == AiReviewBadgeState.NO_DATA) Cursor.getDefaultCursor() else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        aiReviewBadgeLabel.toolTipText = when (state) {
            AiReviewBadgeState.NO_DATA -> "当前未发起AI评审"
            AiReviewBadgeState.STALE -> "AI评审结果已过期，点击查看详情"
            else -> "查看AI评审总览"
        }
        aiReviewBadgeLabel.repaint()
    }

    private fun showAiOverviewPopup() {
        val overview = currentAiOverview ?: return
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(10)
        val tableOuterInset = JBUI.scale(0)
        val contentLeftInset = tableOuterInset

        val title = JBLabel("智能代码评审-总览")
        title.font = title.font.deriveFont(Font.BOLD, globalUiFontSize + 1f)
        title.border = JBUI.Borders.emptyLeft(contentLeftInset)
        panel.add(title)

        if (aiReviewBadgeState == AiReviewBadgeState.STALE) {
            panel.add(Box.createVerticalStrut(8))
            val warn = JBLabel("PR涉及分支有代码变动，当前智能代码评审数据已过期，请重新触发")
            warn.foreground = JBColor(Color(0xD93025), Color(0xF47067))
            panel.add(warn)
        }

        panel.add(Box.createVerticalStrut(10))
        val rows = listOf(
            arrayOf("错误问题数", overview.errorCount.toString()),
            arrayOf("警告问题数", overview.warnCount.toString()),
            arrayOf("待处理问题数", overview.unhandledCount.toString()),
            arrayOf("采纳问题数", overview.adoptedCount.toString()),
            arrayOf("忽略问题数", overview.ignoredCount.toString()),
            arrayOf("误报问题数", overview.misreportedCount.toString())
        )
        val table = JBTable(object : AbstractTableModel() {
            override fun getRowCount(): Int = rows.size
            override fun getColumnCount(): Int = 2
            override fun getColumnName(column: Int): String = if (column == 0) "  问题类型" else "  问题个数"
            override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex][columnIndex]
            override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        })
        table.rowHeight = JBUI.scale(24)
        table.setShowGrid(true)
        table.fillsViewportHeight = true
        if (table.columnModel.columnCount > 1) {
            val halfWidth = JBUI.scale(48)
            table.columnModel.getColumn(0).preferredWidth = halfWidth
            table.columnModel.getColumn(1).preferredWidth = halfWidth
        }
        val leftHeaderRenderer = (table.tableHeader.defaultRenderer as? javax.swing.table.DefaultTableCellRenderer)
            ?: javax.swing.table.DefaultTableCellRenderer()
        leftHeaderRenderer.horizontalAlignment = SwingConstants.LEFT
        table.tableHeader.defaultRenderer = leftHeaderRenderer
        val highlightedDividerRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: javax.swing.JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                horizontalAlignment = SwingConstants.LEFT
                val padding = JBUI.Borders.empty(0, JBUI.scale(8), 0, JBUI.scale(8))
                border = if (row == 1) {
                    javax.swing.BorderFactory.createCompoundBorder(
                        javax.swing.BorderFactory.createMatteBorder(
                            0,
                            0,
                            JBUI.scale(2),
                            0,
                            JBColor(Color(0x8A8A8A), Color(0x6B7280))
                        ),
                        padding
                    )
                } else {
                    padding
                }
                return component
            }
        }
        table.setDefaultRenderer(Any::class.java, highlightedDividerRenderer)
        val tableScrollPane = JBScrollPane(table).apply {
            border = JBUI.Borders.empty(0)
            preferredSize = Dimension(JBUI.scale(150), JBUI.scale(170))
        }

        val tableContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 6, 0, 6)
            add(tableScrollPane, BorderLayout.CENTER)
        }
        panel.add(tableContainer)

        panel.add(Box.createVerticalStrut(6))
        val relation = JBLabel("关系：错误问题数 + 警告问题数 = 待处理问题数 + 采纳问题数 + 忽略问题数 + 误报问题数")
        relation.foreground = UIUtil.getInactiveTextColor()
        relation.border = JBUI.Borders.emptyLeft(contentLeftInset)
        panel.add(relation)

        panel.add(Box.createVerticalStrut(6))
        val pass = overview.unhandledCount == 0
        val result = JBLabel("评审结果：${if (pass) "通过" else "不通过"}")
        result.foreground = if (pass) JBColor(Color(0x1E8E3E), Color(0x57D163)) else JBColor(Color(0xD93025), Color(0xF47067))
        result.border = JBUI.Borders.emptyLeft(contentLeftInset)
        panel.add(result)

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle("AI评审结果")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .show(RelativePoint.getSouthOf(aiReviewBadgeLabel))
    }

    private fun loadAiReviewFileIssues(detail: PrDetail, filePath: String): List<AiReviewIssue> {
        if (!currentAiOverview?.validFlag.orFalse()) return emptyList()
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

    private fun loadFileChanges(detail: PrDetail) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val baseRef = detail.baseCommitSha.trim().takeIf { it.isNotBlank() }
                    ?: ensureOriginBranch(target = detail.targetBranch)
                val headRef = detail.headCommitSha.trim().takeIf { it.isNotBlank() }
                    ?: ensureOriginBranch(target = detail.sourceBranch)
                PrManagerFileLogger.info("Start loading file changes: base=$baseRef head=$headRef")
                val result = branchService.compare(baseRef, headRef)
                SwingUtilities.invokeLater {
                    if (result.error != null) {
                        PrManagerFileLogger.warn("Load file changes failed: ${result.error}")
                        currentFileChanges = emptyList()
                        changeTreeRoot.removeAllChildren()
                        changeSummaryLabel.text = "0 个文件变更"
                        changeAdditionsLabel.text = "+0 additions"
                        changeDeletionsLabel.text = "-0 deletions"
                        changeTree.emptyText.text = result.error
                        changeTreeModel.reload()
                        updateDetailTabCounters(fileCount = 0)
                        return@invokeLater
                    }
                    buildChangeTree(result.changes)
                }
                PrManagerFileLogger.info("File changes loaded: count=${result.changes.size}")
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load file changes error: prId=${detail.id}", e)
            }
        }
    }

    private fun buildChangeTree(changes: List<ChangeItem>) {
        currentFileChanges = changes
        changeTree.emptyText.text = if (changes.isEmpty()) "暂无对比结果" else "未找到匹配文件"
        applyChangeTreeFilter()
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

    private fun openChangeFromMouseEvent(e: MouseEvent) {
        val path = resolveTreePathAtPoint(changeTree, e.x, e.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val change = node.userObject as? ChangeItem ?: return
        changeTree.selectionPath = path
        currentDetail ?: return
        openDiff(change)
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
        if (!currentAiOverview?.validFlag.orFalse()) return 0 to 0
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
        val baseRef = detail.baseCommitSha.trim().takeIf { it.isNotBlank() }
            ?: ensureOriginBranch(target = detail.targetBranch)
        val headRef = detail.headCommitSha.trim().takeIf { it.isNotBlank() }
            ?: ensureOriginBranch(target = detail.sourceBranch)
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
                        commentManager.setAiIssueHandler { issueId, status, onDone ->
                            handleAiIssue(detail, change.filePath, issueId, status, onDone)
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

    private fun loadCommitRecords(detail: PrDetail) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fallbackCommits = detail.commits.sortedByDescending { it.time }

                val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
                if (repo == null) {
                    PrManagerFileLogger.warn("Load commit records fallback: repository not found")
                    SwingUtilities.invokeLater {
                        commitTableModel.setRows(fallbackCommits)
                        renderCommitTimeline(fallbackCommits)
                        updateCommitWarning(false)
                    }
                    return@executeOnPooledThread
                }

                val baseCommit = detail.baseCommitSha.trim()
                val headCommit = detail.headCommitSha.trim()
                val range = if (baseCommit.isNotBlank() && headCommit.isNotBlank()) {
                    val mergeBase = resolveMergeBase(repo, baseCommit, headCommit)
                    if (!mergeBase.isNullOrBlank()) "$mergeBase..$headCommit" else "$baseCommit..$headCommit"
                } else {
                    val sourceRef = toRemoteBranchRef(repo, detail.sourceBranch)
                    val targetRef = toRemoteBranchRef(repo, detail.targetBranch)
                    if (sourceRef.isBlank() || targetRef.isBlank()) {
                        PrManagerFileLogger.warn("Load commit records fallback: invalid refs sourceRef=$sourceRef targetRef=$targetRef")
                        SwingUtilities.invokeLater {
                            commitTableModel.setRows(fallbackCommits)
                            renderCommitTimeline(fallbackCommits)
                            updateCommitWarning(false)
                        }
                        return@executeOnPooledThread
                    }
                    val mergeBase = resolveMergeBase(repo, targetRef, sourceRef)
                    if (!mergeBase.isNullOrBlank()) "$mergeBase..$sourceRef" else "$targetRef..$sourceRef"
                }

                var commits = loadCommitsByRange(repo, range)
                if (commits.isEmpty()) commits = fallbackCommits
                val missingHashes = if (commits.isEmpty()) emptySet() else findMissingCommitsInCurrentBranch(repo, commits)
                PrManagerFileLogger.info("Commit records loaded: prId=${detail.id} count=${commits.size} missing=${missingHashes.size}")
                SwingUtilities.invokeLater {
                    commitTableModel.setRows(commits, missingHashes)
                    renderCommitTimeline(commits, missingHashes)
                    updateCommitWarning(missingHashes.isNotEmpty())
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load commit records error: prId=${detail.id}", e)
                SwingUtilities.invokeLater {
                    val fallbackCommits = detail.commits.sortedByDescending { it.time }
                    commitTableModel.setRows(fallbackCommits)
                    renderCommitTimeline(fallbackCommits)
                    updateCommitWarning(false)
                }
            }
        }
    }

    private fun findMissingCommitsInCurrentBranch(
        repo: git4idea.repo.GitRepository,
        commits: List<CommitItem>
    ): Set<String> {
        if (commits.isEmpty()) return emptySet()
        val missing = mutableSetOf<String>()
        commits.forEach { commit ->
            val hash = commit.hash.trim()
            if (hash.isBlank()) return@forEach
            val handler = GitLineHandler(project, repo.root, GitCommand.MERGE_BASE)
            handler.addParameters("--is-ancestor", hash, "HEAD")
            val result = Git.getInstance().runCommand(handler)
            if (!result.success()) {
                missing.add(hash)
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
            "--pretty=format:%H%x09%an%x09%ad%x09%s",
            "--date=iso",
            range
        )
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()
        val commits = result.output
            .mapNotNull { parseCommitLine(it) }
            .sortedByDescending { it.time }
        return enrichCommitStats(repo, commits)
    }

    private fun enrichCommitStats(repo: git4idea.repo.GitRepository, commits: List<CommitItem>): List<CommitItem> {
        if (commits.isEmpty()) return commits
        return commits.map { commit ->
            val (additions, deletions) = loadCommitStat(repo, commit.hash)
            if (additions == 0 && deletions == 0) commit else commit.copy(additions = additions, deletions = deletions)
        }
    }

    private fun loadCommitStat(repo: git4idea.repo.GitRepository, hash: String): Pair<Int, Int> {
        if (hash.isBlank()) return 0 to 0
        val handler = GitLineHandler(project, repo.root, GitCommand.SHOW)
        handler.addParameters("--shortstat", "--format=", hash)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return 0 to 0
        val summaryLine = result.output.firstOrNull { it.contains("insertion") || it.contains("deletion") || it.contains("changed") }
            ?: return 0 to 0
        val additions = Regex("(\\d+) insertion").find(summaryLine)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val deletions = Regex("(\\d+) deletion").find(summaryLine)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return additions to deletions
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
            PrState.OPEN -> StatusBadge("开启的", JBColor(Color(0x1E8E3E), Color(0x57D163)))
            PrState.MERGED -> StatusBadge("已合并", JBColor(Color(0x8E24AA), Color(0xC77DFF)))
            PrState.CLOSED -> StatusBadge("已关闭", JBColor(Color(0xD93025), Color(0xF47067)))
        }
    }

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
                ReviewerInfo(username = username, approveStatus = approveStatus)
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
                    canBeMerge = canBeMerge
                )
            )
        }
        return PrListResult(totalSize, list, page, totalPage)
    }

    private fun parseDetail(body: String, fallback: PrItem?): PrDetail {
        val root = objectMapper.readTree(body)
        val result = root.get("result") ?: root
        val data = result.get("data")
        val baseInfo = data?.get("pullRequestsBaseInfo") ?: data

        val id = baseInfo?.get("id")?.asLong() ?: fallback?.id ?: -1L
        val iid = baseInfo?.get("iid")?.asLong() ?: fallback?.iid ?: -1L
        val title = baseInfo?.get("title")?.asText() ?: fallback?.title ?: ""
        val status = baseInfo?.get("state")?.asText() ?: fallback?.state?.name?.lowercase() ?: ""
        val sourceBranch = baseInfo?.get("sourceBranch")?.asText() ?: fallback?.sourceBranch ?: ""
        val targetBranch = baseInfo?.get("targetBranch")?.asText() ?: fallback?.targetBranch ?: ""
        val author = baseInfo?.get("userName")?.asText() ?: fallback?.author ?: ""
        val createTime = baseInfo?.get("createdAt")?.asText() ?: ""
        val headCommitSha = baseInfo?.get("headCommitSha")?.asText() ?: ""
        val baseCommitSha = baseInfo?.get("baseCommitSha")?.asText() ?: ""

        val overview = PrOverview(
            desc = title,
            keyReviewers = fallback?.keyReviewers ?: emptyList(),
            needKeyReviewers = fallback?.needKeyReviewers ?: 0,
            reviewers = fallback?.generalReviewers ?: emptyList(),
            needReviewers = fallback?.needReviewers ?: 0,
            mergedType = "",
            deleteBranchAfterMerged = false
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
            createTime = createTime,
            headCommitSha = headCommitSha,
            baseCommitSha = baseCommitSha,
            reviewPass = fallback?.canBeMerge ?: false,
            overview = overview,
            reviewerInfos = fallback?.reviewers ?: emptyList(),
            commits = commits
        )
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
        val prId = result.get("prId")?.asLong() ?: return null
        val validFlag = result.get("validFlag")?.asBoolean() == true
        val fileTree = result.get("fileTreeNode")
        return AiReviewOverview(
            prId = prId,
            validFlag = validFlag,
            errorCount = result.get("aiCodeReviewIssueErrorCount")?.asInt() ?: 0,
            warnCount = result.get("aiCodeReviewIssueWarnCount")?.asInt() ?: 0,
            unhandledCount = result.get("aiCodeReviewIssueUnhandledCount")?.asInt() ?: 0,
            adoptedCount = result.get("aiCodeReviewIssueAdoptedCount")?.asInt() ?: 0,
            ignoredCount = result.get("aiCodeReviewIssueIgnoredCount")?.asInt() ?: 0,
            misreportedCount = result.get("aiCodeReviewIssueMisreportedCount")?.asInt() ?: 0,
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

    private fun handleAiIssue(detail: PrDetail, filePath: String, issueId: Long, issueStatus: Int, onDone: (Boolean) -> Unit) {
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
                        val response = apiService.handleAiReviewIssue(issueId, issueStatus, currentUser)
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
            "open" -> JBColor(Color(0x1A73E8), Color(0x6EA8FF))
            "merged" -> JBColor(Color(0x1E8E3E), Color(0x57D163))
            "closed" -> JBColor(Color(0xD93025), Color(0xF47067))
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
        val selectedBackground = Color.WHITE
        val normalBackground = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))
        val selectedForeground = JBColor(Color(0x111827), Color(0x111827))
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
            border = JBUI.Borders.empty(3, 0, 3, 6)
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
            aiIssueLabel.font = metaFont
            aiIssueLabel.verticalAlignment = SwingConstants.CENTER
            aiIssueLabel.horizontalAlignment = SwingConstants.LEFT
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
            val aiStats = aiIssueCountByFileMap[normalizedFilePath] ?: (0 to 0)
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
        "approved" -> JBColor(Color(0x1E8E3E), Color(0x57D163))
        "commented" -> JBColor(Color(0xF29900), Color(0xF6C26B))
        "rejected" -> JBColor(Color(0xD93025), Color(0xF47067))
        else -> JBColor(Color(0x5F6368), Color(0x9AA0A6))
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

                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    dismissSearchFieldFocus()
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

    private enum class AiReviewBadgeState(val color: Color) {
        NO_DATA(JBColor(Color(0x9AA0A6), Color(0x6B7280))),
        STALE(JBColor(Color(0xF29900), Color(0xF6C26B))),
        PASS(JBColor(Color(0x1E8E3E), Color(0x57D163))),
        FAIL(JBColor(Color(0xD93025), Color(0xF47067)))
    }

    private data class AiReviewOverview(
        val prId: Long,
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
        val username: String,
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
        val canBeMerge: Boolean
    )

    private data class PrDetail(
        val id: Long,
        val iid: Long,
        val title: String,
        val status: String,
        val sourceBranch: String,
        val targetBranch: String,
        val author: String,
        val createTime: String,
        val headCommitSha: String,
        val baseCommitSha: String,
        val reviewPass: Boolean,
        val overview: PrOverview,
        val reviewerInfos: List<ReviewerInfo>,
        val commits: List<CommitItem>
    )

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

    private inner class MergeDialog(
        project: Project,
        defaultDelete: Boolean,
        private val onSubmit: (String, String, Boolean) -> Unit
    ) : com.intellij.openapi.ui.DialogWrapper(project) {
        private val commitField = JBTextArea()
        private val extField = JBTextArea()
        private val deleteCheck = JBCheckBox("是否删除源分支", defaultDelete)

        init {
            title = "Merge"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

            commitField.lineWrap = true
            commitField.rows = 3
            extField.lineWrap = true
            extField.rows = 3

            panel.add(section("提交信息", JBScrollPane(commitField)))
            panel.add(section("扩展信息", JBScrollPane(extField)))
            panel.add(deleteCheck)
            return panel
        }

        override fun doOKAction() {
            val commitMsg = commitField.text.trim()
            val extMsg = extField.text.trim()
            if (commitMsg.isBlank() || extMsg.isBlank()) {
                Messages.showErrorDialog("提交信息和扩展信息不能为空", "提示")
                return
            }
            onSubmit(commitMsg, extMsg, deleteCheck.isSelected)
            super.doOKAction()
        }
    }
}
