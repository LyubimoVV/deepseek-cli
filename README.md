# DeepSeek CLI

Интерактивный CLI-клиент для DeepSeek API на Java 17.

## Требования

- Java 17 или выше
- Maven 3.6+
- API-ключ DeepSeek

## Установка

### 1. Клонирование репозитория

```bash
git clone <repository-url>
cd deepseek-cli
```

### 2. Настройка API-ключа

Установите переменную окружения `DEEPSEEK_API_KEY`:

**Windows (CMD):**
```cmd
set DEEPSEEK_API_KEY=your_api_key_here
```

**Windows (PowerShell):**
```powershell
$env:DEEPSEEK_API_KEY="your_api_key_here"
```

**Linux/macOS:**
```bash
export DEEPSEEK_API_KEY=your_api_key_here
```

### 3. Сборка проекта

```bash
mvn clean package
```

## Запуск

### Через Maven

```bash
mvn exec:java -Dexec.mainClass="com.example.deepseek.app.CliApp"
```

### Через JAR-файл

```bash
java -jar target/deepseek-cli-1.0.0.jar
```

## Использование

После запуска вы увидите приглашение `>`. Введите ваш запрос и нажмите Enter.

### Команды

| Команда | Описание |
|---------|----------|
| `/exit` | Выход из приложения |
| `/clear` | Очистить историю диалога |
| `/help` | Показать справку по командам |

### Пример сессии

```
DeepSeek CLI Client
Type your message and press Enter.
Commands:
  /exit  - Exit the application
  /clear - Clear conversation history
  /help  - Show this help message

> Hello, how are you?
Thinking...

Hello! I'm doing well, thank you for asking. How can I help you today?

> /exit
Goodbye!
```

## Структура проекта

```
deepseek-cli/
├── pom.xml
├── README.md
└── src/main/java/com/example/deepseek/
    ├── app/
    │   └── CliApp.java           # Точка входа, CLI логика
    ├── client/
    │   └── DeepSeekClient.java   # HTTP-клиент для API
    └── dto/
        ├── ChatRequest.java      # DTO запроса
        ├── ChatResponse.java     # DTO ответа
        ├── Choice.java           # DTO выбора ответа
        ├── Message.java          # DTO сообщения
        └── ResponseMessage.java  # DTO ответа ассистента
```

## Обработка ошибок

Приложение обрабатывает следующие типы ошибок:

- **API Error** — ошибки от DeepSeek API (неверный ключ, лимиты и т.д.)
- **Network Error** — проблемы с сетевым соединением
- **Timeout** — превышено время ожидания ответа (60 секунд)

## Лицензия

MIT
