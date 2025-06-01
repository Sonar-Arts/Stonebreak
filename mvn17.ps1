# This script sets up Java 17 and runs Maven with it
# To use: ./mvn17.ps1 clean package

# Set Java 17 environment
$Env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
$Env:PATH = "$Env:JAVA_HOME\bin;$Env:PATH"

# Display Java version being used
Write-Host "Using Java:" -ForegroundColor Cyan
& java -version
Write-Host ""

# Get the Maven command arguments
$MavenArgs = $args

# If no arguments provided, use "compile"
if ($MavenArgs.Count -eq 0) {
    $MavenArgs = @("compile")
}

# Run Maven with the provided arguments
Write-Host "Running: mvn $MavenArgs" -ForegroundColor Cyan
& mvn $MavenArgs

# Check exit code
if ($LASTEXITCODE -eq 0) {
    Write-Host "`nMaven build successful!" -ForegroundColor Green
} else {
    Write-Host "`nMaven build failed with exit code: $LASTEXITCODE" -ForegroundColor Red
}
