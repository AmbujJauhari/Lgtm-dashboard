package com.collops.testdata;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Random;

/**
 * Simulates a single service emitting metrics, traces, and logs continuously.
 *
 * Metrics + traces  → OTLP gRPC → OTel Collector → Prometheus/Mimir + Tempo
 * Logs              → Loki HTTP push API directly
 */
public class ServiceSimulator {

    private static final List<String> HTTP_ROUTES = List.of(
            "/api/orders", "/api/orders/{id}", "/api/cart",
            "/api/checkout", "/api/users/{id}", "/api/products"
    );
    private static final String[] METHODS         = {"GET", "GET", "GET", "GET", "GET", "GET", "POST", "POST", "PUT"};
    private static final List<String> DOWNSTREAM  = List.of(
            "payments-api.internal", "inventory-api.internal", "auth-service.internal"
    );

    private static final List<String> INFO_MSGS  = List.of(
            "Request completed successfully", "Cache hit", "DB query executed", "Event published");
    private static final List<String> WARN_MSGS  = List.of(
            "Slow query detected", "Retry attempt", "Circuit breaker half-open");
    private static final List<String> ERROR_MSGS = List.of(
            "Request failed", "DB connection timeout", "Downstream service unavailable");

    private final String serviceName;
    private final String instance;
    private final double errorRate;
    private final int    baseRps;
    private final String lokiEndpoint;
    private final String hostname;

    private final Tracer          tracer;
    private final DoubleHistogram httpServerDuration;
    private final DoubleHistogram httpClientDuration;
    private final LongCounter     exceptions;
    private final DoubleHistogram gcDuration;

    private final HttpClient   httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper     = new ObjectMapper();
    private final Random       rng        = new Random();

    // Observable gauge state — volatile so metrics export thread sees fresh values
    private volatile double heapEden;
    private volatile double heapSurv;
    private volatile double heapOld;
    private volatile double metaspace;
    private volatile double codeCache;
    private volatile int    threads;
    private volatile int    peakThreads;
    private volatile double cpu;

    // GC tick counters — only mutated on the main thread
    private int ticksSinceYoungGc = 0;
    private int ticksSinceOldGc   = 0;

    public ServiceSimulator(String serviceName, String instance, double errorRate, int baseRps,
                             String otlpEndpoint, String lokiEndpoint) {
        this.serviceName   = serviceName;
        this.instance      = instance;
        this.errorRate     = errorRate;
        this.baseRps       = baseRps;
        this.lokiEndpoint  = lokiEndpoint;
        this.hostname      = resolveHostname();

        // Initial JVM gauge values
        heapEden   = randomBetween(50_000_000,  150_000_000);
        heapSurv   = randomBetween(10_000_000,   30_000_000);
        heapOld    = randomBetween(100_000_000, 300_000_000);
        metaspace  = randomBetween(60_000_000,  100_000_000);
        codeCache  = randomBetween(20_000_000,   50_000_000);
        threads    = rng.nextInt(20, 60);
        peakThreads = threads;
        cpu        = rng.nextDouble(0.05, 0.25);

        Resource resource = Resource.getDefault().merge(
                Resource.builder()
                        .put(AttributeKey.stringKey("service.name"),        serviceName)
                        .put(AttributeKey.stringKey("service.instance.id"), instance)
                        .put(AttributeKey.stringKey("service.group"),       "app")
                        .put(AttributeKey.stringKey("team"),                "CollOps")
                        .put(AttributeKey.stringKey("host.name"),           hostname)
                        .build()
        );

        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setResource(resource)
                .build();

        OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                        .setInterval(Duration.ofSeconds(5))
                        .build())
                .setResource(resource)
                .build();

        OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();

        tracer = otel.getTracer(serviceName);
        Meter  meter  = otel.getMeter(serviceName);

        httpServerDuration = meter.histogramBuilder("http_server_request_duration_seconds")
                .setDescription("HTTP server request duration").setUnit("s").build();
        httpClientDuration = meter.histogramBuilder("http_client_request_duration_seconds")
                .setDescription("HTTP client request duration").setUnit("s").build();
        exceptions = meter.counterBuilder("process_exceptions_total")
                .setDescription("Total unhandled exceptions").build();
        gcDuration = meter.histogramBuilder("jvm_gc_duration_seconds")
                .setDescription("JVM GC pause duration").setUnit("s").build();

        registerMemoryGauges(meter);
        registerThreadGauges(meter);
        registerCpuGauge(meter);
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    public void tick() {
        int count = Math.max(1, baseRps + rng.nextInt(-2, 3));
        for (int i = 0; i < count; i++) {
            simulateRequest();
        }
        simulateGc();
    }

