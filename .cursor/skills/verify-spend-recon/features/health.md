# Health

An operator opens the health URL and sees that the API process is up, including its database check. No API key is required.

## Sub-features

- `health.up` — `GET /actuator/health` returns 200 and `"status":"UP"`.

## How to get to it (user POV)

- Open `http://127.0.0.1:8080/actuator/health` in a browser or with curl. No header is required.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The base URL is `http://127.0.0.1:8080`.

- **Read health.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive health` requests `GET /actuator/health`. Result: HTTP 200, body contains `"status":"UP"`, and the body file is `/tmp/spend-recon-verify-evidence/$RUN_ID/health/body.json`.

## Gotchas

- A Postgres healthcheck is not this check. Ready means the HTTP status is `UP`, which includes the database after Flyway has finished.
- Port `8080` belongs to one stack. Do not point this recipe at an app you did not launch.
- If launch fails with `Connect timed out` in the app log, see the FORWARD-policy note in `SKILL.md`. That host fix is not part of the product.
