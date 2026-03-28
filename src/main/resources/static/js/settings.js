async function loadTsmStatus() {
    try {
        const response = await fetch('/api/tsm');
        const data = await response.json();
        
        if (data.success) {
            const tsmToggle = document.getElementById('tsmToggle');
            const tsmStatus = document.getElementById('tsmStatus');
            
            tsmToggle.checked = data.tsmEnabled;
            tsmStatus.textContent = data.tsmEnabled ? 'Включена' : 'Выключена';
        }
    } catch (error) {
        console.error('Error loading TSM status:', error);
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
            document.getElementById('maxTokensInput').value = settings.maxTokens;
            document.getElementById('temperatureInput').value = settings.temperature;
            document.getElementById('temperatureValue').textContent = settings.temperature;
            document.getElementById('systemPromptInput').value = settings.systemPrompt;
            document.getElementById('modelSelect').value = settings.model;
            
            const tsmToggle = document.getElementById('tsmToggle');
            const tsmStatus = document.getElementById('tsmStatus');
            if (settings.tsmEnabled !== undefined) {
                tsmToggle.checked = settings.tsmEnabled;
                tsmStatus.textContent = settings.tsmEnabled ? 'Включена' : 'Выключена';
            }
            
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
            
            const stopSequencesToggle = document.getElementById('stopSequencesToggle');
            const stopSequencesStatus = document.getElementById('stopSequencesStatus');
            const stopSequencesValueRow = document.getElementById('stopSequencesValueRow');
            const stopSequencesInput = document.getElementById('stopSequencesInput');
            if (settings.stopSequencesEnabled !== undefined) {
                stopSequencesToggle.checked = settings.stopSequencesEnabled;
                stopSequencesStatus.textContent = settings.stopSequencesEnabled ? 'Включено' : 'Выключено';
                stopSequencesValueRow.style.opacity = settings.stopSequencesEnabled ? '1' : '0.5';
                if (settings.stopSequences) {
                    stopSequencesInput.value = settings.stopSequences.join(', ');
                }
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
        await loadRagStatus();
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
    const defaultPrompt = 'Ты полезный помощник';
    
    document.getElementById('systemPromptInput').value = defaultPrompt;
    
    if (confirm('Сбросить системный промпт на стандартный?')) {
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

async function toggleTsm() {
    const tsmToggle = document.getElementById('tsmToggle');
    const tsmStatus = document.getElementById('tsmStatus');
    const enabled = tsmToggle.checked;
    
    try {
        const response = await fetch('/api/tsm', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ enabled })
        });
        
        const data = await response.json();
        
        if (data.success) {
            tsmStatus.textContent = enabled ? 'Включена' : 'Выключена';
            document.getElementById('statusText').textContent = data.message;
        } else {
            tsmToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        tsmToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}

async function loadRagStatus() {
    try {
        const response = await fetch('/api/rag');
        const data = await response.json();
        
        if (data.success) {
            const ragToggle = document.getElementById('ragToggle');
            const ragStatus = document.getElementById('ragStatus');
            const ragStrategyRow = document.getElementById('ragStrategyRow');
            
            ragToggle.checked = data.ragEnabled;
            
            if (!data.ragAvailable) {
                ragToggle.disabled = true;
                ragStatus.textContent = 'Недоступен (Ollama не найдена)';
                ragStrategyRow.style.opacity = '0.5';
            } else {
                ragStatus.textContent = data.ragEnabled ? 'Включён' : 'Выключен';
                if (data.chunksCount !== undefined) {
                    ragStatus.textContent += ` (${data.chunksCount} чанков)`;
                }
                ragStrategyRow.style.opacity = data.ragEnabled ? '1' : '0.5';
            }
            
            await loadRagStrategy();
        }
    } catch (error) {
        console.error('Error loading RAG status:', error);
    }
}

async function loadRagStrategy() {
    try {
        const response = await fetch('/api/rag/strategy');
        const data = await response.json();
        
        if (data.success) {
            document.getElementById('ragStrategySelect').value = data.strategy;
        }
    } catch (error) {
        console.error('Error loading RAG strategy:', error);
    }
}

async function toggleRag() {
    const ragToggle = document.getElementById('ragToggle');
    const ragStatus = document.getElementById('ragStatus');
    const ragStrategyRow = document.getElementById('ragStrategyRow');
    const enabled = ragToggle.checked;
    
    try {
        const response = await fetch('/api/rag', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ enabled })
        });
        
        const data = await response.json();
        
        if (data.success) {
            ragStatus.textContent = enabled ? 'Включён' : 'Выключен';
            document.getElementById('statusText').textContent = data.message;
            ragStrategyRow.style.opacity = enabled ? '1' : '0.5';
        } else {
            ragToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        ragToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}

async function saveRagStrategy() {
    const strategy = document.getElementById('ragStrategySelect').value;
    
    try {
        const response = await fetch('/api/rag/strategy', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ strategy })
        });
        
        const data = await response.json();
        
        if (data.success) {
            document.getElementById('statusText').textContent = data.message;
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function reindexRag() {
    const btn = document.getElementById('reindexRagBtn');
    const originalText = btn.textContent;
    
    if (!confirm('Переиндексировать базу знаний? Старый индекс будет очищен.')) return;
    
    btn.disabled = true;
    btn.textContent = '⏳ Индексация...';
    
    try {
        const response = await fetch('/api/rag/reindex', {
            method: 'POST'
        });
        
        const data = await response.json();
        
        if (data.success) {
            document.getElementById('statusText').textContent = data.message;
            await loadRagStatus();
            alert('✅ ' + data.message);
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    } finally {
        btn.disabled = false;
        btn.textContent = originalText;
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

async function toggleStopSequences() {
    const stopSequencesToggle = document.getElementById('stopSequencesToggle');
    const stopSequencesStatus = document.getElementById('stopSequencesStatus');
    const stopSequencesValueRow = document.getElementById('stopSequencesValueRow');
    const enabled = stopSequencesToggle.checked;

    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'stop_sequences_enabled', value: enabled })
        });

        const data = await response.json();

        if (data.success) {
            stopSequencesStatus.textContent = enabled ? 'Включено' : 'Выключено';
            stopSequencesValueRow.style.opacity = enabled ? '1' : '0.5';
            document.getElementById('statusText').textContent = data.message;
        } else {
            stopSequencesToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        stopSequencesToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}

async function saveStopSequences() {
    const value = document.getElementById('stopSequencesInput').value.trim();
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'stop_sequences', value: value })
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

async function loadRerankerStatus() {
    try {
        const response = await fetch('/api/rag/reranker');
        const data = await response.json();
        
        if (data.success) {
            const rerankerToggle = document.getElementById('rerankerToggle');
            const rerankerStatus = document.getElementById('rerankerStatus');
            const rerankerSettings = document.getElementById('rerankerSettings');
            
            rerankerToggle.checked = data.rerankerEnabled;
            
            if (!data.rerankerAvailable) {
                rerankerToggle.disabled = true;
                rerankerStatus.textContent = 'Недоступен (модель не найдена)';
                rerankerSettings.style.opacity = '0.5';
            } else {
                rerankerStatus.textContent = data.rerankerEnabled ? 'Включён' : 'Выключен';
                if (data.rerankerModel) {
                    rerankerStatus.textContent += ' (' + data.rerankerModel + ')';
                }
                rerankerSettings.style.opacity = data.rerankerEnabled ? '1' : '0.5';
            }
            
            document.getElementById('rerankerThresholdInput').value = data.threshold;
            document.getElementById('rerankerThresholdValue').textContent = data.threshold.toFixed(2);
            document.getElementById('rerankerTopKBeforeInput').value = data.topKBefore;
            document.getElementById('rerankerTopKAfterInput').value = data.topKAfter;
        }
    } catch (error) {
        console.error('Error loading reranker status:', error);
    }
}

async function toggleReranker() {
    const rerankerToggle = document.getElementById('rerankerToggle');
    const rerankerStatus = document.getElementById('rerankerStatus');
    const rerankerSettings = document.getElementById('rerankerSettings');
    const enabled = rerankerToggle.checked;
    
    try {
        const response = await fetch('/api/rag/reranker', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ enabled })
        });
        
        const data = await response.json();
        
        if (data.success) {
            rerankerStatus.textContent = enabled ? 'Включён' : 'Выключен';
            document.getElementById('statusText').textContent = data.message;
            rerankerSettings.style.opacity = enabled ? '1' : '0.5';
        } else {
            rerankerToggle.checked = !enabled;
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        rerankerToggle.checked = !enabled;
        alert('Ошибка соединения: ' + error.message);
    }
}

async function saveRerankerSettings() {
    const threshold = parseFloat(document.getElementById('rerankerThresholdInput').value);
    const topKBefore = parseInt(document.getElementById('rerankerTopKBeforeInput').value);
    const topKAfter = parseInt(document.getElementById('rerankerTopKAfterInput').value);
    
    try {
        const response = await fetch('/api/rag/reranker', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ 
                threshold, 
                topKBefore, 
                topKAfter 
            })
        });
        
        const data = await response.json();
        
        if (data.success) {
            document.getElementById('statusText').textContent = data.message;
            document.getElementById('rerankerThresholdValue').textContent = data.threshold.toFixed(2);
            alert('✅ ' + data.message);
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

function initRerankerThresholdSlider() {
    const slider = document.getElementById('rerankerThresholdInput');
    const valueDisplay = document.getElementById('rerankerThresholdValue');
    
    if (slider && valueDisplay) {
        slider.addEventListener('input', function() {
            valueDisplay.textContent = parseFloat(this.value).toFixed(2);
        });
    }
}
