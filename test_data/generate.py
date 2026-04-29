#!/usr/bin/env python3
"""
Synthetic telemetry generator for the CollOps local dev stack.

Simulates two services pushing metrics, traces, and logs continuously:
  - order-service   (low error rate ~2%)
  - payment-service (higher error rate ~5%)

Metrics + traces  → OTLP gRPC → OTel Collector → Prometheus + Tempo
Logs              → Loki HTTP push API directly

Usage:
  python generate.py
  OTLP_ENDPOINT=http://localhost:4317 LOKI_ENDPOINT=http://localhost:3100 python generate.py
"""

import json
import math
import os
import random
import socket
import time
from datetime import datetime, timezone

import requests
from opentelemetry import metrics, trace
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.metrics import Observation
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import Status, StatusCode

OTLP_ENDPOINT = os.environ.get("OTLP_ENDPOINT", "http://localhost:4317")
LOKI_ENDPOINT = os.environ.get("LOKI_ENDPOINT", "http://localhost:3100")

SERVICES = [
    {"name": "order-service",   "instance": "instance-1", "error_rate": 0.02, "base_rps": 12},
    {"name": "payment-service", "instance": "instance-1", "error_rate": 0.05, "base_rps": 6},
]

HTTP_ROUTES = [
    "/api/orders", "/api/orders/{id}", "/api/cart",
    "/api/checkout", "/api/users/{id}", "/api/products",
]

LOG_MESSAGES = {
    "INFO":  ["Request completed successfully", "Cache hit", "DB query executed", "Event published"],
    "WARN":  ["Slow query detected", "Retry attempt", "Circuit breaker half-open"],
    "ERROR": ["Request failed", "DB connection timeout", "Downstream service unavailable"],
}

# Simulated downstream hosts for outbound HTTP calls
DOWNSTREAM_HOSTS = ["payments-api.internal", "inventory-api.internal", "auth-service.internal"]


def push_log(service_name: str, instance: str, level: str, message: str, trace_id: str = ""):
    ts = str(time.time_ns())
    log_body = json.dumps({
        "level": level,
        "message": message,
        "service": service_name,
        "instance": instance,
        "traceId": trace_id,
        "ts": datetime.now(timezone.utc).isoformat(),
    })
    payload = {
        "streams": [
            {
                "stream": {
                    "team": "CollOps",
                    "service_group": "app",
                    "service_name": service_name,
                    "instance": instance,
                    "level": level,
                },
                "values": [[ts, log_body]],
            }
        ]
    }
    try:
        requests.post(f"{LOKI_ENDPOINT}/loki/api/v1/push", json=payload, timeout=3)
    except Exception:
        pass


