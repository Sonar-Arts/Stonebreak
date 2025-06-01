# Set up Java 17 environment variables for Stonebreak development

# Set Java 17 path
$Env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
$Env:PATH = "$Env:JAVA_HOME\bin;$Env:PATH"

Write-Host "Java environment set to Java 17:"
java -version

Write-Host "JAVA_HOME set to: $Env:JAVA_HOME"

Write-Host "You can now run Maven and other Java tools using Java 17."
# Keep the PowerShell window open
Write-Host "`nPress any key to continue..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
