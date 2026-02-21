@echo off
REM Zapusk DeepSeek Web Interface v brauzere

echo.
echo ========================================
echo   DeepSeek Web Interface Launcher
echo ========================================
echo.

REM Proveryaem nalichie API klyucha
set HAS_DEEPSEEK=0

if not "%DEEPSEEK_API_KEY%"=="" (
    set HAS_DEEPSEEK=1
    echo [OK] DeepSeek API key found
)

if %HAS_DEEPSEEK%==0 (
    echo.
    echo [ERROR] API key not set!
    echo.
    echo Set the DeepSeek API key:
    echo.
    echo For DeepSeek:
    echo   setx DEEPSEEK_API_KEY "your_deepseek_api_key"
    echo.
    echo After setting, RESTART your command prompt or IDE!
    echo.
    echo Or use: setup-api-keys.bat
    echo.
    timeout /t 10
    exit /b 1
)

echo.

REM Proveryaem nalichie jar faila
if not exist "target\deepseek-cli-1.0.0.jar" (
    echo [BUILD] JAR file not found. Building project...
    echo.
    call mvn clean package -DskipTests
    if errorlevel 1 (
        echo.
        echo [ERROR] Build failed!
        echo Check that you have:
        echo   - Java 17 or higher
        echo   - Maven 3.6 or higher
        echo.
        timeout /t 10
        exit /b 1
    )
    echo.
    echo [OK] Build completed successfully!
    echo.
)

echo [START] Starting web server...
echo.
echo Press Ctrl+C to stop the server
echo.

java -Dfile.encoding=UTF-8 -cp target\deepseek-cli-1.0.0.jar com.example.deepseek.app.WebApp %1

echo.
echo [EXIT] Server stopped
echo.