class ServiceSimulator:
    def __init__(self, cfg: dict):
        self.name = cfg["name"]
        self.instance = cfg["instance"]
        self.error_rate = cfg["error_rate"]
        self.base_rps = cfg["base_rps"]

        self._hostname = socket.gethostname()

        resource = Resource.create({
            "service.name": self.name,
            "service.instance.id": self.instance,
            "instance": self.instance,
            "service.group": "app",
            "team": "CollOps",
            "host.name": self._hostname,
        })

        # Traces
        trace_exporter = OTLPSpanExporter(endpoint=OTLP_ENDPOINT, insecure=True)
        tracer_provider = TracerProvider(resource=resource)
        tracer_provider.add_span_processor(BatchSpanProcessor(trace_exporter))
        self._tracer = tracer_provider.get_tracer(self.name)

        # Metrics
        metric_exporter = OTLPMetricExporter(endpoint=OTLP_ENDPOINT, insecure=True)
        reader = PeriodicExportingMetricReader(metric_exporter, export_interval_millis=5_000)
        meter_provider = MeterProvider(resource=resource, metric_readers=[reader])
        meter = meter_provider.get_meter(self.name)

        self._http_duration = meter.create_histogram(
            "http_server_request_duration_seconds",
            description="HTTP server request duration",
            unit="s",
        )
        self._http_client_duration = meter.create_histogram(
            "http_client_request_duration_seconds",
            description="HTTP client request duration",
            unit="s",
        )
        self._exceptions = meter.create_counter(
            "process_exceptions_total",
            description="Total unhandled exceptions",
        )
        self._gc_duration = meter.create_histogram(
            "jvm_gc_duration_seconds",
            description="JVM GC pause duration",
            unit="s",
        )

        # Observable gauges
        self._heap_eden   = random.randint(50_000_000,  150_000_000)
        self._heap_surv   = random.randint(10_000_000,   30_000_000)
        self._heap_old    = random.randint(100_000_000, 300_000_000)
        self._metaspace   = random.randint(60_000_000,  100_000_000)
        self._code_cache  = random.randint(20_000_000,   50_000_000)
        self._threads     = random.randint(20, 60)
        self._peak_threads = self._threads
        self._cpu         = random.uniform(0.05, 0.25)

        # GC timing state
        self._ticks_since_young_gc = 0
        self._ticks_since_old_gc   = 0

        meter.create_observable_gauge(
            "jvm_memory_used_bytes",
            callbacks=[self._memory_cb],
            description="JVM memory used bytes",
            unit="By",
        )
        meter.create_observable_gauge(
            "jvm_memory_committed_bytes",
            callbacks=[self._memory_committed_cb],
            description="JVM memory committed bytes",
            unit="By",
        )
        meter.create_observable_gauge(
            "jvm_threads_live",
            callbacks=[self._threads_cb],
            description="Live thread count",
        )
        meter.create_observable_gauge(
            "jvm_threads_peak",
            callbacks=[self._peak_threads_cb],
            description="Peak thread count since JVM start",
        )
        meter.create_observable_gauge(
            "jvm_cpu_recent_utilization_ratio",
            callbacks=[self._cpu_cb],
            description="Recent process CPU utilization (0–1)",
        )

    # ── Memory callbacks ──────────────────────────────────────────────────────

    def _memory_cb(self, _options):
        self._heap_eden  = max(10_000_000,  min(200_000_000, self._heap_eden  + random.randint(-5_000_000, 15_000_000)))
        self._heap_surv  = max(5_000_000,   min(60_000_000,  self._heap_surv  + random.randint(-2_000_000,  5_000_000)))
        self._heap_old   = max(50_000_000,  min(600_000_000, self._heap_old   + random.randint(-3_000_000, 10_000_000)))
        self._metaspace  = max(40_000_000,  min(150_000_000, self._metaspace  + random.randint(       0,  1_000_000)))
        self._code_cache = max(10_000_000,  min(80_000_000,  self._code_cache + random.randint(       0,    500_000)))

        attrs_base = {"host_name": self._hostname}
        yield Observation(self._heap_eden,  {**attrs_base, "jvm_memory_type": "heap",     "jvm_memory_pool_name": "G1 Eden Space"})
        yield Observation(self._heap_surv,  {**attrs_base, "jvm_memory_type": "heap",     "jvm_memory_pool_name": "G1 Survivor Space"})
        yield Observation(self._heap_old,   {**attrs_base, "jvm_memory_type": "heap",     "jvm_memory_pool_name": "G1 Old Gen"})
        yield Observation(self._metaspace,  {**attrs_base, "jvm_memory_type": "non_heap", "jvm_memory_pool_name": "Metaspace"})
        yield Observation(self._code_cache, {**attrs_base, "jvm_memory_type": "non_heap", "jvm_memory_pool_name": "CodeCache"})

    def _memory_committed_cb(self, _options):
        attrs_base = {"host_name": self._hostname}
        # Committed is slightly above used — OS has reserved it
        yield Observation(int(self._heap_eden  * 1.2), {**attrs_base, "jvm_memory_type": "heap",     "jvm_memory_pool_name": "G1 Eden Space"})
        yield Observation(int(self._heap_surv  * 1.5), {**attrs_base, "jvm_memory_type": "heap",     "jvm_memory_pool_name": "G1 Survivor Space"})
        yield Observation(int(self._heap_old   * 1.1), {**attrs_base, "jvm_memory_type": "heap",     "jvm_memory_pool_name": "G1 Old Gen"})
        yield Observation(int(self._metaspace  * 1.1), {**attrs_base, "jvm_memory_type": "non_heap", "jvm_memory_pool_name": "Metaspace"})
        yield Observation(int(self._code_cache * 1.2), {**attrs_base, "jvm_memory_type": "non_heap", "jvm_memory_pool_name": "CodeCache"})

    # ── Thread callbacks ──────────────────────────────────────────────────────

    def _threads_cb(self, _options):
        self._threads += random.randint(-2, 3)
        self._threads = max(10, min(120, self._threads))
        self._peak_threads = max(self._peak_threads, self._threads)
        yield Observation(self._threads, {"host_name": self._hostname})

    def _peak_threads_cb(self, _options):
        yield Observation(self._peak_threads, {"host_name": self._hostname})

    # ── CPU callback ──────────────────────────────────────────────────────────

    def _cpu_cb(self, _options):
        self._cpu += random.uniform(-0.05, 0.05)
        self._cpu = max(0.02, min(0.95, self._cpu))
        yield Observation(self._cpu, {"host_name": self._hostname})

    # ── GC simulation ─────────────────────────────────────────────────────────

    def _maybe_gc(self):
        self._ticks_since_young_gc += 1
        self._ticks_since_old_gc   += 1

        # Young (minor) GC: every ~5-15 ticks, short pause 2-30ms
        if self._ticks_since_young_gc >= random.randint(5, 15):
            pause = random.uniform(0.002, 0.030)
            self._gc_duration.record(pause, {"jvm_gc_name": "G1 Young Generation", "host_name": self._hostname})
            # Young GC reclaims Eden — simulate heap drop
            self._heap_eden = max(10_000_000, self._heap_eden - random.randint(30_000_000, 80_000_000))
            self._ticks_since_young_gc = 0
            self._cpu = min(0.95, self._cpu + 0.10)  # CPU spike during GC

        # Old (major) GC: every ~120-240 ticks, longer pause 50-200ms
        if self._ticks_since_old_gc >= random.randint(120, 240):
            pause = random.uniform(0.050, 0.200)
            self._gc_duration.record(pause, {"jvm_gc_name": "G1 Old Generation", "host_name": self._hostname})
            self._heap_old = max(50_000_000, self._heap_old - random.randint(50_000_000, 150_000_000))
            self._ticks_since_old_gc = 0
            self._cpu = min(0.95, self._cpu + 0.20)

    # ── Request simulation ────────────────────────────────────────────────────

    def tick(self):
        rps = self.base_rps + random.uniform(-2, 2)
        request_count = max(1, int(rps))
        for _ in range(request_count):
            self._simulate_request()
        self._maybe_gc()

    def _simulate_request(self):
        route = random.choice(HTTP_ROUTES)
        method = random.choices(["GET", "POST", "PUT"], weights=[6, 2, 1])[0]
        is_error = random.random() < self.error_rate
        status_code = random.choice([500, 502, 503]) if is_error else 200

        latency = random.lognormvariate(math.log(0.08), 0.6)
        if is_error:
            latency *= random.uniform(1.5, 4.0)

        with self._tracer.start_as_current_span(
            f"HTTP {method} {route}",
            kind=trace.SpanKind.SERVER,
        ) as span:
            span.set_attribute("http.request.method", method)
            span.set_attribute("url.path", route)
            span.set_attribute("http.response.status_code", status_code)
            span.set_attribute("team", "CollOps")

            trace_id = format(span.get_span_context().trace_id, "032x")

            if is_error:
                span.set_status(Status(StatusCode.ERROR, f"HTTP {status_code}"))
                self._exceptions.add(1, {"exception_type": "HttpServerException", "host_name": self._hostname})
                msg = random.choice(LOG_MESSAGES["ERROR"])
                push_log(self.name, self.instance, "ERROR", f"{msg}: {method} {route} → {status_code}", trace_id)
            elif latency > 0.5:
                msg = random.choice(LOG_MESSAGES["WARN"])
                push_log(self.name, self.instance, "WARN", f"{msg}: {method} {route} took {latency:.2f}s", trace_id)
            else:
                msg = random.choice(LOG_MESSAGES["INFO"])
                push_log(self.name, self.instance, "INFO", f"{msg}: {method} {route} → {status_code}", trace_id)

            # Simulate an outbound HTTP call on ~40% of requests
            if random.random() < 0.4:
                self._simulate_outbound_call(trace_id)

        self._http_duration.record(
            latency,
            {
                "http_request_method": method,
                "url_path": route,
                "http_response_status_code": str(status_code),
                "host_name": self._hostname,
            },
        )

    def _simulate_outbound_call(self, trace_id: str):
        host = random.choice(DOWNSTREAM_HOSTS)
        is_error = random.random() < 0.03
        status_code = random.choice([500, 503]) if is_error else 200
        latency = random.lognormvariate(math.log(0.05), 0.5)

        self._http_client_duration.record(
            latency,
            {
                "http_request_method": "POST",
                "server_address": host,
                "http_response_status_code": str(status_code),
                "host_name": self._hostname,
            },
        )


def main():
    print("=" * 55)
    print("  CollOps test data generator")
    print("=" * 55)
    print(f"  OTLP endpoint : {OTLP_ENDPOINT}")
    print(f"  Loki endpoint : {LOKI_ENDPOINT}")
    print(f"  Services      : {[s['name'] for s in SERVICES]}")
    print("  Press Ctrl+C to stop.")
    print("=" * 55)

    simulators = [ServiceSimulator(s) for s in SERVICES]

    tick = 0
    while True:
        for sim in simulators:
            sim.tick()

        tick += 1
        if tick % 15 == 0:
            ts = datetime.now().strftime("%H:%M:%S")
            print(f"[{ts}] tick {tick} — telemetry flowing for {[s.name for s in simulators]}")

        time.sleep(1)


if __name__ == "__main__":
    main()