    // ── Request simulation ────────────────────────────────────────────────────

    private void simulateRequest() {
        String route      = HTTP_ROUTES.get(rng.nextInt(HTTP_ROUTES.size()));
        String method     = METHODS[rng.nextInt(METHODS.length)];
        boolean isError   = rng.nextDouble() < errorRate;
        int statusCode    = isError ? randomChoice(500, 502, 503) : 200;

        double latency = lognormal(Math.log(0.08), 0.6);
        if (isError) latency *= rng.nextDouble(1.5, 4.0);

        Span span = tracer.spanBuilder("HTTP " + method + " " + route)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        String traceId;
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("http.request.method",         method);
            span.setAttribute("url.path",                    route);
            span.setAttribute("http.response.status_code",   (long) statusCode);
            span.setAttribute("team",                        "CollOps");

            traceId = span.getSpanContext().getTraceId();

            if (isError) {
                span.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
                exceptions.add(1, Attributes.of(
                        AttributeKey.stringKey("exception_type"), "HttpServerException",
                        AttributeKey.stringKey("host_name"),      hostname));
                String msg = randomChoice(ERROR_MSGS);
                pushLog("ERROR", msg + ": " + method + " " + route + " → " + statusCode, traceId);
            } else if (latency > 0.5) {
                String msg = randomChoice(WARN_MSGS);
                pushLog("WARN", String.format("%s: %s %s took %.2fs", msg, method, route, latency), traceId);
            } else {
                String msg = randomChoice(INFO_MSGS);
                pushLog("INFO", msg + ": " + method + " " + route + " → " + statusCode, traceId);
            }

            if (rng.nextDouble() < 0.4) simulateOutboundCall();
        } finally {
            span.end();
        }

