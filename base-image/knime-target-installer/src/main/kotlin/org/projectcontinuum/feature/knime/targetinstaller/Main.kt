package org.projectcontinuum.feature.knime.targetinstaller

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "Usage: install-knime-target [--dry-run] [--knime-home <dir>] <file.target>..."

internal data class Options(
  val targets: List<File>,
  val knimeHome: File,
  val dryRun: Boolean
) {
  companion object {
    fun parse(args: Array<String>, env: Map<String, String> = System.getenv()): Options {
      var dryRun = false
      var knimeHome = env["KNIME_HOME"]
      val targets = mutableListOf<File>()

      val remaining = args.iterator()
      while (remaining.hasNext()) {
        when (val arg = remaining.next()) {
          "--dry-run" -> dryRun = true
          "--knime-home" -> {
            require(remaining.hasNext()) { "--knime-home needs a directory" }
            knimeHome = remaining.next()
          }
          else -> {
            require(!arg.startsWith("-")) { "Unknown option: $arg" }
            targets += File(arg)
          }
        }
      }

      require(targets.isNotEmpty()) { "No .target file given" }
      requireNotNull(knimeHome) { "KNIME home not set — pass --knime-home or set KNIME_HOME" }
      return Options(targets, File(knimeHome), dryRun)
    }
  }
}

fun main(args: Array<String>) {
  val options = try {
    Options.parse(args)
  } catch (e: IllegalArgumentException) {
    System.err.println(e.message)
    System.err.println(USAGE)
    exitProcess(2)
  }

  try {
    install(options)
  } catch (e: Exception) {
    System.err.println("ERROR: ${e.message}")
    exitProcess(1)
  }
}

private fun install(options: Options) {
  options.targets.forEach { require(it.isFile) { "Target file not found: $it" } }
  if (!options.dryRun) {
    val launcher = File(options.knimeHome, "knime")
    require(launcher.canExecute()) { "KNIME launcher not found or not executable: $launcher" }
  }

  val director = P2Director(options.knimeHome, options.dryRun)
  for (file in options.targets) {
    val target = TargetDefinitionParser.parse(file)
    println("==> Installing target '${target.name}' from $file")

    // Installed roots requested without a pinned version are skipped: re-requesting them would re-resolve
    // them against the target's sites, which may carry other versions than the base image ships.
    val installed = director.installedRoots()
    val (alreadyInstalled, pending) = target.units.partition { it.version == null && it.id in installed }
    alreadyInstalled.forEach { println("    already installed: ${it.id}") }
    if (pending.isEmpty()) {
      println("==> '${target.name}': nothing to install")
      continue
    }

    // All repositories in one director run, so units can depend on IUs from another location's site.
    director.install(target.repositories, pending)

    if (!options.dryRun) {
      val installedAfter = director.installedRoots()
      val missing = pending.map { it.id }.filterNot { it in installedAfter }
      check(missing.isEmpty()) { "'${target.name}': not installed after p2 director run: ${missing.joinToString()}" }
    }
    println("==> '${target.name}': installed ${pending.size} unit(s)")
  }
}
