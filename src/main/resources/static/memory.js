// ==================== UTILITIES ====================

function showToast(message, type = 'success') {
    // Создаем toast элемент
    const toast = document.createElement('div');
    toast.textContent = message;
    toast.style.cssText = `
        position: fixed;
        bottom: 20px;
        right: 20px;
        padding: 12px 24px;
        background: ${type === 'error' ? '#ef4444' : '#10b981'};
        color: white;
        border-radius: 8px;
        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
        z-index: 10000;
        animation: slideIn 0.3s ease;
    `;

    document.body.appendChild(toast);

    // Удаляем через 3 секунды
    setTimeout(() => {
        toast.style.animation = 'slideOut 0.3s ease';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ==================== PROFILES ====================

async function loadProfiles() {
    try {
        const response = await fetch('/api/profiles');
        const data = await response.json();

        if (data.success) {
            renderProfilesList(data.profiles);
        }
    } catch (error) {
        console.error('Ошибка загрузки профилей:', error);
    }
}

function renderProfilesList(profiles) {
    const container = document.getElementById('profilesMainList');
    if (!container) return;

    if (!profiles || profiles.length === 0) {
        container.innerHTML = '<p style="color: #666; padding: 1rem;">Нет профилей</p>';
        return;
    }

    const isActive = currentProfile && currentProfile.id === profiles.find(p => p.id === currentProfile.id)?.id;

    container.innerHTML = profiles.map(profile => `
        <div class="profile-card ${currentProfile && currentProfile.id === profile.id ? 'active' : ''}" onclick="selectProfileAsActive(${profile.id})">
            <h4>${escapeHtml(profile.name)}</h4>
            <p>${escapeHtml(profile.description || 'Без описания')}</p>
        </div>
    `).join('');
}

async function selectProfileAsActive(profileId) {
    const sessionId = window.AppState?.currentSessionId;
    if (!sessionId) {
        alert('Сначала выберите сессию');
        return;
    }

    try {
        const response = await fetch(`/api/sessions/${sessionId}/set-profile/${profileId}`, {
            method: 'POST'
        });

        const data = await response.json();

        if (data.success) {
            showToast('✅ Профиль выбран');
            await loadCurrentProfileInfo();
            await loadProfiles();
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка выбора профиля:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

async function createProfile(name, description, systemPrompt, personalization) {
    try {
        const response = await fetch('/api/profiles', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, description, systemPrompt, personalization })
        });

        const data = await response.json();

        if (data.success) {
            showToast('✅ Профиль создан');
            loadProfiles();
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка создания профиля:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

async function deleteProfile(id) {
    if (!confirm('Вы уверены, что хотите удалить этот профиль?')) {
        return;
    }

    try {
        const response = await fetch(`/api/profiles/${id}`, {
            method: 'DELETE'
        });

        const data = await response.json();

        if (data.success) {
            showToast('✅ Профиль удалён');
            loadProfiles();
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка удаления профиля:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

function openProfileModal(profile = null) {
    const modal = document.getElementById('profileEditModal');
    const title = document.getElementById('profileEditModalTitle');

    if (profile) {
        title.textContent = 'Редактировать профиль';
        modal.dataset.profileId = profile.id;
        document.getElementById('profileNameInput').value = profile.name;
        document.getElementById('profileDescriptionInput').value = profile.description || '';
        document.getElementById('profileSystemPromptInput').value = profile.systemPrompt || '';
        document.getElementById('profilePersonalizationInput').value = profile.personalization || '';
    } else {
        title.textContent = 'Создать профиль';
        delete modal.dataset.profileId;
        document.getElementById('profileNameInput').value = '';
        document.getElementById('profileDescriptionInput').value = '';
        document.getElementById('profileSystemPromptInput').value = '';
        document.getElementById('profilePersonalizationInput').value = '';
    }

    modal.classList.add('active');
}

function closeProfileEditModal() {
    const modal = document.getElementById('profileEditModal');
    if (modal) {
        modal.classList.remove('active');
        delete modal.dataset.profileId;
    }
}

async function saveProfile() {
    const name = document.getElementById('profileNameInput').value.trim();
    const description = document.getElementById('profileDescriptionInput').value.trim();
    const systemPrompt = document.getElementById('profileSystemPromptInput').value.trim();
    const personalization = document.getElementById('profilePersonalizationInput').value.trim();

    if (!name) {
        alert('Введите имя профиля');
        return;
    }

    const modal = document.getElementById('profileEditModal');
    const profileId = modal.dataset.profileId;

    try {
        let url = '/api/profiles';
        let method = 'POST';

        if (profileId) {
            url = `/api/profiles/${profileId}`;
            method = 'PUT';
        }

        const response = await fetch(url, {
            method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, description, systemPrompt, personalization })
        });

        const data = await response.json();

        if (data.success) {
            showToast('✅ Профиль сохранён');
            loadProfiles();
            closeProfileEditModal();
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка сохранения профиля:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

async function editCurrentProfile(profileId) {
    try {
        const response = await fetch(`/api/profiles/${profileId}`);
        const data = await response.json();

        if (data.success) {
            const profile = data.profile;
            openProfileModal(profile);
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка получения профиля:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

async function updateProfile(id, name, description, systemPrompt, personalization) {
    try {
        const response = await fetch(`/api/profiles/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, description, systemPrompt, personalization })
        });

        const data = await response.json();

        if (data.success) {
            showToast('✅ Профиль обновлён');
            loadProfiles();
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка обновления профиля:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

// ==================== MEMORY ====================

async function loadWorkingMemory(sessionId) {
    try {
        const response = await fetch(`/api/sessions/${sessionId}/memory/working`);
        const data = await response.json();

        if (data.success) {
            renderWorkingMemory(data.memory);
        }
    } catch (error) {
        console.error('Ошибка загрузки рабочей памяти:', error);
    }
}

async function loadLongTermMemory(profileId) {
    try {
        const response = await fetch(`/api/profiles/${profileId}/memory/longterm`);
        const data = await response.json();

        if (data.success) {
            renderLongTermMemory(data.memory);
        }
    } catch (error) {
        console.error('Ошибка загрузки долгосрочной памяти:', error);
    }
}

function renderWorkingMemory(memory) {
    const container = document.getElementById('workingMemoryList');
    if (!container) return;

    if (!memory || memory.length === 0) {
        container.innerHTML = '<p style="color: #666; padding: 1rem;">Нет записей в рабочей памяти</p>';
        return;
    }

    const grouped = {};
    memory.forEach(m => {
        if (!grouped[m.category]) grouped[m.category] = [];
        grouped[m.category].push(m);
    });

    container.innerHTML = Object.entries(grouped).map(([category, items]) => `
        <div style="margin-bottom: 1rem;">
            <div class="memory-list-category">${category}</div>
            ${items.map(item => `
                <div class="memory-list-item">
                    <div class="memory-list-content">
                        <span class="memory-list-key">${item.key}</span>
                        <span class="memory-list-value">${item.value}</span>
                    </div>
                    <div class="memory-list-actions">
                        <button onclick="deleteWorkingMemory(${item.id})" class="btn-small">🗑️</button>
                    </div>
                </div>
            `).join('')}
        </div>
    `).join('');
}

function renderLongTermMemory(memory) {
    const container = document.getElementById('longTermMemoryList');
    if (!container) return;

    if (!memory || memory.length === 0) {
        container.innerHTML = '<p style="color: #666; padding: 1rem;">Нет записей в долгосрочной памяти</p>';
        return;
    }

    const grouped = {};
    memory.forEach(m => {
        if (!grouped[m.category]) grouped[m.category] = [];
        grouped[m.category].push(m);
    });

    container.innerHTML = Object.entries(grouped).map(([category, items]) => `
        <div style="margin-bottom: 1rem;">
            <div class="memory-list-category">${category}</div>
            ${items.map(item => `
                <div class="memory-list-item">
                    <div class="memory-list-content">
                        <span class="memory-list-key">${item.key}</span>
                        <span class="memory-list-value">${item.value}</span>
                    </div>
                    <div class="memory-list-actions">
                        <button onclick="deleteLongTermMemory(${item.id})" class="btn-small">🗑️</button>
                    </div>
                </div>
            `).join('')}
        </div>
    `).join('');
}

async function saveWorkingMemory() {
    const sessionId = window.AppState?.currentSessionId;
    if (!sessionId) {
        alert('Сначала выберите сессию');
        return;
    }

    const category = document.getElementById('workingCategory').value;
    const key = document.getElementById('workingKey').value.trim();
    const value = document.getElementById('workingValue').value.trim();

    if (!key || !value) {
        alert('Введите ключ и значение');
        return;
    }

    try {
        const response = await fetch(`/api/sessions/${sessionId}/memory/working`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ category, key, value })
        });

        const data = await response.json();

        if (data.success) {
            document.getElementById('workingKey').value = '';
            document.getElementById('workingValue').value = '';
            const sessionId = window.AppState?.currentSessionId;
            if (sessionId) loadWorkingMemory(sessionId);
            showToast('✅ Запись сохранена');
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка сохранения рабочей памяти:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

async function saveLongTermMemory() {
    const profileId = document.getElementById('profileSelect').value;
    if (!profileId) {
        alert('Выберите профиль');
        return;
    }

    const category = document.getElementById('longTermCategory').value;
    const key = document.getElementById('longTermKey').value.trim();
    const value = document.getElementById('longTermValue').value.trim();

    if (!key || !value) {
        alert('Введите ключ и значение');
        return;
    }

    try {
        const response = await fetch(`/api/profiles/${profileId}/memory/longterm`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ category, key, value })
        });

        const data = await response.json();

        if (data.success) {
            document.getElementById('longTermKey').value = '';
            document.getElementById('longTermValue').value = '';
            loadLongTermMemory(profileId);
            showToast('✅ Запись сохранена');
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка сохранения долгосрочной памяти:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

async function deleteWorkingMemory(id) {
    if (!confirm('Удалить эту запись?')) {
        return;
    }

    const sessionId = window.AppState?.currentSessionId;
    try {
        const response = await fetch(`/api/sessions/${sessionId}/memory/working/${id}`, {
            method: 'DELETE'
        });

        const data = await response.json();

        if (data.success) {
            const sessionId = window.AppState?.currentSessionId;
            if (sessionId) loadWorkingMemory(sessionId);
            showToast('✅ Запись удалена');
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка удаления рабочей памяти:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

async function deleteLongTermMemory(id) {
    if (!confirm('Удалить эту запись?')) {
        return;
    }

    try {
        const response = await fetch(`/api/profiles/${profileSelect.value}/memory/longterm/${id}`, {
            method: 'DELETE'
        });

        const data = await response.json();

        if (data.success) {
            loadLongTermMemory(profileSelect.value);
            showToast('✅ Запись удалена');
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка удаления долгосрочной памяти:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

// ==================== SUGGESTIONS ====================

async function loadMemorySuggestions(sessionId) {
    try {
        const response = await fetch(`/api/sessions/${sessionId}/memory/suggestions`);
        const data = await response.json();

        if (data.success) {
            renderSuggestions(data.suggestions);
        updateMemoryTabBadge(data.suggestions.length);
        }
    } catch (error) {
        console.error('Ошибка загрузки предложений:', error);
    }
}

function renderSuggestions(suggestions) {
    const container = document.getElementById('suggestionsList');
    if (!container) return;

    if (!suggestions || suggestions.length === 0) {
        container.innerHTML = '<p style="color: #666; padding: 1rem;">Нет предложений</p>';
        container.dataset.suggestions = '[]';
        return;
    }

    container.innerHTML = suggestions.map(s => `
        <div class="suggestion-card" data-key="${escapeHtml(s.key)}" data-layer="${s.layer}" data-category="${escapeHtml(s.category)}" data-value="${escapeHtml(s.value)}" data-explanation="${escapeHtml(s.explanation)}" data-confidence="${s.confidence}">
            <div class="suggestion-header">
                <span class="suggestion-key">${escapeHtml(s.key)}</span>
                <span class="suggestion-confidence">Уверенность: ${(s.confidence * 100).toFixed(0)}%</span>
            </div>
            <div class="suggestion-content">
                <span class="suggestion-value">${escapeHtml(s.value)}</span>
                <p class="suggestion-explanation">${escapeHtml(s.explanation)}</p>
            </div>
            <div class="suggestion-actions">
                <span class="suggestion-layer ${s.layer}">${s.layer === 'WORKING' ? 'WORKING' : 'LONG_TERM'}</span>
                <button class="btn-small" data-action="save">✅ Сохранить</button>
                <button class="btn-small btn-secondary" data-action="dismiss">✕ Отклонить</button>
            </div>
        </div>
    `).join('');
}

async function saveSuggestion(suggestion) {
    try {
        const sessionId = window.AppState?.currentSessionId;
        let profileId = null;

        if (suggestion.layer === 'LONG_TERM') {
            const sessionResponse = await fetch(`/api/sessions/${sessionId}`);
            const sessionData = await sessionResponse.json();
            const session = sessionData.session;
            profileId = session.profileId;
        }

        const url = suggestion.layer === 'WORKING'
            ? `/api/sessions/${sessionId}/memory/working`
            : `/api/profiles/${profileId}/memory/longterm`;

        const response = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ category: suggestion.category, key: suggestion.key, value: suggestion.value })
        });

        const data = await response.json();

        if (data.success) {
            showToast('✅ Сохранено в ' + (suggestion.layer === 'WORKING' ? 'рабочую' : 'долгосрочную') + ' память');
            const currentSessionId = window.AppState?.currentSessionId;
            if (currentSessionId) await loadMemorySuggestions(currentSessionId);
        } else {
            alert('Ошибка: ' + (data.error || 'Неизвестная ошибка'));
        }
    } catch (error) {
        console.error('Ошибка сохранения предложения:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

function dismissSuggestion(key) {
    const currentSessionId = window.AppState?.currentSessionId;
    if (currentSessionId) loadMemorySuggestions(currentSessionId);
}

async function handleSuggestionClick(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;

    const card = button.closest('.suggestion-card');
    if (!card) return;

    const action = button.dataset.action;
    const suggestion = {
        key: card.dataset.key,
        layer: card.dataset.layer,
        category: card.dataset.category,
        value: card.dataset.value,
        explanation: card.dataset.explanation,
        confidence: parseFloat(card.dataset.confidence)
    };

    if (action === 'save') {
        await saveSuggestion(suggestion);
    } else if (action === 'dismiss') {
        dismissSuggestion(suggestion.key);
    }
}

async function extractSuggestions() {
    const sessionId = window.AppState?.currentSessionId;
    if (!sessionId) {
        alert('Сначала выберите сессию');
        return;
    }

    try {
        const response = await fetch(`/api/sessions/${sessionId}/memory/suggest`, {
            method: 'POST'
        });

        const data = await response.json();

        if (data.success) {
            showToast('🔮 Запущено извлечение фактов');
            await new Promise(resolve => setTimeout(resolve, 1000));
            await loadMemorySuggestions(sessionId);
        }
    } catch (error) {
        console.error('Ошибка извлечения фактов:', error);
        showToast('❌ ' + error.message, 'error');
    }
}

function updateMemoryTabBadge(count) {
    const badge = document.getElementById('memoryTabBadge');
    if (!badge) return;
    if (count > 0) {
        badge.textContent = `🧠 ${count}`;
        badge.style.display = 'inline';
    } else {
        badge.style.display = 'none';
    }
}

function onMemoryTabShown() {
    const sessionId = window.AppState?.currentSessionId;
    if (sessionId) {
        updateMemoryTabBadge(0);
        markSuggestionsAsViewed(sessionId);
    }
}

async function markSuggestionsAsViewed(sessionId) {
    if (!sessionId) return;
    try {
        await fetch(`/api/sessions/${sessionId}/memory/suggestions/viewed`, {
            method: 'POST'
        });
    } catch (error) {
        console.error('Ошибка отметки предложений как просмотренных:', error);
    }
}

async function loadSessionTitle() {
    const sessionId = window.AppState?.currentSessionId;
    if (!sessionId) return;

    try {
        const response = await fetch(`/api/sessions/${sessionId}`);
        const data = await response.json();

        if (data.success && data.session) {
            const memorySessionTitle = document.getElementById('memorySessionTitle');
            if (memorySessionTitle) {
                memorySessionTitle.textContent = data.session.title || 'Сессия #' + sessionId;
            }
        }
    } catch (error) {
        console.error('Ошибка загрузки заголовка сессии:', error);
    }
}

// ==================== INITIALIZATION ====================

let profiles = [];
let currentProfile = null;

async function initializeMemoryFeatures() {
    await loadProfiles();
    await loadCurrentProfileInfo();
    await loadProfileSelect();
    await loadSessionTitle();

    const sessionId = window.AppState?.currentSessionId;
    if (sessionId) {
        await loadMemorySuggestions(sessionId);
        await loadWorkingMemory(sessionId);
    }

    // Добавляем обработчик для кнопки редактирования профиля
    const editBtn = document.getElementById('editCurrentProfileBtn');
    if (editBtn && currentProfile) {
        editBtn.onclick = () => editCurrentProfile(currentProfile.id);
    }

    // Загружаем профили при открытии вкладки профилей
    const profilesTab = document.querySelector('[data-tab="profiles"]');
    if (profilesTab) {
        profilesTab.addEventListener('click', async () => {
            await loadProfiles();
            await loadCurrentProfileInfo();
        });
    }

    // Загружаем память при открытии вкладки память
    const memoryTab = document.querySelector('[data-tab="memory"]');
    if (memoryTab) {
        memoryTab.addEventListener('click', async () => {
            const sessionId = window.AppState?.currentSessionId;
            onMemoryTabShown();
            if (sessionId) {
                await loadWorkingMemory(sessionId);
                await loadMemorySuggestions(sessionId);
                startSuggestionsPolling(sessionId);
            }
        });
    }
});

let suggestionsPollingInterval = null;

function startSuggestionsPolling(sessionId) {
    stopSuggestionsPolling();
    suggestionsPollingInterval = setInterval(async () => {
        if (window.AppState?.currentSessionId === sessionId) {
            await loadMemorySuggestions(sessionId);
        }
    }, 5000);
}

function stopSuggestionsPolling() {
    if (suggestionsPollingInterval) {
        clearInterval(suggestionsPollingInterval);
        suggestionsPollingInterval = null;
    }
}

async function loadCurrentProfileInfo() {
    const sessionId = window.AppState?.currentSessionId;
    if (!sessionId) return;

    try {
        const response = await fetch(`/api/sessions/${sessionId}`);
        const data = await response.json();

        if (data.success && data.session && data.session.profileId) {
            const profileResponse = await fetch(`/api/profiles/${data.session.profileId}`);
            const profileData = await profileResponse.json();

            if (profileData.success) {
                currentProfile = profileData.profile;
                const info = document.getElementById('currentProfileInfo');
                if (info) {
                    info.innerHTML = `
                        <div class="profile-card active">
                            <h4>${escapeHtml(currentProfile.name)}</h4>
                            <p>${escapeHtml(currentProfile.description || 'Без описания')}</p>
                            <div class="profile-card-actions">
                                <button onclick="editCurrentProfile(${currentProfile.id})" class="btn-small">✏️ Редактировать</button>
                            </div>
                        </div>
                    `;
                }
            }
        } else {
            const info = document.getElementById('currentProfileInfo');
            if (info) {
                info.innerHTML = `
                    <div class="profile-card">
                        <h4>Профиль не выбран</h4>
                        <p>Выберите профиль из списка выше</p>
                    </div>
                `;
            }
        }
    } catch (error) {
        console.error('Ошибка загрузки текущего профиля:', error);
    }
}

async function loadProfileSelect() {
    const select = document.getElementById('profileSelect');
    if (!select) return;

    try {
        const response = await fetch('/api/profiles');
        const data = await response.json();

        if (data.success) {
            profiles = data.profiles;
            select.innerHTML = '<option value="">Выбрать профиль...</option>' +
                profiles.map(p => `<option value="${p.id}">${escapeHtml(p.name)}</option>`).join('');

            // Если есть активный профиль, выбираем его
            if (currentProfile) {
                select.value = currentProfile.id;
            }
        }
    } catch (error) {
        console.error('Ошибка загрузки списка профилей:', error);
    }
}

// Добавляем обработчик для выбора профиля в долгосрочной памяти
document.addEventListener('DOMContentLoaded', () => {
    const profileSelect = document.getElementById('profileSelect');
    if (profileSelect) {
        profileSelect.addEventListener('change', async () => {
            const profileId = profileSelect.value;
            if (profileId) {
                await loadLongTermMemory(profileId);
            } else {
                document.getElementById('longTermMemoryList').innerHTML = '<p style="color: #666; padding: 1rem;">Выберите профиль</p>';
            }
        });
    }

    // Обработчик для кнопки сохранения рабочей памяти
    const saveWorkingMemoryBtn = document.getElementById('saveWorkingMemoryBtn');
    if (saveWorkingMemoryBtn) {
        saveWorkingMemoryBtn.addEventListener('click', saveWorkingMemory);
    }

    // Обработчик для кнопки сохранения долгосрочной памяти
    const saveLongTermMemoryBtn = document.getElementById('saveLongTermMemoryBtn');
    if (saveLongTermMemoryBtn) {
        saveLongTermMemoryBtn.addEventListener('click', saveLongTermMemory);
    }

    // Обработчик для кнопки обновления памяти
    const refreshMemoryBtn = document.getElementById('refreshMemoryBtn');
    if (refreshMemoryBtn) {
        refreshMemoryBtn.addEventListener('click', async () => {
            if (currentSessionId) {
                await loadWorkingMemory(currentSessionId);
                await loadMemorySuggestions(currentSessionId);
                showToast('✅ Память обновлена');
            }
        });
    }

    // Обработчик для кнопки извлечения предложений
    const extractSuggestionsBtn = document.getElementById('extractSuggestionsBtn');
    if (extractSuggestionsBtn) {
        extractSuggestionsBtn.addEventListener('click', extractSuggestions);
    }

    // Обработчик для кнопки создания профиля
    const addProfileBtn = document.getElementById('addProfileBtn');
    if (addProfileBtn) {
        addProfileBtn.addEventListener('click', () => openProfileModal());
    }

    // Обработчик для кнопки создания профиля во вкладке профилей
    const createProfileBtn = document.getElementById('createProfileBtn');
    if (createProfileBtn) {
        createProfileBtn.addEventListener('click', () => openProfileModal());
    }

    // Обработчик для закрытия модального окна профилей
    const closeProfilesModal = document.getElementById('closeProfilesModal');
    if (closeProfilesModal) {
        closeProfilesModal.addEventListener('click', () => {
            const profilesModal = document.getElementById('profilesModal');
            if (profilesModal) {
                profilesModal.classList.remove('active');
            }
        });
    }

    // Обработчик для закрытия модального окна редактирования профиля
    const closeProfileEditModalBtn = document.getElementById('closeProfileEditModal');
    if (closeProfileEditModalBtn) {
        closeProfileEditModalBtn.addEventListener('click', closeProfileEditModal);
    }

    // Обработчик для сохранения профиля
    const saveProfileBtn = document.getElementById('saveProfileBtn');
    if (saveProfileBtn) {
        saveProfileBtn.addEventListener('click', saveProfile);
    }

    // Обработчик для кликов по предложениям
    const suggestionsList = document.getElementById('suggestionsList');
    if (suggestionsList) {
        suggestionsList.addEventListener('click', handleSuggestionClick);
    }
});
