// DOM Elements
const chatContainer = document.getElementById('chatContainer');
const messageInput = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const clearBtn = document.getElementById('clearBtn');
const modeSelectSettings = document.getElementById('modeSelectSettings');
const modelSelect = document.getElementById('modelSelect');
const statusText = document.getElementById('statusText');
const modeText = document.getElementById('modeText');
const modelText = document.getElementById('modelText');
const settingsBtn = document.getElementById('settingsBtn');
const settingsModal = document.getElementById('settingsModal');
const closeSettings = document.getElementById('closeSettings');
const sessionsList = document.getElementById('sessionsList');
const newSessionBtn = document.getElementById('newSessionBtn');
const activeTaskIndicator = document.getElementById('activeTaskIndicator');
const activeTaskTitle = document.getElementById('activeTaskTitle');
const activeTaskStep = document.getElementById('activeTaskStep');
const closeTaskBtn = document.getElementById('closeTaskBtn');
const providerSelect = null;

// State
let isLoading = false;
let availableModels = [];
let currentSessionId = null;
let lastMessageId = null;
let userScrolled = false;
let hiddenTime = 0;
let pageHiddenTime = null;
let typingStartTime = null;
let typingElement = null;
let typingText = null;

// Auto-scroll to bottom - with delay to let DOM update
function scrollToBottom() {
    if (!userScrolled) {
        requestAnimationFrame(() => {
            chatContainer.scrollTop = chatContainer.scrollHeight;
        });
    }
}

// Detect user scroll - если пользователь открутил вверх более чем на 50px, считаем что он хочет читать историю
chatContainer.addEventListener('scroll', () => {
    const scrollDistanceFromBottom = chatContainer.scrollHeight - chatContainer.scrollTop - chatContainer.clientHeight;
    userScrolled = scrollDistanceFromBottom > 50;
});

// Wheel handler - отключаем автоскролл только при прокрутке ВВЕРХ
chatContainer.addEventListener('wheel', (event) => {
    if (event.deltaY < 0) {
        userScrolled = true;
    }
}, { passive: true });

// Настройка marked.js для рендеринга Markdown
if (typeof marked !== 'undefined') {
    marked.setOptions({
        breaks: true,
        gfm: true
    });
}

// Initialize
document.addEventListener('DOMContentLoaded', async () => {
    await loadActiveSession();
    await loadSessions();
    loadProviders();
    loadModels();
    await loadHistory();
    await loadMode();
    await loadModel();
    await loadSettings();
    await loadSessionStats();
    setupEventListeners();

    // Initialize memory features
    if (typeof initializeMemoryFeatures === 'function') {
        await initializeMemoryFeatures();
    }
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
    
    // Sessions
    newSessionBtn.addEventListener('click', createNewSession);

    // Settings modal
    settingsBtn.addEventListener('click', async () => {
        settingsModal.classList.add('active');
        await loadSettings();
        loadSystemInfo();
        loadProvidersInfo();
        loadThinkingStatus();

        // Load profiles when opening settings
        if (typeof loadProfiles === 'function') {
            await loadProfiles();
        }
        if (typeof loadCurrentProfileInfo === 'function') {
            await loadCurrentProfileInfo();
        }
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

    // Strategy select
    document.getElementById('strategySelect').addEventListener('change', updateStrategyUI);
    
    // Save strategy button
    if (document.getElementById('saveStrategy')) {
        document.getElementById('saveStrategy').addEventListener('click', saveContextStrategy);
    }

    // Save window size button
    if (document.getElementById('saveWindowSize')) {
        document.getElementById('saveWindowSize').addEventListener('click', saveWindowSize);
    }

    // Branching buttons
    if (document.getElementById('createBranchBtn')) {
        document.getElementById('createBranchBtn').addEventListener('click', createBranchFromCurrent);
    }

    // Profile buttons
    if (document.getElementById('createProfileBtn')) {
        document.getElementById('createProfileBtn').addEventListener('click', openCreateProfileModal);
    }

    if (document.getElementById('closeProfileEditModal')) {
        document.getElementById('closeProfileEditModal').addEventListener('click', () => {
            document.getElementById('profileEditModal').classList.remove('active');
        });
    }

    if (document.getElementById('profileEditModal')) {
        document.getElementById('profileEditModal').addEventListener('click', (e) => {
            if (e.target.id === 'profileEditModal') {
                document.getElementById('profileEditModal').classList.remove('active');
            }
        });
    }


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

    // Сбрасываем флаг скролла - пользователь ожидает ответ
    userScrolled = false;

    // Add user message to UI
    addMessage('user', message);
    
    // Обычный режим
    await sendSingleMessage(message);
}

async function sendSingleMessage(message) {
    showTyping();
    setLoading(true);
    statusText.textContent = 'Отправка запроса...';

    try {
        console.log('Sending message:', message);
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ message })
        });

        const data = await response.json();
        console.log('Chat API response:', data);
        console.log('Chat response:', data);

        hideTyping();

        if (data.success) {
            if (data.taskCreated) {
                console.log('Task created, taskId:', data.taskId);
                statusText.textContent = 'Задача создана. Загрузка истории...';
                if (data.taskPlanMessage) {
                    addMessage('system', data.taskPlanMessage, false, null, true, data.taskId, 'PLANNING');
                }
                await loadHistory();
                addConfirmationButton(data.taskId);
                statusText.textContent = 'Готов к работе';
            } else if (data.requiresConfirmation) {
                console.log('Task requires confirmation');
                addMessage('system', data.response);
                addConfirmationButton(null);
                statusText.textContent = 'Ожидание подтверждения плана';
            } else if (data.taskCompleted) {
                console.log('Task completed');
                addMessage('system', data.response);
                statusText.textContent = 'Задача завершена';
            } else {
                console.log('Normal chat response');
                await addMessageWithTyping('assistant', data.response, false, data.metrics);
                lastMessageId = data.lastMessageId;
                statusText.textContent = 'Готов к работе';
                loadSessionStats();
            }
        } else {
            console.error('Chat error:', data.error);
            addMessage('assistant', '❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
            statusText.textContent = 'Ошибка';
        }
    } catch (error) {
        console.error('Send message error:', error);
        hideTyping();
        addMessage('assistant', '❌ Ошибка соединения: ' + error.message);
        statusText.textContent = 'Ошибка соединения';
    }

    setLoading(false);
}

