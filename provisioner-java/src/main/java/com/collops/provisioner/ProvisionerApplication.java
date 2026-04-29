package com.collops.provisioner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProvisionerApplication {

    public static void main(String[] args) {
        // --config=<path> selects the environment YAML; defaults to config/local.yaml.
        // Must be set as a system property before the Spring context starts so that
        // @ConfigurationProperties binding picks up the correct file.
        String configPath = "config/local.yaml";
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length());
                break;
            }
        }
        System.setProperty("spring.config.import", "file:" + configPath);

        System.exit(SpringApplication.exit(SpringApplication.run(ProvisionerApplication.class, args)));
    }
}
