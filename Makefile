.PHONY: kind-up kind-down load

kind-up:
	./scripts/kind-up.sh

kind-down:
	./scripts/kind-down.sh

# Needs the Compose app on http://127.0.0.1:8080, plus curl, jq, and k6.
load:
	./scripts/seed.sh
	k6 run scripts/load/reconcile-seed.js
	k6 run scripts/load/happy-path.js
