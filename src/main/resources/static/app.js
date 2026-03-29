document.addEventListener('DOMContentLoaded', async () => {
    await loadActiveSession();
    await loadSessions();
    loadProviders();
    await loadModels();
    await loadHistory();
    await loadModel();
    await loadSettings();
    await loadSessionStats();
    setupEventListeners();

    if (typeof initializeMemoryFeatures === 'function') {
        await initializeMemoryFeatures();
    }

    if (window.AppState.currentSessionId && typeof checkPausedTask === 'function') {
        await checkPausedTask(window.AppState.currentSessionId);
    }
});

function setupEventListeners() {
    const chatContainer = document.getElementById('chatContainer');
    const messageInput = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendBtn');
    const clearBtn = document.getElementById('clearBtn');
    const modelSelect = document.getElementById('modelSelect');
    const statusText = document.getElementById('statusText');
    const settingsBtn = document.getElementById('settingsBtn');
    const settingsModal = document.getElementById('settingsModal');
    const closeSettings = document.getElementById('closeSettings');
    const newSessionBtn = document.getElementById('newSessionBtn');
    const providerSelect = null;

    sendBtn.addEventListener('click', sendMessage);
    
    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    messageInput.addEventListener('input', () => {
        messageInput.style.height = 'auto';
        messageInput.style.height = Math.min(messageInput.scrollHeight, 150) + 'px';
    });

    clearBtn.addEventListener('click', clearHistory);
    modelSelect.addEventListener('change', changeModel);
    
    newSessionBtn.addEventListener('click', createNewSession);

    settingsBtn.addEventListener('click', async () => {
        settingsModal.classList.add('active');
        await loadSettings();
        loadSystemInfo();
        loadProvidersInfo();
        loadThinkingStatus();
        loadRerankerStatus();

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
    
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const currentActive = document.querySelector('.tab-btn.active');
            if (currentActive && currentActive.dataset.tab === 'memory' && btn.dataset.tab !== 'memory') {
                if (typeof stopSuggestionsPolling === 'function') {
                    stopSuggestionsPolling();
                }
            }
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
        });
    });

    document.getElementById('saveMaxTokens').addEventListener('click', saveMaxTokens);
    document.getElementById('saveTemperature').addEventListener('click', saveTemperature);

    document.getElementById('tsmToggle').addEventListener('change', toggleTsm);
    document.getElementById('ragToggle').addEventListener('change', toggleRag);
    document.getElementById('ragStrategySelect').addEventListener('change', saveRagStrategy);
    document.getElementById('reindexRagBtn').addEventListener('click', reindexRag);
    document.getElementById('rerankerToggle').addEventListener('change', toggleReranker);
    document.getElementById('rerankerThresholdInput').addEventListener('input', (e) => {
        document.getElementById('rerankerThresholdValue').textContent = parseFloat(e.target.value).toFixed(2);
    });
    document.getElementById('saveRerankerSettings').addEventListener('click', saveRerankerSettings);
    document.getElementById('maxTokensToggle').addEventListener('change', toggleMaxTokens);
    
    document.getElementById('temperatureToggle').addEventListener('change', toggleTemperature);
    
    document.getElementById('temperatureInput').addEventListener('input', (e) => {
        document.getElementById('temperatureValue').textContent = e.target.value;
    });
    
    document.getElementById('stopSequencesToggle').addEventListener('change', toggleStopSequences);
    document.getElementById('saveStopSequences').addEventListener('click', saveStopSequences);
    
    document.getElementById('saveSystemPrompt').addEventListener('click', saveSystemPrompt);
    document.getElementById('resetSystemPrompt').addEventListener('click', resetSystemPrompt);
    
    document.getElementById('thinkingToggle').addEventListener('change', toggleThinking);

    document.getElementById('strategySelect').addEventListener('change', updateStrategyUI);
    
    if (document.getElementById('saveStrategy')) {
        document.getElementById('saveStrategy').addEventListener('click', saveContextStrategy);
    }

    if (document.getElementById('saveWindowSize')) {
        document.getElementById('saveWindowSize').addEventListener('click', saveWindowSize);
    }

    if (document.getElementById('createBranchBtn')) {
        document.getElementById('createBranchBtn').addEventListener('click', createBranchFromCurrent);
    }

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

    if (providerSelect) {
        providerSelect.addEventListener('change', changeProvider);
    }

    chatContainer.addEventListener('scroll', () => {
        const scrollDistanceFromBottom = chatContainer.scrollHeight - chatContainer.scrollTop - chatContainer.clientHeight;
        window.AppState.userScrolled = scrollDistanceFromBottom > 50;
    });

    chatContainer.addEventListener('wheel', (event) => {
        if (event.deltaY < 0) {
            window.AppState.userScrolled = true;
        }
    }, { passive: true });

    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            window.pageHiddenTime = Date.now();
        } else if (window.pageHiddenTime && window.typingStartTime) {
            const elapsed = Date.now() - window.typingStartTime;
            const avgDelay = 9.5;
            const expectedChars = Math.floor(elapsed / avgDelay);
            
            if (window.typingElement && window.typingText) {
                const chars = window.typingText.split('');
                let currentText = '';
                
                for (let i = 0; i < Math.min(expectedChars, chars.length); i++) {
                    currentText += chars[i];
                }
                
                if (typeof marked !== 'undefined') {
                    window.typingElement.innerHTML = marked.parse(currentText);
                } else {
                    window.typingElement.textContent = currentText;
                }
                
                if (!window.AppState.userScrolled) {
                    chatContainer.scrollTop = chatContainer.scrollHeight;
                }
            }
        }
    });
}

