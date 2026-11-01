#!/usr/bin/env bash
# Run on the existing VPS only after review. Pulls a prebuilt ACME client;
# it never builds an application/image and never touches the global Compose.
set -euo pipefail
bundle_dir=$(cd -- "$(dirname -- "$0")" && pwd)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=/opt/apps/backups/tls-$stamp
install -d -m 700 "$backup"
cp -a /etc/nginx/sites-available/cogniva "$backup/cogniva"
install -d /var/www/cogniva-acme /etc/letsencrypt /var/lib/letsencrypt /var/log/letsencrypt
# Keep the current HTTP API available while completing HTTP-01 validation.
python3 - <<'PY'
from pathlib import Path
p=Path('/etc/nginx/sites-available/cogniva')
s=p.read_text()
if '/.well-known/acme-challenge/' not in s:
    s=s.replace('server {','server {\n    location ^~ /.well-known/acme-challenge/ { root /var/www/cogniva-acme; try_files $uri =404; }',1)
    p.write_text(s)
PY
nginx -t
systemctl reload nginx
docker pull certbot/certbot:v5.4.0
image=$(docker inspect certbot/certbot:v5.4.0 --format '{{index .RepoDigests 0}}')
certbot_args=(--rm -v /etc/letsencrypt:/etc/letsencrypt -v /var/lib/letsencrypt:/var/lib/letsencrypt -v /var/log/letsencrypt:/var/log/letsencrypt -v /var/www/cogniva-acme:/var/www/cogniva-acme)
docker run "${certbot_args[@]}" "$image" certonly --staging --preferred-profile shortlived --webroot --webroot-path /var/www/cogniva-acme --ip-address 198.199.65.103 --cert-name cogniva-ip-staging --non-interactive --agree-tos --register-unsafely-without-email
docker run "${certbot_args[@]}" "$image" certonly --preferred-profile shortlived --webroot --webroot-path /var/www/cogniva-acme --ip-address 198.199.65.103 --cert-name cogniva-ip --non-interactive --agree-tos --register-unsafely-without-email
install -m 644 "$bundle_dir/nginx-rate-limits.conf" /etc/nginx/conf.d/cogniva-rate-limits.conf
test -f /etc/nginx/cogniva-gate.conf || printf 'set $cogniva_maintenance 0;\n' > /etc/nginx/cogniva-gate.conf
install -m 644 "$bundle_dir/nginx-concursoprep.conf" /etc/nginx/sites-available/cogniva
if ! nginx -t; then
    cp -a "$backup/cogniva" /etc/nginx/sites-available/cogniva
    rm -f /etc/nginx/conf.d/cogniva-rate-limits.conf
    exit 1
fi
systemctl reload nginx
# Only the production lineage renews here. Hourly checks are appropriate for
# six-day certificates. Every successful renewal loads the renewed certificate.
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
  "docker run ${certbot_args[*]} $image renew --cert-name cogniva-ip --quiet" \
  'nginx -t' 'systemctl reload nginx' > /usr/local/sbin/cogniva-renew-tls
chmod 700 /usr/local/sbin/cogniva-renew-tls
cat > /etc/systemd/system/cogniva-renew-tls.service <<'UNIT'
[Unit]
Description=Renew Cogniva short-lived IP certificate and reload nginx
After=docker.service network-online.target
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/cogniva-renew-tls
UNIT
cat > /etc/systemd/system/cogniva-renew-tls.timer <<'UNIT'
[Unit]
Description=Hourly Cogniva IP certificate renewal check
[Timer]
OnCalendar=hourly
RandomizedDelaySec=600
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now cogniva-renew-tls.timer
systemctl start cogniva-renew-tls.service
openssl x509 -in /etc/letsencrypt/live/cogniva-ip/fullchain.pem -noout -dates -ext subjectAltName
curl --fail --silent --show-error --retry 5 --retry-connrefused --retry-delay 1 https://198.199.65.103/api/v1/health
