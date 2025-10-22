package io.github.sashirestela.openai.agent;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Configuration for {@link AgentService} with builder pattern.
 * Provides flexible configuration for OpenAI and Azure OpenAI deployments.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * AgentServiceConfig config = AgentServiceConfig.builder()
 *     .provider(Provider.OPENAI)
 *     .openAiApiKey(System.getenv("OPENAI_API_KEY"))
 *     .agentResultClassPackage("com.example.results")
 *     .requestsPerSecond(5)
 *     .maxRetries(3)
 *     .build();
 * }</pre>
 *
 * @see AgentService
 */
@Getter
@Builder
public class AgentServiceConfig {

    // === OpenAI Configuration ===

    /**
     * OpenAI API keys (one per instance).
     * Supports multiple keys for load balancing or different OpenAI-compatible endpoints.
     * The number of instances is automatically determined from the size of this list.
     */
    private final List<String> openAiApiKeys;

    /**
     * Base URLs for OpenAI API (one per instance).
     * Default: "https://api.openai.com/v1" for all instances if not specified.
     * Can be different for OpenAI-compatible endpoints (local LLMs, proxies, etc.)
     */
    private final List<String> openAiBaseUrls;

    /**
     * Models deployed on OpenAI instances (comma-separated).
     * Default: "gpt-4o,gpt-4o-mini,gpt-3.5-turbo,dall-e-3"
     * Example: "gpt-4o,dall-e-3"
     */
    private final String openAiModels;

    // === Azure Configuration ===

    /**
     * Azure OpenAI API keys (one per instance).
     * The number of instances is automatically determined from the size of this list.
     */
    private final List<String> azureApiKeys;

    /**
     * Azure OpenAI base URLs (one per instance).
     * Format: https://{resource-name}.openai.azure.com/
     */
    private final List<String> azureBaseUrls;

    /**
     * Azure OpenAI API version.
     * Example: "2024-08-01-preview"
     */
    private final String azureApiVersion;

    /**
     * Models deployed on Azure chat instances (comma-separated).
     * Default: "gpt-4o"
     * Example: "gpt-4o,gpt-4o-mini"
     */
    private final String azureModels;

    // === DALL-E Configuration (Azure only) ===

    /**
     * Azure OpenAI API keys for DALL-E (optional, separate deployment).
     */
    private final List<String> azureDalleApiKeys;

    /**
     * Azure OpenAI base URLs for DALL-E (optional, separate deployment).
     */
    private final List<String> azureDalleBaseUrls;

    /**
     * Azure API version for DALL-E.
     * Default: "2024-02-01"
     */
    @Builder.Default
    private final String azureDalleApiVersion = "2024-02-01";

    /**
     * Models deployed on Azure DALL-E instances (comma-separated).
     * Default: "dall-e-3"
     */
    private final String azureDalleModels;

    // === Agent Configuration ===

    /**
     * Package name for agent result classes.
     * Used for dynamic class loading when mapping responses.
     * Example: "com.example.agents.results"
     */
    private final String agentResultClassPackage;

    /**
     * Path to folder containing agent JSON definition files.
     * Example: "/config/agents" or "classpath:agents"
     */
    private final String agentJsonFolderPath;

    /**
     * Default timeout for agent responses in milliseconds.
     * Default: 120000ms (2 minutes)
     */
    @Builder.Default
    private final long defaultResponseTimeout = 120000L;

    // === Rate Limiting ===

    /**
     * Maximum requests per second to OpenAI API.
     * Uses token bucket algorithm (Bucket4j).
     * Default: 5 requests/second
     */
    @Builder.Default
    private final int requestsPerSecond = 5;

    /**
     * Maximum retry attempts for failed requests.
     * Default: 3
     */
    @Builder.Default
    private final int maxRetries = 3;

    /**
     * Base delay for exponential backoff in milliseconds.
     * Default: 10000ms (10 seconds)
     */
    @Builder.Default
    private final long retryBaseDelayMs = 10000L;

    /**
     * Delay for rate limit errors in milliseconds.
     * Default: 60000ms (1 minute)
     */
    @Builder.Default
    private final long rateLimitDelayMs = 60000L;

    /**
     * Delay for 502 errors in milliseconds.
     * Default: 300000ms (5 minutes)
     */
    @Builder.Default
    private final long error502DelayMs = 300000L;

    // === Image Generation Configuration ===

    /**
     * Enable automatic prompt sanitization when content policy violations occur.
     * Default: false
     */
    @Builder.Default
    private final boolean enableImagePromptSanitization = false;

    /**
     * Agent ID to use for image prompt sanitization.
     * Default: "298"
     */
    @Builder.Default
    private final String imageSanitizerAgentId = "298";

    // === Executor Configuration ===

    /**
     * Custom executor for asynchronous requests (optional).
     * If null, AgentService will create a default executor.
     */
    private final Executor customExecutor;

