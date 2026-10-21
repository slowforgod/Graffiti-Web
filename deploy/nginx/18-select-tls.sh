#!/bin/sh
# Runs before the image's envsubst step (20-envsubst-on-templates.sh). Picks the HTTPS server
# blocks when a certificate for $DOMAIN exists, otherwise HTTP only, which is enough to get the
# first certificate from Let's Encrypt.
set -eu

: "${DOMAIN:?DOMAIN must be set}"
mkdir -p /etc/nginx/templates
if [ -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" ]; then
    cp /etc/nginx/tls-templates/https.conf.template /etc/nginx/templates/default.conf.template
    echo "select-tls: certificate found for ${DOMAIN}, serving HTTPS"
else
    cp /etc/nginx/tls-templates/http-only.conf.template /etc/nginx/templates/default.conf.template
    echo "select-tls: no certificate for ${DOMAIN}, serving HTTP only"
fi
