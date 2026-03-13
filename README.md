# Maven Lens

一个 MCP (Model Context Protocol) Server，让 AI 助手能够检索和阅读 Maven 项目依赖中的类信息与源码。

## 功能

- **find_class** — 按类名搜索 Maven 依赖中的类，支持精确匹配和模糊搜索，返回全限定名、所属 artifact、类型（class/interface/enum/annotation/record）
- **get_class_info** — 获取类的签名概要（类声明、字段、方法签名，不含方法体），token 开销小，适合快速了解 API 结构
- **get_class_source** — 获取类的完整源码，优先从 sources.jar 提取，无 sources.jar 时自动 CFR 反编译

## 前置要求

- JDK 17+（MCP SDK 依赖要求）
- Maven 3.6+

## 构建

```bash
mvn clean package -DskipTests
```

构建产物为 fat jar：`target/maven-lens-1.0-SNAPSHOT.jar`

## MCP 配置

### Claude Desktop

编辑配置文件：

- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "maven-lens": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/maven-lens-1.0-SNAPSHOT.jar"]
    }
  }
}
```

### Claude Code

编辑项目目录下的 `.mcp.json`：

```json
{
  "mcpServers": {
    "maven-lens": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/maven-lens-1.0-SNAPSHOT.jar"]
    }
  }
}
```

### Cursor

在 Settings > MCP 中添加，或编辑 `~/.cursor/mcp.json`：

```json
{
  "mcpServers": {
    "maven-lens": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/maven-lens-1.0-SNAPSHOT.jar"]
    }
  }
}
```

> 将 `/absolute/path/to/maven-lens-1.0-SNAPSHOT.jar` 替换为实际的 jar 文件绝对路径。

如果系统默认 JDK 不是 17+，需要指定 JDK 17 的完整路径：

```json
{
  "mcpServers": {
    "maven-lens": {
      "command": "/path/to/jdk17/bin/java",
      "args": ["-jar", "/absolute/path/to/maven-lens-1.0-SNAPSHOT.jar"]
    }
  }
}
```

## 工具说明

### find_class

按类名搜索依赖中的类。

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| pom_path | string | 是 | 项目 pom.xml 的绝对路径 |
| class_name | string | 是 | 类名，支持精确匹配和模糊搜索 |

**参数示例：**

```json
{
  "pom_path": "/home/user/my-project/pom.xml",
  "class_name": "Transactional"
}
```

**返回示例：**

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

### get_class_info

获取类的签名概要，方法体替换为 `{ ... }`。

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| pom_path | string | 是 | 项目 pom.xml 的绝对路径 |
| fully_qualified_name | string | 是 | 全限定类名 |

**参数示例：**

```json
{
  "pom_path": "/home/user/my-project/pom.xml",
  "fully_qualified_name": "org.springframework.transaction.annotation.Transactional"
}
```

**返回示例：**

```json
{
  "source_type": "sources-jar",
  "artifact": "org.springframework:spring-tx:6.1.4",
  "content": "public @interface Transactional {\n    Propagation propagation() default Propagation.REQUIRED;\n    ...\n}"
}
```

### get_class_source

获取完整源码。

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| pom_path | string | 是 | 项目 pom.xml 的绝对路径 |
| fully_qualified_name | string | 是 | 全限定类名 |

**参数示例：**

```json
{
  "pom_path": "/home/user/my-project/pom.xml",
  "fully_qualified_name": "com.google.common.collect.ImmutableList"
}
```

**返回示例：**

```json
{
  "source_type": "decompiled",
  "artifact": "com.google.guava:guava:33.0.0-jre",
  "content": "// 完整源码..."
}
```

`source_type` 为 `sources-jar` 或 `decompiled`，表示源码来源。

## 工作原理

1. 首次调用任意工具时，自动解析 pom.xml 的依赖树并构建类名索引
2. 解析结果按 pom.xml 路径缓存，pom.xml 修改后自动重建
3. 源码获取优先从 sources.jar 提取原始源码，无 sources.jar 时通过 CFR 反编译字节码
4. 支持多模块 Maven 项目

## License

Apache License 2.0