function addConfirmationButton(taskId) {
    console.log('Adding confirmation button for task: ' + taskId);

    const existingButtons = document.querySelectorAll('.task-confirmation');
    existingButtons.forEach(btn => {
        console.log('Removing existing confirmation button');
        btn.remove();
    });

    const buttonDiv = document.createElement('div');
    buttonDiv.className = 'task-confirmation';
    buttonDiv.id = 'task-confirmation-' + (taskId || 'current');
    buttonDiv.innerHTML = `
        <button onclick="confirmPlan(${taskId})">✅ Подтвердить план</button>
    `;

    chatContainer.appendChild(buttonDiv);
    chatContainer.scrollTop = chatContainer.scrollHeight;
    console.log('Confirmation button added: ' + buttonDiv.id);
}

async function confirmPlan(taskId) {
    try {
        console.log('Confirming plan for task: ' + taskId);

        const activeTask = await getActiveTask();
        if (!activeTask || !activeTask.id) {
            console.warn('Active task not found');
            addMessage('system', '❌ Активная задача не найдена');
            return;
        }

        const actualTaskId = taskId || activeTask.id;
        console.log('Using task ID: ' + actualTaskId);

        const response = await fetch(`/api/sessions/${currentSessionId}/tasks/${actualTaskId}/confirm-plan`, {
            method: 'POST'
        });

        const data = await response.json();

        if (data.success) {
            console.log('Plan confirmed successfully, reloading history');
            await loadHistory();
        } else {
            console.error('Plan confirmation failed: ' + (data.error || 'Неизвестная ошибка'));
            addMessage('system', '❌ Ошибка подтверждения: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Error confirming plan: ' + error.message);
        addMessage('system', '❌ Ошибка подтверждения: ' + error.message);
    }
}

async function getActiveTask() {
    try {
        console.log('Getting active task for session: ' + currentSessionId);
        const response = await fetch(`/api/sessions/${currentSessionId}/active-task`);
        const data = await response.json();
        const task = data.success && data.task ? data.task : null;
        console.log('Active task result:', task);
        return task;
    } catch (error) {
        console.error('Error getting active task:', error);
        return null;
    }
}

function addMessage(role, content, isLimited = false, metrics = null, isTaskNote = false, taskId = null, taskState = null) {
    const welcome = chatContainer.querySelector('.welcome-message');
    if (welcome) {
        welcome.remove();
    }

    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${role}${isLimited ? ' limited' : ''}${isTaskNote ? ' task-note collapsed' : ''}`;

    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = role === 'user' ? '👤' : (isLimited ? '🔬' : '🤖');

    if (isTaskNote && taskId) {
        avatar.onclick = () => toggleTaskDetails(messageDiv, taskId, taskState);
        avatar.title = 'Нажмите чтобы раскрыть детали';
    }

    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';

    if (isLimited) {
        const label = document.createElement('div');
        label.className = 'message-label';
        label.textContent = '🔬 Ограниченный запрос';
        contentDiv.appendChild(label);
    }

    const textDiv = document.createElement('div');

    if (role === 'assistant' && typeof marked !== 'undefined') {
        textDiv.innerHTML = marked.parse(content);
    } else {
        textDiv.textContent = content;
    }

    contentDiv.appendChild(textDiv);

    if (role === 'assistant' && metrics && metrics.outputTokens !== undefined) {
        const metricsDiv = document.createElement('div');
        metricsDiv.className = 'message-metrics';
        metricsDiv.innerHTML = `
            <span class="metric-item" title="Входные токены">
                <span class="metric-icon">📥</span>
                <span class="metric-value">${metrics.inputTokens || 0}</span>
            </span>
            <span class="metric-item" title="Выходные токены">
                <span class="metric-icon">📤</span>
                <span class="metric-value">${metrics.outputTokens || 0}</span>
            </span>
            <span class="metric-item" title="Время ответа">
                <span class="metric-icon">⏱️</span>
                <span class="metric-value">${metrics.formattedLatency || (metrics.latency ? formatLatency(metrics.latency) : '0 ms')}</span>
            </span>
        `;
        contentDiv.appendChild(metricsDiv);
    }

    if (isTaskNote && taskId) {
        contentDiv.onclick = () => toggleTaskDetails(messageDiv, taskId, taskState);
    }

    messageDiv.appendChild(avatar);
    messageDiv.appendChild(contentDiv);
    chatContainer.appendChild(messageDiv);

    setTimeout(() => {
        chatContainer.scrollTop = chatContainer.scrollHeight;
    }, 50);
}

function formatLatency(ms) {
    if (ms < 1000) {
        return ms + ' ms';
    }
    return (ms / 1000).toFixed(2) + ' sec';
}

async function toggleTaskDetails(messageDiv, taskId, taskState) {
    if (!taskId || !taskState) return;

    const isExpanded = messageDiv.classList.contains('expanded');
    messageDiv.classList.toggle('expanded');
    messageDiv.classList.toggle('collapsed');

    if (!isExpanded) {
        try {
            const response = await fetch(`/api/sessions/${currentSessionId}/tasks/${taskId}/messages/${taskState}`);
            const data = await response.json();

            if (data.success && data.message) {
                const contentDiv = messageDiv.querySelector('.message-content');
                const existingDetails = contentDiv.querySelector('.task-details');

                if (existingDetails) {
                    existingDetails.remove();
                }

                const detailsDiv = document.createElement('div');
                detailsDiv.className = 'task-details';
                detailsDiv.style.marginTop = '0.5rem';
                detailsDiv.style.padding = '0.5rem';
                detailsDiv.style.background = 'rgba(0, 0, 0, 0.05)';
                detailsDiv.style.borderRadius = '0.5rem';
                detailsDiv.innerHTML = `
                    <div style="font-weight: 600; margin-bottom: 0.25rem;">Детали этапа:</div>
                    <div style="font-size: 0.9em; white-space: pre-wrap;">${data.message.response}</div>
                `;
                contentDiv.appendChild(detailsDiv);
            }
        } catch (error) {
            console.error('Error loading task details:', error);
        }
    } else {
        const contentDiv = messageDiv.querySelector('.message-content');
        const existingDetails = contentDiv.querySelector('.task-details');
        if (existingDetails) {
            existingDetails.remove();
        }
    }
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
    
    // Добавляем метрики для ответов ассистента
    if (role === 'assistant' && metrics) {
        const metricsDiv = document.createElement('div');
        metricsDiv.className = 'message-metrics';
        metricsDiv.innerHTML = `
            <span class="metric-item" title="Входные токены">
                <span class="metric-icon">📥</span>
                <span class="metric-value">${metrics.inputTokens || 0}</span>
            </span>
            <span class="metric-item" title="Выходные токены">
                <span class="metric-icon">📤</span>
                <span class="metric-value">${metrics.outputTokens || 0}</span>
            </span>
            <span class="metric-item" title="Время ответа">
                <span class="metric-icon">⏱️</span>
                <span class="metric-value">${metrics.formattedLatency || '0 ms'}</span>
            </span>
        `;
        contentDiv.appendChild(metricsDiv);
    }
    
    // Scroll to bottom
    chatContainer.scrollTop = chatContainer.scrollHeight;
}

// Функция для эффекта печатания
let typingCancelled = false;

async function typeText(element, text) {
    const chars = text.split('');
    let currentText = '';
    const cursor = document.createElement('span');
    cursor.className = 'typing-cursor';
    typingCancelled = false;
    
    typingStartTime = Date.now();
    typingElement = element;
    typingText = text;
    
    const avgDelay = 9.5;
    let lastUpdateTime = typingStartTime;
    
    return new Promise(resolve => {
        function update() {
            if (typingCancelled) {
                typingStartTime = null;
                typingElement = null;
                typingText = null;
                cursor.remove();
                resolve();
                return;
            }
            
            const now = Date.now();
            const elapsed = now - typingStartTime;
            const expectedChars = Math.floor(elapsed / avgDelay);
            
            if (expectedChars > currentText.length) {
                const newChars = expectedChars - currentText.length;
                const addCount = Math.min(newChars, chars.length - currentText.length);
                
                for (let j = 0; j < addCount; j++) {
                    currentText += chars[currentText.length];
                }
                
                if (typeof marked !== 'undefined') {
                    element.innerHTML = marked.parse(currentText);
                } else {
                    element.textContent = currentText;
                }
                
                element.appendChild(cursor);
                
                if (!userScrolled) {
                    chatContainer.scrollTop = chatContainer.scrollHeight;
                }
                
                lastUpdateTime = now;
            }
            
            if (currentText.length < chars.length) {
                requestAnimationFrame(update);
            } else {
                typingStartTime = null;
                typingElement = null;
                typingText = null;
                cursor.remove();
                resolve();
            }
        }
        
        requestAnimationFrame(update);
    });
}

// Track hidden time for background typing
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        pageHiddenTime = Date.now();
    } else if (pageHiddenTime && typingStartTime) {
        const elapsed = Date.now() - typingStartTime;
        const avgDelay = 9.5;
        const expectedChars = Math.floor(elapsed / avgDelay);
        
        if (typingElement && typingText) {
            const chars = typingText.split('');
            let currentText = '';
            
            for (let i = 0; i < Math.min(expectedChars, chars.length); i++) {
                currentText += chars[i];
            }
            
            if (typeof marked !== 'undefined') {
                typingElement.innerHTML = marked.parse(currentText);
            } else {
                typingElement.textContent = currentText;
            }
            
            if (!userScrolled) {
                chatContainer.scrollTop = chatContainer.scrollHeight;
            }
        }
        
        hiddenTime += Date.now() - pageHiddenTime;
        pageHiddenTime = null;
    } else if (pageHiddenTime) {
        hiddenTime += Date.now() - pageHiddenTime;
        pageHiddenTime = null;
    }
});

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
        console.log('Loading history for session: ' + currentSessionId);
        const response = await fetch('/api/history');
        const data = await response.json();

        chatContainer.innerHTML = '';

        if (data.history && data.history.length > 0) {
            console.log('Found ' + data.history.length + ' messages in history');
            data.history.forEach(msg => {
                const hasMetrics = msg.outputTokens !== undefined && msg.outputTokens > 0;
                const metrics = hasMetrics ? {
                    inputTokens: msg.inputTokens || 0,
                    outputTokens: msg.outputTokens,
                    latency: msg.latency,
                    formattedLatency: formatLatency(msg.latency || 0)
                } : null;
                addMessage(msg.role, msg.content, false, metrics,
                    msg.isTaskNote || false, msg.taskId || null, msg.taskState || null);

                if (msg.id && msg.role === 'assistant') {
                    lastMessageId = msg.id;
                }
            });

            if (data.taskRequiresConfirmation && data.activeTaskId) {
                console.log('Task requires confirmation, adding button for task: ' + data.activeTaskId);
                addConfirmationButton(data.activeTaskId);
            } else {
                console.log('Task does not require confirmation');
            }
        } else {
            console.log('No messages in history, showing welcome message');
            chatContainer.innerHTML = `
                <div class="welcome-message">
                    <div class="welcome-icon">👋</div>
                    <h2>Привет! Я AI Ассистент</h2>
                    <p>Задайте мне любой вопрос!</p>
                    <div class="provider-info">
                        <span class="provider-badge deepseek">DeepSeek</span>
                    </div>
                </div>
            `;
        }

        modeText.textContent = 'Режим: ' + data.modeName;
        modeSelectSettings.value = String(data.mode);

        // Update memory tab session title
        if (currentSessionId && typeof loadSessionTitle === 'function') {
            await loadSessionTitle();
        }
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
            if (providerSelect) {
                if (settings.model.startsWith('deepseek')) {
                    providerSelect.value = 'deepseek';
                } else if (settings.model.includes('/')) {
                    providerSelect.value = 'openrouter';
                }
            }

            // Преобразуем availableModels в формат для UI
            availableModels = (settings.availableModels || []).map(id => ({ id, displayName: id }));
        }
        
        // Загружаем стратегию контекста
        await loadContextStrategy();
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

// ==================== SESSIONS ====================

async function loadSessions() {
    try {
        const response = await fetch('/api/sessions');
        const data = await response.json();
        
        if (data.success) {
            renderSessionsList(data.sessions, currentSessionId);
        }
    } catch (error) {
        console.error('Ошибка загрузки сессий:', error);
        sessionsList.innerHTML = '<div class="sessions-loading">Ошибка загрузки</div>';
    }
}

function renderSessionsList(sessions, activeId) {
    if (!sessions || sessions.length === 0) {
        sessionsList.innerHTML = '<div class="sessions-loading">Нет сессий</div>';
        return;
    }

    sessionsList.innerHTML = sessions.map(session => `
        <div class="session-item ${session.id === activeId ? 'active' : ''}" data-id="${session.id}">
            <div class="session-info" onclick="activateSession(${session.id})">
                <div class="session-title">${escapeHtml(session.title)}</div>
                <div class="session-meta">${formatDate(session.updatedAt)} · ${session.messageCount} сообщ.</div>
            </div>
            <button class="session-delete" onclick="deleteSession(event, ${session.id})" title="Удалить">🗑️</button>
        </div>
    `).join('');
}

async function createNewSession() {
    try {
        const response = await fetch('/api/sessions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({})
        });

        const data = await response.json();

        if (data.success) {
            currentSessionId = data.session.id;
            await loadHistory();
            await loadSessions();
            await loadSessionStats();

            // Load profile and memory info
            if (typeof loadCurrentProfileInfo === 'function') {
                await loadCurrentProfileInfo();
            }
            if (typeof loadSessionTitle === 'function') {
                await loadSessionTitle();
            }

            // Clear UI
            chatContainer.innerHTML = `
                <div class="welcome-message">
                    <div class="welcome-icon">👋</div>
                    <h2>Новая сессия</h2>
                    <p>Задайте мне любой вопрос!</p>
                </div>
            `;
        }
    } catch (error) {
        alert('Ошибка создания сессии: ' + error.message);
    }
}

async function activateSession(sessionId) {
    if (sessionId === currentSessionId) return;

    try {
        const response = await fetch('/api/sessions/' + sessionId + '/activate', {
            method: 'POST'
        });

        const data = await response.json();

        if (data.success) {
            currentSessionId = sessionId;
            await loadHistory();
            await loadSessions();
            await loadSessionStats();
            statusText.textContent = 'Сессия активирована';

            // Update memory tab session title
            const session = data.session;
            const memorySessionTitle = document.getElementById('memorySessionTitle');
            if (memorySessionTitle && session) {
                memorySessionTitle.textContent = session.title || 'Сессия #' + sessionId;
            }

            // Load profile and memory info
            if (typeof loadCurrentProfileInfo === 'function') {
                await loadCurrentProfileInfo();
            }
            if (typeof loadWorkingMemory === 'function') {
                await loadWorkingMemory(currentSessionId);
            }
        }
    } catch (error) {
        alert('Ошибка активации сессии: ' + error.message);
    }
}

async function deleteSession(event, sessionId) {
    event.stopPropagation();
    
    if (!confirm('Удалить эту сессию и все её сообщения?')) {
        return;
    }
    
    try {
        const response = await fetch('/api/sessions/' + sessionId, {
            method: 'DELETE'
        });
        
        const data = await response.json();
        
        if (data.success) {
            if (currentSessionId === sessionId) {
                currentSessionId = null;
            }
            await loadSessions();
            
            // If we deleted the active session, refresh to create/get new one
            if (!currentSessionId) {
                window.location.reload();
            }
        }
    } catch (error) {
        alert('Ошибка удаления сессии: ' + error.message);
    }
}

async function loadActiveSession() {
    try {
        const response = await fetch('/api/sessions/active');
        const data = await response.json();
        
        if (data.success && data.session) {
            currentSessionId = data.session.id;
        }
    } catch (error) {
        console.error('Ошибка загрузки активной сессии:', error);
    }
}

function formatDate(dateString) {
    const date = new Date(dateString);
    const now = new Date();
    const diff = now - date;
    
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);
    
    if (minutes < 1) return 'только что';
    if (minutes < 60) return minutes + ' мин. назад';
    if (hours < 24) return hours + ' ч. назад';
    if (days < 7) return days + ' дн. назад';
    
    return date.toLocaleDateString('ru');
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

async function loadSessionStats() {
    if (!currentSessionId) {
        document.getElementById('sessionStats').style.display = 'none';
        return;
    }

    try {
        let url = `/api/sessions/${currentSessionId}/stats`;
        
        const strategyResponse = await fetch(`/api/sessions/${currentSessionId}/context-strategy`);
        const strategyData = await strategyResponse.json();
        
        if (strategyData.success && strategyData.strategy === 'BRANCHING') {
            const branchesResponse = await fetch(`/api/sessions/${currentSessionId}/branches`);
            const branchesData = await branchesResponse.json();
            
            if (branchesData.success && branchesData.branches) {
                const activeBranch = branchesData.branches.find(b => b.isActive);
                if (activeBranch) {
                    url = `/api/sessions/${currentSessionId}/branches/${activeBranch.id}/stats`;
                }
            }
        }
        
        const response = await fetch(url);
        const data = await response.json();

        if (data.success && data.stats) {
            const statsEl = document.getElementById('sessionStats');
            statsEl.style.display = 'flex';

            const totalTokens = data.stats.totalTokens;
            const contextLimit = 128000;
            const percent = (totalTokens / contextLimit * 100).toFixed(1);

            document.getElementById('totalTokens').textContent = totalTokens.toLocaleString();
            document.getElementById('totalPercent').textContent = `(${percent}%)`;
            document.getElementById('totalCost').textContent = '$' + data.stats.totalCost.toFixed(4);

            const progressBar = document.getElementById('contextProgressBar');
            progressBar.style.width = Math.min(percent, 100) + '%';

            progressBar.className = 'context-progress-bar';
            if (percent >= 90) progressBar.classList.add('high');
            else if (percent >= 70) progressBar.classList.add('medium');
            else progressBar.classList.add('low');
        }
    } catch (error) {
        console.error('Ошибка загрузки статистики сессии:', error);
    }
}

// ==================== CONTEXT STRATEGIES ====================


async function loadContextStrategy() {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/context-strategy`);
        const data = await response.json();

        if (data.success) {
            const strategySelect = document.getElementById('strategySelect');
            strategySelect.value = data.strategy;

            if (data.strategy === 'SLIDING_WINDOW') {
                const windowSizeResponse = await fetch(`/api/sessions/${currentSessionId}/sliding-window-settings`);
                const windowSizeData = await windowSizeResponse.json();
                if (windowSizeData.success) {
                    document.getElementById('windowSizeInput').value = windowSizeData.slidingWindowSize;
                }
            } else if (data.strategy === 'COMPRESSION') {
                const compressionResponse = await fetch(`/api/sessions/${currentSessionId}/compression-settings`);
                const compressionData = await compressionResponse.json();
                if (compressionData.success) {
                    document.getElementById('keepMessagesInput').value = compressionData.compressionKeepMessages;
                    document.getElementById('summaryIntervalInput').value = compressionData.compressionSummaryInterval;
                }
            } else if (data.strategy === 'STICKY_FACTS') {
                const stickyResponse = await fetch(`/api/sessions/${currentSessionId}/sticky-facts-settings`);
                const stickyData = await stickyResponse.json();
                if (stickyData.success) {
                    document.getElementById('stickyFactsWindowInput').value = stickyData.stickyFactsWindowSize;
                }
            }

            await updateStrategyUI();
        }
    } catch (error) {
        console.error('Ошибка загрузки стратегии:', error);
    }
}

