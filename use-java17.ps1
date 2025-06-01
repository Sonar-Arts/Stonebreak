$Env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
$Env:PATH = "$Env:JAVA_HOME\bin;$Env:PATH"

Write-Host "Java environment set to Java 17:"
& "$Env:JAVA_HOME\bin\java" -version

Write-Host "JAVA_HOME set to: $Env:JAVA_HOME"

Write-Host "You can now run Maven and other Java tools using Java 17."

# Now try to run Maven with Java 17
Write-Host "`nChecking Maven with Java 17:"
mvn -version

# Keep the console open
Write-Host "`nPress Enter to exit..."
Read-Host
