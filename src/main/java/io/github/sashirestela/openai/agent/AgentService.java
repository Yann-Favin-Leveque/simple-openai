package io.github.sashirestela.openai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sashirestela.openai.SimpleOpenAI;
import io.github.sashirestela.openai.SimpleOpenAIAzure;
import io.github.sashirestela.openai.common.ResponseFormat;
import io.github.sashirestela.openai.common.content.ContentPart;
import io.github.sashirestela.openai.common.content.ImageDetail;
import io.github.sashirestela.openai.domain.assistant.Assistant;
import io.github.sashirestela.openai.domain.assistant.AssistantRequest;
import io.github.sashirestela.openai.domain.assistant.ThreadMessageRole;
import io.github.sashirestela.openai.domain.assistant.ThreadRun;
import io.github.sashirestela.openai.domain.assistant.ThreadRun.RunStatus;
import io.github.sashirestela.openai.domain.assistant.ThreadRunRequest;
import io.github.sashirestela.openai.domain.assistant.ToolResourceFull;
import io.github.sashirestela.openai.domain.assistant.VectorStoreRequest;
import io.github.sashirestela.openai.domain.chat.Chat;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import io.github.sashirestela.openai.domain.chat.ChatRequest;
import io.github.sashirestela.openai.domain.image.Image;
import io.github.sashirestela.openai.domain.image.ImageRequest;
import io.github.sashirestela.openai.support.JsonSchemaGenerator;
import io.github.sashirestela.openai.support.RateLimiter;
import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for managing OpenAI/Azure AI agents (Assistants API).
 * Supports multi-instance Azure deployments, rate limiting, structured outputs, and image generation.
 */
public class AgentService {

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentServiceConfig config;
    private final SimpleOpenAI openAI;
    private final List<SimpleOpenAIAzure> azureInstances;
    private final AtomicInteger azureInstanceIndex;
    private final RateLimiter rateLimiter;
    private final Map<String, Agent> agents;

    /**
     * Configuration for AgentService.
     */
    @Data
    @Builder
    public static class AgentServiceConfig {
        // OpenAI Configuration
        private String openAiApiKey;
        private String openAiBaseUrl;

        // Azure Configuration
        @Builder.Default
        private List<AzureInstanceConfig> azureInstances = new ArrayList<>();

        // Agent Configuration
        private String agentJsonFolderPath;
        private String agentResultClassPackage;

        // Rate Limiting
        @Builder.Default
        private int requestsPerSecond = 5;

        // Optional Image Generation Sanitization
        @Builder.Default
        private boolean enableImagePromptSanitization = false;

        @Builder.Default
        private String imageSanitizerAgentId = "298";

        // Timeouts
        @Builder.Default
        private long defaultTimeoutSeconds = 120;

        @Builder.Default
        private int maxRetries = 3;

        @Builder.Default
        private long retryBaseDelayMs = 1000;

        @Data
        @Builder
        public static class AzureInstanceConfig {
            private String apiKey;
            private String baseUrl;
            private String apiVersion;
        }
    }

    /**
     * Constructs AgentService with the provided configuration.
     * Initializes OpenAI and Azure clients, loads agent definitions from JSON files.
     *
     * @param config AgentService configuration
     * @throws IOException if agent definitions cannot be loaded
     */
    public AgentService(AgentServiceConfig config) throws IOException {
        this.config = config;
        this.agents = new ConcurrentHashMap<>();

        // Initialize OpenAI client
        if (config.getOpenAiApiKey() != null && !config.getOpenAiApiKey().isEmpty()) {
            SimpleOpenAI.Builder builder = SimpleOpenAI.builder().apiKey(config.getOpenAiApiKey());
            if (config.getOpenAiBaseUrl() != null && !config.getOpenAiBaseUrl().isEmpty()) {
                builder.baseUrl(config.getOpenAiBaseUrl());
            }
            this.openAI = builder.build();
            logger.info("Initialized OpenAI client");
        } else {
            this.openAI = null;
            logger.info("OpenAI client not initialized (no API key provided)");
        }

        // Initialize Azure clients
        this.azureInstances = new ArrayList<>();
        this.azureInstanceIndex = new AtomicInteger(0);

        for (AgentServiceConfig.AzureInstanceConfig azureConfig : config.getAzureInstances()) {
            SimpleOpenAIAzure azureClient = SimpleOpenAIAzure.builder()
                    .apiKey(azureConfig.getApiKey())
                    .baseUrl(azureConfig.getBaseUrl())
                    .apiVersion(azureConfig.getApiVersion())
                    .build();
            this.azureInstances.add(azureClient);
        }

        if (!this.azureInstances.isEmpty()) {
            logger.info("Initialized {} Azure OpenAI instances", this.azureInstances.size());
        }

        // Initialize rate limiter
        this.rateLimiter = new RateLimiter(config.getRequestsPerSecond());
        logger.info("Initialized rate limiter: {} requests/second", config.getRequestsPerSecond());

        // Load agent definitions if folder path is provided
        if (config.getAgentJsonFolderPath() != null && !config.getAgentJsonFolderPath().isEmpty()) {
            loadAgentDefinitions();
        }
    }