async function sendMessage() {
    const chatContainer = document.getElementById('chatContainer');
    const messageInput = document.getElementById('messageInput');
    const statusText = document.getElementById('statusText');
    
    const message = messageInput.value.trim();
    if (!message || window.AppState.isLoading) return;

    messageInput.value = '';
    messageInput.style.height = 'auto';

    window.AppState.userScrolled = false;

    addMessage('user', message);
    
    await sendSingleMessage(message);
}

async function sendSingleMessage(message) {
    const statusText = document.getElementById('statusText');
    
    showTyping();
    window.AppState.isLoading = true;
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

        if (data.success) {
            if (data.userMessageId) {
                const lastUserMsg = chatContainer.querySelector('.message.user:last-of-type');
                if (lastUserMsg && !lastUserMsg.dataset.messageId) {
                    lastUserMsg.dataset.messageId = data.userMessageId;
                }
            }
            if (data.taskCreated) {
                console.log('Task created, taskId:', data.taskId);
                statusText.textContent = 'Генерация плана...';
                await loadHistory();
                hideTyping();
                statusText.textContent = 'Готов к работе';
                if (data.sessionTitle) {
                    console.log('Updating session title:', data.sessionTitle);
                    updateSessionTitleInList(data.sessionTitle);
                }
            } else if (data.requiresConfirmation) {
                hideTyping();
                console.log('Task requires confirmation');
                addMessage('system', data.response);
                addConfirmationButton(null);
                statusText.textContent = 'Ожидание подтверждения плана';
            } else if (data.taskCompleted) {
                hideTyping();
                console.log('Task completed');
                addMessage('system', data.response);
                statusText.textContent = 'Задача завершена';
            } else {
                hideTyping();
                console.log('Normal chat response');
                await addMessageWithTyping('assistant', data.response, false, data.metrics);
                window.AppState.lastMessageId = data.lastMessageId;
                statusText.textContent = 'Готов к работе';
                loadSessionStats();
                if (data.sessionTitle) {
                    console.log('Updating session title:', data.sessionTitle);
                    updateSessionTitleInList(data.sessionTitle);
                } else {
                    console.log('No sessionTitle in response');
                }
            }
        } else {
            hideTyping();
            console.error('Chat error:', data.error);
            if (data.userMessageId) {
                const lastUserMsg = chatContainer.querySelector('.message.user:last-of-type');
                if (lastUserMsg && !lastUserMsg.dataset.messageId) {
                    lastUserMsg.dataset.messageId = data.userMessageId;
                }
            }
            await loadHistory();
            statusText.textContent = 'Ошибка';
        }
    } catch (error) {
        console.error('Send message error:', error);
        hideTyping();
        addMessage('assistant', '❌ Ошибка соединения: ' + error.message);
        statusText.textContent = 'Ошибка соединения';
    }

    window.AppState.isLoading = false;
}

