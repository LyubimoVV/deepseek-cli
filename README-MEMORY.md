# Реализация модели памяти с 3 слоями

## Обзор

Реализована многослойная модель памяти для AI ассистента с явным разделением данных по типам:

### Слои памяти

| Слой | Описание | Хранилище | Метод доступа |
|------|----------|-----------|--------------|
| SHORT_TERM | Текущий диалог (сообщения) | `messages` таблица | Автоматически |
| WORKING | Данные текущей задачи | `working_memory` | Явно через UI |
| LONG_TERM | Профиль, знания | `long_term_memory` | Явно через UI |

---

## Backend

### Созданные файлы

**База данных:**
- `DatabaseConfig.java` - миграция режимов в профили

**DTO:**
- `MemoryRequest.java` - запрос для сохранения в память
- `ProfileRequest.java` - запрос для профилей

**Repository:**
- `ProfileRepository.java` (интерфейс)
- `ProfileRepositoryImpl.java`
- `WorkingMemoryRepository.java` (интерфейс)
- `WorkingMemoryRepositoryImpl.java`
- `LongTermMemoryRepository.java` (интерфейс)
- `LongTermMemoryRepositoryImpl.java`

**Сервисы:**
- `MemoryService.java` - единый роутер памяти с приоритетом LTM > WM
- `MemoryExtractionAgent.java` - агент для предложений

---

## API Endpoints

### Профили (6 endpoints)
```
GET    /api/profiles                        - список профилей
POST   /api/profiles                        - создать профиль
GET    /api/profiles/{id}                   - получить профиль
PUT    /api/profiles/{id}                   - обновить профиль
DELETE /api/profiles/{id}                   - удалить профиль
GET    /api/profiles/default                - дефолтный профиль
POST   /api/sessions/{id}/set-profile/{profileId}  - привязать профиль к сессии
```

### Рабочая память (4 endpoints)
```
GET    /api/sessions/{id}/memory/working    - все записи
POST   /api/sessions/{id}/memory/working    - сохранить
PUT    /api/sessions/{id}/memory/working/{key}  - обновить
DELETE /api/sessions/{id}/memory/working/{key}  - удалить
```

### Долговременная память (4 endpoints)
```
GET    /api/profiles/{id}/memory/longterm   - все записи профиля
POST   /api/profiles/{id}/memory/longterm   - сохранить
PUT    /api/profiles/{id}/memory/longterm/{key}  - обновить
DELETE /api/profiles/{id}/memory/longterm/{key}  - удалить
```

### Предложения (3 endpoints)
```
POST   /api/sessions/{id}/memory/suggest   - запустить извлечение
GET    /api/sessions/{id}/memory/suggestions - получить предложения
POST   /api/sessions/{id}/memory/suggestions/viewed  - отметить как просмотренные
POST   /api/memory/analyze                 - проанализировать текст
```

---

## Frontend

### Вкладки

Новые вкладки в навигации:
- **👤 Профили** - CRUD профилей, выбор активного профиля
- **🧠 Память** - рабочая и долговременная память, предложения

Удалена вкладка:
- **ℹ️ Информация**

### Компоненты

**Профили:**
- Список профилей с карточками
- Создать/редактировать/удалить профиль
- Выбор активного профиля
- Дропдаун в шапке для быстрого переключения

**Память:**
- Секции для рабочей и долговременной памяти
- Категории dropdown (task, variables, decisions, constraints / profile, preferences, knowledge, solutions, patterns)
- Автоматическое извлечение фактов (через 30 сек после сообщения пользователя)
- Badge на вкладке с количеством предложений

---

## Особенности

### Приоритет LTM > WM
```
memoryService.get(sessionId, profileId, key)
→ проверяет сначала долгосрочную память
→ затем рабочую память
```

### Исключение дубликатов
```
buildMemoryContext(sessionId)
→ загружает LTM и WM
→ фильтрует WM, исключая ключи из LTM
→ формирует контекст без дубликатов
```

### Фоновый анализ
```
onMessageSaved(sessionId, "user", content)
→ запланирует анализ через 30 секунд (debounce)
→ можно отменять и перепланировать
→ результаты кэшируются для быстрого доступа
```

### Миграция режимов
```
При первом запуске:
1. Создаются 3 профиля (Тестировщик, Помощник, Default)
2. Все сессии привязаны к профилям
3. Старые режимы сохранены для совместимости
```

---

## Категории

### Рабочая память (WORKING)
- `task` - текущая задача, цели
- `variables` - промежуточные данные, результаты
- `decisions` - решения в рамках задачи
- `constraints` - ограничения текущей задачи

### Долговременная память (LONG_TERM)
- `profile` - профильная информация
- `preferences` - предпочтения
- `knowledge` - накопленные знания
- `solutions` - лучшие решения
- `patterns` - паттерны использования

---

## Валидация

### Ключ (Memory)
```
- Длина: 1-100 символов
- Формат: a-zA-Z0-9_\\-
```

### Значение (Memory)
```
- Длина: 1-10,000 символов
```

---

## Статус реализации

✅ Backend - все компоненты реализованы
✅ Database - миграция БД готова
✅ API endpoints - 17 endpoints создано
✅ Валидация - реализована
✅ Frontend - HTML структура готова
✅ Frontend - CSS стили добавлены
✅ JavaScript функции добавлены
✅ Интеграция - WebApp обновлён

### Тесты
✅ WorkingMemoryRepositoryTest - 7 тестов
✅ LongTermMemoryRepositoryTest - 8 тестов
✅ ProfileRepositoryTest - 7 тестов
✅ MemoryServiceTest - 12 тестов
✅ MemoryExtractionAgentTest - 2 теста

---

## Использование

### Сохранение в рабочую память
```javascript
await saveWorkingMemory(sessionId, 'task', 'current_goal', 'реализовать auth');
```

### Сохранение в долговременную память
```javascript
await saveLongTermMemory(profileId, 'preferences', 'language', 'русский');
```

### Получение с приоритетом
```java
String lang = memoryService.get(sessionId, profileId, "language");
// Сначала проверит LTM, затем WM
```

### Построить контекст
```java
String context = memoryService.buildMemoryContext(sessionId);
// Автоматически объединяет LTM и WM без дубликатов
```

### Автоматическое извлечение
```javascript
// После каждого сообщения пользователя - автоматический анализ через 30 сек
// Предложения отображаются в UI с badge с количеством
// При просмотре - помечаются как просмотренные
```
---

## Структура БД

```sql
-- Профили
CREATE TABLE profiles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    system_prompt TEXT,
    settings TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Рабочая память
CREATE TABLE working_memory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    category TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    priority INTEGER DEFAULT 1,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(session_id, key),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

-- Долговременная память
CREATE TABLE long_term_memory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id INTEGER NOT NULL,
    category TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    priority INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(profile_id, category, key),
    FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
);

-- Связь сессий с профилями
ALTER TABLE sessions ADD COLUMN profile_id INTEGER DEFAULT 1;
```
