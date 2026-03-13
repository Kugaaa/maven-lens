# Maven Lens

[中文文档](README_zh.md)

An MCP (Model Context Protocol) Server that enables AI assistants to search and read class information and source code from Maven project dependencies.

## The Problem

AI coding assistants working with Maven projects cannot see class definitions or source code from third-party dependencies — they have to guess how to use APIs. Maven Lens gives AI the ability to:

- Search for classes in dependencies to find the correct fully qualified name and artifact
- View class signature summaries (fields, method signatures) to quickly understand API structure
- View full source code to understand implementation details

## Features

Three MCP Tools, ordered by information granularity from coarse to fine:

| Tool | Purpose | Token Cost |
|------|---------|-----------|
| **find_class** | Search by class name, return matching class list | Low |
| **get_class_info** | Get class signature summary (method bodies removed) | Medium |
| **get_class_source** | Get full source code | High |

## Prerequisites

- **JDK 17+** — required to run the MCP Server
- **Maven 3.6+** — required to build the project
- Target project dependencies already downloaded to local repository (`~/.m2/repository`). Run `mvn dependency:resolve` in your target project if needed

## Build

```bash
mvn clean package -DskipTests
```

Output is a fat jar: `target/maven-lens-1.0.0.jar`

## MCP Configuration

Maven Lens runs as a **STDIO-based** MCP Server — the AI client launches the jar as a subprocess and communicates via stdin/stdout.

### Claude Code

Edit `.mcp.json` in your project directory:

```json
{
  "mcpServers": {
    "maven-lens": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/maven-lens-1.0.0.jar"]
    }
  }
}
```

### Claude Desktop

Edit the config file:

- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "maven-lens": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/maven-lens-1.0.0.jar"]
    }
  }
}
```

### Cursor

Add in Settings > MCP, or edit `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "maven-lens": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/maven-lens-1.0.0.jar"]
    }
  }
}
```

> Replace `/absolute/path/to/maven-lens-1.0.0.jar` with the actual absolute path to the jar file.

If your default JDK is not 17+, specify the full path to JDK 17:

```json
{
  "mcpServers": {
    "maven-lens": {
      "command": "/path/to/jdk17/bin/java",
      "args": ["-jar", "/absolute/path/to/maven-lens-1.0.0.jar"]
    }
  }
}
```

## Tool Reference

### find_class

Search for classes in dependencies by name. Supports three matching modes:
1. **FQN exact match** — input a fully qualified name to locate directly
2. **Simple name exact match** — input a class name like `Transactional` to find all classes with that name
3. **Fuzzy match** — input a partial name, matched by containment

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| pom_path | string | Yes | Absolute path to the project's pom.xml |
| class_name | string | Yes | Class name, supports FQN, simple name, or fuzzy search |

**Response example:**

```json
{
  "matches": [
    {
      "fqn": "org.springframework.transaction.annotation.Transactional",
      "artifact": "org.springframework:spring-tx:6.1.4",
      "type": "annotation",
      "has_sources": true
    }
  ]
}
```

Response fields:
- `fqn` — Fully qualified class name
- `artifact` — Maven coordinates (groupId:artifactId:version)
- `type` — Type: class / interface / enum / annotation / record
- `has_sources` — Whether a sources.jar is available for original source code

### get_class_info

Get a class signature summary. Method bodies are replaced with `{ ... }`, preserving class declarations, fields, method signatures, and annotations. Ideal for quickly understanding API structure with far fewer tokens than full source.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| pom_path | string | Yes | Absolute path to the project's pom.xml |
| fully_qualified_name | string | Yes | Fully qualified class name |

**Response example:**

```json
{
  "source_type": "sources-jar",
  "artifact": "org.springframework:spring-tx:6.1.4",
  "content": "public @interface Transactional {\n    Propagation propagation() default Propagation.REQUIRED;\n    ...\n}"
}
```

### get_class_source

Get full source code, including all method implementations.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| pom_path | string | Yes | Absolute path to the project's pom.xml |
| fully_qualified_name | string | Yes | Fully qualified class name |

**Response example:**

```json
{
  "source_type": "decompiled",
  "artifact": "com.google.guava:guava:33.0.0-jre",
  "content": "// full source code..."
}
```

`source_type` is either `sources-jar` or `decompiled`, indicating the source origin.

## How It Works

### Dependency Resolution

1. On first invocation, resolves the full dependency tree from pom.xml (using Maven Resolver)
2. Supports multi-module projects — automatically walks up to find the root pom and collects all submodules
3. Internal modules are read from `target/classes` directory, no need to package into a jar
4. For transitive dependencies not expanded by Maven Resolver, supplements by parsing local repository pom files
5. Results are cached by pom.xml path and automatically rebuilt when pom.xml is modified

### Class Index

After resolution, all dependency jars and classes directories are scanned to build a two-level index:
- Simple name index — for fast exact and fuzzy matching
- FQN index — for fully qualified name lookups

### Source Retrieval

Priority:
1. **sources.jar** — extracts original `.java` source from local repository, preserving comments and Javadoc
2. **CFR decompilation** — when no sources.jar is available, decompiles `.class` bytecode

## License

Apache License 2.0
