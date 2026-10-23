package org.projectcontinuum.feature.knime.targetinstaller

import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** A p2 installable unit. [version] `null` means "latest available" (PDE writes this as `0.0.0`). */
data class InstallableUnit(
  val id: String,
  val version: String? = null
) {
  /** p2 director `-installIU` syntax. */
  fun toDirectorArg(): String = if (version == null) id else "$id/$version"
}

data class TargetLocation(
  val repositories: List<String>,
  val units: List<InstallableUnit>
)

data class TargetDefinition(
  val name: String,
  val locations: List<TargetLocation>
) {
  val repositories: List<String> get() = locations.flatMap { it.repositories }.distinct()
  val units: List<InstallableUnit> get() = locations.flatMap { it.units }.distinct()
}

/**
 * Parses the subset of the PDE target definition format (`.target`) that maps onto the p2 director:
 * `InstallableUnit` locations with their `<unit>`s and `<repository>` sites. Other location types
 * (Directory, Profile, Feature, Maven) can't be installed from an update site and are rejected.
 *
 * Repository locations may reference environment variables with PDE's `${env_var:NAME}` syntax.
 * Location attributes such as `includeMode`, `includeSource` and `includeAllPlatforms` only affect
 * PDE target resolution and are ignored.
 */
object TargetDefinitionParser {
  private const val INSTALLABLE_UNIT_LOCATION = "InstallableUnit"
  private const val LATEST_VERSION = "0.0.0"
  private val ENV_VAR = Regex("""\$\{env_var:([^}]+)}""")
  private val ANY_VAR = Regex("""\$\{[^}]*}""")

  fun parse(file: File, env: Map<String, String> = System.getenv()): TargetDefinition {
    val factory = DocumentBuilderFactory.newInstance().apply {
      // .target files never need a DTD — refuse them to rule out XXE
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }
    val root = factory.newDocumentBuilder().parse(file).documentElement
    require(root.tagName == "target") { "$file: root element must be <target>, found <${root.tagName}>" }

    val locations = root.children("locations")
      .flatMap { it.children("location") }
      .mapIndexed { index, location -> parseLocation(location, "$file: location #${index + 1}", env) }
    require(locations.isNotEmpty()) { "$file: no <location> entries found" }

    return TargetDefinition(
      name = root.getAttribute("name").ifBlank { file.nameWithoutExtension },
      locations = locations
    )
  }

  private fun parseLocation(location: Element, context: String, env: Map<String, String>): TargetLocation {
    val type = location.getAttribute("type")
    require(type == INSTALLABLE_UNIT_LOCATION) {
      "$context: unsupported location type '$type' (only '$INSTALLABLE_UNIT_LOCATION' is supported)"
    }

    val units = location.children("unit").map { unit ->
      val id = unit.getAttribute("id").trim()
      require(id.isNotEmpty()) { "$context: <unit> without id" }
      val version = unit.getAttribute("version").trim().takeUnless { it.isEmpty() || it == LATEST_VERSION }
      InstallableUnit(id, version)
    }
    val repositories = location.children("repository").map { repository ->
      val url = repository.getAttribute("location").trim()
      require(url.isNotEmpty()) { "$context: <repository> without location" }
      resolveVariables(url, context, env)
    }
    require(units.isNotEmpty()) { "$context: no <unit> entries" }
    require(repositories.isNotEmpty()) { "$context: no <repository> entries" }

    return TargetLocation(repositories, units)
  }

  private fun resolveVariables(value: String, context: String, env: Map<String, String>): String {
    val resolved = ENV_VAR.replace(value) { match ->
      val name = match.groupValues[1]
      env[name] ?: throw IllegalArgumentException("$context: environment variable '$name' used in '$value' is not set")
    }
    ANY_VAR.find(resolved)?.let {
      throw IllegalArgumentException("$context: unsupported variable '${it.value}' in '$value' (only \${env_var:NAME} is supported)")
    }
    return resolved
  }

  private fun Element.children(tagName: String): List<Element> =
    (0 until childNodes.length)
      .map { childNodes.item(it) }
      .filterIsInstance<Element>()
      .filter { it.tagName == tagName }
}
