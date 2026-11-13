#!/usr/bin/env bash
# Provision and auto-renew short-lived TLS on the VPS without touching shared Compose.
set -euo pipefail

PUBLIC_IPV4="${COGNIVA_PUBLIC_IPV4:-198.199.65.103}"
PUBLIC_IPV6="${COGNIVA_PUBLIC_IPV6:-}"
PUBLIC_HOST="${COGNIVA_PUBLIC_HOST:-$PUBLIC_IPV4}"

bundle_dir=$(cd -- "$(dirname -- "$0")" && pwd)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=/opt/apps/backups/tls-$stamp
install -d -m 700 "$backup"
if [ -f /etc/nginx/sites-available/cogniva ]; then
    cp -a /etc/nginx/sites-available/cogniva "$backup/cogniva"
fi
install -d /var/www/cogniva-acme /etc/letsencrypt /var/lib/letsencrypt /var/log/letsencrypt

if [ -f /etc/nginx/sites-available/cogniva ]; then
    python3 - <<'PY'
from pathlib import Path
p = Path('/etc/nginx/sites-available/cogniva')
s = p.read_text(encoding='utf-8')
if '/.well-known/acme-challenge/' not in s:
    s = s.replace('server {', 'server {\n    location ^~ /.well-known/acme-challenge/ { root /var/www/cogniva-acme; try_files $uri =404; }', 1)
    p.write_text(s, encoding='utf-8')
PY
    nginx -t && systemctl reload nginx
fi

docker pull certbot/certbot:v5.4.0
image=$(docker inspect certbot/certbot:v5.4.0 --format '{{index .RepoDigests 0}}')
certbot_args=(--rm -v /etc/letsencrypt:/etc/letsencrypt -v /var/lib/letsencrypt:/var/lib/letsencrypt -v /var/log/letsencrypt:/var/log/letsencrypt -v /var/www/cogniva-acme:/var/www/cogniva-acme)
ip_flags=(--ip-address "$PUBLIC_IPV4")
if [ -n "$PUBLIC_IPV6" ]; then
    ip_flags+=(--ip-address "$PUBLIC_IPV6")
fi

docker run "${certbot_args[@]}" "$image" certonly --staging --preferred-profile shortlived --webroot --webroot-path /var/www/cogniva-acme "${ip_flags[@]}" --cert-name cogniva-ip-staging --non-interactive --agree-tos --register-unsafely-without-email
docker run "${certbot_args[@]}" "$image" certonly --preferred-profile shortlived --webroot --webroot-path /var/www/cogniva-acme "${ip_flags[@]}" --cert-name cogniva-ip --non-interactive --agree-tos --register-unsafely-without-email

san_output=$(openssl x509 -in /etc/letsencrypt/live/cogniva-ip/fullchain.pem -noout -ext subjectAltName)
ipv6_listen="# IPv6 SSL disabled (no IPv6 SAN configured)"
if [ -n "$PUBLIC_IPV6" ] && printf '%s' "$san_output" | grep -Fq "$PUBLIC_IPV6"; then
    ipv6_listen="listen [$PUBLIC_IPV6]:443 ssl default_server;"
fi

install -m 644 "$bundle_dir/nginx-rate-limits.conf" /etc/nginx/conf.d/cogniva-rate-limits.conf
test -f /etc/nginx/cogniva-gate.conf || printf 'set $cogniva_maintenance 0;\n' > /etc/nginx/cogniva-gate.conf

python3 - "$bundle_dir/nginx-cogniva.conf" "$PUBLIC_HOST" "$PUBLIC_IPV4" "$ipv6_listen" <<'PY'
import sys
from pathlib import Path
template = Path(sys.argv[1]).read_text(encoding='utf-8')
rendered = (
    template
    .replace('__PUBLIC_HOST__', sys.argv[2])
    .replace('__PUBLIC_IPV4__', sys.argv[3])
    .replace('# __IPV6_SSL_LISTEN__', sys.argv[4])
)
Path('/etc/nginx/sites-available/cogniva').write_text(rendered, encoding='utf-8')
PY

if ! nginx -t; then
    if [ -f "$backup/cogniva" ]; then
        cp -a "$backup/cogniva" /etc/nginx/sites-available/cogniva
    fi
    exit 1
fi
systemctl reload nginx

cat > /usr/local/sbin/cogniva-renew-tls <<EOF
#!/usr/bin/env bash
set -euo pipefail
before=\$(sha256sum /etc/letsencrypt/live/cogniva-ip/fullchain.pem | awk '{print \$1}')
docker run ${certbot_args[*]} $image renew --cert-name cogniva-ip --quiet
after=\$(sha256sum /etc/letsencrypt/live/cogniva-ip/fullchain.pem | awk '{print \$1}')
if [ "\$before" != "\$after" ]; then
    nginx -t
    systemctl reload nginx
fi
# Fail loudly if remaining certificate validity drops below 36 hours (six-day cert profile).
openssl x509 -in /etc/letsencrypt/live/cogniva-ip/fullchain.pem -checkend 129600 -noout
EOF
chmod 700 /usr/local/sbin/cogniva-renew-tls

cat > /etc/systemd/system/cogniva-renew-tls.service <<'UNIT'
[Unit]
Description=Renew Cogniva short-lived IP certificate and verify remaining lifetime
After=docker.service network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/cogniva-renew-tls
UNIT

cat > /etc/systemd/system/cogniva-renew-tls.timer <<'UNIT'
[Unit]
Description=Hourly Cogniva IP certificate renewal and expiry check

[Timer]
OnCalendar=hourly
RandomizedDelaySec=300
Persistent=true

[Install]
WantedBy=timers.target
UNIT

systemctl daemon-reload
systemctl enable --now cogniva-renew-tls.timer
systemctl start cogniva-renew-tls.service
openssl x509 -in /etc/letsencrypt/live/cogniva-ip/fullchain.pem -noout -dates -ext subjectAltName
curl --fail --silent --show-error --retry 5 --retry-connrefused --retry-delay 1 "https://$PUBLIC_HOST/api/v1/health"
