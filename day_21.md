# Day 21: Embedding Pipeline с Ollama nomic-embed-text

## Реализовано

### 1. Пайплайн индексации
- ✅ Chunking (разбиение на чанки)
- ✅ Генерация эмбеддингов через Ollama
- ✅ Сохранение индекса в SQLite + векторный индекс в памяти
- ✅ Персистентность между перезапусками

### 2. Две стратегии chunking

#### FIXED (FixedSizeChunker)
- Размер чанка: **400 токенов** (~800 символов для кириллицы)
- Overlap: **40 токенов** (~80 символов)
- Разбивает по фиксированному размеру
- Разбивает длинные строки по пробелам/точкам/запятым

#### STRUCTURE (StructureAwareChunker)
- Максимальный размер: **1000 символов**
- Учитывает структуру документа:
  - Markdown: по заголовкам
  - Java: по классам
  - Текст: по параграфам
- Разбивает большие параграфы

### 3. Хранение

**SQLite таблицы:**
```sql
chunk_metadata (chunk_id, source, title, section, position, strategy, content)
chunk_embeddings (chunk_id, embedding BLOB, strategy)
```

**Векторный индекс:**
- `SmileVectorIndex` - cosine similarity search
- Отдельные индексы для FIXED и STRUCTURE
- Размерность: 768 (nomic-embed-text)

### 4. REST API

```
POST   /api/embeddings/index      - индексировать файл/директорию
GET    /api/embeddings/search     - поиск (q, k, strategy)
DELETE /api/embeddings/index      - очистить индекс
GET    /api/embeddings/stats      - статистика
GET    /api/embeddings/compare    - сравнить стратегии chunking
GET    /api/embeddings/ollama     - статус Ollama
```

## Сравнение стратегий

### Тестовый файл: king.txt

| Метрика | FIXED | STRUCTURE |
|---------|-------|-----------|
| Чанков | 93 | 62 |
| Avg размер | 619 chars | 810 chars |
| Max размер | 859 chars | 1000 chars |
| Min размер | 0 chars | 19 chars |

### Поиск "Эл" (персонаж)

#### FIXED стратегия
```
Результатов: 5
Лучший score: 0.656
Особенности: Находит конкретные упоминания имени
```

**Топ-1 результат:**
```
Score: 0.656
Content: "Эл меня вроде бы и не услышал. — Ты знал, что сначала я открыл это заведение в Оберне?..."
Section: body
```

#### STRUCTURE стратегия
```
Результатов: меньше (в BOTH доминируют FIXED результаты)
Лучший score: ~0.43-0.44
Особенности: Находит по контексту, но менее точно для имён
```

### Выводы

| Критерий | FIXED | STRUCTURE |
|----------|-------|-----------|
| **Точность поиска имён** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **Сохранение контекста** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Для кода** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Для текста** | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **Количество чанков** | Больше | Меньше |
| **Размер чанков** | Равномерный | Вариативный |

### Рекомендации

**Использовать FIXED когда:**
- Поиск конкретных имён, терминов
- Нужна равномерная плотность индекса
- Документы без явной структуры

**Использовать STRUCTURE когда:**
- Документы с заголовками/разделами
- Код (Java, Markdown)
- Важен контекст целиком
- Нужны осмысленные границы чанков

**Использовать BOTH когда:**
- Не уверены в типе запроса
- Нужен максимальный coverage
- Комбинированный поиск

## Технические детали

### Ollama
- Модель: `nomic-embed-text` (274 MB)
- Контекст: 8192 токенов
- Размерность: 768
- URL: http://localhost:11434

### Ограничения для кириллицы
- 1 символ ≈ 1-2 токена (вместо 4 для английского)
- Максимальный чанк уменьшен до 800-1000 chars
- Пакетная обработка по 50 чанков

### Файловые структуры
```
src/main/java/com/example/deepseek/embedding/
├── Chunk.java
├── ChunkMetadata.java
├── EmbeddingService.java
├── chunking/
│   ├── FixedSizeChunker.java
│   └── StructureAwareChunker.java
├── ollama/
│   └── OllamaClient.java
├── index/
│   ├── VectorIndex.java
│   └── SmileVectorIndex.java
├── repository/
│   └── ChunkMetadataRepository.java
└── controllers/
    └── EmbeddingController.java
```

## Примеры использования

### Индексация
```javascript
// STRUCTURE
fetch('/api/embeddings/index', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({path: 'src/main/resources/static/text/king.txt', strategy: 'STRUCTURE'})
}).then(r => r.json()).then(console.log)

// FIXED
fetch('/api/embeddings/index', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({path: 'src/main/resources/static/text/king.txt', strategy: 'FIXED'})
}).then(r => r.json()).then(console.log)
```

### Поиск
```javascript
// По конкретной стратегии
fetch('/api/embeddings/search?q=king&k=5&strategy=STRUCTURE')
  .then(r => r.json()).then(console.log)

// По всем стратегиям
fetch('/api/embeddings/search?q=king&k=5&strategy=BOTH')
  .then(r => r.json()).then(console.log)
```

### Статистика
```javascript
fetch('/api/embeddings/stats').then(r => r.json()).then(console.log)
```

## Метрики производительности

### Индексация king.txt
- 155 чанков total
- ~2-3 секунды на 50 чанков
- ~10-15 секунд на весь файл

### Поиск
- Время: <100ms
- Точность: 0.43-0.66 для релевантных результатов

## Следующие шаги

- [ ] Добавить поддержку PDF, DOCX
- [ ] Оптимизировать batch insert в SQLite
- [ ] Добавить удаление документов из индекса
- [ ] Кэширование query embeddings
- [ ] UI для управления индексом
