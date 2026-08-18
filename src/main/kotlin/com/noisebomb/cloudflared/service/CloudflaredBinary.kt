package com.noisebomb.cloudflared.service

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Asks a candidate executable what it is, by running `--version`. Without this the first sign that
 * `cloudflared` is missing — or that the path points at some other program — is a connection that
 * refuses to start, or worse, one that sits at "Running" because a long-lived process was spawned
 * and nothing it printed matched anything this plugin looks for.
 */
object CloudflaredBinary {

    sealed interface Result {
        /** Nothing runnable at that path, or nothing by that name on PATH. */
        data object Missing : Result

        /** It ran, but did not identify itself as cloudflared. [description] is what it said. */
        data class Unexpected(val description: String) : Result

        data class Ok(val version: String) : Result
    }

    private const val TIMEOUT_SECONDS = 5L
    private const val VERSION_PREFIX = "cloudflared version"

    fun probe(executable: String): Result {
        val command = GeneralCommandLine(executable, "--version").withCharset(StandardCharsets.UTF_8)
        val process = try {
            command.createProcess()
        } catch (e: ExecutionException) {
            thisLogger().debug("Could not run $executable", e)
            return Result.Missing
        }
        return try {
            // Waiting before reading: a program that is not cloudflared may never exit, and reading
            // its output first would block this thread for as long as it stays alive.
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result.Unexpected("it did not answer --version")
            }
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val line = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            when {
                line.startsWith(VERSION_PREFIX, ignoreCase = true) -> Result.Ok(line)
                line.isEmpty() -> Result.Unexpected("it printed nothing for --version")
                else -> Result.Unexpected(line)
            }
        } catch (e: IOException) {
            thisLogger().debug("Could not read the version of $executable", e)
            Result.Missing
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.Missing
        }
    }
}
