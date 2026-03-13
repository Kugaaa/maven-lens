package com.kuga.mavenlens.tool;

import com.kuga.mavenlens.index.ClassInfo;
import com.kuga.mavenlens.resolver.ArtifactLocator;
import com.kuga.mavenlens.resolver.ResolvedArtifact;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * find_class MCP Tool：按类名搜索，返回匹配的类列表
 *
 * @author guorunze
 */
public class FindClassTool {

    private static final Logger log = LoggerFactory.getLogger(FindClassTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "pom_path": {
                  "type": "string",
                  "description": "Absolute path to the project's pom.xml file"
                },
                "class_name": {
                  "type": "string",
                  "description": "Simple class name to search for, supports exact match and fuzzy search (prefix/contains)"
                }
              },
              "required": ["pom_path", "class_name"]
            }
            """;

    private final ProjectContext projectContext;

    public FindClassTool(ProjectContext projectContext) {
        this.projectContext = projectContext;
    }

    public McpServerFeatures.SyncToolSpecification toSpecification() {
        Tool tool = Tool.builder()
                .name("find_class")
                .description("Search for a class by name in Maven dependencies. Returns matching fully qualified names, artifact coordinates, and class types. Automatically resolves pom.xml on first call.")
                .inputSchema(McpJsonDefaults.getMapper(), SCHEMA)
                .build();

        return new McpServerFeatures.SyncToolSpecification(tool, this::handle);
    }

    private CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        String pomPath = request.arguments().get("pom_path").toString();
        String className = request.arguments().get("class_name").toString();

        if (pomPath == null || pomPath.isBlank()) {
            return errorResult("pom_path is required");
        }
        if (className == null || className.isBlank()) {
            return errorResult("class_name is required");
        }

        try {
            ProjectContext.CachedProject project = projectContext.getOrBuild(pomPath);
            List<ClassInfo> matches = project.getIndex().search(className);

            if (matches.isEmpty()) {
                return CallToolResult.builder()
                        .addTextContent("{\"matches\": [], \"message\": \"No class found matching: " + className + "\"}")
                        .build();
            }

            StringBuilder json = new StringBuilder();
            json.append("{\"matches\": [");
            for (int i = 0; i < matches.size(); i++) {
                ClassInfo info = matches.get(i);
                ResolvedArtifact resolvedArtifact = findArtifact(project, info);
                String type;
                if (resolvedArtifact != null && resolvedArtifact.isDirectory()) {
                    type = projectContext.getDecompiler().detectTypeFromDirectory(resolvedArtifact.getJarFile(), info.getFqn());
                } else if (resolvedArtifact != null) {
                    type = projectContext.getDecompiler().detectType(resolvedArtifact.getJarFile(), info.getFqn());
                } else {
                    type = "class";
                }

                ArtifactLocator locator = projectContext.getResolver().getArtifactLocator();
                boolean hasSources = locator.hasSourcesJar(info.getGroupId(), info.getArtifactId(), info.getVersion());

                if (i > 0) json.append(",");
                json.append("\n  {")
                        .append("\"fqn\": \"").append(info.getFqn()).append("\", ")
                        .append("\"artifact\": \"").append(info.getArtifact()).append("\", ")
                        .append("\"type\": \"").append(type).append("\", ")
                        .append("\"has_sources\": ").append(hasSources)
                        .append("}");
            }
            json.append("\n]}");

            return CallToolResult.builder()
                    .addTextContent(json.toString())
                    .build();
        } catch (Exception e) {
            log.error("find_class failed", e);
            return errorResult("Failed to search class: " + e.getMessage());
        }
    }

    private ResolvedArtifact findArtifact(ProjectContext.CachedProject project, ClassInfo info) {
        for (ResolvedArtifact artifact : project.getArtifacts()) {
            if (artifact.getGroupId().equals(info.getGroupId())
                    && artifact.getArtifactId().equals(info.getArtifactId())
                    && artifact.getVersion().equals(info.getVersion())) {
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
}
