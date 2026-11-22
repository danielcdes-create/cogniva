"""Idempotently synchronize /etc/nginx/sites-available/cogniva and rate-limit zones."""
import os
import pathlib
import re
import shutil
import subprocess
import sys


def atomic_write(dest: pathlib.Path, content: str) -> None:
    tmp = dest.with_name(dest.name + '.tmp')
    tmp.write_text(content, encoding='utf-8')
    os.chmod(tmp, 0o644)
    os.replace(tmp, dest)


def main() -> None:
    tpl_path = pathlib.Path(sys.argv[1])
    rates_tpl_path = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else None
    site = pathlib.Path('/etc/nginx/sites-available/cogniva')
    rates_dest = pathlib.Path('/etc/nginx/conf.d/cogniva-rate-limits.conf')
    try:
        if not site.is_file():
            return
        cur = site.read_text(encoding='utf-8')

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

        rates_rendered: str | None = None
        if rates_tpl_path and rates_tpl_path.is_file() and rates_dest.parent.is_dir():
            rates_rendered = rates_tpl_path.read_text(encoding='utf-8')

        site_changed = rendered != cur
        rates_cur = rates_dest.read_text(encoding='utf-8') if rates_dest.is_file() else None
        rates_changed = rates_rendered is not None and rates_rendered != rates_cur
        if not site_changed and not rates_changed:
            return

        bak = pathlib.Path('/opt/apps/cogniva-nginx.bak')
        rates_bak = pathlib.Path('/opt/apps/cogniva-nginx-rates.bak')
        shutil.copy2(site, bak)
        had_rates = rates_dest.is_file()
        if had_rates:
            shutil.copy2(rates_dest, rates_bak)

        if site_changed:
            atomic_write(site, rendered)
        if rates_changed and rates_rendered is not None:
            atomic_write(rates_dest, rates_rendered)

        if subprocess.call(['nginx', '-t']) == 0:
            subprocess.check_call(['systemctl', 'reload', 'nginx'])
        else:
            if site_changed:
                atomic_write(site, bak.read_text(encoding='utf-8'))
            if rates_changed:
                if had_rates:
                    atomic_write(rates_dest, rates_bak.read_text(encoding='utf-8'))
                else:
                    rates_dest.unlink(missing_ok=True)
            raise SystemExit('nginx -t failed when applying Cogniva Nginx configuration')
    finally:
        tpl_path.unlink(missing_ok=True)
        if rates_tpl_path is not None:
            rates_tpl_path.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