function addConfirmationButton(taskId) {
    const chatContainer = document.getElementById('chatContainer');
    console.log('Adding confirmation button for task: ' + taskId);

    const existingButtons = document.querySelectorAll('.task-confirmation');
    existingButtons.forEach(btn => {
        console.log('Removing existing confirmation button');
        btn.remove();
    });

    const buttonDiv = document.createElement('div');
    buttonDiv.className = 'task-confirmation';
    buttonDiv.id = 'task-confirmation-' + (taskId || 'current');
    if (taskId) {
        buttonDiv.dataset.taskId = taskId;
    }
    buttonDiv.innerHTML = `
        <button onclick="confirmPlan(${taskId})">✅ Подтвердить план</button>
        <button onclick="rejectPlan(${taskId})" style="margin-left: 8px;">❌ Отклонить план</button>
    `;

    chatContainer.appendChild(buttonDiv);
    chatContainer.scrollTop = chatContainer.scrollHeight;
    console.log('Confirmation button added: ' + buttonDiv.id);
}

async function confirmPlan(taskId) {
    const chatContainer = document.getElementById('chatContainer');
    
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

        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${actualTaskId}/confirm-plan`, {
            method: 'POST'
        });

        const data = await response.json();

        if (data.success) {
            console.log('Plan confirmed successfully');
            
            const confirmBtns = document.querySelectorAll('.task-confirmation');
            console.log('Found ' + confirmBtns.length + ' confirmation buttons to remove');
            confirmBtns.forEach(btn => btn.remove());
            
            addMessage('system', '⏳ Выполнение задачи начато. Шаги будут появляться по мере выполнения...');
            
            startTaskPolling(actualTaskId);
        } else {
            console.error('Plan confirmation failed: ' + (data.error || 'Неизвестная ошибка'));
            addMessage('system', '❌ Ошибка подтверждения: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Error confirming plan: ' + error.message);
        addMessage('system', '❌ Ошибка подтверждения: ' + error.message);
    }
}

async function rejectPlan(taskId) {
    const newDescription = prompt('Введите уточнённое описание задачи:');
    
    if (!newDescription || newDescription.trim() === '') {
        return;
    }
    
    const confirmBtns = document.querySelectorAll('.task-confirmation');
    confirmBtns.forEach(btn => btn.remove());
    
    addMessage('system', '⏳ Перегенерация плана...');
    
    try {
        const activeTask = await getActiveTask();
        const actualTaskId = taskId || activeTask?.id;
        
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${actualTaskId}/replan`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ newDescription: newDescription.trim() })
        });
        
        const data = await response.json();
        
        if (data.success) {
            await loadHistory();
        } else {
            addMessage('system', '❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Error rejecting plan: ' + error.message);
        addMessage('system', '❌ Ошибка: ' + error.message);
    }
}

