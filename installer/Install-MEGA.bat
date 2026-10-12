<# :
@echo off
title M.E.G.A Client installer
echo.
echo  M.E.G.A Client installer for Minecraft 1.21.11
echo  ----------------------------------------------
powershell -NoProfile -ExecutionPolicy Bypass -Command "iex ((Get-Content -LiteralPath '%~f0' -Raw))"
echo.
pause
exit /b
#>
# ---- PowerShell part (everything above is the double-click launcher) ----
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$MC = '1.21.11'
$Repo = 'aresrevision-prog/M.E.G.A'
$UA = @{ 'User-Agent' = 'MEGA-Installer' }

function Step($t) { Write-Host "  > $t" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "    $t" -ForegroundColor Green }
function Fail($t) { Write-Host ""; Write-Host "  ERROR: $t" -ForegroundColor Red; exit 1 }

try {
    # 1. Find .minecraft
    $mcDir = Join-Path $env:APPDATA '.minecraft'
    if (-not (Test-Path $mcDir)) {
        Fail "No .minecraft folder found at $mcDir. Install the Minecraft Launcher, start Minecraft $MC once, close it, then run this again."
    }
    Step "Minecraft folder: $mcDir"

    # 2. Close the launcher so it doesn't overwrite the new profile
    $running = Get-Process -Name 'MinecraftLauncher','Minecraft' -ErrorAction SilentlyContinue
    if ($running) {
        Write-Host "    The Minecraft Launcher is open. Close it, then press Enter." -ForegroundColor Yellow
        [void](Read-Host)
    }

    # 3. Fabric Loader (latest stable for 1.21.11)
    Step "Getting Fabric Loader for $MC"
    $loaders = Invoke-RestMethod -Uri "https://meta.fabricmc.net/v2/versions/loader/$MC" -Headers $UA
    $loader = ($loaders | Where-Object { $_.loader.stable } | Select-Object -First 1).loader.version
    if (-not $loader) { $loader = $loaders[0].loader.version }
    $verId = "fabric-loader-$loader-$MC"
    $verDir = Join-Path $mcDir "versions\$verId"
    New-Item -ItemType Directory -Force -Path $verDir | Out-Null
    $json = Invoke-WebRequest -UseBasicParsing -Uri "https://meta.fabricmc.net/v2/versions/loader/$MC/$loader/profile/json" -Headers $UA
    [IO.File]::WriteAllText((Join-Path $verDir "$verId.json"), $json.Content)
    Ok "Fabric Loader $loader installed"

    # 4. Separate game folder for the M.E.G.A profile (keeps your settings and server list)
    $gameDir = Join-Path $mcDir 'mega-1.21.11'
    New-Item -ItemType Directory -Force -Path $gameDir | Out-Null
    foreach ($f in 'options.txt', 'servers.dat') {
        $src = Join-Path $mcDir $f; $dst = Join-Path $gameDir $f
        if ((Test-Path $src) -and -not (Test-Path $dst)) { Copy-Item $src $dst }
    }
    Ok "Game folder: $gameDir (your other mods stay separate)"

    # 5. Launcher profile "M.E.G.A"
    Step "Adding the M.E.G.A profile to the launcher"
    $icon = 'Furnace'
    $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $added = 0
    foreach ($name in 'launcher_profiles.json', 'launcher_profiles_microsoft_store.json') {
        $file = Join-Path $mcDir $name
        if (-not (Test-Path $file)) {
            if ($name -ne 'launcher_profiles.json') { continue }
            '{"profiles":{},"settings":{},"version":3}' | Set-Content -Encoding UTF8 $file
        }
        Copy-Item $file "$file.mega-backup" -Force
        $data = Get-Content $file -Raw | ConvertFrom-Json
        if (-not $data.profiles) { $data | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{}) -Force }
        $prof = [pscustomobject]@{
            name = 'M.E.G.A'; type = 'custom'; created = $now; lastUsed = $now
            icon = $icon; lastVersionId = $verId; gameDir = $gameDir
        }
        $data.profiles | Add-Member -NotePropertyName 'mega-client' -NotePropertyValue $prof -Force
        $out = $data | ConvertTo-Json -Depth 32
        [IO.File]::WriteAllText($file, $out, (New-Object Text.UTF8Encoding($false)))
        $added++
    }
    Ok "Profile 'M.E.G.A' added ($added launcher file(s); backups saved as *.mega-backup)"

    # 5. Mod JAR (latest release)
    Step "Downloading the latest M.E.G.A Client"
    # Own game folder, so mods for other Minecraft versions in .minecraft\mods are never loaded with M.E.G.A
    Get-ChildItem (Join-Path $mcDir 'mods') -Filter 'mega-client-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
    $mods = Join-Path $gameDir 'mods'
    New-Item -ItemType Directory -Force -Path $mods | Out-Null
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $UA
    $asset = $rel.assets | Where-Object { $_.name -like 'mega-client-*.jar' -and $_.name -notlike '*-sources*' } | Select-Object -First 1
    if (-not $asset) { Fail "No mod JAR found in the latest release ($($rel.tag_name))." }
    Get-ChildItem $mods -Filter 'mega-client-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
    Invoke-WebRequest -UseBasicParsing -Uri $asset.browser_download_url -OutFile (Join-Path $mods $asset.name) -Headers $UA
    Ok "$($asset.name) -> $mods"

    # 6. Optional spawner texture pack
    $packs = Join-Path $gameDir 'resourcepacks'
    $ans = Read-Host "  > Also install the M.E.G.A spawner texture pack? (y/N)"
    if ($ans -match '^[yY]') {
        New-Item -ItemType Directory -Force -Path $packs | Out-Null
        Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/$Repo/main/resourcepacks/MEGA-Spawner-Texture.zip" -OutFile (Join-Path $packs 'MEGA-Spawner-Texture.zip') -Headers $UA
        Ok "Pack copied. Turn it on in Options > Resource Packs."
    }

    Write-Host ""
    Write-Host "  All done!" -ForegroundColor Green
    Write-Host "  1. Open the Minecraft Launcher."
    Write-Host "  2. Next to the Play button pick the 'M.E.G.A' profile, then press Play."
    Write-Host "  3. In game press 0 to open the M.E.G.A settings panel."
    Write-Host ""
    Write-Host "  Run this installer again any time to update to the newest version."
}
catch {
    Fail $_.Exception.Message
}
