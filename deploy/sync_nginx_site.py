"""Idempotently ensure /etc/nginx/sites-available/cogniva includes /missoes/ routes."""
import pathlib
import re
import shutil
import subprocess
import sys


def main() -> None:
    tpl_path = pathlib.Path(sys.argv[1])
    site = pathlib.Path('/etc/nginx/sites-available/cogniva')
    try:
        if not site.is_file():
            return
        cur = site.read_text(encoding='utf-8')
        if 'location /missoes/' in cur and 'location ^~ /api/v1/missions/media/' in cur:
            return

        bak = pathlib.Path('/opt/apps/cogniva-nginx.bak')
        shutil.copy2(site, bak)

        host_m = re.search(r'server_name\s+([^;]+);', cur)
        ipv4_m = re.search(r'listen\s+([0-9.]+):443\s+ssl', cur)
        ipv6_m = re.search(r'(listen\s+\[[^\]]+\]:443\s+ssl[^;]*;)', cur)

        pub_host = host_m.group(1).strip() if host_m else '198.199.65.103'
        pub_ipv4 = ipv4_m.group(1).strip() if ipv4_m else '198.199.65.103'
        ipv6_line = ipv6_m.group(1) if ipv6_m else '# IPv6 SSL disabled'

        rendered = (
            tpl_path.read_text(encoding='utf-8')
            .replace('__PUBLIC_HOST__', pub_host)
            .replace('__PUBLIC_IPV4__', pub_ipv4)
            .replace('# __IPV6_SSL_LISTEN__', ipv6_line)
        )
        site.write_text(rendered, encoding='utf-8')
        if subprocess.call(['nginx', '-t']) == 0:
            subprocess.check_call(['systemctl', 'reload', 'nginx'])
        else:
            shutil.copy2(bak, site)
            raise SystemExit('nginx -t failed when applying /missoes/ routes')
    finally:
        tpl_path.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
