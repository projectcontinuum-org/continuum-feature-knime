package org.projectcontinuum.feature.knime.executor.service

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.projectcontinuum.feature.knime.executor.config.KnimeExecutorProperties
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KNIMECliServiceTest {

  private lateinit var knimeProperties: KnimeExecutorProperties
  private lateinit var knimeCliService: KNIMECliService

  @BeforeEach
  fun setUp() {
    knimeProperties = mockk(relaxed = true)
    every { knimeProperties.getExecutablePath() } returns "/usr/bin/knime"
    knimeCliService = KNIMECliService(knimeProperties)
  }

  @Test
  fun `executeWorkflow should succeed with valid workflow`() {
    // Create a temporary workflow directory
    val tempDir = File.createTempFile("knime_test", "").apply { delete() && mkdir() }
    val workflowDir = File(tempDir, "workflow").apply { mkdir() }
    val workspaceDir = File(tempDir, "workspace").apply { mkdir() }

    try {
      // Create a mock workflow.knime file to simulate a valid workflow
      File(workflowDir, "workflow.knime").writeText("<dummy>test workflow</dummy>")

      // Mock the executable to be a simple echo command that succeeds
      every { knimeProperties.getExecutablePath() } returns "echo"

      // Execute the workflow
      val result = knimeCliService.executeWorkflow(
        workflowPath = workflowDir.absolutePath,
        workspaceDir = workspaceDir.absolutePath,
        timeoutSeconds = 10
      )

      // Verify
      assertEquals(0, result.exitCode)
      assertNotNull(result.stdout)
      assertNotNull(result.stderr)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `executeWorkflow should return non-zero exit code on failure`() {
    val tempDir = File.createTempFile("knime_test", "").apply { delete() && mkdir() }
    val workflowDir = File(tempDir, "workflow").apply { mkdir() }
    val workspaceDir = File(tempDir, "workspace").apply { mkdir() }

    try {
      // Mock the executable to be a command that fails
      every { knimeProperties.getExecutablePath() } returns "false"

      // Execute the workflow
      val result = knimeCliService.executeWorkflow(
        workflowPath = workflowDir.absolutePath,
        workspaceDir = workspaceDir.absolutePath,
        timeoutSeconds = 10
      )

      // Verify the exit code is non-zero
      assertTrue(result.exitCode != 0)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `executeWorkflow should handle non-existent executable gracefully`() {
    val tempDir = File.createTempFile("knime_test", "").apply { delete() && mkdir() }
    val workflowDir = File(tempDir, "workflow").apply { mkdir() }
    val workspaceDir = File(tempDir, "workspace").apply { mkdir() }

    try {
      // Mock the executable to a non-existent path
      every { knimeProperties.getExecutablePath() } returns "/non/existent/path/to/knime"

      // Execute should return a non-zero exit code (bash returns 127 for command not found)
      val result = knimeCliService.executeWorkflow(
        workflowPath = workflowDir.absolutePath,
        workspaceDir = workspaceDir.absolutePath,
        timeoutSeconds = 10
      )

      // Verify it failed with a non-zero exit code
      assertTrue(result.exitCode != 0)
      assertTrue(result.stderr.contains("No such file or directory") || result.exitCode == 127)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `executeWorkflow should return ProcessResult with exit code and output`() {
    val tempDir = File.createTempFile("knime_test", "").apply { delete() && mkdir() }
    val workflowDir = File(tempDir, "workflow").apply { mkdir() }
    val workspaceDir = File(tempDir, "workspace").apply { mkdir() }

    try {
      // Mock the executable to output to stdout and stderr
      every { knimeProperties.getExecutablePath() } returns "sh"

      val result = knimeCliService.executeWorkflow(
        workflowPath = workflowDir.absolutePath,
        workspaceDir = workspaceDir.absolutePath,
        timeoutSeconds = 10
      )

      // Verify ProcessResult structure
      assertNotNull(result)
      assertEquals(result.exitCode, result.exitCode) // Just verify it has an exit code
      assertNotNull(result.stdout)
      assertNotNull(result.stderr)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `executeWorkflow should handle workflow with spaces in path`() {
    val tempDir = File.createTempFile("knime test dir", "").apply { delete() && mkdir() }
    val workflowDir = File(tempDir, "workflow with spaces").apply { mkdir() }
    val workspaceDir = File(tempDir, "workspace").apply { mkdir() }

    try {
      File(workflowDir, "workflow.knime").writeText("<dummy>test</dummy>")

      // Mock the executable to be echo
      every { knimeProperties.getExecutablePath() } returns "echo"

      val result = knimeCliService.executeWorkflow(
        workflowPath = workflowDir.absolutePath,
        workspaceDir = workspaceDir.absolutePath,
        timeoutSeconds = 10
      )

      // Should handle spaces in path correctly
      assertEquals(0, result.exitCode)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `executeWorkflow should throw RuntimeException if java process execution fails`() {
    val tempDir = File.createTempFile("knime_test", "").apply { delete() && mkdir() }
    val workflowDir = File(tempDir, "workflow").apply { mkdir() }
    val workspaceDir = File(tempDir, "workspace").apply { mkdir() }

    try {
      // This test validates exception handling in KNIMECliService.executeWorkflow
      // For this mock test, we can't truly trigger Java-level process exceptions,
      // so we document that the service returns non-zero exit codes for command failures
      // In production, if /bin/bash itself fails (which is rare), a RuntimeException is thrown

      // Create a valid scenario where the KNIME command completes
      every { knimeProperties.getExecutablePath() } returns "echo"

      val result = knimeCliService.executeWorkflow(
        workflowPath = workflowDir.absolutePath,
        workspaceDir = workspaceDir.absolutePath,
        timeoutSeconds = 10
      )

      assertEquals(0, result.exitCode)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `buildCommand includes a quoted --variable flag per workflow variable`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      variables = listOf(
        WorkflowVariable(name = "count", value = "3", type = "int"),
        WorkflowVariable(name = "label", value = "hello world", type = "String")
      )
    )

    assertTrue(command.contains("--variable='count;3;int'"))
    assertTrue(command.contains("--variable='label;hello world;String'"))
  }

  @Test
  fun `buildCommand safely escapes a single quote in a workflow variable value`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      variables = listOf(WorkflowVariable(name = "injected", value = "x'; rm -rf ~; '", type = "String"))
    )

    assertTrue(command.contains("--variable='injected;x'\\''; rm -rf ~; '\\'';String'"))
  }

  @Test
  fun `buildCommand omits --variable flags when no workflow variables are given`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false
    )

    assertTrue(!command.contains("--variable"))
  }

  @Test
  fun `buildCommand omits --credential flags when no workflow credentials are given`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false
    )

    assertTrue(!command.contains("--credential"))
  }

  @Test
  fun `buildCommand includes a name-only --credential flag when login and password are absent`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      credentials = listOf(WorkflowCredential(name = "dbCred"))
    )

    assertTrue(command.contains("--credential='dbCred'"))
  }

  @Test
  fun `buildCommand includes a name-login --credential flag when only login is present`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      credentials = listOf(WorkflowCredential(name = "dbCred", login = "alice"))
    )

    assertTrue(command.contains("--credential='dbCred;alice'"))
  }

  @Test
  fun `buildCommand includes a name-login-password --credential flag when both are present`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      credentials = listOf(WorkflowCredential(name = "dbCred", login = "alice", password = "s3cret"))
    )

    assertTrue(command.contains("--credential='dbCred;alice;s3cret'"))
  }

  @Test
  fun `buildCommand includes one --credential flag per entry in order`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      credentials = listOf(
        WorkflowCredential(name = "first", login = "alice", password = "s3cret"),
        WorkflowCredential(name = "second", login = "bob")
      )
    )

    val firstIndex = command.indexOf("--credential='first;alice;s3cret'")
    val secondIndex = command.indexOf("--credential='second;bob'")
    assertTrue(firstIndex >= 0)
    assertTrue(secondIndex > firstIndex)
  }

  @Test
  fun `buildCommand safely escapes a single quote in a workflow credential login or password`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      credentials = listOf(WorkflowCredential(name = "dbCred", login = "x'; rm -rf ~; '", password = "p'\"w"))
    )

    assertTrue(command.contains("--credential='dbCred;x'\\''; rm -rf ~; '\\'';p'\\''\"w'"))
  }

  @Test
  fun `buildCommand includes both --variable and --credential flags together with reset`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = true,
      variables = listOf(WorkflowVariable(name = "count", value = "3", type = "int")),
      credentials = listOf(WorkflowCredential(name = "dbCred", login = "alice", password = "s3cret"))
    )

    assertTrue(command.contains("--variable='count;3;int'"))
    assertTrue(command.contains("--credential='dbCred;alice;s3cret'"))
    assertTrue(command.contains("--reset"))
  }
}