    /**
     * Loads agent definitions from JSON files in the configured folder.
     */
    private void loadAgentDefinitions() throws IOException {
        Path agentFolder = Paths.get(config.getAgentJsonFolderPath());

        if (!Files.exists(agentFolder) || !Files.isDirectory(agentFolder)) {
            logger.warn("Agent JSON folder does not exist or is not a directory: {}", agentFolder);
            return;
        }

        try (Stream<Path> paths = Files.walk(agentFolder)) {
            List<Path> jsonFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .collect(Collectors.toList());

            logger.info("Found {} agent JSON files", jsonFiles.size());

            for (Path jsonFile : jsonFiles) {
                try {
                    String content = Files.readString(jsonFile);
                    AgentDefinition definition = objectMapper.readValue(content, AgentDefinition.class);

                    Agent agent = Agent.builder()
                            .id(definition.getId())
                            .name(definition.getName())
                            .openAiId(definition.getOpenAiId())
                            .openAiAzureIds(definition.getOpenAiAzureIds())
                            .model(definition.getModel())
                            .instructions(definition.getInstructions())
                            .resultClass(definition.getResultClass())
                            .temperature(definition.getTemperature())
                            .threadType(definition.getThreadType())
                            .statut(definition.getStatut())
                            .responseTimeout(definition.getResponseTimeout() != null ?
                                    definition.getResponseTimeout().longValue() : config.getDefaultTimeoutSeconds())
                            .retrieval(definition.getRetrieval() != null ? definition.getRetrieval() : false)
                            .build();

                    agents.put(agent.getId(), agent);
                    logger.debug("Loaded agent: {} ({})", agent.getName(), agent.getId());

                } catch (Exception e) {
                    logger.error("Failed to load agent from file: {}", jsonFile, e);
                }
            }

            logger.info("Successfully loaded {} agents", agents.size());
        }
    }

    /**
     * Gets an agent by ID.
     *
     * @param agentId Agent ID
     * @return Agent or null if not found
     */
    public Agent getAgent(String agentId) {
        return agents.get(agentId);
    }

    /**
     * Gets all loaded agents.
     *
     * @return Map of agent ID to Agent
     */
    public Map<String, Agent> getAllAgents() {
        return Collections.unmodifiableMap(agents);
    }

    /**
     * Creates or updates an OpenAI Assistant for an agent.
     *
     * @param agentId Agent ID
     * @return CompletableFuture with the created/updated Assistant
     */
    public CompletableFuture<Assistant> createAgent(String agentId) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build assistant request
                AssistantRequest.AssistantRequestBuilder requestBuilder = AssistantRequest.builder()
                        .name(agent.getName())
                        .instructions(agent.getInstructions())
                        .model(agent.getModel());

                if (agent.getTemperature() != null) {
                    requestBuilder.temperature(agent.getTemperature());
                }

                // Add response format if result class is specified
                if (agent.getResultClass() != null && !agent.getResultClass().isEmpty() &&
                        config.getAgentResultClassPackage() != null) {
                    ResponseFormat format = JsonSchemaGenerator.createResponseFormat(
                            agent.getResultClass(),
                            config.getAgentResultClassPackage());
                    requestBuilder.responseFormat(format);
                }

                AssistantRequest request = requestBuilder.build();

