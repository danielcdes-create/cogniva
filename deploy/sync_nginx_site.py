"""Idempotently synchronize /etc/nginx/sites-available/cogniva and rate-limit zones."""
from __future__ import annotations

import os
import pathlib
import re
import shutil
import subprocess
import sys

DISABLED_IPV6_COMMENT = '# IPv6 SSL disabled (no IPv6 SAN configured)'
DEFAULT_SITE_PATH = pathlib.Path('/etc/nginx/sites-available/cogniva')
DEFAULT_RATES_DEST = pathlib.Path('/etc/nginx/conf.d/cogniva-rate-limits.conf')
DEFAULT_GATE_PATH = pathlib.Path('/etc/nginx/cogniva-gate.conf')


def atomic_write(dest: pathlib.Path, content: str) -> None:
    tmp = dest.with_name(dest.name + '.tmp')
    tmp.write_text(content, encoding='utf-8')
    os.chmod(tmp, 0o644)
    os.replace(tmp, dest)


def render_site_config(template_text: str, current_site_text: str) -> str:
    host_m = re.search(r'server_name\s+([^;]+);', current_site_text)
    ipv4_m = re.search(r'listen\s+([0-9.]+):443\s+ssl', current_site_text)
    ipv6_m = re.search(r'(listen\s+\[[^\]]+\]:443\s+ssl[^;]*;)', current_site_text)

    pub_host = host_m.group(1).strip() if host_m else '198.199.65.103'
    pub_ipv4 = ipv4_m.group(1).strip() if ipv4_m else '198.199.65.103'
    ipv6_line = ipv6_m.group(1) if ipv6_m else DISABLED_IPV6_COMMENT

    return (
        template_text.replace('__PUBLIC_HOST__', pub_host)
        .replace('__PUBLIC_IPV4__', pub_ipv4)
        .replace('# __IPV6_SSL_LISTEN__', ipv6_line)
    )


def sync_nginx_site(
    tpl_path: pathlib.Path,
    rates_tpl_path: pathlib.Path | None = None,
    *,
    site: pathlib.Path | None = None,
    rates_dest: pathlib.Path | None = None,
    gate_path: pathlib.Path | None = None,
    app_root: pathlib.Path | None = None,
) -> bool:
    site_file = site or DEFAULT_SITE_PATH
    rates_file = rates_dest or DEFAULT_RATES_DEST
    gate_file = gate_path or DEFAULT_GATE_PATH
    effective_app_root = app_root or pathlib.Path(os.environ.get('COGNIVA_APP_ROOT', '/opt/apps'))

    try:
        if not site_file.is_file():
            return False
        cur = site_file.read_text(encoding='utf-8')
        rendered = render_site_config(tpl_path.read_text(encoding='utf-8'), cur)

        rates_rendered: str | None = None
        if rates_tpl_path and rates_tpl_path.is_file() and rates_file.parent.is_dir():
            rates_rendered = rates_tpl_path.read_text(encoding='utf-8')

        site_changed = rendered != cur
        rates_cur = rates_file.read_text(encoding='utf-8') if rates_file.is_file() else None
        rates_changed = rates_rendered is not None and rates_rendered != rates_cur
        gate_prev = gate_file.read_text(encoding='utf-8') if gate_file.is_file() else None
        gate_stale = gate_prev is not None and 'set $cogniva_maintenance 1;' in gate_prev
        if not site_changed and not rates_changed and not gate_stale:
            return False

        bak = effective_app_root / 'cogniva-nginx.bak'
        rates_bak = effective_app_root / 'cogniva-nginx-rates.bak'
        bak.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(site_file, bak)
        had_rates = rates_file.is_file()
        if had_rates:
            shutil.copy2(rates_file, rates_bak)

        if site_changed:
            atomic_write(site_file, rendered)
        if rates_changed and rates_rendered is not None:
            atomic_write(rates_file, rates_rendered)
        if gate_stale:
            atomic_write(gate_file, 'set $cogniva_maintenance 0;\n')

        def rollback() -> None:
            if site_changed:
                atomic_write(site_file, bak.read_text(encoding='utf-8'))
            if rates_changed:
                if had_rates:
                    atomic_write(rates_file, rates_bak.read_text(encoding='utf-8'))
                else:
                    rates_file.unlink(missing_ok=True)
            if gate_stale and gate_prev is not None:
                atomic_write(gate_file, gate_prev)

        if subprocess.call(['nginx', '-t']) != 0:
            rollback()
            raise SystemExit('nginx -t failed when applying Cogniva Nginx configuration')

        try:
            subprocess.check_call(['systemctl', 'reload', 'nginx'])
        except (subprocess.CalledProcessError, OSError) as exc:
            rollback()
            raise SystemExit('systemctl reload nginx failed when applying Cogniva Nginx configuration') from exc

        return True
    finally:
        tpl_path.unlink(missing_ok=True)
        if rates_tpl_path is not None:
            rates_tpl_path.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if not args:
        raise SystemExit('Usage: sync_nginx_site.py <site-template> [rate-limits-template]')
    tpl_path = pathlib.Path(args[0])
    rates_tpl_path = pathlib.Path(args[1]) if len(args) > 1 else None
    sync_nginx_site(tpl_path, rates_tpl_path)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
