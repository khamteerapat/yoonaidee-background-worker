package com.tesseractone.yoonaidee_bg_worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Manual API check; loads Spring configuration without starting the worker or PostgreSQL. */
public final class OpenAiSmoke {
    private OpenAiSmoke() {}

    public static void main(String[] args) throws Exception {
        SpringApplication application = new SpringApplication(SmokeConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("spring.profiles.default", "local"));
        try (var context = application.run(args)) {
            OpenAiResearchExplainer explainer = context.getBean(OpenAiResearchExplainer.class);
            List<String> files = context.getBean(ApplicationArguments.class).getNonOptionArgs();
            if (files.isEmpty()) {
                explainer.checkConnection();
                System.out.println("OpenAI connection OK");
                return;
            }
            if (files.size() != 1) {
                throw new IllegalArgumentException("Usage: OpenAiSmoke [--spring.profiles.active=profile] [evidence.json]");
            }
            JsonObject input = JsonParser.parseString(Files.readString(Path.of(files.getFirst()))).getAsJsonObject();
            System.out.println(explainer.explain(input));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(OpenAiResearchExplainer.class)
    static class SmokeConfiguration {
    }
}
