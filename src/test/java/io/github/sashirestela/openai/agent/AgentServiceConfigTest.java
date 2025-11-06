package io.github.sashirestela.openai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentServiceConfig} configuration builder.
 * These tests verify the configuration validation and factory methods.
 */
class AgentServiceConfigTest {

    @Test
    void testOpenAIFactoryMethod() {
        var config = AgentServiceConfig.forOpenAI("sk-test-key")
                .agentResultClassPackage("com.example")
                .build();

        assertFalse(config.isUseAzure());
        assertEquals(List.of("sk-test-key"), config.getOpenAiApiKeys());
        assertEquals(List.of("https://api.openai.com/v1"), config.getOpenAiBaseUrls());
        assertEquals("com.example", config.getAgentResultClassPackage());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testAzureSingleInstanceFactoryMethod() {
        var config = AgentServiceConfig.forAzure(
                "azure-key",
                "https://test.openai.azure.com/",
                "2024-08-01-preview"
        ).build();

        assertTrue(config.isUseAzure());
        assertEquals(List.of("azure-key"), config.getAzureApiKeys());
        assertEquals(List.of("https://test.openai.azure.com/"), config.getAzureBaseUrls());
        assertEquals("2024-08-01-preview", config.getAzureApiVersion());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testAzureMultiInstanceFactoryMethod() {
        var apiKeys = List.of("key1", "key2", "key3");
        var baseUrls = List.of("url1", "url2", "url3");

        var config = AgentServiceConfig.forAzureMultiInstance(
                apiKeys,
                baseUrls,
                "2024-08-01-preview"
        ).build();

        assertTrue(config.isUseAzure());
        assertEquals(3, config.getAzureApiKeys().size());
        assertEquals(apiKeys, config.getAzureApiKeys());
        assertEquals(baseUrls, config.getAzureBaseUrls());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testAzureMultiInstanceFactoryMethodMismatchThrows() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            AgentServiceConfig.forAzureMultiInstance(
                    List.of("key1", "key2"),
                    List.of("url1"),  // Mismatched size!
                    "2024-08-01-preview"
            );
        });
        assertTrue(exception.getMessage().contains("same size"));
    }

    @Test
    void testValidationFailsWhenNoProviderConfigured() {
        var config = AgentServiceConfig.builder()
                // Missing both OpenAI and Azure config!
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("At least one provider must be configured"));
    }

    @Test
    void testValidationFailsWhenAzureUrlsMissing() {
        var config = AgentServiceConfig.builder()
                .azureApiKeys(List.of("key1", "key2"))
                .azureApiVersion("2024-08-01-preview")
                // Missing azureBaseUrls!
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("Azure base URLs are required"));
    }

    @Test
    void testValidationFailsWhenAzureSizeMismatch() {
        var config = AgentServiceConfig.builder()
                .azureApiKeys(List.of("key1", "key2", "key3"))  // 3 keys
                .azureBaseUrls(List.of("url1", "url2"))  // Only 2 URLs - mismatch!
                .azureApiVersion("2024-08-01-preview")
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("must have the same size"));
    }

    @Test
    void testValidationFailsWhenAzureApiVersionMissing() {
        var config = AgentServiceConfig.builder()
                .azureApiKeys(List.of("key1"))
                .azureBaseUrls(List.of("url1"))
                // Missing azureApiVersion!
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("Azure API version is required"));
    }

    @Test
    void testValidationFailsWhenRequestsPerSecondNegative() {
        var config = AgentServiceConfig.forOpenAI("sk-test-key")
                .requestsPerSecond(-1)
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("requestsPerSecond must be positive"));
    }

    @Test
    void testValidationFailsWhenMaxRetriesNegative() {
        var config = AgentServiceConfig.forOpenAI("sk-test-key")
                .maxRetries(-1)
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("maxRetries cannot be negative"));
    }

    @Test
    void testValidationFailsWhenTimeoutNegative() {
        var config = AgentServiceConfig.forOpenAI("sk-test-key")
                .defaultResponseTimeout(-1000L)
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("defaultResponseTimeout must be positive"));
    }

    @Test
    void testDefaultValues() {
        var config = AgentServiceConfig.forOpenAI("sk-test-key").build();

        assertEquals(5, config.getRequestsPerSecond());
        assertEquals(3, config.getMaxRetries());
        assertEquals(120000L, config.getDefaultResponseTimeout());
        assertEquals(10000L, config.getRetryBaseDelayMs());
        assertEquals(60000L, config.getRateLimitDelayMs());
        assertEquals(300000L, config.getError502DelayMs());
        assertFalse(config.isEnableImagePromptSanitization());
        assertEquals("298", config.getImageSanitizerAgentId());
        assertEquals("2024-02-01", config.getAzureDalleApiVersion());
    }

    @Test
    void testCustomValues() {
        var config = AgentServiceConfig.forOpenAI("sk-test-key")
                .requestsPerSecond(10)
                .maxRetries(5)
                .defaultResponseTimeout(60000L)
                .retryBaseDelayMs(5000L)
                .rateLimitDelayMs(30000L)
                .error502DelayMs(120000L)
                .enableImagePromptSanitization(true)
                .imageSanitizerAgentId("custom-agent-id")
                .agentResultClassPackage("com.example.results")
                .agentJsonFolderPath("/config/agents")
                .build();

        assertEquals(10, config.getRequestsPerSecond());
        assertEquals(5, config.getMaxRetries());
        assertEquals(60000L, config.getDefaultResponseTimeout());
        assertEquals(5000L, config.getRetryBaseDelayMs());
        assertEquals(30000L, config.getRateLimitDelayMs());
        assertEquals(120000L, config.getError502DelayMs());
        assertTrue(config.isEnableImagePromptSanitization());
        assertEquals("custom-agent-id", config.getImageSanitizerAgentId());
        assertEquals("com.example.results", config.getAgentResultClassPackage());
        assertEquals("/config/agents", config.getAgentJsonFolderPath());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testAzureDalleConfiguration() {
        var config = AgentServiceConfig.forAzure(
                "azure-key",
                "https://test.openai.azure.com/",
                "2024-08-01-preview"
        )
                .azureDalleApiKeys(List.of("dalle-key1", "dalle-key2"))
                .azureDalleBaseUrls(List.of("dalle-url1", "dalle-url2"))
                .azureDalleApiVersion("2024-03-01")
                .build();

        assertEquals(List.of("dalle-key1", "dalle-key2"), config.getAzureDalleApiKeys());
        assertEquals(List.of("dalle-url1", "dalle-url2"), config.getAzureDalleBaseUrls());
        assertEquals("2024-03-01", config.getAzureDalleApiVersion());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testBuilderPattern() {
        // Test that we can chain builder methods
        var config = AgentServiceConfig.builder()
                .openAiApiKeys(List.of("sk-test-key"))
                .requestsPerSecond(10)
                .maxRetries(5)
                .defaultResponseTimeout(60000L)
                .agentResultClassPackage("com.example")
                .build();

        assertNotNull(config);
        assertEquals(List.of("sk-test-key"), config.getOpenAiApiKeys());
        assertFalse(config.isUseAzure());
        assertEquals(10, config.getRequestsPerSecond());
        assertEquals("com.example", config.getAgentResultClassPackage());
    }

}
