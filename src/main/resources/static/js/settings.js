async function loadMode() {
    try {
        const response = await fetch('/api/mode');
        const data = await response.json();
        
        document.getElementById('modeText').textContent = 'Режим: ' + data.modeName;
        document.getElementById('modeSelectSettings').value = String(data.mode);
    } catch (error) {
        console.error('Error loading mode:', error);
    }
}

async function loadModel() {
    try {
        const response = await fetch('/api/model');
        const data = await response.json();
        
        document.getElementById('modelSelect').value = data.model;
        document.getElementById('modelText').textContent = 'Модель: ' + data.modelName;
        
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
            
            const maxTokensToggle = document.getElementById('maxTokensToggle');
            const maxTokensStatus = document.getElementById('maxTokensStatus');
            const maxTokensValueRow = document.getElementById('maxTokensValueRow');
            if (settings.maxTokensEnabled !== undefined) {
                maxTokensToggle.checked = settings.maxTokensEnabled;
                maxTokensStatus.textContent = settings.maxTokensEnabled ? 'Включено' : 'Выключено';
                maxTokensValueRow.style.opacity = settings.maxTokensEnabled ? '1' : '0.5';
            }
            
            const temperatureToggle = document.getElementById('temperatureToggle');
            const temperatureStatus = document.getElementById('temperatureStatus');
            const temperatureValueRow = document.getElementById('temperatureValueRow');
            if (settings.temperatureEnabled !== undefined) {
                temperatureToggle.checked = settings.temperatureEnabled;
                temperatureStatus.textContent = settings.temperatureEnabled ? 'Включено' : 'Выключено';
                temperatureValueRow.style.opacity = settings.temperatureEnabled ? '1' : '0.5';
            }

            if (providerSelect) {
                if (settings.model.startsWith('deepseek')) {
                    providerSelect.value = 'deepseek';
                } else if (settings.model.includes('/')) {
                    providerSelect.value = 'openrouter';
                }
            }

            window.AppState.availableModels = (settings.availableModels || []).map(id => ({ id, displayName: id }));
        }
        
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
    const modeSelectSettings = document.getElementById('modeSelectSettings');
    const mode = parseInt(modeSelectSettings.value);
    const chatContainer = document.getElementById('chatContainer');
    
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
            document.getElementById('modeText').textContent = 'Режим: ' + data.modeName;
            document.getElementById('statusText').textContent = data.message;
            
            const systemResponse = await fetch('/api/system');
            const systemData = await systemResponse.json();
            if (systemData.success) {
                document.getElementById('systemPromptInput').value = systemData.systemPrompt;
                document.getElementById('systemModeInfo').textContent = systemData.modeDescription;
            }
            
            alert('✅ Режим изменён на: ' + data.modeName);
            
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
            document.getElementById('statusText').textContent = data.message;
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
            document.getElementById('statusText').textContent = data.message;
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
    const chatContainer = document.getElementById('chatContainer');
    
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
            document.getElementById('statusText').textContent = data.message;
            alert('✅ Системный промпт обновлён');
            
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
    const modeSelectSettings = document.getElementById('modeSelectSettings');
    const mode = parseInt(modeSelectSettings.value);
    const defaultPrompt = mode === 1 
        ? 'Ты senior тестировщик из Google с 10+ годами опыта. Объясняй концепции тестирования простыми словами, как будто объясняешь джуниору на первом дне работы. Используй практические примеры из реальной разработки. Отвечай кратко и структурированно.'
        : 'Ты полезный помощник';
    
    document.getElementById('systemPromptInput').value = defaultPrompt;
    
    if (confirm('Сбросить системный промпт на стандартный для текущего режима?')) {
        await saveSystemPrompt();
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
            document.getElementById('statusText').textContent = data.message;
        } else {
            thinkingToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        thinkingToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}

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
            document.getElementById('statusText').textContent = data.message;
        } else {
            maxTokensToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        maxTokensToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}

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
            document.getElementById('statusText').textContent = data.message;
        } else {
            temperatureToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        temperatureToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}

async function changeModel() {
    const modelSelect = document.getElementById('modelSelect');
    const model = modelSelect.value;
    
    if (providerSelect) {
        if (model.startsWith('deepseek')) {
            providerSelect.value = 'deepseek';
        } else if (model.includes('/')) {
            providerSelect.value = 'openrouter';
        }
    }
    
    try {
        document.getElementById('statusText').textContent = 'Смена модели...';
        
        const response = await fetch('/api/model', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ model })
        });
        
        const data = await response.json();

        if (data.success) {
            document.getElementById('modelText').textContent = 'Модель: ' + data.modelName;
            document.getElementById('statusText').textContent = data.message;
            loadThinkingStatus();
        } else {
            document.getElementById('statusText').textContent = 'Ошибка: ' + (data.error || 'Неизвестная ошибка');
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
            loadModel();
        }
    } catch (error) {
        console.error('Error changing model:', error);
        document.getElementById('statusText').textContent = 'Ошибка при смене модели';
        alert('Ошибка при смене модели');
        loadModel();
    }
}
