param(
    [switch] $Gui,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ApplicationArgs
)

$ErrorActionPreference = 'Stop'
function Get-ContentHash([string] $path) {
    $stream = [System.IO.File]::OpenRead($path)
    try {
        $algorithm = [System.Security.Cryptography.SHA256]::Create()
        try { return [System.BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '') }
        finally { $algorithm.Dispose() }
    } finally { $stream.Dispose() }
}

$project = Split-Path -Parent $PSCommandPath
$target = Join-Path $project 'target'
if (!(Test-Path -LiteralPath $target -PathType Container)) {
    throw 'Application JAR not found. Build the project first.'
}
$jar = Get-ChildItem -LiteralPath $target -File -Filter '*.jar' |
    Where-Object { $_.Name -eq 'invoce-manager.jar' -or
        $_.Name -match '^(?:invoce|invoice)-manager-.+\.jar$' } |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($null -eq $jar) {
    throw 'Application JAR not found. Build the project first.'
}

$jdk = Get-ChildItem -LiteralPath 'D:/Program Files/Java' -Directory -Filter 'jdk-25*' |
    Sort-Object Name -Descending | Select-Object -First 1
if ($null -eq $jdk) { throw 'JDK 25 was not found under D:/Program Files/Java.' }
$javaBinary = if ($Gui) { 'javaw.exe' } else { 'java.exe' }
$java = Join-Path $jdk.FullName "bin/$javaBinary"
if (!(Test-Path -LiteralPath $java)) { throw "Java executable not found: $java" }

# Maven replaces target/*.jar during a build. Run a verified, immutable copy instead.
$sourceHash = Get-ContentHash $jar.FullName
$cacheDirectory = Join-Path $project 'work/run-cache'
New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null
$snapshot = Join-Path $cacheDirectory ("$($jar.BaseName)-$sourceHash.jar")
if (!(Test-Path -LiteralPath $snapshot)) {
    $temporary = Join-Path $cacheDirectory ("copy-$([guid]::NewGuid()).tmp")
    try {
        Copy-Item -LiteralPath $jar.FullName -Destination $temporary
        if ((Get-ContentHash $temporary) -ne $sourceHash) {
            throw 'The JAR changed while it was being copied. Wait for the build to finish and try again.'
        }
        Move-Item -LiteralPath $temporary -Destination $snapshot
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}
if ((Get-ContentHash $snapshot) -ne $sourceHash -or
    (Get-ContentHash $jar.FullName) -ne $sourceHash) {
    throw 'The application JAR changed during launch. Wait for the build to finish and try again.'
}

Push-Location $project
try {
    & $java --enable-native-access=ALL-UNNAMED -jar $snapshot @ApplicationArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
