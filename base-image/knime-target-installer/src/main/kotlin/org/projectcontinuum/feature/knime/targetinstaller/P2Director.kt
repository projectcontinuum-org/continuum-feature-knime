package org.projectcontinuum.feature.knime.targetinstaller

import java.io.File

/**
 * Thin wrapper around KNIME's headless p2 director (`knime -application org.eclipse.equinox.p2.director`).
 * The director runs inside KNIME's own launcher, i.e. on KNIME's bundled JRE with its knime.ini settings.
 */
class P2Director(
  knimeHome: File,
  private val dryRun: Boolean = false
) {
  companion object {
    private val INSTALLED_ROOT = Regex("""^([A-Za-z0-9_.\-]+)/\S+$""")

    /** `-listInstalledRoots` prints one `id/version` per line, mixed with launcher/log noise. */
    internal fun parseInstalledRoots(output: String): Set<String> =
      output.lineSequence()
        .mapNotNull { INSTALLED_ROOT.find(it.trim())?.groupValues?.get(1) }
        .toSet()
  }

  private val director = listOf(
    File(knimeHome, "knime").path,
    "-application", "org.eclipse.equinox.p2.director",
    "-consolelog",
    "-nosplash"
  )

  fun installedRoots(): Set<String> =
    if (dryRun) emptySet() else parseInstalledRoots(capture(director + "-listInstalledRoots"))

  fun install(repositories: List<String>, units: List<InstallableUnit>) {
    run(
      director + listOf(
        "-repository", repositories.joinToString(","),
        "-installIU", units.joinToString(",") { it.toDirectorArg() }
      )
    )
  }

  private fun run(command: List<String>) {
    println("+ ${command.joinToString(" ")}")
    if (dryRun) return
    val exitCode = ProcessBuilder(command).inheritIO().start().waitFor()
    check(exitCode == 0) { "p2 director failed with exit code $exitCode" }
  }

  private fun capture(command: List<String>): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "p2 director failed with exit code $exitCode:\n$output" }
    return output
  }
}
