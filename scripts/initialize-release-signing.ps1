$ErrorActionPreference='Stop'
$signingDir=Join-Path $env:LOCALAPPDATA 'Cogniva\release-signing'
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
    # minSdk 28. Production signing uses only the private release key.
    # This script does not build a lineage and does not upload one.
    $gh='C:\Program Files\GitHub CLI\gh.exe'
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyFile)) | & $gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 --repo danielcdes-create/cogniva
    if($LASTEXITCODE -ne 0){throw 'Falha ao salvar keystore no GitHub Secrets'}
    $env:COGNIVA_RELEASE_PASSWORD | & $gh secret set ANDROID_RELEASE_PASSWORD --repo danielcdes-create/cogniva
    if($LASTEXITCODE -ne 0){throw 'Falha ao salvar senha no GitHub Secrets'}
    Write-Output "Chave privada e senha DPAPI preservadas em $signingDir. Segredos de CI configurados."
}finally{Remove-Item Env:\COGNIVA_RELEASE_PASSWORD -ErrorAction SilentlyContinue}
