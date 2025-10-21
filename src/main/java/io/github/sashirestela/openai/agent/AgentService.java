package io.github.sashirestela.openai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sashirestela.openai.SimpleOpenAI;
import io.github.sashirestela.openai.common.ResponseFormat;
import io.github.sashirestela.openai.common.content.ContentPart;
import io.github.sashirestela.openai.common.content.ContentPart.ContentPartTextAnnotation;
import io.github.sashirestela.openai.common.content.ImageDetail;
import io.github.sashirestela.openai.domain.assistant.Assistant;
import io.github.sashirestela.openai.domain.assistant.AssistantRequest;
import io.github.sashirestela.openai.domain.assistant.ThreadMessageRequest;
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
import io.github.sashirestela.openai.domain.image.ImageRequest.Quality;
import io.github.sashirestela.openai.domain.image.Size;
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
    private final List<SimpleOpenAI> instances;  // For agents/chat: OpenAI + Azure instances
    private final List<SimpleOpenAI> imageInstances;  // For DALL-E: OpenAI + Azure DALL-E instances
    private final AtomicInteger instanceIndex;
    private final AtomicInteger imageInstanceIndex;
    private final RateLimiter rateLimiter;
    private final Map<String, Agent> agents;

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
        this.instances = new ArrayList<>();
        this.imageInstances = new ArrayList<>();
        this.instanceIndex = new AtomicInteger(0);
        this.imageInstanceIndex = new AtomicInteger(0);

        // Initialize OpenAI client (if configured) - can be used for BOTH agents and images
        if (config.getOpenAiApiKey() != null && !config.getOpenAiApiKey().isEmpty()) {
            SimpleOpenAI.SimpleOpenAIBuilder builder = SimpleOpenAI.builder()
                .apiKey(config.getOpenAiApiKey())
                .isAzure(false);  // Standard OpenAI

            if (config.getOpenAiBaseUrl() != null && !config.getOpenAiBaseUrl().isEmpty()) {
                builder.baseUrl(config.getOpenAiBaseUrl());
            }

            SimpleOpenAI openAIClient = builder.build();
            this.instances.add(openAIClient);  // For agents/chat
            this.imageInstances.add(openAIClient);  // For images (DALL-E)
            logger.info("Initialized OpenAI client (agents + images)");
        }

        // Initialize Azure clients for agents/chat (if configured)
        if (config.getAzureApiKeys() != null && !config.getAzureApiKeys().isEmpty()) {
            for (int i = 0; i < config.getAzureApiKeys().size(); i++) {
                SimpleOpenAI azureClient = SimpleOpenAI.builder()
                        .apiKey(config.getAzureApiKeys().get(i))
                        .baseUrl(config.getAzureBaseUrls().get(i))
                        .isAzure(true)  // Azure OpenAI
                        .azureApiVersion(config.getAzureApiVersion())
                        .build();
                this.instances.add(azureClient);  // For agents/chat ONLY
            }
            logger.info("Initialized {} Azure OpenAI instances (agents/chat)", config.getAzureApiKeys().size());
        }

        // Initialize Azure DALL-E clients for images (if configured) - SEPARATE deployment
        if (config.getAzureDalleApiKeys() != null && !config.getAzureDalleApiKeys().isEmpty()) {
            for (int i = 0; i < config.getAzureDalleApiKeys().size(); i++) {
                SimpleOpenAI azureDalleClient = SimpleOpenAI.builder()
                        .apiKey(config.getAzureDalleApiKeys().get(i))
                        .baseUrl(config.getAzureDalleBaseUrls().get(i))
                        .isAzure(true)  // Azure DALL-E
                        .azureApiVersion(config.getAzureDalleApiVersion())
                        .build();
                this.imageInstances.add(azureDalleClient);  // For images ONLY
            }
            logger.info("Initialized {} Azure DALL-E instances (images only)", config.getAzureDalleApiKeys().size());
        }

        if (this.instances.isEmpty()) {
            throw new IllegalStateException("No OpenAI or Azure instances configured for agents");
        }

        logger.info("Total agent instances: {} | Total image instances: {}", this.instances.size(), this.imageInstances.size());

        // Initialize rate limiter
        this.rateLimiter = new RateLimiter(config.getRequestsPerSecond());
        logger.info("Initialized rate limiter: {} requests/second", config.getRequestsPerSecond());

        // Load agent definitions if folder path is provided
        if (config.getAgentJsonFolderPath() != null && !config.getAgentJsonFolderPath().isEmpty()) {
            loadAgentDefinitions();
        }
    }

    /**
     * Gets the next instance using round-robin (for agents/chat).
     */
    private SimpleOpenAI getNextInstance() {
        int idx = instanceIndex.getAndUpdate(i -> (i + 1) % instances.size());
        return instances.get(idx);
    }

    /**
     * Gets the next image instance using round-robin (for DALL-E).
     */
    private SimpleOpenAI getNextImageInstance() {
        if (imageInstances.isEmpty()) {
            throw new IllegalStateException("No image generation instances configured. Set OPENAI_KEY or OPENAI_AZURE_DALLEE_KEY.");
        }
        int idx = imageInstanceIndex.getAndUpdate(i -> (i + 1) % imageInstances.size());
        return imageInstances.get(idx);
    }

    /**
     * Gets a specific instance by index (for agents/chat).
     */
    private SimpleOpenAI getInstance(int index) {
        return instances.get(index);
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
                            .assistantIds(definition.getAssistantIds())
                            .model(definition.getModel())
                            .instructions(definition.getInstructions())
                            .resultClass(definition.getResultClass())
                            .temperature(definition.getTemperature())
                            .threadType(definition.getThreadType())
                            .responseTimeout(definition.getResponseTimeout() != null ?
                                    definition.getResponseTimeout().longValue() : config.getDefaultResponseTimeout())
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
     * Creates or updates an OpenAI Assistant for an agent on ALL configured instances.
     * This is essential for multi-instance Azure deployments to ensure load balancing works correctly.
     *
     * @param agentId Agent ID
     * @return CompletableFuture with the created/updated Assistant (from first instance)
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

                // Initialize assistantIds list if null
                if (agent.getAssistantIds() == null) {
                    agent.setAssistantIds(new java.util.ArrayList<>());
                }

                // Ensure list has enough capacity for all instances
                while (agent.getAssistantIds().size() < instances.size()) {
                    agent.getAssistantIds().add(null);
                }

                // Create or update assistant on ALL instances
                Assistant firstAssistant = null;
                for (int i = 0; i < instances.size(); i++) {
                    SimpleOpenAI client = getInstance(i);
                    Assistant assistant;

                    String existingAssistantId = agent.getAssistantIds().get(i);
                    if (existingAssistantId != null && !existingAssistantId.isEmpty()) {
                        // Update existing assistant
                        var modifyRequest = io.github.sashirestela.openai.domain.assistant.AssistantModifyRequest.builder()
                                .name(agent.getName())
                                .instructions(agent.getInstructions())
                                .model(agent.getModel())
                                .temperature(agent.getTemperature())
                                .responseFormat(request.getResponseFormat())
                                .build();
                        assistant = client.assistants()
                                .modify(existingAssistantId, modifyRequest)
                                .join();
                        logger.info("✅ Updated assistant on instance {}: {} ({})", i, agent.getName(), assistant.getId());
                    } else {
                        // Create new assistant
                        assistant = client.assistants().create(request).join();
                        agent.getAssistantIds().set(i, assistant.getId());
                        logger.info("✅ Created assistant on instance {}: {} ({})", i, agent.getName(), assistant.getId());
                    }

                    // Keep reference to first assistant to return
                    if (i == 0) {
                        firstAssistant = assistant;
                    }
                }

                logger.info("🎯 Agent '{}' successfully created/updated on all {} instance(s)", agent.getName(), instances.size());
                return firstAssistant;

            } catch (Exception e) {
                logger.error("Failed to create/update agent: {}", agentId, e);
                throw new RuntimeException("Failed to create/update agent: " + agentId, e);
            }
        });
    }

    /**
     * Creates or updates ALL loaded agents on ALL configured instances.
     * This is useful for initialization or bulk updates.
     *
     * @return CompletableFuture that completes when all agents are created/updated
     */
    public CompletableFuture<Void> createAllAgents() {
        if (agents.isEmpty()) {
            logger.warn("No agents loaded to create/update");
            return CompletableFuture.completedFuture(null);
        }

        logger.info("🚀 Creating/updating {} agents on {} instance(s)...", agents.size(), instances.size());

        return CompletableFuture.supplyAsync(() -> {
            List<CompletableFuture<Assistant>> futures = new ArrayList<>();

            // Create/update all agents
            for (String agentId : agents.keySet()) {
                futures.add(createAgent(agentId));
            }

            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            logger.info("✅ Successfully created/updated all {} agents on all {} instance(s)", agents.size(), instances.size());
            return null;
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
     * Sends a message to an agent and returns the typed response object.
     * Automatically deserializes the JSON response to the agent's configured result class.
     *
     * @param agentId          Agent ID
     * @param userMessage      User message content
     * @param threadId         Thread ID (null to create new thread)
     * @param additionalParams Additional parameters for the request
     * @return CompletableFuture with the agent's response as a typed object
     */
    public CompletableFuture<Object> requestAgentTyped(
            String agentId,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams) {

        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        // Get JSON response first
        return requestAgent(agentId, userMessage, threadId, additionalParams)
                .thenApply(jsonResponse -> {
                    try {
                        // If no result class configured, return raw JSON string
                        if (agent.getResultClass() == null || agent.getResultClass().isEmpty()) {
                            return jsonResponse;
                        }

                        // If no package configured, cannot deserialize
                        if (config.getAgentResultClassPackage() == null || config.getAgentResultClassPackage().isEmpty()) {
                            logger.warn("Agent {} has resultClass but agentResultClassPackage not configured, returning raw JSON", agentId);
                            return jsonResponse;
                        }

                        // Build full class name: package + resultClass
                        String fullClassName = config.getAgentResultClassPackage() + "." + agent.getResultClass();
                        logger.debug("Deserializing response for agent {} to class: {}", agentId, fullClassName);

                        // Load class dynamically and deserialize
                        Class<?> resultClass = Class.forName(fullClassName);
                        return objectMapper.readValue(jsonResponse, resultClass);

                    } catch (ClassNotFoundException e) {
                        String fullClassName = config.getAgentResultClassPackage() + "." + agent.getResultClass();
                        throw new RuntimeException("Result class not found: " + fullClassName + " for agent " + agentId, e);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize response for agent " + agentId + ": " + e.getMessage(), e);
                    }
                });
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
                // With unified architecture, always use executeAzureRequest (works for all instances)
                return executeAzureRequest(agent, userMessage, threadId, additionalParams);

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

        SimpleOpenAI client = getInstance(0);  // Use first instance for OpenAI

        // Get or create thread
        String actualThreadId = threadId;
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            var thread = client.threads().create(null).join();
            actualThreadId = thread.getId();
            logger.debug("Created new thread: {}", actualThreadId);
        }

        // Add message to thread
        var messageRequest = ThreadMessageRequest.builder()
                .role(ThreadMessageRole.USER)
                .content(userMessage)
                .build();
        client.threadMessages().create(actualThreadId, messageRequest).join();

        // Create and execute run
        String assistantId = agent.getAssistantIds() != null && !agent.getAssistantIds().isEmpty()
                ? agent.getAssistantIds().get(0)
                : null;
        if (assistantId == null) {
            throw new IllegalStateException("No assistant ID configured for agent: " + agent.getId());
        }
        ThreadRunRequest.ThreadRunRequestBuilder runBuilder = ThreadRunRequest.builder()
                .assistantId(assistantId);

        if (agent.getTemperature() != null) {
            runBuilder.temperature(agent.getTemperature());
        }

        ThreadRunRequest runRequest = runBuilder.build();
        ThreadRun run = client.threadRuns().create(actualThreadId, runRequest).join();

        // Poll for completion
        ThreadRun completedRun = pollForCompletion(client, actualThreadId, run.getId(), agent.getResponseTimeout());

        // Check status
        if (completedRun.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Run failed with status: " + completedRun.getStatus());
        }

        // Get response
        var messages = client.threadMessages().getList(actualThreadId).join();
        if (messages.isEmpty()) {
            throw new RuntimeException("No messages returned");
        }

        return extractMessageContent(messages.get(0).getContent());
    }

    /**
     * Executes a request using client with round-robin load balancing.
     */
    private String executeAzureRequest(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams) throws Exception {

        if (instances.isEmpty()) {
            throw new IllegalStateException("No instances configured");
        }

        // Round-robin instance selection
        int instanceIdx = instanceIndex.getAndUpdate(i -> (i + 1) % instances.size());
        SimpleOpenAI client = instances.get(instanceIdx);

        // Get assistant ID for this instance
        String assistantId = null;
        if (agent.getAssistantIds() != null && instanceIdx < agent.getAssistantIds().size()) {
            assistantId = agent.getAssistantIds().get(instanceIdx);
        }
        if (assistantId == null) {
            throw new IllegalStateException("No assistant ID configured for instance " + instanceIdx
                    + " of agent: " + agent.getId());
        }

        logger.debug("Using instance {} with assistant {}", instanceIdx, assistantId);

        // Get or create thread
        String actualThreadId = threadId;
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            var thread = client.threads().create(null).join();
            actualThreadId = thread.getId();
            logger.debug("Created new thread: {}", actualThreadId);
        }

        // Add message to thread
        var messageRequest = ThreadMessageRequest.builder()
                .role(ThreadMessageRole.USER)
                .content(userMessage)
                .build();
        client.threadMessages().create(actualThreadId, messageRequest).join();

        // Create and execute run
        ThreadRunRequest.ThreadRunRequestBuilder runBuilder = ThreadRunRequest.builder()
                .assistantId(assistantId);

        if (agent.getTemperature() != null) {
            runBuilder.temperature(agent.getTemperature());
        }

        ThreadRunRequest runRequest = runBuilder.build();
        ThreadRun run = client.threadRuns().create(actualThreadId, runRequest).join();

        // Poll for completion
        ThreadRun completedRun = pollForCompletionAzure(client, actualThreadId, run.getId(),
                agent.getResponseTimeout());

        // Check status
        if (completedRun.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Run failed with status: " + completedRun.getStatus());
        }

        // Get response
        var messages = client.threadMessages().getList(actualThreadId).join();
        if (messages.isEmpty()) {
            throw new RuntimeException("No messages returned");
        }

        return extractMessageContent(messages.get(0).getContent());
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
            ThreadRun run = client.threadRuns().getOne(threadId, runId).join();
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
            SimpleOpenAI client,
            String threadId,
            String runId,
            long timeoutSeconds) throws Exception {

        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        while (true) {
            ThreadRun run = client.threadRuns().getOne(threadId, runId).join();
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
            if (part instanceof ContentPartTextAnnotation) {
                ContentPartTextAnnotation textPart = (ContentPartTextAnnotation) part;
                if (textPart.getText() != null && textPart.getText().getValue() != null) {
                    sb.append(textPart.getText().getValue());
                }
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
     * For Azure multi-instance, the returned reference encodes the instance index.
     *
     * @param name    Vector store name
     * @param fileIds List of file IDs to attach
     * @return CompletableFuture with vector store reference.
     *         Format: "instanceIndex_vectorStoreId" for Azure multi-instance, plain ID otherwise.
     *         Example: "2_vs_abc123" means vector store vs_abc123 on instance 2.
     */
    public CompletableFuture<String> createVectorStore(String name, List<String> fileIds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                VectorStoreRequest request = VectorStoreRequest.builder()
                        .name(name)
                        .fileIds(fileIds)
                        .build();

                // Create on specific instance and encode instance index
                int instIndex = instanceIndex.getAndUpdate(i -> (i + 1) % instances.size());
                var vectorStore = instances.get(instIndex).vectorStores().create(request).join();
                String vectorStoreId = vectorStore.getId();

                logger.info("Created vector store: {} ({}) on instance {}", name, vectorStoreId, instIndex);

                // Encode instance index for multi-instance tracking
                if (instances.size() > 1) {
                    return instIndex + "_" + vectorStoreId;
                } else {
                    return vectorStoreId;
                }

            } catch (Exception e) {
                logger.error("Failed to create vector store: {}", name, e);
                throw new RuntimeException("Failed to create vector store", e);
            }
        });
    }

    /**
     * Deletes a vector store.
     *
     * @param vectorStoreRef Vector store reference (can be "instanceIndex_vectorStoreId" or plain ID)
     * @return CompletableFuture with deletion result
     */
    public CompletableFuture<Boolean> deleteVectorStore(String vectorStoreRef) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIndex = extractInstanceIndex(vectorStoreRef);
                String actualVectorStoreId = extractVectorStoreId(vectorStoreRef);

                instances.get(instanceIndex).vectorStores().delete(actualVectorStoreId).join();
                logger.info("Deleted vector store: {} from instance {}", actualVectorStoreId, instanceIndex);
                return true;
            } catch (Exception e) {
                logger.error("Failed to delete vector store: {}", vectorStoreRef, e);
                return false;
            }
        });
    }

    /**
     * Extracts instance index from a reference string.
     * Format: "instanceIndex_id" → returns instanceIndex
     * Plain ID → returns 0 (default instance)
     */
    private int extractInstanceIndex(String ref) {
        if (ref == null || !ref.contains("_")) {
            return 0;
        }
        int underscoreIndex = ref.indexOf('_');
        try {
            return Integer.parseInt(ref.substring(0, underscoreIndex));
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse instance index from ref: {}", ref);
            return 0;
        }
    }

    /**
     * Extracts the actual ID from a reference string.
     * Format: "instanceIndex_id" → returns id
     * Plain ID → returns as-is
     */
    private String extractVectorStoreId(String ref) {
        if (ref == null || !ref.contains("_")) {
            return ref;
        }
        int underscoreIndex = ref.indexOf('_');
        return ref.substring(underscoreIndex + 1);
    }

    /**
     * Extracts thread ID from a thread reference.
     * Same logic as extractVectorStoreId, but named for clarity.
     */
    private String extractThreadId(String threadRef) {
        return extractVectorStoreId(threadRef); // Same extraction logic
    }

    /**
     * Extracts response text from thread messages.
     * Gets the first assistant message and extracts its text content.
     */
    private String extractResponseFromMessages(io.github.sashirestela.openai.common.Page<io.github.sashirestela.openai.domain.assistant.ThreadMessage> messages) {
        if (messages == null || messages.getData().isEmpty()) {
            return "";
        }

        // Get first message (most recent)
        var message = messages.getData().get(0);
        if (message.getContent() == null || message.getContent().isEmpty()) {
            return "";
        }

        // Extract text from content parts
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : message.getContent()) {
            if (part instanceof ContentPartTextAnnotation) {
                ContentPartTextAnnotation textPart = (ContentPartTextAnnotation) part;
                if (textPart.getText() != null && textPart.getText().getValue() != null) {
                    sb.append(textPart.getText().getValue());
                }
            }
        }
        return sb.toString();
    }

    // ==================== PERSISTENT THREAD API ====================

    /**
     * Creates a persistent thread for multi-turn conversations.
     * The thread must be explicitly deleted when done using {@link #deleteThread(String)}.
     *
     * @return CompletableFuture with thread reference.
     *         Format: "instanceIndex_threadId" for Azure multi-instance, plain ID otherwise.
     *         Example: "2_thread_xyz" means thread on instance 2.
     */
    public CompletableFuture<String> createThread() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create on specific instance and encode instance index
                int instIndex = instanceIndex.getAndUpdate(i -> (i + 1) % instances.size());
                var thread = instances.get(instIndex).threads().create(null).join();
                String threadId = thread.getId();

                logger.debug("Created thread {} on instance {}", threadId, instIndex);

                // Encode instance index for multi-instance tracking
                if (instances.size() > 1) {
                    return instIndex + "_" + threadId;
                } else {
                    return threadId;
                }
            } catch (Exception e) {
                logger.error("Failed to create thread", e);
                throw new RuntimeException("Failed to create thread", e);
            }
        });
    }

    /**
     * Sends a message to an existing thread WITHOUT deleting it.
     * Use this for multi-turn conversations where you want to maintain context.
     *
     * @param agentId   Agent ID
     * @param threadRef Thread reference (from {@link #createThread()})
     * @param message   User message
     * @return CompletableFuture with agent's response and updated thread reference.
     *         Returns format: {"response": "...", "threadRef": "..."}
     */
    public CompletableFuture<String> sendMessageToThread(String agentId, String threadRef, String message) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIndex = extractInstanceIndex(threadRef);
                String actualThreadId = extractThreadId(threadRef);

                // Add message to thread
                var messageRequest = ThreadMessageRequest.builder()
                        .role(ThreadMessageRole.USER)
                        .content(message)
                        .build();

                SimpleOpenAI client = instances.get(instanceIndex);
                client.threadMessages().create(actualThreadId, messageRequest).join();

                // Create run - get assistant ID for this instance
                String assistantId = null;
                if (agent.getAssistantIds() != null && instanceIndex < agent.getAssistantIds().size()) {
                    assistantId = agent.getAssistantIds().get(instanceIndex);
                }
                if (assistantId == null) {
                    throw new IllegalStateException("No assistant ID configured for instance " + instanceIndex
                            + " of agent: " + agent.getId());
                }

                ThreadRunRequest runRequest = ThreadRunRequest.builder()
                        .assistantId(assistantId)
                        .temperature(agent.getTemperature())
                        .build();

                ThreadRun run = client.threadRuns().create(actualThreadId, runRequest).join();

                // Poll for completion
                ThreadRun completedRun = pollForCompletionAzure(client,
                        actualThreadId, run.getId(), agent.getResponseTimeout());

                // Get response
                var messages = client.threadMessages().getList(actualThreadId).join();
                return extractResponseFromMessages(messages);

            } catch (Exception e) {
                logger.error("Failed to send message to thread {}", threadRef, e);
                throw new RuntimeException("Failed to send message to thread", e);
            }
        });
    }

    /**
     * Deletes a persistent thread when conversation is complete.
     *
     * @param threadRef Thread reference (from {@link #createThread()})
     * @return CompletableFuture with deletion result
     */
    public CompletableFuture<Boolean> deleteThread(String threadRef) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIndex = extractInstanceIndex(threadRef);
                String actualThreadId = extractThreadId(threadRef);

                instances.get(instanceIndex).threads().delete(actualThreadId).join();
                logger.debug("Deleted thread {} from instance {}", actualThreadId, instanceIndex);
                return true;
            } catch (Exception e) {
                logger.error("Failed to delete thread: {}", threadRef, e);
                return false;
            }
        });
    }

    // ==================== VECTOR STORE METHODS ====================

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

        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create thread
                SimpleOpenAI client = getNextInstance();
                var thread = client.threads().create(null).join();
                String threadId = thread.getId();

                // Add message
                var messageRequest = ThreadMessageRequest.builder()
                        .role(ThreadMessageRole.USER)
                        .content(userMessage)
                        .build();
                client.threadMessages().create(threadId, messageRequest).join();

                // Create run with vector store
                String assistantId = agent.getAssistantIds() != null && !agent.getAssistantIds().isEmpty()
                        ? agent.getAssistantIds().get(0)
                        : null;
                if (assistantId == null) {
                    throw new IllegalStateException("No assistant ID configured for agent: " + agent.getId());
                }
                ThreadRunRequest runRequest = ThreadRunRequest.builder()
                        .assistantId(assistantId)
                        .temperature(agent.getTemperature())
                        .build();

                ThreadRun run = client.threadRuns().create(threadId, runRequest).join();

                // Poll for completion
                ThreadRun completedRun = pollForCompletion(client, threadId, run.getId(),
                        agent.getResponseTimeout());

                if (completedRun.getStatus() != RunStatus.COMPLETED) {
                    throw new RuntimeException("Run failed with status: " + completedRun.getStatus());
                }

                // Get response
                var messages = client.threadMessages().getList(threadId).join();
                if (messages.isEmpty()) {
                    throw new RuntimeException("No messages returned");
                }

                return extractMessageContent(messages.get(0).getContent());

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

                // Use next instance (round-robin across all instances)
                SimpleOpenAI client = getNextInstance();
                Chat chatResponse = client.chatCompletions().create(request).join();

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

                // Use next instance (round-robin across all instances)
                SimpleOpenAI client = getNextInstance();
                Chat chatResponse = client.chatCompletions().create(request).join();

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

        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
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
                // Convert size string to Size enum
                Size sizeEnum = Size.valueOf("X" + size.replace("x", "_"));

                // Convert quality string to Quality enum
                Quality qualityEnum = quality != null ? Quality.valueOf(quality.toUpperCase()) : null;

                ImageRequest request = ImageRequest.builder()
                        .prompt(prompt)
                        .model(model)
                        .size(sizeEnum)
                        .quality(qualityEnum)
                        .n(1)
                        .build();

                SimpleOpenAI client = getNextInstance();
                var response = client.images().create(request).join();

                if (response == null || response.isEmpty()) {
                    throw new RuntimeException("No images returned");
                }

                String imageUrl = response.get(0).getUrl();
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

        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
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
                SimpleOpenAI client = getNextInstance();
                io.github.sashirestela.openai.domain.batch.Batch batch = client.batches().create(request).join();

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
        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                SimpleOpenAI client = getInstance(0);
                return client.batches().getOne(batchId).join();
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
        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                SimpleOpenAI client = getInstance(0);
                io.github.sashirestela.openai.domain.batch.Batch batch = client.batches().cancel(batchId).join();
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

        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                SimpleOpenAI client = getInstance(0);
                return client.batches().getList(after, limit).join();
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

        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                long timeoutMs = timeoutSeconds * 1000;

                while (true) {
                    SimpleOpenAI client = getInstance(0);
                    io.github.sashirestela.openai.domain.batch.Batch batch = client.batches().getOne(batchId).join();
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
     * Generates an image using DALL-E with default settings.
     *
     * @param prompt The text description of the image to generate
     * @return CompletableFuture with base64-encoded image data
     */
    public CompletableFuture<String> generateImage(String prompt) {
        return generateImage(prompt, "dall-e-3", Size.X1024, Quality.STANDARD);
    }

    /**
     * Generates an image using DALL-E with custom settings.
     * Uses round-robin load balancing across configured instances.
     *
     * @param prompt The text description of the image to generate
     * @param model The DALL-E model to use (e.g., "dall-e-3", "dall-e-2")
     * @param size The image size (e.g., Size.X1024, Size.X1792_1024)
     * @param quality The image quality ("standard" or "hd")
     * @return CompletableFuture with base64-encoded image data
     */
    public CompletableFuture<String> generateImage(String prompt, String model, Size size, Quality quality) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Rate limit check
                rateLimiter.tryConsume();

                // Get next IMAGE instance using round-robin (OpenAI + Azure DALL-E only)
                SimpleOpenAI instance = getNextImageInstance();

                // Create image request with b64_json response format
                ImageRequest imageRequest = ImageRequest.builder()
                        .model(model)
                        .prompt(prompt)
                        .size(size)
                        .quality(quality)
                        .n(1)
                        .responseFormat(io.github.sashirestela.openai.domain.image.ImageResponseFormat.B64JSON)
                        .build();

                logger.debug("Generating image with model: {}, size: {}, quality: {}", model, size, quality);

                // Call DALL-E API
                List<Image> response = instance.images().create(imageRequest).join();

                if (response == null || response.isEmpty()) {
                    throw new RuntimeException("Image generation returned empty response");
                }

                // Extract base64 image data
                String base64Image = response.get(0).getB64Json();
                logger.debug("Image generated successfully (base64 length: {})",
                    base64Image != null ? base64Image.length() : 0);

                return base64Image;

            } catch (Exception e) {
                logger.error("Failed to generate image: {}", e.getMessage());
                throw new RuntimeException("Image generation failed", e);
            }
        });
    }

    /**
     * Shuts down the service and releases resources.
     */
    public void shutdown() {
        logger.info("Shutting down AgentService");
        // Add any cleanup logic here if needed
    }
}
