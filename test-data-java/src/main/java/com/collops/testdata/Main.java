package com.collops.testdata;

import java.util.List;

/**
 * Starts one ServiceSimulator per configured service and ticks them every second.
 *
 * Environment variables:
 *   OTLP_ENDPOINT  — OTLP gRPC endpoint (default: http://localhost:4317)
 *   LOKI_ENDPOINT  — Loki push endpoint  (default: http://localhost:3100)
 */
public class Main {

    record ServiceDef(String name, String instance, double errorRate, int baseRps) {}

    private static final List<ServiceDef> SERVICES = List.of(
            new ServiceDef("order-service",   "instance-1", 0.02, 12),
            new ServiceDef("payment-service", "instance-1", 0.05,  6)
    );

    public static void main(String[] args) throws InterruptedException {
        String otlpEndpoint = env("OTLP_ENDPOINT", "http://localhost:4317");
        String lokiEndpoint = env("LOKI_ENDPOINT",  "http://localhost:3100");

        System.out.println("=".repeat(55));
        System.out.println("  CollOps test data generator");
        System.out.println("=".repeat(55));
        System.out.printf("  OTLP endpoint : %s%n", otlpEndpoint);
        System.out.printf("  Loki endpoint : %s%n", lokiEndpoint);
        System.out.printf("  Services      : %s%n", SERVICES.stream().map(ServiceDef::name).toList());
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println("=".repeat(55));

        List<ServiceSimulator> simulators = SERVICES.stream()
                .map(s -> new ServiceSimulator(s.name(), s.instance(), s.errorRate(), s.baseRps(),
                        otlpEndpoint, lokiEndpoint))
                .toList();

        int tick = 0;
        while (true) {
            for (ServiceSimulator sim : simulators) {
                sim.tick();
            }
            tick++;
            if (tick % 15 == 0) {
                System.out.printf("[%tT] tick %d — telemetry flowing%n", System.currentTimeMillis(), tick);
            }
            Thread.sleep(1_000);
        }
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null && !val.isBlank() ? val : defaultValue;
    }
}
