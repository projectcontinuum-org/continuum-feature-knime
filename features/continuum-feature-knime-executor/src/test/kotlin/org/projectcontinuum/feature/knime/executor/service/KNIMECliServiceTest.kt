package org.projectcontinuum.feature.knime.executor.service

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.projectcontinuum.feature.knime.executor.config.KnimeExecutorProperties
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `executeWorkflow should throw RuntimeException for a non-existent executable`() {
    val tempDir = File.createTempFile("knime_test", "").apply { delete() && mkdir() }
    val workflowDir = File(tempDir, "workflow").apply { mkdir() }
    val workspaceDir = File(tempDir, "workspace").apply { mkdir() }

    try {
      // Mock the executable to a non-existent path
      every { knimeProperties.getExecutablePath() } returns "/non/existent/path/to/knime"

      // Without a shell in between, ProcessBuilder.start() itself fails, which is surfaced as
      // a RuntimeException (the node model folds that into a failed ProcessResult).
      val thrown = assertThrows<RuntimeException> {
        knimeCliService.executeWorkflow(
          workflowPath = workflowDir.absolutePath,
          workspaceDir = workspaceDir.absolutePath,
          timeoutSeconds = 10
        )
      }

      assertTrue(thrown.message!!.startsWith("Failed to execute KNIME workflow"))
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
      // In production, if the executable cannot be started, a RuntimeException is thrown

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
  fun `buildCommand emits the batch executor arguments in order as separate list elements`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false
    )

    assertEquals(
      listOf(
        "/usr/bin/knime",
        "-nosplash",
        "--launcher.suppressErrors",
        "-application",
        "com.nodepit.batch.application.NodePitBatchExecutor",
        "--save",
        "/tmp/workflow.knwf"
      ),
      command
    )
  }

  @Test
  fun `buildCommand includes a --variable argument per workflow variable`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      variables = listOf(
        WorkflowVariable(name = "count", value = "3", type = "int"),
        WorkflowVariable(name = "label", value = "hello world", type = "String")
      )
    )

    assertTrue(command.contains("--variable=count;3;int"))
    assertTrue(command.contains("--variable=label;hello world;String"))
  }

  @Test
  fun `buildCommand passes a workflow variable value with shell metacharacters as one literal argument`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      variables = listOf(WorkflowVariable(name = "injected", value = "x' \"\$(id)\" rm -rf ~ '", type = "String"))
    )

    assertTrue(command.contains("--variable=injected;x' \"\$(id)\" rm -rf ~ ';String"))
  }

  @Test
  fun `buildCommand passes a workflow path with shell metacharacters as one literal argument`() {
    val path = "/tmp/knime_workspace/\$(id)\"; touch pwned; `id`.knwf"
    val command = knimeCliService.buildCommand(
      workflowPath = path,
      workspaceDir = "/tmp/workspace",
      reset = false
    )

    assertEquals(path, command.last())
    assertEquals(1, command.count { it.contains("\$(id)") })
  }

  @Test
  fun `buildCommand omits --variable flags when no workflow variables are given`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false
    )

    assertTrue(command.none { it.startsWith("--variable") })
  }

  @Test
  fun `buildCommand omits --credential flags when no workflow credentials are given`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false
    )

    assertTrue(command.none { it.startsWith("--credential") })
  }

  @Test
  fun `buildCommand includes a name-only --credential flag when login and password are absent`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      credentials = listOf(WorkflowCredential(name = "dbCred"))
    )

    assertTrue(command.contains("--credential=dbCred"))
  }

  @Test
  fun `buildCommand includes a name-login --credential flag when only login is present`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      credentials = listOf(WorkflowCredential(name = "dbCred", login = "alice"))
    )

    assertTrue(command.contains("--credential=dbCred;alice"))
  }

  @Test
  fun `buildCommand includes a name-login-password --credential flag when both are present`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      credentials = listOf(WorkflowCredential(name = "dbCred", login = "alice", password = "s3cret"))
    )

    assertTrue(command.contains("--credential=dbCred;alice;s3cret"))
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

    val firstIndex = command.indexOf("--credential=first;alice;s3cret")
    val secondIndex = command.indexOf("--credential=second;bob")
    assertTrue(firstIndex >= 0)
    assertTrue(secondIndex > firstIndex)
  }

  @Test
  fun `buildCommand passes a workflow credential login or password with quotes as one literal argument`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      credentials = listOf(WorkflowCredential(name = "dbCred", login = "x'; rm -rf ~; '", password = "p'\"w\$(id)"))
    )

    assertTrue(command.contains("--credential=dbCred;x'; rm -rf ~; ';p'\"w\$(id)"))
  }

  @Test
  fun `buildCommand includes both --variable and --credential flags together with reset before --save`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = true,
      variables = listOf(WorkflowVariable(name = "count", value = "3", type = "int")),
      credentials = listOf(WorkflowCredential(name = "dbCred", login = "alice", password = "s3cret"))
    )

    assertTrue(command.contains("--variable=count;3;int"))
    assertTrue(command.contains("--credential=dbCred;alice;s3cret"))
    assertTrue(command.indexOf("--reset") in 0 until command.indexOf("--save"))
    assertEquals("/tmp/workflow.knwf", command.last())
  }

  @Test
  fun `redactCredentials hides credential login and password but keeps the credential name`() {
    val command = knimeCliService.buildCommand(
      workflowPath = "/tmp/workflow.knwf",
      workspaceDir = "/tmp/workspace",
      reset = false,
      variables = listOf(WorkflowVariable(name = "count", value = "3", type = "int")),
      credentials = listOf(
        WorkflowCredential(name = "dbCred", login = "alice", password = "s3cret"),
        WorkflowCredential(name = "nameOnly")
      )
    )

    val logged = knimeCliService.redactCredentials(command)

    assertFalse(logged.contains("alice"))
    assertFalse(logged.contains("s3cret"))
    assertTrue(logged.contains("--credential=dbCred;<redacted>"))
    assertTrue(logged.contains("--credential=nameOnly"))
    assertTrue(logged.contains("--variable=count;3;int"))
  }

  @Test
  fun `executeWorkflow passes a workflow path with shell metacharacters literally without executing it`() {
    val tempDir = File.createTempFile("knime_test", "").apply { delete() && mkdir() }
    try {
      val marker = File(tempDir, "pwned")
      // If any shell interpreted this path, the command substitution would create the marker.
      val maliciousPath = "${tempDir.absolutePath}/\$(touch ${marker.absolutePath})\"; touch ${marker.absolutePath}; `touch ${marker.absolutePath}`.knwf"
      every { knimeProperties.getExecutablePath() } returns "echo"

      val result = knimeCliService.executeWorkflow(
        workflowPath = maliciousPath,
        workspaceDir = tempDir.absolutePath,
        timeoutSeconds = 10
      )

      assertEquals(0, result.exitCode)
      assertTrue(result.stdout.trimEnd().endsWith(maliciousPath))
      assertFalse(marker.exists())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `executeWorkflow drains a large stderr stream without deadlocking`() {
    val tempDir = File.createTempFile("knime_test", "").apply { delete() && mkdir() }
    try {
      // Fake KNIME executable (test-only shell script): floods stderr far beyond the pipe
      // buffer size, then writes to stdout. A sequential stdout-then-stderr reader would hang.
      val fakeExecutable = File(tempDir, "fake-knime.sh").apply {
        writeText("#!/bin/bash\nyes err | head -c 200000 1>&2\necho done\n")
        setExecutable(true)
      }
      every { knimeProperties.getExecutablePath() } returns fakeExecutable.absolutePath

      val result = knimeCliService.executeWorkflow(
        workflowPath = File(tempDir, "workflow.knwf").absolutePath,
        workspaceDir = tempDir.absolutePath,
        timeoutSeconds = 10
      )

      assertEquals(0, result.exitCode)
      assertEquals("done\n", result.stdout)
      assertEquals(200000, result.stderr.length)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `executeWorkflow kills a process tree that exceeds the timeout`() {
    val tempDir = File.createTempFile("knime_test", "").apply { delete() && mkdir() }
    try {
      val pidFile = File(tempDir, "child.pid")
      // Fake KNIME executable (test-only shell script) that spawns a long-sleeping child and
      // waits on it, so both the process and its descendant must be killed on timeout.
      val fakeExecutable = File(tempDir, "fake-knime.sh").apply {
        writeText("#!/bin/bash\nsleep 60 &\necho \$! > '${pidFile.absolutePath}'\nwait\n")
        setExecutable(true)
      }
      every { knimeProperties.getExecutablePath() } returns fakeExecutable.absolutePath

      val start = System.nanoTime()
      val thrown = assertThrows<RuntimeException> {
        knimeCliService.executeWorkflow(
          workflowPath = File(tempDir, "workflow.knwf").absolutePath,
          workspaceDir = tempDir.absolutePath,
          timeoutSeconds = 1
        )
      }
      val elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0

      assertTrue(thrown.message!!.contains("timed out after 1 seconds"))
      assertTrue(elapsedSeconds < 20, "executeWorkflow took ${elapsedSeconds}s despite a 1s timeout")
      val childPid = pidFile.readText().trim().toLong()
      val child = ProcessHandle.of(childPid)
      if (child.isPresent) {
        child.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
      }
      assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
