// Import memory.js
import './memory.js';

// Add DOM elements for profiles
const profilesModal = document.getElementById('profilesModal');
const profileEditModal = document.getElementById('profileEditModal');
const closeProfilesModal = document.getElementById('closeProfilesModal');
const addProfileBtn = document.getElementById('addProfileBtn');
const profilesMainList = document.getElementById('profilesMainList');
const profileSelect = document.getElementById('profileSelect');

// Add DOM elements for memory
const memoryTabBadge = document.getElementById('memoryTabBadge');

// Tab navigation
const tabProfiles = document.querySelector('[data-tab="profiles"]');
const tabMemory = document.querySelector('[data-tab="memory"]');

// Update navigation handlers
tabProfiles.addEventListener('click', () => {
    onMemoryTabShown();
});

tabMemory.addEventListener('click', () => {
    onMemoryTabShown();
});

// Initialize memory features on load
document.addEventListener('DOMContentLoaded', () => {
    initializeMemoryFeatures();
});
