param([ValidateSet('Debug','Release')][string]$Variant='Debug')
$ErrorActionPreference='Stop'
if(-not $env:JAVA_HOME){throw 'Defina JAVA_HOME para o Temurin 21 documentado antes do build.'}
$env:ANDROID_HOME=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat "assemble$Variant" --console=plain
    if($LASTEXITCODE -ne 0){throw "Gradle falhou: $LASTEXITCODE"}
    $variantDir=$Variant.ToLowerInvariant()
    $output=Join-Path $PSScriptRoot "app\build\outputs\apk\$variantDir"
    $metadataPath=Join-Path $output 'output-metadata.json'
    $metadata=Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $element=$metadata.elements[0]
    if(-not $element.versionCode -or -not $element.versionName){throw 'Metadados de versão inválidos'}
    $apk=Join-Path $output $element.outputFile
    if(-not(Test-Path -LiteralPath $apk)){throw 'APK não encontrado'}
    $dist=Join-Path $PSScriptRoot "dist\$($element.versionName)-$($element.versionCode)-$variantDir"
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    Copy-Item -LiteralPath $apk -Destination (Join-Path $dist $element.outputFile) -Force
    Copy-Item -LiteralPath $metadataPath -Destination (Join-Path $dist 'output-metadata.json') -Force
    [ordered]@{version_code=$element.versionCode;version_name=$element.versionName;size_bytes=(Get-Item -LiteralPath $apk).Length;sha256_checksum=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant();variant=$Variant;signed_release=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dist 'build-info.json') -Encoding utf8
    Write-Output "Build local: $dist. A publicação usa exclusivamente o pipeline de promoção validado."
}finally{Pop-Location}
