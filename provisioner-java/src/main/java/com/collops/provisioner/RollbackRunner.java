package com.collops.provisioner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entry point for the `rollback` command.
 *
 * Usage:
 *   java -jar provisioner.jar rollback --config=config/prod.yaml --service-group=app --version=3
 *
 * Reverts all three dashboards (overview, service_overview, investigation) for the
 * given service group to the specified Grafana version number.
 *
 * To list available versions: GET /api/dashboards/uid/{uid}/versions
 */
@Component
@Order(2)
public class RollbackRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(RollbackRunner.class);

    @Autowired private EnvConfig envConfig;

    private int exitCode = 0;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> nonOptionArgs = args.getNonOptionArgs();
        if (nonOptionArgs.isEmpty()) {
            System.err.println("Usage: java -jar provisioner.jar [deploy|rollback] --config=<path>");
            exitCode = 1;
            return;
        }
        String command = nonOptionArgs.get(0);
        if ("deploy".equals(command)) {
            return;
        }
        if (!"rollback".equals(command)) {
            System.err.println("Unknown command: " + command);
            System.err.println("Usage: java -jar provisioner.jar [deploy|rollback] --config=<path>");
            exitCode = 1;
            return;
        }

        String serviceGroup = requiredOption(args, "service-group");
        String verStr       = requiredOption(args, "version");
        if (serviceGroup == null || verStr == null) {
            exitCode = 1;
            return;
        }

        int version;
        try {
            version = Integer.parseInt(verStr);
        } catch (NumberFormatException e) {
            System.err.println("--version must be an integer, got: " + verStr);
            exitCode = 1;
            return;
        }

        EnvConfig.ServiceGroupConfig sg = envConfig.getServiceGroups().get(serviceGroup);
        if (sg == null) {
            System.err.println("Service group '" + serviceGroup + "' not found in config file.");
            exitCode = 1;
            return;
        }

        String team         = envConfig.getTeam();
        String env          = envConfig.getEnv();
        String envFolderUid = slug(team) + "-" + slug(serviceGroup) + "-" + slug(env);

        List<String> dashboardUids = List.of(
                envFolderUid + "-overview",
                envFolderUid + "-service-overview",
                envFolderUid + "-investigation"
        );

        String token = sg.getGrafanaTokenEnv() != null ? System.getenv(sg.getGrafanaTokenEnv()) : null;
        String grafanaUser = System.getenv().getOrDefault("GRAFANA_USER", "admin");
        String grafanaPass = System.getenv().getOrDefault("GRAFANA_PASSWORD", "admin");

        GrafanaClient client = new GrafanaClient(sg.getGrafanaUrl(), token, grafanaUser, grafanaPass);

        log.info("Rolling back service_group='{}' env='{}' to version {}", serviceGroup, env, version);

        for (String uid : dashboardUids) {
            int id = client.resolveDashboardId(uid);
            if (id == -1) {
                log.warn("Dashboard '{}' not found, skipping", uid);
                continue;
            }
            client.rollback(id, version);
        }

        log.info("Rollback complete.");
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private String requiredOption(ApplicationArguments args, String name) {
        if (!args.containsOption(name) || args.getOptionValues(name).isEmpty()) {
            System.err.println("Missing required option: --" + name);
            return null;
        }
        return args.getOptionValues(name).get(0);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
