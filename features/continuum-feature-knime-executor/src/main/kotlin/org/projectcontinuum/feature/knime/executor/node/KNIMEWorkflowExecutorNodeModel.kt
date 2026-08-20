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
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
    private const val STAGE_UNZIP_WORKFLOW = "Unzip Workflow"
    private const val STAGE_EXECUTE_WORKFLOW = "Execute Workflow"
    private const val STAGE_UPLOAD_RESULTS = "Upload Results"
    private const val STAGE_CLEANUP = "Cleanup"
  }

  final override val inputPorts = emptyMap<String, ContinuumWorkflowModel.NodePort>()
  final override val outputPorts = emptyMap<String, ContinuumWorkflowModel.NodePort>()

  override val categories = listOf("Integration & Automation")

  val propertiesSchema: Map<String, Any> = objectMapper.readValue(
    """
      {
        "type": "object",
        "properties": {
          "workflowLocation": {
            "type": "string",
            "title": "Workflow Location",
            "description": "Local file/directory or URL to KNIME workflow: path/to/workflow.knwf, path/to/workflow.zip, path/to/workflow/, https://example.com/workflow.zip, or s3://bucket/workflow.zip"
          },
          "timeoutSeconds": {
            "type": "integer",
            "title": "Execution Timeout (seconds)",
            "default": 300,
            "minimum": 10
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
                  "placeholder": "https://example.com/workflow.zip or /path/to/workflow.zip"
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
      "timeoutSeconds" to 300
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
      unzipStage: StageStatus,
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
            STAGE_UNZIP_WORKFLOW to unzipStage,
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
        unzipStage = StageStatus.PENDING,
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

      // Output location is implicit and configured by the worker via system property or environment variable
      val outputLocation = System.getProperty("knime.results.output")
        ?: System.getenv("KNIME_RESULTS_OUTPUT")
        ?: File(System.getProperty("java.io.tmpdir"), "knime-results").absolutePath

      val timeoutSeconds = (properties["timeoutSeconds"] as? Number)?.toInt() ?: 300

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
      val workflowDir = File(tempDir, "workflow")
      workflowDir.mkdirs()

      LOGGER.info("Created temp workspace: ${tempDir.absolutePath}")

      try {
        // Download workflow (0-20%)
        reportStageProgress(
          progressPercentage = 0,
          message = "Downloading workflow from: $workflowLocation",
          downloadStage = StageStatus.IN_PROGRESS,
          unzipStage = StageStatus.PENDING,
          executeStage = StageStatus.PENDING,
          uploadStage = StageStatus.PENDING,
          cleanupStage = StageStatus.PENDING
        )
        LOGGER.info("Downloading workflow from: $workflowLocation")
        // Determine destination filename based on source (preserve .knwf extension)
        val destFileName = determineDestinationName(workflowLocation)
        val workflowSource = File(tempDir, destFileName)
        downloadWorkflow(workflowLocation, workflowSource)

        reportStageProgress(
          progressPercentage = 20,
          message = "Workflow retrieved successfully",
          downloadStage = StageStatus.COMPLETED,
          unzipStage = StageStatus.PENDING,
          executeStage = StageStatus.PENDING,
          uploadStage = StageStatus.PENDING,
          cleanupStage = StageStatus.PENDING
        )

        // Determine execution path and whether unzipping is needed
        // .knwf files are executed directly by KNIME batch executor (no unzip needed)
        // .zip files and directories need extraction/copying
        var executionWorkflowPath = workflowDir.absolutePath
        val isKnwfFile = workflowSource.isFile && workflowSource.name.endsWith(".knwf")

        if (isKnwfFile) {
          // KNIME batch executor supports .knwf files directly - no extraction needed
          LOGGER.info("Using .knwf file directly without extraction")
          executionWorkflowPath = workflowSource.absolutePath
          // Skip unzip stage since no extraction is needed
          reportStageProgress(
            progressPercentage = 40,
            message = "Workflow prepared successfully (native .knwf format)",
            downloadStage = StageStatus.COMPLETED,
            unzipStage = StageStatus.COMPLETED,
            executeStage = StageStatus.PENDING,
            uploadStage = StageStatus.PENDING,
            cleanupStage = StageStatus.PENDING
          )
        } else {
          // Unzip workflow if it's an archive (20-40%)
          reportStageProgress(
            progressPercentage = 20,
            message = "Processing workflow archive",
            downloadStage = StageStatus.COMPLETED,
            unzipStage = StageStatus.IN_PROGRESS,
            executeStage = StageStatus.PENDING,
            uploadStage = StageStatus.PENDING,
            cleanupStage = StageStatus.PENDING
          )
          
          // Extract if we have a ZIP file, or just use directory if already copied
          if (workflowSource.isFile && workflowSource.name.endsWith(".zip")) {
            LOGGER.info("Extracting ZIP workflow archive")
            unzipWorkflow(workflowSource, workflowDir)
            LOGGER.info("Workflow archive extracted")
          } else if (workflowSource.isDirectory) {
            // Already copied as directory, nothing more to do
            LOGGER.info("Workflow directory already in place")
            executionWorkflowPath = workflowSource.absolutePath
          }

          reportStageProgress(
            progressPercentage = 40,
            message = "Workflow prepared successfully",
            downloadStage = StageStatus.COMPLETED,
            unzipStage = StageStatus.COMPLETED,
            executeStage = StageStatus.PENDING,
            uploadStage = StageStatus.PENDING,
            cleanupStage = StageStatus.PENDING
          )
        }

        // Execute workflow (40-70%)
        reportStageProgress(
          progressPercentage = 40,
          message = "Executing KNIME workflow (timeout: ${timeoutSeconds}s)",
          downloadStage = StageStatus.COMPLETED,
          unzipStage = StageStatus.COMPLETED,
          executeStage = StageStatus.IN_PROGRESS,
          uploadStage = StageStatus.PENDING,
          cleanupStage = StageStatus.PENDING
        )
        LOGGER.info("Executing KNIME workflow from: $executionWorkflowPath")
        val result = knimeCliService.executeWorkflow(
          workflowPath = executionWorkflowPath,
          workspaceDir = tempDir.absolutePath,
          timeoutSeconds = timeoutSeconds
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
          unzipStage = StageStatus.COMPLETED,
          executeStage = StageStatus.COMPLETED,
          uploadStage = StageStatus.PENDING,
          cleanupStage = StageStatus.PENDING
        )

        // Upload result (70-90%)
        reportStageProgress(
          progressPercentage = 70,
          message = "Preparing workflow results for upload",
          downloadStage = StageStatus.COMPLETED,
          unzipStage = StageStatus.COMPLETED,
          executeStage = StageStatus.COMPLETED,
          uploadStage = StageStatus.IN_PROGRESS,
          cleanupStage = StageStatus.PENDING
        )
        LOGGER.info("Preparing workflow results for upload")
        val outputZipFile = File(tempDir, "workflow_executed_${System.currentTimeMillis()}.zip")
        
        // Zip the appropriate source based on workflow type
        if (isKnwfFile) {
          LOGGER.info("Creating ZIP with .knwf file for upload")
          zipSingleFile(workflowSource, outputZipFile)
        } else {
          LOGGER.info("Creating ZIP with workflow directory for upload")
          zipWorkflow(workflowDir, outputZipFile)
        }

        reportStageProgress(
          progressPercentage = 80,
          message = "Uploading results to: $outputLocation",
          downloadStage = StageStatus.COMPLETED,
          unzipStage = StageStatus.COMPLETED,
          executeStage = StageStatus.COMPLETED,
          uploadStage = StageStatus.IN_PROGRESS,
          cleanupStage = StageStatus.PENDING
        )
        LOGGER.info("Uploading workflow to: $outputLocation")
        uploadWorkflow(outputZipFile, outputLocation)

        reportStageProgress(
          progressPercentage = 90,
          message = "Results uploaded successfully",
          downloadStage = StageStatus.COMPLETED,
          unzipStage = StageStatus.COMPLETED,
          executeStage = StageStatus.COMPLETED,
          uploadStage = StageStatus.COMPLETED,
          cleanupStage = StageStatus.PENDING
        )

        // Cleanup is mandatory (90-100%)
        reportStageProgress(
          progressPercentage = 90,
          message = "Cleaning up temporary files",
          downloadStage = StageStatus.COMPLETED,
          unzipStage = StageStatus.COMPLETED,
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
          unzipStage = StageStatus.COMPLETED,
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
    // Extract filename from location to preserve extension
    return when {
      location.contains("/") -> location.substringAfterLast("/")
      location.contains("\\") -> location.substringAfterLast("\\")
      else -> "workflow.knwf"
    }.let { filename ->
      // If filename has no extension, add .knwf or .zip as appropriate
      if (!filename.contains(".")) {
        "$filename.knwf"
      } else {
        filename
      }
    }
  }

  private fun downloadWorkflow(location: String, destination: File) {
    try {
      when {
        location.startsWith("s3://") -> downloadFromS3(location, destination)
        location.startsWith("http://") || location.startsWith("https://") -> downloadFromHttp(location, destination)
        location.startsWith("/") || location.contains(":\\") -> {
          // Local file path or directory
          val source = File(location)
          if (!source.exists()) {
            throw Exception("Workflow file or directory not found: $location")
          }

          when {
            source.isDirectory -> {
              // Directory: copy contents directly to workflow directory
              LOGGER.info("Copying workflow directory from: $location")
              source.copyRecursively(destination, overwrite = true)
              LOGGER.info("Copied workflow directory: $location")
            }
            source.isFile && (location.endsWith(".zip") || location.endsWith(".knwf")) -> {
              // ZIP or KNWF file: copy to temp location (no extraction for .knwf files)
              LOGGER.info("Copying workflow file from: $location")
              source.copyTo(destination, overwrite = true)
              LOGGER.info("Copied workflow file: $location")
            }
            else -> throw Exception("Unsupported workflow format. Use .knwf, .zip files or a directory: $location")
          }
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

  private fun unzipWorkflow(zipFile: File, destination: File) {
    try {
      ZipInputStream(zipFile.inputStream()).use { zipInput ->
        var entry = zipInput.nextEntry
        while (entry != null) {
          val file = File(destination, entry.name)

          if (entry.isDirectory) {
            file.mkdirs()
          } else {
            file.parentFile?.mkdirs()
            file.outputStream().use { output ->
              zipInput.copyTo(output)
            }
          }

          entry = zipInput.nextEntry
        }
      }
      LOGGER.info("Unzipped workflow to: ${destination.absolutePath}")
    } catch (e: Exception) {
      throw NodeRuntimeException(
        workflowId = "",
        nodeId = "",
        message = "Failed to unzip workflow: ${e.message}"
      )
    }
  }

  private fun zipWorkflow(sourceDir: File, destination: File) {
    try {
      if (!sourceDir.exists() || !sourceDir.isDirectory) {
        throw Exception("Source directory does not exist or is not a directory: ${sourceDir.absolutePath}")
      }

      val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
      LOGGER.info("Zipping ${files.size} files from: ${sourceDir.absolutePath}")

      ZipOutputStream(destination.outputStream()).use { zipOutput ->
        files.forEach { file ->
          try {
            // Use forward slashes for ZIP entries (ZIP standard)
            val relativePath = file.relativeTo(sourceDir).path.replace("\\", "/")
            val entry = ZipEntry(relativePath)
            entry.size = file.length()
            entry.compressedSize = -1
            zipOutput.putNextEntry(entry)

            file.inputStream().buffered().use { input ->
              input.copyTo(zipOutput)
            }
            zipOutput.closeEntry()
            LOGGER.debug("Zipped file: $relativePath (${file.length()} bytes)")
          } catch (e: Exception) {
            LOGGER.error("Error zipping file ${file.absolutePath}: ${e.message}")
            throw e
          }
        }
      }

      LOGGER.info("Zipped workflow to: ${destination.absolutePath} (size: ${destination.length()} bytes)")
    } catch (e: Exception) {
      throw NodeRuntimeException(
        workflowId = "",
        nodeId = "",
        message = "Failed to zip workflow: ${e.message}"
      )
    }
  }

  private fun zipSingleFile(sourceFile: File, destination: File) {
    try {
      if (!sourceFile.exists() || !sourceFile.isFile) {
        throw Exception("Source file does not exist or is not a file: ${sourceFile.absolutePath}")
      }

      LOGGER.info("Zipping single file: ${sourceFile.absolutePath} (${sourceFile.length()} bytes)")

      ZipOutputStream(destination.outputStream()).use { zipOutput ->
        val entry = ZipEntry(sourceFile.name)
        entry.size = sourceFile.length()
        entry.compressedSize = -1
        zipOutput.putNextEntry(entry)

        sourceFile.inputStream().buffered().use { input ->
          input.copyTo(zipOutput)
        }
        zipOutput.closeEntry()
      }

      LOGGER.info("Zipped single file to: ${destination.absolutePath} (size: ${destination.length()} bytes)")
    } catch (e: Exception) {
      throw NodeRuntimeException(
        workflowId = "",
        nodeId = "",
        message = "Failed to zip file: ${e.message}"
      )
    }
  }

  private fun uploadWorkflow(sourceFile: File, location: String) {
    try {
      when {
        location.startsWith("s3://") -> uploadToS3(sourceFile, location)
        location.startsWith("/") || location.contains(":\\") -> {
          // Local file path
          val destDir = File(location)
          destDir.mkdirs()
          val destFile = File(destDir, sourceFile.name)
          sourceFile.copyTo(destFile, overwrite = true)
          LOGGER.info("Uploaded workflow to local path: ${destFile.absolutePath}")
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

  private fun uploadToS3(sourceFile: File, s3Path: String) {
    // TODO: Implement S3 upload using AWS SDK
    // For now, provide a placeholder
    LOGGER.warn("S3 upload not yet implemented for path: $s3Path")
    throw NotImplementedError("S3 upload support coming in future version")
  }
}
