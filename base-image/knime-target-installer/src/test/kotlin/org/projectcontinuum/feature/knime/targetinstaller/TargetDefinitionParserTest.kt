package org.projectcontinuum.feature.knime.targetinstaller

import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TargetDefinitionParserTest {

  private fun targetFile(xml: String): File =
    createTempFile(suffix = ".target").apply { writeText(xml.trimIndent()) }.toFile().apply { deleteOnExit() }

  @Test
  fun `parses installable unit locations with repositories and versions`() {
    val file = targetFile(
      """
      <?xml version="1.0" encoding="UTF-8" standalone="no"?>
      <?pde version="3.8"?>
      <target name="Lilly KNIME Target" sequenceNumber="1512743975">
          <locations>
              <location includeAllPlatforms="false" includeConfigurePhase="false" includeMode="planner" includeSource="true" type="InstallableUnit">
                  <unit id="org.knime.features.base.feature.group" version="0.0.0"/>
                  <unit id="org.knime.features.python.feature.group" version="5.12.0.v202604301200"/>
                  <repository location="http://update-site-knime-dev.paas-np.am.lilly.com/45/KNIME"/>
              </location>
              <location includeAllPlatforms="false" includeConfigurePhase="false" includeMode="planner" includeSource="true" type="InstallableUnit">
                  <unit id="org.rdkit.knime.feature.feature.group" version="0.0.0"/>
                  <unit id="org.knime.features.base.feature.group" version="0.0.0"/>
                  <repository location="http://update-site-knime-dev.paas-np.am.lilly.com/45/Community/Trusted"/>
                  <repository location="http://update-site-knime-dev.paas-np.am.lilly.com/45/KNIME"/>
              </location>
          </locations>
          <targetJRE path="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-11"/>
          <launcherArgs><vmArgs>-server</vmArgs></launcherArgs>
      </target>
      """
    )

    val target = TargetDefinitionParser.parse(file, env = emptyMap())

    assertEquals("Lilly KNIME Target", target.name)
    assertEquals(2, target.locations.size)
    assertEquals(
      listOf(
        "http://update-site-knime-dev.paas-np.am.lilly.com/45/KNIME",
        "http://update-site-knime-dev.paas-np.am.lilly.com/45/Community/Trusted"
      ),
      target.repositories
    )
    assertEquals(
      listOf(
        "org.knime.features.base.feature.group",
        "org.knime.features.python.feature.group/5.12.0.v202604301200",
        "org.rdkit.knime.feature.feature.group"
      ),
      target.units.map { it.toDirectorArg() }
    )
  }

  @Test
  fun `resolves env_var variables in repository locations`() {
    val file = targetFile(
      """
      <target name="NodePit Batch">
          <locations>
              <location type="InstallableUnit">
                  <unit id="com.nodepit.batch.feature.feature.group" version="0.0.0"/>
                  <repository location="https://download.nodepit.com/${'$'}{env_var:KNIME_VERSION}"/>
              </location>
          </locations>
      </target>
      """
    )

    val target = TargetDefinitionParser.parse(file, env = mapOf("KNIME_VERSION" to "5.12"))

    assertEquals(listOf("https://download.nodepit.com/5.12"), target.repositories)
  }

  @Test
  fun `fails on unset env_var variable`() {
    val file = targetFile(
      """
      <target name="t"><locations><location type="InstallableUnit">
          <unit id="a.feature.group" version="0.0.0"/>
          <repository location="https://example.com/${'$'}{env_var:KNIME_VERSION}"/>
      </location></locations></target>
      """
    )

    val error = assertFailsWith<IllegalArgumentException> { TargetDefinitionParser.parse(file, env = emptyMap()) }
    assertTrue("KNIME_VERSION" in error.message!!)
  }

  @Test
  fun `rejects unsupported location types`() {
    val file = targetFile(
      """
      <target name="t"><locations>
          <location path="/opt/plugins" type="Directory"/>
      </locations></target>
      """
    )

    val error = assertFailsWith<IllegalArgumentException> { TargetDefinitionParser.parse(file, env = emptyMap()) }
    assertTrue("Directory" in error.message!!)
  }

  @Test
  fun `rejects locations without repository`() {
    val file = targetFile(
      """
      <target name="t"><locations><location type="InstallableUnit">
          <unit id="a.feature.group" version="0.0.0"/>
      </location></locations></target>
      """
    )

    assertFailsWith<IllegalArgumentException> { TargetDefinitionParser.parse(file, env = emptyMap()) }
  }

  @Test
  fun `parses installed roots from director output`() {
    val output = """
      !SESSION 2026-10-23 10:00:00.000 -----------------------------------------------
      eclipse.buildId=unknown
      com.nodepit.batch.feature.feature.group/1.1.0.202604181052
      org.knime.product.desktop/5.12.0.v202604301200
      Operation completed in 1234 ms.
    """.trimIndent()

    assertEquals(
      setOf("com.nodepit.batch.feature.feature.group", "org.knime.product.desktop"),
      P2Director.parseInstalledRoots(output)
    )
  }
}
