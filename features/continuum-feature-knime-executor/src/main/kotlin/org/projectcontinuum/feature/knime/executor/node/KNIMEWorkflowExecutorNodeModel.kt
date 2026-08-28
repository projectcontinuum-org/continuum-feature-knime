package org.projectcontinuum.feature.knime.executor.node

import org.projectcontinuum.core.commons.annotation.ContinuumNode
import org.projectcontinuum.core.commons.context.ExecutionContext
import org.projectcontinuum.core.commons.exception.CredentialsNotFoundException
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
import org.projectcontinuum.feature.knime.executor.service.ProcessResult
import org.projectcontinuum.feature.knime.executor.service.WorkflowCredential
import org.projectcontinuum.feature.knime.executor.service.WorkflowVariable
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.TEXT_PLAIN_VALUE
import org.springframework.web.util.UriUtils
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.zip.ZipFile
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

    private val WORKFLOW_VARIABLE_TYPES = setOf("String", "int", "double")
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
            "description": "Local path or URL to a KNIME workflow: path/to/workflow.knwf, s3://bucket/workflow.knwf, or any http(s):// URL that returns a valid KNIME workflow archive (the .knwf extension is not required for http(s) URLs)"
          },
          "executionUploadUrl": {
            "type": "string",
            "title": "Execution Result Upload URL",
            "description": "HTTP(S) URL the executed .knwf is POSTed back to (multipart/form-data) once the workflow finishes, success or failure"
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
          },
          "workflowVariables": {
            "type": "array",
            "title": "Workflow Variables",
            "description": "Variables passed to the workflow at runtime via NodePit's --variable flag. Configure matching Workflow Variables in KNIME first (right-click workflow -> Workflow Variables...)",
            "items": {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "title": "Name"
                },
                "value": {
                  "type": "string",
                  "title": "Value"
                },
                "type": {
                  "type": "string",
                  "title": "Type",
                  "enum": ["String", "int", "double"],
                  "default": "String"
                }
              },
              "required": ["name", "value", "type"]
            },
            "default": []
          },
          "workflowCredentials": {
            "type": "array",
            "title": "Workflow Credentials",
            "description": "Login/password credentials injected into the workflow at runtime via NodePit's --credential flag. Configure a matching entry in KNIME first (right-click workflow -> Workflow Credentials...) - the Name below must exactly match the name configured there.",
            "items": {
              "type": "object",
              "properties": {
                "knimeCredentialName": {
                  "type": "string",
                  "title": "KNIME Credential Name",
                  "description": "Must exactly match the name configured in this workflow's own Workflow Credentials dialog in KNIME"
                },
                "credential": {
                  "type": "string",
                  "title": "Credential",
                  "description": "Continuum stored credential providing the login and password to inject"
                }
              },
              "required": ["knimeCredentialName", "credential"]
            },
            "default": []
          }
        },
        "required": ["workflowLocation", "executionUploadUrl"]
      }
    """.trimIndent(),
    object : TypeReference<Map<String, Any>>() {}
  )

  val propertiesUiSchema: Map<String, Any> = objectMapper.readValue(
    """
      {
        "type": "VerticalLayout",
        "elements": [
          {
            "type": "Control",
            "scope": "#/properties/workflowLocation",
            "options": {
              "placeholder": "https://example.com/workflow.knwf or /path/to/workflow.knwf"
            }
          },
          {
            "type": "Control",
            "scope": "#/properties/executionUploadUrl",
            "options": {
              "placeholder": "https://example.com/api/v1/knime-workflows/{workflowId}/executions"
            }
          },
          {
            "type": "Control",
            "scope": "#/properties/timeoutSeconds"
          },
          {
            "type": "Control",
            "scope": "#/properties/resetWorkflow"
          },
          {
            "type": "Control",
            "scope": "#/properties/workflowVariables",
            "options": {
              "showSortButtons": true
            }
          },
          {
            "type": "Control",
            "scope": "#/properties/workflowCredentials",
            "options": {
              "showSortButtons": true,
              "detail": {
                "type": "VerticalLayout",
                "elements": [
                  { "type": "Control", "scope": "#/properties/knimeCredentialName" },
                  {
                    "type": "Control",
                    "scope": "#/properties/credential",
                    "options": { "format": "credential", "credentialType": "GENERIC" }
                  }
                ]
              }
            }
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
        <path d="m10.445 21.393 11.54 -0.775 0.451 0.775zM7.56 11.113l-5.092 10.28h-0.904Zm10.427 2.652 -6.43 -9.505 0.452 -0.775zm2.57 5.216 0.627 0.896 -10.652 0.707zM4.655 20.976l-1.143 0.09 4.709 -9.488Zm6.173 -14.667 0.476 -0.998 5.984 8.782zM19.1 17.364l0.847 1.015 -8.685 1.413zM6.76 20.532l-1.32 0.224 3.11 -8.162Zm3.406 -12.189 0.472 -1.207 5.558 6.732Zm7.403 7.54 1.13 1.016 -6.378 1.98zm-8.759 4.08 -1.46 0.448 1.46 -6.44zm0.8 -9.539 0.363 -1.48 4.868 4.477zm-0.348 9.402v-7.851l0.244 -1.085 6.864 3.926 0.834 0.758L10.34 19.5zM12.01 1.694 0 22.306h24z" fill="#000000" stroke-width="1"></path>
      </svg>
    """.trimIndent(),
    inputs = inputPorts,
    outputs = outputPorts,
    properties = mapOf(
      "workflowLocation" to "",
      "executionUploadUrl" to "",
      "timeoutSeconds" to 300,
      "resetWorkflow" to false,
      "workflowVariables" to emptyList<Map<String, Any>>(),
      "workflowCredentials" to emptyList<Map<String, Any>>()
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
          isRetriable = false,
          workflowId = "",
          nodeId = "",
          message = "workflowLocation property is required"
        )

      val executionUploadUrl = (properties["executionUploadUrl"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        ?: throw NodeRuntimeException(
          isRetriable = false,
          workflowId = "",
          nodeId = "",
          message = "executionUploadUrl property is required"
        )

      val isHttpLocation = workflowLocation.startsWith("http://") || workflowLocation.startsWith("https://")

      // HTTP(S) endpoints rarely expose a .knwf-suffixed URL in practice (IDs, tokens,
      // presigned/redirect URLs, content-negotiated APIs) — for those we validate the
      // downloaded bytes instead of the URL string. Local paths and s3:// keys still require
      // the extension since those are trusted, filesystem-shaped inputs we control.
      if (!isHttpLocation && !workflowLocation.lowercase().endsWith(".knwf")) {
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
      val workflowVariables = parseWorkflowVariables(properties["workflowVariables"])
      val workflowCredentialRefs = parseWorkflowCredentialRefs(properties["workflowCredentials"])
      val workflowCredentials = workflowCredentialRefs.map { ref ->
        val data = try {
          executionContext.getCredential<Map<String, Any>>(ref.credential)
        } catch (e: CredentialsNotFoundException) {
          throw NodeRuntimeException(
            workflowId = "",
            nodeId = "",
            message = "Could not resolve credential '${ref.credential}' for workflow credential '${ref.knimeCredentialName}'"
          )
        }
        WorkflowCredential(name = ref.knimeCredentialName, login = data["login"]?.toString(), password = data["password"]?.toString())
      }

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
        downloadWorkflow(workflowLocation, workflowSource, executionContext.ownerId)

        if (isHttpLocation) {
          validateKnimeWorkflowArchive(workflowSource, workflowLocation)
        }

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
        val result = try {
          knimeCliService.executeWorkflow(
            workflowPath = executionWorkflowPath,
            workspaceDir = tempDir.absolutePath,
            timeoutSeconds = timeoutSeconds,
            reset = resetWorkflow,
            variables = workflowVariables,
            credentials = workflowCredentials
          )
        } catch (e: Exception) {
          // The KNIME process can throw instead of returning a non-zero exit code (e.g. it times
          // out and is killed, or fails to start). Fold that into a failed ProcessResult so the
          // upload below still runs and the caller sees a FAILED execution instead of nothing.
          LOGGER.error("KNIME workflow process failed to run: ${e.message}", e)
          ProcessResult(exitCode = -1, stdout = "", stderr = e.message ?: "KNIME workflow process failed to run")
        }

        // Always report the outcome (success or failure) back to whoever will serve the
        // executed workflow later — this must happen before the exit-code check below can
        // throw and skip it.
        val executionStatus = if (result.exitCode == 0) "SUCCESS" else "FAILED"
        LOGGER.info("Uploading execution result ($executionStatus) to: $executionUploadUrl")
        try {
          uploadExecutionResult(workflowSource, executionUploadUrl, executionContext.ownerId, executionStatus)
        } catch (e: Exception) {
          throw NodeRuntimeException(
            workflowId = "",
            nodeId = "",
            message = "Failed to upload execution result: ${e.message}"
          )
        }

        if (result.exitCode != 0) {
          LOGGER.error("KNIME workflow execution failed with exit code ${result.exitCode}")
          LOGGER.error("stderr: ${result.stderr}")
          // A non-zero exit from the KNIME batch executor means the workflow itself failed
          // (bad node config, data error, etc.) — retrying would just reproduce the same
          // failure, so this is not retriable.
          throw NodeRuntimeException(
            isRetriable = false,
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

      } catch (e: NodeRuntimeException) {
        // Rethrow as-is so isRetriable (e.g. false for a failed KNIME batch run above) survives
        // instead of being lost to the generic NodeRuntimeException wrapping below.
        LOGGER.error("Error during workflow execution", e)
        if (tempDir.exists()) {
          tempDir.deleteRecursively()
        }
        throw e
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

  /**
   * NodePit's --variable flag uses ';' to separate name/value/type, so none of the three may
   * contain it or the flag would be misparsed on the KNIME side.
   */
  internal fun parseWorkflowVariables(rawVariables: Any?): List<WorkflowVariable> {
    val entries = rawVariables as? List<*> ?: return emptyList()

    return entries.map { entry ->
      val entryMap = entry as? Map<*, *>
        ?: throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Each workflow variable must be an object with name, value, and type"
        )

      val name = (entryMap["name"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        ?: throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Each workflow variable requires a non-blank name"
        )
      val value = entryMap["value"]?.toString() ?: ""
      val type = (entryMap["type"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        ?: throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Workflow variable '$name' requires a type"
        )

      if (type !in WORKFLOW_VARIABLE_TYPES) {
        throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Workflow variable '$name' has invalid type '$type' - must be one of ${WORKFLOW_VARIABLE_TYPES.joinToString(", ")}"
        )
      }
      if (name.contains(";") || value.contains(";") || type.contains(";")) {
        throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Workflow variable '$name' cannot contain ';' (NodePit uses ';' as the field separator)"
        )
      }

      WorkflowVariable(name = name, value = value, type = type)
    }
  }

  internal data class WorkflowCredentialRef(val knimeCredentialName: String, val credential: String)

  /**
   * NodePit's --credential flag uses ';' to separate name/login/password, so the KNIME
   * credential name may not contain it or the flag would be misparsed on the KNIME side.
   */
  internal fun parseWorkflowCredentialRefs(raw: Any?): List<WorkflowCredentialRef> {
    val entries = raw as? List<*> ?: return emptyList()

    val refs = entries.map { entry ->
      val entryMap = entry as? Map<*, *>
        ?: throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Each workflow credential must be an object with knimeCredentialName and credential"
        )

      val knimeCredentialName = (entryMap["knimeCredentialName"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        ?: throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Each workflow credential requires a non-blank knimeCredentialName"
        )
      val credential = (entryMap["credential"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        ?: throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Workflow credential '$knimeCredentialName' requires a non-blank credential"
        )

      if (knimeCredentialName.contains(";")) {
        throw NodeRuntimeException(
          workflowId = "",
          nodeId = "",
          message = "Workflow credential name '$knimeCredentialName' cannot contain ';' (NodePit uses ';' as the field separator)"
        )
      }

      WorkflowCredentialRef(knimeCredentialName = knimeCredentialName, credential = credential)
    }

    val duplicateNames = refs.groupingBy { it.knimeCredentialName }.eachCount().filterValues { it > 1 }.keys
    if (duplicateNames.isNotEmpty()) {
      throw NodeRuntimeException(
        workflowId = "",
        nodeId = "",
        message = "Duplicate workflow credential name(s): ${duplicateNames.joinToString(", ")}"
      )
    }

    return refs
  }

  internal fun determineDestinationName(location: String): String {
    // The KNIME batch executor (NodePitBatchExecutor) resolves the archive by its local file
    // extension, so the downloaded copy must end in .knwf even when the source URL doesn't
    // (HTTP locations are no longer required to be .knwf-suffixed — see execute()).
    val withoutQueryOrFragment = location.substringBefore("?").substringBefore("#")
    val isHttpLocation = withoutQueryOrFragment.startsWith("http://") || withoutQueryOrFragment.startsWith("https://")
    val rawName = when {
      withoutQueryOrFragment.contains("/") -> withoutQueryOrFragment.substringAfterLast("/")
      withoutQueryOrFragment.contains("\\") -> withoutQueryOrFragment.substringAfterLast("\\")
      else -> withoutQueryOrFragment
    }.ifBlank { "workflow" }
    // The workflowLocation URL's file name is percent-encoded by KnimeWorkflowScheduleService
    // (UriUtils.encodePathSegment) since the original file name can contain spaces/special
    // characters. Decode it back here so the downloaded copy — and anything uploaded from it,
    // like the execution result — keeps the original file name instead of literal %-escapes.
    // Local paths and s3:// keys are never percent-encoded, so leave those untouched.
    val decodedName = if (isHttpLocation) {
      runCatching { UriUtils.decode(rawName, StandardCharsets.UTF_8) }.getOrDefault(rawName)
    } else {
      rawName
    }
    return if (decodedName.lowercase().endsWith(".knwf")) decodedName else "$decodedName.knwf"
  }

  /**
   * Confirms downloaded bytes are actually a KNIME workflow archive (a zip containing a
   * `workflow.knime` file) rather than trusting the source URL's shape — the substitute for
   * the `.knwf` suffix check that HTTP(S) locations skip.
   */
  private fun validateKnimeWorkflowArchive(file: File, sourceLocation: String) {
    val isValidKnimeArchive = runCatching {
      ZipFile(file).use { zip ->
        zip.entries().asSequence().any { entry ->
          entry.name.removePrefix("/").substringAfterLast("/") == "workflow.knime"
        }
      }
    }.getOrDefault(false)

    if (!isValidKnimeArchive) {
      throw NodeRuntimeException(
        workflowId = "",
        nodeId = "",
        message = "Downloaded content from $sourceLocation is not a valid KNIME workflow archive " +
          "(expected a workflow.knime entry)"
      )
    }
  }

  private fun downloadWorkflow(location: String, destination: File, ownerId: String?) {
    try {
      when {
        location.startsWith("s3://") -> downloadFromS3(location, destination)
        location.startsWith("http://") || location.startsWith("https://") -> downloadFromHttp(location, destination, ownerId)
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

  internal fun downloadFromHttp(url: String, destination: File, ownerId: String?) {
    try {
      val connection = java.net.URL(url).openConnection()
      if (ownerId != null) {
        connection.setRequestProperty("x-continuum-user-id", ownerId)
      }
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

  /**
   * Streams the executed .knwf as multipart/form-data rather than buffering it in memory —
   * executed workflows can be large. `setChunkedStreamingMode` avoids HttpURLConnection's
   * default behavior of buffering the entire request body when Content-Length isn't known
   * upfront.
   */
  internal fun uploadExecutionResult(sourceFile: File, uploadUrl: String, ownerId: String?, status: String) {
    val boundary = "----ContinuumBoundary${UUID.randomUUID()}"
    val connection = java.net.URL(uploadUrl).openConnection() as java.net.HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setChunkedStreamingMode(8192)
    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    if (ownerId != null) {
      connection.setRequestProperty("x-continuum-user-id", ownerId)
    }

    connection.outputStream.use { output ->
      output.write(
        "--$boundary\r\nContent-Disposition: form-data; name=\"status\"\r\n\r\n$status\r\n".toByteArray()
      )
      output.write(
        ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${sourceFile.name}\"\r\n" +
          "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
      )
      sourceFile.inputStream().use { it.copyTo(output) }
      output.write("\r\n--$boundary--\r\n".toByteArray())
    }

    val responseCode = connection.responseCode
    if (responseCode !in 200..299) {
      throw Exception("Execution result upload failed with HTTP $responseCode: ${connection.responseMessage}")
    }
    LOGGER.info("Uploaded execution result to: $uploadUrl")
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
