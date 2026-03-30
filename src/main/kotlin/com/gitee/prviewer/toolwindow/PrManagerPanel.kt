package com.gitee.prviewer.toolwindow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gitee.prviewer.comment.DiffEditorBinder
import com.gitee.prviewer.comment.IssueItem
import com.gitee.prviewer.comment.IssueReply
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
import com.intellij.ui.DocumentAdapter
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
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.time.Duration
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
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.JToggleButton
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class PrManagerPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
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
        mergeUrl = mergeUrl
    )

    private val branchService = BranchCompareService(project)
    private val commentManager = LineCommentManager(project)
    private val diffBinder = DiffEditorBinder(project, commentManager)

    private val statusLabel = JBLabel("正在加载 PR 列表...")
    private val tableModel = PrTableModel()
    private val prTable = JBTable(tableModel)
    private val loadMoreLabel = JBLabel("加载更多中...", SwingConstants.CENTER)
    private val searchField = JBTextField()
    private val refreshButton = JButton()
    private val filterTabsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }
    private var filterTabsMinWidth = 0
    private val filterButtons = listOf(
        JToggleButton("开启的"),
        JToggleButton("已合并"),
        JToggleButton("已关闭"),
        JToggleButton("与我有关")
    )

    private var activeFilter = PrFilter.OPEN
    private var relatedOnly = false
    private var currentPage = 1
    private var totalPage = 0
    private var totalCount = 0
    private var isLoading = false
    private var userTriggeredListScroll = false
    private val pageSize = config.getProperty("prviewer.api.pageSize", "10").toIntOrNull() ?: 10

    private val detailCard = JPanel(java.awt.CardLayout())
    private val detailEmpty = JPanel(BorderLayout())
    private val detailPanel = JPanel(BorderLayout())
    private val detailHeaderTitle = JBLabel("-")
    private val detailStatus = JBLabel("-")
    private val detailMeta = JBLabel("-")
    private val issueCountLabel = JBLabel("未解决问题: 0/0")
    private val reviewActionButton = JButton()
    private val detailTabs = JBTabbedPane()

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

        prTable.font = prTable.font.deriveFont(Font.PLAIN, globalUiFontSize)
        prTable.tableHeader.font = prTable.tableHeader.font.deriveFont(Font.BOLD, globalUiFontSize)

        filterButtons.forEach { button ->
            button.font = button.font.deriveFont(Font.PLAIN, globalUiFontSize)
        }

        detailHeaderTitle.font = detailHeaderTitle.font.deriveFont(Font.BOLD, globalUiFontSize + 1f)
        detailStatus.font = detailStatus.font.deriveFont(Font.PLAIN, globalUiFontSize)
        detailMeta.font = detailMeta.font.deriveFont(Font.PLAIN, globalUiFontSize)
        issueCountLabel.font = issueCountLabel.font.deriveFont(Font.PLAIN, globalUiFontSize)
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
            if (!userTriggeredListScroll) return@addAdjustmentListener
            val bar = pane.verticalScrollBar
            val reachedBottom = bar.maximum - (bar.value + bar.visibleAmount) <= JBUI.scale(30)
            if (reachedBottom) {
                loadPrs(append = true)
            }
        }

        loadMoreLabel.isVisible = false
        loadMoreLabel.border = JBUI.Borders.empty(6, 0)

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
        val leftMeta = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        leftMeta.isOpaque = false
        leftMeta.add(detailMeta)
        issueCountLabel.border = JBUI.Borders.emptyLeft(20)
        leftMeta.add(issueCountLabel)
        metaRow.add(leftMeta, BorderLayout.WEST)

        val rightMeta = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        rightMeta.isOpaque = false
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
        label.font = label.font.deriveFont(Font.PLAIN, globalUiFontSize)
        label.border = JBUI.Borders.emptyBottom(4)
        wrapper.add(label, BorderLayout.NORTH)
        wrapper.add(component, BorderLayout.CENTER)
        wrapper.border = JBUI.Borders.emptyBottom(10)
        wrapper.maximumSize = Dimension(Int.MAX_VALUE, wrapper.preferredSize.height)
        return wrapper
    }

    private fun buildFileChangePanel(): JComponent {
        changeTree.emptyText.text = "暂无对比结果"
        changeTree.cellRenderer = ChangeTreeCellRenderer()
        changeTree.isRootVisible = false
        changeTree.showsRootHandles = true
        changeTree.toggleClickCount = 1
        changeTree.addTreeSelectionListener {
            val node = changeTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val selected = node.userObject as? ChangeItem ?: return@addTreeSelectionListener
            val detail = currentDetail ?: return@addTreeSelectionListener
            openDiff(selected, detail.sourceBranch, detail.targetBranch)
        }
        return JBScrollPane(changeTree)
    }

    private fun buildCommitPanel(): JComponent {
        commitTable.fillsViewportHeight = true
        commitTable.rowHeight = JBUI.scale(26)
        commitTable.setShowGrid(false)
        commitTable.tableHeader.reorderingAllowed = false
        if (commitTable.columnModel.columnCount > 3) {
            commitTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(60)
            commitTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(60)
            commitTable.columnModel.getColumn(2).preferredWidth = JBUI.scale(360)
            commitTable.columnModel.getColumn(3).preferredWidth = JBUI.scale(90)
        }

        commitTable.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = commitTable.rowAtPoint(e.point)
                val col = commitTable.columnAtPoint(e.point)
                if (row >= 0 && col == 1) {
                    val fullHash = commitTableModel.getFullHashAt(row)
                    commitTable.toolTipText = fullHash.ifBlank { null }
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
                commitTable.setRowSelectionInterval(row, row)
                commitTable.setColumnSelectionInterval(col, col)
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

        return JBScrollPane(commitTable)
    }

    private fun bindActions() {
        filterButtons.forEachIndexed { index, button ->
            button.addActionListener {
                try {
                    when (index) {
                        0 -> {
                            activeFilter = PrFilter.OPEN
                            relatedOnly = false
                        }
                        1 -> {
                            activeFilter = PrFilter.MERGED
                            relatedOnly = false
                        }
                        2 -> {
                            activeFilter = PrFilter.CLOSED
                            relatedOnly = false
                        }
                        else -> {
                            activeFilter = PrFilter.ALL
                            relatedOnly = true
                        }
                    }
                    PrManagerFileLogger.info("Filter changed: index=$index filter=$activeFilter relatedOnly=$relatedOnly")
                    updateFilterButtonStyles()
                    resetAndLoad()
                } catch (e: Exception) {
                    PrManagerFileLogger.error("Failed to handle filter change", e)
                }
            }
        }

        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                resetAndLoad()
            }
        })

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
                    LineCommentStore.addReply(filePath, line, side, parent.id, content, System.getenv("USERID").orEmpty(), parent.rootId)
                    return
                }
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val response = apiService.replyNote(
                            prId = detail.id,
                            context = content,
                            filePath = filePath,
                            codeLine = line + 1,
                            nodeId = parent.id.takeIf { it.isNotBlank() },
                            replyFloorNum = parent.floorNum
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
        tableModel.setRows(emptyList(), append = false)
        prTable.clearSelection()
        renderEmptyDetail()
        loadPrs(append = false)
    }

    private fun loadPrs(append: Boolean = false) {
        if (isLoading) return
        if (append && totalPage > 0 && currentPage >= totalPage) return
        if (append && totalCount > 0 && tableModel.rowCount >= totalCount) return
        isLoading = true
        statusLabel.text = if (append) "正在加载更多 PR..." else "正在加载 PR 列表..."
        setLoadMoreVisible(append)
        PrManagerFileLogger.info("Start loading PR list: append=$append currentPage=$currentPage totalPage=$totalPage")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = if (mockEnabled) {
                    val mockJson = readMockJson(mockListFile) ?: ""
                    if (mockJson.isBlank()) {
                        PrListResult(0, emptyList(), 0, 0)
                    } else {
                        parsePrList(mockJson)
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
                    statusLabel.text = if (totalCount > 0) "已加载 $loaded/$totalCount 条 PR" else "暂无 PR"
                }
                PrManagerFileLogger.info("Finish loading PR list: append=$append page=${result.page} loaded=${result.items.size} total=${result.total}")
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load PR list error", e)
                SwingUtilities.invokeLater {
                    if (!append) {
                        tableModel.setRows(emptyList(), append = false)
                    }
                    statusLabel.text = "暂无 PR"
                }
            } finally {
                isLoading = false
                setLoadMoreVisible(false)
            }
        }
    }

    private fun buildListRequestBody(append: Boolean): String {
        val status = when (activeFilter) {
            PrFilter.OPEN -> "opened"
            PrFilter.CLOSED -> "closed"
            PrFilter.MERGED -> "merged"
            PrFilter.ALL -> "all"
        }
        val pageValue = if (append) currentPage + 1 else 1
        val currentUser = System.getenv("USERID").orEmpty()
        val payload = linkedMapOf(
            "sshPath" to resolveGitAddress(),
            "page" to pageValue,
            "perPage" to pageSize,
            "states" to listOf(status),
            "sourceBranch" to "",
            "targetBranch" to "",
            "keywords" to (searchField.text?.trim() ?: "")
        )
        if (relatedOnly && currentUser.isNotBlank()) {
            payload["authorName"] = currentUser
            payload["reviewerName"] = currentUser
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
        (detailCard.layout as java.awt.CardLayout).show(detailCard, "empty")

        detailHeaderTitle.text = "未选择 PR"
        detailStatus.text = "-"
        detailStatus.foreground = JBColor.GRAY
        detailMeta.text = "请选择左侧 PR 查看详情"
        issueCountLabel.text = "未解决问题: 0/0"
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
        detailStatus.text = ""
        detailMeta.text = ""
        issueCountLabel.text = "未解决问题: 0/0"
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
                PrManagerFileLogger.info("PR detail loaded: prId=$prId iid=${detail.iid}")
                loadNotes(detail)
                loadFileChanges(detail.sourceBranch, detail.targetBranch)
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
        detailStatus.text = detail.status
        detailStatus.foreground = statusColor(detail.status)

        val meta = "创建人: ${detail.author}      创建时间: ${detail.createTime}      分支信息: ${detail.sourceBranch} -> ${detail.targetBranch}"
        detailMeta.text = meta

        overviewDesc.text = detail.overview.desc
        keyReviewersField.text = detail.overview.keyReviewers.joinToString(",")
        keyReviewersField.toolTipText = detail.overview.keyReviewers.joinToString(",")
        keyReviewerHint.text = "至少需要 ${detail.overview.needKeyReviewers} 名关键评审成员评审通过后可合并"

        reviewersField.text = detail.overview.reviewers.joinToString(",")
        reviewersField.toolTipText = detail.overview.reviewers.joinToString(",")
        reviewerHint.text = "至少需要 ${detail.overview.needReviewers} 名普通评审成员评审通过后可合并"

        setupReviewAction(detail)
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
                    issueCountLabel.text = "未解决问题: ${result.stats.unresolved}/${result.stats.total}"
                    changeTree.repaint()
                }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load notes error: prId=${detail.id} iid=${detail.iid}", e)
            }
        }
    }

    private fun loadFileChanges(source: String, target: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                PrManagerFileLogger.info("Start loading file changes: target=$target source=$source")
                val result = branchService.compare(target, source)
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
                PrManagerFileLogger.error("Load file changes error: target=$target source=$source", e)
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

    private fun issueCountByFile(filePath: String): Pair<Int, Int>? {
        val fileIssues = issueItemsByFile(filePath)
        if (fileIssues.isEmpty()) return null
        val unresolved = fileIssues.count { it.status.trim().lowercase() == "open" }
        return unresolved to fileIssues.size
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

    private fun openDiff(change: ChangeItem, source: String, target: String) {
        try {
            PrManagerFileLogger.info("Open diff: file=${change.filePath} target=$target source=$source")
            val sourceContent = branchService.loadFileContent(source, change.filePath)
            val targetContent = branchService.loadFileContent(target, change.filePath)
            if (sourceContent == null && targetContent == null) {
                updateStatus("无法加载文件内容")
                PrManagerFileLogger.warn("Open diff failed, content empty: file=${change.filePath}")
                return
            }

            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(change.filePath)
            val contentFactory = DiffContentFactory.getInstance()
            val left = contentFactory.create(project, targetContent ?: "", fileType)
            val right = contentFactory.create(project, sourceContent ?: "", fileType)
            val request = SimpleDiffRequest(
                "${change.filePath} ($target..$source)",
                left,
                right,
                target,
                source
            )
            commentManager.updateIssueLines(change.filePath, issueLineSetByFile(change.filePath))
            commentManager.updateIssueDetails(change.filePath, issueItemsByFile(change.filePath))
            diffBinder.bindNextDiff(change.filePath)
            DiffManager.getInstance().showDiff(project, request)
        } catch (e: Exception) {
            PrManagerFileLogger.error("Open diff error: file=${change.filePath}", e)
            updateStatus("打开Diff失败: ${e.message ?: "未知错误"}")
        }
    }

    private fun loadCommitRecords(detail: PrDetail) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fallbackCommits = detail.commits.sortedByDescending { it.time }

                val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
                if (repo == null) {
                    PrManagerFileLogger.warn("Load commit records fallback: repository not found")
                    SwingUtilities.invokeLater { commitTableModel.setRows(fallbackCommits) }
                    return@executeOnPooledThread
                }

                val sourceRef = toRemoteBranchRef(repo, detail.sourceBranch)
                val targetRef = toRemoteBranchRef(repo, detail.targetBranch)
                if (sourceRef.isBlank() || targetRef.isBlank()) {
                    PrManagerFileLogger.warn("Load commit records fallback: invalid refs sourceRef=$sourceRef targetRef=$targetRef")
                    SwingUtilities.invokeLater { commitTableModel.setRows(fallbackCommits) }
                    return@executeOnPooledThread
                }

                val mergeBase = resolveMergeBase(repo, targetRef, sourceRef)
                val ranges = buildList {
                    if (!mergeBase.isNullOrBlank()) {
                        add("$mergeBase..$sourceRef")
                    }
                    add("$targetRef..$sourceRef")
                    add("$targetRef...$sourceRef")
                }

                var commits: List<CommitItem> = emptyList()
                for (range in ranges) {
                    val rows = loadCommitsByRange(repo, range)
                    if (rows.isNotEmpty()) {
                        commits = rows
                        break
                    }
                }

                if (commits.isEmpty()) commits = fallbackCommits
                PrManagerFileLogger.info("Commit records loaded: prId=${detail.id} count=${commits.size}")
                SwingUtilities.invokeLater { commitTableModel.setRows(commits) }
            } catch (e: Exception) {
                PrManagerFileLogger.error("Load commit records error: prId=${detail.id}", e)
                SwingUtilities.invokeLater { commitTableModel.setRows(detail.commits.sortedByDescending { it.time }) }
            }
        }
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
                    val author = note.get("author")?.readText("username", "login", "name").orEmpty()
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
                            id = commentId,
                            filePath = filePath,
                            line = lineIndex,
                            side = Side.RIGHT,
                            content = content,
                            author = author,
                            createdAt = createdAt,
                            parentId = null,
                            rootId = rootId.ifBlank { commentId },
                            floorNum = floorNum,
                            replyFloorNum = replyFloorNum,
                            resolved = resolved
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
                            val childAuthor = child.get("author")?.readText("username", "login", "name").orEmpty()
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
                                    id = childCommentId,
                                    filePath = filePath,
                                    line = lineIndex,
                                    side = Side.RIGHT,
                                    content = childContent,
                                    author = childAuthor,
                                    createdAt = childCreatedAt,
                                    parentId = rootId.ifBlank { commentId },
                                    rootId = childRootId.ifBlank { rootId.ifBlank { commentId } },
                                    floorNum = childFloorNum,
                                    replyFloorNum = childReplyFloorNum,
                                    resolved = childResolved
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

    private fun setLoadMoreVisible(visible: Boolean) {
        SwingUtilities.invokeLater { loadMoreLabel.isVisible = visible }
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
        private val unresolvedLabel = javax.swing.JLabel()
        private val totalLabel = javax.swing.JLabel()

        init {
            rowPanel.add(mainLabel)
            rowPanel.add(unresolvedLabel)
            rowPanel.add(totalLabel)
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

            val font = fallbackRenderer.font.deriveFont(Font.PLAIN, globalUiFontSize)
            mainLabel.font = font
            unresolvedLabel.font = font
            totalLabel.font = font
            mainLabel.icon = changeTypeIcon(userObject.changeType)
            mainLabel.verticalAlignment = SwingConstants.CENTER
            unresolvedLabel.verticalAlignment = SwingConstants.CENTER
            totalLabel.verticalAlignment = SwingConstants.CENTER

            if (sel) {
                rowPanel.background = fallbackRenderer.backgroundSelectionColor
                mainLabel.foreground = fallbackRenderer.textSelectionColor
                unresolvedLabel.foreground = fallbackRenderer.textSelectionColor
                totalLabel.foreground = fallbackRenderer.textSelectionColor
            } else {
                rowPanel.background = fallbackRenderer.backgroundNonSelectionColor
                mainLabel.foreground = changeTypeColor(userObject.changeType)
                unresolvedLabel.foreground = JBColor(Color(0xD93025), Color(0xF47067))
                totalLabel.foreground = JBColor(Color(0x5F6368), Color(0x9AA0A6))
            }

            if (issueBadge == null) {
                mainLabel.text = baseText
                unresolvedLabel.text = ""
                totalLabel.text = ""
            } else {
                val (unresolved, total) = issueBadge
                mainLabel.text = "$baseText  "
                unresolvedLabel.text = unresolved.toString()
                totalLabel.text = "/$total"
            }
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

        fun setRows(items: List<CommitItem>) {
            rows = items
            fireTableDataChanged()
        }

        fun getFullHashAt(row: Int): String = rows.getOrNull(row)?.hash.orEmpty()

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
