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
import io.github.sashirestela.openai.domain.assistant.ThreadRequest;
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
import java.util.Arrays;
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
    private final List<Instance> instances;  // All instances (OpenAI + Azure) with their deployed models
    private final Map<String, AtomicInteger> modelIndexes;  // Separate round-robin counter per model
    private final AtomicInteger globalInstanceIndex;  // Global counter for model-agnostic operations (threads, vector stores)
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
        this.modelIndexes = new ConcurrentHashMap<>();  // One atomic counter per model
        this.globalInstanceIndex = new AtomicInteger(0);  // For model-agnostic operations

        // Initialize OpenAI instances (if configured)
        if (config.getOpenAiApiKeys() != null && !config.getOpenAiApiKeys().isEmpty()) {
            // Parse deployed models from config (default to common models if not specified)
            String modelsConfig = config.getOpenAiModels();
            List<String> deployedModels = modelsConfig != null && !modelsConfig.trim().isEmpty()
                    ? Arrays.asList(modelsConfig.split(","))
                    : List.of("gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo", "dall-e-3");

            for (int i = 0; i < config.getOpenAiApiKeys().size(); i++) {
                String apiKey = config.getOpenAiApiKeys().get(i);

                // Use provided URL or default to standard OpenAI endpoint
                String baseUrl = "https://api.openai.com/v1";
                if (config.getOpenAiBaseUrls() != null && i < config.getOpenAiBaseUrls().size()) {
                    String providedUrl = config.getOpenAiBaseUrls().get(i);
                    if (providedUrl != null && !providedUrl.trim().isEmpty()) {
                        baseUrl = providedUrl;
                    }
                }

                SimpleOpenAI client = SimpleOpenAI.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .isAzure(false)
                        .build();

                Instance instance = Instance.builder()
                        .id("openai-" + i)
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .provider(Provider.OPENAI)
                        .azureApiVersion(null)  // Not Azure
                        .deployedModels(deployedModels)
                        .client(client)
                        .build();

                this.instances.add(instance);
            }
            logger.info("Initialized {} OpenAI instance(s) with models: {}",
                    config.getOpenAiApiKeys().size(), deployedModels);
        }

        // Initialize Azure chat instances (if configured)
        if (config.getAzureApiKeys() != null && !config.getAzureApiKeys().isEmpty()) {
            // Parse deployed models from config (default to gpt-4o if not specified)
            String azureModelsConfig = config.getAzureModels();
            List<String> azureDeployedModels = azureModelsConfig != null && !azureModelsConfig.trim().isEmpty()
                    ? Arrays.asList(azureModelsConfig.split(","))
                    : List.of("gpt-4o");

            for (int i = 0; i < config.getAzureApiKeys().size(); i++) {
                // Normalize Azure baseUrl to ensure it contains /openai
                String normalizedBaseUrl = normalizeAzureBaseUrl(config.getAzureBaseUrls().get(i));

                SimpleOpenAI client = SimpleOpenAI.builder()
                        .apiKey(config.getAzureApiKeys().get(i))
                        .baseUrl(normalizedBaseUrl)
                        .isAzure(true)
                        .azureApiVersion(config.getAzureApiVersion())
                        .build();

                Instance instance = Instance.builder()
                        .id("azure-chat-" + i)
                        .baseUrl(normalizedBaseUrl)
                        .apiKey(config.getAzureApiKeys().get(i))
                        .provider(Provider.AZURE)
                        .azureApiVersion(config.getAzureApiVersion())
                        .deployedModels(azureDeployedModels)
                        .client(client)
                        .build();

                this.instances.add(instance);
            }
            logger.info("Initialized {} Azure OpenAI chat instance(s) with models: {}",
                    config.getAzureApiKeys().size(), azureDeployedModels);
        }

        // Initialize Azure DALL-E instances (if configured) - SEPARATE deployment
        if (config.getAzureDalleApiKeys() != null && !config.getAzureDalleApiKeys().isEmpty()) {
            // Parse deployed models from config (default to dall-e-3 if not specified)
            String azureDalleModelsConfig = config.getAzureDalleModels();
            List<String> azureDalleDeployedModels = azureDalleModelsConfig != null && !azureDalleModelsConfig.trim().isEmpty()
                    ? Arrays.asList(azureDalleModelsConfig.split(","))
                    : List.of("dall-e-3");

            for (int i = 0; i < config.getAzureDalleApiKeys().size(); i++) {
                // Normalize Azure baseUrl to ensure it contains /openai
                String normalizedBaseUrl = normalizeAzureBaseUrl(config.getAzureDalleBaseUrls().get(i));

                SimpleOpenAI client = SimpleOpenAI.builder()
                        .apiKey(config.getAzureDalleApiKeys().get(i))
                        .baseUrl(normalizedBaseUrl)
                        .isAzure(true)
                        .azureApiVersion(config.getAzureDalleApiVersion())
                        .build();

                Instance instance = Instance.builder()
                        .id("azure-dalle-" + i)
                        .baseUrl(normalizedBaseUrl)
                        .apiKey(config.getAzureDalleApiKeys().get(i))
                        .provider(Provider.AZURE)
                        .azureApiVersion(config.getAzureDalleApiVersion())
                        .deployedModels(azureDalleDeployedModels)
                        .client(client)
                        .build();

                this.instances.add(instance);
            }
            logger.info("Initialized {} Azure DALL-E instance(s) with models: {}",
                    config.getAzureDalleApiKeys().size(), azureDalleDeployedModels);
        }

        if (this.instances.isEmpty()) {
            throw new IllegalStateException("No OpenAI or Azure instances configured");
        }

        logger.info("Total instances: {} | Models available: {}",
                this.instances.size(),
                this.instances.stream()
                        .flatMap(i -> i.getDeployedModels().stream())
                        .distinct()
                        .collect(java.util.stream.Collectors.toList()));

        // Initialize rate limiter
        this.rateLimiter = new RateLimiter(config.getRequestsPerSecond());
        logger.info("Initialized rate limiter: {} requests/second", config.getRequestsPerSecond());

        // Load agent definitions if folder path is provided
        if (config.getAgentJsonFolderPath() != null && !config.getAgentJsonFolderPath().isEmpty()) {
            loadAgentDefinitions();
        }
    }

    // ==================== INSTANCE INDEX ENCODING/DECODING ====================
    // Thread IDs and Vector Store IDs are encoded with instance index for persistence
    // Format: "instanceIndex_actualId" (e.g., "3_thread_abc123" or "5_vs_xyz789")

    /**
     * Encodes a thread/vector store ID with its instance index.
     * Format: "instanceIndex_actualId"
     *
     * @param instanceIndex Instance index (0-8)
     * @param actualId Actual OpenAI ID (e.g., "thread_abc123")
     * @return Encoded ID (e.g., "3_thread_abc123")
     */
    private String encodeWithInstance(int instanceIndex, String actualId) {
        return instanceIndex + "_" + actualId;
    }

    /**
     * Decodes an encoded ID to extract instance index and actual ID.
     * Format: "instanceIndex_actualId"
     *
     * @param encodedId Encoded ID (e.g., "3_thread_abc123")
     * @return Array [instanceIndex, actualId] or null if not encoded
     */
    private String[] decodeInstanceId(String encodedId) {
        if (encodedId == null || !encodedId.contains("_")) {
            return null;
        }
        String[] parts = encodedId.split("_", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            Integer.parseInt(parts[0]);  // Validate it's a number
            return parts;
        } catch (NumberFormatException e) {
            return null;  // Not encoded, just a regular ID with underscore
        }
    }

    // ==================== MODEL-AWARE INSTANCE SELECTION ====================

    /**
     * Gets the next instance INDEX that has the specified model deployed.
     * Uses SEPARATE round-robin counter PER MODEL for optimal load distribution.
     *
     * Algorithm:
     * 1. Get or create atomic counter for this model
     * 2. Increment atomic counter
     * 3. Check if instance at this index has the model
     * 4. If yes, return this index
     * 5. If no, increment again and check next
     * 6. If we've checked all instances → error (no instance has this model)
     *
     * Benefits of per-model counters:
     * - gpt-4o requests don't affect gpt-4o-mini round-robin
     * - Each model has independent, evenly distributed load balancing
     *
     * @param model Model name (e.g., "gpt-4o", "dall-e-3")
     * @return INDEX of instance that has this model
     * @throws IllegalArgumentException if no instance has the model
     */
    private int getNextInstanceForModel(String model) {
        // Get or create atomic counter for this specific model
        AtomicInteger modelIndex = modelIndexes.computeIfAbsent(model, k -> new AtomicInteger(0));

        int startIndex = modelIndex.get();
        logger.trace("Round-robin for model '{}': current counter = {}", model, startIndex);

        // Try all instances starting from current position
        for (int i = 0; i < instances.size(); i++) {
            int idx = (startIndex + i) % instances.size();
            Instance instance = instances.get(idx);

            if (instance.hasModel(model)) {
                // Advance this model's counter for next call
                int nextIndex = (idx + 1) % instances.size();
                modelIndex.set(nextIndex);
                logger.trace("Round-robin for model '{}': selected instance {}, next counter will be {}",
                        model, idx, nextIndex);
                return idx;
            }
        }

        // No instance found with this model
        throw new IllegalArgumentException(
                String.format("No instance configured with model '%s'. Available models: %s",
                        model,
                        instances.stream()
                                .flatMap(i -> i.getDeployedModels().stream())
                                .distinct()
                                .collect(java.util.stream.Collectors.toList()))
        );
    }

    /**
     * Creates a client for Azure chat completion or image generation.
     * For Azure, these endpoints require /deployments/{model}/ in the URL path.
     * For OpenAI, returns the standard client.
     *
     * Azure URL format: https://[host].openai.azure.com/openai/deployments/{model}/[endpoint]
     * OpenAI URL format: https://api.openai.com/v1/[endpoint] (model in body)
     *
     * @param model Model name (used as deployment name for Azure)
     * @param instanceIndex Index of the instance to use
     * @return SimpleOpenAI client configured for the endpoint
     */
    private SimpleOpenAI getClientForChatOrImage(String model, int instanceIndex) {
        Instance instance = instances.get(instanceIndex);

        // For OpenAI instances, just return the client directly
        if (instance.getProvider() == Provider.OPENAI) {
            return instance.getClient();
        }

        // For Azure instances, create a client with deployment URL
        // Azure chat/image URLs need: https://[baseUrl]/openai/deployments/{model}/[endpoint]
        String baseUrl = instance.getBaseUrl(); // Already has /openai from normalization
        String deploymentUrl = baseUrl + "/deployments/" + model;

        logger.debug("Creating Azure chat/image client for model '{}': {} (deployment URL: {})",
                     model, instance.getId(), deploymentUrl);

        return SimpleOpenAI.builder()
                .baseUrl(deploymentUrl)
                .apiKey(instance.getApiKey())
                .isAzure(true)
                .azureApiVersion(instance.getAzureApiVersion())
                .build();
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

                // Create or update assistant ONLY on instances that have this model deployed
                Assistant firstAssistant = null;
                boolean assistantIdsChanged = false;  // Track if we need to persist changes

                for (int i = 0; i < instances.size(); i++) {
                    Instance instance = instances.get(i);

                    // Skip instances that don't have this model deployed
                    if (!instance.hasModel(agent.getModel())) {
                        continue;
                    }

                    // Use per-model Azure client (handles Azure deployment URLs correctly)
                    SimpleOpenAI client = getOrCreateAzureClientForModel(agent.getModel(), i);
                    Assistant assistant;

                    String existingAssistantId = agent.getAssistantIds().get(i);
                    if (existingAssistantId != null && !existingAssistantId.isEmpty()) {
                        // Try to update existing assistant - if fails (404), create new one
                        try {
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
                        } catch (Exception e) {
                            // If modify fails (404 = assistant doesn't exist), create new assistant
                            logger.warn("⚠️ Failed to modify assistant {} on instance {} ({}), creating new assistant...",
                                    existingAssistantId, i, e.getMessage());
                            assistant = client.assistants().create(request).join();
                            agent.getAssistantIds().set(i, assistant.getId());
                            assistantIdsChanged = true;
                            logger.info("✅ Created new assistant on instance {}: {} ({}) to replace {}",
                                    i, agent.getName(), assistant.getId(), existingAssistantId);
                        }
                    } else {
                        // Create new assistant
                        assistant = client.assistants().create(request).join();
                        agent.getAssistantIds().set(i, assistant.getId());
                        assistantIdsChanged = true;
                        logger.info("✅ Created assistant on instance {}: {} ({})", i, agent.getName(), assistant.getId());
                    }

                    // Keep reference to first assistant to return
                    if (i == 0) {
                        firstAssistant = assistant;
                    }
                }

                // Count instances with this model
                long instancesWithModel = instances.stream()
                        .filter(inst -> inst.hasModel(agent.getModel()))
                        .count();

                // Persist assistant IDs back to JSON if they changed
                if (assistantIdsChanged) {
                    try {
                        saveAgentDefinitionIds(agent);
                        logger.info("💾 Persisted updated assistant IDs for agent: {}", agent.getName());
                    } catch (IOException e) {
                        logger.error("⚠️ Failed to persist assistant IDs to JSON for agent: {}", agent.getName(), e);
                        // Continue anyway - IDs are in memory
                    }
                }

                logger.info("🎯 Agent '{}' (model: {}) successfully created/updated on {} instance(s) that have this model",
                        agent.getName(), agent.getModel(), instancesWithModel);
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
     * Normalizes Azure base URL to ensure it contains /openai path.
     * Azure OpenAI requires URLs in format: https://instance.openai.azure.com/openai
     *
     * @param baseUrl Base Azure URL (e.g., "https://instance.openai.azure.com")
     * @return Normalized URL with /openai path (e.g., "https://instance.openai.azure.com/openai")
     */
    private String normalizeAzureBaseUrl(String baseUrl) {
        // Remove trailing slash if present
        String cleanBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        // Add /openai if not already present
        if (!cleanBaseUrl.endsWith("/openai") && !cleanBaseUrl.contains("/openai/")) {
            return cleanBaseUrl + "/openai";
        }
        return cleanBaseUrl;
    }

    /**
     * Builds Azure URL with model deployment path appended.
     * Azure OpenAI requires URLs in format: https://instance.openai.azure.com/openai/deployments/{model}
     *
     * @param baseUrl Base Azure URL (should already contain /openai from normalization)
     * @param deploymentName Model deployment name (e.g., "gpt-4o", "gpt-4o-mini")
     * @return Full Azure URL with deployment path
     */
    private String buildAzureUrlWithDeployment(String baseUrl, String deploymentName) {
        // Remove trailing slash if present
        String cleanBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        // Add deployment path if not already present
        if (!cleanBaseUrl.contains("/deployments/")) {
            return cleanBaseUrl + "/deployments/" + deploymentName;
        }
        return cleanBaseUrl;
    }

    /**
     * Gets or creates an Azure client configured for a specific model deployment.
     * This is necessary because Azure OpenAI requires the model deployment name in the URL.
     *
     * @param model Model name (e.g., "gpt-4o", "gpt-4o-mini")
     * @param index Instance index
     * @return SimpleOpenAI client configured for the model deployment
     */
    private SimpleOpenAI getOrCreateAzureClientForModel(String model, int index) {
        Instance instance = instances.get(index);

        // If OpenAI provider, return client directly
        if (instance.getProvider() == Provider.OPENAI) {
            return instance.getClient();
        }

        // For Azure, build deployment URL with model name
        String deploymentUrl = buildAzureUrlWithDeployment(instance.getBaseUrl(), model);

        // Create Azure client with model-specific deployment URL
        return SimpleOpenAI.builder()
                .apiKey(instance.getApiKey())
                .baseUrl(deploymentUrl)
                .isAzure(true)
                .azureApiVersion(instance.getAzureApiVersion())
                .build();
    }

    /**
     * Saves updated assistant IDs back to the agent's JSON definition file.
     * Only updates the "assistantIds" field, preserving all other fields.
     *
     * @param agent Agent with updated assistant IDs
     * @throws IOException if file cannot be read/written
     */
    private void saveAgentDefinitionIds(Agent agent) throws IOException {
        if (config.getAgentJsonFolderPath() == null || config.getAgentJsonFolderPath().isEmpty()) {
            logger.warn("Agent JSON folder path not configured, cannot persist assistant IDs");
            return;
        }

        Path agentFolder = Paths.get(config.getAgentJsonFolderPath());

        // Find the agent's JSON file
        try (Stream<Path> paths = Files.walk(agentFolder)) {
            List<Path> matchingFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> {
                        try {
                            String content = Files.readString(path);
                            AgentDefinition def = objectMapper.readValue(content, AgentDefinition.class);
                            return agent.getId().equals(def.getId());
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            if (matchingFiles.isEmpty()) {
                logger.warn("No JSON file found for agent ID: {}", agent.getId());
                return;
            }

            Path jsonFile = matchingFiles.get(0);

            // Load existing JSON as Map to preserve all fields
            @SuppressWarnings("unchecked")
            Map<String, Object> existingJson = objectMapper.readValue(jsonFile.toFile(), Map.class);

            // Update only the assistantIds field
            existingJson.put("assistantIds", agent.getAssistantIds());

            // Write back with pretty printing
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(jsonFile.toFile(), existingJson);

            logger.debug("Saved assistant IDs to: {}", jsonFile);

        } catch (IOException e) {
            logger.error("Failed to persist assistant IDs for agent: {}", agent.getId(), e);
            throw e;
        }
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
                // Execute request on instance that has this agent's model
                return executeAgentRequest(agent, userMessage, threadId, additionalParams);

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

        // Get instance that has this agent's model
        int instanceIdx = getNextInstanceForModel(agent.getModel());
        SimpleOpenAI client = instances.get(instanceIdx).getClient();

        // Get or create thread
        String actualThreadId = threadId;
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            var thread = client.threads().create(ThreadRequest.builder().build()).join();
            actualThreadId = thread.getId();
            logger.debug("Created new thread: {}", actualThreadId);
        }

        // Add message to thread
        var messageRequest = ThreadMessageRequest.builder()
                .role(ThreadMessageRole.USER)
                .content(userMessage)
                .build();
        client.threadMessages().create(actualThreadId, messageRequest).join();

        // Get assistant ID for this specific instance
        String assistantId = null;
        if (agent.getAssistantIds() != null && instanceIdx < agent.getAssistantIds().size()) {
            assistantId = agent.getAssistantIds().get(instanceIdx);
        }
        if (assistantId == null) {
            throw new IllegalStateException("No assistant ID configured for instance " + instanceIdx
                    + " of agent: " + agent.getId());
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
     * Executes an agent request using model-aware instance selection.
     * Supports persistent threads via encoded thread IDs (format: "instanceIndex_threadId").
     */
    private String executeAgentRequest(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams) throws Exception {

        if (instances.isEmpty()) {
            throw new IllegalStateException("No instances configured");
        }

        // Determine which instance to use
        int instanceIdx;
        String actualThreadId;

        // Check if threadId is encoded with instance index (persistent thread)
        String[] decoded = decodeInstanceId(threadId);
        if (decoded != null) {
            // Persistent thread - MUST use the instance that created it
            instanceIdx = Integer.parseInt(decoded[0]);
            actualThreadId = decoded[1];
            logger.debug("Using persistent thread {} on instance {} (model: {})",
                    actualThreadId, instanceIdx, agent.getModel());
        } else {
            // New thread or non-persistent - use model-aware round-robin
            instanceIdx = getNextInstanceForModel(agent.getModel());
            actualThreadId = threadId;  // null or regular (non-encoded) thread ID
            logger.debug("Using model-aware round-robin for agent '{}': selected instance {} for model '{}'",
                    agent.getName(), instanceIdx, agent.getModel());
        }

        SimpleOpenAI client = instances.get(instanceIdx).getClient();

        // Get assistant ID for this specific instance
        String assistantId = null;
        if (agent.getAssistantIds() != null && instanceIdx < agent.getAssistantIds().size()) {
            assistantId = agent.getAssistantIds().get(instanceIdx);
        }
        if (assistantId == null) {
            throw new IllegalStateException("No assistant ID configured for instance " + instanceIdx
                    + " of agent: " + agent.getId());
        }

        logger.debug("Using instance {} (model: {}) with assistant {}", instanceIdx, agent.getModel(), assistantId);

        // Create thread if needed (actualThreadId already set from decoding logic above)
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            var thread = client.threads().create(ThreadRequest.builder().build()).join();
            actualThreadId = thread.getId();
            logger.debug("Created new thread {} on instance {}", actualThreadId, instanceIdx);
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

                // Create on specific instance and encode instance index (global round-robin)
                int instIndex = globalInstanceIndex.getAndUpdate(i -> (i + 1) % instances.size());
                var vectorStore = instances.get(instIndex).getClient().vectorStores().create(request).join();
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

                instances.get(instanceIndex).getClient().vectorStores().delete(actualVectorStoreId).join();
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
     * Creates a new persistent thread on an instance that has the specified model.
     * Returns an encoded thread ID (format: "instanceIndex_threadId") for persistence.
     * The thread must be explicitly deleted when done using {@link #deleteThread(String)}.
     *
     * @param model Model name (e.g., "gpt-4o", "gpt-4o-mini")
     * @return Encoded thread ID (e.g., "3_thread_abc123")
     */
    public CompletableFuture<String> createThread(String model) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get instance that has this model (model-aware round-robin)
                int instIndex = getNextInstanceForModel(model);
                var thread = instances.get(instIndex).getClient().threads().create(ThreadRequest.builder().build()).join();
                String threadId = thread.getId();

                logger.debug("Created thread {} on instance {} (model: {})", threadId, instIndex, model);

                // Encode instance index for persistence across requests
                return encodeWithInstance(instIndex, threadId);
            } catch (Exception e) {
                logger.error("Failed to create thread for model: {}", model, e);
                throw new RuntimeException("Failed to create thread for model: " + model, e);
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

                SimpleOpenAI client = instances.get(instanceIndex).getClient();
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

                instances.get(instanceIndex).getClient().threads().delete(actualThreadId).join();
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
                // Create thread on instance that has this agent's model
                int instanceIdx = getNextInstanceForModel(agent.getModel());
                SimpleOpenAI client = instances.get(instanceIdx).getClient();
                var thread = client.threads().create(ThreadRequest.builder().build()).join();
                String threadId = thread.getId();

                // Add message
                var messageRequest = ThreadMessageRequest.builder()
                        .role(ThreadMessageRole.USER)
                        .content(userMessage)
                        .build();
                client.threadMessages().create(threadId, messageRequest).join();

                // Get assistant ID for this specific instance
                String assistantId = null;
                if (agent.getAssistantIds() != null && instanceIdx < agent.getAssistantIds().size()) {
                    assistantId = agent.getAssistantIds().get(instanceIdx);
                }
                if (assistantId == null) {
                    throw new IllegalStateException("No assistant ID configured for instance " + instanceIdx
                            + " of agent: " + agent.getId());
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
     * Automatically routes to the correct instance based on the model.
     *
     * @param model       Model name (e.g., "gpt-4o", "gpt-4o-mini")
     * @param messages    List of chat messages
     * @param temperature Temperature (optional, can be null)
     * @return CompletableFuture with response content
     */
    public CompletableFuture<String> requestChatCompletion(
            String model,
            List<ChatMessage> messages,
            Double temperature) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                ChatRequest.ChatRequestBuilder requestBuilder = ChatRequest.builder()
                        .model(model)
                        .messages(messages);

                if (temperature != null) {
                    requestBuilder.temperature(temperature);
                }

                ChatRequest request = requestBuilder.build();

                // Get instance that has this model deployed
                int instanceIdx = getNextInstanceForModel(model);
                SimpleOpenAI client = getClientForChatOrImage(model, instanceIdx);
                Chat chatResponse = client.chatCompletions().create(request).join();

                if (chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
                    throw new RuntimeException("No choices returned in chat completion");
                }

                return chatResponse.getChoices().get(0).getMessage().getContent();

            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                // Check for Azure content filter / content policy violation
                if (errorMessage.contains("content_filter") ||
                    errorMessage.contains("content_policy_violation") ||
                    errorMessage.contains("responsibleaipolicyviolation")) {

                    logger.error("❌ Azure content filter blocked the request");
                    logger.error("   Error: {}", e.getMessage());
                    logger.error("   This usually means the prompt triggered Azure's content safety policies");
                    logger.error("   Consider: 1) Rephrasing the prompt, 2) Using a different model, or 3) Reviewing Azure content filter settings");
                    throw new RuntimeException("Content filter violation: " + e.getMessage(), e);
                }

                logger.error("Chat completion request failed", e);
                throw new RuntimeException("Chat completion request failed", e);
            }
        });
    }

    /**
     * Sends a structured chat completion request with JSON Schema.
     * Automatically routes to the correct instance based on the model.
     *
     * @param model         Model name (e.g., "gpt-4o")
     * @param messages      List of chat messages
     * @param temperature   Temperature (optional, can be null)
     * @param resultClass   Result class name for schema generation
     * @return CompletableFuture with response content
     */
    public CompletableFuture<String> requestStructuredChatCompletion(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            String resultClass) {

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

                // Get instance that has this model deployed
                int instanceIdx = getNextInstanceForModel(model);
                SimpleOpenAI client = getClientForChatOrImage(model, instanceIdx);
                Chat chatResponse = client.chatCompletions().create(request).join();

                if (chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
                    throw new RuntimeException("No choices returned in structured chat completion");
                }

                return chatResponse.getChoices().get(0).getMessage().getContent();

            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                // Check for Azure content filter / content policy violation
                if (errorMessage.contains("content_filter") ||
                    errorMessage.contains("content_policy_violation") ||
                    errorMessage.contains("responsibleaipolicyviolation")) {

                    logger.error("❌ Azure content filter blocked the structured chat completion request");
                    logger.error("   Error: {}", e.getMessage());
                    logger.error("   This usually means the prompt triggered Azure's content safety policies");
                    logger.error("   Consider: 1) Rephrasing the prompt, 2) Using a different model, or 3) Reviewing Azure content filter settings");
                    throw new RuntimeException("Content filter violation: " + e.getMessage(), e);
                }

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

                // Get instance that has this image model deployed (dall-e-3)
                int instanceIdx = getNextInstanceForModel(model);
                SimpleOpenAI client = getClientForChatOrImage(model, instanceIdx);
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
                // Batch API typically uses gpt-4o - route to instance with that model
                SimpleOpenAI client = instances.get(getNextInstanceForModel("gpt-4o")).getClient();
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
                SimpleOpenAI client = instances.get(getNextInstanceForModel("gpt-4o")).getClient();
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
                SimpleOpenAI client = instances.get(getNextInstanceForModel("gpt-4o")).getClient();
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
                SimpleOpenAI client = instances.get(getNextInstanceForModel("gpt-4o")).getClient();
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
                    SimpleOpenAI client = instances.get(getNextInstanceForModel("gpt-4o")).getClient();
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

                // Get instance that has this image model deployed
                int instanceIdx = getNextInstanceForModel(model);
                SimpleOpenAI instance = getClientForChatOrImage(model.toString(), instanceIdx);

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
