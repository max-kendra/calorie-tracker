function Assert-Success($stepName) {
    if ($LASTEXITCODE -ne 0) {
        Write-Host "==> FAILED: $stepName (exit code $LASTEXITCODE) - stopping, nothing further was run." -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

$PiHost = "adytum.local"
$ImageName = "calorie-tracker:latest"
$RemoteDir = "/mnt/data/calorie-tracker"

Write-Host "==> Checking if Docker Desktop is running..."
docker info > $null 2>&1
Assert-Success "docker info (Docker Desktop doesn't appear to be running - start it first)"

Write-Host "==> Building $ImageName for linux/arm64..."
docker buildx build --platform linux/arm64 -t $ImageName --load .
Assert-Success "docker buildx build"

Write-Host "==> Saving image to a local tar file..."
$TarFile = "calorie-tracker-image.tar"
docker save -o $TarFile $ImageName
Assert-Success "docker save"

Write-Host "==> Shipping $TarFile to $PiHost..."

$RemoteTarPath = "$RemoteDir/deploy-tmp/$TarFile"
ssh $PiHost "mkdir -p $RemoteDir/deploy-tmp"
Assert-Success "ssh mkdir remote tmp dir"
scp $TarFile "${PiHost}:${RemoteTarPath}"
Assert-Success "scp image tar"

Write-Host "==> Loading image on $PiHost..."
ssh $PiHost "docker load -i $RemoteTarPath && rm $RemoteTarPath"
Assert-Success "ssh docker load"

Write-Host "==> Cleaning up local tar file..."
Remove-Item $TarFile

Write-Host "==> Copying docker-compose.yml to $PiHost..."

ssh $PiHost "mkdir -p $RemoteDir"
Assert-Success "ssh mkdir remote deploy dir"
scp docker-compose.yml "${PiHost}:${RemoteDir}/docker-compose.yml"
Assert-Success "scp docker-compose.yml"

Write-Host "==> Composing up on $PiHost..."
ssh $PiHost "cd $RemoteDir && docker compose up -d"
Assert-Success "ssh docker compose up -d (bring up new services)"

Write-Host "==> Done. If this deploy included a migration, remember to run it manually:"
Write-Host "    ssh $PiHost `"cd $RemoteDir && docker compose exec api alembic upgrade head`""