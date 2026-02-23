// DOM Elements
const chatContainer = document.getElementById('chatContainer');
const messageInput = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const clearBtn = document.getElementById('clearBtn');
const modeSelectSettings = document.getElementById('modeSelectSettings');
const modelSelect = document.getElementById('modelSelect');
const providerSelect = document.getElementById('providerSelect');
const statusText = document.getElementById('statusText');
const modeText = document.getElementById('modeText');
const modelText = document.getElementById('modelText');
const settingsBtn = document.getElementById('settingsBtn');
const settingsModal = document.getElementById('settingsModal');
const closeSettings = document.getElementById('closeSettings');

// State
let isLoading = false;
let availableModels = [];

// Настройка marked.js для рендеринга Markdown
if (typeof marked !== 'undefined') {
    marked.setOptions({
        breaks: true,
        gfm: true
    });
}

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    loadProviders();
    loadModels();
    loadHistory();
    loadMode();
    loadModel();
    loadSettings();
    setupEventListeners();
});

function setupEventListeners() {
    sendBtn.addEventListener('click', sendMessage);
    
    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    // Auto-resize textarea
    messageInput.addEventListener('input', () => {
        messageInput.style.height = 'auto';
        messageInput.style.height = Math.min(messageInput.scrollHeight, 150) + 'px';
    });

    clearBtn.addEventListener('click', clearHistory);
    modelSelect.addEventListener('change', changeModel);
    
    // Settings modal
    settingsBtn.addEventListener('click', async () => {
        settingsModal.classList.add('active');
        await loadSettings();
        loadSystemInfo();
        loadProvidersInfo();
        loadThinkingStatus();
    });
    
    closeSettings.addEventListener('click', () => {
        settingsModal.classList.remove('active');
    });
    
    settingsModal.addEventListener('click', (e) => {
        if (e.target === settingsModal) {
            settingsModal.classList.remove('active');
        }
    });
    
    // Tabs
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
        });
    });
    
    // Settings handlers
    document.getElementById('saveMode').addEventListener('click', saveMode);
    document.getElementById('saveMaxTokens').addEventListener('click', saveMaxTokens);
    document.getElementById('saveTemperature').addEventListener('click', saveTemperature);
    
    // MaxTokens toggle
    document.getElementById('maxTokensToggle').addEventListener('change', toggleMaxTokens);
    
    // Temperature toggle
    document.getElementById('temperatureToggle').addEventListener('change', toggleTemperature);
    
    // Temperature slider
    document.getElementById('temperatureInput').addEventListener('input', (e) => {
        document.getElementById('temperatureValue').textContent = e.target.value;
    });
    
    // System prompt handlers
    document.getElementById('saveSystemPrompt').addEventListener('click', saveSystemPrompt);
    document.getElementById('resetSystemPrompt').addEventListener('click', resetSystemPrompt);
    
    // Thinking mode toggle
    document.getElementById('thinkingToggle').addEventListener('change', toggleThinking);
    
    // Provider select - only if exists
    if (providerSelect) {
        providerSelect.addEventListener('change', changeProvider);
    }
}

async function sendMessage() {
    const message = messageInput.value.trim();
    if (!message || isLoading) return;

    // Clear input
    messageInput.value = '';
    messageInput.style.height = 'auto';

    // Add user message to UI
    addMessage('user', message);
    
    // Обычный режим
    await sendSingleMessage(message);
}

async function sendSingleMessage(message) {
    // Show typing indicator
    showTyping();
    setLoading(true);
    statusText.textContent = 'Отправка запроса...';

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ message })
        });

        const data = await response.json();
        
        hideTyping();

        if (data.success) {
            // Добавляем сообщение с эффектом печатания и метриками
            await addMessageWithTyping('assistant', data.response, false, data.metrics);
            statusText.textContent = 'Готов к работе';
        } else {
            addMessage('assistant', '❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
            statusText.textContent = 'Ошибка';
        }
    } catch (error) {
        hideTyping();
        addMessage('assistant', '❌ Ошибка соединения: ' + error.message);
        statusText.textContent = 'Ошибка соединения';
    }

    setLoading(false);
}

