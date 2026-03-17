async function loadProviders() {
    try {
        const response = await fetch('/api/providers');
        const data = await response.json();
        
        if (data.success && data.providers) {
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
            window.AppState.availableModels = data.models;
        }
    } catch (error) {
        console.error('Error loading models:', error);
    }
}

async function loadModel() {
    try {
        const response = await fetch('/api/model');
        const data = await response.json();
        
        const modelSelect = document.getElementById('modelSelect');
        const modelText = document.getElementById('modelText');
        
        modelSelect.value = data.model;
        modelText.textContent = 'Модель: ' + data.modelName;
        
        const providerSelect = null;
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

async function loadMode() {
    try {
        const response = await fetch('/api/mode');
        const data = await response.json();
        
        const modeText = document.getElementById('modeText');
        const modeSelectSettings = document.getElementById('modeSelectSettings');
        
        modeText.textContent = 'Режим: ' + data.modeName;
        modeSelectSettings.value = String(data.mode);
    } catch (error) {
        console.error('Error loading mode:', error);
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

async function loadThinkingStatus() {
    try {
        const response = await fetch('/api/thinking');
        const data = await response.json();
        
        if (data.success) {
            const thinkingGroup = document.getElementById('thinkingGroup');
            const thinkingToggle = document.getElementById('thinkingToggle');
            const thinkingStatus = document.getElementById('thinkingStatus');
            
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

async function getActiveTask() {
    const sessionId = window.AppState.currentSessionId;
    try {
        console.log('Getting active task for session: ' + sessionId);
        const response = await fetch(`/api/sessions/${sessionId}/active-task`);
        const data = await response.json();
        const task = data.success && data.task ? data.task : null;
        console.log('Active task result:', task);
        return task;
    } catch (error) {
        console.error('Error getting active task:', error);
        return null;
    }
}
