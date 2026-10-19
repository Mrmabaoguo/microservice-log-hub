param(
    [string]$IdeaLibPath = "E:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2\lib",
    [string]$JavacPath = "E:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2\jbr\bin\javac.exe"
)

$ErrorActionPreference = 'Stop'
$baseDir = $PSScriptRoot
$outDir = Join-Path $baseDir "out"
$distDir = Join-Path $baseDir "dist"
$desktopDir = "D:\Desktop"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

# 自动从 plugin.xml 读取最新版本号，彻底杜绝文件名版本号不一致
$rawXml = Get-Content "$baseDir\resources\META-INF\plugin.xml" -Raw -Encoding UTF8
if ($rawXml -match '<version>([^<]+)</version>') {
    $pluginVersion = $Matches[1].Trim()
} else {
    $pluginVersion = "1.3.0"
}

# 清理 dist 与桌面上旧版本的 jar 包
Remove-Item "$distDir\microservice-log-hub-*.jar" -Force -ErrorAction SilentlyContinue
Remove-Item "$desktopDir\microservice-log-hub-*.jar" -Force -ErrorAction SilentlyContinue

function Build-PluginVariant {
    param(
        [string]$ReleaseVersion,
        [string]$SinceBuild,
        [string]$JarFileName
    )

    Remove-Item "$outDir\*" -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$outDir\META-INF" | Out-Null

    $pluginXml = $rawXml -replace 'since-build="[^"]+"', "since-build=`"$SinceBuild`""
    Set-Content -Path "$outDir\META-INF\plugin.xml" -Value $pluginXml -Encoding UTF8 -NoNewline

    & $JavacPath -encoding UTF-8 --release $ReleaseVersion -cp "$IdeaLibPath\*" -d $outDir `
        "$baseDir\src\com\devtool\plugin\loghub\MicroserviceLogToolWindowFactory.java" `
        "$baseDir\src\com\devtool\plugin\loghub\MicroserviceLogPanel.java"

    $distJar = Join-Path $distDir $JarFileName
    $desktopJar = Join-Path $desktopDir $JarFileName
    jar cf $distJar -C $outDir .
    Copy-Item $distJar $desktopJar -Force
    Write-Host "Built: $desktopJar" -ForegroundColor Green
}

# 1. 构建 2022.3 及以后版本 (Java 17+, since-build 223.0)
Build-PluginVariant -ReleaseVersion "17" -SinceBuild "223.0" -JarFileName "microservice-log-hub-$pluginVersion-IDEA2022.3及以后.jar"

# 2. 构建 2022.2 及以下老版本 (Java 11+, since-build 201.0)
Build-PluginVariant -ReleaseVersion "11" -SinceBuild "201.0" -JarFileName "microservice-log-hub-$pluginVersion-IDEA2022.2及以下老版本.jar"
