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
const taskIdInput = document.getElementById('taskId');
const tabSessions = document.getElementById('tabSessions');
const tabTasks = document.getElementById('tabTasks');
const sessionsTab = document.getElementById('sessionsTab');
const tasksTab = document.getElementById('tasksTab');
const providerSelect = null;

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
let typingCancelled = false;
let taskPollingIntervals = {};
let taskPollingInterval = null;
let displayedTaskNoteKeys = new Set();
let lastHistoryLength = 0;
const openDetails = new Set();

if (typeof marked !== 'undefined') {
    marked.setOptions({
        breaks: true,
        gfm: true
    });
}

window.AppState = {
    get isLoading() { return isLoading; },
    set isLoading(v) { isLoading = v; },
    get availableModels() { return availableModels; },
    set availableModels(v) { availableModels = v; },
    get currentSessionId() { return currentSessionId; },
    set currentSessionId(v) { currentSessionId = v; },
    get lastMessageId() { return lastMessageId; },
    set lastMessageId(v) { lastMessageId = v; },
    get userScrolled() { return userScrolled; },
    set userScrolled(v) { userScrolled = v; },
    get typingCancelled() { return typingCancelled; },
    set typingCancelled(v) { typingCancelled = v; },
    get taskPollingIntervals() { return taskPollingIntervals; },
    get displayedTaskNoteKeys() { return displayedTaskNoteKeys; },
    get openDetails() { return openDetails; },
    get lastHistoryLength() { return lastHistoryLength; },
    set lastHistoryLength(v) { lastHistoryLength = v; },
    get taskPollingInterval() { return taskPollingInterval; },
    set taskPollingInterval(v) { taskPollingInterval = v; }
};
