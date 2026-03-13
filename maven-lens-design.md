# Maven Lens - 设计文档

> 为 Coding Agent 提供 Maven 依赖类库查看能力的 MCP Server

## 1. 背景与问题

当前 Coding Agent（如 Claude Code）在辅助 Java 开发时，无法查看 Maven 依赖中的类定义、方法签名和文档注释。Agent 只能依赖训练数据中的知识，存在以下问题：

- 信息可能过时或不准确
- 无法获取私有/内部 Maven 库的 API 信息
- 缺乏具体版本的准确接口定义

## 2. 方案概述

开发一个 MCP Server，在 Agent 需要时按需查询 Maven 依赖的类信息。

**核心策略：**
- 优先从 `sources.jar` 提取源码（保留完整 Javadoc 注释）
- `sources.jar` 不可用时，使用 CFR 反编译字节码（无注释，但有完整结构）
- 返回结果中标注信息来源，让 Agent 知道是否包含文档

## 3. 技术选型

| 组件 | 选择 | 理由 |
|------|------|------|
| 开发语言 | Java | Maven 依赖解析库、jar 操作、反编译工具均在 Java 生态内 |
| MCP 协议 | modelcontextprotocol/java-sdk | 官方 Java SDK |
| 依赖解析 | maven-resolver | Maven 自身使用的解析引擎，支持 pom 继承、dependencyManagement、scope 传递、版本仲裁 |
| 反编译 | CFR | 纯 Java，无外部依赖，输出质量高 |
| 构建工具 | Maven | 与项目领域一致 |

## 4. MCP Tools 设计

### 4.1 `find_class`

按类名搜索，返回匹配的类列表。首次调用时自动解析 pom.xml 并构建索引。

**输入：**
```json
{
  "pom_path": "/path/to/pom.xml",
  "class_name": "Transactional"
}
```

**输出：**
```json
{
  "matches": [
    {
      "fqn": "org.springframework.transaction.annotation.Transactional",
      "artifact": "org.springframework:spring-tx:5.3.30",
      "type": "annotation",
      "has_sources": true
    },
    {
      "fqn": "jakarta.transaction.Transactional",
      "artifact": "jakarta.transaction:jakarta.transaction-api:2.0.1",
      "type": "annotation",
      "has_sources": false
    }
  ]
}
```

**`type` 取值：** `class` / `interface` / `annotation` / `enum` / `record`

**实现要点：**
- 首次调用时内部自动解析 pom.xml、下载依赖、构建类名索引
- 扫描依赖 jar 的包结构，按简单类名匹配
- 支持模糊搜索（前缀/包含匹配）
- 同一 `pom_path` 的解析结果和索引在 Server 生命周期内缓存复用

### 4.2 `get_class_info`

获取类的签名概要：类声明、字段列表、方法签名，不含方法体。适合 Agent 快速了解类的 API 结构，token 开销小。

**输入：**
```json
{
  "pom_path": "/path/to/pom.xml",
  "fully_qualified_name": "org.springframework.transaction.annotation.Transactional"
}
```

**输出：**
```json
{
  "source_type": "sources-jar",
  "artifact": "org.springframework:spring-tx:5.3.30",
  "content": "package org.springframework.transaction.annotation;\n\n/**\n * Describes a transaction attribute on an individual method or on a class.\n */\n@Target({ElementType.TYPE, ElementType.METHOD})\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface Transactional {\n    @AliasFor(\"transactionManager\")\n    String value() default \"\";\n    Propagation propagation() default Propagation.REQUIRED;\n    Isolation isolation() default Isolation.DEFAULT;\n    int timeout() default -1;\n    // ...\n}"
}
```

**实现要点：**
- 源码模式：从 sources.jar 提取 `.java` 文件，解析后只保留类声明、字段、方法签名和 Javadoc，去掉方法体
- 反编译模式：CFR 反编译 `.class` 后做同样的精简处理
- `source_type` 标注来源：`sources-jar` 或 `decompiled`

### 4.3 `get_class_source`

获取指定类的完整源码。当 Agent 需要深入了解实现细节（如方法体逻辑）时使用。

**输入：**
```json
{
  "pom_path": "/path/to/pom.xml",
  "fully_qualified_name": "org.springframework.transaction.annotation.Transactional"
}
```

**输出：**
```json
{
  "source_type": "sources-jar",
  "artifact": "org.springframework:spring-tx:5.3.30",
  "content": "// 完整源码内容"
}
```

**实现要点：**
- 优先查找 `artifactId-version-sources.jar`，提取 `.java` 文件
- sources.jar 不存在时，使用 CFR 反编译 `.class` 文件
- `source_type` 字段标注来源：`sources-jar` 或 `decompiled`

## 5. 核心流程

