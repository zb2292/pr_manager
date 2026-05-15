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
import java.util.Properties
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.JToggleButton
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
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = height
            g2.color = badgeColor
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = badgeColor
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private fun withAlpha(color: Color, alpha: Int): Color {
    return Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
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
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(16)
            g2.color = withAlpha(pillColor, 38)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = withAlpha(pillColor, 90)
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
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
    private val prTable = JBTable(tableModel)
    private var prTableScrollPane: JBScrollPane? = null
    private val loadMoreLabel = JBLabel("加载更多中...", SwingConstants.CENTER)
    private val searchField = JBTextField()
    private val createdByMeCheck = JBCheckBox("我创建的").apply {
        isFocusable = false
        isFocusPainted = false
    }
    private val reviewedByMeCheck = JBCheckBox("我评审的").apply {
        isFocusable = false
        isFocusPainted = false
    }
    private val filterCheckPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(createdByMeCheck)
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(reviewedByMeCheck)
    }
    private val refreshButton = JButton()
    private var suppressFilterCheckEvent = false
    private val filterTabsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }
    private var filterTabsMinWidth = 0
    private val filterButtons = listOf(
        JToggleButton("开启的"),
        JToggleButton("已合并"),
        JToggleButton("已关闭")
    )

    private var activeFilter = PrFilter.OPEN
    private var currentPage = 1
    private var totalPage = 0
    private var totalCount = 0
    private var isLoading = false
    private var userTriggeredListScroll = false
    private var lastListScrollValue = 0
    private var hasMorePrs = false
    private val pageSize = config.getProperty("prviewer.api.pageSize", "10").toIntOrNull() ?: 10

    private val detailCard = JPanel(java.awt.CardLayout())
    private val detailEmpty = JPanel(BorderLayout())
    private val detailPanel = JPanel(BorderLayout())
    private val detailHeaderTitle = JBLabel("-")
    private val detailStatus: StatusBadgeLabel = StatusBadgeLabel()
    private val detailAuthorLabel = OutlinedPillLabel()
    private val detailCreateTimeLabel = OutlinedPillLabel()
    private val detailBranchLabel = OutlinedPillLabel()
    private val issueCountLabel = OutlinedPillLabel()
    private val aiReviewBadgeLabel = JBLabel().apply {
        isOpaque = false
        icon = AiBadgeIcon(AiReviewBadgeState.NO_DATA.color)
        toolTipText = "当前无AI评审结果"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        val badgeSize = Dimension(JBUI.scale(20), JBUI.scale(20))
        preferredSize = badgeSize
        minimumSize = badgeSize
        maximumSize = badgeSize
        border = JBUI.Borders.empty()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val state = aiReviewBadgeState
                if (state == AiReviewBadgeState.NO_DATA) return
                showAiOverviewPopup()
            }
        })
    }
    private val reviewActionButton = JButton()
    private val detailTabs = JBTabbedPane()
    private val fileChangeTabTitleLabel = JBLabel("文件改动")
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
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    toggleFileChangeWarningBalloon()
                }
            }
        })
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
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    toggleCommitWarningBalloon()
                }
            }
        })
    }
    private var fileChangeWarningText: String? = null
    private var commitWarningText: String? = null
    private var commitWarningBalloon: Balloon? = null
    private var fileChangeWarningBalloon: Balloon? = null

    private val overviewDesc = JBTextArea()
    private val keyReviewersField = JBTextField()
    private val keyReviewerHint = JBLabel("-")
    private val reviewersField = JBTextField()
    private val reviewerHint = JBLabel("-")
    private val mergeTypeField = JBTextField()
    private val deleteBranchCheck = JBCheckBox("合并后删除源分支")

    private val changeTreeRoot = DefaultMutableTreeNode("ROOT")
    private val changeTreeModel = DefaultTreeModel(changeTreeRoot)
    private val changeTree = Tree(changeTreeModel)
    private val commitTableModel = CommitTableModel()
    private val commitTable = JBTable(commitTableModel)

    private var currentDetail: PrDetail? = null
    private var currentAiOverview: AiReviewOverview? = null
    private var aiReviewBadgeState: AiReviewBadgeState = AiReviewBadgeState.NO_DATA
    private var aiIssueCountByFileMap: Map<String, Pair<Int, Int>> = emptyMap()
    private var currentDiffFilePath: String? = null
    private val mockAiIssueStatusOverrides = mutableMapOf<Long, Int>()

    init {
        applyGlobalFontSettings()
        setContent(buildMainPanel())
        bindActions()
        bindCommentActions()
        PrManagerFileLogger.info("PR Manager panel initialized, mockEnabled=$mockEnabled")
        resetAndLoad()
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
        createdByMeCheck.font = createdByMeCheck.font.deriveFont(Font.PLAIN, globalUiFontSize)
        reviewedByMeCheck.font = reviewedByMeCheck.font.deriveFont(Font.PLAIN, globalUiFontSize)

        prTable.font = prTable.font.deriveFont(Font.PLAIN, globalUiFontSize)
        prTable.tableHeader.font = prTable.tableHeader.font.deriveFont(Font.BOLD, globalUiFontSize)

        filterButtons.forEach { button ->
            button.font = button.font.deriveFont(Font.PLAIN, globalUiFontSize)
        }

        detailHeaderTitle.font = detailHeaderTitle.font.deriveFont(Font.BOLD, globalUiFontSize + 1f)
        detailStatus.font = detailStatus.font.deriveFont(Font.PLAIN, globalUiFontSize)
        listOf(detailAuthorLabel, detailCreateTimeLabel, detailBranchLabel, issueCountLabel).forEach {
            it.font = it.font.deriveFont(Font.PLAIN, globalUiFontSize)
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

        changeTree.font = changeTree.font.deriveFont(Font.PLAIN, globalUiFontSize)
        commitTable.font = commitTable.font.deriveFont(Font.PLAIN, globalUiFontSize)
        commitTable.tableHeader.font = commitTable.tableHeader.font.deriveFont(Font.BOLD, globalUiFontSize)
    }

    private fun buildMainPanel(): JPanel {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(8)
        val contentScroll = JBScrollPane(
            buildContentPanel(),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
            horizontalScrollBar.unitIncrement = JBUI.scale(24)
        }
        root.add(contentScroll, BorderLayout.CENTER)
        root.add(buildStatusPanel(), BorderLayout.SOUTH)
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
        if (filterTabsPanel.componentCount == 0) {
            val group = ButtonGroup()
            filterButtons.forEachIndexed { index, button ->
                button.isFocusable = false
                button.isFocusPainted = false
                button.isBorderPainted = false
                button.isContentAreaFilled = false
                button.isOpaque = false
                button.horizontalAlignment = SwingConstants.LEFT
                button.horizontalTextPosition = SwingConstants.LEFT
                button.iconTextGap = 0
                button.margin = JBUI.insets(2, 2)
                val charWidth = button.getFontMetrics(button.font).charWidth('中')
                val baseWidth = charWidth * 4
                val tabButtonWidth = if (index == filterButtons.lastIndex) baseWidth + JBUI.scale(10) else baseWidth
                val tabButtonSize = Dimension(tabButtonWidth, button.preferredSize.height)
                button.preferredSize = tabButtonSize
                button.minimumSize = tabButtonSize
                button.maximumSize = tabButtonSize
                group.add(button)
                filterTabsPanel.add(button)
                if (index < filterButtons.lastIndex) {
                    filterTabsPanel.add(Box.createHorizontalStrut(JBUI.scale(2)))
                }
            }
            filterTabsPanel.add(Box.createHorizontalStrut(JBUI.scale(8)))
            filterTabsPanel.add(filterCheckPanel)
            filterButtons.first().isSelected = true
            updateFilterButtonStyles()
            filterTabsMinWidth = filterTabsPanel.preferredSize.width
            filterTabsPanel.minimumSize = Dimension(filterTabsMinWidth, filterTabsPanel.preferredSize.height)
        }

        refreshButton.icon = AllIcons.Actions.Refresh
        refreshButton.text = ""
        refreshButton.toolTipText = "刷新"
        refreshButton.preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))

        val searchHint = "标题"
        searchField.emptyText.text = searchHint

        createdByMeCheck.isOpaque = false
        reviewedByMeCheck.isOpaque = false

        val searchWrapper = JPanel(BorderLayout())
        searchWrapper.border = JBUI.Borders.emptyLeft(6)
        searchWrapper.add(searchField, BorderLayout.CENTER)
        val fixedSearchWidth = (searchField.getFontMetrics(searchField.font).stringWidth(searchHint) + JBUI.scale(28)) * 3
        val fixedSearchSize = Dimension(fixedSearchWidth, searchField.preferredSize.height)
        val minimumSearchSize = Dimension(JBUI.scale(180), searchField.preferredSize.height)
        searchWrapper.preferredSize = fixedSearchSize
        searchWrapper.minimumSize = minimumSearchSize

        val rightActions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
            add(searchWrapper)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(refreshButton)
        }

        return JPanel().apply {
            layout = BorderLayout()
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(filterTabsPanel, BorderLayout.CENTER)
            add(rightActions, BorderLayout.EAST)
        }
    }

    private fun buildTablePanel(): JPanel {
        prTable.fillsViewportHeight = true
        prTable.rowHeight = JBUI.scale(28)
        prTable.setShowGrid(false)
        prTable.tableHeader.reorderingAllowed = false
        prTable.emptyText.text = "暂无 PR，点击刷新按钮重试"
        prTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val row = prTable.selectedRow
                if (row >= 0) {
                    val item = tableModel.getItemAt(row)
                    showDetail(item.id)
                } else {
                    renderEmptyDetail()
                }
            }
        }

        val pane = JBScrollPane(prTable)
        pane.border = JBUI.Borders.emptyTop(6)
        pane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
        prTableScrollPane = pane
        if (prTable.columnModel.columnCount > 4) {
            prTable.columnModel.getColumn(4).cellRenderer = ReviewerCellRenderer()
            prTable.columnModel.getColumn(4).preferredWidth = JBUI.scale(140)
        }
        prTable.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = prTable.rowAtPoint(e.point)
                val col = prTable.columnAtPoint(e.point)
                if (row < 0 || col != 4) {
                    prTable.toolTipText = null
                    return
                }
                val item = tableModel.getItemAt(row)
                val reviewers = item.reviewers
                if (reviewers.isEmpty()) {
                    prTable.toolTipText = null
                    return
                }
                val cellRect = prTable.getCellRect(row, col, false)
                val iconSize = JBUI.scale(16)
                val gap = JBUI.scale(4)
                val startX = cellRect.x + gap
                val slot = iconSize + gap
                val idx = (e.x - startX) / slot
                if (idx in reviewers.indices) {
                    val reviewer = reviewers[idx]
                    val status = reviewer.approveStatus.ifBlank { "unknown" }
                    prTable.toolTipText = "username: ${reviewer.username}, approve_status: $status"
                } else {
                    prTable.toolTipText = null
                }
            }
        })
        prTable.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                prTable.toolTipText = null
            }
        })
        val markUserScroll = { userTriggeredListScroll = true }
        pane.addMouseWheelListener { markUserScroll() }
        prTable.addMouseWheelListener { markUserScroll() }
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
        loadMoreLabel.border = JBUI.Borders.empty()
        val loadMoreHeight = prTable.rowHeight
        loadMoreLabel.preferredSize = Dimension(0, loadMoreHeight)
        loadMoreLabel.minimumSize = Dimension(0, loadMoreHeight)
        loadMoreLabel.maximumSize = Dimension(Int.MAX_VALUE, loadMoreHeight)

        return JPanel(BorderLayout()).apply {
            add(pane, BorderLayout.CENTER)
            add(loadMoreLabel, BorderLayout.SOUTH)
        }
    }

    private fun buildStatusPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.emptyTop(6)
        panel.add(statusLabel, BorderLayout.WEST)
        return panel
    }

    private fun buildDetailPanel() {
        detailEmpty.border = JBUI.Borders.empty(12)
        detailCard.add(detailEmpty, "empty")

        val detailRoot = JPanel(BorderLayout())
        detailRoot.border = JBUI.Borders.emptyLeft(1)
        detailRoot.add(buildDetailHeader(), BorderLayout.NORTH)
        detailRoot.add(buildDetailTabs(), BorderLayout.CENTER)
        detailPanel.add(detailRoot, BorderLayout.CENTER)
        detailCard.add(detailPanel, "detail")

        (detailCard.layout as java.awt.CardLayout).show(detailCard, "empty")
        renderEmptyDetail()
    }

    private fun buildDetailHeader(): JComponent {
        val header = JPanel(BorderLayout())
        header.border = JBUI.Borders.emptyBottom(4)

        val titleRow = JPanel(BorderLayout())
        titleRow.border = JBUI.Borders.emptyBottom(6)
        detailHeaderTitle.font = detailHeaderTitle.font.deriveFont(Font.BOLD, globalUiFontSize + 1f)
        titleRow.add(detailHeaderTitle, BorderLayout.WEST)
        titleRow.add(detailStatus, BorderLayout.EAST)

        val metaRow = JPanel(BorderLayout())
        val leftMeta = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        leftMeta.isOpaque = false
        leftMeta.add(detailAuthorLabel)
        leftMeta.add(detailCreateTimeLabel)
        leftMeta.add(detailBranchLabel)
        leftMeta.add(issueCountLabel)
        metaRow.add(leftMeta, BorderLayout.WEST)

        val rightMeta = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0))
        rightMeta.isOpaque = false
        rightMeta.add(aiReviewBadgeLabel)
        reviewActionButton.isVisible = false
        rightMeta.add(reviewActionButton)
        metaRow.add(rightMeta, BorderLayout.EAST)

        header.add(titleRow, BorderLayout.NORTH)
        header.add(metaRow, BorderLayout.SOUTH)
        return header
    }

    private fun buildDetailTabs(): JComponent {
        detailTabs.border = JBUI.Borders.empty()
        detailTabs.addTab("概览", buildOverviewPanel())
        detailTabs.addTab("文件改动", buildFileChangePanel())
        detailTabs.addTab("提交记录", buildCommitPanel())
        setupDetailTabsHeader()
        return detailTabs
    }

    private fun buildOverviewPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8, 0, 8, 8)

        overviewDesc.lineWrap = true
        overviewDesc.wrapStyleWord = true
        overviewDesc.rows = 4
        overviewDesc.isEditable = false
        val descScroll = JBScrollPane(overviewDesc)
        val panelBackground = panel.background
        overviewDesc.isOpaque = true
        overviewDesc.background = panelBackground
        descScroll.viewport.isOpaque = true
        descScroll.viewport.background = panelBackground
        descScroll.background = panelBackground
        keyReviewersField.background = panelBackground
        reviewersField.background = panelBackground
        keyReviewersField.isOpaque = false
        reviewersField.isOpaque = false
        val descLineHeight = overviewDesc.getFontMetrics(overviewDesc.font).height
        val descHeight = descLineHeight * 4 + JBUI.scale(12)
        val descSize = Dimension(JBUI.scale(780), descHeight)
        descScroll.preferredSize = descSize
        descScroll.minimumSize = descSize

        keyReviewersField.isEditable = false
        reviewersField.isEditable = false
        mergeTypeField.isEditable = false

        panel.add(section("描述", descScroll))
        panel.add(section("关键评审人员", buildReviewerRow(keyReviewersField, keyReviewerHint)))
        panel.add(section("普通评审人员", buildReviewerRow(reviewersField, reviewerHint)))
        panel.add(Box.createVerticalGlue())

        return JBScrollPane(
            panel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }
    }

    private fun buildReviewerRow(field: JBTextField, hint: JBLabel): JComponent {
        val row = JPanel(BorderLayout())
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.isOpaque = false
        val charWidth = field.getFontMetrics(field.font).charWidth('中')
        val leftShift = Math.round(charWidth / 5f)
        row.border = JBUI.Borders.emptyLeft(-leftShift)

        val fieldHeight = field.getFontMetrics(field.font).height + JBUI.scale(10)
        val fieldSize = Dimension(JBUI.scale(347), fieldHeight)
        field.preferredSize = fieldSize
        field.minimumSize = fieldSize
        field.maximumSize = fieldSize

        hint.border = JBUI.Borders.emptyLeft(8)
        hint.minimumSize = Dimension(0, fieldHeight)
        hint.preferredSize = Dimension(0, fieldHeight)
        hint.maximumSize = Dimension(Int.MAX_VALUE, fieldHeight)

        row.add(field, BorderLayout.WEST)
        row.add(hint, BorderLayout.CENTER)
        return row
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
        val label = JBLabel(title)
        val charWidth = label.getFontMetrics(label.font).charWidth('中')
        val leftInset = Math.round(charWidth / 6f)
        label.font = label.font.deriveFont(Font.BOLD, globalUiFontSize + 1f)
        label.border = JBUI.Borders.empty(0, leftInset, 8, 0)
        wrapper.add(label, BorderLayout.NORTH)
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(leftInset)
            add(component, BorderLayout.CENTER)
        }
        wrapper.add(body, BorderLayout.CENTER)
        wrapper.border = JBUI.Borders.emptyBottom(12)
        wrapper.maximumSize = Dimension(Int.MAX_VALUE, wrapper.preferredSize.height)
        return wrapper
    }

    private fun buildFileChangePanel(): JComponent {
        changeTree.emptyText.text = "暂无对比结果"
        changeTree.cellRenderer = ChangeTreeCellRenderer()
        changeTree.isRootVisible = false
        changeTree.showsRootHandles = true
        changeTree.toggleClickCount = 0
        changeTree.addTreeSelectionListener {
            val node = changeTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val selected = node.userObject as? ChangeItem ?: return@addTreeSelectionListener
            currentDetail ?: return@addTreeSelectionListener
            openDiff(selected)
        }
        changeTree.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = changeTree.getRowForLocation(e.x, e.y)
                if (row < 0) {
                    changeTree.toolTipText = null
                    return
                }
                val path = changeTree.getPathForRow(row)
                val node = path?.lastPathComponent as? DefaultMutableTreeNode
                val change = node?.userObject as? ChangeItem
                if (change == null) {
                    changeTree.toolTipText = null
                    return
                }

                val rowBounds = changeTree.getRowBounds(row)
                if (rowBounds == null) {
                    changeTree.toolTipText = null
                    return
                }

                val typeLabel = changeTypeFullText(change.changeType)
                val renameHint = if (typeLabel.startsWith("RENAMED") && !change.fromFilePath.isNullOrBlank()) {
                    " from ${change.fromFilePath}"
                } else {
                    ""
                }
                val baseText = "${change.filePath.substringAfterLast('/')} ($typeLabel$renameHint)"
                val font = changeTree.font.deriveFont(Font.PLAIN, globalUiFontSize)
                val badgeFont = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                val fm = changeTree.getFontMetrics(font)
                val badgeFm = changeTree.getFontMetrics(badgeFont)
                val iconWidth = changeTypeIcon(change.changeType).iconWidth
                val iconTextGap = JBUI.scale(4)
                val badgeGap = JBUI.scale(12)
                val aiGap = JBUI.scale(8)
                val mainWidth = iconWidth + iconTextGap + fm.stringWidth(baseText)
                val issueSlotWidth = badgeFm.stringWidth("00/00")

                val issueStartX = rowBounds.x + mainWidth + badgeGap
                val issueEndX = issueStartX + issueSlotWidth

                val issueText = issueCountByFile(change.filePath)?.let { "${it.first}/${it.second}" } ?: "0/0"
                val aiBadge = aiIssueCountByFile(change.filePath)
                val hasAiIssue = aiBadge.first > 0 || aiBadge.second > 0
                val aiText = if (hasAiIssue) "${aiBadge.first}/${aiBadge.second}" else "无"
                val aiStartX = issueEndX + aiGap
                val aiEndX = aiStartX + badgeFm.stringWidth(aiText)

                changeTree.toolTipText = when {
                    e.x in issueStartX until issueEndX -> "评审问题（未解决/总数）：$issueText"
                    hasAiIssue && e.x in aiStartX until aiEndX -> "AI评审 错误问题/警告问题：$aiText"
                    else -> null
                }
            }
        })
        changeTree.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                changeTree.toolTipText = null
            }

            override fun mousePressed(e: MouseEvent) {
                showChangeTreePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                showChangeTreePopup(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val path = changeTree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                if (node.userObject !is String) return
                if (changeTree.isExpanded(path)) {
                    changeTree.collapsePath(path)
                } else {
                    changeTree.expandPath(path)
                }
            }
        })
        return JBScrollPane(changeTree).apply {
            border = JBUI.Borders.empty()
        }
    }

    private fun buildCommitPanel(): JComponent {
        commitTable.fillsViewportHeight = true
        commitTable.rowHeight = JBUI.scale(26)
        commitTable.setShowGrid(false)
        commitTable.tableHeader.reorderingAllowed = false
        commitTable.setRowSelectionAllowed(false)
        commitTable.setColumnSelectionAllowed(false)
        commitTable.selectionModel.setSelectionInterval(-1, -1)
        commitTable.columnModel.selectionModel.setSelectionInterval(-1, -1)
        if (commitTable.columnModel.columnCount > 3) {
            commitTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(60)
            commitTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(60)
            commitTable.columnModel.getColumn(2).preferredWidth = JBUI.scale(360)
            commitTable.columnModel.getColumn(3).preferredWidth = JBUI.scale(90)
            commitTable.columnModel.getColumn(1).cellRenderer = CommitHashCellRenderer()
        }

        commitTable.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = commitTable.rowAtPoint(e.point)
                val col = commitTable.columnAtPoint(e.point)
                if (row >= 0 && col == 1) {
                    commitTable.toolTipText = if (commitTableModel.isMissingAt(row)) {
                        "当前分支不包含此记录"
                    } else {
                        commitTableModel.getFullHashAt(row).ifBlank { null }
                    }
                } else {
                    commitTable.toolTipText = null
                }
            }
        })
        commitTable.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                commitTable.toolTipText = null
            }

            override fun mousePressed(e: MouseEvent) {
                showCopyHashPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                showCopyHashPopup(e)
            }

            private fun showCopyHashPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = commitTable.rowAtPoint(e.point)
                val col = commitTable.columnAtPoint(e.point)
                if (row < 0 || col != 1) return
                val fullHash = commitTableModel.getFullHashAt(row)
                if (fullHash.isBlank()) return
                val menu = javax.swing.JPopupMenu()
                val item = javax.swing.JMenuItem("复制提交编号")
                item.addActionListener {
                    copyToClipboard(fullHash)
                    updateStatus("已复制提交编号: ${fullHash.take(7)}")
                }
                menu.add(item)
                menu.show(commitTable, e.x, e.y)
            }
        })

        val copyActionKey = "copyCommitHash"
        commitTable.inputMap.put(javax.swing.KeyStroke.getKeyStroke("meta C"), copyActionKey)
        commitTable.inputMap.put(javax.swing.KeyStroke.getKeyStroke("ctrl C"), copyActionKey)
        commitTable.actionMap.put(copyActionKey, object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val row = commitTable.selectedRow
                val col = commitTable.selectedColumn
                if (row >= 0 && col == 1) {
                    val fullHash = commitTableModel.getFullHashAt(row)
                    if (fullHash.isNotBlank()) {
                        copyToClipboard(fullHash)
                        updateStatus("已复制提交编号: ${fullHash.take(7)}")
                        return
                    }
                }
                if (row >= 0 && col >= 0) {
                    copyToClipboard(commitTable.getValueAt(row, col)?.toString().orEmpty())
                }
            }
        })

        return JBScrollPane(commitTable).apply {
            border = JBUI.Borders.empty()
        }
    }

    private fun setupDetailTabsHeader() {
        detailTabs.setTabComponentAt(0, createDetailTabHeader("概览", null))
        detailTabs.setTabComponentAt(1, createDetailTabHeader("文件改动", fileChangeWarningButton))
        detailTabs.setTabComponentAt(2, createDetailTabHeader("提交记录", commitWarningLabel))
    }

    private fun createDetailTabHeader(title: String, tail: JComponent?): JComponent {
        val titleLabel = JBLabel(title).apply {
            font = font.deriveFont(Font.PLAIN, globalUiFontSize)
        }
        if (title == "文件改动") {
            fileChangeTabTitleLabel.font = titleLabel.font
            fileChangeTabTitleLabel.text = title
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, JBUI.scale(12), 0, JBUI.scale(12))
            add(if (title == "文件改动") fileChangeTabTitleLabel else titleLabel)
            if (tail != null) {
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(tail)
            }
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
            fileChangeWarningButton.toolTipText = null
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
            commitWarningLabel.toolTipText = null
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
        filterButtons.forEachIndexed { index, button ->
            button.addActionListener {
                try {
                    activeFilter = when (index) {
                        0 -> PrFilter.OPEN
                        1 -> PrFilter.MERGED
                        else -> PrFilter.CLOSED
                    }
                    PrManagerFileLogger.info(
                        "Filter changed: index=$index filter=$activeFilter createdByMe=${createdByMeCheck.isSelected} reviewedByMe=${reviewedByMeCheck.isSelected}"
                    )
                    updateFilterButtonStyles()
                    resetAndLoad()
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Failed to handle filter change", e)
                }
            }
        }

        val onFilterCheckChanged: (Any?) -> Unit = onFilterCheckChanged@{ source ->
            if (suppressFilterCheckEvent) return@onFilterCheckChanged
            try {
                val createdSelected = createdByMeCheck.isSelected
                val reviewedSelected = reviewedByMeCheck.isSelected
                if (createdSelected && reviewedSelected) {
                    suppressFilterCheckEvent = true
                    when (source) {
                        createdByMeCheck -> createdByMeCheck.isSelected = false
                        reviewedByMeCheck -> reviewedByMeCheck.isSelected = false
                        else -> {
                            createdByMeCheck.isSelected = false
                            reviewedByMeCheck.isSelected = false
                        }
                    }
                    suppressFilterCheckEvent = false
                    Messages.showInfoMessage("评审人与发起人不能相同", "提示")
                    return@onFilterCheckChanged
                }
                PrManagerFileLogger.info(
                    "Filter checkbox changed: createdByMe=$createdSelected reviewedByMe=$reviewedSelected"
                )
                resetAndLoad()
            } catch (e: Exception) {
                PrManagerFileLogger.error("Failed to handle filter checkbox change", e)
            }
        }
        createdByMeCheck.addActionListener { event -> onFilterCheckChanged(event.source) }
        reviewedByMeCheck.addActionListener { event -> onFilterCheckChanged(event.source) }

        searchField.addActionListener {
            try {
                val keyword = searchField.text?.trim().orEmpty()
                PrManagerFileLogger.info(
                    "Search triggered by Enter: keyword=$keyword filter=$activeFilter createdByMe=${createdByMeCheck.isSelected} reviewedByMe=${reviewedByMeCheck.isSelected}"
                )
                resetAndLoad()
            } catch (e: Exception) {
                PrManagerFileLogger.error("Failed to search PR list", e)
            }
        }

        refreshButton.addActionListener {
            try {
                PrManagerFileLogger.info("Refresh button clicked")
                resetAndLoad()
            } catch (e: Exception) {
                PrManagerFileLogger.error("Failed to refresh PR list", e)
            }
        }
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
        currentPage = 1
        totalPage = 0
        totalCount = 0
        userTriggeredListScroll = false
        lastListScrollValue = 0
        hasMorePrs = false
        updateLoadMoreState(loading = false, hasMore = false)
        tableModel.setRows(emptyList(), append = false)
        SwingUtilities.invokeLater {
            prTableScrollPane?.verticalScrollBar?.value = 0
        }
        prTable.clearSelection()
        renderEmptyDetail()
        loadPrs(append = false)
    }

    private fun loadPrs(append: Boolean = false) {
        if (isLoading) return
        if (append && totalPage > 0 && currentPage >= totalPage) return
        if (append && totalCount > 0 && tableModel.rowCount >= totalCount) return
        isLoading = true
        statusLabel.text = "加载中..."
        updateLoadMoreState(loading = append, hasMore = false)
        PrManagerFileLogger.info("Start loading PR list: append=$append currentPage=$currentPage totalPage=$totalPage")

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
                    totalCount = result.total
                    totalPage = result.totalPage
                    currentPage = result.page
                    tableModel.setRows(result.items, append = append)
                    val loaded = tableModel.rowCount
                    val hasMore = (totalPage > 0 && currentPage < totalPage) || (totalCount > 0 && loaded < totalCount)
                    hasMorePrs = hasMore
                    userTriggeredListScroll = false
                    lastListScrollValue = prTableScrollPane?.verticalScrollBar?.value ?: 0
                    statusLabel.text = if (totalCount > 0) "已加载 $loaded/$totalCount 条 PR" else "暂无 PR"
                    updateLoadMoreState(loading = false, hasMore = hasMore)
                }
                PrManagerFileLogger.info("Finish loading PR list: append=$append page=${result.page} loaded=${result.items.size} total=${result.total}")
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load PR list error", e)
                SwingUtilities.invokeLater {
                    if (!append) {
                        tableModel.setRows(emptyList(), append = false)
                    }
                    statusLabel.text = "暂无 PR"
                    hasMorePrs = false
                    userTriggeredListScroll = false
                    lastListScrollValue = prTableScrollPane?.verticalScrollBar?.value ?: 0
                    updateLoadMoreState(loading = false, hasMore = false)
                }
            } finally {
                isLoading = false
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

            val filterCreated = createdByMeCheck.isSelected
            val filterReviewed = reviewedByMeCheck.isSelected
            if ((filterCreated || filterReviewed) && currentUser.isNotBlank()) {
                val createdMatched = item.author == currentUser
                val reviewedMatched = item.keyReviewers.contains(currentUser) ||
                    item.generalReviewers.contains(currentUser) ||
                    item.reviewers.any { it.username == currentUser }
                val relatedMatched = when {
                    filterCreated && filterReviewed -> createdMatched || reviewedMatched
                    filterCreated -> createdMatched
                    else -> reviewedMatched
                }
                if (!relatedMatched) return@filter false
            }

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
        val filterCreated = createdByMeCheck.isSelected
        val filterReviewed = reviewedByMeCheck.isSelected
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
            if (filterCreated) {
                payload["authorName"] = currentUser
            }
            if (filterReviewed) {
                payload["reviewerName"] = currentUser
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
        currentAiOverview = null
        aiIssueCountByFileMap = emptyMap()
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
        detailAuthorLabel.setPill("")
        detailCreateTimeLabel.setPill("")
        detailBranchLabel.setPill("")
        issueCountLabel.setPill("")
        reviewActionButton.isVisible = false

        overviewDesc.text = ""
        keyReviewersField.text = ""
        keyReviewersField.toolTipText = null
        keyReviewerHint.text = "-"
        reviewersField.text = ""
        reviewersField.toolTipText = null
        reviewerHint.text = "-"
        mergeTypeField.text = ""
        deleteBranchCheck.isSelected = false

        changeTreeRoot.removeAllChildren()
        changeTree.emptyText.text = "暂无对比结果"
        changeTreeModel.reload()
        commitTableModel.setRows(emptyList())
    }

    private fun showDetail(prId: Long) {
        (detailCard.layout as java.awt.CardLayout).show(detailCard, "detail")
        detailHeaderTitle.text = "加载中..."
        detailStatus.isVisible = false
        detailStatus.setBadge("", JBColor.GRAY)
        detailAuthorLabel.setPill("")
        detailCreateTimeLabel.setPill("")
        detailBranchLabel.setPill("")
        issueCountLabel.setPill("")
        currentAiOverview = null
        aiIssueCountByFileMap = emptyMap()
        currentDiffFilePath = null
        mockAiIssueStatusOverrides.clear()
        updateAiReviewBadge(AiReviewBadgeState.NO_DATA)
        reviewActionButton.isVisible = false
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

        detailAuthorLabel.setPill("创建人: ${detail.author}", detailAuthorPillColor)
        detailCreateTimeLabel.setPill("创建时间: ${detail.createTime}", detailCreateTimePillColor)
        detailBranchLabel.setPill("分支信息: ${detail.sourceBranch} -> ${detail.targetBranch}", detailBranchPillColor)
        issueCountLabel.setPill("未解决问题: 0/0", detailIssuePillColor)

        overviewDesc.text = detail.overview.desc
        keyReviewersField.text = detail.overview.keyReviewers.joinToString(",")
        keyReviewersField.toolTipText = detail.overview.keyReviewers.joinToString(",")
        keyReviewerHint.text = "至少需要 ${detail.overview.needKeyReviewers} 名关键评审成员评审通过后可合并"

        reviewersField.text = detail.overview.reviewers.joinToString(",")
        reviewersField.toolTipText = detail.overview.reviewers.joinToString(",")
        reviewerHint.text = "至少需要 ${detail.overview.needReviewers} 名普通评审成员评审通过后可合并"

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
                    issueCountLabel.setPill("未解决问题: ${result.stats.unresolved}/${result.stats.total}", detailIssuePillColor)
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
        aiReviewBadgeLabel.icon = AiBadgeIcon(state.color)
        aiReviewBadgeLabel.toolTipText = if (state == AiReviewBadgeState.NO_DATA) "当前无AI评审结果" else "查看AI评审总览"
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
                        changeTreeRoot.removeAllChildren()
                        changeTree.emptyText.text = result.error
                        changeTreeModel.reload()
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
        changeTreeRoot.removeAllChildren()
        var insertedFiles = 0

        changes.forEach { change ->
            if (insertChangeNode(change)) {
                insertedFiles++
            }
        }

        sortTree(changeTreeRoot)
        compactDirectoryTree(changeTreeRoot)
        changeTreeModel.reload()
        expandAllFromRoot()
        SwingUtilities.invokeLater { expandAllFromRoot() }

        if (insertedFiles < changes.size) {
            updateStatus("文件树构建异常: 期望${changes.size}，实际$insertedFiles")
        }
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
        val path = changeTree.getPathForLocation(e.x, e.y) ?: return
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
                    updateCommitWarning(missingHashes.isNotEmpty())
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load commit records error: prId=${detail.id}", e)
                SwingUtilities.invokeLater {
                    commitTableModel.setRows(detail.commits.sortedByDescending { it.time })
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
        return result.output
            .mapNotNull { parseCommitLine(it) }
            .sortedByDescending { it.time }
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
            val author = authorNode?.readText("name", "username", "login")
                ?.takeIf { it.isNotBlank() }
                ?: node.readText("author", "creator", "createdBy", "created_by")
            val statusText = node.readText("status", "state")
            val state = parseState(statusText)
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
            val fullPath = listOf(parentPath, node.nodeName)
                .filter { it.isNotBlank() }
                .joinToString("/")
                .replace("//", "/")
                .trim('/')
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
        val selectedColor = JBColor(Color(0x1A73E8), Color(0x6EA8FF))
        val normalColor = JBColor.foreground()
        filterButtons.forEach { button ->
            button.foreground = if (button.isSelected) selectedColor else normalColor
            val style = if (button.isSelected) Font.BOLD else Font.PLAIN
            button.font = button.font.deriveFont(style, globalUiFontSize)
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
        private val rowPanel = JPanel().apply {
            val flow = FlowLayout(FlowLayout.LEFT, 0, 0)
            flow.alignOnBaseline = true
            layout = flow
            isOpaque = true
        }
        private val mainLabel = javax.swing.JLabel()
        private val issueLabel = javax.swing.JLabel()
        private val aiLabel = javax.swing.JLabel()
        private val badgeGap = Box.createHorizontalStrut(JBUI.scale(12))
        private val aiGap = Box.createHorizontalStrut(JBUI.scale(8))

        init {
            rowPanel.add(mainLabel)
            rowPanel.add(badgeGap)
            rowPanel.add(issueLabel)
            rowPanel.add(aiGap)
            rowPanel.add(aiLabel)
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

            if (userObject !is ChangeItem) {
                if (base is javax.swing.JComponent) {
                    base.font = base.font.deriveFont(Font.PLAIN, globalUiFontSize)
                }
                return base
            }

            val fileName = userObject.filePath.substringAfterLast('/')
            val typeLabel = changeTypeFullText(userObject.changeType)
            val fromPath = userObject.fromFilePath
            val renameHint = if (typeLabel.startsWith("RENAMED") && !fromPath.isNullOrBlank()) " from $fromPath" else ""
            val baseText = "$fileName ($typeLabel$renameHint)"
            val issueBadge = issueCountByFile(userObject.filePath)
            val aiBadge = aiIssueCountByFile(userObject.filePath)
            val hasAiIssue = aiBadge.first > 0 || aiBadge.second > 0

            val font = fallbackRenderer.font.deriveFont(Font.PLAIN, globalUiFontSize)
            val badgeFont = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            mainLabel.font = font
            issueLabel.font = badgeFont
            aiLabel.font = badgeFont
            val issueSlotWidth = issueLabel.getFontMetrics(badgeFont).stringWidth("00/00")
            val issueSlotHeight = issueLabel.getFontMetrics(badgeFont).height
            val issueSlotSize = Dimension(issueSlotWidth, issueSlotHeight)
            issueLabel.minimumSize = issueSlotSize
            issueLabel.preferredSize = issueSlotSize
            issueLabel.maximumSize = issueSlotSize
            aiLabel.minimumSize = issueSlotSize
            aiLabel.preferredSize = issueSlotSize
            aiLabel.maximumSize = issueSlotSize
            issueLabel.horizontalAlignment = SwingConstants.LEFT
            aiLabel.horizontalAlignment = SwingConstants.LEFT
            mainLabel.icon = changeTypeIcon(userObject.changeType)
            mainLabel.verticalAlignment = SwingConstants.CENTER
            issueLabel.verticalAlignment = SwingConstants.CENTER
            aiLabel.verticalAlignment = SwingConstants.CENTER

            val neutralColor = JBColor(Color(0x5F6368), Color(0x9AA0A6))
            val dangerColor = JBColor(Color(0xD93025), Color(0xF47067))
            val warnColor = JBColor(Color(0xF29900), Color(0xF6C26B))
            val issueText = issueBadge?.let { "${it.first}/${it.second}" }.orEmpty()
            val aiText = if (hasAiIssue) "${aiBadge.first}/${aiBadge.second}" else ""

            if (sel) {
                rowPanel.background = fallbackRenderer.backgroundSelectionColor
                mainLabel.foreground = fallbackRenderer.textSelectionColor
                issueLabel.foreground = fallbackRenderer.textSelectionColor
                aiLabel.foreground = fallbackRenderer.textSelectionColor
                issueLabel.text = issueText.padEnd(5, ' ')
                aiLabel.text = aiText
            } else {
                rowPanel.background = fallbackRenderer.backgroundNonSelectionColor
                mainLabel.foreground = changeTypeColor(userObject.changeType)
                issueLabel.foreground = if ((issueBadge?.first ?: 0) > 0) dangerColor else neutralColor
                aiLabel.foreground = neutralColor
                issueLabel.text = issueBadge?.let { badge ->
                    if (badge.first > 0) {
                        "<html><span style='font-family:monospace;color:${toHex(dangerColor)};'>${badge.first}</span><span style='font-family:monospace;color:${toHex(neutralColor)};'>/${badge.second}</span></html>"
                    } else {
                        issueText.padEnd(5, ' ')
                    }
                }.orEmpty()
                aiLabel.text = if (hasAiIssue) {
                    "<html><span style='font-family:monospace;color:${toHex(dangerColor)};'>${aiBadge.first}</span><span style='font-family:monospace;color:${toHex(neutralColor)};'>/</span><span style='font-family:monospace;color:${toHex(warnColor)};'>${aiBadge.second}</span></html>"
                } else {
                    ""
                }
            }

            mainLabel.text = baseText
            return rowPanel
        }
    }

    private inner class CommitHashCellRenderer : TableCellRenderer {
        private val label = JBLabel()

        override fun getTableCellRendererComponent(
            table: javax.swing.JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val text = value?.toString().orEmpty()
            label.text = text
            label.font = table.font
            label.isOpaque = true
            label.background = if (isSelected) table.selectionBackground else table.background

            val isMissing = commitTableModel.isMissingAt(row)
            label.foreground = if (isSelected) {
                table.selectionForeground
            } else {
                if (isMissing) JBColor(Color(0xD93025), Color(0xF47067)) else table.foreground
            }
            return label
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
        val time: String
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

    private class PrTableModel : AbstractTableModel() {
        private val columns = arrayOf("标题", "源分支", "目标分支", "创建人", "评审人")
        private var rows: List<PrItem> = emptyList()

        fun setRows(items: List<PrItem>, append: Boolean) {
            rows = if (append) rows + items else items
            fireTableDataChanged()
        }

        fun getItemAt(row: Int): PrItem = rows[row]

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
        private val columns = arrayOf("提交人", "提交编号", "提交信息", "提交日期")
        private var rows: List<CommitItem> = emptyList()
        private var missingHashes: Set<String> = emptySet()

        fun setRows(items: List<CommitItem>, missingHashes: Set<String> = emptySet()) {
            rows = items
            this.missingHashes = missingHashes
            fireTableDataChanged()
        }

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
                0 -> item.author
                1 -> if (item.hash.length > 7) item.hash.take(7) else item.hash
                2 -> item.message
                3 -> item.time
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