    /**
     * Validate configuration consistency.
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        // Validate OpenAI configuration if provided
        if (openAiApiKeys != null && !openAiApiKeys.isEmpty()) {
            // Base URLs are optional (defaults to https://api.openai.com/v1)
            // But if provided, must match key count
            if (openAiBaseUrls != null && !openAiBaseUrls.isEmpty()) {
                if (openAiApiKeys.size() != openAiBaseUrls.size()) {
                    throw new IllegalArgumentException(
                        String.format("OpenAI API keys (%d) and base URLs (%d) must have the same size",
                            openAiApiKeys.size(), openAiBaseUrls.size()));
                }
            }
        }

        // Validate Azure configuration if provided
        if (azureApiKeys != null && !azureApiKeys.isEmpty()) {
            if (azureBaseUrls == null || azureBaseUrls.isEmpty()) {
                throw new IllegalArgumentException("Azure base URLs are required when Azure API keys are provided");
            }
            if (azureApiKeys.size() != azureBaseUrls.size()) {
                throw new IllegalArgumentException(
                    String.format("Azure API keys (%d) and base URLs (%d) must have the same size",
                        azureApiKeys.size(), azureBaseUrls.size()));
            }
            if (azureApiVersion == null || azureApiVersion.isEmpty()) {
                throw new IllegalArgumentException("Azure API version is required when Azure is configured");
            }
        }

        // Validate that at least one provider is configured
        boolean hasOpenAI = openAiApiKeys != null && !openAiApiKeys.isEmpty();
        boolean hasAzure = azureApiKeys != null && !azureApiKeys.isEmpty();

        if (!hasOpenAI && !hasAzure) {
            throw new IllegalArgumentException("At least one provider must be configured (OpenAI or Azure)");
        }

        if (requestsPerSecond <= 0) {
            throw new IllegalArgumentException("requestsPerSecond must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        if (defaultResponseTimeout <= 0) {
            throw new IllegalArgumentException("defaultResponseTimeout must be positive");
        }
    }

    // ==================== HELPER METHODS ====================

    /**
     * Check if Azure is configured.
     * @return true if Azure API keys are provided
     */
    public boolean isUseAzure() {
        return azureApiKeys != null && !azureApiKeys.isEmpty();
    }

    // ==================== FACTORY METHODS FOR CLEANER API ====================

    /**
     * Creates a configuration for standard OpenAI API with a single instance.
     *
     * @param apiKey OpenAI API key
     * @return Builder pre-configured for OpenAI
     */
    public static AgentServiceConfigBuilder forOpenAI(String apiKey) {
        return AgentServiceConfig.builder()
                .openAiApiKeys(List.of(apiKey))
                .openAiBaseUrls(List.of("https://api.openai.com/v1"));
    }

    /**
     * Creates a configuration for OpenAI with multiple instances (load balancing).
     *
     * @param apiKeys List of OpenAI API keys (one per instance)
     * @return Builder pre-configured for multi-instance OpenAI
     */
    public static AgentServiceConfigBuilder forOpenAIMultiInstance(List<String> apiKeys) {
        if (apiKeys == null || apiKeys.isEmpty()) {
            throw new IllegalArgumentException("API keys list cannot be null or empty");
        }
        // Default all instances to standard OpenAI URL
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < apiKeys.size(); i++) {
            urls.add("https://api.openai.com/v1");
        }
        return AgentServiceConfig.builder()
                .openAiApiKeys(apiKeys)
                .openAiBaseUrls(urls);
    }

    /**
     * Creates a configuration for OpenAI-compatible endpoints with custom URLs.
     *
     * @param apiKeys List of API keys
     * @param baseUrls List of base URLs (for local LLMs, proxies, etc.)
     * @return Builder pre-configured for custom OpenAI-compatible endpoints
     */
    public static AgentServiceConfigBuilder forOpenAICompatible(
            List<String> apiKeys,
            List<String> baseUrls) {
        if (apiKeys == null || baseUrls == null || apiKeys.size() != baseUrls.size()) {
            throw new IllegalArgumentException("API keys and base URLs must have the same size");
        }
        return AgentServiceConfig.builder()
                .openAiApiKeys(apiKeys)
                .openAiBaseUrls(baseUrls);
    }

    /**
     * Creates a configuration for Azure OpenAI with a single instance.
     *
     * @param apiKey Azure OpenAI API key
     * @param baseUrl Azure OpenAI base URL (e.g., "https://my-resource.openai.azure.com/")
     * @param apiVersion Azure API version (e.g., "2024-08-01-preview")
     * @return Builder pre-configured for single Azure instance
     */
    public static AgentServiceConfigBuilder forAzure(String apiKey, String baseUrl, String apiVersion) {
        return AgentServiceConfig.builder()
                .azureApiKeys(List.of(apiKey))
                .azureBaseUrls(List.of(baseUrl))
                .azureApiVersion(apiVersion);
    }

    /**
     * Creates a configuration for Azure OpenAI with multiple instances (load balancing).
     *
     * @param apiKeys List of Azure OpenAI API keys (one per instance)
     * @param baseUrls List of Azure OpenAI base URLs (one per instance)
     * @param apiVersion Azure API version (e.g., "2024-08-01-preview")
     * @return Builder pre-configured for multi-instance Azure
     */
    public static AgentServiceConfigBuilder forAzureMultiInstance(
            List<String> apiKeys,
            List<String> baseUrls,
            String apiVersion) {
        if (apiKeys == null || baseUrls == null || apiKeys.size() != baseUrls.size()) {
            throw new IllegalArgumentException("API keys and base URLs must have the same size");
        }
        return AgentServiceConfig.builder()
                .azureApiKeys(apiKeys)
                .azureBaseUrls(baseUrls)
                .azureApiVersion(apiVersion);
    }

}
