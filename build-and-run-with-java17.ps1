# Run this script to build and run Stonebreak with Java 17

# Set Java 17 environment
$Env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
$Env:PATH = "$Env:JAVA_HOME\bin;$Env:PATH"

# Verify Java version
Write-Host "Using Java version:" -ForegroundColor Cyan
& java -version
Write-Host ""

# Run Maven build
Write-Host "Building with Maven..." -ForegroundColor Cyan
& mvn clean package -Dmaven.test.skip=true

# Check if build was successful
if ($LASTEXITCODE -eq 0) {
    Write-Host "`nBuild successful! Running Stonebreak..." -ForegroundColor Green
    
    # Run the application
    & java -jar target\stonebreak-1.0-SNAPSHOT.jar
}
else {
    Write-Host "`nBuild failed with exit code: $LASTEXITCODE" -ForegroundColor Red
    
    # Check for common Java version issues
    Write-Host "`nChecking for common issues:" -ForegroundColor Yellow
    
    $sourceFiles = Get-ChildItem -Path src\main\java -Recurse -Filter "*.java" | Where-Object { $_.FullName -match "\.java$" }
    $foundIssues = $false
    
    # Look for Java 17 features
    foreach ($file in $sourceFiles) {
        $content = Get-Content $file.FullName -Raw
        
        if ($content -match '"""') {
            Write-Host "  - Found text blocks in $($file.Name) which require Java 17+" -ForegroundColor Yellow
            $foundIssues = $true
        }
        
        if ($content -match '\bswitch\s*\([^)]+\)\s*{') {
            if ($content -match '\bswitch\s*\([^)]+\)\s*\{[^}]*->') {
                Write-Host "  - Found switch expressions in $($file.Name) which require Java 14+" -ForegroundColor Yellow
                $foundIssues = $true
            }
        }
        
        if ($content -match 'record\s+\w+\s*\(') {
            Write-Host "  - Found record classes in $($file.Name) which require Java 16+" -ForegroundColor Yellow
            $foundIssues = $true
        }
    }
    
    if (-not $foundIssues) {
        Write-Host "  - No obvious Java version issues found in source files." -ForegroundColor Yellow
    }
    
    Write-Host "`nMake sure Maven is using Java 17:" -ForegroundColor Yellow
    Write-Host "  - Check 'mvn -version' output" -ForegroundColor Yellow
    Write-Host "  - Check JAVA_HOME environment variable" -ForegroundColor Yellow
}

Write-Host "`nPress Enter to exit..."
Read-Host
