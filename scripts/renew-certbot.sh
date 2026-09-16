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

if [ -d "$PORTAFOLIO_DIR" ] && [ "${FORCE_HOST_CERTBOT:-0}" != "1" ]; then
  cd "$PORTAFOLIO_DIR"
  if [ $# -gt 0 ]; then
    sg docker -c "docker compose --profile ssl run --rm certbot renew $*"
  else
    sg docker -c 'docker compose --profile ssl run --rm certbot renew --quiet'
  fi
  sg docker -c 'docker compose exec -T nginx nginx -s reload'
elif command -v certbot >/dev/null 2>&1; then
  # Standalone VPS with host-level Certbot and Nginx
  if [ "$(id -u)" -eq 0 ]; then
    certbot renew --quiet "$@"
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet nginx 2>/dev/null; then
      systemctl reload nginx
    elif command -v nginx >/dev/null 2>&1; then
      nginx -s reload
    fi
  else
    sudo certbot renew --quiet "$@"
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet nginx 2>/dev/null; then
      sudo systemctl reload nginx
    elif command -v nginx >/dev/null 2>&1; then
      sudo nginx -s reload
    fi
  fi
else
  echo "Error: neither host certbot nor PORTAFOLIO_DIR ($PORTAFOLIO_DIR) is available." >&2
  exit 1
fi

echo "renew-certbot.sh ok $(date -Is)"
