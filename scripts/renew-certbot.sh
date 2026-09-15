#!/bin/sh
set -eu

# Renew the TLS certificates served by the shared Nginx of the Portafolio
# project. They live in Docker volumes owned by that Compose project
# (portafolio_letsencrypt, portafolio_certbot-www), so renewal runs inside it.
# The webroot authenticator in each renewal config is the same one Nginx serves
# at /.well-known/acme-challenge/, so a working renewal also proves that
# ingress path. Certbot only renews certificates within 30 days of expiring: a
# run with nothing due is a successful no-op.
#
# Run daily by the user crontab, which appends both streams to
# ../certbot-renew.log. The heartbeat line is always the last line of a
# successful run, so a log that stops growing means the job stopped running.
#
# PORTAFOLIO_DIR overrides the Compose project location.

PORTAFOLIO_DIR=${PORTAFOLIO_DIR:-/home/wilkin/proyectos/Portafolio}

cd "$PORTAFOLIO_DIR"

sg docker -c 'docker compose --profile ssl run --rm certbot renew --quiet'

# Nginx reads certificate files when it loads a configuration, and the active
# configuration is generated at container start from the templates, so a reload
# is enough to start serving renewed certificates. Deliberately not
# `up -d --force-recreate`: this job renews certificates, it does not deploy
# unrelated working-tree changes to the Compose file or the Nginx templates.
sg docker -c 'docker compose exec -T nginx nginx -s reload'

echo "renew-certbot.sh ok $(date -Is)"