async function saveContextStrategy() {
    if (!currentSessionId) {
        alert('Нет активной сессии');
        return;
    }

    const strategy = document.getElementById('strategySelect').value;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/context-strategy`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ strategy })
        });

        const data = await response.json();

        if (data.success) {
            alert('✅ ' + data.message);
            await loadContextStrategy();
        } else {
            alert('❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function saveWindowSize() {
    if (!currentSessionId) {
        alert('Нет активной сессии');
        return;
    }

    const windowSize = parseInt(document.getElementById('windowSizeInput').value);

    if (windowSize < 1 || windowSize > 100) {
        alert('Размер окна должен быть от 1 до 100');
        return;
    }

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/sliding-window-settings`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ slidingWindowSize: windowSize })
        });

        const data = await response.json();

        if (data.success) {
            alert('✅ ' + data.message);
        } else {
            alert('❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

// ==================== STICKY FACTS ====================

const factsModal = document.getElementById('factsModal');

if (document.getElementById('manageFactsBtn')) {
    document.getElementById('manageFactsBtn').addEventListener('click', () => {
        loadFacts();
        factsModal.classList.add('active');
    });
}

if (document.getElementById('closeFactsModal')) {
    document.getElementById('closeFactsModal').addEventListener('click', () => {
        factsModal.classList.remove('active');
    });
}

if (document.getElementById('saveStickyFactsSettingsBtn')) {
    document.getElementById('saveStickyFactsSettingsBtn').addEventListener('click', async () => {
        if (!currentSessionId) {
            alert('Нет активной сессии');
            return;
        }

        const windowSize = parseInt(document.getElementById('stickyFactsWindowInput').value);

        if (windowSize < 1 || windowSize > 100) {
            alert('Размер окна должен быть от 1 до 100');
            return;
        }

        try {
            const response = await fetch(`/api/sessions/${currentSessionId}/sticky-facts-settings`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ stickyFactsWindowSize: windowSize })
            });

            const data = await response.json();

            if (data.success) {
                alert('✅ ' + data.message);
            } else {
                alert('❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
            }
        } catch (error) {
            alert('Ошибка соединения: ' + error.message);
        }
    });
}

