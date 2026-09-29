param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ApplicationArgs
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSCommandPath
$jar = Join-Path $project 'target/invoice-manager-1.5.12.jar'
if (!(Test-Path -LiteralPath $jar)) {
    throw "Application JAR not found: $jar. Build the project first."
}

$jdk = Get-ChildItem -LiteralPath 'D:/Program Files/Java' -Directory -Filter 'jdk-25*' |
    Sort-Object Name -Descending | Select-Object -First 1
if ($null -eq $jdk) { throw 'JDK 25 was not found under D:/Program Files/Java.' }
$java = Join-Path $jdk.FullName 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "Java executable not found: $java" }

# Maven replaces target/*.jar during a build. Run a verified, immutable copy instead.
$sourceHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
$cacheDirectory = Join-Path $project 'work/run-cache'
New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null
$snapshot = Join-Path $cacheDirectory ("invoice-manager-1.5.12-$sourceHash.jar")
if (!(Test-Path -LiteralPath $snapshot)) {
    $temporary = Join-Path $cacheDirectory ("copy-$([guid]::NewGuid()).tmp")
    try {
        Copy-Item -LiteralPath $jar -Destination $temporary
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $sourceHash) {
            throw 'The JAR changed while it was being copied. Wait for the build to finish and try again.'
        }
        Move-Item -LiteralPath $temporary -Destination $snapshot
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}
if ((Get-FileHash -LiteralPath $snapshot -Algorithm SHA256).Hash -ne $sourceHash -or
    (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $sourceHash) {
    throw 'The application JAR changed during launch. Wait for the build to finish and try again.'
}

Push-Location $project
try {
    & $java --enable-native-access=ALL-UNNAMED -jar $snapshot @ApplicationArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
