package com.gitee.prviewer.toolwindow

import com.gitee.prviewer.service.PrManagerFileLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import java.util.Properties
import javax.swing.JPanel
import javax.swing.JTextArea

class PrManagerToolWindowFactory : ToolWindowFactory {
    companion object {
        private const val FONT_SIZE_KEY = "prviewer.ui.font.size"
        private const val DEFAULT_FONT_SIZE = 13f

        private fun loadGlobalFontSize(): Float {
            val props = Properties()
            val stream = PrManagerToolWindowFactory::class.java.getResourceAsStream("/prviewer.properties")
            if (stream != null) {
                stream.use { props.load(it) }
            }
            return props.getProperty(FONT_SIZE_KEY, DEFAULT_FONT_SIZE.toString()).toFloatOrNull() ?: DEFAULT_FONT_SIZE
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentComponent = try {
            PrManagerPanel(project)
        } catch (t: Throwable) {
            PrManagerFileLogger.error("Failed to create PR Manager tool window content", t)
            buildFallbackPanel(t)
        }

        val content = ContentFactory.SERVICE.getInstance().createContent(contentComponent, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun buildFallbackPanel(error: Throwable): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(12)
        val globalFontSize = loadGlobalFontSize()

        val title = JBLabel("PR Manager 加载失败，请点击关闭后重试").apply {
            font = font.deriveFont(Font.BOLD, globalFontSize)
        }
        panel.add(title, BorderLayout.NORTH)

        val detail = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(Font.PLAIN, globalFontSize)
            text = buildString {
                append(error::class.java.name)
                if (!error.message.isNullOrBlank()) {
                    append(": ")
                    append(error.message)
                }
                append("\n\n日志路径: ")
                append(PrManagerFileLogger.currentLogPath())
            }
            border = JBUI.Borders.emptyTop(8)
        }
        panel.add(JBScrollPane(detail), BorderLayout.CENTER)

        return panel
    }
}