function addMessage(role, content, isLimited = false) {
    // Remove welcome message if exists
    const welcome = chatContainer.querySelector('.welcome-message');
    if (welcome) {
        welcome.remove();
    }

    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${role}${isLimited ? ' limited' : ''}`;
    
    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = role === 'user' ? '👤' : (isLimited ? '🔬' : '🤖');
    
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';
    
    if (isLimited) {
        const label = document.createElement('div');
        label.className = 'message-label';
        label.textContent = '🔬 Ограниченный запрос';
        contentDiv.appendChild(label);
    }
    
    const textDiv = document.createElement('div');
    
    // Рендерим markdown для ассистента, обычный текст для пользователя
    if (role === 'assistant' && typeof marked !== 'undefined') {
        textDiv.innerHTML = marked.parse(content);
    } else {
        textDiv.textContent = content;
    }
    
    contentDiv.appendChild(textDiv);
    
    messageDiv.appendChild(avatar);
    messageDiv.appendChild(contentDiv);
    chatContainer.appendChild(messageDiv);
    
    // Scroll to bottom
    chatContainer.scrollTop = chatContainer.scrollHeight;
}

// Функция для добавления сообщения с эффектом печатания
async function addMessageWithTyping(role, content, isLimited = false, metrics = null) {
    // Remove welcome message if exists
    const welcome = chatContainer.querySelector('.welcome-message');
    if (welcome) {
        welcome.remove();
    }

    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${role}${isLimited ? ' limited' : ''}`;
    
    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = role === 'user' ? '👤' : (isLimited ? '🔬' : '🤖');
    
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';
    
    if (isLimited) {
        const label = document.createElement('div');
        label.className = 'message-label';
        label.textContent = '🔬 Ограниченный запрос';
        contentDiv.appendChild(label);
    }
    
    const textDiv = document.createElement('div');
    contentDiv.appendChild(textDiv);
    
    messageDiv.appendChild(avatar);
    messageDiv.appendChild(contentDiv);
    chatContainer.appendChild(messageDiv);
    
    // Эффект печатания
    await typeText(textDiv, content);
    
    // Добавляем метрики ПОСЛЕ завершения печатания (для ответов ассистента)
    if (role === 'assistant' && metrics) {
        const metricsDiv = document.createElement('div');
        metricsDiv.className = 'message-metrics';
        metricsDiv.innerHTML = `
            <span class="metric-item" title="Входные токены">
                <span class="metric-icon">📥</span>
                <span class="metric-value">${metrics.inputTokens}</span>
            </span>
            <span class="metric-item" title="Выходные токены">
                <span class="metric-icon">📤</span>
                <span class="metric-value">${metrics.outputTokens}</span>
            </span>
            <span class="metric-item" title="Время ответа">
                <span class="metric-icon">⏱️</span>
                <span class="metric-value">${metrics.formattedLatency}</span>
            </span>
            <span class="metric-item" title="Стоимость">
                <span class="metric-icon">💰</span>
                <span class="metric-value">${metrics.formattedCost}</span>
            </span>
        `;
        contentDiv.appendChild(metricsDiv);
    }
    
    // Scroll to bottom
    chatContainer.scrollTop = chatContainer.scrollHeight;
}

