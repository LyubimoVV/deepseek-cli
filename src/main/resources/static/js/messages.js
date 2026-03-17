function showTyping() {
    const chatContainer = document.getElementById('chatContainer');
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

function addMessage(role, content, isLimited = false, metrics = null, isTaskNote = false, taskId = null, taskState = null, stepIndex = null, autoScroll = true) {
    const chatContainer = document.getElementById('chatContainer');
    const welcome = chatContainer.querySelector('.welcome-message');
    if (welcome) {
        welcome.remove();
    }

    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${role}${isLimited ? ' limited' : ''}${isTaskNote ? ' task-note collapsed' : ''}`;
    
    if (stepIndex !== null) {
        messageDiv.dataset.stepIndex = stepIndex;
    }

    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = role === 'user' ? '👤' : (isLimited ? '🔬' : '🤖');

    if (isTaskNote && taskId) {
        avatar.onclick = () => toggleTaskDetails(messageDiv, taskId, taskState, stepIndex);
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
        contentDiv.onclick = () => toggleTaskDetails(messageDiv, taskId, taskState, stepIndex);
    }

    messageDiv.appendChild(avatar);
    messageDiv.appendChild(contentDiv);
    chatContainer.appendChild(messageDiv);

    if (autoScroll) {
        setTimeout(() => {
            chatContainer.scrollTop = chatContainer.scrollHeight;
        }, 50);
    }
}

async function addMessageWithTyping(role, content, isLimited = false, metrics = null) {
    const chatContainer = document.getElementById('chatContainer');
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
    
    await typeText(textDiv, content);
    
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
    
    chatContainer.scrollTop = chatContainer.scrollHeight;
}

async function typeText(element, text) {
    const chatContainer = document.getElementById('chatContainer');
    const chars = text.split('');
    let currentText = '';
    const cursor = document.createElement('span');
    cursor.className = 'typing-cursor';
    window.AppState.typingCancelled = false;
    
    window.typingStartTime = Date.now();
    window.typingElement = element;
    window.typingText = text;
    
    const avgDelay = 9.5;
    let lastUpdateTime = window.typingStartTime;
    
    return new Promise(resolve => {
        function update() {
            if (window.AppState.typingCancelled) {
                window.typingStartTime = null;
                window.typingElement = null;
                window.typingText = null;
                cursor.remove();
                resolve();
                return;
            }
            
            const now = Date.now();
            const elapsed = now - window.typingStartTime;
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
                
                if (!window.AppState.userScrolled) {
                    chatContainer.scrollTop = chatContainer.scrollHeight;
                }
                
                lastUpdateTime = now;
            }
            
            if (currentText.length < chars.length) {
                requestAnimationFrame(update);
            } else {
                window.typingStartTime = null;
                window.typingElement = null;
                window.typingText = null;
                cursor.remove();
                resolve();
            }
        }
        
        requestAnimationFrame(update);
    });
}

async function toggleTaskDetails(messageDiv, taskId, taskState, stepIndex) {
    if (!taskId || !taskState) return;
    const sessionId = window.AppState.currentSessionId;

    const detailsKey = `details-${taskId}-${taskState}-${stepIndex || 'all'}`;
    const isExpanded = messageDiv.classList.contains('expanded');
    messageDiv.classList.toggle('expanded');
    messageDiv.classList.toggle('collapsed');

    if (!isExpanded) {
        if (window.AppState.openDetails.has(detailsKey)) {
            const existing = document.getElementById(detailsKey);
            if (existing) existing.remove();
            window.AppState.openDetails.delete(detailsKey);
            return;
        }

        try {
            const response = await fetch(`/api/sessions/${sessionId}/tasks/${taskId}/messages/${taskState}`);
            const data = await response.json();

            if (data.success && data.messages && data.messages.length > 0) {
                const contentDiv = messageDiv.querySelector('.message-content');
                const existingDetails = contentDiv.querySelector('.task-details');

                if (existingDetails) {
                    existingDetails.remove();
                }

                let targetMsg = null;
                if (stepIndex !== null) {
                    targetMsg = data.messages.find(m => m.stepIndex === stepIndex);
                }

                const detailsDiv = document.createElement('div');
                detailsDiv.id = detailsKey;
                detailsDiv.className = 'task-details';
                detailsDiv.style.marginTop = '0.5rem';
                detailsDiv.style.padding = '0.5rem';
                detailsDiv.style.background = 'rgba(0, 0, 0, 0.05)';
                detailsDiv.style.borderRadius = '0.5rem';

                if (targetMsg) {
                    detailsDiv.innerHTML = `
                        <div style="font-weight: 600; margin-bottom: 0.25rem;">Шаг ${targetMsg.stepIndex}:</div>
                        <div style="font-size: 0.9em; white-space: pre-wrap;">${escapeHtml(targetMsg.response)}</div>
                    `;
                } else {
                    const stepsHtml = data.messages.map((msg, idx) => `
                        <div class="step-item" data-msg-id="${msg.id}" style="margin-bottom: 0.5rem; padding-bottom: 0.5rem; border-bottom: 1px solid rgba(0,0,0,0.1);">
                            <div style="font-weight: 600; margin-bottom: 0.25rem;">Шаг ${msg.stepIndex || idx + 1}:</div>
                            <div style="font-size: 0.9em; white-space: pre-wrap;">${escapeHtml(msg.response)}</div>
                        </div>
                    `).join('');
                    detailsDiv.innerHTML = `
                        <div style="font-weight: 600; margin-bottom: 0.5rem;">Детали этапа (${data.messages.length} шагов):</div>
                        <div class="steps-list">${stepsHtml}</div>
                    `;
                }
                contentDiv.appendChild(detailsDiv);
                window.AppState.openDetails.add(detailsKey);
            }
        } catch (error) {
            console.error('Error loading task details:', error);
        }
    } else {
        const contentDiv = messageDiv.querySelector('.message-content');
        const existingDetails = contentDiv.querySelector('.task-details');
        if (existingDetails) {
            existingDetails.remove();
            window.AppState.openDetails.delete(detailsKey);
        }
    }
}
