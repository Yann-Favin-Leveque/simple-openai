package io.github.sashirestela.openai.agent;

import lombok.Builder;
import lombok.Getter;

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

    /**
     * AI Provider type for agent execution.
     */
    public enum Provider {
        /** Standard OpenAI API */
        OPENAI,
        /** Azure OpenAI Service */
        AZURE
        // TODO: Future providers (use Chat Completion fallback):
        // CLAUDE, GROK, GEMINI
    }

    // === Provider Configuration ===

    /**
     * AI provider to use.
     * Default: OPENAI
     */
    @Builder.Default
    private final Provider provider = Provider.OPENAI;

    // === OpenAI Configuration ===

    /**
     * OpenAI API key for standard OpenAI usage.
     */
    private final String openAiApiKey;

    /**
     * Base URL for OpenAI API.
     * Default: "https://api.openai.com/v1"
     */
    @Builder.Default
    private final String openAiBaseUrl = "https://api.openai.com/v1";

    // === Azure Configuration ===

    /**
     * Whether to use Azure OpenAI instead of standard OpenAI.
     */
    @Builder.Default
    private final boolean useAzure = false;

    /**
     * Number of Azure instances for load balancing.
     * Must match the size of azureApiKeys and azureBaseUrls.
     */
    @Builder.Default
    private final int azureInstanceCount = 1;

    /**
     * Azure OpenAI API keys (one per instance).
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
        if (provider == Provider.AZURE) {
            if (azureApiKeys == null || azureBaseUrls == null) {
                throw new IllegalArgumentException("Azure API keys and base URLs are required when provider=AZURE");
            }
            if (azureApiKeys.size() != azureInstanceCount || azureBaseUrls.size() != azureInstanceCount) {
                throw new IllegalArgumentException(
                    String.format("Azure instance count (%d) must match API keys (%d) and base URLs (%d)",
                        azureInstanceCount, azureApiKeys.size(), azureBaseUrls.size()));
            }
            if (azureApiVersion == null || azureApiVersion.isEmpty()) {
                throw new IllegalArgumentException("Azure API version is required when provider=AZURE");
            }
        } else if (provider == Provider.OPENAI) {
            if (openAiApiKey == null || openAiApiKey.isEmpty()) {
                throw new IllegalArgumentException("OpenAI API key is required when provider=OPENAI");
            }
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
     * Check if Azure provider is being used.
     * @return true if provider is AZURE
     */
    public boolean isUseAzure() {
        return provider == Provider.AZURE;
    }

    // ==================== FACTORY METHODS FOR CLEANER API ====================

    /**
     * Creates a configuration for standard OpenAI API.
     *
     * @param apiKey OpenAI API key
     * @return Builder pre-configured for OpenAI
     */
    public static AgentServiceConfigBuilder forOpenAI(String apiKey) {
        return AgentServiceConfig.builder()
                .provider(Provider.OPENAI)
                .openAiApiKey(apiKey)
                .openAiBaseUrl("https://api.openai.com/v1");
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
                .provider(Provider.AZURE)
                .azureInstanceCount(1)
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
                .provider(Provider.AZURE)
                .azureInstanceCount(apiKeys.size())
                .azureApiKeys(apiKeys)
                .azureBaseUrls(baseUrls)
                .azureApiVersion(apiVersion);
    }

}