if (document.getElementById('saveCompressionSettingsBtn')) {
    document.getElementById('saveCompressionSettingsBtn').addEventListener('click', async () => {
        if (!currentSessionId) {
            alert('Нет активной сессии');
            return;
        }

        const keepMessages = parseInt(document.getElementById('keepMessagesInput').value);

        if (keepMessages < 1 || keepMessages > 100) {
            alert('Количество сообщений должно быть от 1 до 100');
            return;
        }

        try {
            const response = await fetch(`/api/sessions/${currentSessionId}/compression-settings`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ compressionKeepMessages: keepMessages, compressionSummaryInterval: parseInt(document.getElementById('summaryIntervalInput').value) })
            });

            const data = await response.json();

            if (data.success) {
                alert('✅ ' + data.message);
            } else {
                alert('❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
            }
        } catch (error) {
            alert('Ошибка соединения: ' + error.message);
        }
    });
}

async function loadFacts() {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/facts`);
        const data = await response.json();

        if (data.success) {
            renderFacts(data.facts);
        }
    } catch (error) {
        console.error('Ошибка загрузки фактов:', error);
    }
}

function renderFacts(facts) {
    const container = document.getElementById('factsList');
    if (!container) return;

    if (!facts || facts.length === 0) {
        container.innerHTML = '<p style="color: #666;">Нет сохранённых фактов</p>';
        return;
    }

    const grouped = {};
    facts.forEach(f => {
        if (!grouped[f.category]) grouped[f.category] = [];
        grouped[f.category].push(f);
    });

    let html = '';
    for (const [category, categoryFacts] of Object.entries(grouped)) {
        html += `<h4 style="margin: 1rem 0 0.5rem 0; color: #374151;">${category}</h4>`;
        categoryFacts.forEach(fact => {
            html += `
                <div style="display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem; background: #f3f4f6; border-radius: 0.5rem; margin-bottom: 0.5rem;">
                    <span style="font-weight: 500; flex: 1;">${fact.key}:</span>
                    <span style="flex: 2;">${fact.value}</span>
                    <button onclick="deleteFact(${fact.id})" style="padding: 0.25rem 0.5rem; background: #ef4444; color: white; border: none; border-radius: 0.25rem; cursor: pointer;">✕</button>
                </div>
            `;
        });
    }

    container.innerHTML = html;
}

if (document.getElementById('addFactBtn')) {
    document.getElementById('addFactBtn').addEventListener('click', async () => {
        if (!currentSessionId) {
            alert('Нет активной сессии');
            return;
        }

        const category = document.getElementById('newFactCategory').value;
        const key = document.getElementById('newFactKey').value.trim();
        const value = document.getElementById('newFactValue').value.trim();

        if (!key || !value) {
            alert('Введите ключ и значение');
            return;
        }

        try {
            const response = await fetch(`/api/sessions/${currentSessionId}/facts`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ category, key, value })
            });

            const data = await response.json();

            if (data.success) {
                document.getElementById('newFactKey').value = '';
                document.getElementById('newFactValue').value = '';
                loadFacts();
            } else {
                alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
            }
        } catch (error) {
            alert('Ошибка соединения: ' + error.message);
        }
    });
}

if (document.getElementById('extractFactsBtn')) {
    document.getElementById('extractFactsBtn').addEventListener('click', async () => {
        if (!currentSessionId) {
            alert('Нет активной сессии');
            return;
        }

        try {
            const response = await fetch(`/api/sessions/${currentSessionId}/facts/extract`, {
                method: 'POST'
            });

            const data = await response.json();

            if (data.success) {
                alert('✅ ' + data.message);
                setTimeout(loadFacts, 2000);
            } else {
                alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
            }
        } catch (error) {
            alert('Ошибка соединения: ' + error.message);
        }
    });
}

window.deleteFact = async function(factId) {
    if (!confirm('Удалить этот факт?')) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/facts/${factId}`, {
            method: 'DELETE'
        });

        const data = await response.json();

        if (data.success) {
            loadFacts();
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
};

