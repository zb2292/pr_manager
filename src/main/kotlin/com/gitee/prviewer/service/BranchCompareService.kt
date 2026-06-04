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

        val (nameStatusResult, numStatResult) = resolveDiffResults(repo, sourceBranch, targetBranch)
        if (!nameStatusResult.success()) {
            val message = nameStatusResult.errorOutput.joinToString("\n").ifBlank { "分支对比失败" }
            return CompareResult(emptyList(), message)
        }

        val numStatByPath = if (numStatResult.success()) {
            numStatResult.output.mapNotNull { parseNumStatLine(it) }.toMap()
        } else {
            emptyMap()
        }

        val changes = nameStatusResult.output.mapNotNull { line ->
            parseDiffLine(line, numStatByPath)
        }
        return CompareResult(changes)
    }

    fun compareBetweenRefs(baseRef: String, headRef: String, pathFilters: Set<String> = emptySet()): CompareResult {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: return CompareResult(emptyList(), "未找到 Git 仓库")

        val nameStatusResult = runDirectDiff(repo, baseRef, headRef, pathFilters)
        if (!nameStatusResult.success()) {
            val message = nameStatusResult.errorOutput.joinToString("\n").ifBlank { "分支对比失败" }
            return CompareResult(emptyList(), message)
        }

        val numStatResult = runDirectNumStat(repo, baseRef, headRef, pathFilters)
        val numStatByPath = if (numStatResult.success()) {
            numStatResult.output.mapNotNull { parseNumStatLine(it) }.toMap()
        } else {
            emptyMap()
        }

        val changes = nameStatusResult.output.mapNotNull { line ->
            parseDiffLine(line, numStatByPath)
        }
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

    private fun resolveDiffResults(
        repo: GitRepository,
        sourceBranch: String,
        targetBranch: String
    ): Pair<git4idea.commands.GitCommandResult, git4idea.commands.GitCommandResult> {
        val mergeBase = resolveMergeBase(repo, sourceBranch, targetBranch)
        return if (!mergeBase.isNullOrBlank()) {
            runDiffFromBase(repo, mergeBase, targetBranch) to runNumStatFromBase(repo, mergeBase, targetBranch)
        } else {
            runDiff(repo, sourceBranch, targetBranch) to runNumStat(repo, sourceBranch, targetBranch)
        }
    }

    private fun runDiff(repo: GitRepository, sourceBranch: String, targetBranch: String) =
        Git.getInstance().runCommand(
            GitLineHandler(project, repo.root, GitCommand.DIFF).apply {
                addParameters("--name-status", "-M", "-C", sourceBranch, targetBranch)
            }
        )

    private fun runDiffFromBase(repo: GitRepository, mergeBase: String, sourceBranch: String) =
        Git.getInstance().runCommand(
            GitLineHandler(project, repo.root, GitCommand.DIFF).apply {
                addParameters("--name-status", "-M", "-C", mergeBase, sourceBranch)
            }
        )

    private fun runNumStat(repo: GitRepository, sourceBranch: String, targetBranch: String) =
        Git.getInstance().runCommand(
            GitLineHandler(project, repo.root, GitCommand.DIFF).apply {
                addParameters("--numstat", "-M", "-C", sourceBranch, targetBranch)
            }
        )

    private fun runNumStatFromBase(repo: GitRepository, mergeBase: String, sourceBranch: String) =
        Git.getInstance().runCommand(
            GitLineHandler(project, repo.root, GitCommand.DIFF).apply {
                addParameters("--numstat", "-M", "-C", mergeBase, sourceBranch)
            }
        )

    private fun runDirectDiff(
        repo: GitRepository,
        baseRef: String,
        headRef: String,
        pathFilters: Set<String>
    ) = Git.getInstance().runCommand(
        GitLineHandler(project, repo.root, GitCommand.DIFF).apply {
            addParameters("--name-status", "-M", "-C", baseRef, headRef)
            addPathFilters(pathFilters)
        }
    )

    private fun runDirectNumStat(
        repo: GitRepository,
        baseRef: String,
        headRef: String,
        pathFilters: Set<String>
    ) = Git.getInstance().runCommand(
        GitLineHandler(project, repo.root, GitCommand.DIFF).apply {
            addParameters("--numstat", "-M", "-C", baseRef, headRef)
            addPathFilters(pathFilters)
        }
    )

    private fun GitLineHandler.addPathFilters(pathFilters: Set<String>) {
        if (pathFilters.isEmpty()) return
        addParameters("--")
        pathFilters
            .map { it.trim().replace('\\', '/') }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { addParameters(it) }
    }

    private fun resolveMergeBase(repo: GitRepository, branchA: String, branchB: String): String? {
        val handler = GitLineHandler(project, repo.root, GitCommand.MERGE_BASE)
        handler.addParameters(branchA, branchB)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        return result.output.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
    }

    private fun parseDiffLine(line: String, numStatByPath: Map<String, Pair<Int, Int>>): ChangeItem? {
        if (line.isBlank()) return null
        val columns = line.split('\t')
        if (columns.size < 2) return null

        val status = columns[0].trim()
        return when {
            status.startsWith("R") || status.startsWith("C") -> {
                if (columns.size < 3) return null
                val fromPath = columns[1].trim()
                val toPath = columns[2].trim()
                if (toPath.isBlank()) {
                    null
                } else {
                    val stats = numStatByPath[toPath] ?: 0 to 0
                    ChangeItem(toPath, status, fromPath.ifBlank { null }, stats.first, stats.second)
                }
            }

            else -> {
                val path = columns[1].trim()
                if (path.isBlank()) {
                    null
                } else {
                    val stats = numStatByPath[path] ?: 0 to 0
                    ChangeItem(path, status, additions = stats.first, deletions = stats.second)
                }
            }
        }
    }

    private fun parseNumStatLine(line: String): Pair<String, Pair<Int, Int>>? {
        if (line.isBlank()) return null
        val columns = line.split('\t')
        if (columns.size < 3) return null

        val additions = columns[0].toIntOrNull() ?: 0
        val deletions = columns[1].toIntOrNull() ?: 0
        val path = columns.last().trim()
        if (path.isBlank()) return null
        return path to (additions to deletions)
    }
}
