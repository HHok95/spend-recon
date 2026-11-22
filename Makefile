.PHONY: kind-up kind-down load

kind-up:
	./scripts/kind-up.sh

kind-down:
	./scripts/kind-down.sh

# Needs the Compose app on http://127.0.0.1:8080, plus curl, jq, and k6.
# The tool check runs before seed.sh, so a missing k6 does not post a book.
load:
	@command -v curl >/dev/null || { echo "Missing curl." >&2; exit 1; }
	@command -v jq >/dev/null || { echo "Missing jq." >&2; exit 1; }
	@command -v k6 >/dev/null || { echo "Missing k6." >&2; exit 1; }
	./scripts/seed.sh
	k6 run scripts/load/reconcile-seed.js
	k6 run scripts/load/happy-path.js
