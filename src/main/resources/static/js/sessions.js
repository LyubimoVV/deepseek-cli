async function loadSessions() {
    const sessionsList = document.getElementById('sessionsList');
    try {
        const response = await fetch('/api/sessions');
        const data = await response.json();
        
        if (data.success) {
            renderSessionsList(data.sessions, window.AppState.currentSessionId);
        }
    } catch (error) {
        console.error('Ошибка загрузки сессий:', error);
        sessionsList.innerHTML = '<div class="sessions-loading">Ошибка загрузки</div>';
    }
}

function renderSessionsList(sessions, activeId) {
    const sessionsList = document.getElementById('sessionsList');
    if (!sessions || sessions.length === 0) {
        sessionsList.innerHTML = '<div class="sessions-loading">Нет сессий</div>';
        return;
    }

    sessionsList.innerHTML = sessions.map(session => `
        <div class="session-item ${session.id === activeId ? 'active' : ''}" data-id="${session.id}">
            <div class="session-info" onclick="activateSession(${session.id})">
                <div class="session-title">${escapeHtml(session.title)}</div>
            </div>
            <button class="session-delete" onclick="deleteSession(event, ${session.id})" title="Удалить">🗑️</button>
        </div>
    `).join('');
}

async function createNewSession() {
    const chatContainer = document.getElementById('chatContainer');
    try {
        const response = await fetch('/api/sessions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({})
        });

        const data = await response.json();

        if (data.success) {
            window.AppState.currentSessionId = data.session.id;
            await loadHistory();
            await loadSessions();
            await loadSessionStats();

            if (typeof loadCurrentProfileInfo === 'function') {
                await loadCurrentProfileInfo();
            }
            if (typeof loadSessionTitle === 'function') {
                await loadSessionTitle();
            }

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
    if (sessionId === window.AppState.currentSessionId) return;

    try {
        const response = await fetch('/api/sessions/' + sessionId + '/activate', {
            method: 'POST'
        });

        const data = await response.json();

        if (data.success) {
            window.AppState.currentSessionId = sessionId;
            await loadHistory();
            await loadSessions();
            await loadSessionStats();
            document.getElementById('statusText').textContent = 'Сессия активирована';

            const session = data.session;
            const memorySessionTitle = document.getElementById('memorySessionTitle');
            if (memorySessionTitle && session) {
                memorySessionTitle.textContent = session.title || 'Сессия #' + sessionId;
            }

            if (typeof loadCurrentProfileInfo === 'function') {
                await loadCurrentProfileInfo();
            }
            if (typeof loadWorkingMemory === 'function') {
                await loadWorkingMemory(window.AppState.currentSessionId);
            }

            await checkPausedTask(sessionId);
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
            if (window.AppState.currentSessionId === sessionId) {
                window.AppState.currentSessionId = null;
            }
            await loadSessions();
            
            if (!window.AppState.currentSessionId) {
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
            window.AppState.currentSessionId = data.session.id;
        }
    } catch (error) {
        console.error('Ошибка загрузки активной сессии:', error);
    }
}

async function loadSessionStats() {
    const sessionStats = document.getElementById('sessionStats');
    if (!window.AppState.currentSessionId) {
        sessionStats.style.display = 'none';
        return;
    }

    try {
        let url = `/api/sessions/${window.AppState.currentSessionId}/stats`;
        
        const strategyResponse = await fetch(`/api/sessions/${window.AppState.currentSessionId}/context-strategy`);
        const strategyData = await strategyResponse.json();
        
        if (strategyData.success && strategyData.strategy === 'BRANCHING') {
            const branchesResponse = await fetch(`/api/sessions/${window.AppState.currentSessionId}/branches`);
            const branchesData = await branchesResponse.json();
            
            if (branchesData.success && branchesData.branches) {
                const activeBranch = branchesData.branches.find(b => b.isActive);
                if (activeBranch) {
                    url = `/api/sessions/${window.AppState.currentSessionId}/branches/${activeBranch.id}/stats`;
                }
            }
        }
        
        const response = await fetch(url);
        const data = await response.json();

        if (data.success && data.stats) {
            sessionStats.style.display = 'flex';

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

function updateSessionTitleInList(title) {
    console.log('updateSessionTitleInList called with:', title, 'currentSessionId:', window.AppState.currentSessionId);
    const selector = `.session-item[data-id="${window.AppState.currentSessionId}"] .session-title`;
    console.log('Selector:', selector);
    const activeSession = document.querySelector(selector);
    console.log('Found element:', activeSession);
    if (activeSession) {
        activeSession.textContent = title;
        console.log('Title updated to:', title);
    } else {
        console.log('Element not found');
    }
}

async function checkPausedTask(sessionId) {
    try {
        const response = await fetch(`/api/sessions/${sessionId}/tasks`);
        const data = await response.json();
        const pausedTask = data.tasks?.find(t => t.state === 'PAUSED');
        
        if (pausedTask) {
            showResumeTaskDialog(pausedTask);
        }
    } catch (error) {
        console.error('Error checking paused task:', error);
    }
}

function showResumeTaskDialog(task) {
    const modal = document.getElementById('resumeTaskModal');
    if (!modal) return;
    
    document.getElementById('resumeTaskTitle').textContent = task.title;
    document.getElementById('resumeTaskReason').textContent = task.pauseReason || '';
    modal.classList.add('active');
    
    document.getElementById('resumeTaskBtn').onclick = () => resumePausedTask(task.id);
    document.getElementById('keepPausedBtn').onclick = () => modal.classList.remove('active');
}

async function resumePausedTask(taskId) {
    try {
        const response = await fetch(`/api/sessions/${window.AppState.currentSessionId}/tasks/${taskId}/resume`, {
            method: 'POST'
        });
        const data = await response.json();
        
        if (data.success) {
            document.getElementById('resumeTaskModal').classList.remove('active');
            if (typeof loadTasks === 'function') {
                await loadTasks();
            }
        } else {
            alert('Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('Ошибка: ' + error.message);
    }
}
