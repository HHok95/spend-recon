# Scalar API reference

An operator opens the API reference and sees Scalar render the OpenAPI document this service publishes. The old Swagger UI URL is not served.

## Sub-features

- `scalar.page` — `GET /scalar` returns 200, the page names Scalar, and it loads `/v3/api-docs`.
- `scalar.document` — `GET /v3/api-docs` returns 200 and `"title":"spend-recon"`.
- `scalar.no-swagger-ui` — `GET /swagger-ui/index.html` returns 404.

## How to get to it (user POV)

- Open `http://127.0.0.1:8080/scalar` for the reference UI.
- Open `http://127.0.0.1:8080/v3/api-docs` for the raw document.
- There is no Swagger UI bookmark. `/swagger-ui/index.html` is not a page.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The base URL is `http://127.0.0.1:8080`.
- The running app was built with `scalar.url=/v3/api-docs`. Scalar's unset default is a public demo document, which is not this API.

- **Open the reference.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive scalar` requests `GET /scalar`, `GET /v3/api-docs`, and `GET /swagger-ui/index.html`. Result: `/scalar` is 200 and its body contains `Scalar` and `/v3/api-docs`; the document title is `spend-recon`; Swagger UI is 404. Files are under `/tmp/spend-recon-verify-evidence/$RUN_ID/scalar/`.

## Gotchas

- A 200 from `/scalar` that does not mention `/v3/api-docs` is the demo spec, not this service. Do not pass it.
- Do not accept `/swagger-ui.html` or `/swagger-ui/index.html` as a substitute. Those routes are removed.
- The page is HTML. A browser still needs to load Scalar's script from this same origin; the recipe checks the HTML the server returns.
