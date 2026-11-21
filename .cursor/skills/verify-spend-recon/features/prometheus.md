# Prometheus scrape

An operator or a scraper fetches the Prometheus text from the API and can see process metrics.

## Sub-features

- `prometheus.scrape` — `GET /actuator/prometheus` returns 200 and includes `jvm_memory_used_bytes`.

## How to get to it (user POV)

- Open `http://127.0.0.1:8080/actuator/prometheus`. No API key is required.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The base URL is `http://127.0.0.1:8080`.

- **Scrape metrics.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive prometheus` requests `GET /actuator/prometheus`. Result: HTTP 200 and the body contains `jvm_memory_used_bytes`. The body file is `/tmp/spend-recon-verify-evidence/$RUN_ID/prometheus/body.txt`.

## Gotchas

- The endpoint is not exposed unless the running config includes `prometheus` in `management.endpoints.web.exposure.include`. A 404 means that config is missing, not that the JVM has no metrics.
- Spring Boot tests turn metric exporters off unless observability is enabled. This recipe hits the Compose app, where the exporter is on.
