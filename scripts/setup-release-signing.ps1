param(
    [string]$Alias = "mediaanvil"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $projectRoot "mediaanvil-signing"
$keystorePath = Join-Path $signingDirectory "mediaanvil-release.jks"
$propertiesPath = Join-Path $projectRoot "signing.properties"
$secretsPath = Join-Path $signingDirectory "github-secrets.txt"

if (Test-Path -LiteralPath $keystorePath) {
    throw "Release keystore already exists: $keystorePath"
}

New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null

$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$password = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")

$keytool = (Get-Command keytool.exe -ErrorAction Stop).Source
$process = [System.Diagnostics.Process]::new()
$process.StartInfo.FileName = $keytool
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.RedirectStandardInput = $true
$process.StartInfo.RedirectStandardOutput = $true
$process.StartInfo.RedirectStandardError = $true
$process.StartInfo.ArgumentList.Add("-genkeypair")
$process.StartInfo.ArgumentList.Add("-keystore")
$process.StartInfo.ArgumentList.Add($keystorePath)
$process.StartInfo.ArgumentList.Add("-alias")
$process.StartInfo.ArgumentList.Add($Alias)
$process.StartInfo.ArgumentList.Add("-keyalg")
$process.StartInfo.ArgumentList.Add("RSA")
$process.StartInfo.ArgumentList.Add("-keysize")
$process.StartInfo.ArgumentList.Add("4096")
$process.StartInfo.ArgumentList.Add("-validity")
$process.StartInfo.ArgumentList.Add("10000")
$process.StartInfo.ArgumentList.Add("-storetype")
$process.StartInfo.ArgumentList.Add("JKS")
$process.StartInfo.ArgumentList.Add("-dname")
$process.StartInfo.ArgumentList.Add("CN=MediaAnvil, OU=Release, O=iMankoppai, C=CN")

if (-not $process.Start()) {
    throw "Unable to start keytool"
}
$process.StandardInput.WriteLine($password)
$process.StandardInput.WriteLine($password)
$process.StandardInput.WriteLine("")
$process.StandardInput.Close()
$stdout = $process.StandardOutput.ReadToEnd()
$stderr = $process.StandardError.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -ne 0) {
    throw "keytool failed: $stdout $stderr"
}

$relativeKeystore = "../mediaanvil-signing/mediaanvil-release.jks"
@"
storeFile=$relativeKeystore
storePassword=$password
keyAlias=$Alias
keyPassword=$password
"@ | Set-Content -LiteralPath $propertiesPath -Encoding utf8NoBOM

$keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
@"
ANDROID_KEYSTORE_BASE64=$keystoreBase64
ANDROID_KEYSTORE_PASSWORD=$password
ANDROID_KEY_ALIAS=$Alias
ANDROID_KEY_PASSWORD=$password
"@ | Set-Content -LiteralPath $secretsPath -Encoding ascii

Write-Output "Release signing created."
Write-Output "Keystore: $keystorePath"
Write-Output "GitHub Secrets file: $secretsPath"
Write-Output "Back up the entire mediaanvil-signing directory before publishing."
