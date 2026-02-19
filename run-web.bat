@echo off
REM Запуск DeepSeek Web Interface в браузере

echo.
echo ========================================
echo   DeepSeek Web Interface Launcher
echo ========================================
echo.

REM Проверяем наличие API ключа
if "%DEEPSEEK_API_KEY%"=="" (
    echo ОШИБКА: Не установлена переменная окружения DEEPSEEK_API_KEY
    echo.
    echo Установите её командой:
    echo   set DEEPSEEK_API_KEY=your_api_key_here
    echo.
    echo Или добавьте в переменные среды Windows.
    echo.
    pause
    exit /b 1
)

REM Проверяем наличие jar файла
if not exist "target\deepseek-cli-1.0.0.jar" (
    echo JAR файл не найден. Собираем проект...
    echo.
    mvn clean package -q
    if %errorlevel% neq 0 (
        echo.
        echo ОШИБКА: Сборка не удалась!
        pause
        exit /b 1
    )
    echo.
    echo Сборка завершена успешно!
    echo.
)

echo Запускаем веб-сервер...
echo.
java -Dfile.encoding=UTF-8 -cp target\deepseek-cli-1.0.0.jar com.example.deepseek.app.WebApp %1