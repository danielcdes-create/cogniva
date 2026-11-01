param([string]$AuditJavaHome = 'C:\Users\danie\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2')
$ErrorActionPreference = 'Stop'
$auditRepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$env:JAVA_HOME = $AuditJavaHome
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$auditSources = (Join-Path $PSScriptRoot 'android-probes').Replace('\', '/')
Push-Location (Join-Path $auditRepoRoot 'android-app')
try {
    & .\gradlew.bat verifyPaparazziDebug lintDebug assembleDebug --continue --no-daemon --console=plain -I (Join-Path $PSScriptRoot 'audit.init.gradle') "-PcognivaAuditSources=$auditSources"
    $auditExit = $LASTEXITCODE
} finally { Pop-Location }
exit $auditExit
