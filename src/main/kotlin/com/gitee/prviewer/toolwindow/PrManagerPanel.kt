package com.gitee.prviewer.toolwindow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gitee.prviewer.comment.DiffEditorBinder
import com.gitee.prviewer.comment.IssueItem
import com.gitee.prviewer.comment.IssueReply
import com.gitee.prviewer.comment.IssueStats
import com.gitee.prviewer.comment.LineCommentManager
import com.gitee.prviewer.comment.PrIssueCache
import com.gitee.prviewer.model.ChangeItem
import com.gitee.prviewer.service.BranchCompareService
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
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.JToggleButton
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class PrManagerPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()
    private val config = Properties().apply {
        val stream = PrManagerPanel::class.java.getResourceAsStream("/prviewer.properties")
        if (stream != null) {
            stream.use { load(it) }
        }
    }

    private val mockEnabled = config.getProperty("prviewer.mock.enabled", "false").toBoolean()
    private val globalUiFontSize = config.getProperty("prviewer.ui.font.size", "13").toFloatOrNull() ?: 13f
    private val mockDir = config.getProperty("prviewer.mock.dir", "mock").trim().trimEnd('/')
    private val mockListFile = config.getProperty("prviewer.mock.list.file", "pr-list.json").trim()
    private val mockDetailFile = config.getProperty("prviewer.mock.detail.file", "pr-detail.json").trim()
    private val mockIssuesFile = config.getProperty("prviewer.mock.issues.file", "pr-issues.json").trim()

    private val listUrl = buildUrl(config.getProperty("prviewer.api.list.path", "/api/prs"))
    private val detailUrl = buildUrl(config.getProperty("prviewer.api.detail.path", "/api/pr/detail"))
    private val issuesUrl = buildUrl(config.getProperty("prviewer.api.issues.path", "/api/pr/issues"))
    private val reviewUrl = buildUrl(config.getProperty("prviewer.api.review.path", "/api/pr/review"))
    private val mergeUrl = buildUrl(config.getProperty("prviewer.api.merge.path", "/api/pr/merge"))

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
    private var currentOffset = -1L
    private var totalCount = 0
    private var isLoading = false
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

        val searchHint = "标题/源分支/目标分支/创建人"
        searchField.emptyText.text = searchHint

        val searchWrapper = JPanel(BorderLayout())
        searchWrapper.border = JBUI.Borders.emptyLeft(6)
        searchWrapper.add(searchField, BorderLayout.CENTER)
        val fixedSearchWidth = searchField.getFontMetrics(searchField.font).stringWidth(searchHint) + JBUI.scale(28)
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
        pane.verticalScrollBar.addAdjustmentListener {
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
        val descSize = Dimension(JBUI.scale(520), descHeight)
        descScroll.preferredSize = descSize
        descScroll.minimumSize = descSize

        keyReviewersField.isEditable = false
        reviewersField.isEditable = false
        mergeTypeField.isEditable = false

        panel.add(section("描述", descScroll))
        panel.add(section("关键评审人员", buildReviewerRow(keyReviewersField, keyReviewerHint)))
        panel.add(section("普通评审人员", buildReviewerRow(reviewersField, reviewerHint)))
        panel.add(section("选择合并方式", mergeTypeField))
        deleteBranchCheck.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(deleteBranchCheck)
        return panel
    }

    private fun buildReviewerRow(field: JBTextField, hint: JBLabel): JComponent {
        val row = JPanel(BorderLayout())
        row.alignmentX = Component.LEFT_ALIGNMENT
        field.preferredSize = Dimension(JBUI.scale(260), field.preferredSize.height)
        row.add(field, BorderLayout.WEST)
        hint.border = JBUI.Borders.emptyLeft(8)
        row.add(hint, BorderLayout.CENTER)
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
        return JBScrollPane(commitTable)
    }

    private fun bindActions() {
        filterButtons.forEachIndexed { index, button ->
            button.addActionListener {
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
                updateFilterButtonStyles()
                resetAndLoad()
            }
        }

        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                resetAndLoad()
            }
        })

        refreshButton.addActionListener {
            resetAndLoad()
        }
    }

    private fun resetAndLoad() {
        currentOffset = -1L
        totalCount = 0
        tableModel.setRows(emptyList(), append = false)
        prTable.clearSelection()
        renderEmptyDetail()
        loadPrs(append = false)
    }

    private fun loadPrs(append: Boolean = false) {
        if (isLoading) return
        if (append && currentOffset < 0) return
        if (append && totalCount > 0 && tableModel.rowCount >= totalCount) return
        isLoading = true
        statusLabel.text = if (append) "正在加载更多 PR..." else "正在加载 PR 列表..."
        setLoadMoreVisible(append)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = if (mockEnabled) {
                    val mockJson = readMockJson(mockListFile) ?: ""
                    if (mockJson.isBlank()) {
                        PrListResult(0, emptyList())
                    } else {
                        parsePrList(mockJson)
                    }
                } else {
                    val requestBody = buildListRequestBody(append)
                    val request = HttpRequest.newBuilder(URI.create(listUrl))
                        .timeout(Duration.ofSeconds(12))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build()
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !in 200..299) {
                        PrListResult(0, emptyList())
                    } else {
                        parsePrList(response.body())
                    }
                }

                SwingUtilities.invokeLater {
                    totalCount = result.total
                    tableModel.setRows(result.items, append = append)
                    updateOffsetAfterLoad()
                    val loaded = tableModel.rowCount
                    statusLabel.text = if (totalCount > 0) "已加载 $loaded/$totalCount 条 PR" else "暂无 PR"
                }
            } catch (_: Exception) {
                SwingUtilities.invokeLater {
                    if (!append) {
                        tableModel.setRows(emptyList(), append = false)
                        updateOffsetAfterLoad()
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
            PrFilter.OPEN -> "open"
            PrFilter.CLOSED -> "closed"
            PrFilter.MERGED -> "merged"
            PrFilter.ALL -> "all"
        }
        val offsetValue = if (append) currentOffset else -1L
        val payload = mapOf(
            "gitAddress" to resolveGitAddress(),
            "offSet" to offsetValue,
            "pageSize" to pageSize,
            "status" to status,
            "related" to relatedOnly,
            "searchContext" to (searchField.text?.trim() ?: "")
        )
        return objectMapper.writeValueAsString(payload)
    }

    private fun resolveGitAddress(): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return ""
        val remote = repo.remotes.firstOrNull { it.name == "origin" } ?: repo.remotes.firstOrNull()
        return remote?.urls?.firstOrNull().orEmpty()
    }

    private fun updateOffsetAfterLoad() {
        val lastId = tableModel.lastId()
        currentOffset = if (lastId >= 0) lastId + 1 else -1L
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

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val detail = if (mockEnabled) {
                    val mockJson = readMockJson(mockDetailFile)
                        ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockDetailFile")
                    parseDetail(mockJson)
                } else {
                    val payload = mapOf("id" to prId)
                    val request = HttpRequest.newBuilder(URI.create(detailUrl))
                        .timeout(Duration.ofSeconds(12))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                        .build()
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !in 200..299) {
                        updateStatus("详情加载失败: ${response.statusCode()}")
                        return@executeOnPooledThread
                    }
                    parseDetail(response.body())
                }
                SwingUtilities.invokeLater {
                    currentDetail = detail
                    renderDetail(detail)
                }
                loadIssues(prId)
                loadFileChanges(detail.sourceBranch, detail.targetBranch)
                loadCommitRecords(detail)
            } catch (e: Exception) {
                updateStatus("详情加载失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun renderDetail(detail: PrDetail) {
        detailHeaderTitle.text = "#${detail.id} ${detail.title}"
        detailStatus.text = detail.status
        detailStatus.foreground = statusColor(detail.status)

        val meta = "创建人: ${detail.author}      创建时间: ${detail.createTime}      分支信息: ${detail.sourceBranch} -> ${detail.targetBranch}"
        detailMeta.text = meta

        overviewDesc.text = detail.overview.desc
        keyReviewersField.text = detail.overview.keyReviewers.joinToString(",")
        keyReviewersField.toolTipText = detail.overview.keyReviewers.joinToString(",")
        keyReviewerHint.text = "至少需要${detail.overview.needKeyReviewers}名关键评审成员评审通过后可合并"

        reviewersField.text = detail.overview.reviewers.joinToString(",")
        reviewersField.toolTipText = detail.overview.reviewers.joinToString(",")
        reviewerHint.text = "至少需要${detail.overview.needReviewers}名普通评审成员评审通过后可合并"

        mergeTypeField.text = detail.overview.mergedType
        deleteBranchCheck.isSelected = detail.overview.deleteBranchAfterMerged

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
                val payload = mapOf("id" to prId)
                val request = HttpRequest.newBuilder(URI.create(reviewUrl))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    updateStatus("评审失败: ${response.statusCode()}")
                    return@executeOnPooledThread
                }
                updateStatus("评审通过")
            } catch (e: Exception) {
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
                val payload = mapOf(
                    "id" to prId,
                    "action" to "delete",
                    "commitMsg" to commitMsg,
                    "extMsg" to extMsg,
                    "deleteBranchAfterMerged" to deleteBranch
                )
                val request = HttpRequest.newBuilder(URI.create(mergeUrl))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    updateStatus("合并失败: ${response.statusCode()}")
                    return@executeOnPooledThread
                }
                updateStatus("已提交合并")
            } catch (e: Exception) {
                updateStatus("合并失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun loadIssues(prId: Long) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val stats = if (mockEnabled) {
                    val mockJson = readMockJson(mockIssuesFile)
                        ?: throw IllegalStateException("Mock文件不存在: $mockDir/$mockIssuesFile")
                    parseIssueStats(mockJson)
                } else {
                    val payload = mapOf("id" to prId)
                    val request = HttpRequest.newBuilder(URI.create(issuesUrl))
                        .timeout(Duration.ofSeconds(12))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                        .build()
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !in 200..299) {
                        return@executeOnPooledThread
                    }
                    parseIssueStats(response.body())
                }
                PrIssueCache.put(prId, stats)
                SwingUtilities.invokeLater {
                    issueCountLabel.text = "未解决问题: ${stats.unresolved}/${stats.total}"
                    changeTree.repaint()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadFileChanges(source: String, target: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = branchService.compare(source, target)
            SwingUtilities.invokeLater {
                if (result.error != null) {
                    changeTreeRoot.removeAllChildren()
                    changeTree.emptyText.text = result.error
                    changeTreeModel.reload()
                    return@invokeLater
                }
                buildChangeTree(result.changes)
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
        val prId = currentDetail?.id ?: return null
        val stats = PrIssueCache.get(prId) ?: return null
        val normalized = normalizeFilePath(filePath)
        val fileIssues = stats.issues.filter {
            val issuePath = normalizeFilePath(it.file)
            issuePath == normalized || issuePath.endsWith(normalized) || normalized.endsWith(issuePath)
        }
        if (fileIssues.isEmpty()) return null
        val unresolved = fileIssues.count { it.status.trim().lowercase() == "open" }
        return unresolved to fileIssues.size
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
        val sourceContent = branchService.loadFileContent(source, change.filePath)
        val targetContent = branchService.loadFileContent(target, change.filePath)
        if (sourceContent == null && targetContent == null) {
            updateStatus("无法加载文件内容")
            return
        }

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(change.filePath)
        val contentFactory = DiffContentFactory.getInstance()
        val left = contentFactory.create(project, sourceContent ?: "", fileType)
        val right = contentFactory.create(project, targetContent ?: "", fileType)
        val request = SimpleDiffRequest(
            "${change.filePath} ($source..$target)",
            left,
            right,
            source,
            target
        )
        diffBinder.bindNextDiff(change.filePath)
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun loadCommitRecords(detail: PrDetail) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val fallbackCommits = detail.commits.sortedByDescending { it.time }
            if (mockEnabled) {
                SwingUtilities.invokeLater { commitTableModel.setRows(fallbackCommits) }
                return@executeOnPooledThread
            }

            val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            if (repo == null) {
                SwingUtilities.invokeLater { commitTableModel.setRows(fallbackCommits) }
                return@executeOnPooledThread
            }

            val sourceHead = resolveBranchHead(repo, detail.sourceBranch)
            val mergeBase = resolveMergeBase(repo, detail.targetBranch, detail.sourceBranch)
            val ranges = buildList {
                if (!mergeBase.isNullOrBlank() && !sourceHead.isNullOrBlank()) {
                    add("$mergeBase..$sourceHead")
                }
                add("${detail.targetBranch}..${detail.sourceBranch}")
                add("${detail.targetBranch}...${detail.sourceBranch}")
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
            SwingUtilities.invokeLater { commitTableModel.setRows(commits) }
        }
    }

    private fun resolveBranchHead(repo: git4idea.repo.GitRepository, branch: String): String? {
        val handler = GitLineHandler(project, repo.root, GitCommand.REV_PARSE)
        handler.addParameters(branch)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        return result.output.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
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
        return CommitItem(
            hash = parts[0],
            author = parts[1],
            time = parts[2],
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
        val root = objectMapper.readTree(body) ?: return PrListResult(0, emptyList())
        val data = root.get("data")
        val total = data?.get("total")?.asInt() ?: 0
        val listNode = data?.get("prList")
        if (listNode == null || !listNode.isArray) {
            return PrListResult(total, emptyList())
        }
        val list = mutableListOf<PrItem>()
        for (node in listNode) {
            val id = node.readText("id").toLongOrNull() ?: -1L
            val title = node.readText("title", "name")
            val source = node.readText("sourceBranch", "source_branch", "source")
            val target = node.readText("targetBranch", "target_branch", "target")
            val author = node.readText("author", "creator", "createdBy", "created_by")
            val statusText = node.readText("status", "state")
            val state = parseState(statusText)
            list.add(PrItem(id, title, source, target, author, state))
        }
        return PrListResult(total, list)
    }

    private fun parseDetail(body: String): PrDetail {
        val root = objectMapper.readTree(body)
        val data = root.get("data")
        val overviewNode = data?.get("overview")
        val overview = PrOverview(
            desc = overviewNode?.get("desc")?.asText() ?: "",
            keyReviewers = overviewNode?.get("keyReviewers")?.map { it.asText() } ?: emptyList(),
            needKeyReviewers = overviewNode?.get("needKeyReviewers")?.asInt() ?: 0,
            reviewers = overviewNode?.get("reviewers")?.map { it.asText() } ?: emptyList(),
            needReviewers = overviewNode?.get("needReviewers")?.asInt() ?: 0,
            mergedType = overviewNode?.get("mergedType")?.asText() ?: "",
            deleteBranchAfterMerged = overviewNode?.get("deleteBranchAfterMerged")?.asBoolean() ?: false
        )
        val commits = data?.get("commits")?.map {
            CommitItem(
                author = it.readText("commitBy", "author", "committer"),
                hash = it.readText("commitId", "hash", "id"),
                message = it.readText("commitMsg", "message", "msg"),
                time = it.readText("commitTime", "time", "date")
            )
        } ?: emptyList()
        return PrDetail(
            id = data?.get("id")?.asLong() ?: -1L,
            title = data?.get("title")?.asText() ?: "",
            status = data?.get("status")?.asText() ?: "",
            sourceBranch = data?.get("sourceBranch")?.asText() ?: "",
            targetBranch = data?.get("targetBranch")?.asText() ?: "",
            author = data?.get("author")?.asText() ?: "",
            createTime = data?.get("createTime")?.asText() ?: "",
            reviewPass = data?.get("reviewPass")?.asBoolean() ?: false,
            overview = overview,
            commits = commits
        )
    }

    private fun parseIssueStats(body: String): IssueStats {
        val root = objectMapper.readTree(body)
        val data = root.get("data")
        return IssueStats(
            total = data?.get("total")?.asInt() ?: 0,
            unresolved = data?.get("unresolved")?.asInt() ?: 0,
            issues = data?.get("issues")?.map { parseIssueItem(it) } ?: emptyList()
        )
    }

    private fun parseIssueItem(node: JsonNode): IssueItem {
        return IssueItem(
            id = node.get("id")?.asText() ?: "",
            number = node.get("number")?.asText() ?: "",
            createBy = node.get("createBy")?.asText() ?: "",
            msg = node.get("msg")?.asText() ?: "",
            createTime = node.get("createTime")?.asText() ?: "",
            file = node.get("file")?.asText() ?: "",
            line = node.get("line")?.asInt() ?: 0,
            status = node.get("status")?.asText() ?: "",
            replies = node.get("replies")?.map {
                IssueReply(
                    num = it.get("num")?.asText() ?: "",
                    createBy = it.get("createBy")?.asText() ?: "",
                    msg = it.get("msg")?.asText() ?: "",
                    createTime = it.get("createTime")?.asText() ?: "",
                    responseTo = it.get("responseTo")?.asText() ?: ""
                )
            } ?: emptyList()
        )
    }

    private fun statusColor(status: String): JBColor {
        return when (status.trim().lowercase()) {
            "open" -> JBColor(Color(0x1A73E8), Color(0x6EA8FF))
            "merged" -> JBColor(Color(0x1E8E3E), Color(0x57D163))
            "closed" -> JBColor(Color(0xD93025), Color(0xF47067))
            else -> JBColor.GRAY
        }
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

    private inner class ChangeTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: javax.swing.JTree?,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode
            val userObject = node?.userObject
            when (userObject) {
                is ChangeItem -> {
                    val fileName = userObject.filePath.substringAfterLast('/')
                    val typeLabel = changeTypeFullText(userObject.changeType)
                    val fromPath = userObject.fromFilePath
                    val renameHint = if (typeLabel.startsWith("RENAMED") && !fromPath.isNullOrBlank()) " from $fromPath" else ""
                    val issueBadge = issueCountByFile(userObject.filePath)
                    val baseText = "$fileName ($typeLabel$renameHint)"
                    text = if (issueBadge == null) {
                        baseText
                    } else {
                        val (unresolved, total) = issueBadge
                        if (sel) {
                            "$baseText  $unresolved/$total"
                        } else {
                            "<html>$baseText&nbsp;&nbsp;<span style='color:#D93025;'>$unresolved</span>/$total</html>"
                        }
                    }
                    icon = changeTypeIcon(userObject.changeType)
                    font = font.deriveFont(Font.PLAIN, globalUiFontSize)
                    if (!sel) {
                        foreground = changeTypeColor(userObject.changeType)
                    }
                }
                is String -> {
                    text = userObject
                    icon = null
                    font = font.deriveFont(Font.PLAIN, globalUiFontSize)
                }
            }
            return component
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

    private data class PrListResult(
        val total: Int,
        val items: List<PrItem>
    )

    private data class PrItem(
        val id: Long,
        val title: String,
        val sourceBranch: String,
        val targetBranch: String,
        val author: String,
        val state: PrState
    )

    private data class PrDetail(
        val id: Long,
        val title: String,
        val status: String,
        val sourceBranch: String,
        val targetBranch: String,
        val author: String,
        val createTime: String,
        val reviewPass: Boolean,
        val overview: PrOverview,
        val commits: List<CommitItem>
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
        private val columns = arrayOf("标题", "源分支", "目标分支", "创建人")
        private var rows: List<PrItem> = emptyList()

        fun setRows(items: List<PrItem>, append: Boolean) {
            rows = if (append) rows + items else items
            fireTableDataChanged()
        }

        fun getItemAt(row: Int): PrItem = rows[row]

        fun lastId(): Long {
            return rows.lastOrNull()?.id ?: -1L
        }

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

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = rows[rowIndex]
            return when (columnIndex) {
                0 -> item.author
                1 -> item.hash
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