async function updateStrategyUI() {
    const strategy = document.getElementById('strategySelect').value;

    console.log('updateStrategyUI called, strategy:', strategy);

    // Скрываем все блоки настроек
    document.getElementById('slidingWindowSettings').style.display = 'none';
    document.getElementById('compressionSettings').style.display = 'none';
    document.getElementById('stickyFactsSettings').style.display = 'none';
    document.getElementById('branchingSettings').style.display = 'none';

    // Показываем соответствующий блок
    if (strategy === 'COMPRESSION') {
        document.getElementById('compressionSettings').style.display = 'block';
    } else if (strategy === 'SLIDING_WINDOW') {
        document.getElementById('slidingWindowSettings').style.display = 'block';
    } else if (strategy === 'STICKY_FACTS') {
        document.getElementById('stickyFactsSettings').style.display = 'block';
    } else if (strategy === 'BRANCHING') {
        document.getElementById('branchingSettings').style.display = 'block';
        loadBranches();
    }
}

async function loadBranches() {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/branches`);
        const data = await response.json();

        if (data.success) {
            renderBranchTree(data.branches);
        }
    } catch (error) {
        console.error('Ошибка загрузки веток:', error);
    }
}

function formatRelativeTime(dateStr) {
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now - date;
    const diffSec = Math.floor(diffMs / 1000);
    const diffMin = Math.floor(diffSec / 60);
    const diffHours = Math.floor(diffMin / 60);
    const diffDays = Math.floor(diffHours / 24);

    if (diffSec < 60) return 'только что';
    if (diffMin < 60) return `${diffMin} мин назад`;
    if (diffHours < 24) return `${diffHours} ч назад`;
    if (diffDays < 7) return `${diffDays} дн назад`;
    return date.toLocaleDateString('ru-RU');
}

function renderBranchTree(branches) {
    const container = document.getElementById('branchTree');
    container.innerHTML = '';

    if (!branches || branches.length === 0) {
        container.innerHTML = '<p style="color: #6b7280;">Нет веток</p>';
        return;
    }

    branches.forEach(branch => {
        const item = document.createElement('div');
        item.className = `branch-item ${branch.isActive ? 'active' : ''}`;
        item.innerHTML = `
            <span class="branch-name">${escapeHtml(branch.name)}</span>
            ${branch.parentMessageId ? 
                `<span class="badge badge-secondary">от ${formatRelativeTime(branch.createdAt)}</span>` : 
                '<span class="badge badge-primary">main</span>'}
            <div class="branch-actions">
                <button class="btn-small" onclick="switchBranch(${branch.id})" title="Переключить">🔀</button>
                ${!branch.isMain ? `
                    <button class="btn-small" onclick="deleteBranch(${branch.id})" title="Удалить">🗑️</button>
                ` : ''}
            </div>
        `;
        container.appendChild(item);
    });
}

async function createBranchFromCurrent() {
    if (!currentSessionId) return;

    const branchName = prompt('Название ветки:', 'branch-' + Date.now());
    if (!branchName) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/branches`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name: branchName,
                checkpointMessageId: lastMessageId
            })
        });

        const data = await response.json();
        if (data.success) {
            await loadBranches();
            alert('✅ Ветка создана. Новые сообщения пойдут в неё.');
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function switchBranch(branchId) {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/branches/${branchId}/switch`, {
            method: 'POST'
        });

        const data = await response.json();
        if (data.success) {
            await loadBranches();
            await loadHistory();
            await loadSessionStats();
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function deleteBranch(branchId) {
    if (!confirm('Удалить ветку? Все сообщения ветки будут удалены.')) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/branches/${branchId}`, {
            method: 'DELETE'
        });

        const data = await response.json();
        if (data.success) {
            await loadBranches();
            alert('✅ Ветка удалена');
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function loadCompressionSettings() {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/compression-settings`);
        const data = await response.json();

        if (data.success) {
            document.getElementById('keepMessagesInput').value = data.compressionKeepMessages;
            document.getElementById('summaryIntervalInput').value = data.compressionSummaryInterval;
        }
    } catch (error) {
        console.error('Ошибка загрузки настроек compression:', error);
    }
}

async function loadStickyFactsSettings() {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/sticky-facts-settings`);
        const data = await response.json();

        if (data.success) {
            document.getElementById('stickyFactsWindowInput').value = data.stickyFactsWindowSize;
        }
    } catch (error) {
        console.error('Ошибка загрузки настроек sticky facts:', error);
    }
}

