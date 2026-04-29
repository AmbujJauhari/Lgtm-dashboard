# CollOps Grafana Dashboard

Three-tier observability dashboard structure for the CollOps service fleet.

```
Tier 1  CollOps Overview      → which service is on fire?
Tier 2  <Service> Overview    → what is wrong with it?
Tier 3  Investigation         → why? (logs + metrics + traces correlated)
```

---

## Prerequisites

- Docker Desktop (or Docker Engine + Compose v2)
- Python 3.10+

---

## 1. Start the local LGTM stack

```bash
docker compose up -d
```

Two containers start:

| Container | Image | Purpose |
|---|---|---|
| `collops-lgtm` | `grafana/otel-lgtm` | All-in-one: Grafana + Loki + Tempo + Prometheus + OTel Collector |
| `collops-test-data` | local build | Synthetic telemetry generator |

Exposed ports on the `collops-lgtm` container:

| Port | Service |
|---|---|
| 3001 | Grafana UI — http://localhost:3001 (admin / admin) |
| 4317 | OTLP gRPC ingest |
| 4318 | OTLP HTTP ingest |
| 9090 | Prometheus direct query |

Wait ~15 seconds for Grafana to be ready:

```bash
docker compose ps
curl -s http://localhost:3001/api/health | jq .
```

---

## 2. Verify test data is flowing

The test data generator starts automatically as part of `docker compose up`. It simulates two CollOps services (`order-service`, `payment-service`) producing realistic metrics, traces, and logs.

Check its output:

```bash
docker compose logs -f test-data-generator
```

You should see a heartbeat every 15 seconds. You can also verify data is arriving in each backend:

```bash
# Metrics
curl -s "http://localhost:9090/api/v1/query?query=http_server_request_duration_seconds_count" | jq .

# Logs
curl -s "http://localhost:3100/loki/api/v1/labels" | jq .

# Traces
curl -s "http://localhost:3200/api/search?service=order-service&limit=5" | jq .
```

To stop the generator without stopping the rest of the stack:

```bash
docker compose stop test-data-generator
```

To run the generator on your host machine instead (e.g. to modify it without rebuilding the image):

```bash
cd test_data
pip install -r requirements.txt
python generate.py
```

---

## 3. Run the provisioner

The provisioner runs as a Docker container — no Python needed on the host.

Build the provisioner image once (or after editing `provisioner/requirements.txt`):

```bash
docker compose build provisioner
```

### One-time setup (creates folders, library panels, Overview + Investigation dashboards)

```bash
docker compose run --rm provisioner setup --env local
```

### Onboard a service (creates its subfolder and component dashboard)

```bash
docker compose run --rm provisioner onboard --env local --service order-service
docker compose run --rm provisioner onboard --env local --service payment-service
```

Open Grafana at http://localhost:3001 — you should see:

```
CollOps/
├── CollOps Overview
├── Investigation/
│   └── Investigation
├── Library Panels/    (8 reusable panels)
├── order-service/
│   └── order-service Overview
└── payment-service/
    └── payment-service Overview
```

All provisioner commands are **idempotent** — safe to re-run.

The provisioner service uses `profiles: [tools]` so it is never started automatically by `docker compose up -d`. It only runs when you explicitly call `docker compose run`.

---

## 4. Onboard a new service

1. Add the OTel zero-code agent to the service (it will start pushing telemetry with `appCode=CollOps`).
2. Run:
   ```bash
   docker compose run --rm provisioner onboard --env nonprod --service my-new-service
   ```
   This creates the Grafana folder and dashboard. No other changes needed.

The service name is added automatically to `config/services.txt` which acts as the promotion manifest.

---

## 5. Promote to another environment

Update `config/environments.yaml` with the target Grafana URL and datasource UIDs (get UIDs via `GET /api/datasources` on that Grafana instance).

Then trigger the GitLab pipeline manually:

```
GitLab → CI/CD → Pipelines → Run pipeline → promote-uat
```

Or run locally:

```bash
export GRAFANA_TOKEN_UAT=<service-account-token>
python provisioner/main.py setup --env uat
python provisioner/main.py onboard --env uat --service all
```

`--service all` reads `config/services.txt` and provisions every listed service.

---

## Navigation flow

```
CollOps Overview (Tier 1)
  Click service row/bar
        ↓
  <Service> Overview (Tier 2)
    Click "Investigate" link (top-right)
          ↓
      Investigation (Tier 3)
        Metric exemplar dot  → Tempo trace
        Log traceId value    → Tempo trace
        Tempo trace row      → Loki logs for that service
```

---

## Updating library panels

Library panels are defined in `library_panels/`. Editing a panel JSON and re-running `setup` updates the library element in Grafana. All dashboards that reference the panel pick up the change automatically.

```bash
# Edit library_panels/component/red_metrics.json, then:
docker compose run --rm provisioner setup --env local
```

---

## Tear down local stack

```bash
docker compose down -v   # removes containers and volumes (wipes all data)
docker compose down      # removes containers only (keeps volumes)
```

---

## Datasource UIDs

The `grafana/otel-lgtm` image provisions datasources automatically. The UIDs in `config/environments.yaml` (`prometheus`, `loki`, `tempo`) are the defaults. If your image version uses different UIDs, find them with:

```bash
curl -s http://localhost:3001/api/datasources -u admin:admin | jq '.[] | {name, uid}'
```

Update the `local.datasources` block in `config/environments.yaml` accordingly.

---

## Repository layout

```
lgtm-dashboard/
├── docker-compose.yml           grafana/otel-lgtm + test data generator
├── provisioner/
│   ├── main.py                  CLI: setup / onboard
│   ├── grafana_client.py        Grafana HTTP API wrapper
│   └── requirements.txt
├── templates/
│   ├── collops_overview.json    Tier 1 static dashboard
│   ├── service_overview.json.j2 Tier 2 Jinja2 template (per service)
│   └── investigation.json       Tier 3 static dashboard
├── library_panels/
│   ├── aggregate/               [Aggregate] panels (cross-service)
│   └── component/               [Component] panels (per instance)
├── config/
│   ├── environments.yaml        Grafana URLs + datasource UIDs per env
│   └── services.txt             Onboarded service names (promotion manifest)
├── test_data/
│   ├── generate.py              Synthetic telemetry generator
│   ├── requirements.txt
│   └── Dockerfile
└── .gitlab-ci.yml               Promotion pipeline (UAT + prod)
```
