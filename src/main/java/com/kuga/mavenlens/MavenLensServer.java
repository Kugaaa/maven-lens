package com.kuga.mavenlens;

import com.kuga.mavenlens.tool.FindClassTool;
import com.kuga.mavenlens.tool.GetClassInfoTool;
import com.kuga.mavenlens.tool.GetClassSourceTool;
import com.kuga.mavenlens.tool.ProjectContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Maven Lens MCP Server 启动入口
 *
 * @author guorunze
 */
public class MavenLensServer {

    public static void main(String[] args) {
        ProjectContext projectContext = new ProjectContext();

        StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("maven-lens", "1.0.0")
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(
                        new FindClassTool(projectContext).toSpecification(),
                        new GetClassInfoTool(projectContext).toSpecification(),
                        new GetClassSourceTool(projectContext).toSpecification()
                )
                .build();

        // Server 通过 stdin/stdout 通信，阻塞直到进程退出
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