async function loadSlidingWindowSettings() {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/sliding-window-settings`);
        const data = await response.json();

        if (data.success) {
            document.getElementById('windowSizeInput').value = data.slidingWindowSize;
        }
    } catch (error) {
        console.error('Ошибка загрузки настроек sliding window:', error);
    }
}

async function loadProfiles() {
    try {
        const response = await fetch('/api/profiles');
        const data = await response.json();

        if (data.success) {
            const profilesMainList = document.getElementById('profilesMainList');
            profilesMainList.innerHTML = '';

            data.profiles.forEach(profile => {
                const profileCard = document.createElement('div');
                profileCard.className = 'profile-card';
                profileCard.innerHTML = `
                    <h4>${escapeHtml(profile.name)}</h4>
                    <p>${escapeHtml(profile.description || 'Нет описания')}</p>
                    ${profile.personalization ? `<p class="profile-personalization">Персонализация: ${escapeHtml(profile.personalization)}</p>` : ''}
                    <button class="btn-small" onclick="setSessionProfile(${profile.id})">🎯 Использовать</button>
                `;
                profilesMainList.appendChild(profileCard);
            });
        }
    } catch (error) {
        console.error('Ошибка загрузки профилей:', error);
    }
}

async function loadCurrentProfileInfo() {
    if (!currentSessionId) return;

    try {
        const sessionResponse = await fetch(`/api/sessions/${currentSessionId}`);
        const sessionData = await sessionResponse.json();

        if (sessionData.success && sessionData.session && sessionData.session.profileId) {
            const profileId = sessionData.session.profileId;
            const profileResponse = await fetch(`/api/profiles/${profileId}`);
            const profileData = await profileResponse.json();

            if (profileData.success) {
                const profile = profileData.profile;
                const currentProfileInfo = document.getElementById('currentProfileInfo');
                currentProfileInfo.innerHTML = `
                    <div class="profile-card">
                        <h4>${escapeHtml(profile.name)}</h4>
                        <p>${escapeHtml(profile.description || 'Нет описания')}</p>
                        ${profile.personalization ? `<p class="profile-personalization">Персонализация: ${escapeHtml(profile.personalization)}</p>` : ''}
                        <button class="btn-small" id="editCurrentProfileBtn" onclick="editProfile(${profile.id})">✏️ Редактировать</button>
                    </div>
                `;
            }
        }
    } catch (error) {
        console.error('Ошибка загрузки информации о профиле:', error);
    }
}

async function setSessionProfile(profileId) {
    if (!currentSessionId) {
        alert('Сначала создайте или выберите сессию');
        return;
    }

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/profiles/${profileId}`, {
            method: 'POST'
        });

        const data = await response.json();
        if (data.success) {
            await loadHistory();
            await loadCurrentProfileInfo();
            alert('✅ Профиль применён к сессии');
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

function openCreateProfileModal() {
    document.getElementById('profileEditModalTitle').textContent = 'Создать профиль';
    document.getElementById('profileNameInput').value = '';
    document.getElementById('profileDescriptionInput').value = '';
    document.getElementById('profileSystemPromptInput').value = '';
    document.getElementById('profilePersonalizationInput').value = '';
    document.getElementById('saveProfileBtn').onclick = createProfile;
    document.getElementById('profileEditModal').classList.add('active');
}

function editProfile(profileId) {
    fetch(`/api/profiles/${profileId}`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const profile = data.profile;
                document.getElementById('profileEditModalTitle').textContent = 'Редактировать профиль';
                document.getElementById('profileNameInput').value = profile.name;
                document.getElementById('profileDescriptionInput').value = profile.description || '';
                document.getElementById('profileSystemPromptInput').value = profile.systemPrompt || '';
                document.getElementById('profilePersonalizationInput').value = profile.personalization || '';
                document.getElementById('saveProfileBtn').onclick = () => updateProfile(profileId);
                document.getElementById('profileEditModal').classList.add('active');
            } else {
                alert('❌ Ошибка: ' + data.error);
            }
        })
        .catch(error => {
            alert('❌ Ошибка: ' + error.message);
        });
}

