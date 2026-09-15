.PHONY: install dev build ci ci-clone db-reset db-status db-migrate db-backup db-restore docker-up docker-down

install:
	npm install

dev:
	npm run dev

build:
	npm run build

ci:
	bash scripts/ci.sh

ci-clone:
	@set -euo pipefail; \
	clone_dir="$$(mktemp -d /tmp/trindade-ci.XXXXXX)"; \
	cleanup() { rm -rf "$$clone_dir"; }; \
	trap cleanup EXIT; \
	git clone --no-local "$$(git rev-parse --show-toplevel)" "$$clone_dir"; \
	overlay_paths=""; \
	for path in scripts/ci.sh scripts/verify-schema-clis.sh package.json README.md Makefile; do \
		if git status --porcelain -- "$$path" | grep -q .; then overlay_paths="$$overlay_paths $$path"; fi; \
	done; \
	if [ -n "$$overlay_paths" ]; then \
		printf '\n!!! WARNING: ci-clone is overlaying these dirty paths:%s !!!\n' "$$overlay_paths" >&2; \
		printf '!!! This run is NOT a clean-checkout proof; it is HEAD plus working-tree gate files. !!!\n\n' >&2; \
		mkdir -p "$$clone_dir/scripts"; \
		for path in $$overlay_paths; do \
			mkdir -p "$$clone_dir/$$(dirname "$$path")"; cp "$$path" "$$clone_dir/$$path"; \
		done; \
	else \
		printf '\nci-clone: no gate paths are dirty; this is a clean-checkout proof of HEAD.\n'; \
	fi; \
	docker run --rm \
		--user root \
		--env HOST_UID="$$(id -u)" \
		--env HOST_GID="$$(id -g)" \
		--env HOME=/tmp/trindade-home \
		--env npm_config_cache=/tmp/trindade-npm-cache \
		--volume "$$clone_dir:/work" \
		--workdir /work \
		node:24-bookworm-slim \
		bash -ceu 'apt-get update -qq; apt-get install -y -qq --no-install-recommends util-linux >/dev/null; install -d -o "$$HOST_UID" -g "$$HOST_GID" "$$HOME" "$$npm_config_cache"; setpriv --reuid "$$HOST_UID" --regid "$$HOST_GID" --clear-groups bash scripts/ci.sh'


db-reset:
	rm -f packages/backend/data/trindade.db
	@echo "Database reset. Restart the backend to recreate."

db-status:
	@npm run db:status --workspace=packages/backend -- "$${DATABASE_PATH:-packages/backend/data/trindade.db}"

db-migrate:
	@npm run db:migrate --workspace=packages/backend -- "$${DATABASE_PATH:-packages/backend/data/trindade.db}"

db-backup:
	@bash scripts/db-backup.sh "$${BACKUP_DIR:-}"

db-restore:
	@bash scripts/db-restore.sh "$${BACKUP_DIR:?Error: BACKUP_DIR must be provided}" $${CONFIRM:-}

docker-up:
	docker compose up -d

docker-down:
	docker compose down