        httpServerDuration.record(latency, Attributes.builder()
                .put("http_request_method",          method)
                .put("url_path",                     route)
                .put("http_response_status_code",    String.valueOf(statusCode))
                .put("host_name",                    hostname)
                .build());
    }

    private void simulateOutboundCall() {
        String  host       = randomChoice(DOWNSTREAM);
        boolean isError    = rng.nextDouble() < 0.03;
        int     statusCode = isError ? randomChoice(500, 503) : 200;
        double  latency    = lognormal(Math.log(0.05), 0.5);

        httpClientDuration.record(latency, Attributes.builder()
                .put("http_request_method",       "POST")
                .put("server_address",            host)
                .put("http_response_status_code", String.valueOf(statusCode))
                .put("host_name",                 hostname)
                .build());
    }

    // ── GC simulation ─────────────────────────────────────────────────────────

    private void simulateGc() {
        ticksSinceYoungGc++;
        ticksSinceOldGc++;

        // Young (minor) GC: every 5-15 ticks, 2-30 ms pause
        if (ticksSinceYoungGc >= rng.nextInt(5, 16)) {
            double pause = rng.nextDouble(0.002, 0.030);
            gcDuration.record(pause, Attributes.of(
                    AttributeKey.stringKey("jvm_gc_name"), "G1 Young Generation",
                    AttributeKey.stringKey("host_name"),   hostname));
            heapEden = Math.max(10_000_000, heapEden - rng.nextDouble(30_000_000, 80_000_000));
            cpu      = Math.min(0.95, cpu + 0.10);
            ticksSinceYoungGc = 0;
        }

        // Old (major) GC: every 120-240 ticks, 50-200 ms pause
        if (ticksSinceOldGc >= rng.nextInt(120, 241)) {
            double pause = rng.nextDouble(0.050, 0.200);
            gcDuration.record(pause, Attributes.of(
                    AttributeKey.stringKey("jvm_gc_name"), "G1 Old Generation",
                    AttributeKey.stringKey("host_name"),   hostname));
            heapOld = Math.max(50_000_000, heapOld - rng.nextDouble(50_000_000, 150_000_000));
            cpu     = Math.min(0.95, cpu + 0.20);
            ticksSinceOldGc = 0;
        }
    }

    // ── Observable gauge registration ─────────────────────────────────────────

    private void registerMemoryGauges(Meter meter) {
        meter.gaugeBuilder("jvm_memory_used_bytes")
                .setDescription("JVM memory used bytes").setUnit("By")
                .buildWithCallback(obs -> {
                    heapEden  = clamp(heapEden  + rng.nextDouble(-5_000_000,  15_000_000),  10_000_000,  200_000_000);
                    heapSurv  = clamp(heapSurv  + rng.nextDouble(-2_000_000,   5_000_000),   5_000_000,   60_000_000);
                    heapOld   = clamp(heapOld   + rng.nextDouble(-3_000_000,  10_000_000),  50_000_000,  600_000_000);
                    metaspace = clamp(metaspace + rng.nextDouble(         0,   1_000_000),  40_000_000,  150_000_000);
                    codeCache = clamp(codeCache + rng.nextDouble(         0,     500_000),  10_000_000,   80_000_000);

                    obs.record((long) heapEden,  memAttrs("heap",     "G1 Eden Space"));
                    obs.record((long) heapSurv,  memAttrs("heap",     "G1 Survivor Space"));
                    obs.record((long) heapOld,   memAttrs("heap",     "G1 Old Gen"));
                    obs.record((long) metaspace, memAttrs("non_heap", "Metaspace"));
                    obs.record((long) codeCache, memAttrs("non_heap", "CodeCache"));
                });

        meter.gaugeBuilder("jvm_memory_committed_bytes")
                .setDescription("JVM memory committed bytes").setUnit("By")
                .buildWithCallback(obs -> {
                    obs.record((long) (heapEden  * 1.2), memAttrs("heap",     "G1 Eden Space"));
                    obs.record((long) (heapSurv  * 1.5), memAttrs("heap",     "G1 Survivor Space"));
                    obs.record((long) (heapOld   * 1.1), memAttrs("heap",     "G1 Old Gen"));
                    obs.record((long) (metaspace * 1.1), memAttrs("non_heap", "Metaspace"));
                    obs.record((long) (codeCache * 1.2), memAttrs("non_heap", "CodeCache"));
                });
    }

    private void registerThreadGauges(Meter meter) {
        meter.gaugeBuilder("jvm_threads_live")
                .ofLongs()
                .setDescription("Live thread count")
                .buildWithCallback(obs -> {
                    threads     = (int) clamp(threads + rng.nextInt(-2, 4), 10, 120);
                    peakThreads = Math.max(peakThreads, threads);
                    obs.record(threads, Attributes.of(AttributeKey.stringKey("host_name"), hostname));
                });

        meter.gaugeBuilder("jvm_threads_peak")
                .ofLongs()
                .setDescription("Peak thread count since JVM start")
                .buildWithCallback(obs ->
                        obs.record(peakThreads, Attributes.of(AttributeKey.stringKey("host_name"), hostname)));
    }

    private void registerCpuGauge(Meter meter) {
        meter.gaugeBuilder("jvm_cpu_recent_utilization_ratio")
                .setDescription("Recent process CPU utilization (0-1)")
                .buildWithCallback(obs -> {
                    cpu = clamp(cpu + rng.nextDouble(-0.05, 0.05), 0.02, 0.95);
                    obs.record(cpu, Attributes.of(AttributeKey.stringKey("host_name"), hostname));
                });
    }

    private Attributes memAttrs(String type, String pool) {
        return Attributes.builder()
                .put("host_name",            hostname)
                .put("jvm_memory_type",      type)
                .put("jvm_memory_pool_name", pool)
                .build();
    }

    // ── Loki log push ─────────────────────────────────────────────────────────

    private void pushLog(String level, String message, String traceId) {
        try {
            ObjectNode logBody = mapper.createObjectNode();
            logBody.put("level",    level);
            logBody.put("message",  message);
            logBody.put("service",  serviceName);
            logBody.put("instance", instance);
            logBody.put("traceId",  traceId);

            ObjectNode payload = mapper.createObjectNode();
            ArrayNode  streams = payload.putArray("streams");
            ObjectNode stream  = streams.addObject();

            ObjectNode labels = stream.putObject("stream");
            labels.put("team",         "CollOps");
            labels.put("service_group","app");
            labels.put("service_name", serviceName);
            labels.put("instance",     instance);
            labels.put("level",        level);

            ArrayNode values = stream.putArray("values");
            ArrayNode entry  = values.addArray();
            entry.add(String.valueOf(System.currentTimeMillis() * 1_000_000L));
            entry.add(mapper.writeValueAsString(logBody));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(lokiEndpoint + "/loki/api/v1/push"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // best-effort log push; never fail the simulation
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double lognormal(double mu, double sigma) {
        return Math.exp(mu + sigma * rng.nextGaussian());
    }

    private double randomBetween(double min, double max) {
        return min + rng.nextDouble() * (max - min);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @SafeVarargs
    private static <T> T randomChoice(T... items) {
        return items[new Random().nextInt(items.length)];
    }

    private static String randomChoice(List<String> items) {
        return items.get(new Random().nextInt(items.size()));
    }

    private static String resolveHostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown-host"; }
    }
}