function startTaskPolling(taskId) {
    const chatContainer = document.getElementById('chatContainer');
    console.log('[TaskPolling] Starting polling for task: ' + taskId);
    
    if (taskPollingIntervals[taskId]) {
        clearInterval(taskPollingIntervals[taskId]);
    }
    
    taskPollingIntervals[taskId] = setInterval(async () => {
        try {
            const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${taskId}`);
            const data = await response.json();
            
            if (data.success && data.task) {
                console.log('[TaskPolling] Task ' + taskId + ' state: ' + data.task.state);
                
                await loadNewMessagesOnly();
                
                if (data.task.state === 'DONE' || data.task.state === 'PLANNING') {
                    console.log('[TaskPolling] Task completed, stopping polling');
                    clearInterval(taskPollingIntervals[taskId]);
                    delete taskPollingIntervals[taskId];
                    
                    window.AppState.displayedTaskNoteKeys.clear();
                    await loadHistory();
                    
                    if (data.task.state === 'PLANNING') {
                        addReplanButton(taskId);
                    }
                }
            }
        } catch (error) {
            console.error('[TaskPolling] Error: ' + error.message);
        }
    }, 3000);
}

async function loadNewMessagesOnly() {
    try {
        const response = await fetch('/api/history');
        const data = await response.json();

        if (data.history && data.history.length > 0) {
            data.history.forEach(msg => {
                if (!msg.isTaskNote) {
                    return;
                }
                
                const noteKey = `${msg.taskId}-${msg.taskState}-${msg.stepIndex || 0}-${msg.content.substring(0, 50)}`;
                if (window.AppState.displayedTaskNoteKeys.has(noteKey)) {
                    return;
                }
                window.AppState.displayedTaskNoteKeys.add(noteKey);
                
                const hasMetrics = msg.outputTokens !== undefined && msg.outputTokens > 0;
                const metrics = hasMetrics ? {
                    inputTokens: msg.inputTokens || 0,
                    outputTokens: msg.outputTokens,
                    latency: msg.latency,
                    formattedLatency: formatLatency(msg.latency || 0)
                } : null;
                addMessage(msg.role, msg.content, false, metrics,
                    msg.isTaskNote || false, msg.taskId || null, msg.taskState || null, msg.stepIndex || null, false);
            });
        }
    } catch (error) {
        console.error('Error loading new messages:', error);
    }
}

function addReplanButton(taskId) {
    const chatContainer = document.getElementById('chatContainer');
    const buttonDiv = document.createElement('div');
    buttonDiv.id = `replan-button-${taskId}`;
    buttonDiv.className = 'message system';
    buttonDiv.innerHTML = `
        <button onclick="replanTask(${taskId})">🔄 Вернуться к планированию</button>
    `;
    
    chatContainer.appendChild(buttonDiv);
    chatContainer.scrollTop = chatContainer.scrollHeight;
    console.log('Replan button added: ' + buttonDiv.id);
}

async function replanTask(taskId) {
    const chatContainer = document.getElementById('chatContainer');
    try {
        console.log('Replanning task: ' + taskId);
        
        const button = document.getElementById(`replan-button-${taskId}`);
        if (button) {
            button.remove();
        }
        
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${taskId}/replan`, {
            method: 'POST'
        });
        
        const data = await response.json();
        
        if (data.success) {
            console.log('Task replanned successfully');
            addMessage('system', data.message);
            await loadHistory();
        } else {
            console.error('Task replanning failed: ' + (data.error || 'Неизвестная ошибка'));
            addMessage('system', '❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Error replanning task: ' + error.message);
        addMessage('system', '❌ Ошибка: ' + error.message);
    }
}

async function clearHistory() {
    const chatContainer = document.getElementById('chatContainer');
    const statusText = document.getElementById('statusText');
    
    if (!confirm('Очистить историю чата?')) return;
    
    try {
        const response = await fetch('/api/clear', {
            method: 'POST'
        });
        
        const data = await response.json();
        
        if (data.success) {
            window.AppState.displayedTaskNoteKeys.clear();
            window.AppState.openDetails.clear();
            window.AppState.lastHistoryLength = 0;
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

async function loadHistory() {
    const chatContainer = document.getElementById('chatContainer');
    
    try {
        const response = await fetch('/api/history');
        const data = await response.json();

        const existingMessageIds = new Set(
            [...chatContainer.querySelectorAll('[data-message-id]')]
                .map(el => parseInt(el.dataset.messageId))
                .filter(id => !isNaN(id))
        );

        const existingUserMessages = new Set(
            [...chatContainer.querySelectorAll('.message.user .message-content')]
                .map(el => {
                    const firstDiv = el.querySelector('div');
                    return firstDiv ? firstDiv.textContent.trim().substring(0, 100) : el.textContent.trim().substring(0, 100);
                })
        );

        const existingAssistantMessages = new Set(
            [...chatContainer.querySelectorAll('.message.assistant .message-content')]
                .map(el => {
                    const firstDiv = el.querySelector('div');
                    return firstDiv ? firstDiv.textContent.trim().substring(0, 100) : el.textContent.trim().substring(0, 100);
                })
        );

        const existingTaskNotes = new Set(
            [...chatContainer.querySelectorAll('.message.task-note')]
                .map(el => {
                    const taskId = el.dataset.taskId;
                    const taskState = el.dataset.taskState;
                    const stepIndex = el.dataset.stepIndex;
                    return taskId && taskState ? `${taskId}-${taskState}-${stepIndex || 0}` : null;
                })
                .filter(key => key !== null)
        );

        if (data.history && data.history.length > 0) {
            const welcome = chatContainer.querySelector('.welcome-message');
            if (welcome) {
                welcome.remove();
            }
            
            data.history.forEach(msg => {
                if (msg.id && existingMessageIds.has(msg.id)) {
                    return;
                }
                
                if (msg.role === 'user') {
                    const contentKey = msg.content.trim().substring(0, 100);
                    if (existingUserMessages.has(contentKey)) {
                        return;
                    }
                    existingUserMessages.add(contentKey);
                }
                
                if (!msg.id && msg.role === 'assistant') {
                    const contentKey = msg.content.trim().substring(0, 100);
                    if (existingAssistantMessages.has(contentKey)) {
                        return;
                    }
                    existingAssistantMessages.add(contentKey);
                }
                
                if (msg.isTaskNote) {
                    const noteKey = `${msg.taskId}-${msg.taskState}-${msg.stepIndex || 0}`;
                    if (existingTaskNotes.has(noteKey)) {
                        return;
                    }
                }
                
                const hasMetrics = msg.outputTokens !== undefined && msg.outputTokens > 0;
                const metrics = hasMetrics ? {
                    inputTokens: msg.inputTokens || 0,
                    outputTokens: msg.outputTokens,
                    latency: msg.latency,
                    formattedLatency: formatLatency(msg.latency || 0)
                } : null;
                addMessage(msg.role, msg.content, false, metrics,
                    msg.isTaskNote || false, msg.taskId || null, msg.taskState || null, msg.stepIndex || null, true, msg.id || null);
                
                if (msg.isTaskNote) {
                    const noteKey = `${msg.taskId}-${msg.taskState}-${msg.stepIndex || 0}-${msg.content.substring(0, 50)}`;
                    window.AppState.displayedTaskNoteKeys.add(noteKey);
                }

                if (msg.id && msg.role === 'assistant') {
                    window.AppState.lastMessageId = msg.id;
                }
            });
            
            const existingConfirmBtn = chatContainer.querySelector('.task-confirmation');
            if (data.taskRequiresConfirmation && data.activeTaskId) {
                const existingBtnForTask = chatContainer.querySelector(`.task-confirmation[data-task-id="${data.activeTaskId}"]`);
                const hasPlanningNote = chatContainer.querySelector(`.message.task-note[data-task-id="${data.activeTaskId}"][data-task-state="PLANNING"]`);
                if (!existingConfirmBtn && !existingBtnForTask && hasPlanningNote) {
                    addConfirmationButton(data.activeTaskId);
                }
            } else if (!data.taskRequiresConfirmation && existingConfirmBtn) {
                existingConfirmBtn.remove();
            }
        } else if (chatContainer.children.length === 0) {
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

        if (data.history) {
            window.AppState.lastHistoryLength = data.history.length;
        }

        if (window.AppState.currentSessionId && typeof loadSessionTitle === 'function') {
            await loadSessionTitle();
        }
    } catch (error) {
        console.error('Error loading history:', error);
    }
}

async function loadContextStrategy() {
    if (!window.AppState.currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/context-strategy`);
        const data = await response.json();

        if (data.success) {
            const strategySelect = document.getElementById('strategySelect');
            strategySelect.value = data.strategy;

            if (data.strategy === 'SLIDING_WINDOW') {
                const windowSizeResponse = await fetch(`/api/sessions/${window.AppState.currentSessionId}/sliding-window-settings`);
                const windowSizeData = await windowSizeResponse.json();
                if (windowSizeData.success) {
                    document.getElementById('windowSizeInput').value = windowSizeData.slidingWindowSize;
                }
            } else if (data.strategy === 'COMPRESSION') {
                const compressionResponse = await fetch(`/api/sessions/${window.AppState.currentSessionId}/compression-settings`);
                const compressionData = await compressionResponse.json();
                if (compressionData.success) {
                    document.getElementById('keepMessagesInput').value = compressionData.compressionKeepMessages;
                    document.getElementById('summaryIntervalInput').value = compressionData.compressionSummaryInterval;
                }
            } else if (data.strategy === 'STICKY_FACTS') {
                const stickyResponse = await fetch(`/api/sessions/${window.AppState.currentSessionId}/sticky-facts-settings`);
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
    if (!window.AppState.currentSessionId) {
        alert('Нет активной сессии');
        return;
    }

    const strategy = document.getElementById('strategySelect').value;

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/context-strategy`, {
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
    if (!window.AppState.currentSessionId) {
        alert('Нет активной сессии');
        return;
    }

    const windowSize = parseInt(document.getElementById('windowSizeInput').value);

    if (windowSize < 1 || windowSize > 100) {
        alert('Размер окна должен быть от 1 до 100');
        return;
    }

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/sliding-window-settings`, {
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
        if (!window.AppState.currentSessionId) {
            alert('Нет активной сессии');
            return;
        }

        const windowSize = parseInt(document.getElementById('stickyFactsWindowInput').value);

        if (windowSize < 1 || windowSize > 100) {
            alert('Размер окна должен быть от 1 до 100');
            return;
        }

        try {
            const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/sticky-facts-settings`, {
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
        if (!window.AppState.currentSessionId) {
            alert('Нет активной сессии');
            return;
        }

        const keepMessages = parseInt(document.getElementById('keepMessagesInput').value);

        if (keepMessages < 1 || keepMessages > 100) {
            alert('Количество сообщений должно быть от 1 до 100');
            return;
        }

        try {
            const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/compression-settings`, {
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
    if (!window.AppState.currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/facts`);
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
        if (!window.AppState.currentSessionId) {
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
            const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/facts`, {
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
        if (!window.AppState.currentSessionId) {
            alert('Нет активной сессии');
            return;
        }

        try {
            const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/facts/extract`, {
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
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/facts/${factId}`, {
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

    document.getElementById('slidingWindowSettings').style.display = 'none';
    document.getElementById('compressionSettings').style.display = 'none';
    document.getElementById('stickyFactsSettings').style.display = 'none';
    document.getElementById('branchingSettings').style.display = 'none';

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
    if (!window.AppState.currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/branches`);
        const data = await response.json();

        if (data.success) {
            renderBranchTree(data.branches);
        }
    } catch (error) {
        console.error('Ошибка загрузки веток:', error);
    }
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
    if (!window.AppState.currentSessionId) return;

    const branchName = prompt('Название ветки:', 'branch-' + Date.now());
    if (!branchName) return;

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/branches`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name: branchName,
                checkpointMessageId: window.AppState.lastMessageId
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
    if (!window.AppState.currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/branches/${branchId}/switch`, {
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
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/branches/${branchId}`, {
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
    if (!window.AppState.currentSessionId) return;

    try {
        const sessionResponse = await fetch(`/api/sessions/${window.AppState.currentSessionId}`);
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
    if (!window.AppState.currentSessionId) {
        alert('Сначала создайте или выберите сессию');
        return;
    }

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/profiles/${profileId}`, {
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

async function loadTasks() {
    if (!window.AppState.currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks`);
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
    if (!window.AppState.currentSessionId) return;

    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/active-task`);
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
        fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${task.id}/context`)
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
        'DONE': 'task-state-done',
        'PAUSED': 'task-state-paused'
    };

    const stateIcons = {
        'PLANNING': '📋',
        'EXECUTION': '⚙️',
        'VALIDATION': '✅',
        'DONE': '🎉',
        'PAUSED': '⏸️'
    };

    const stateClass = stateClasses[task.state] || '';
    const stateIcon = stateIcons[task.state] || '';

    const pauseStatus = task.state === 'PAUSED' ? `<span class="task-paused">⏸️ ${task.pauseReason || 'На паузе'}</span>` : '';

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
        <div class="task-item ${stateClass} ${task.state === 'PAUSED' ? 'task-paused-item' : ''}" data-task-id="${task.id}">
            <div class="task-header">
                <span class="task-title">${escapeHtml(task.title)}</span>
                <span class="task-state-badge">${stateIcon} ${task.state}</span>
            </div>
            ${task.description ? `<div class="task-description">${escapeHtml(task.description)}</div>` : ''}
            ${task.expectedAction ? `<div class="task-expected-action">📌 ${escapeHtml(task.expectedAction)}</div>` : ''}
            ${pauseStatus}
            ${contextSection}
            <div class="task-actions">
                ${task.state === 'PAUSED' ? `<button class="btn-small btn-secondary" onclick="resumeTask(${task.id})">▶️ Продолжить</button>` : ''}
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
        'PLANNING': ['EXECUTION', 'PAUSED'],
        'EXECUTION': ['VALIDATION', 'PLANNING', 'PAUSED'],
        'VALIDATION': ['DONE', 'EXECUTION', 'PAUSED'],
        'PAUSED': ['PLANNING', 'EXECUTION', 'VALIDATION'],
        'DONE': []
    };

    const stateLabels = {
        'PLANNING': '📋 Планирование',
        'EXECUTION': '⚙️ Выполнение',
        'VALIDATION': '✅ Проверка',
        'DONE': '🎉 Завершено',
        'PAUSED': '⏸️ Пауза'
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
        taskIdInput.value = task.id;
    } else {
        taskModal.querySelector('h2').textContent = '📝 Новая задача';
        taskTitle.value = '';
        taskDescription.value = '';
        taskState.value = 'PLANNING';
        taskExpectedAction.value = '';
        taskIdInput.value = '';
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
    const id = taskIdInput.value;

    if (!title) {
        alert('Пожалуйста, укажите заголовок задачи');
        return;
    }

    try {
        let response;

        if (id) {
            response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ title, description })
            });
        } else {
            response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks`, {
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
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${taskId}`);
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
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${taskId}`, {
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
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${taskId}/transition`, {
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

async function resumeTask(taskId) {
    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${taskId}/resume`, {
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
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${taskId}/context`);
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

const originalLoadActiveSession = loadActiveSession;
loadActiveSession = async function() {
    const result = await originalLoadActiveSession.apply(this, arguments);
    if (tasksTab.style.display === 'block') {
        await loadTasks();
    }
    return result;
};

window.heartbeatInterval = setInterval(() => {
    if (window.AppState.currentSessionId) {
        fetch('/api/heartbeat', { method: 'POST' });
    }
}, 30000);

async function checkActiveTaskAndPoll() {
    if (!window.AppState.currentSessionId) return;
    
    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks`);
        const data = await response.json();
        const activeTask = data.tasks?.find(t => t.state === 'EXECUTION' || t.state === 'VALIDATION' || t.state === 'PLANNING');
        
        if (activeTask && !window.AppState.taskPollingInterval) {
            window.AppState.taskPollingInterval = setInterval(async () => {
                try {
                    const histResponse = await fetch('/api/history');
                    const histData = await histResponse.json();
                    
                    const newLength = histData.history ? histData.history.length : 0;
                    if (newLength !== window.AppState.lastHistoryLength) {
                        window.AppState.lastHistoryLength = newLength;
                        await loadHistory();
                        if (typeof loadTasks === 'function') {
                            await loadTasks();
                        }
                    }
                } catch (e) {
                    console.error('Polling error:', e);
                }
            }, 3000);
        } else if (!activeTask && window.AppState.taskPollingInterval) {
            clearInterval(window.AppState.taskPollingInterval);
            window.AppState.taskPollingInterval = null;
        }
    } catch (error) {
        console.error('Error checking active task:', error);
    }
}

setInterval(checkActiveTaskAndPoll, 5000);
checkActiveTaskAndPoll();

async function loadMcpServers() {
    const container = document.getElementById('mcpServersList');
    if (!container) return;
    
    try {
        const sessionId = window.AppState.currentSessionId || '';
        const response = await fetch(`/api/mcp/servers?sessionId=${sessionId}`);
        const data = await response.json();
        
        if (data.success && data.servers) {
            renderMcpServers(data.servers);
        } else {
            container.innerHTML = '<div class="mcp-empty">Ошибка загрузки серверов</div>';
        }
    } catch (error) {
        console.error('Error loading MCP servers:', error);
        container.innerHTML = '<div class="mcp-empty">Ошибка соединения</div>';
    }
}

function renderMcpServers(servers) {
    const container = document.getElementById('mcpServersList');
    if (!container) return;
    
    if (!servers || servers.length === 0) {
        container.innerHTML = '<div class="mcp-empty">Нет доступных MCP серверов</div>';
        return;
    }
    
    container.innerHTML = servers.map(server => `
        <div class="mcp-server-item" data-server="${server.name}">
            <div class="mcp-server-header">
                <div class="mcp-server-info">
                    <span class="mcp-server-name">${escapeHtml(server.name)}</span>
                    <span class="mcp-server-status status-${server.status.toLowerCase()}">${server.status}</span>
                </div>
                <div class="mcp-server-actions">
                    ${server.status === 'CONNECTED' 
                        ? `<button class="btn-small btn-secondary" onclick="disconnectMcpServer('${server.name}')">🔌 Отключить</button>`
                        : `<button class="btn-small btn-primary" onclick="connectMcpServer('${server.name}')">🔌 Подключить</button>`
                    }
                </div>
            </div>
            <div class="mcp-server-details">
                <p class="mcp-server-desc">${escapeHtml(server.description || '')}</p>
                ${server.toolsCount > 0 ? `<span class="mcp-tools-count">🛠️ ${server.toolsCount} tools</span>` : ''}
            </div>
            <div class="mcp-server-session-toggle">
                <label class="toggle-switch">
                    <input type="checkbox" ${server.enabledForSession ? 'checked' : ''} 
                           onchange="toggleMcpServerForSession('${server.name}', this.checked)">
                    <span class="toggle-slider"></span>
                </label>
                <span>Использовать в текущей сессии</span>
            </div>
        </div>
    `).join('');
}

async function connectMcpServer(serverName) {
    try {
        const response = await fetch(`/api/mcp/servers/${serverName}/connect`, { method: 'POST' });
        const data = await response.json();
        
        if (data.success) {
            await loadMcpServers();
        } else {
            alert('❌ Ошибка подключения: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function disconnectMcpServer(serverName) {
    try {
        const response = await fetch(`/api/mcp/servers/${serverName}/disconnect`, { method: 'POST' });
        const data = await response.json();
        
        if (data.success) {
            await loadMcpServers();
        } else {
            alert('❌ Ошибка отключения: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
    }
}

async function toggleMcpServerForSession(serverName, enabled) {
    if (!window.AppState.currentSessionId) {
        alert('Сначала выберите или создайте сессию');
        return;
    }
    
    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/mcp-servers/${serverName}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled })
        });
        
        const data = await response.json();
        
        if (!data.success) {
            alert('❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
            await loadMcpServers();
        }
    } catch (error) {
        alert('❌ Ошибка: ' + error.message);
        await loadMcpServers();
    }
}

document.querySelector('[data-tab="mcp"]')?.addEventListener('click', loadMcpServers);
