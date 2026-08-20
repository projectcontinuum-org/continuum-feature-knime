<div align="center">
  <h1>Continuum Feature KNIME Executor</h1>
  <strong>Execute KNIME workflows in Continuum for <a href="https://github.com/projectcontinuum/Continuum">Project Continuum</a></strong>
</div>

<div align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.1.0-blue?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Spring_Boot-3.4-green?logo=springboot&logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/KNIME-5.0+-orange" alt="KNIME 5.0+">
  <img src="https://img.shields.io/badge/JDK-21-red" alt="JDK 21">
</div>

---

## 🌐 Part of Project Continuum

This is the **KNIME workflow execution feature** for [Project Continuum](https://github.com/projectcontinuum/Continuum) — a distributed, crash-proof workflow execution platform. It enables seamless integration and orchestration of KNIME workflows within Continuum's workflow engine.

---

## 🔥 What Is This

A standalone Gradle feature module containing **1 production-ready node** for executing KNIME workflows. The node automates the complete workflow lifecycle:

1. **Download** KNIME `.knwf` workflow files from remote locations
2. **Execute** workflows using KNIME's batch executor
3. **Capture** execution results with detailed logging
4. **Upload** executed workflows to configured output locations
5. **Cleanup** temporary resources (optional)

Ships as a Spring Boot worker component that auto-registers with Temporal for durable, fault-tolerant execution.

---

## 🧩 Included Nodes

| Category | Node | Description |
|----------|------|-------------|
| **Integration** | KNIME Workflow Executor | Execute KNIME workflows from remote sources with automatic orchestration |

**Key Features:**
- ✅ No input data tables required (standalone execution)
- ✅ Configurable KNIME executable path
- ✅ Timeout protection for long-running workflows
- ✅ Comprehensive progress reporting
- ✅ Detailed error diagnostics with stderr capture
- ✅ Optional automatic cleanup of temporary files
- ✅ Support for local, HTTP/HTTPS, and S3 sources (v2)

---

## 📦 Dependencies

```kotlin
// Jackson for JSON schema/UI schema generation
implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
```

**Runtime Requirements:**
- KNIME 5.0+ installed and in PATH (or configured via `KNIME_HOME`)
- Java 21+ (JVM runtime)
- Temporary disk space for workflow download and execution

**Test Dependencies:**
- JUnit 5 for test execution
- mockk for mocking KNIME properties and services

---

## 🚀 Quick Start

### 1. Configuration

Configure KNIME executable path in `application.yml` or via environment:

```yaml
# application.yml (worker)
continuum:
  feature:
    knime:
      executor:
        executable-path: ${KNIME_HOME:knime}
```

Or set environment variable:
```bash
export KNIME_HOME=/opt/knime/knime
```

### 2. Verify Installation

```bash
# Test KNIME CLI access
knime -nosplash -application org.knime.product.KNIME_BATCH_APPLICATION -version
```

### 3. Create Workflow Configuration

In Continuum workflow designer, add KNIME Workflow Executor node:

```json
{
  "workflowLocation": "/path/to/workflow.knwf",
  "outputLocation": "/path/to/results/",
  "timeoutSeconds": 300,
  "deleteLocalFiles": true
}
```

### 4. Execute Workflow

Trigger node execution — Continuum handles download, execution, upload, and cleanup.

---

## 📚 Documentation

### For Node Users
- **[KNIME Workflow Executor Node Guide](src/main/resources/org/projectcontinuum/feature/knime/executor/node/KNIMEWorkflowExecutorNodeModel.doc.md)** — Complete node reference with configuration, examples, and troubleshooting

### For Feature Developers
- **Architecture Overview** — See module structure below
- **[Unit Tests](src/test/kotlin/org/projectcontinuum/feature/knime/executor/service/)** — KNIMECliService test coverage

---

## 🏗️ Module Structure

```
continuum-feature-knime-executor/
├── src/main/kotlin/org/projectcontinuum/feature/knime/executor/
│   ├── AutoConfigure.kt                    # Spring @Configuration entry point
│   ├── config/
│   │   ├── KnimeExecutorProperties.kt      # @ConfigurationProperties binding
│   │   └── YamlPropertySourceFactory.kt    # YAML property source factory
│   ├── service/
│   │   └── KNIMECliService.kt              # ProcessBuilder wrapper for KNIME CLI
│   └── node/
│       └── KNIMEWorkflowExecutorNodeModel.kt  # @ContinuumNode implementation
│
├── src/main/resources/
│   ├── continuum-feature-knime-executor.yaml  # Feature properties
│   └── org/projectcontinuum/feature/knime/executor/node/
│       └── KNIMEWorkflowExecutorNodeModel.doc.md  # Node documentation
│
├── src/test/kotlin/org/projectcontinuum/feature/knime/executor/
│   └── service/
│       └── KNIMECliServiceTest.kt          # 6 unit tests
│
├── build.gradle.kts                         # Gradle build configuration
└── README.md                                # This file
```

---

## 🔧 Component Details

### AutoConfigure.kt
- **Purpose:** Spring configuration entry point
- **Responsibilities:** 
  - Component scanning for @Service and @Component classes
  - Loading feature properties via @PropertySource
  - Bean initialization

**Key Code:**
```kotlin
@Configuration
@ComponentScan
@PropertySource("classpath:continuum-feature-knime-executor.yaml", 
                factory = YamlPropertySourceFactory::class)
class AutoConfigure
```

### KnimeExecutorProperties.kt
- **Purpose:** Bind KNIME configuration to Java object
- **Property:** `continuum.feature.knime.executor.executable-path`
- **Validation:** Verifies executable exists, is readable, and is executable

**Configuration Schema:**
```yaml
continuum:
  feature:
    knime:
      executor:
        executable-path: /opt/knime/knime  # or ${KNIME_HOME:knime}
```

### KNIMECliService.kt
- **Purpose:** Wrapper around KNIME CLI invocation
- **Responsibilities:**
  - Build KNIME batch executor command
  - Manage process lifecycle with timeout
  - Capture stdout/stderr separately
  - Report exit codes and execution results

**Key Method:**
```kotlin
fun executeWorkflow(
  workflowPath: String,
  workspaceDir: String,
  timeoutSeconds: Int
): ProcessResult
```

### KNIMEWorkflowExecutorNodeModel.kt
- **Purpose:** Continuum node orchestrating complete workflow execution
- **Responsibilities:**
  - Coordinate download → execute → upload pipeline
  - Manage temporary workspace creation/cleanup
  - Report progress in 10% increments
  - Handle errors with detailed diagnostics

**Execution Flow:**
```
0% - Validate
10% - Create workspace
30% - Download workflow (.knwf)
70% - Execute KNIME (batch executor unzips → executes → saves .knwf in place)
90% - Upload
100% - Complete
```

---

## 🧪 Testing

### Unit Tests
- **KNIMECliServiceTest** — 6 test cases covering:
  - Successful workflow execution
  - Failed command handling (non-zero exit codes)
  - Non-existent executable graceful handling
  - ProcessResult data structure validation
  - Path quoting for spaces in file names
  - Exception handling in Java process execution

### Run Tests
```bash
./gradlew :features:continuum-feature-knime-executor:test
```

### Test Coverage
- KNIMECliService: 100% coverage (6 test methods)
- ProcessBuilder integration: Tested with mocked commands
- Error paths: Covered for common failure scenarios

### Integration Testing (Future)
- KNIMEWorkflowExecutorNodeModel orchestration flow
- Download/upload methods with mock locations
- End-to-end workflow execution scenarios

---

## 🚢 Building

### Build Feature Module
```bash
./gradlew :features:continuum-feature-knime-executor:build
```

### Build Worker with Feature
```bash
./gradlew :worker:build
```

### Build Outputs
- **Feature JAR:** `features/continuum-feature-knime-executor/build/libs/continuum-feature-knime-executor-*.jar`
- **Worker JAR:** `worker/build/libs/worker-*.jar`

---

## 📋 Configuration Properties

### Feature Properties (continuum-feature-knime-executor.yaml)

```yaml
continuum:
  feature:
    knime:
      executor:
        # Path to KNIME executable
        # Supports: /absolute/path, ${KNIME_HOME:default}, or 'knime' in PATH
        executable-path: ${KNIME_HOME:knime}
```

### Node Properties (Workflow Configuration)

| Property | Type | Required | Default | Range |
|----------|------|----------|---------|-------|
| workflowLocation | String | ✅ | - | URL or file path |
| outputLocation | String | ✅ | - | URL or file path |
| timeoutSeconds | Integer | ❌ | 300 | 10-3600 |
| workspaceDir | String | ❌ | /tmp | Writable directory |
| deleteLocalFiles | Boolean | ❌ | true | true/false |

---

## 🔌 Integration with Continuum

### Auto-Discovery
The feature is automatically discovered by:
1. Gradle plugin includes feature module
2. Worker component scan finds @ComponentScan in AutoConfigure
3. Temporal worker auto-discovery registers KNIMEWorkflowExecutorNodeModel

### Spring Boot Integration
- Feature properties auto-loaded via @PropertySource
- Services auto-wired via constructor injection
- Jackson ObjectMapper shared from Spring context

### Worker Integration
```kotlin
// worker/build.gradle.kts
dependencies {
  implementation(project(":features:continuum-feature-knime-executor"))
}

// settings.gradle.kts
include(":features:continuum-feature-knime-executor")
```

---

## 🐛 Troubleshooting

### KNIME Not Found
**Error:** Exit code 127 ("command not found")

**Solution:**
1. Install KNIME: https://www.knime.com/download
2. Set KNIME_HOME: `export KNIME_HOME=/opt/knime/knime`
3. Verify executable: `which knime` or `$KNIME_HOME -version`

### Workflow Timeout
**Error:** "KNIME workflow execution timed out"

**Solution:**
1. Increase `timeoutSeconds` property (minimum 10, recommended 300-1800)
2. Check workflow complexity — may need parallel processing
3. Disable cleanup: `deleteLocalFiles: false` for investigation

### Permission Errors
**Error:** "Permission denied" on output directory

**Solution:**
1. Verify write permissions: `ls -ld /output/path/`
2. Fix ownership: `chown -R user:group /output/path/`
3. For network shares: Check mount options and NFS permissions

### Disk Space Issues
**Error:** "No space left on device"

**Solution:**
1. Check available disk: `df -h`
2. Clean temp directories: `rm -rf /tmp/knime_workspace_*`
3. Increase `workspaceDir` to larger partition if available

### KNIME Process Crashes
**Error:** Process exits with code 1, no output

**Solution:**
1. Enable detailed logging: Set `deleteLocalFiles: false` to inspect temp files
2. Check KNIME logs in workspace: `.knime/` directory
3. Verify KNIME installation: `knime -version`
4. Try executing workflow manually for diagnostics

---

## 🚀 Future Enhancements (v2)

### S3 Support
- [ ] AWS SDK integration for S3 download/upload
- [ ] IAM credential handling via ExecutionContext
- [ ] Multi-part upload for large result files
- [ ] S3 path validation and error handling

### Advanced Features
- [ ] Workflow parameter passing to KNIME
- [ ] Result file extraction and table streaming
- [ ] Workflow versioning and rollback support
- [ ] Performance metrics collection

### Monitoring
- [ ] Prometheus metrics for execution duration
- [ ] KNIME resource utilization tracking
- [ ] Workflow success/failure dashboards

---

## 📄 License

Part of Project Continuum. See root LICENSE file.

---

## 🤝 Contributing

Feature improvements and bug reports welcome. See CONTRIBUTING.md in root project.

---

## 📞 Support

- **Node Documentation:** See `KNIMEWorkflowExecutorNodeModel.doc.md`
- **KNIME Documentation:** https://docs.knime.com/
- **Continuum Community:** See main repository

---

## 📝 Changelog

### v1.0.0 (Initial Release)
- ✅ KNIME Workflow Executor node
- ✅ Local file system support
- ✅ HTTP/HTTPS download support
- ✅ Configurable executable path
- ✅ Timeout protection
- ✅ Progress reporting
- ✅ Comprehensive error handling
- ✅ Unit test coverage (6 tests)

### v2.0.0 (Planned)
- 🚧 S3 download/upload support
- 🚧 Workflow parameter passing
- 🚧 Performance metrics
- 🚧 Advanced monitoring
