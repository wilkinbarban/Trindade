SHELL := /usr/bin/env bash

.PHONY: install dev build ci ci-clone ci-android db-reset db-status db-migrate db-backup db-restore docker-up docker-down

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



# Run the Android lane in the SDK image. The canonical gate image has no JDK, and a bare host has no
# Java, Gradle or Android SDK at all, so this lane brings its own toolchain rather than asking a
# developer to install 3-5 GB. Four details are load-bearing:
#   * the SDK install runs as ROOT, in its own invocation, and only the build runs as the invoking
#     user. A named volume mounted over /opt/android-sdk-linux is seeded from the image's SDK
#     directory, which is root-owned, so a non-root process cannot create platforms/android-37.0 in
#     it. Seeding the directory is not the same as granting write permission to the host UID, and
#     conflating the two made the first run on a fresh volume fail -- a state this host could not
#     reach, because its volume was already populated. The install leaves root-owned files that are
#     world-readable, and the build only reads them.
#   * the build runs as the INVOKING USER, never as root. Running as root wrote root-owned build
#     output into the mounted repository, which git ignores and therefore hides, but the ci-clone
#     copy step cannot read, so the Node gate then died with "Permission denied". Same class of
#     defect as the npm-cache poisoning documented above.
#   * GRADLE_USER_HOME is a host directory rather than a named volume, because host directories are
#     already owned by the invoking user and need no chown.
#   * the install is skipped when the platform is already present, so only a fresh volume pays.
#     The package id carries the dotted `.0` suffix, and that is not cosmetic: `sdkmanager --list`
#     offers both `platforms;android-37` and `platforms;android-37.0`, but only the dotted form
#     installs -- the integer form fails with "Failed to find package". Listing a package is not the
#     same as being able to install it, which is the mistake that produced this note.
ci-android:
	@set -euo pipefail; \
	mkdir -p "$$HOME/.cache/trindade-gradle"; \
	docker run --rm \
		--user 0:0 \
		--volume "trindade-android-sdk:/opt/android-sdk-linux" \
		ghcr.io/cirruslabs/android-sdk:35 \
		bash -c 'if [ ! -d /opt/android-sdk-linux/platforms/android-37.0 ]; then sdkmanager --install "platforms;android-37.0"; fi'; \
	docker run --rm \
		--user "$$(id -u):$$(id -g)" \
		--env GRADLE_USER_HOME=/gradle-home \
		--env HOME=/gradle-home \
		--volume "$$HOME/.cache/trindade-gradle:/gradle-home" \
		--volume "trindade-android-sdk:/opt/android-sdk-linux" \
		--volume "$$(git rev-parse --show-toplevel):/work" \
		--workdir /work \
		ghcr.io/cirruslabs/android-sdk:35 \
		bash scripts/ci-android.sh

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
