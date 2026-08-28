package org.projectcontinuum.feature.knime.executor.node

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.projectcontinuum.core.commons.context.CredentialFetcher
import org.projectcontinuum.core.commons.context.ExecutionContext
import org.projectcontinuum.core.commons.exception.NodeRuntimeException
import org.projectcontinuum.feature.knime.executor.config.KnimeExecutorProperties
import org.projectcontinuum.feature.knime.executor.service.KNIMECliService
import org.projectcontinuum.feature.knime.executor.service.ProcessResult
import org.projectcontinuum.feature.knime.executor.service.WorkflowCredential
import org.projectcontinuum.feature.knime.executor.service.WorkflowVariable
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KNIMEWorkflowExecutorNodeModelTest {

  private lateinit var server: HttpServer
  @Volatile
  private var receivedHeader: String? = null
  @Volatile
  private var receivedUploadHeader: String? = null
  @Volatile
  private var receivedUploadBody: ByteArray? = null
  private lateinit var knimeCliService: KNIMECliService
  private lateinit var nodeModel: KNIMEWorkflowExecutorNodeModel

  @BeforeEach
  fun setUp() {
    receivedHeader = null
    receivedUploadHeader = null
    receivedUploadBody = null
    server = HttpServer.create(InetSocketAddress(0), 0).apply {
      createContext("/workflow.knwf") { exchange ->
        receivedHeader = exchange.requestHeaders.getFirst("x-continuum-user-id")
        val body = "content".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      createContext("/upload") { exchange ->
        receivedUploadHeader = exchange.requestHeaders.getFirst("x-continuum-user-id")
        receivedUploadBody = exchange.requestBody.use { it.readBytes() }
        exchange.sendResponseHeaders(200, -1)
        exchange.responseBody.close()
      }
      createContext("/upload-fail") { exchange ->
        exchange.sendResponseHeaders(500, -1)
        exchange.responseBody.close()
      }
      start()
    }
    knimeCliService = mockk(relaxed = true)
    nodeModel = KNIMEWorkflowExecutorNodeModel(
      objectMapper = ObjectMapper(),
      knimeCliService = knimeCliService,
      knimeProperties = mockk(relaxed = true)
    )
  }

  @AfterEach
  fun tearDown() {
    server.stop(0)
  }

  @Test
  fun `downloadFromHttp sends the owner header when an owner id is present`() {
    val destination = File.createTempFile("knime-download-test", ".knwf")
    try {
      nodeModel.downloadFromHttp("http://localhost:${server.address.port}/workflow.knwf", destination, "alice")

      assertEquals("alice", receivedHeader)
    } finally {
      destination.delete()
    }
  }

  @Test
  fun `downloadFromHttp omits the owner header when no owner id is present`() {
    val destination = File.createTempFile("knime-download-test", ".knwf")
    try {
      nodeModel.downloadFromHttp("http://localhost:${server.address.port}/workflow.knwf", destination, null)

      assertNull(receivedHeader)
    } finally {
      destination.delete()
    }
  }

  @Test
  fun `uploadExecutionResult sends a multipart body with the status field, file part, and owner header`() {
    val sourceFile = File.createTempFile("knime-upload-test", ".knwf")
    try {
      sourceFile.writeBytes("executed-workflow-bytes".toByteArray())

      nodeModel.uploadExecutionResult(
        sourceFile,
        "http://localhost:${server.address.port}/upload",
        "alice",
        "SUCCESS"
      )

      assertEquals("alice", receivedUploadHeader)
      val body = String(receivedUploadBody!!)
      assertTrue(body.contains("Content-Disposition: form-data; name=\"status\""))
      assertTrue(body.contains("SUCCESS"))
      assertTrue(body.contains("Content-Disposition: form-data; name=\"file\"; filename=\"${sourceFile.name}\""))
      assertTrue(body.contains("executed-workflow-bytes"))
    } finally {
      sourceFile.delete()
    }
  }

  @Test
  fun `uploadExecutionResult omits the owner header when no owner id is present`() {
    val sourceFile = File.createTempFile("knime-upload-test", ".knwf")
    try {
      sourceFile.writeBytes("executed-workflow-bytes".toByteArray())

      nodeModel.uploadExecutionResult(
        sourceFile,
        "http://localhost:${server.address.port}/upload",
        null,
        "FAILED"
      )

      assertNull(receivedUploadHeader)
      assertTrue(String(receivedUploadBody!!).contains("FAILED"))
    } finally {
      sourceFile.delete()
    }
  }

  @Test
  fun `uploadExecutionResult throws when the server responds with a non-2xx status`() {
    val sourceFile = File.createTempFile("knime-upload-test", ".knwf")
    try {
      sourceFile.writeBytes("executed-workflow-bytes".toByteArray())

      assertFailsWith<Exception> {
        nodeModel.uploadExecutionResult(
          sourceFile,
          "http://localhost:${server.address.port}/upload-fail",
          "alice",
          "SUCCESS"
        )
      }
    } finally {
      sourceFile.delete()
    }
  }

  @Test
  fun `determineDestinationName decodes a percent-encoded space in an HTTP URL's file name`() {
    val destinationName = nodeModel.determineDestinationName(
      "http://localhost:${server.address.port}/api/v1/knime-workflows/abc/content/My%20Workflow.knwf"
    )

    assertEquals("My Workflow.knwf", destinationName)
  }

  @Test
  fun `determineDestinationName leaves a literal percent sign in a local path untouched`() {
    val destinationName = nodeModel.determineDestinationName("/tmp/100%complete.knwf")

    assertEquals("100%complete.knwf", destinationName)
  }

  @Test
  fun `execute throws when executionUploadUrl is missing`() {
    val sourceWorkflow = File.createTempFile("knime-workflow", ".knwf")
    try {
      sourceWorkflow.writeBytes("dummy".toByteArray())
      val properties = mapOf("workflowLocation" to sourceWorkflow.absolutePath)

      assertFailsWith<NodeRuntimeException> {
        nodeModel.execute(
          properties,
          emptyMap(),
          mockk(relaxed = true),
          mockk(relaxed = true),
          ExecutionContext()
        )
      }
    } finally {
      sourceWorkflow.delete()
    }
  }

  @Test
  fun `execute uploads a FAILED status before throwing when the KNIME process exits non-zero`() {
    every { knimeCliService.executeWorkflow(any(), any(), any(), any(), any(), any()) } returns
      ProcessResult(exitCode = 1, stdout = "", stderr = "boom")

    val sourceWorkflow = File.createTempFile("knime-workflow", ".knwf")
    try {
      sourceWorkflow.writeBytes("dummy".toByteArray())
      val properties = mapOf(
        "workflowLocation" to sourceWorkflow.absolutePath,
        "executionUploadUrl" to "http://localhost:${server.address.port}/upload"
      )

      val thrown = assertFailsWith<NodeRuntimeException> {
        nodeModel.execute(
          properties,
          emptyMap(),
          mockk(relaxed = true),
          mockk(relaxed = true),
          ExecutionContext()
        )
      }

      assertEquals(false, thrown.isRetriable)
      assertTrue(String(receivedUploadBody!!).contains("FAILED"))
    } finally {
      sourceWorkflow.delete()
    }
  }

  @Test
  fun `execute still uploads a FAILED status when the KNIME process throws instead of exiting non-zero`() {
    every { knimeCliService.executeWorkflow(any(), any(), any(), any(), any(), any()) } throws
      RuntimeException("KNIME workflow execution timed out after 300 seconds")

    val sourceWorkflow = File.createTempFile("knime-workflow", ".knwf")
    try {
      sourceWorkflow.writeBytes("dummy".toByteArray())
      val properties = mapOf(
        "workflowLocation" to sourceWorkflow.absolutePath,
        "executionUploadUrl" to "http://localhost:${server.address.port}/upload"
      )

      val thrown = assertFailsWith<NodeRuntimeException> {
        nodeModel.execute(
          properties,
          emptyMap(),
          mockk(relaxed = true),
          mockk(relaxed = true),
          ExecutionContext()
        )
      }

      assertEquals(false, thrown.isRetriable)
      assertTrue(String(receivedUploadBody!!).contains("FAILED"))
    } finally {
      sourceWorkflow.delete()
    }
  }

  @Test
  fun `execute forwards parsed workflow variables to knimeCliService`() {
    val variablesSlot = slot<List<WorkflowVariable>>()
    every {
      knimeCliService.executeWorkflow(any(), any(), any(), any(), capture(variablesSlot), any())
    } returns ProcessResult(exitCode = 0, stdout = "", stderr = "")

    val sourceWorkflow = File.createTempFile("knime-workflow", ".knwf")
    try {
      sourceWorkflow.writeBytes("dummy".toByteArray())
      val properties = mapOf(
        "workflowLocation" to sourceWorkflow.absolutePath,
        "executionUploadUrl" to "http://localhost:${server.address.port}/upload",
        "workflowVariables" to listOf(
          mapOf("name" to "myVar", "value" to "42", "type" to "int")
        )
      )

      nodeModel.execute(
        properties,
        emptyMap(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        ExecutionContext()
      )

      assertEquals(listOf(WorkflowVariable("myVar", "42", "int")), variablesSlot.captured)
    } finally {
      sourceWorkflow.delete()
    }
  }

  @Test
  fun `parseWorkflowVariables returns an empty list when the property is absent`() {
    assertEquals(emptyList(), nodeModel.parseWorkflowVariables(null))
  }

  @Test
  fun `parseWorkflowVariables maps valid entries into WorkflowVariable`() {
    val result = nodeModel.parseWorkflowVariables(
      listOf(
        mapOf("name" to "count", "value" to "3", "type" to "int"),
        mapOf("name" to "label", "value" to "hello", "type" to "String")
      )
    )

    assertEquals(
      listOf(
        WorkflowVariable("count", "3", "int"),
        WorkflowVariable("label", "hello", "String")
      ),
      result
    )
  }

  @Test
  fun `parseWorkflowVariables throws when type is not String, int, or double`() {
    assertFailsWith<NodeRuntimeException> {
      nodeModel.parseWorkflowVariables(listOf(mapOf("name" to "x", "value" to "1", "type" to "boolean")))
    }
  }

  @Test
  fun `parseWorkflowVariables throws when name is blank`() {
    assertFailsWith<NodeRuntimeException> {
      nodeModel.parseWorkflowVariables(listOf(mapOf("name" to "  ", "value" to "1", "type" to "String")))
    }
  }

  @Test
  fun `parseWorkflowVariables throws when a value contains a semicolon`() {
    assertFailsWith<NodeRuntimeException> {
      nodeModel.parseWorkflowVariables(listOf(mapOf("name" to "x", "value" to "a;b", "type" to "String")))
    }
  }

  @Test
  fun `parseWorkflowCredentialRefs returns an empty list when the property is absent`() {
    assertEquals(emptyList(), nodeModel.parseWorkflowCredentialRefs(null))
  }

  @Test
  fun `parseWorkflowCredentialRefs maps valid entries into WorkflowCredentialRef`() {
    val result = nodeModel.parseWorkflowCredentialRefs(
      listOf(
        mapOf("knimeCredentialName" to "dbCred", "credential" to "my-db-credential"),
        mapOf("knimeCredentialName" to "apiCred", "credential" to "my-api-credential")
      )
    )

    assertEquals(
      listOf(
        KNIMEWorkflowExecutorNodeModel.WorkflowCredentialRef("dbCred", "my-db-credential"),
        KNIMEWorkflowExecutorNodeModel.WorkflowCredentialRef("apiCred", "my-api-credential")
      ),
      result
    )
  }

  @Test
  fun `parseWorkflowCredentialRefs throws when an entry is not an object`() {
    assertFailsWith<NodeRuntimeException> {
      nodeModel.parseWorkflowCredentialRefs(listOf("not-an-object"))
    }
  }

  @Test
  fun `parseWorkflowCredentialRefs throws when knimeCredentialName is blank`() {
    assertFailsWith<NodeRuntimeException> {
      nodeModel.parseWorkflowCredentialRefs(listOf(mapOf("knimeCredentialName" to "  ", "credential" to "my-db-credential")))
    }
  }

  @Test
  fun `parseWorkflowCredentialRefs throws when credential is missing`() {
    assertFailsWith<NodeRuntimeException> {
      nodeModel.parseWorkflowCredentialRefs(listOf(mapOf("knimeCredentialName" to "dbCred")))
    }
  }

  @Test
  fun `parseWorkflowCredentialRefs throws when knimeCredentialName contains a semicolon`() {
    assertFailsWith<NodeRuntimeException> {
      nodeModel.parseWorkflowCredentialRefs(listOf(mapOf("knimeCredentialName" to "db;Cred", "credential" to "my-db-credential")))
    }
  }

  @Test
  fun `parseWorkflowCredentialRefs throws on duplicate knimeCredentialName values`() {
    assertFailsWith<NodeRuntimeException> {
      nodeModel.parseWorkflowCredentialRefs(
        listOf(
          mapOf("knimeCredentialName" to "dbCred", "credential" to "cred-a"),
          mapOf("knimeCredentialName" to "dbCred", "credential" to "cred-b")
        )
      )
    }
  }

  @Test
  fun `execute resolves workflow credentials and forwards them to knimeCliService`() {
    val credentialsSlot = slot<List<WorkflowCredential>>()
    every {
      knimeCliService.executeWorkflow(any(), any(), any(), any(), any(), capture(credentialsSlot))
    } returns ProcessResult(exitCode = 0, stdout = "", stderr = "")

    val fakeStore = mapOf("my-db-credential" to mapOf("login" to "alice", "password" to "s3cret"))
    val sourceWorkflow = File.createTempFile("knime-workflow", ".knwf")
    try {
      sourceWorkflow.writeBytes("dummy".toByteArray())
      val properties = mapOf(
        "workflowLocation" to sourceWorkflow.absolutePath,
        "executionUploadUrl" to "http://localhost:${server.address.port}/upload",
        "workflowCredentials" to listOf(
          mapOf("knimeCredentialName" to "dbCred", "credential" to "my-db-credential")
        )
      )

      nodeModel.execute(
        properties,
        emptyMap(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        ExecutionContext(credentialFetcher = CredentialFetcher { name -> fakeStore[name] })
      )

      assertEquals(listOf(WorkflowCredential("dbCred", "alice", "s3cret")), credentialsSlot.captured)
    } finally {
      sourceWorkflow.delete()
    }
  }

  @Test
  fun `execute leaves password null when the resolved credential data has only a login`() {
    val credentialsSlot = slot<List<WorkflowCredential>>()
    every {
      knimeCliService.executeWorkflow(any(), any(), any(), any(), any(), capture(credentialsSlot))
    } returns ProcessResult(exitCode = 0, stdout = "", stderr = "")

    val fakeStore = mapOf("my-db-credential" to mapOf("login" to "alice"))
    val sourceWorkflow = File.createTempFile("knime-workflow", ".knwf")
    try {
      sourceWorkflow.writeBytes("dummy".toByteArray())
      val properties = mapOf(
        "workflowLocation" to sourceWorkflow.absolutePath,
        "executionUploadUrl" to "http://localhost:${server.address.port}/upload",
        "workflowCredentials" to listOf(
          mapOf("knimeCredentialName" to "dbCred", "credential" to "my-db-credential")
        )
      )

      nodeModel.execute(
        properties,
        emptyMap(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        ExecutionContext(credentialFetcher = CredentialFetcher { name -> fakeStore[name] })
      )

      assertEquals(listOf(WorkflowCredential("dbCred", "alice", null)), credentialsSlot.captured)
    } finally {
      sourceWorkflow.delete()
    }
  }

  @Test
  fun `execute calls through with an empty credentials list when workflowCredentials is absent`() {
    val credentialsSlot = slot<List<WorkflowCredential>>()
    every {
      knimeCliService.executeWorkflow(any(), any(), any(), any(), any(), capture(credentialsSlot))
    } returns ProcessResult(exitCode = 0, stdout = "", stderr = "")

    val sourceWorkflow = File.createTempFile("knime-workflow", ".knwf")
    try {
      sourceWorkflow.writeBytes("dummy".toByteArray())
      val properties = mapOf(
        "workflowLocation" to sourceWorkflow.absolutePath,
        "executionUploadUrl" to "http://localhost:${server.address.port}/upload"
      )

      nodeModel.execute(
        properties,
        emptyMap(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        ExecutionContext()
      )

      assertEquals(emptyList(), credentialsSlot.captured)
    } finally {
      sourceWorkflow.delete()
    }
  }

  @Test
  fun `execute throws a NodeRuntimeException naming both credential identifiers when the stored credential cannot be resolved`() {
    val sourceWorkflow = File.createTempFile("knime-workflow", ".knwf")
    try {
      sourceWorkflow.writeBytes("dummy".toByteArray())
      val properties = mapOf(
        "workflowLocation" to sourceWorkflow.absolutePath,
        "executionUploadUrl" to "http://localhost:${server.address.port}/upload",
        "workflowCredentials" to listOf(
          mapOf("knimeCredentialName" to "dbCred", "credential" to "missing-credential")
        )
      )

      val thrown = assertFailsWith<NodeRuntimeException> {
        nodeModel.execute(
          properties,
          emptyMap(),
          mockk(relaxed = true),
          mockk(relaxed = true),
          ExecutionContext(credentialFetcher = CredentialFetcher { null })
        )
      }

      assertTrue(thrown.message!!.contains("missing-credential"))
      assertTrue(thrown.message!!.contains("dbCred"))
    } finally {
      sourceWorkflow.delete()
    }
  }
}