```
Agent 遇到不认识的类
  │
  ▼
find_class("Transactional", pom_path)
  │
  ├─ 内部：首次调用自动解析 pom.xml、构建索引（后续命中缓存）
  ├─ 内部：扫描各 jar 包结构
  └─ 返回匹配的 FQN 列表
  │
  ▼
get_class_info(fqn, pom_path)          ← 多数场景到这一步就够了
  │
  ├─ 返回类签名概要（字段 + 方法签名 + Javadoc）
  └─ Agent 了解 API 结构，继续工作
  │
  ▼（仅在需要查看方法实现细节时）
get_class_source(fqn, pom_path)
  │
  ├─ 查找 sources.jar → 有 → 提取 .java 源码（含 Javadoc）
  └─ 无 sources.jar   → CFR 反编译 .class（无注释）
  │
  ▼
Agent 获得完整类定义，继续工作
```

## 6. 配套 SKILL 设计

SKILL 文件作为 Agent 的使用指南，指导 Agent 在合适的时机调用 MCP 工具。

**核心内容：**
- 当遇到不熟悉的类/注解/接口时，主动调用 `find_class` 查询
- 优先使用 `get_class_info` 查看类的 API 概要，满足大部分场景
- 仅在需要了解方法实现细节时，才调用 `get_class_source` 获取完整源码
- 当 `source_type` 为 `decompiled` 时，提示用户文档信息可能不完整

## 7. 项目结构

```
maven-lens/
├── pom.xml
├── src/main/java/com/kuga/mavenlens/
│   ├── MavenLensServer.java          # MCP Server 启动入口
│   ├── tool/
│   │   ├── FindClassTool.java        # find_class 实现
│   │   ├── GetClassInfoTool.java     # get_class_info 实现
│   │   └── GetClassSourceTool.java   # get_class_source 实现
│   ├── resolver/
│   │   ├── DependencyResolver.java   # Maven 依赖解析
│   │   └── ArtifactLocator.java      # 本地仓库 jar 定位
│   ├── source/
│   │   ├── SourceExtractor.java      # sources.jar 提取
│   │   ├── SourceSummaryParser.java  # 源码精简（去方法体，保留签名）
│   │   └── Decompiler.java           # CFR 反编译封装
│   └── index/
│       └── ClassIndex.java           # jar 包类名索引（内存）
└── src/test/java/
```

## 8. 关键实现细节

### 8.1 依赖解析

使用 `org.apache.maven.resolver` 系列库：
- `maven-resolver-api`
- `maven-resolver-impl`
- `maven-resolver-connector-basic`
- `maven-resolver-transport-file`
- `maven-resolver-transport-http`

需要处理：
- `<parent>` 继承
- `<dependencyManagement>` 版本管理
- scope 传递规则
- 版本冲突仲裁
- 多模块项目：遇到 `<modules>` 声明时，递归解析各子模块的 pom.xml，合并依赖列表去重

### 8.2 类名索引与缓存

MCP Server 是长驻进程，索引在 Server 生命周期内缓存复用：

- **索引构建**：对每个依赖 jar，读取其 entry 列表（不解压），构建 `简单类名 → List<FQN>` 的映射
- **缓存粒度**：以 `pom_path` 为 key 缓存整个项目的解析结果和索引
- **缓存失效**：当 pom.xml 的 lastModified 变化时，自动重建该项目的索引

### 8.3 源码获取

**sources.jar 路径规则：**
```
~/.m2/repository/{groupId路径}/{artifactId}/{version}/{artifactId}-{version}-sources.jar
```

不存在时，尝试从远程仓库下载。下载失败则走反编译。

**CFR 反编译调用：**
```java
CfrDriver driver = new CfrDriver.Builder()
    .withOptions(options)
    .withOutputSink(sink)
    .build();
driver.analyse(Collections.singletonList(classFilePath));
```

### 8.4 源码精简（get_class_info）

从完整源码中提取类签名概要的处理流程：
1. 保留 package 声明和 import 语句
2. 保留类/接口/注解的声明（含类级 Javadoc 和注解）
3. 保留所有字段声明（含字段级 Javadoc 和注解）
4. 保留所有方法签名（含方法级 Javadoc、注解、参数列表、throws 声明），去掉方法体
5. 保留内部类/枚举的声明结构，同样只保留签名

### 8.5 私有仓库支持

读取用户 `~/.m2/settings.xml` 中配置的仓库地址和认证信息，传递给 maven-resolver，无需额外配置。

## 9. 错误处理

| 场景 | 处理方式 |
|------|----------|
| pom.xml 不存在或格式错误 | 返回明确的错误信息，说明文件路径和解析失败原因 |
| 远程仓库不可达 | 仅使用本地已有的 jar，在返回结果中标注哪些依赖解析失败 |
| jar 包损坏或 class 文件无法反编译 | 跳过该类，返回错误信息说明原因 |
| 类名在依赖中未找到 | 返回空结果，附带提示信息 |

## 10. MCP Server 配置示例

用户在 Claude Code 中的 MCP 配置：

```json
{
  "mcpServers": {
    "maven-lens": {
      "command": "java",
      "args": ["-jar", "/path/to/maven-lens.jar"],
      "type": "stdio"
    }
  }
}
```

## 11. 后续扩展方向

- **search_by_annotation**：按注解搜索类/方法（如查找所有 `@RestController` 标注的类）
- **get_class_hierarchy**：查看类的继承链和接口实现
- **get_package_classes**：列出某个包下的所有类

---

*author: guorunze*
