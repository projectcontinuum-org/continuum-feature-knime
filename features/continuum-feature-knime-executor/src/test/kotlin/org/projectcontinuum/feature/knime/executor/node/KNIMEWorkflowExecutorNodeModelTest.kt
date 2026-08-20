package org.projectcontinuum.feature.knime.executor.node

import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.projectcontinuum.feature.knime.executor.config.KnimeExecutorProperties
import org.projectcontinuum.feature.knime.executor.service.KNIMECliService
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KNIMEWorkflowExecutorNodeModelTest {

  private lateinit var server: HttpServer
  @Volatile
  private var receivedHeader: String? = null
  private lateinit var nodeModel: KNIMEWorkflowExecutorNodeModel

  @BeforeEach
  fun setUp() {
    receivedHeader = null
    server = HttpServer.create(InetSocketAddress(0), 0).apply {
      createContext("/workflow.knwf") { exchange ->
        receivedHeader = exchange.requestHeaders.getFirst("x-continuum-user-id")
        val body = "content".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      start()
    }
    nodeModel = KNIMEWorkflowExecutorNodeModel(
      objectMapper = ObjectMapper(),
      knimeCliService = mockk(relaxed = true),
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
}
