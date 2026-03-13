---
name: maven-lens
description: 查询 Maven 项目依赖中第三方库的类定义和源码。解决了你无法直接查看 jar 包内容、只能依赖训练数据（可能过时或版本不匹配）的问题。
---

# Maven Lens — MCP 工具使用指南

## 什么是 Maven Lens

Maven Lens 是一个 MCP Server，提供多个工具让你能够查询 Maven 项目依赖中第三方库的类定义和源码。这解决了你无法直接查看 jar
包内容、只能依赖训练数据（可能过时或版本不匹配）的问题。

## 何时使用

当你在 **Maven（Java）项目**中工作，遇到以下场景时，**必须主动使用 Maven Lens**：

1. **不确定某个第三方类/注解的用法** — 不要凭记忆猜测 API，先查一下实际版本的定义
2. **需要了解第三方类有哪些方法/字段** — 查看类签名比猜测更可靠
3. **需要理解第三方库的实现逻辑** — 获取完整源码进行分析
4. **遇到类名冲突，需要确认全限定名** — 搜索类名找到所有匹配项
5. **用户问到某个依赖库的 API 细节** — 直接查实际代码，不要编造
6. **私有/内部库** — 这些库不在你的训练数据中，必须通过工具查询

**判断标准**：如果你对某个第三方类的 API 不够确定（参数、返回值、注解属性等），就应该用 Maven Lens 查一下，而不是凭印象回答。

## 何时不使用

- 项目不是 Maven 项目（没有 pom.xml）
- 查看的是项目自身的源码（直接读文件即可）
- 查看 JDK 标准库的类（如 `java.util.List`）— 这些不在 Maven 依赖中

## 三个工具及使用策略

Maven Lens 提供三个工具，按 Token 成本从低到高排列。**总是从成本最低的工具开始，按需逐步升级**：

### 1. find_class — 搜索类名（低成本，优先使用）

按类名搜索依赖中的类，返回全限定名、所属 artifact、类型等基本信息。

**参数**：

| 参数         | 类型     | 必填 | 说明                   |
|------------|--------|----|----------------------|
| pom_path   | string | 是  | 项目 pom.xml 的**绝对路径** |
| class_name | string | 是  | 类名，支持三种匹配方式          |

**class_name 支持的匹配方式**：

- 全限定名精确匹配：`org.springframework.transaction.annotation.Transactional`
- 简单类名精确匹配：`Transactional`
- 模糊匹配：`Transact`（按包含关系搜索）

**返回示例**：

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

- `fqn` — 全限定类名
- `artifact` — Maven 坐标 groupId:artifactId:version
- `type` — 类型：class / interface / enum / annotation / record
- `has_sources` — 是否有 sources.jar（有则 get_class_source 可获取原始源码含 Javadoc）

### 2. get_class_info — 获取类签名（中等成本）

获取类的签名概要：类声明、字段、方法签名、注解、Javadoc，**方法体被替换为 `{ ... }`**。

**参数**：

| 参数                   | 类型     | 必填 | 说明                        |
|----------------------|--------|----|---------------------------|
| pom_path             | string | 是  | 项目 pom.xml 的**绝对路径**      |
| fully_qualified_name | string | 是  | 全限定类名（从 find_class 结果中获取） |

**返回示例**：

```json
{
  "source_type": "sources-jar",
  "artifact": "org.springframework:spring-tx:6.1.4",
  "content": "package org.springframework.transaction.annotation;\n\n@Target({ElementType.TYPE, ElementType.METHOD})\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface Transactional {\n    String value() default \"\";\n    Propagation propagation() default Propagation.REQUIRED;\n    // ...\n}"
}
```

- `source_type` — 源码来源：`sources-jar`（原始源码）或 `decompiled`（反编译）
- `content` — 类签名概要（不含方法体）

### 3. get_class_source — 获取完整源码（高成本，按需使用）

获取类的完整源码，包含所有方法实现。**仅在需要理解实现逻辑时使用**。

**参数**：

| 参数                   | 类型     | 必填 | 说明                   |
|----------------------|--------|----|----------------------|
| pom_path             | string | 是  | 项目 pom.xml 的**绝对路径** |
| fully_qualified_name | string | 是  | 全限定类名                |

**返回示例**：

```json
{
  "source_type": "decompiled",
  "artifact": "com.google.guava:guava:33.0.0-jre",
  "content": "// 完整源码，包含所有方法实现..."
}
```

## 标准工作流程

```
第一步：find_class 搜索类名
  → 获取全限定名（fqn）、确认所属依赖和版本
  → 大多数场景到这一步就够了（确认类存在、了解属于哪个库）

第二步：get_class_info 查看类签名（如需了解 API）
  → 获取字段、方法签名、注解
  → 足以回答"这个类有哪些方法""这个注解有哪些属性"等问题

第三步：get_class_source 查看完整源码（仅在需要实现细节时）
  → 获取完整方法体
  → 用于回答"这个方法内部怎么实现的""为什么会有这个行为"等问题
```

**重要原则**：不要跳步。先 find_class，再按需 get_class_info，最后才考虑 get_class_source。

## pom_path 的确定

- pom_path 必须是**绝对路径**
- 通常就是当前项目根目录下的 `pom.xml`
- 多模块项目中，使用你当前工作的子模块的 `pom.xml`，工具会自动向上查找根 pom 并解析完整依赖树

## 注意事项

1. **依赖必须已下载** — 目标依赖需要已存在于本地 `~/.m2/repository`。如果工具报错找不到类，可能需要先执行
   `mvn dependency:resolve`
2. **内部模块需编译** — 多模块项目的子模块需要已执行 `mvn compile` 生成 `target/classes`
3. **不含 JDK 类** — 工具只索引 Maven 依赖中的类，不包含 `java.*`、`javax.*` 等 JDK 自带的类
4. **反编译无 Javadoc** — 当 `source_type` 为 `decompiled` 时，源码不包含 Javadoc 注释

## 完整示例

### 场景：帮用户在 Spring 项目中使用 @Transactional

```
→ 用户："帮我给这个方法加上事务管理"

1. 先查找 Transactional 注解：
   find_class(pom_path="/home/user/project/pom.xml", class_name="Transactional")
   → 得到 fqn: org.springframework.transaction.annotation.Transactional

2. 查看注解有哪些属性：
   get_class_info(pom_path="/home/user/project/pom.xml",
                  fully_qualified_name="org.springframework.transaction.annotation.Transactional")
   → 得到 propagation、isolation、timeout、readOnly 等属性定义

3. 根据实际 API 定义为用户编写代码，而不是凭记忆猜测
```

### 场景：用户问某个工具类有哪些方法

```
→ 用户："StringUtils 有没有判断空白字符串的方法？"

1. find_class(pom_path="...", class_name="StringUtils")
   → 可能返回多个匹配（Apache Commons、Spring 等），确认用户项目用的是哪个

2. get_class_info(pom_path="...", fully_qualified_name="org.apache.commons.lang3.StringUtils")
   → 浏览方法签名，找到 isBlank()、isNotBlank() 等方法并告知用户
```