                // Create or update assistant
                Assistant assistant;
                if (agent.getOpenAiId() != null && !agent.getOpenAiId().isEmpty()) {
                    // Update existing
                    assistant = openAI.assistants()
                            .modify(agent.getOpenAiId(), request)
                            .join();
                    logger.info("Updated OpenAI assistant: {} ({})", agent.getName(), assistant.getId());
                } else {
                    // Create new
                    assistant = openAI.assistants().create(request).join();
                    agent.setOpenAiId(assistant.getId());
                    logger.info("Created OpenAI assistant: {} ({})", agent.getName(), assistant.getId());
                }

                return assistant;

            } catch (Exception e) {
                logger.error("Failed to create/update agent: {}", agentId, e);
                throw new RuntimeException("Failed to create/update agent: " + agentId, e);
            }
        });
    }

    /**
     * Modifies an existing agent's configuration.
     *
     * @param agentId Agent ID
     * @param updates Map of field names to new values
     * @return CompletableFuture with the updated Agent
     */
    public CompletableFuture<Agent> modifyAgent(String agentId, Map<String, Object> updates) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        return CompletableFuture.supplyAsync(() -> {
            // Apply updates (simplified - you may want to use reflection for full flexibility)
            if (updates.containsKey("instructions")) {
                agent.setInstructions((String) updates.get("instructions"));
            }
            if (updates.containsKey("temperature")) {
                agent.setTemperature(((Number) updates.get("temperature")).doubleValue());
            }
            if (updates.containsKey("model")) {
                agent.setModel((String) updates.get("model"));
            }

            logger.info("Modified agent: {}", agentId);
            return agent;
        });
    }

    /**
     * Sends a request to an agent and waits for completion.
     *
     * @param agentId          Agent ID
     * @param userMessage      User message content
     * @param threadId         Thread ID (null to create new thread)
     * @param additionalParams Additional parameters for the request
     * @return CompletableFuture with the agent's response as a string
     */
    public CompletableFuture<String> requestAgent(
            String agentId,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams) {

        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        return attemptRequest(agent, userMessage, threadId, additionalParams, 0);
    }

    /**
     * Internal method to attempt a request with retry logic.
     */
    private CompletableFuture<String> attemptRequest(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams,
            int attemptNumber) {

        // Rate limiting
        if (!rateLimiter.tryConsume()) {
            logger.debug("Rate limit reached, delaying request");
            return delayedCompletion(100, TimeUnit.MILLISECONDS)
                    .thenCompose(v -> attemptRequest(agent, userMessage, threadId, additionalParams, attemptNumber));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Determine which client to use
                boolean useAzure = agent.getOpenAiAzureIds() != null && !agent.getOpenAiAzureIds().isEmpty();

                if (useAzure) {
                    return executeAzureRequest(agent, userMessage, threadId, additionalParams);
                } else {
                    return executeOpenAIRequest(agent, userMessage, threadId, additionalParams);
                }

            } catch (Exception e) {
                return handleRequestException(agent, userMessage, threadId, additionalParams, attemptNumber, e);
            }
        });
    }

    /**
     * Executes a request using OpenAI client.
     */
    private String executeOpenAIRequest(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams) throws Exception {

        // Get or create thread
        String actualThreadId = threadId;
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            var thread = openAI.threads().create(null).join();
            actualThreadId = thread.getId();
            logger.debug("Created new thread: {}", actualThreadId);
        }

        // Add message to thread
        openAI.threads().createMessage(
                actualThreadId,
                ChatMessage.builder()
                        .role(ThreadMessageRole.USER)
                        .content(userMessage)
                        .build()
        ).join();

        // Create and execute run
        ThreadRunRequest.ThreadRunRequestBuilder runBuilder = ThreadRunRequest.builder()
                .assistantId(agent.getOpenAiId());

        if (agent.getTemperature() != null) {
            runBuilder.temperature(agent.getTemperature());
        }

        ThreadRunRequest runRequest = runBuilder.build();
        ThreadRun run = openAI.threads().createRun(actualThreadId, runRequest).join();

        // Poll for completion
        ThreadRun completedRun = pollForCompletion(openAI, actualThreadId, run.getId(), agent.getResponseTimeout());

        // Check status
        if (completedRun.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Run failed with status: " + completedRun.getStatus());
        }

        // Get response
        var messages = openAI.threads().getMessages(actualThreadId, null).join();
        if (messages.getData().isEmpty()) {
            throw new RuntimeException("No messages returned");
        }

        return extractMessageContent(messages.getData().get(0).getContent());
    }

    /**
     * Executes a request using Azure client with round-robin load balancing.
     */
    private String executeAzureRequest(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams) throws Exception {

        if (azureInstances.isEmpty()) {
            throw new IllegalStateException("No Azure instances configured");
        }

        // Round-robin instance selection
        int instanceIdx = azureInstanceIndex.getAndUpdate(i -> (i + 1) % azureInstances.size());
        SimpleOpenAIAzure azureClient = azureInstances.get(instanceIdx);
        String assistantId = agent.getOpenAiAzureIds().get(instanceIdx % agent.getOpenAiAzureIds().size());

        logger.debug("Using Azure instance {} with assistant {}", instanceIdx, assistantId);

        // Get or create thread
        String actualThreadId = threadId;
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            var thread = azureClient.threads().create(null).join();
            actualThreadId = thread.getId();
            logger.debug("Created new Azure thread: {}", actualThreadId);
        }

        // Add message to thread
        azureClient.threads().createMessage(
                actualThreadId,
                ChatMessage.builder()
                        .role(ThreadMessageRole.USER)
                        .content(userMessage)
                        .build()
        ).join();

        // Create and execute run
        ThreadRunRequest.ThreadRunRequestBuilder runBuilder = ThreadRunRequest.builder()
                .assistantId(assistantId);

        if (agent.getTemperature() != null) {
            runBuilder.temperature(agent.getTemperature());
        }

        ThreadRunRequest runRequest = runBuilder.build();
        ThreadRun run = azureClient.threads().createRun(actualThreadId, runRequest).join();

        // Poll for completion
        ThreadRun completedRun = pollForCompletionAzure(azureClient, actualThreadId, run.getId(),
                agent.getResponseTimeout());

        // Check status
        if (completedRun.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Azure run failed with status: " + completedRun.getStatus());
        }

        // Get response
        var messages = azureClient.threads().getMessages(actualThreadId, null).join();
        if (messages.getData().isEmpty()) {
            throw new RuntimeException("No messages returned from Azure");
        }

        return extractMessageContent(messages.getData().get(0).getContent());
    }

    /**
     * Polls for run completion (OpenAI).
     */
    private ThreadRun pollForCompletion(
            SimpleOpenAI client,
            String threadId,
            String runId,
            long timeoutSeconds) throws Exception {

        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        while (true) {
            ThreadRun run = client.threads().getRun(threadId, runId).join();
            RunStatus status = run.getStatus();

            if (status == RunStatus.COMPLETED ||
                status == RunStatus.FAILED ||
                status == RunStatus.CANCELLED ||
                status == RunStatus.EXPIRED) {
                return run;
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new RuntimeException("Request timeout after " + timeoutSeconds + " seconds");
            }

            Thread.sleep(1000); // Poll every second
        }
    }

    /**
     * Polls for run completion (Azure).
     */
    private ThreadRun pollForCompletionAzure(
            SimpleOpenAIAzure client,
            String threadId,
            String runId,
            long timeoutSeconds) throws Exception {

        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        while (true) {
            ThreadRun run = client.threads().getRun(threadId, runId).join();
            RunStatus status = run.getStatus();

            if (status == RunStatus.COMPLETED ||
                status == RunStatus.FAILED ||
                status == RunStatus.CANCELLED ||
                status == RunStatus.EXPIRED) {
                return run;
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new RuntimeException("Request timeout after " + timeoutSeconds + " seconds");
            }

            Thread.sleep(1000); // Poll every second
        }
    }

    /**
     * Extracts text content from message content parts.
     */
    private String extractMessageContent(List<ContentPart> contentParts) {
        if (contentParts == null || contentParts.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ContentPart part : contentParts) {
            if (part.getText() != null && part.getText().getValue() != null) {
                sb.append(part.getText().getValue());
            }
        }
        return sb.toString();
    }

    /**
     * Handles request exceptions with retry logic.
     */
    private String handleRequestException(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams,
            int attemptNumber,
            Exception e) {

        if (attemptNumber >= config.getMaxRetries()) {
            logger.error("Max retries reached for agent: {}", agent.getId(), e);
            throw new RuntimeException("Request failed after " + config.getMaxRetries() + " retries", e);
        }

        long delay = calculateDelay(attemptNumber);
        logger.warn("Request failed (attempt {}), retrying in {}ms: {}",
                attemptNumber + 1, delay, e.getMessage());

        try {
            Thread.sleep(delay);
            return attemptRequest(agent, userMessage, threadId, additionalParams, attemptNumber + 1).join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry interrupted", ie);
        }
    }

    /**
     * Calculates exponential backoff delay.
     */
    private long calculateDelay(int attemptNumber) {
        return config.getRetryBaseDelayMs() * (long) Math.pow(2, attemptNumber);
    }

    /**
     * Creates a delayed CompletableFuture.
     */
    private CompletableFuture<Void> delayedCompletion(long delay, TimeUnit unit) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(unit.toMillis(delay));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Creates a vector store with file attachments.
     *
     * @param name    Vector store name
     * @param fileIds List of file IDs to attach
     * @return CompletableFuture with vector store ID
     */
    public CompletableFuture<String> createVectorStore(String name, List<String> fileIds) {
        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenAI client not initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                VectorStoreRequest request = VectorStoreRequest.builder()
                        .name(name)
                        .fileIds(fileIds)
                        .build();

                var vectorStore = openAI.vectorStores().create(request).join();
                logger.info("Created vector store: {} ({})", name, vectorStore.getId());
                return vectorStore.getId();

            } catch (Exception e) {
                logger.error("Failed to create vector store: {}", name, e);
                throw new RuntimeException("Failed to create vector store", e);
            }
        });
    }

    /**
     * Deletes a vector store.
     *
     * @param vectorStoreId Vector store ID
     * @return CompletableFuture with deletion result
     */
    public CompletableFuture<Boolean> deleteVectorStore(String vectorStoreId) {
        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenAI client not initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                openAI.vectorStores().delete(vectorStoreId).join();
                logger.info("Deleted vector store: {}", vectorStoreId);
                return true;
            } catch (Exception e) {
                logger.error("Failed to delete vector store: {}", vectorStoreId, e);
                return false;
            }
        });
    }

    /**
     * Sends a request to an agent with vector store support.
     *
     * @param agentId       Agent ID
     * @param userMessage   User message
     * @param vectorStoreId Vector store ID
     * @return CompletableFuture with response
     */
    public CompletableFuture<String> requestAgentWithVectorStorage(
            String agentId,
            String userMessage,
            String vectorStoreId) {

        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenAI client not initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create thread
                var thread = openAI.threads().create(null).join();
                String threadId = thread.getId();

                // Add message
                openAI.threads().createMessage(
                        threadId,
                        ChatMessage.builder()
                                .role(ThreadMessageRole.USER)
                                .content(userMessage)
                                .build()
                ).join();

                // Create run with vector store
                ThreadRunRequest runRequest = ThreadRunRequest.builder()
                        .assistantId(agent.getOpenAiId())
                        .temperature(agent.getTemperature())
                        .toolResources(ToolResourceFull.builder()
                                .fileSearch(ToolResourceFull.FileSearch.builder()
                                        .vectorStoreIds(List.of(vectorStoreId))
                                        .build())
                                .build())
                        .build();

                ThreadRun run = openAI.threads().createRun(threadId, runRequest).join();

                // Poll for completion
                ThreadRun completedRun = pollForCompletion(openAI, threadId, run.getId(),
                        agent.getResponseTimeout());

                if (completedRun.getStatus() != RunStatus.COMPLETED) {
                    throw new RuntimeException("Run failed with status: " + completedRun.getStatus());
                }

                // Get response
                var messages = openAI.threads().getMessages(threadId, null).join();
                if (messages.getData().isEmpty()) {
                    throw new RuntimeException("No messages returned");
                }

                return extractMessageContent(messages.getData().get(0).getContent());

            } catch (Exception e) {
                logger.error("Request with vector storage failed for agent: {}", agentId, e);
                throw new RuntimeException("Request with vector storage failed", e);
            }
        });
    }

    /**
     * Sends a chat completion request (non-Assistant API).
     *
     * @param model       Model name
     * @param messages    List of chat messages
     * @param temperature Temperature
     * @param useAzure    Whether to use Azure
     * @return CompletableFuture with response content
     */
    public CompletableFuture<String> requestChatCompletion(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            boolean useAzure) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                ChatRequest.ChatRequestBuilder requestBuilder = ChatRequest.builder()
                        .model(model)
                        .messages(messages);

                if (temperature != null) {
                    requestBuilder.temperature(temperature);
                }

                ChatRequest request = requestBuilder.build();

                Chat chatResponse;
                if (useAzure && !azureInstances.isEmpty()) {
                    int instanceIdx = azureInstanceIndex.getAndUpdate(i -> (i + 1) % azureInstances.size());
                    SimpleOpenAIAzure azureClient = azureInstances.get(instanceIdx);
                    chatResponse = azureClient.chatCompletions().create(request).join();
                } else {
                    if (openAI == null) {
                        throw new IllegalStateException("OpenAI client not initialized");
                    }
                    chatResponse = openAI.chatCompletions().create(request).join();
                }

                if (chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
                    throw new RuntimeException("No choices returned in chat completion");
                }

                return chatResponse.getChoices().get(0).getMessage().getContent();

            } catch (Exception e) {
                logger.error("Chat completion request failed", e);
                throw new RuntimeException("Chat completion request failed", e);
            }
        });
    }

    /**
     * Sends a structured chat completion request with JSON Schema.
     *
     * @param model         Model name
     * @param messages      List of chat messages
     * @param temperature   Temperature
     * @param resultClass   Result class name for schema generation
     * @param useAzure      Whether to use Azure
     * @return CompletableFuture with response content
     */
    public CompletableFuture<String> requestStructuredChatCompletion(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            String resultClass,
            boolean useAzure) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                ChatRequest.ChatRequestBuilder requestBuilder = ChatRequest.builder()
                        .model(model)
                        .messages(messages);

                if (temperature != null) {
                    requestBuilder.temperature(temperature);
                }

                // Add response format if result class is specified
                if (resultClass != null && !resultClass.isEmpty() &&
                        config.getAgentResultClassPackage() != null) {
                    ResponseFormat format = JsonSchemaGenerator.createResponseFormat(
                            resultClass,
                            config.getAgentResultClassPackage());
                    requestBuilder.responseFormat(format);
                }

                ChatRequest request = requestBuilder.build();

                Chat chatResponse;
                if (useAzure && !azureInstances.isEmpty()) {
                    int instanceIdx = azureInstanceIndex.getAndUpdate(i -> (i + 1) % azureInstances.size());
                    SimpleOpenAIAzure azureClient = azureInstances.get(instanceIdx);
                    chatResponse = azureClient.chatCompletions().create(request).join();
                } else {
                    if (openAI == null) {
                        throw new IllegalStateException("OpenAI client not initialized");
                    }
                    chatResponse = openAI.chatCompletions().create(request).join();
                }

                if (chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
                    throw new RuntimeException("No choices returned in structured chat completion");
                }

                return chatResponse.getChoices().get(0).getMessage().getContent();

            } catch (Exception e) {
                logger.error("Structured chat completion request failed", e);
                throw new RuntimeException("Structured chat completion request failed", e);
            }
        });
    }

    /**
     * Generates an image using DALL-E with optional prompt sanitization fallback.
     *
     * @param prompt                  Image prompt
     * @param model                   DALL-E model (e.g., "dall-e-3")
     * @param size                    Image size (e.g., "1024x1024")
     * @param quality                 Image quality (e.g., "standard" or "hd")
     * @param withSanitizationFallback Whether to use sanitization agent if content policy violation occurs
     * @return CompletableFuture with image URL
     */
    public CompletableFuture<String> generateImage(
            String prompt,
            String model,
            String size,
            String quality,
            boolean withSanitizationFallback) {

        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenAI client not initialized"));
        }

        return attemptImageGeneration(prompt, model, size, quality, withSanitizationFallback, 0);
    }

    /**
     * Attempts image generation with retry logic.
     */
    private CompletableFuture<String> attemptImageGeneration(
            String prompt,
            String model,
            String size,
            String quality,
            boolean withSanitizationFallback,
            int attemptNumber) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                ImageRequest request = ImageRequest.builder()
                        .prompt(prompt)
                        .model(model)
                        .size(size)
                        .quality(quality)
                        .n(1)
                        .build();

                var response = openAI.images().create(request).join();

                if (response.getData() == null || response.getData().isEmpty()) {
                    throw new RuntimeException("No images returned");
                }

                String imageUrl = response.getData().get(0).getUrl();
                logger.info("Generated image successfully");
                return imageUrl;

            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                // Check for content policy violation
                if (withSanitizationFallback &&
                    config.isEnableImagePromptSanitization() &&
                    (errorMessage.contains("content_policy_violation") ||
                     errorMessage.contains("safety system"))) {

                    logger.warn("Content policy violation detected, attempting sanitization");
                    return sanitizeImagePromptAndRetry(prompt, model, size, quality, attemptNumber).join();
                }

                // Retry logic for other errors
                if (attemptNumber < config.getMaxRetries()) {
                    long delay = calculateDelay(attemptNumber);
                    logger.warn("Image generation failed (attempt {}), retrying in {}ms",
                            attemptNumber + 1, delay);
                    try {
                        Thread.sleep(delay);
                        return attemptImageGeneration(prompt, model, size, quality,
                                withSanitizationFallback, attemptNumber + 1).join();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                }

                logger.error("Image generation failed after {} attempts", attemptNumber + 1, e);
                throw new RuntimeException("Image generation failed", e);
            }
        });
    }

    /**
     * Sanitizes image prompt using the configured sanitizer agent and retries.
     */
    private CompletableFuture<String> sanitizeImagePromptAndRetry(
            String originalPrompt,
            String model,
            String size,
            String quality,
            int attemptNumber) {

        if (attemptNumber >= config.getMaxRetries()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("Image generation failed after sanitization attempts"));
        }

        Agent sanitizerAgent = agents.get(config.getImageSanitizerAgentId());
        if (sanitizerAgent == null) {
            logger.warn("Image sanitizer agent not found: {}", config.getImageSanitizerAgentId());
            return CompletableFuture.failedFuture(
                    new RuntimeException("Image sanitizer agent not configured"));
        }

        return requestAgent(config.getImageSanitizerAgentId(), originalPrompt, null, new HashMap<>())
                .thenCompose(sanitizedPrompt -> {
                    logger.info("Sanitized prompt, retrying image generation");
                    return attemptImageGeneration(sanitizedPrompt, model, size, quality,
                            false, attemptNumber + 1); // Don't sanitize again
                });
    }

    /**
     * Maps a JSON response to a typed agent result.
     *
     * @param <T>         Result type
     * @param jsonResponse JSON response string
     * @param resultClass  Result class name
     * @return Typed result instance
     */
    public <T extends AgentResult> T mapResponse(String jsonResponse, String resultClass) {
        try {
            if (config.getAgentResultClassPackage() == null) {
                throw new IllegalStateException("Agent result class package not configured");
            }

            String fullClassName = config.getAgentResultClassPackage() + "." + resultClass;
            @SuppressWarnings("unchecked")
            Class<T> clazz = (Class<T>) Class.forName(fullClassName);

            return AgentResult.jsonMapper(jsonResponse, clazz);

        } catch (ClassNotFoundException e) {
            logger.error("Result class not found: {}", resultClass, e);
            throw new RuntimeException("Result class not found: " + resultClass, e);
        }
    }

    /**
     * Validates that a response is complete and not truncated.
     *
     * @param response Response string
     * @return true if response appears complete
     */
    public boolean responseOk(String response) {
        if (response == null || response.trim().isEmpty()) {
            return false;
        }

        String trimmed = response.trim();

        // Check for JSON completeness
        if (trimmed.startsWith("{")) {
            int openBraces = 0;
            for (char c : trimmed.toCharArray()) {
                if (c == '{') openBraces++;
                if (c == '}') openBraces--;
            }
            return openBraces == 0;
        }

        if (trimmed.startsWith("[")) {
            int openBrackets = 0;
            for (char c : trimmed.toCharArray()) {
                if (c == '[') openBrackets++;
                if (c == ']') openBrackets--;
            }
            return openBrackets == 0;
        }

        // For non-JSON responses, consider them OK if not empty
        return true;
    }

    /**
     * Creates a batch request for processing multiple agent requests asynchronously.
     *
     * @param inputFileId File ID containing batch requests (JSONL format)
     * @param endpoint Endpoint type for the batch
     * @param metadata Optional metadata for the batch
     * @return CompletableFuture with Batch object
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> createBatch(
            String inputFileId,
            io.github.sashirestela.openai.domain.batch.EndpointType endpoint,
            Map<String, String> metadata) {

        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenAI client not initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                io.github.sashirestela.openai.domain.batch.BatchRequest.BatchRequestBuilder builder =
                        io.github.sashirestela.openai.domain.batch.BatchRequest.builder()
                                .inputFileId(inputFileId)
                                .endpoint(endpoint)
                                .completionWindow(io.github.sashirestela.openai.domain.batch.BatchRequest.CompletionWindowType.T24H);

                if (metadata != null && !metadata.isEmpty()) {
                    builder.metadata(metadata);
                }

                io.github.sashirestela.openai.domain.batch.BatchRequest request = builder.build();
                io.github.sashirestela.openai.domain.batch.Batch batch = openAI.batches().create(request).join();

                logger.info("Created batch: {} with status: {}", batch.getId(), batch.getStatus());
                return batch;

            } catch (Exception e) {
                logger.error("Failed to create batch", e);
                throw new RuntimeException("Failed to create batch", e);
            }
        });
    }

    /**
     * Retrieves the status and details of a batch.
     *
     * @param batchId Batch ID
     * @return CompletableFuture with Batch object
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> getBatch(String batchId) {
        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenAI client not initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return openAI.batches().getOne(batchId).join();
            } catch (Exception e) {
                logger.error("Failed to get batch: {}", batchId, e);
                throw new RuntimeException("Failed to get batch: " + batchId, e);
            }
        });
    }

    /**
     * Cancels an in-progress batch.
     *
     * @param batchId Batch ID
     * @return CompletableFuture with Batch object showing cancelled status
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> cancelBatch(String batchId) {
        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenAI client not initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                io.github.sashirestela.openai.domain.batch.Batch batch = openAI.batches().cancel(batchId).join();
                logger.info("Cancelled batch: {}", batchId);
                return batch;
            } catch (Exception e) {
                logger.error("Failed to cancel batch: {}", batchId, e);
                throw new RuntimeException("Failed to cancel batch: " + batchId, e);
            }
        });
    }

    /**
     * Lists batches with optional filtering.
     *
     * @param limit Maximum number of batches to return (default 20)
     * @param after Cursor for pagination
     * @return CompletableFuture with list of batches
     */
    public CompletableFuture<io.github.sashirestela.openai.common.Page<io.github.sashirestela.openai.domain.batch.Batch>> listBatches(
            Integer limit,
            String after) {

        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenAI client not initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return openAI.batches().getList(limit, after).join();
            } catch (Exception e) {
                logger.error("Failed to list batches", e);
                throw new RuntimeException("Failed to list batches", e);
            }
        });
    }

    /**
     * Polls a batch until it reaches a terminal state (completed, failed, expired, cancelled).
     *
     * @param batchId Batch ID
     * @param pollIntervalSeconds Interval between status checks in seconds
     * @param timeoutSeconds Maximum time to wait in seconds
     * @return CompletableFuture with final Batch object
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> pollBatchUntilComplete(
            String batchId,
            long pollIntervalSeconds,
            long timeoutSeconds) {

        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("OpenAI client not initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                long timeoutMs = timeoutSeconds * 1000;

                while (true) {
                    io.github.sashirestela.openai.domain.batch.Batch batch = openAI.batches().getOne(batchId).join();
                    String status = batch.getStatus();

                    logger.debug("Batch {} status: {}", batchId, status);

                    // Terminal states
                    if ("completed".equals(status) ||
                        "failed".equals(status) ||
                        "expired".equals(status) ||
                        "cancelled".equals(status)) {
                        logger.info("Batch {} reached terminal state: {}", batchId, status);
                        return batch;
                    }

                    // Check timeout
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        throw new RuntimeException("Batch polling timeout after " + timeoutSeconds + " seconds");
                    }

                    // Wait before next poll
                    Thread.sleep(pollIntervalSeconds * 1000);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Batch polling interrupted", e);
            } catch (Exception e) {
                logger.error("Failed to poll batch: {}", batchId, e);
                throw new RuntimeException("Failed to poll batch: " + batchId, e);
            }
        });
    }

    /**
     * Convenience method to create a batch for chat completion requests.
     * The input file should be in JSONL format with each line containing a batch request.
     *
     * @param inputFileId File ID containing chat completion requests
     * @param metadata Optional metadata
     * @return CompletableFuture with Batch object
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> createChatCompletionBatch(
            String inputFileId,
            Map<String, String> metadata) {

        return createBatch(
                inputFileId,
                io.github.sashirestela.openai.domain.batch.EndpointType.CHAT_COMPLETIONS,
                metadata);
    }

    /**
     * Shuts down the service and releases resources.
     */
    public void shutdown() {
        logger.info("Shutting down AgentService");
        // Add any cleanup logic here if needed
    }
}
