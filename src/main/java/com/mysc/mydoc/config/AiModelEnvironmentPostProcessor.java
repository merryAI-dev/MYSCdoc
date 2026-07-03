package com.mysc.mydoc.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

public class AiModelEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addFirst(new MapPropertySource("mydoc-ai-models", Map.of(
                "spring.ai.model.embedding", StringUtils.hasText(environment.getProperty("OPENAI_API_KEY")) ? "openai" : "none",
                "spring.ai.model.chat", StringUtils.hasText(environment.getProperty("ANTHROPIC_API_KEY")) ? "anthropic" : "none"
        )));
    }
}