// Функция для эффекта печатания
async function typeText(element, text) {
    const chars = text.split('');
    let currentText = '';
    const cursor = document.createElement('span');
    cursor.className = 'typing-cursor';
    
    // Скорость печатания (мс на символ)
    const baseDelay = 7;
    
    for (let i = 0; i < chars.length; i++) {
        currentText += chars[i];
        
        // Рендерим markdown на лету
        if (typeof marked !== 'undefined') {
            element.innerHTML = marked.parse(currentText);
        } else {
            element.textContent = currentText;
        }
        
        // Добавляем курсор
        element.appendChild(cursor);
        
        // Случайная задержка для более естественного эффекта (уменьшена в 2 раза)
        const delay = baseDelay + Math.random() * 5;
        await sleep(delay);
    }
    
    // Убираем курсор после завершения
    cursor.remove();
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function showTyping() {
    const typingDiv = document.createElement('div');
    typingDiv.className = 'message assistant';
    typingDiv.id = 'typing-indicator';
    
    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = '🤖';
    
    const typing = document.createElement('div');
    typing.className = 'typing';
    typing.innerHTML = '<span></span><span></span><span></span>';
    
    typingDiv.appendChild(avatar);
    typingDiv.appendChild(typing);
    chatContainer.appendChild(typingDiv);
    
    chatContainer.scrollTop = chatContainer.scrollHeight;
}

function hideTyping() {
    const typing = document.getElementById('typing-indicator');
    if (typing) {
        typing.remove();
    }
}

function setLoading(loading) {
    isLoading = loading;
    sendBtn.disabled = loading;
    messageInput.disabled = loading;
}

async function clearHistory() {
    if (!confirm('Очистить историю чата?')) return;
    
    try {
        const response = await fetch('/api/clear', {
            method: 'POST'
        });
        
        const data = await response.json();
        
        if (data.success) {
            chatContainer.innerHTML = `
                <div class="welcome-message">
                    <div class="welcome-icon">👋</div>
                    <h2>Привет! Я AI Ассистент</h2>
                    <p>Выберите модель и задайте мне любой вопрос!</p>
                    <div class="provider-info">
                        <span class="provider-badge deepseek">DeepSeek</span>
                    </div>
                </div>
            `;
            statusText.textContent = 'История очищена';
        }
    } catch (error) {
        statusText.textContent = 'Ошибка при очистке';
    }
}

async function changeProvider() {
    const provider = providerSelect.value;
    
    // Обновляем модель в соответствии с провайдером
    if (provider === 'deepseek') {
        modelSelect.value = 'deepseek-reasoner';
    } else if (provider === 'openrouter') {
        modelSelect.value = 'openai/gpt-oss-20b:free';
    }
    
    await changeModel();
}

async function changeModel() {
     const model = modelSelect.value;
     
     // Определяем провайдера по модели (только если providerSelect существует)
     if (providerSelect) {
         if (model.startsWith('deepseek')) {
             providerSelect.value = 'deepseek';
         } else if (model.includes('/')) {
             providerSelect.value = 'openrouter';
         }
     }
     
     try {
         statusText.textContent = 'Смена модели...';
         
         const response = await fetch('/api/model', {
             method: 'POST',
             headers: {
                 'Content-Type': 'application/json'
             },
             body: JSON.stringify({ model })
         });
         
         const data = await response.json();
         
         if (data.success) {
             modelText.textContent = 'Модель: ' + data.modelName;
             statusText.textContent = data.message;
             // Обновляем видимость thinking mode при смене модели
             loadThinkingStatus();
         } else {
             statusText.textContent = 'Ошибка: ' + (data.error || 'Неизвестная ошибка');
             alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
             // Возвращаем предыдущую модель
             loadModel();
         }
     } catch (error) {
         console.error('Error changing model:', error);
         statusText.textContent = 'Ошибка при смене модели';
         alert('Ошибка при смене модели');
         loadModel();
     }
 }

async function loadModel() {
    try {
        const response = await fetch('/api/model');
        const data = await response.json();
        
        modelSelect.value = data.model;
        modelText.textContent = 'Модель: ' + data.modelName;
        
        // Определяем провайдера по модели (только если providerSelect существует)
        if (providerSelect) {
            if (data.model.startsWith('deepseek')) {
                providerSelect.value = 'deepseek';
            } else if (data.model.includes('/')) {
                providerSelect.value = 'openrouter';
            }
        }
    } catch (error) {
        console.error('Error loading model:', error);
    }
}

async function loadProviders() {
    try {
        const response = await fetch('/api/providers');
        const data = await response.json();
        
        if (data.success && data.providers) {
            // Просто логируем доступные провайдеры
            console.log('Available providers:', data.providers.map(p => p.name));
        }
    } catch (error) {
        console.error('Error loading providers:', error);
    }
}

async function loadModels() {
    try {
        const response = await fetch('/api/models');
        const data = await response.json();
        
        if (data.success && data.models) {
            availableModels = data.models;
        }
    } catch (error) {
        console.error('Error loading models:', error);
    }
}

async function loadHistory() {
    try {
        const response = await fetch('/api/history');
        const data = await response.json();
        
        if (data.history && data.history.length > 0) {
            chatContainer.innerHTML = '';
            data.history.forEach(msg => {
                addMessage(msg.role, msg.content);
            });
        }
        
        modeText.textContent = 'Режим: ' + data.modeName;
        modeSelectSettings.value = String(data.mode);
    } catch (error) {
        console.error('Error loading history:', error);
    }
}

async function loadMode() {
    try {
        const response = await fetch('/api/mode');
        const data = await response.json();
        
        modeText.textContent = 'Режим: ' + data.modeName;
        modeSelectSettings.value = String(data.mode);
    } catch (error) {
        console.error('Error loading mode:', error);
    }
}

async function loadSettings() {
    try {
        const response = await fetch('/api/settings');
        const data = await response.json();
        
        if (data.success) {
            const settings = data.settings;
            document.getElementById('modeSelectSettings').value = String(settings.mode);
            document.getElementById('maxTokensInput').value = settings.maxTokens;
            document.getElementById('temperatureInput').value = settings.temperature;
            document.getElementById('temperatureValue').textContent = settings.temperature;
            document.getElementById('systemModeInfo').textContent = settings.modeDescription;
            document.getElementById('systemPromptInput').value = settings.systemPrompt;
            document.getElementById('modelSelect').value = settings.model;
            
            // Загружаем состояние enabled для maxTokens
            const maxTokensToggle = document.getElementById('maxTokensToggle');
            const maxTokensStatus = document.getElementById('maxTokensStatus');
            const maxTokensValueRow = document.getElementById('maxTokensValueRow');
            if (settings.maxTokensEnabled !== undefined) {
                maxTokensToggle.checked = settings.maxTokensEnabled;
                maxTokensStatus.textContent = settings.maxTokensEnabled ? 'Включено' : 'Выключено';
                maxTokensValueRow.style.opacity = settings.maxTokensEnabled ? '1' : '0.5';
            }
            
            // Загружаем состояние enabled для temperature
            const temperatureToggle = document.getElementById('temperatureToggle');
            const temperatureStatus = document.getElementById('temperatureStatus');
            const temperatureValueRow = document.getElementById('temperatureValueRow');
            if (settings.temperatureEnabled !== undefined) {
                temperatureToggle.checked = settings.temperatureEnabled;
                temperatureStatus.textContent = settings.temperatureEnabled ? 'Включено' : 'Выключено';
                temperatureValueRow.style.opacity = settings.temperatureEnabled ? '1' : '0.5';
            }
            
            // Определяем провайдера по модели
            if (settings.model.startsWith('deepseek')) {
                providerSelect.value = 'deepseek';
            } else if (settings.model.includes('/')) {
                providerSelect.value = 'openrouter';
            }
            
            // Преобразуем availableModels в формат для UI
            availableModels = (settings.availableModels || []).map(id => ({ id, displayName: id }));
        }
    } catch (error) {
        console.error('Error loading settings:', error);
    }
}

    async function loadSystemInfo() {
        try {
            const infoResponse = await fetch('/api/info');
            const infoData = await infoResponse.json();
            
            if (infoData.success) {
                const info = infoData.info;
                document.getElementById('infoOs').textContent = info.osName + ' ' + info.osVersion;
                document.getElementById('infoUser').textContent = info.userName;
            }
        } catch (error) {
            console.error('Error loading system info:', error);
        }
    }

async function loadProvidersInfo() {
    try {
        const response = await fetch('/api/providers');
        const data = await response.json();
        
        if (data.success && data.providers) {
            const container = document.getElementById('providersList');
            container.innerHTML = '';
            
            data.providers.forEach(provider => {
                const div = document.createElement('div');
                div.className = 'provider-info-item';
                div.innerHTML = `
                    <strong>${provider.displayName}</strong>
                    <span class="models-count">${provider.models.length} моделей</span>
                `;
                container.appendChild(div);
            });
        }
    } catch (error) {
        console.error('Error loading providers info:', error);
    }
}

async function saveMode() {
    const mode = parseInt(modeSelectSettings.value);
    
    try {
        const response = await fetch('/api/mode', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ mode })
        });
        
        const data = await response.json();
        
        if (data.success) {
            modeText.textContent = 'Режим: ' + data.modeName;
            statusText.textContent = data.message;
            
            // Обновляем системный промпт в настройках
            const systemResponse = await fetch('/api/system');
            const systemData = await systemResponse.json();
            if (systemData.success) {
                document.getElementById('systemPromptInput').value = systemData.systemPrompt;
                document.getElementById('systemModeInfo').textContent = systemData.modeDescription;
            }
            
            alert('✅ Режим изменён на: ' + data.modeName);
            
            // Clear chat UI
            chatContainer.innerHTML = `
                <div class="welcome-message">
                    <div class="welcome-icon">${mode === 1 ? '🧪' : '🛠️'}</div>
                    <h2>Режим "${data.modeName}" активирован</h2>
                    <p>История очищена. Готов к работе!</p>
                </div>
            `;
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function saveMaxTokens() {
    const value = parseInt(document.getElementById('maxTokensInput').value);
    
    if (isNaN(value) || value < 1) {
        alert('Введите корректное число токенов');
        return;
    }
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'max_tokens', value: value })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
            alert('✅ ' + data.message);
        } else {
            alert('Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function saveTemperature() {
    const value = parseFloat(document.getElementById('temperatureInput').value);
    
    if (isNaN(value) || value < 0 || value > 2) {
        alert('Temperature должна быть от 0 до 2');
        return;
    }
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'temperature', value: value })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
            alert('✅ ' + data.message);
        } else {
            alert('Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function saveSystemPrompt() {
    const value = document.getElementById('systemPromptInput').value;
    
    if (!value.trim()) {
        alert('Системный промпт не может быть пустым');
        return;
    }
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'system_prompt', value: value })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
            alert('✅ Системный промпт обновлён');
            
            // Clear chat
            chatContainer.innerHTML = `
                <div class="welcome-message">
                    <div class="welcome-icon">🎭</div>
                    <h2>Системный промпт обновлён</h2>
                    <p>История очищена. Готов к работе!</p>
                </div>
            `;
        } else {
            alert('Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function resetSystemPrompt() {
    const mode = parseInt(modeSelectSettings.value);
    const defaultPrompt = mode === 1 
        ? 'Ты senior тестировщик из Google с 10+ годами опыта. Объясняй концепции тестирования простыми словами, как будто объясняешь джуниору на первом дне работы. Используй практические примеры из реальной разработки. Отвечай кратко и структурированно.'
        : 'Ты полезный помощник';
    
    document.getElementById('systemPromptInput').value = defaultPrompt;
    
    if (confirm('Сбросить системный промпт на стандартный для текущего режима?')) {
        await saveSystemPrompt();
    }
}

// Thinking mode functions
async function loadThinkingStatus() {
    try {
        const response = await fetch('/api/thinking');
        const data = await response.json();
        
        if (data.success) {
            const thinkingGroup = document.getElementById('thinkingGroup');
            const thinkingToggle = document.getElementById('thinkingToggle');
            const thinkingStatus = document.getElementById('thinkingStatus');
            
            // Показываем thinking mode только для deepseek-reasoner
            if (data.supportsThinking) {
                thinkingGroup.style.display = 'block';
                thinkingToggle.checked = data.thinkingEnabled;
                thinkingStatus.textContent = data.thinkingEnabled ? 'Включён' : 'Выключен';
            } else {
                thinkingGroup.style.display = 'none';
            }
        }
    } catch (error) {
        console.error('Error loading thinking status:', error);
    }
}

async function toggleThinking() {
    const thinkingToggle = document.getElementById('thinkingToggle');
    const thinkingStatus = document.getElementById('thinkingStatus');
    const enabled = thinkingToggle.checked;
    
    try {
        const response = await fetch('/api/thinking', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ enabled })
        });
        
        const data = await response.json();
        
        if (data.success) {
            thinkingStatus.textContent = enabled ? 'Включён' : 'Выключен';
            statusText.textContent = data.message;
        } else {
            thinkingToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        thinkingToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}

