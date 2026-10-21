param([string]$Clang = 'clang')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
foreach ($firmwareName in @('disk-test-v1', 'importer-test-v1', 'ttd-test-v1', 'importer-ttd-test-v1')) {
$firmwareSource = Join-Path $projectRoot "docs/firmware/$firmwareName.c"
$firmwareOutput = Join-Path $projectRoot "src/main/resources/data/openwac/firmware/$firmwareName.wasm"
& $Clang --target=wasm32-unknown-unknown -Oz -std=c11 -nostdlib -ffreestanding -fno-builtin `
    '-Wl,--no-entry' '-Wl,--export=_start' '-Wl,--allow-undefined' '-Wl,--strip-all' `
    '-Wl,--initial-memory=65536' '-Wl,--max-memory=65536' '-Wl,-z,stack-size=16384' `
    $firmwareSource -o $firmwareOutput
if ($LASTEXITCODE -ne 0) { throw "Firmware compilation failed ($LASTEXITCODE)" }
Write-Output "Compiled $firmwareOutput"
}
