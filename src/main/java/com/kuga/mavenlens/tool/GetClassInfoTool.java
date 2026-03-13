package com.kuga.mavenlens.tool;

import com.kuga.mavenlens.index.ClassInfo;
import com.kuga.mavenlens.resolver.ResolvedArtifact;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * get_class_info MCP Tool：获取类的签名概要（不含方法体），token 开销小
 *
 * @author guorunze
 */
public class GetClassInfoTool {

    private static final Logger log = LoggerFactory.getLogger(GetClassInfoTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "pom_path": {
                  "type": "string",
                  "description": "Absolute path to the project's pom.xml file"
                },
                "fully_qualified_name": {
                  "type": "string",
                  "description": "Fully qualified class name, e.g. org.springframework.transaction.annotation.Transactional"
                }
              },
              "required": ["pom_path", "fully_qualified_name"]
            }
            """;

    private final ProjectContext projectContext;

    public GetClassInfoTool(ProjectContext projectContext) {
        this.projectContext = projectContext;
    }

    public McpServerFeatures.SyncToolSpecification toSpecification() {
        Tool tool = Tool.builder()
                .name("get_class_info")
                .description("Get class signature summary: class declaration, fields, method signatures (without method bodies). Suitable for quickly understanding the API structure with minimal token cost.")
                .inputSchema(McpJsonDefaults.getMapper(), SCHEMA)
                .build();

        return new McpServerFeatures.SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments();
        String pomPath = (String) arguments.get("pom_path");
        String fqn = (String) arguments.get("fully_qualified_name");

        if (pomPath == null || pomPath.isBlank()) {
            return errorResult("pom_path is required");
        }
        if (fqn == null || fqn.isBlank()) {
            return errorResult("fully_qualified_name is required");
        }

        try {
            ProjectContext.CachedProject project = projectContext.getOrBuild(pomPath);
            ClassInfo classInfo = project.getIndex().findByFqn(fqn);
            if (classInfo == null) {
                return errorResult("Class not found in dependencies: " + fqn);
            }

            String sourceType;
            String source = projectContext.getSourceExtractor().extractSource(
                    classInfo.getGroupId(), classInfo.getArtifactId(), classInfo.getVersion(), fqn);

            if (source != null) {
                sourceType = "sources-jar";
            } else {
                ResolvedArtifact artifact = findArtifact(project, classInfo);
                if (artifact == null) {
                    return errorResult("Artifact not found for: " + classInfo.getArtifact());
                }
                if (artifact.isDirectory()) {
                    source = projectContext.getDecompiler().decompileFromDirectory(artifact.getJarFile(), fqn);
                } else {
                    source = projectContext.getDecompiler().decompile(artifact.getJarFile(), fqn);
                }
                if (source == null) {
                    return errorResult("Failed to decompile class: " + fqn);
                }
                sourceType = "decompiled";
            }

            String summary = projectContext.getSummaryParser().summarize(source);

            String json = "{\"source_type\": \"" + sourceType + "\", " +
                    "\"artifact\": \"" + classInfo.getArtifact() + "\", " +
                    "\"content\": " + escapeJsonString(summary) + "}";

            return CallToolResult.builder()
                    .addTextContent(json)
                    .build();
        } catch (Exception e) {
            log.error("get_class_info failed", e);
            return errorResult("Failed to get class info: " + e.getMessage());
        }
    }

    private ResolvedArtifact findArtifact(ProjectContext.CachedProject project, ClassInfo classInfo) {
        for (ResolvedArtifact artifact : project.getArtifacts()) {
            if (artifact.getGroupId().equals(classInfo.getGroupId())
                    && artifact.getArtifactId().equals(classInfo.getArtifactId())
                    && artifact.getVersion().equals(classInfo.getVersion())) {
                return artifact;
            }
        }
        return null;
    }

    private CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .addTextContent("{\"error\": \"" + message + "\"}")
                .isError(true)
                .build();
    }

    static String escapeJsonString(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