// MaxTokens toggle function
async function toggleMaxTokens() {
    const maxTokensToggle = document.getElementById('maxTokensToggle');
    const maxTokensStatus = document.getElementById('maxTokensStatus');
    const maxTokensValueRow = document.getElementById('maxTokensValueRow');
    const enabled = maxTokensToggle.checked;
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'max_tokens_enabled', value: enabled })
        });
        
        const data = await response.json();
        
        if (data.success) {
            maxTokensStatus.textContent = enabled ? 'Включено' : 'Выключено';
            maxTokensValueRow.style.opacity = enabled ? '1' : '0.5';
            statusText.textContent = data.message;
        } else {
            maxTokensToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        maxTokensToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}

// Temperature toggle function
async function toggleTemperature() {
    const temperatureToggle = document.getElementById('temperatureToggle');
    const temperatureStatus = document.getElementById('temperatureStatus');
    const temperatureValueRow = document.getElementById('temperatureValueRow');
    const enabled = temperatureToggle.checked;
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'temperature_enabled', value: enabled })
        });
        
        const data = await response.json();
        
        if (data.success) {
            temperatureStatus.textContent = enabled ? 'Включено' : 'Выключено';
            temperatureValueRow.style.opacity = enabled ? '1' : '0.5';
            statusText.textContent = data.message;
        } else {
            temperatureToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        temperatureToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}
