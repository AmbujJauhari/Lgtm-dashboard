package com.firm.panels;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PanelsApplication {

    public static void main(String[] args) {
        // --config=<path> selects the environment YAML; defaults to config/local.yaml (classpath).
        // Pass file:/absolute/path.yaml to load from the filesystem instead.
        String configPath = "classpath:config/local.yaml";
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                String raw = arg.substring("--config=".length());
                configPath = raw.startsWith("file:") || raw.startsWith("classpath:") ? raw : "classpath:" + raw;
                break;
            }
        }
        System.setProperty("spring.config.import", configPath);

        System.exit(SpringApplication.exit(SpringApplication.run(PanelsApplication.class, args)));
    }
}
