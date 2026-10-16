param(
    [string]$IdeaLibPath = "E:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2\lib",
    [string]$JavacPath = "E:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2\jbr\bin\javac.exe"
)

$ErrorActionPreference = 'Stop'
$baseDir = $PSScriptRoot
$outDir = Join-Path $baseDir "out"
$distDir = Join-Path $baseDir "dist"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
Remove-Item "$outDir\*" -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item -Path "$baseDir\resources\META-INF" -Destination "$outDir\META-INF" -Recurse -Force

& $JavacPath -encoding UTF-8 --release 17 -cp "$IdeaLibPath\*" -d $outDir `
    "$baseDir\src\com\devtool\plugin\loghub\MicroserviceLogToolWindowFactory.java" `
    "$baseDir\src\com\devtool\plugin\loghub\MicroserviceLogPanel.java"

$jarFile = Join-Path $distDir "microservice-log-hub-1.1.0.jar"
jar cf $jarFile -C $outDir .
Write-Host "Build succeeded: $jarFile" -ForegroundColor Green
