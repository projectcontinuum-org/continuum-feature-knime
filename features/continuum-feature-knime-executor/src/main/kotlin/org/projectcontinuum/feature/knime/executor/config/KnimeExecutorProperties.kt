package org.projectcontinuum.feature.knime.executor.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.io.File

@Component
@ConfigurationProperties(prefix = "continuum.feature.knime.executor")
class KnimeExecutorProperties {
  companion object {
    private val LOGGER = LoggerFactory.getLogger(KnimeExecutorProperties::class.java)
    private const val DEFAULT_EXECUTABLE_NAME = "knime"
  }

  var knimeHome: String = ""
  var knimeExecutable: String = DEFAULT_EXECUTABLE_NAME

  /**
   * Constructs and returns the full path to the KNIME executable.
   * Combines knimeHome + knimeExecutable to form the complete path.
   */
  fun getExecutablePath(): String = File(knimeHome, knimeExecutable).absolutePath

  fun validateExecutable() {
    if (knimeHome.isBlank()) {
      val errorMsg = "KNIME home directory is not configured. Set 'continuum.feature.knime.executor.knime-home' in application.yml"
      LOGGER.error(errorMsg)
      throw IllegalStateException(errorMsg)
    }

    val knimeHomeFile = File(knimeHome)
    if (!knimeHomeFile.exists()) {
      val errorMsg = "KNIME home directory not found at: $knimeHome"
      LOGGER.error(errorMsg)
      throw IllegalStateException(errorMsg)
    }

    if (!knimeHomeFile.isDirectory) {
      val errorMsg = "KNIME home path is not a directory: $knimeHome"
      LOGGER.error(errorMsg)
      throw IllegalStateException(errorMsg)
    }

    val executablePath = getExecutablePath()
    val executable = File(executablePath)
    if (!executable.exists()) {
      val errorMsg = "KNIME executable not found at: $executablePath"
      LOGGER.error(errorMsg)
      throw IllegalStateException(errorMsg)
    }

    if (!executable.isFile) {
      val errorMsg = "KNIME executable path is not a file: $executablePath"
      LOGGER.error(errorMsg)
      throw IllegalStateException(errorMsg)
    }

    if (!executable.canExecute()) {
      val errorMsg = "KNIME executable is not executable: $executablePath"
      LOGGER.error(errorMsg)
      throw IllegalStateException(errorMsg)
    }

    LOGGER.info("KNIME executable validated: $executablePath")
  }
}