async function createProfile() {
    const name = document.getElementById('profileNameInput').value.trim();
    const description = document.getElementById('profileDescriptionInput').value.trim();
    const systemPrompt = document.getElementById('profileSystemPromptInput').value.trim();
    const personalization = document.getElementById('profilePersonalizationInput').value.trim();

    if (!name) {
        alert('Введите имя профиля');
        return;
    }

    try {
        const response = await fetch('/api/profiles', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name,
                description,
                systemPrompt,
                personalization
            })
        });

        const data = await response.json();
        if (data.success) {
            await loadProfiles();
            document.getElementById('profileEditModal').classList.remove('active');
            alert('✅ Профиль создан');
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function updateProfile(profileId) {
    const name = document.getElementById('profileNameInput').value.trim();
    const description = document.getElementById('profileDescriptionInput').value.trim();
    const systemPrompt = document.getElementById('profileSystemPromptInput').value.trim();
    const personalization = document.getElementById('profilePersonalizationInput').value.trim();

    if (!name) {
        alert('Введите имя профиля');
        return;
    }

    try {
        const response = await fetch(`/api/profiles/${profileId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name,
                description,
                systemPrompt,
                personalization
            })
        });

        const data = await response.json();
        if (data.success) {
            await loadProfiles();
            await loadCurrentProfileInfo();
            document.getElementById('profileEditModal').classList.remove('active');
            alert('✅ Профиль обновлён');
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ==================== TASKS ====================

const taskModal = document.getElementById('taskModal');
const tasksList = document.getElementById('tasksList');
const newTaskBtn = document.getElementById('newTaskBtn');
const closeTaskModal = document.getElementById('closeTaskModal');
const saveTaskBtn = document.getElementById('saveTaskBtn');
const cancelTaskBtn = document.getElementById('cancelTaskBtn');
const taskTitle = document.getElementById('taskTitle');
const taskDescription = document.getElementById('taskDescription');
const taskState = document.getElementById('taskState');
const taskExpectedAction = document.getElementById('taskExpectedAction');
const taskId = document.getElementById('taskId');
const tabSessions = document.getElementById('tabSessions');
const tabTasks = document.getElementById('tabTasks');
const sessionsTab = document.getElementById('sessionsTab');
const tasksTab = document.getElementById('tasksTab');

async function loadTasks() {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/tasks`);
        const data = await response.json();

        if (data.success) {
            renderTasks(data.tasks);
        }
    } catch (error) {
        console.error('Error loading tasks:', error);
        tasksList.innerHTML = '<div class="tasks-empty">Ошибка загрузки задач</div>';
    }

    await loadActiveTask();
}

async function loadActiveTask() {
    if (!currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/active-task`);
        const data = await response.json();

        if (data.success) {
            if (data.task && data.task !== "") {
                showActiveTaskIndicator(data.task);
            } else {
                hideActiveTaskIndicator();
            }
        }
    } catch (error) {
        console.error('Error loading active task:', error);
    }
}

function showActiveTaskIndicator(task) {
    activeTaskIndicator.style.display = 'flex';
    activeTaskTitle.textContent = task.title;
    
    if (task.context) {
        fetch(`/api/sessions/${currentSessionId}/tasks/${task.id}/context`)
            .then(r => r.json())
            .then(data => {
                if (data.success && data.context) {
                    const ctx = data.context;
                    activeTaskStep.textContent = `Шаг ${ctx.step}/${ctx.total}: ${escapeHtml(ctx.current || '')}`;
                }
            });
    } else {
        activeTaskStep.textContent = '';
    }
}

function hideActiveTaskIndicator() {
    activeTaskIndicator.style.display = 'none';
}

function renderTasks(tasks) {
    if (!tasks || tasks.length === 0) {
        tasksList.innerHTML = '<div class="tasks-empty">Нет задач</div>';
        return;
    }

    tasksList.innerHTML = tasks.map(task => createTaskItem(task)).join('');
}

function createTaskItem(task) {
    const stateClasses = {
        'PLANNING': 'task-state-planning',
        'EXECUTION': 'task-state-execution',
        'VALIDATION': 'task-state-validation',
        'DONE': 'task-state-done'
    };

    const stateIcons = {
        'PLANNING': '📋',
        'EXECUTION': '⚙️',
        'VALIDATION': '✅',
        'DONE': '🎉'
    };

    const stateClass = stateClasses[task.state] || '';
    const stateIcon = stateIcons[task.state] || '';

    const pauseStatus = task.paused ? `<span class="task-paused">⏸️ Пауза${task.pauseReason ? ': ' + task.pauseReason : ''}</span>` : '';

    const contextSection = task.context ? `
        <div class="task-context-section">
            <div class="task-context-header">
                <span class="task-context-title">📊 Контекст задачи</span>
                <button class="btn-small btn-secondary" onclick="loadTaskContext(${task.id})">↻ Обновить</button>
            </div>
            <div class="task-context-content" id="task-context-${task.id}">
                <div class="task-context-loading">Загрузка...</div>
            </div>
        </div>
    ` : '';

    return `
        <div class="task-item ${stateClass} ${task.paused ? 'task-paused-item' : ''}" data-task-id="${task.id}">
            <div class="task-header">
                <span class="task-title">${escapeHtml(task.title)}</span>
                <span class="task-state-badge">${stateIcon} ${task.state}</span>
            </div>
            ${task.description ? `<div class="task-description">${escapeHtml(task.description)}</div>` : ''}
            ${task.expectedAction ? `<div class="task-expected-action">📌 ${escapeHtml(task.expectedAction)}</div>` : ''}
            ${pauseStatus}
            ${contextSection}
            <div class="task-actions">
                ${!task.paused ? `<button class="btn-small btn-secondary" onclick="pauseTask(${task.id})">⏸️</button>` : ''}
                ${task.paused ? `<button class="btn-small btn-secondary" onclick="resumeTask(${task.id})">▶️</button>` : ''}
                <button class="btn-small btn-secondary" onclick="editTask(${task.id})">✏️</button>
                <button class="btn-small btn-danger" onclick="deleteTask(${task.id})">🗑️</button>
            </div>
            <div class="task-transitions">
                ${getTaskTransitionButtons(task)}
            </div>
        </div>
    `;
}

function getTaskTransitionButtons(task) {
    const transitions = {
        'PLANNING': ['EXECUTION'],
        'EXECUTION': ['VALIDATION', 'PLANNING'],
        'VALIDATION': ['DONE', 'EXECUTION'],
        'DONE': []
    };

    const stateLabels = {
        'PLANNING': '📋 Планирование',
        'EXECUTION': '⚙️ Выполнение',
        'VALIDATION': '✅ Проверка',
        'DONE': '🎉 Завершено'
    };

    const validTransitions = transitions[task.state] || [];

    if (validTransitions.length === 0) {
        return '';
    }

    return validTransitions.map(state => `
        <button class="btn-small btn-transition" onclick="transitionTask(${task.id}, '${state}')">
            → ${stateLabels[state]}
        </button>
    `).join('');
}

function openTaskModal(task = null) {
    if (task) {
        taskModal.querySelector('h2').textContent = '✏️ Редактировать задачу';
        taskTitle.value = task.title;
        taskDescription.value = task.description || '';
        taskState.value = task.state;
        taskExpectedAction.value = task.expectedAction || '';
        taskId.value = task.id;
    } else {
        taskModal.querySelector('h2').textContent = '📝 Новая задача';
        taskTitle.value = '';
        taskDescription.value = '';
        taskState.value = 'PLANNING';
        taskExpectedAction.value = '';
        taskId.value = '';
    }

    taskModal.classList.add('active');
}

function closeTaskModalFn() {
    taskModal.classList.remove('active');
}

async function saveTask() {
    const title = taskTitle.value.trim();
    const description = taskDescription.value.trim();
    const state = taskState.value;
    const expectedAction = taskExpectedAction.value.trim();
    const id = taskId.value;

    if (!title) {
        alert('Пожалуйста, укажите заголовок задачи');
        return;
    }

    try {
        let response;

        if (id) {
            response = await fetch(`/api/sessions/${currentSessionId}/tasks/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ title, description })
            });
        } else {
            response = await fetch(`/api/sessions/${currentSessionId}/tasks`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ title, description, state })
            });
        }

        const data = await response.json();

        if (data.success) {
            await loadTasks();
            closeTaskModalFn();
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function editTask(taskId) {
    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/tasks/${taskId}`);
        const data = await response.json();

        if (data.success) {
            openTaskModal(data.task);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function deleteTask(taskId) {
    if (!confirm('Удалить задачу?')) {
        return;
    }

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/tasks/${taskId}`, {
            method: 'DELETE'
        });

        const data = await response.json();

        if (data.success) {
            await loadTasks();
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function transitionTask(taskId, newState) {
    const expectedAction = prompt('Укажите ожидаемое действие (необязательно):');

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/tasks/${taskId}/transition`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ state: newState, expectedAction })
        });

        const data = await response.json();

        if (data.success) {
            await loadTasks();
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function pauseTask(taskId) {
    const reason = prompt('Укажите причину паузы:');

    if (reason === null) return;

    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/tasks/${taskId}/pause`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ reason })
        });

        const data = await response.json();

        if (data.success) {
            await loadTasks();
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function resumeTask(taskId) {
    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/tasks/${taskId}/resume`, {
            method: 'POST'
        });

        const data = await response.json();

        if (data.success) {
            await loadTasks();
        } else {
            alert('❌ Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

function switchTab(tab) {
    document.querySelectorAll('.sidebar-tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.sidebar-content').forEach(c => c.style.display = 'none');

    if (tab === 'sessions') {
        tabSessions.classList.add('active');
        sessionsTab.style.display = 'block';
        loadSessions();
    } else {
        tabTasks.classList.add('active');
        tasksTab.style.display = 'block';
        loadTasks();
    }
}

newTaskBtn.addEventListener('click', () => openTaskModal());
closeTaskModal.addEventListener('click', closeTaskModalFn);
cancelTaskBtn.addEventListener('click', closeTaskModalFn);
saveTaskBtn.addEventListener('click', saveTask);

tabSessions.addEventListener('click', () => switchTab('sessions'));
tabTasks.addEventListener('click', () => switchTab('tasks'));
closeTaskBtn.addEventListener('click', hideActiveTaskIndicator);

async function loadTaskContext(taskId) {
    try {
        const response = await fetch(`/api/sessions/${currentSessionId}/tasks/${taskId}/context`);
        const data = await response.json();

        const contextDiv = document.getElementById(`task-context-${taskId}`);
        if (data.success && data.context) {
            const ctx = data.context;
            contextDiv.innerHTML = `
                <div class="task-context-info">
                    <div class="task-context-row">
                        <span class="task-context-label">Шаг:</span>
                        <span class="task-context-value">${ctx.step} / ${ctx.total}</span>
                    </div>
                    ${ctx.current ? `
                    <div class="task-context-row">
                        <span class="task-context-label">Текущий:</span>
                        <span class="task-context-value current">${escapeHtml(ctx.current)}</span>
                    </div>
                    ` : ''}
                </div>
                ${ctx.plan && ctx.plan.length > 0 ? `
                <div class="task-context-plan">
                    <div class="task-context-plan-title">План:</div>
                    <div class="task-context-plan-items">
                        ${ctx.plan.map((step, idx) => `
                            <div class="task-context-plan-item ${idx < ctx.step ? 'done' : idx === ctx.step - 1 ? 'current' : ''}">
                                ${idx + 1}. ${escapeHtml(step)}
                            </div>
                        `).join('')}
                    </div>
                </div>
                ` : ''}
                ${ctx.done && ctx.done.length > 0 ? `
                <div class="task-context-done">
                    <div class="task-context-done-title">Выполнено:</div>
                    <div class="task-context-done-items">
                        ${ctx.done.map(step => `
                            <div class="task-context-done-item">✓ ${escapeHtml(step)}</div>
                        `).join('')}
                    </div>
                </div>
                ` : ''}
            `;
        } else {
            contextDiv.innerHTML = '<div class="task-context-empty">Контекст не найден</div>';
        }
    } catch (error) {
        const contextDiv = document.getElementById(`task-context-${taskId}`);
        contextDiv.innerHTML = `<div class="task-context-error">Ошибка загрузки контекста: ${escapeHtml(error.message)}</div>`;
    }
}

// Load tasks when session changes
const originalLoadActiveSession = loadActiveSession;
loadActiveSession = async function() {
    const result = await originalLoadActiveSession.apply(this, arguments);
    if (tasksTab.style.display === 'block') {
        await loadTasks();
    }
    return result;
};
