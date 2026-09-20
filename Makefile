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



# Run the Android lane in the SDK image. The canonical gate image has no JDK, and a bare host has
# no Java, Gradle or Android SDK at all, so this lane brings its own toolchain rather than asking a
# developer to install 3-5 GB.
#
# EVERYTHING runs as the INVOKING USER, including the SDK provisioning, and the SDK lives in a host
# directory rather than a named volume. Those two changes fix the same class of problem, and the
# second is what makes the first possible:
#
#   * a named volume mounted over /opt/android-sdk-linux is seeded from the image, and everything in
#     it belongs to root. A non-root process could read the SDK but not write to it, and Gradle does
#     write to it: AGP installs missing build-tools and platforms itself and fails with "The SDK
#     directory is not writable" when it cannot. Installing the components up front as root worked
#     around the symptom for the versions known at the time, and broke again the moment AGP wanted
#     build-tools;36.0.0 -- which is what a workaround is.
#   * a host directory is owned by the invoking user from the start, so nothing needs a chown, the
#     SDK stays writable by the build, and the whole lane runs as one identity. It is seeded from the
#     image once, by that same user.
#   * running the build as root is separately forbidden: it wrote root-owned build output into the
#     mounted repository, which git ignores and therefore hides, but the ci-clone copy step cannot
#     read, so the Node gate then died with "Permission denied". Same class as the npm-cache
#     poisoning documented above.
#
# GRADLE_USER_HOME is a host directory for the same reason.
#
# The image is pinned by digest, not by its tag alone. The digest lives in three places -- the two `docker
# run` lines below, .github/workflows/ci.yml, and .github/workflows/android-release.yml -- and the three
# move together: a local lane that runs against whatever the tag resolves to that day can pass here and
# fail in CI against the pinned image, and the release workflow signs with the pinned one. There is
# deliberately no shared variable file or script between them: the image changes once or twice a year, and
# a mechanism would cost more to keep correct than the three edits it saves.
ci-android:
	@set -euo pipefail; \
	mkdir -p "$$HOME/.cache/trindade-gradle" "$$HOME/.cache/trindade-android-sdk"; \
	if [ ! -d "$$HOME/.cache/trindade-android-sdk/platforms" ]; then \
		printf 'Seeding the Android SDK cache from the image; this happens once.\n'; \
		docker run --rm --user "$$(id -u):$$(id -g)" \
			--volume "$$HOME/.cache/trindade-android-sdk:/sdk" \
			ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9 \
			bash -c 'cp -a /opt/android-sdk-linux/. /sdk/'; \
	fi; \
	docker run --rm \
		--user "$$(id -u):$$(id -g)" \
		--env GRADLE_USER_HOME=/gradle-home \
		--env HOME=/gradle-home \
		--volume "$$HOME/.cache/trindade-gradle:/gradle-home" \
		--volume "$$HOME/.cache/trindade-android-sdk:/opt/android-sdk-linux" \
		--volume "$$(git rev-parse --show-toplevel):/work" \
		--workdir /work \
		ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9 \
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
