function Assert-Success($stepName) {
    if ($LASTEXITCODE -ne 0) {
        Write-Host "==> FAILED: $stepName (exit code $LASTEXITCODE) - stopping, nothing further was run." -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

$PiHost = "adytum.local"
$RemoteDir = "/mnt/data/calorie-tracker"
$WorkflowFile = "build.yml"

Write-Host "==> Checking gh CLI is installed and authenticated..."
gh auth status > $null 2>&1
Assert-Success "gh auth status (run 'gh auth login' first if this is a fresh machine)"

Write-Host "==> Triggering the build workflow on GitHub Actions..."
gh workflow run $WorkflowFile
Assert-Success "gh workflow run"

# gh workflow run only QUEUES the run and returns immediately - a
# short pause here before listing runs avoids a race where the new run
# hasn't been registered yet and this grabs the PREVIOUS run's id
# instead (this exact race, and this exact mitigation, shows up in
# other projects' own release-automation scripts too - not a made-up
# concern).
Write-Host "==> Waiting a moment for the run to register..."
Start-Sleep -Seconds 5

Write-Host "==> Finding the run that was just queued..."
$RunId = gh run list --workflow $WorkflowFile --limit 1 --json databaseId --jq ".[0].databaseId"
Assert-Success "gh run list"
if ([string]::IsNullOrWhiteSpace($RunId)) {
    Write-Host "==> FAILED: couldn't find the queued run - check the Actions tab directly." -ForegroundColor Red
    exit 1
}
Write-Host "==> Run ID: $RunId"

Write-Host "==> Watching the build (this is the real build - can take a few minutes)..."
gh run watch $RunId --exit-status
Assert-Success "gh run watch (build failed - check the run's logs in the Actions tab)"

Write-Host "==> Build succeeded. Copying docker-compose.yml to $PiHost (in case it changed)..."
ssh $PiHost "mkdir -p $RemoteDir"
Assert-Success "ssh mkdir remote deploy dir"
scp docker-compose.yml "${PiHost}:${RemoteDir}/docker-compose.yml"
Assert-Success "scp docker-compose.yml"

Write-Host "==> Pulling the new image and restarting on $PiHost..."
# Explicit pull + --force-recreate, not just relying on pull_policy:
# always alone - the same reasoning as always in this project: Compose
# reusing a tag (:latest) doesn't reliably notice on its own that a
# NEWER image was pulled under that same tag, so both steps stay
# explicit rather than trusting one setting to cover it.
ssh $PiHost "cd $RemoteDir && docker compose pull api && docker compose up -d --force-recreate api"
Assert-Success "ssh docker compose pull && up -d --force-recreate"

Write-Host "==> Done. If this deploy included a migration, remember to run it manually:"
Write-Host "    ssh $PiHost `"cd $RemoteDir && docker compose exec api alembic upgrade head`""