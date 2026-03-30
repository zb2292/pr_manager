package com.gitee.prviewer.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PrManagerFileLogger {
    private val lock = Any()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private const val runLogName = "run.log"
    private const val runDateFileName = "run-date.txt"

    fun info(message: String) {
        write("INFO", message, null)
    }

    fun warn(message: String, throwable: Throwable? = null) {
        write("WARN", message, throwable)
    }

    fun error(message: String, throwable: Throwable? = null) {
        write("ERROR", message, throwable)
    }

    fun currentLogPath(): String {
        return resolveLogDir().resolve(runLogName).toString()
    }

    private fun resolveLogDir(): Path {
        val home = System.getProperty("user.home") ?: "."
        return Paths.get(home, ".prManager")
    }

    private fun write(level: String, message: String, throwable: Throwable?) {
        runCatching {
            synchronized(lock) {
                val logDir = resolveLogDir()
                Files.createDirectories(logDir)
                rotateRunLogIfNeeded(logDir)

                val logFile = logDir.resolve(runLogName)
                val builder = StringBuilder()
                builder.append(LocalDateTime.now().format(timeFormatter))
                    .append(" [")
                    .append(level)
                    .append("] ")
                    .append(message)
                    .append(System.lineSeparator())

                if (throwable != null) {
                    builder.append(throwable::class.java.name)
                    if (!throwable.message.isNullOrBlank()) {
                        builder.append(": ").append(throwable.message)
                    }
                    builder.append(System.lineSeparator())
                    throwable.stackTrace.forEach { stack ->
                        builder.append("    at ")
                            .append(stack.toString())
                            .append(System.lineSeparator())
                    }
                }

                Files.writeString(
                    logFile,
                    builder.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )
            }
        }
    }

    private fun rotateRunLogIfNeeded(logDir: Path) {
        val today = LocalDate.now().format(dateFormatter)
        val runLog = logDir.resolve(runLogName)
        val runDateFile = logDir.resolve(runDateFileName)
        val recordedDate = if (Files.exists(runDateFile)) {
            Files.readString(runDateFile).trim().takeIf { it.isNotBlank() }
        } else {
            null
        }

        if (Files.exists(runLog) && recordedDate != today) {
            val archiveDate = recordedDate ?: runCatching {
                val modified = Files.getLastModifiedTime(runLog).toInstant()
                LocalDate.ofInstant(modified, ZoneId.systemDefault()).format(dateFormatter)
            }.getOrElse { today }

            val archiveFile = logDir.resolve("pr-manager-$archiveDate.log")
            if (Files.exists(archiveFile)) {
                val content = Files.readString(runLog)
                if (content.isNotEmpty()) {
                    Files.writeString(
                        archiveFile,
                        content,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    )
                }
                Files.deleteIfExists(runLog)
            } else {
                Files.move(runLog, archiveFile, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        Files.writeString(
            runDateFile,
            today,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
    }
}
