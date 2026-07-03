package com.mysc.mydoc.mcp;

import com.mysc.mydoc.config.HeaderAuthFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfig {

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .mcpEndpoint("/mcp")
                .contextExtractor(request -> {
                    Object memberId = request.getAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE);
                    if (memberId == null) {
                        return McpTransportContext.EMPTY;
                    }
                    return McpTransportContext.create(Map.of(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE, memberId));
                })
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider
    ) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transportProvider, "/mcp");
        registration.setName("mcp");
        return registration;
    }

    @Bean(destroyMethod = "close")
    McpSyncServer mcpServer(
            HttpServletStreamableServerTransportProvider transportProvider,
            McpToolService tools
    ) {
        return McpServer.sync(transportProvider)
                .serverInfo("mydoc", "0.0.1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.specifications())
                .build();
    }
}
