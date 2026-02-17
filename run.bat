@echo off
echo Starting DeepSeek CLI...
echo.

REM Проверяем наличие jar файла
if not exist "target\deepseek-cli-1.0.0.jar" (
    echo JAR file not found. Building project...
    mvn clean package -q
    if %errorlevel% neq 0 (
        echo Build failed!
        pause
        exit /b 1
    )
    echo Build completed.
    echo.
)

java -jar target\deepseek-cli-1.0.0.jar
pause
