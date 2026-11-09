param(
    [Parameter(Mandatory = $true)][string]$Backup,
    [string]$ProtocolReference = 'D:/project/legado/app/src/main/java/io/legado/app/service/OnlineTtsBackendRegistry.kt',
    [string]$JavaHome = 'D:/jdk-17.0.2',
    [switch]$Live
)
$ErrorActionPreference = 'Stop'
$auditRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
Push-Location $auditRoot
try {
    $auditJars = @(
        'third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.4-android21.1/htmlunit-core-js-5.3.0-legado.4-android21.1.jar',
        '.gradle_home/caches/modules-2/files-2.1/com.google.code.gson/gson/2.13.2/48b8230771e573b54ce6e867a9001e75977fe78e/gson-2.13.2.jar',
        '.gradle_home/caches/modules-2/files-2.1/cn.hutool/hutool-crypto/5.8.22/94c690a59d9d995eb3b5a784580d7d8cf21fb4e3/hutool-crypto-5.8.22.jar',
        '.gradle_home/caches/modules-2/files-2.1/cn.hutool/hutool-core/5.8.22/66d9b0fed298d2883c7e4d9fc8b7e116f1579373/hutool-core-5.8.22.jar',
        'C:/Users/Administrator/.gradle/caches/modules-2/files-2.1/org.yaml/snakeyaml/2.6/2bc14918a2f8d5414749ab12d0c590cd3198b8c1/snakeyaml-2.6.jar'
    )
    foreach ($jar in $auditJars) {
        if (-not (Test-Path -LiteralPath $jar)) { throw "Missing local audit dependency: $jar" }
    }
    $auditArgs = @('-Dfile.encoding=UTF-8', '--class-path', ($auditJars -join ';'),
        'docs/audits/multitts/XfBackupAudit.java', $Backup, $ProtocolReference)
    if ($Live) { $auditArgs += '--live' }
    & (Join-Path $JavaHome 'bin/java.exe') @auditArgs
    if ($LASTEXITCODE -ne 0) { throw "Audit exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
