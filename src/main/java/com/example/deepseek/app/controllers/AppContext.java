package com.example.deepseek.app.controllers;

import com.example.deepseek.agent.FactsExtractionAgent;
import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.client.ClientManager;
import com.example.deepseek.context.ContextStrategyFactory;
import com.example.deepseek.db.BranchRepository;
import com.example.deepseek.db.FactsRepository;
import com.example.deepseek.db.GlobalSummaryRepository;
import com.example.deepseek.db.SessionHeartbeatRepository;
import com.example.deepseek.db.SessionService;
import com.example.deepseek.memory.MemoryService;
import com.example.deepseek.memory.agent.MemoryExtractionAgent;
import com.example.deepseek.memory.repository.ProfileRepository;
import com.example.deepseek.task.HeartbeatMonitor;
import com.example.deepseek.task.TaskManagerAgent;
import com.example.deepseek.task.TaskService;
import com.example.deepseek.invariant.InvariantService;
import com.example.deepseek.mcp.McpService;
import com.example.deepseek.mcp.McpSseProxyService;
import com.example.deepseek.rag.RagService;
import com.example.deepseek.rag.RerankerService;

public final class AppContext {
    private static AppContext instance;
    
    private ClientManager clientManager;
    private SessionService sessionService;
    private SummaryAgent summaryAgent;
    private ContextStrategyFactory strategyFactory;
    private GlobalSummaryRepository globalSummaryRepository;
    private ProfileRepository profileRepository;
    private MemoryService memoryService;
    private MemoryExtractionAgent memoryExtractionAgent;
    private TaskService taskService;
    private TaskManagerAgent taskManagerAgent;
    private FactsRepository factsRepository;
    private BranchRepository branchRepository;
    private FactsExtractionAgent factsExtractionAgent;
    private SessionHeartbeatRepository heartbeatRepository;
    private HeartbeatMonitor heartbeatMonitor;
    private InvariantService invariantService;
    private McpService mcpService;
    private McpSseProxyService mcpSseProxyService;
    private RagService ragService;
    private RerankerService rerankerService;
    private boolean tsmEnabled = true;
    private boolean ragEnabled = false;
    private String ragSearchStrategy = "BOTH";
    private boolean rerankerEnabled = true;
    private double rerankerThreshold = 0.3;
    private int rerankerTopKBefore = 100;
    private int rerankerTopKAfter = 25;
    
    private AppContext() {}
    
    public static AppContext getInstance() {
        if (instance == null) {
            instance = new AppContext();
        }
        return instance;
    }
    
    public ClientManager getClientManager() { return clientManager; }
    public void setClientManager(ClientManager clientManager) { this.clientManager = clientManager; }
    
    public SessionService getSessionService() { return sessionService; }
    public void setSessionService(SessionService sessionService) { this.sessionService = sessionService; }
    
    public SummaryAgent getSummaryAgent() { return summaryAgent; }
    public void setSummaryAgent(SummaryAgent summaryAgent) { this.summaryAgent = summaryAgent; }
    
    public ContextStrategyFactory getStrategyFactory() { return strategyFactory; }
    public void setStrategyFactory(ContextStrategyFactory strategyFactory) { this.strategyFactory = strategyFactory; }
    
    public GlobalSummaryRepository getGlobalSummaryRepository() { return globalSummaryRepository; }
    public void setGlobalSummaryRepository(GlobalSummaryRepository globalSummaryRepository) { this.globalSummaryRepository = globalSummaryRepository; }
    
    public ProfileRepository getProfileRepository() { return profileRepository; }
    public void setProfileRepository(ProfileRepository profileRepository) { this.profileRepository = profileRepository; }
    
    public MemoryService getMemoryService() { return memoryService; }
    public void setMemoryService(MemoryService memoryService) { this.memoryService = memoryService; }
    
    public MemoryExtractionAgent getMemoryExtractionAgent() { return memoryExtractionAgent; }
    public void setMemoryExtractionAgent(MemoryExtractionAgent memoryExtractionAgent) { this.memoryExtractionAgent = memoryExtractionAgent; }
    
