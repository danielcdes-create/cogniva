$ErrorActionPreference = 'Stop'
$auditRepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$auditTempRoot = Join-Path ([IO.Path]::GetTempPath()) ('cogniva-audit-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $auditTempRoot | Out-Null
Copy-Item -LiteralPath (Join-Path $auditRepoRoot 'backend-rust\Cargo.toml') -Destination $auditTempRoot
Copy-Item -LiteralPath (Join-Path $auditRepoRoot 'backend-rust\Cargo.lock') -Destination $auditTempRoot
Copy-Item -LiteralPath (Join-Path $auditRepoRoot 'backend-rust\src') -Destination $auditTempRoot -Recurse
Add-Content -LiteralPath (Join-Path $auditTempRoot 'src\main.rs') -Value ([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'backend-probes.rs'))) -Encoding utf8
Write-Output ('Copia sintetica temporaria: ' + $auditTempRoot)
& cargo test --locked --manifest-path (Join-Path $auditTempRoot 'Cargo.toml') --target-dir (Join-Path $auditRepoRoot 'backend-rust\target') audit_probes -- --nocapture
exit $LASTEXITCODE
