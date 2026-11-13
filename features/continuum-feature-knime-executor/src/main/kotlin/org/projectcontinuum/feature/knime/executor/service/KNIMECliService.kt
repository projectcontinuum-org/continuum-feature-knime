package org.projectcontinuum.feature.knime.executor.service

import org.projectcontinuum.feature.knime.executor.config.KnimeExecutorProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ProcessResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String
)

data class WorkflowVariable(
  val name: String,
  val value: String,
  val type: String
)

data class WorkflowCredential(
  val name: String,
  val login: String? = null,
  val password: String? = null
)

@Service
class KNIMECliService(
  private val knimeProperties: KnimeExecutorProperties
) {
  companion object {
    private val LOGGER = LoggerFactory.getLogger(KNIMECliService::class.java)
    private const val KNIME_BATCH_EXECUTOR = "com.nodepit.batch.application.NodePitBatchExecutor"
    private const val CREDENTIAL_FLAG = "--credential="

    /** How long to wait for the stdout/stderr reader threads to drain after the process ends. */
    private const val READER_JOIN_TIMEOUT_MS = 5_000L

    /** How long to wait for a forcibly destroyed process (tree) to actually exit. */
    private const val KILL_WAIT_SECONDS = 5L
  }

  /**
   * Executes a KNIME workflow using the KNIME batch executor.
   *
   * The executable is started directly via [ProcessBuilder] with an argument list — never
   * through a shell — so no argument (workflow path, variable value, credential) is ever
   * subject to shell expansion.
   *
   * @param workflowPath Path to the extracted KNIME workflow directory
   * @param workspaceDir Temporary workspace directory for KNIME execution
   * @param timeoutSeconds Timeout in seconds for workflow execution
   * @return ProcessResult containing exit code and output
   * @throws RuntimeException if execution fails (process cannot be started, or times out)
   */
  fun executeWorkflow(
    workflowPath: String,
    workspaceDir: String,
    timeoutSeconds: Int,
    reset: Boolean = false,
    variables: List<WorkflowVariable> = emptyList(),
    credentials: List<WorkflowCredential> = emptyList()
  ): ProcessResult {
    val command = buildCommand(workflowPath, workspaceDir, reset, variables, credentials)

    LOGGER.info("Executing KNIME workflow: $workflowPath")
    LOGGER.debug("KNIME command: {}", redactCredentials(command))

    return try {
      val process = ProcessBuilder(command).apply {
        redirectErrorStream(false)
      }.start()
      // The batch executor never reads stdin; close it so the child sees EOF instead of blocking.
      runCatching { process.outputStream.close() }

      val stdout = StringBuffer()
      val stderr = StringBuffer()

      // Drain stdout and stderr concurrently: reading one to EOF before the other deadlocks
      // as soon as the child fills the other pipe's OS buffer.
      val stdoutReader = startReader("knime-stdout", process.inputStream, stdout) { line ->
        LOGGER.debug("KNIME stdout: $line")
      }
      val stderrReader = startReader("knime-stderr", process.errorStream, stderr) { line ->
        LOGGER.warn("KNIME stderr: $line")
      }

      val completed = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
      if (!completed) {
        LOGGER.error("KNIME process timed out after $timeoutSeconds seconds; killing process tree")
        destroyProcessTree(process)
        joinReaders(process, stdoutReader, stderrReader)
        throw RuntimeException("KNIME workflow execution timed out after $timeoutSeconds seconds")
      }
      joinReaders(process, stdoutReader, stderrReader)

      val exitCode = process.exitValue()
      LOGGER.info("KNIME process completed with exit code: $exitCode")

      if (exitCode != 0) {
        LOGGER.error("KNIME workflow execution failed with exit code $exitCode")
        LOGGER.error("stderr: $stderr")
      }

      ProcessResult(
        exitCode = exitCode,
        stdout = stdout.toString(),
        stderr = stderr.toString()
      )
    } catch (e: Exception) {
      LOGGER.error("Error executing KNIME workflow: ${e.message}", e)
      throw RuntimeException("Failed to execute KNIME workflow: ${e.message}", e)
    }
  }

  /**
   * Builds the batch executor argument list. Each element is passed verbatim as one argv entry
   * (no shell), so values containing spaces, quotes, `$`, `;`, etc. need no escaping.
   *
   * Note: credentials (including passwords) are passed as `--credential=` arguments because
   * that is the only way the KNIME/NodePit batch CLI accepts them; they are therefore visible
   * in the process list (`ps`) for the lifetime of the run. This is a KNIME CLI limitation.
   */
  internal fun buildCommand(
    workflowPath: String,
    @Suppress("UNUSED_PARAMETER") workspaceDir: String,
    reset: Boolean,
    variables: List<WorkflowVariable> = emptyList(),
    credentials: List<WorkflowCredential> = emptyList()
  ): List<String> = buildList {
    add(knimeProperties.getExecutablePath())
    add("-nosplash")
    add("--launcher.suppressErrors")
    add("-application")
    add(KNIME_BATCH_EXECUTOR)
    variables.forEach { variable ->
      add("--variable=${variable.name};${variable.value};${variable.type}")
    }
    credentials.forEach { credential ->
      add(CREDENTIAL_FLAG + buildCredentialValue(credential))
    }
    if (reset) {
      add("--reset")
    }
    add("--save")
    add(workflowPath)
  }

  /**
   * The CLI's `<name[;login[;password]]>` syntax is positional/optional, so build the value
   * from only the trailing present components — never emit an empty segment like `name;;`.
   */
  private fun buildCredentialValue(credential: WorkflowCredential): String = when {
    credential.login != null && credential.password != null -> "${credential.name};${credential.login};${credential.password}"
    credential.login != null -> "${credential.name};${credential.login}"
    else -> credential.name
  }

  /**
   * Renders the command for logging with credential login/password replaced; only the KNIME
   * credential name (which cannot contain ';') is kept.
   */
  internal fun redactCredentials(command: List<String>): String = command.joinToString(" ") { arg ->
    if (arg.startsWith(CREDENTIAL_FLAG) && arg.contains(";")) {
      arg.substringBefore(";") + ";<redacted>"
    } else {
      arg
    }
  }

  private fun startReader(
    name: String,
    stream: InputStream,
    sink: StringBuffer,
    onLine: (String) -> Unit
  ): Thread = Thread({
    runCatching {
      stream.bufferedReader().use { reader ->
        reader.lineSequence().forEach { line ->
          onLine(line)
          sink.append(line).append("\n")
        }
      }
    }
  }, name).apply {
    isDaemon = true
    start()
  }

  /**
   * Forcibly kills the process and all its descendants. Descendants are snapshotted before the
   * parent is killed, since they are re-parented (and no longer reported) once it exits.
   */
  private fun destroyProcessTree(process: Process) {
    val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
    descendants.forEach { runCatching { it.destroyForcibly() } }
    process.destroyForcibly()
    runCatching { process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS) }
  }

  /**
   * Waits (bounded) for the reader threads to drain. If a stray process still holds a pipe open,
   * close the streams so the readers unblock rather than leaking the thread forever.
   */
  private fun joinReaders(process: Process, vararg readers: Thread) {
    readers.forEach { it.join(READER_JOIN_TIMEOUT_MS) }
    if (readers.any { it.isAlive }) {
      LOGGER.warn("KNIME output readers did not finish in time; closing process streams")
      runCatching { process.inputStream.close() }
      runCatching { process.errorStream.close() }
      readers.forEach { it.join(READER_JOIN_TIMEOUT_MS) }
    }
  }
}
