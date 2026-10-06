param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$taskBackendRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskMavenRoot = Join-Path $env:USERPROFILE '.m2\repository'
$taskArtifacts = @('org\postgresql\postgresql','com\fasterxml\jackson\core\jackson-databind','com\fasterxml\jackson\core\jackson-core','com\fasterxml\jackson\core\jackson-annotations')
$taskJars = foreach ($taskArtifact in $taskArtifacts) {
    $taskJar = Get-ChildItem -LiteralPath (Join-Path $taskMavenRoot $taskArtifact) -Recurse -File -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $taskJar) { throw "Missing runtime library: $taskArtifact" }
    $taskJar.FullName
}
Push-Location -LiteralPath $taskBackendRoot
try {
    $taskArguments = @('--class-path', ($taskJars -join ';'), (Join-Path $PSScriptRoot 'MigrateSupabaseUsers.java'))
    if ($Apply) { $taskArguments += '--apply' }
    & java @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Migration did not finish. Inspect status before retry.' }
} finally { Pop-Location }
