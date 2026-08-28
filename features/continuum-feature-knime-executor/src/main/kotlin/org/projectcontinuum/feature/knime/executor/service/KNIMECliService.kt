package org.projectcontinuum.feature.knime.executor.service

import org.projectcontinuum.feature.knime.executor.config.KnimeExecutorProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
  }

  /**
   * Executes a KNIME workflow using the KNIME batch executor.
   *
   * @param workflowPath Path to the extracted KNIME workflow directory
   * @param workspaceDir Temporary workspace directory for KNIME execution
   * @param timeoutSeconds Timeout in seconds for workflow execution
   * @return ProcessResult containing exit code and output
   * @throws RuntimeException if execution fails
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
    LOGGER.debug("KNIME command: $command")

    return try {
      val process = ProcessBuilder("/bin/bash", "-c", command).apply {
        redirectErrorStream(false)
      }.start()

      val stdout = StringBuilder()
      val stderr = StringBuilder()

      // Capture stdout
      process.inputStream.bufferedReader().use { reader ->
        reader.lines().forEach { line ->
          LOGGER.debug("KNIME stdout: $line")
          stdout.append(line).append("\n")
        }
      }

      // Capture stderr
      process.errorStream.bufferedReader().use { reader ->
        reader.lines().forEach { line ->
          LOGGER.warn("KNIME stderr: $line")
          stderr.append(line).append("\n")
        }
      }

      // Wait for process with timeout
      val completed = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
      if (!completed) {
        process.destroy()
        LOGGER.error("KNIME process timed out after $timeoutSeconds seconds")
        throw RuntimeException("KNIME workflow execution timed out after $timeoutSeconds seconds")
      }

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

  internal fun buildCommand(
    workflowPath: String,
    workspaceDir: String,
    reset: Boolean,
    variables: List<WorkflowVariable> = emptyList(),
    credentials: List<WorkflowCredential> = emptyList()
  ): String {
    val resetFlag = if (reset) "--reset \\\n      " else ""
    val variableFlags = variables.joinToString("") { variable ->
      "--variable=${shellQuote("${variable.name};${variable.value};${variable.type}")} \\\n      "
    }
    val credentialFlags = credentials.joinToString("") { credential ->
      "--credential=${shellQuote(buildCredentialValue(credential))} \\\n      "
    }
    return """
      "${knimeProperties.getExecutablePath()}" \
      -nosplash \
      --launcher.suppressErrors \
      -application $KNIME_BATCH_EXECUTOR \
      $variableFlags$credentialFlags$resetFlag--save \
      "$workflowPath"
    """.trimIndent()
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
   * The full command runs via `/bin/bash -c`, so any interpolated value coming from user
   * input (workflow variable values) must be shell-escaped to prevent command injection.
   */
  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
