# Maven Lens

[English](README.md)

一个 MCP (Model Context Protocol) Server，让 AI 助手能够检索和阅读 Maven 项目依赖中的类信息与源码。

## 解决什么问题

AI 编码助手在处理 Maven 项目时，无法查看第三方依赖的类定义和源码，只能靠"猜"来使用 API。Maven Lens 让 AI 能够：

- 搜索依赖中的类，找到正确的全限定名和所属 artifact
- 查看类的签名概要（字段、方法签名），快速了解 API 结构
- 查看完整源码，理解具体实现逻辑

## 功能

提供三个 MCP Tool，按信息粒度从粗到细排列：

| 工具 | 用途 | Token 开销 |
|------|------|-----------|
| **find_class** | 按类名搜索，返回匹配的类列表 | 低 |
| **get_class_info** | 获取类签名概要（去掉方法体） | 中 |
| **get_class_source** | 获取完整源码 | 高 |

## 前置要求

- JDK 17+
- Maven 3.6+
- 目标项目的依赖已下载到本地仓库（`~/.m2/repository`）

## 构建

```bash
mvn clean package -DskipTests
```

构建产物为 fat jar：`target/maven-lens-1.0-SNAPSHOT.jar`

## MCP 配置

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

按类名搜索依赖中的类。支持三种匹配方式：
1. **FQN 精确匹配** — 输入完整全限定名，直接定位
2. **简单类名精确匹配** — 输入类名如 `Transactional`，返回所有同名类
3. **模糊匹配** — 输入部分类名，按包含关系搜索

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| pom_path | string | 是 | 项目 pom.xml 的绝对路径 |
| class_name | string | 是 | 类名，支持 FQN、简单类名、模糊搜索 |

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

返回字段说明：
- `fqn` — 全限定类名
- `artifact` — 所属 Maven 坐标（groupId:artifactId:version）
- `type` — 类型：class / interface / enum / annotation / record
- `has_sources` — 是否有 sources.jar（有则后续可获取原始源码）

### get_class_info

获取类的签名概要。方法体替换为 `{ ... }`，保留类声明、字段、方法签名和注解。适合快速了解 API 结构，token 开销远小于完整源码。

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| pom_path | string | 是 | 项目 pom.xml 的绝对路径 |
| fully_qualified_name | string | 是 | 全限定类名 |

**返回示例：**

```json
{
  "source_type": "sources-jar",
  "artifact": "org.springframework:spring-tx:6.1.4",
  "content": "public @interface Transactional {\n    Propagation propagation() default Propagation.REQUIRED;\n    ...\n}"
}
```

### get_class_source

获取完整源码，包含所有方法实现细节。

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| pom_path | string | 是 | 项目 pom.xml 的绝对路径 |
| fully_qualified_name | string | 是 | 全限定类名 |

**返回示例：**

```json
{
  "source_type": "decompiled",
  "artifact": "com.google.guava:guava:33.0.0-jre",
  "content": "// 完整源码..."
}
```

`source_type` 取值为 `sources-jar` 或 `decompiled`，表示源码来源。

## 工作原理

### 依赖解析

1. 首次调用时，解析 pom.xml 的完整依赖树（使用 Maven Resolver）
2. 支持多模块项目 — 自动向上查找根 pom，收集所有子模块
3. 内部模块优先读取 `target/classes` 目录，无需打包成 jar
4. 对 Maven Resolver 未展开的传递依赖，从本地仓库的 pom 补充解析
5. 解析结果按 pom.xml 路径缓存，pom.xml 修改后自动重建

### 类名索引

解析完成后，遍历所有依赖的 jar 和 classes 目录，构建两级索引：
- 简单类名索引 — 支持快速精确匹配和模糊搜索
- FQN 索引 — 支持全限定名精确查找

### 源码获取

优先级：
1. **sources.jar** — 从本地仓库提取原始 `.java` 源码，保留注释和 Javadoc
2. **CFR 反编译** — 无 sources.jar 时，反编译 `.class` 字节码

## License

Apache License 2.0
