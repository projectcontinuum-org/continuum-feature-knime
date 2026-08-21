package org.projectcontinuum.feature.knime.executor.node

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.projectcontinuum.core.commons.context.ExecutionContext
import org.projectcontinuum.core.commons.exception.NodeRuntimeException
import org.projectcontinuum.feature.knime.executor.config.KnimeExecutorProperties
import org.projectcontinuum.feature.knime.executor.service.KNIMECliService
import org.projectcontinuum.feature.knime.executor.service.ProcessResult
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
    every { knimeCliService.executeWorkflow(any(), any(), any(), any()) } returns
      ProcessResult(exitCode = 1, stdout = "", stderr = "boom")

    val sourceWorkflow = File.createTempFile("knime-workflow", ".knwf")
    try {
      sourceWorkflow.writeBytes("dummy".toByteArray())
      val properties = mapOf(
        "workflowLocation" to sourceWorkflow.absolutePath,
        "executionUploadUrl" to "http://localhost:${server.address.port}/upload"
      )

      assertFailsWith<NodeRuntimeException> {
        nodeModel.execute(
          properties,
          emptyMap(),
          mockk(relaxed = true),
          mockk(relaxed = true),
          ExecutionContext()
        )
      }

      assertTrue(String(receivedUploadBody!!).contains("FAILED"))
    } finally {
      sourceWorkflow.delete()
    }
  }
}
