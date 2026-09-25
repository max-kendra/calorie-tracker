# Builds the image off-Pi and ships the FINISHED image over, rather
# than building on the Pi itself - see docker-compose.yml's own
# comment on the api service for why: poetry installing torch +
# compiling zxing-cpp from source (no prebuilt ARM64 wheel) is heavy
# enough that it crashed the Pi outright once (SIGBUS, likely
# disk-space or memory exhaustion under sustained build load). The Pi
# now only ever RUNS a container, never builds one.
#
# Run this from the calorie-tracker repo root (same directory the
# Dockerfile lives in - it's the build context, same as always) on
# your dev machine, with calorie-tracker AND the nested
# calorie-tracker-web both already pulled up to date first - this
# script builds from whatever's on disk right now, it doesn't pull
# either repo itself.
#
# Requires Docker Desktop (buildx + the QEMU emulation for arm64
# cross-builds are bundled in by default - no separate setup needed,
# but Docker Desktop itself needs to actually be RUNNING - see the
# explicit check below, since a failed native command here doesn't
# stop the script on its own).
#
# Passwordless SSH: set up an SSH key and copy it to the Pi's
# authorized_keys once (see design discussion) - once done, none of
# the ssh/scp calls below prompt for a password.
#
# First build after switching to this script will still take a while
# (x86_64 -> arm64 is emulated, not native, so it's not necessarily
# FASTER wall-clock than building on the Pi was) - the actual win here
# is that your dev machine has enough RAM/disk to not crash doing it,
# not raw speed. Docker's own layer caching still applies normally
# after that first build, same as it always did.

$ErrorActionPreference = "Stop"

# $ErrorActionPreference only stops PowerShell-native exceptions/cmdlet
# errors - it does NOT stop this script when an external .exe (docker,
# ssh, scp) exits with a failure code, which is exactly what happened
# before: the buildx build failed outright (Docker Desktop wasn't
# running), but the script kept going anyway - piping a failed build's
# nonexistent/garbage output into `docker save | ssh ... docker load`
# ("unrecognized image format"), then still copying the compose file
# and restarting the Pi's containers regardless. This helper makes
# every native-command failure below actually halt the script, the
# same way a PowerShell-native error already would.
function Assert-Success($stepName) {
    if ($LASTEXITCODE -ne 0) {
        Write-Host "==> FAILED: $stepName (exit code $LASTEXITCODE) - stopping, nothing further was run." -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

$PiHost = "adytum.local"
$ImageName = "calorie-tracker:latest"
$RemoteDir = "~/calorie-tracker"

Write-Host "==> Checking Docker Desktop is actually running..."
docker info > $null 2>&1
Assert-Success "docker info (Docker Desktop doesn't appear to be running - start it first)"

Write-Host "==> Building $ImageName for linux/arm64 (this can take a while - emulated, not native)..."
docker buildx build --platform linux/arm64 -t $ImageName --load .
Assert-Success "docker buildx build"

Write-Host "==> Shipping image to $PiHost..."
docker save $ImageName | ssh $PiHost docker load
Assert-Success "docker save | ssh docker load"

Write-Host "==> Copying docker-compose.yml to $PiHost (in case it changed)..."
scp docker-compose.yml "${PiHost}:${RemoteDir}/docker-compose.yml"
Assert-Success "scp docker-compose.yml"

Write-Host "==> Restarting on $PiHost (no --build - uses the image just loaded)..."
ssh $PiHost "cd $RemoteDir && docker compose up -d"
Assert-Success "ssh docker compose up -d"

Write-Host "==> Done. If this deploy included a migration, remember to run it manually:"
Write-Host "    ssh $PiHost `"cd $RemoteDir && docker compose exec api alembic upgrade head`""