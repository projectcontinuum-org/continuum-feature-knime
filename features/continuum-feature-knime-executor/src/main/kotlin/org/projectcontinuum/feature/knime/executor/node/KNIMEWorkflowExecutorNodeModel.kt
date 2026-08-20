package org.projectcontinuum.feature.knime.executor.node

import org.projectcontinuum.core.commons.annotation.ContinuumNode
import org.projectcontinuum.core.commons.context.ExecutionContext
import org.projectcontinuum.core.commons.exception.NodeRuntimeException
import org.projectcontinuum.core.commons.model.ContinuumWorkflowModel
import org.projectcontinuum.core.commons.node.ProcessNodeModel
import org.projectcontinuum.core.commons.protocol.progress.NodeProgressCallback
import org.projectcontinuum.core.commons.protocol.progress.NodeProgress
import org.projectcontinuum.core.commons.protocol.progress.StageStatus
import org.projectcontinuum.core.commons.utils.NodeInputReader
import org.projectcontinuum.core.commons.utils.NodeOutputWriter
import org.projectcontinuum.feature.knime.executor.service.KNIMECliService
import org.projectcontinuum.feature.knime.executor.config.KnimeExecutorProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.TEXT_PLAIN_VALUE
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.pathString

@ContinuumNode
class KNIMEWorkflowExecutorNodeModel(
  private val objectMapper: ObjectMapper,
  private val knimeCliService: KNIMECliService,
  private val knimeProperties: KnimeExecutorProperties
) : ProcessNodeModel() {

  companion object {
    private val LOGGER = LoggerFactory.getLogger(KNIMEWorkflowExecutorNodeModel::class.java)

    // Stage names for progress reporting
    private const val STAGE_DOWNLOAD_WORKFLOW = "Download Workflow"
    private const val STAGE_EXECUTE_WORKFLOW = "Execute Workflow"
    private const val STAGE_UPLOAD_RESULTS = "Upload Results"
    private const val STAGE_CLEANUP = "Cleanup"
  }

  final override val inputPorts = emptyMap<String, ContinuumWorkflowModel.NodePort>()
  final override val outputPorts = mapOf(
    "destinationPath" to ContinuumWorkflowModel.NodePort(
      name = "destination path",
      contentType = TEXT_PLAIN_VALUE
    )
  )

  override val categories = listOf("Integration & Automation")

  val propertiesSchema: Map<String, Any> = objectMapper.readValue(
    """
      {
        "type": "object",
        "properties": {
          "workflowLocation": {
            "type": "string",
            "title": "Workflow Location",
            "description": "Local path or URL to a KNIME .knwf workflow file: path/to/workflow.knwf, https://example.com/workflow.knwf, or s3://bucket/workflow.knwf"
          },
          "timeoutSeconds": {
            "type": "integer",
            "title": "Execution Timeout (seconds)",
            "default": 300,
            "minimum": 10
          },
          "resetWorkflow": {
            "type": "boolean",
            "title": "Reset Workflow Before Execution",
            "description": "Pass --reset to the KNIME batch executor to clear node outputs before running",
            "default": false
          }
        },
        "required": ["workflowLocation"]
      }
    """.trimIndent(),
    object : TypeReference<Map<String, Any>>() {}
  )

  val propertiesUiSchema: Map<String, Any> = objectMapper.readValue(
    """
      {
        "type": "Categorization",
        "elements": [
          {
            "type": "Category",
            "label": "Workflow Configuration",
            "elements": [
              {
                "type": "Control",
                "scope": "#/properties/workflowLocation",
                "options": {
                  "placeholder": "https://example.com/workflow.knwf or /path/to/workflow.knwf"
                }
              }
            ]
          },
          {
            "type": "Category",
            "label": "Execution Settings",
            "elements": [
              {
                "type": "Control",
                "scope": "#/properties/timeoutSeconds"
              },
              {
                "type": "Control",
                "scope": "#/properties/resetWorkflow"
              }
            ]
          }
        ]
      }
    """.trimIndent(),
    object : TypeReference<Map<String, Any>>() {}
  )

  override val metadata = ContinuumWorkflowModel.NodeData(
    id = this.javaClass.name,
    description = "Download, execute, and upload KNIME workflows",
    title = "KNIME Workflow Executor",
    subTitle = "Execute KNIME workflows from remote locations",
    nodeModel = this.javaClass.name,
    icon = """
      <svg xmlns="http://www.w3.org/2000/svg" fill="none" stroke="currentColor" stroke-width="1.5" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" d="M9.594 3.94c.09-.542.56-.94 1.11-.94h2.592c.55 0 1.02.398 1.11.94m-9.642 0A9.009 9.009 0 005.416 3c.44 0 .888.023 1.332.069m0 0a9.015 9.015 0 018.504 0m0 0c.44-.046.888-.069 1.332-.069.513 0 1.02.027 1.523.078m-9.642.908a9.01 9.01 0 012.812-.946c.44.058.888.088 1.337.088.45 0 .898-.03 1.337-.09a9.01 9.01 0 012.812.946m-18.217.744h7.564a2.25 2.25 0 00-2.25-2.25H5.5a2.25 2.25 0 00-2.25 2.25zm7.6 2.355a11.261 11.261 0 015.772 1.6c.419.264.83.544 1.231.836a8.968 8.968 0 00-1.231-.836 11.26 11.26 0 00-5.772-1.6m0 0H5.5a2.25 2.25 0 012.25 2.25v1.5m0 0a2.25 2.25 0 002.25 2.25h.716m0 0a2.25 2.25 0 002.25-2.25v-1.5m0 0h7.5a2.25 2.25 0 012.25 2.25v7.5a2.25 2.25 0 01-2.25 2.25h-7.5a2.25 2.25 0 01-2.25-2.25v-7.5m0 0V8.25m0 0a2.25 2.25 0 002.25-2.25V5.5" />
      </svg>
    """.trimIndent(),
    inputs = inputPorts,
    outputs = outputPorts,
    properties = mapOf(
      "workflowLocation" to "",
      "timeoutSeconds" to 300,
      "resetWorkflow" to false
    ),
    propertiesSchema = propertiesSchema,
    propertiesUISchema = propertiesUiSchema
  )

  override fun execute(
    properties: Map<String, Any>?,
    inputs: Map<String, NodeInputReader>,
    nodeOutputWriter: NodeOutputWriter,
    nodeProgressCallback: NodeProgressCallback,
    executionContext: ExecutionContext
  ) {
    LOGGER.info("KNIME Workflow Executor starting")

    // Helper function to report stage progress with rich detail
    fun reportStageProgress(
      progressPercentage: Int,
      message: String,
      downloadStage: StageStatus,
      executeStage: StageStatus,
      uploadStage: StageStatus,
      cleanupStage: StageStatus
    ) {
      nodeProgressCallback.report(
        NodeProgress(
          progressPercentage = progressPercentage,
          message = message,
          stageStatus = mapOf(
            STAGE_DOWNLOAD_WORKFLOW to downloadStage,
            STAGE_EXECUTE_WORKFLOW to executeStage,
            STAGE_UPLOAD_RESULTS to uploadStage,
            STAGE_CLEANUP to cleanupStage
          )
        )
      )
    }

    try {
      // Validate properties
      reportStageProgress(
        progressPercentage = 0,
        message = "Initializing workflow executor",
        downloadStage = StageStatus.PENDING,
        executeStage = StageStatus.PENDING,
        uploadStage = StageStatus.PENDING,
        cleanupStage = StageStatus.PENDING
      )

      val workflowLocation = properties?.get("workflowLocation") as? String
        ?: throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "workflowLocation property is required"
        )

      if (!workflowLocation.lowercase().endsWith(".knwf")) {
        throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "workflowLocation must point to a .knwf file: $workflowLocation"
        )
      }

      // Output location is implicit and configured by the worker via system property or environment variable
      val outputLocation = System.getProperty("knime.results.output")
        ?: System.getenv("KNIME_RESULTS_OUTPUT")
        ?: File(System.getProperty("java.io.tmpdir"), "knime-results").absolutePath

      val timeoutSeconds = (properties["timeoutSeconds"] as? Number)?.toInt() ?: 300
      val resetWorkflow = properties["resetWorkflow"] as? Boolean ?: false

      if (timeoutSeconds < 10) {
        throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Timeout must be at least 10 seconds"
        )
      }

      LOGGER.info("Configuration: workflowLocation=$workflowLocation, timeout=$timeoutSeconds")
      LOGGER.info("Results will be stored at: $outputLocation")

      // Create temporary workspace (always use system temp, not user-configurable)
      val baseWorkspaceDir = File(System.getProperty("java.io.tmpdir"))
      val tempDir = File(baseWorkspaceDir, "knime_workspace_${UUID.randomUUID()}")
      tempDir.mkdirs()

      LOGGER.info("Created temp workspace: ${tempDir.absolutePath}")

      try {
        // Download workflow (0-30%)
        reportStageProgress(
          progressPercentage = 0,
          message = "Downloading workflow from: $workflowLocation",
          downloadStage = StageStatus.IN_PROGRESS,
          executeStage = StageStatus.PENDING,
          uploadStage = StageStatus.PENDING,
          cleanupStage = StageStatus.PENDING
        )
        LOGGER.info("Downloading workflow from: $workflowLocation")
        val destFileName = determineDestinationName(workflowLocation)
        val workflowSource = File(tempDir, destFileName)
        downloadWorkflow(workflowLocation, workflowSource)

        reportStageProgress(
          progressPercentage = 30,
          message = "Workflow retrieved successfully",
          downloadStage = StageStatus.COMPLETED,
          executeStage = StageStatus.PENDING,
          uploadStage = StageStatus.PENDING,
          cleanupStage = StageStatus.PENDING
        )

        // KNIME batch executor (NodePitBatchExecutor) accepts the .knwf path directly:
        // it unzips, executes, and saves the result back into the same .knwf file.
        val executionWorkflowPath = workflowSource.absolutePath

        // Execute workflow (30-70%)
        reportStageProgress(
          progressPercentage = 30,
          message = "Executing KNIME workflow (timeout: ${timeoutSeconds}s)",
          downloadStage = StageStatus.COMPLETED,
          executeStage = StageStatus.IN_PROGRESS,
          uploadStage = StageStatus.PENDING,
          cleanupStage = StageStatus.PENDING
        )
        LOGGER.info("Executing KNIME workflow from: $executionWorkflowPath")
        val result = knimeCliService.executeWorkflow(
          workflowPath = executionWorkflowPath,
          workspaceDir = tempDir.absolutePath,
          timeoutSeconds = timeoutSeconds,
          reset = resetWorkflow
        )

        if (result.exitCode != 0) {
          LOGGER.error("KNIME workflow execution failed with exit code ${result.exitCode}")
          LOGGER.error("stderr: ${result.stderr}")
          throw NodeRuntimeException(
            workflowId = "",
            nodeId = "",
            message = "KNIME workflow execution failed with exit code ${result.exitCode}: ${result.stderr}"
          )
        }

        reportStageProgress(
          progressPercentage = 70,
          message = "KNIME workflow executed successfully",
          downloadStage = StageStatus.COMPLETED,
          executeStage = StageStatus.COMPLETED,
          uploadStage = StageStatus.PENDING,
          cleanupStage = StageStatus.PENDING
        )

        // Upload result (70-90%)
        reportStageProgress(
          progressPercentage = 70,
          message = "Uploading results to: $outputLocation",
          downloadStage = StageStatus.COMPLETED,
          executeStage = StageStatus.COMPLETED,
          uploadStage = StageStatus.IN_PROGRESS,
          cleanupStage = StageStatus.PENDING
        )
        LOGGER.info("Uploading workflow to: $outputLocation")
        val destinationPath = uploadWorkflow(workflowSource, outputLocation)
        nodeOutputWriter.createOutputPortWriter("destinationPath").use { writer ->
          writer.write(0L, mapOf("destinationPath" to destinationPath))
        }

        reportStageProgress(
          progressPercentage = 90,
          message = "Results uploaded successfully",
          downloadStage = StageStatus.COMPLETED,
          executeStage = StageStatus.COMPLETED,
          uploadStage = StageStatus.COMPLETED,
          cleanupStage = StageStatus.PENDING
        )

        // Cleanup is mandatory (90-100%)
        reportStageProgress(
          progressPercentage = 90,
          message = "Cleaning up temporary files",
          downloadStage = StageStatus.COMPLETED,
          executeStage = StageStatus.COMPLETED,
          uploadStage = StageStatus.COMPLETED,
          cleanupStage = StageStatus.IN_PROGRESS
        )
        LOGGER.info("Cleaning up temporary files")
        tempDir.deleteRecursively()
        LOGGER.info("Cleanup complete")

        reportStageProgress(
          progressPercentage = 100,
          message = "Workflow execution completed successfully",
          downloadStage = StageStatus.COMPLETED,
          executeStage = StageStatus.COMPLETED,
          uploadStage = StageStatus.COMPLETED,
          cleanupStage = StageStatus.COMPLETED
        )
        LOGGER.info("KNIME Workflow Executor completed successfully")

      } catch (e: Exception) {
        LOGGER.error("Error during workflow execution", e)
        // Attempt cleanup even on error
        if (tempDir.exists()) {
          tempDir.deleteRecursively()
        }
        throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Workflow execution failed: ${e.message}"
        )
      }

    } catch (e: NodeRuntimeException) {
      LOGGER.error("KNIME Workflow Executor error: ${e.message}", e)
      throw e
    } catch (e: Exception) {
      LOGGER.error("Unexpected error in KNIME Workflow Executor", e)
      throw NodeRuntimeException(
        workflowId = "",
        nodeId = "",
        message = "Unexpected error: ${e.message}"
      )
    }
  }

  private fun determineDestinationName(location: String): String {
    // Extract filename from location (extension already validated as .knwf)
    return when {
      location.contains("/") -> location.substringAfterLast("/")
      location.contains("\\") -> location.substringAfterLast("\\")
      else -> location
    }
  }

  private fun downloadWorkflow(location: String, destination: File) {
    try {
      when {
        location.startsWith("s3://") -> downloadFromS3(location, destination)
        location.startsWith("http://") || location.startsWith("https://") -> downloadFromHttp(location, destination)
        location.startsWith("/") || location.contains(":\\") -> {
          // Local .knwf file path
          val source = File(location)
          if (!source.exists()) {
            throw Exception("Workflow file not found: $location")
          }
          if (!source.isFile) {
            throw Exception("Unsupported workflow format. Use a .knwf file: $location")
          }

          LOGGER.info("Copying workflow file from: $location")
          source.copyTo(destination, overwrite = true)
          LOGGER.info("Copied workflow file: $location")
        }
        else -> throw Exception("Unsupported workflow location format: $location")
      }
    } catch (e: Exception) {
      throw NodeRuntimeException(
        workflowId = "",
        nodeId = "",
        message = "Failed to download workflow: ${e.message}"
      )
    }
  }

  private fun downloadFromHttp(url: String, destination: File) {
    try {
      val connection = java.net.URL(url).openConnection()
      connection.inputStream.use { input ->
        destination.outputStream().use { output ->
          input.copyTo(output)
        }
      }
      LOGGER.info("Downloaded workflow from HTTP: $url")
    } catch (e: Exception) {
      throw Exception("HTTP download failed: ${e.message}")
    }
  }

  private fun downloadFromS3(s3Path: String, destination: File) {
    // TODO: Implement S3 download using AWS SDK
    // For now, provide a placeholder
    LOGGER.warn("S3 download not yet implemented for path: $s3Path")
    throw NotImplementedError("S3 download support coming in future version")
  }

  private fun uploadWorkflow(sourceFile: File, location: String): String {
    try {
      return when {
        location.startsWith("s3://") -> uploadToS3(sourceFile, location)
        location.startsWith("/") || location.contains(":\\") -> {
          // Local file path
          val destDir = File(location)
          destDir.mkdirs()
          val destFile = File(destDir, sourceFile.name)
          sourceFile.copyTo(destFile, overwrite = true)
          LOGGER.info("Uploaded workflow to local path: ${destFile.absolutePath}")
          destFile.absolutePath
        }
        else -> throw Exception("Unsupported output location format: $location")
      }
    } catch (e: Exception) {
      throw NodeRuntimeException(
        workflowId = "",
        nodeId = "",
        message = "Failed to upload workflow: ${e.message}"
      )
    }
  }

  private fun uploadToS3(sourceFile: File, s3Path: String): String {
    // TODO: Implement S3 upload using AWS SDK
    // For now, provide a placeholder
    LOGGER.warn("S3 upload not yet implemented for path: $s3Path")
    throw NotImplementedError("S3 upload support coming in future version")
  }
}
