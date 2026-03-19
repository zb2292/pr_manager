package com.gitee.prviewer.service

import com.gitee.prviewer.model.ChangeItem
import com.gitee.prviewer.model.CompareResult
import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

class BranchCompareService(private val project: Project) {

    fun compare(sourceBranch: String, targetBranch: String): CompareResult {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: return CompareResult(emptyList(), "未找到 Git 仓库")

        val result = runDiff(repo, sourceBranch, targetBranch)
        if (!result.success()) {
            val message = result.errorOutput.joinToString("\n").ifBlank { "分支对比失败" }
            return CompareResult(emptyList(), message)
        }

        val changes = result.output.mapNotNull { parseDiffLine(it) }
        return CompareResult(changes)
    }

    fun loadFileContent(branch: String, filePath: String): String? {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        val handler = GitLineHandler(project, repo.root, GitCommand.SHOW)
        handler.addParameters("$branch:$filePath")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        return result.output.joinToString("\n")
    }

    private fun runDiff(repo: GitRepository, sourceBranch: String, targetBranch: String) =
        Git.getInstance().runCommand(
            GitLineHandler(project, repo.root, GitCommand.DIFF).apply {
                addParameters("--name-status", "-M", "-C", sourceBranch, targetBranch)
            }
        )

    private fun parseDiffLine(line: String): ChangeItem? {
        if (line.isBlank()) return null
        val columns = line.split('\t')
        if (columns.size < 2) return null

        val status = columns[0].trim()
        return when {
            status.startsWith("R") || status.startsWith("C") -> {
                if (columns.size < 3) return null
                val fromPath = columns[1].trim()
                val toPath = columns[2].trim()
                if (toPath.isBlank()) null else ChangeItem(toPath, status, fromPath.ifBlank { null })
            }
            else -> {
                val path = columns[1].trim()
                if (path.isBlank()) null else ChangeItem(path, status)
            }
        }
    }
}
