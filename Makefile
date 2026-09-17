SHELL := /usr/bin/env bash

.PHONY: install dev build ci ci-clone db-reset db-status db-migrate db-backup db-restore docker-up docker-down

install:
	npm install

dev:
	npm run dev

build:
	npm run build

ci:
	bash scripts/ci.sh

# Run the published gate in the declared engine: clone HEAD, overlay any dirty files, and report
# which paths were overlaid. Two details have bitten here and are load-bearing:
#   * the Playwright browser pre-warm must run as the invoking user, never as root. Running it as
#     root wrote root-owned entries into the shared npm cache, and the unprivileged `npm ci` inside
#     the gate then failed with EACCES followed by a cascade of ENOTEMPTY cleanup errors. It only
#     triggered on a host with no pre-existing browser cache, which is exactly a fresh machine and
#     the fresh-CI case.
#   * when the host does have a browser cache it is mounted READ-ONLY, which makes the pre-warm a
#     no-op instead of a re-download.
ci-clone:
	@set -euo pipefail; \
	clone_dir="$$(mktemp -d /tmp/trindade-ci.XXXXXX)"; \
	cleanup() { rm -rf "$$clone_dir"; }; \
	trap cleanup EXIT; \
	git clone --no-local "$$(git rev-parse --show-toplevel)" "$$clone_dir"; \
	overlay_paths=""; \
	while IFS= read -r line || [ -n "$$line" ]; do \
		[ -z "$$line" ] && continue; \
		st="$${line:0:2}"; \
		fp="$${line:3}"; \
		if [[ "$$fp" == *" -> "* ]]; then fp="$${fp##* -> }"; fi; \
		fp="$${fp#\"}"; fp="$${fp%\"}"; \
		overlay_paths="$$overlay_paths $$fp"; \
		if [[ "$$st" =~ D ]]; then \
			rm -rf "$$clone_dir/$$fp"; \
		else \
			if [ -d "$$fp" ]; then \
				mkdir -p "$$clone_dir/$$fp"; cp -a "$$fp/." "$$clone_dir/$$fp/"; \
			else \
				mkdir -p "$$clone_dir/$$(dirname "$$fp")"; cp -a "$$fp" "$$clone_dir/$$fp"; \
			fi; \
		fi; \
	done < <(git status --porcelain); \
	if [ -n "$$overlay_paths" ]; then \
		printf '\n!!! WARNING: ci-clone is overlaying these dirty paths:%s !!!\n' "$$overlay_paths" >&2; \
		printf '!!! This run is NOT a clean-checkout proof; it is HEAD plus uncommitted working-tree files. !!!\n\n' >&2; \
	else \
		printf '\nci-clone: working tree is clean; this is a clean-checkout proof of HEAD.\n'; \
	fi; \
	pw_mount=""; \
	if [ -d "$$HOME/.cache/ms-playwright" ]; then \
		pw_mount="--volume $$HOME/.cache/ms-playwright:/tmp/trindade-home/.cache/ms-playwright:ro"; \
	fi; \
	docker run --rm \
		--user root \
		--env HOST_UID="$$(id -u)" \
		--env HOST_GID="$$(id -g)" \
		--env HOME=/tmp/trindade-home \
		--env npm_config_cache=/tmp/trindade-npm-cache \
		$$pw_mount \
		--volume "$$clone_dir:/work" \
		--workdir /work \
		node:24-bookworm-slim \
		bash -ceu 'apt-get update -qq >/dev/null; apt-get install -y -qq --no-install-recommends util-linux libglib2.0-0 libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libdbus-1-3 libxcb1 libxkbcommon0 libx11-6 libxcomposite1 libxdamage1 libxext6 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2 libasound2 >/dev/null; install -d -o "$$HOST_UID" -g "$$HOST_GID" "$$HOME" "$$npm_config_cache"; as_host() { setpriv --reuid "$$HOST_UID" --regid "$$HOST_GID" --clear-groups "$$@"; }; if [ ! -d /tmp/trindade-home/.cache/ms-playwright ]; then as_host npx -p @playwright/test@1.60.0 playwright install chromium chromium-headless-shell >/dev/null 2>&1 || true; fi; as_host bash scripts/ci.sh'


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