    public TaskService getTaskService() { return taskService; }
    public void setTaskService(TaskService taskService) { this.taskService = taskService; }
    
    public TaskManagerAgent getTaskManagerAgent() { return taskManagerAgent; }
    public void setTaskManagerAgent(TaskManagerAgent taskManagerAgent) { this.taskManagerAgent = taskManagerAgent; }
    
    public FactsRepository getFactsRepository() { return factsRepository; }
    public void setFactsRepository(FactsRepository factsRepository) { this.factsRepository = factsRepository; }
    
    public BranchRepository getBranchRepository() { return branchRepository; }
    public void setBranchRepository(BranchRepository branchRepository) { this.branchRepository = branchRepository; }
    
    public FactsExtractionAgent getFactsExtractionAgent() { return factsExtractionAgent; }
    public void setFactsExtractionAgent(FactsExtractionAgent factsExtractionAgent) { this.factsExtractionAgent = factsExtractionAgent; }
    
    public boolean isTsmEnabled() { return tsmEnabled; }
    public void setTsmEnabled(boolean tsmEnabled) { this.tsmEnabled = tsmEnabled; }
    
    public boolean isRagEnabled() { return ragEnabled; }
    public void setRagEnabled(boolean ragEnabled) { this.ragEnabled = ragEnabled; }
    
    public RagService getRagService() { return ragService; }
    public void setRagService(RagService ragService) { this.ragService = ragService; }
    
    public String getRagSearchStrategy() { return ragSearchStrategy; }
    public void setRagSearchStrategy(String ragSearchStrategy) { this.ragSearchStrategy = ragSearchStrategy; }
    
    public RerankerService getRerankerService() { return rerankerService; }
    public void setRerankerService(RerankerService rerankerService) { this.rerankerService = rerankerService; }
    
    public boolean isRerankerEnabled() { return rerankerEnabled; }
    public void setRerankerEnabled(boolean rerankerEnabled) { this.rerankerEnabled = rerankerEnabled; }
    
    public double getRerankerThreshold() { return rerankerThreshold; }
    public void setRerankerThreshold(double rerankerThreshold) { this.rerankerThreshold = rerankerThreshold; }
    
    public int getRerankerTopKBefore() { return rerankerTopKBefore; }
    public void setRerankerTopKBefore(int rerankerTopKBefore) { this.rerankerTopKBefore = rerankerTopKBefore; }
    
    public int getRerankerTopKAfter() { return rerankerTopKAfter; }
    public void setRerankerTopKAfter(int rerankerTopKAfter) { this.rerankerTopKAfter = rerankerTopKAfter; }
    
    public long getProfileIdForSession(long sessionId) {
        if (sessionId <= 0) {
            return 1L;
        }
        try {
            return sessionService.getSessionRepository().getProfileId(sessionId);
        } catch (Exception e) {
            return 1L;
        }
    }
    
    public SessionHeartbeatRepository getHeartbeatRepository() { return heartbeatRepository; }
    public void setHeartbeatRepository(SessionHeartbeatRepository heartbeatRepository) { this.heartbeatRepository = heartbeatRepository; }
    
    public HeartbeatMonitor getHeartbeatMonitor() { return heartbeatMonitor; }
    public void setHeartbeatMonitor(HeartbeatMonitor heartbeatMonitor) { this.heartbeatMonitor = heartbeatMonitor; }
    
    public InvariantService getInvariantService() { return invariantService; }
    public void setInvariantService(InvariantService invariantService) { this.invariantService = invariantService; }
    
    public McpService getMcpService() { return mcpService; }
    public void setMcpService(McpService mcpService) { this.mcpService = mcpService; }
    
    public McpSseProxyService getMcpSseProxyService() { return mcpSseProxyService; }
    public void setMcpSseProxyService(McpSseProxyService mcpSseProxyService) { this.mcpSseProxyService = mcpSseProxyService; }
}
