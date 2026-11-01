param([switch]$UpdateOldCapabilities)
$ErrorActionPreference='Stop'
$signingDir=Join-Path $env:LOCALAPPDATA 'Cogniva\release-signing'
$repoRoot=Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
& icacls $signingDir /inheritance:r /grant:r "${env:USERNAME}:(OI)(CI)F" | Out-Null
if($LASTEXITCODE -ne 0){throw 'Falha ao restringir acesso à chave'}
$keyFile=Join-Path $signingDir 'cogniva-release.p12'
$passwordFile=Join-Path $signingDir 'password.dpapi.xml'
if(-not(Test-Path -LiteralPath $keyFile)){
    $bytes=New-Object byte[] 48
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $password=[Convert]::ToBase64String($bytes)
    ConvertTo-SecureString $password -AsPlainText -Force | Export-Clixml -LiteralPath $passwordFile
    $env:COGNIVA_RELEASE_PASSWORD=$password
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $keyFile -storetype PKCS12 -storepass:env COGNIVA_RELEASE_PASSWORD -alias cogniva -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Cogniva Release, O=Cogniva, C=BR'
    if($LASTEXITCODE -ne 0){throw 'Falha ao gerar chave de release'}
}else{
    $secure=Import-Clixml -LiteralPath $passwordFile
    $env:COGNIVA_RELEASE_PASSWORD=[Net.NetworkCredential]::new('',$secure).Password
}
try {
    $signer=Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat'
    $lineage=Join-Path $signingDir 'cogniva.lineage'
    if(-not(Test-Path -LiteralPath $lineage) -or $UpdateOldCapabilities){
        if(Test-Path -LiteralPath $lineage){Copy-Item -LiteralPath $lineage -Destination ($lineage+'.before-'+(Get-Date -Format 'yyyyMMddTHHmmss'))}
        & $signer rotate --out $lineage --old-signer --ks (Join-Path $repoRoot 'android-app\app\debug.keystore') --ks-key-alias androiddebugkey --ks-pass pass:android --set-installed-data true --set-permission false --set-auth false --set-shared-uid false --set-rollback false --new-signer --ks $keyFile --ks-key-alias cogniva --ks-pass env:COGNIVA_RELEASE_PASSWORD
        if($LASTEXITCODE -ne 0){throw 'Falha ao gerar prova de rotação'}
    }
    $gh='C:\Program Files\GitHub CLI\gh.exe'
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyFile)) | & $gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 --repo danielcdes-create/cogniva
    if($LASTEXITCODE -ne 0){throw 'Falha ao salvar keystore no GitHub Secrets'}
    $env:COGNIVA_RELEASE_PASSWORD | & $gh secret set ANDROID_RELEASE_PASSWORD --repo danielcdes-create/cogniva
    if($LASTEXITCODE -ne 0){throw 'Falha ao salvar senha no GitHub Secrets'}
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($lineage)) | & $gh secret set ANDROID_SIGNING_LINEAGE_BASE64 --repo danielcdes-create/cogniva
    if($LASTEXITCODE -ne 0){throw 'Falha ao salvar lineage no GitHub Secrets'}
    Write-Output "Chave privada e senha DPAPI preservadas em $signingDir. Segredos de CI configurados."
}finally{Remove-Item Env:\COGNIVA_RELEASE_PASSWORD -ErrorAction SilentlyContinue}
