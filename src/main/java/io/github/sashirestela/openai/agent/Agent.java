package io.github.sashirestela.openai.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents an OpenAI Assistant Agent with configuration and runtime state.
 * This class serves as a runtime wrapper around OpenAI Assistants API,
 * providing a simplified interface for agent management.
 *
 * <p>Agents can be configured to use either standard OpenAI API or Azure OpenAI,
 * with support for multi-instance Azure deployments for load balancing.</p>
 *
 * @see AgentService
 * @see AgentDefinition
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent {

    /**
     * Unique identifier for this agent (application-level ID).
     */
    private String id;

    /**
     * Human-readable name for the agent.
     */
    private String name;

    /**
     * OpenAI Assistant ID (for standard OpenAI API).
     */
    private String openAiId;

    /**
     * OpenAI Assistant IDs for Azure multi-instance deployments.
     * Each element corresponds to an Azure instance for load balancing.
     */
    private List<String> openAiAzureIds;

    /**
     * Model name (e.g., "gpt-4o", "gpt-4o-mini").
     */
    private String model;

    /**
     * System instructions for the agent.
     */
    private String instructions;

    /**
     * Fully qualified class name for structured output mapping.
     * If null, returns raw string response.
     * The class should implement {@link AgentResult} interface.
     */
    private String resultClass;

    /**
     * Temperature for response generation (0.0 to 2.0).
     * Lower values make output more focused and deterministic.
     */
    private Double temperature;

    /**
     * Thread management type (e.g., "SINGLE", "MULTI").
     */
    private String threadType;

    /**
     * Current agent status (e.g., "OK", "ERROR").
     */
    private String status;

    /**
     * Current thread ID if using persistent thread.
     */
    private String threadId;

    /**
     * Response timeout in milliseconds.
     * Default: 120000ms (2 minutes).
     */
    @Builder.Default
    private Long responseTimeout = 120000L;

    /**
     * Whether to enable file search/retrieval capabilities (RAG).
     */
    @Builder.Default
    private Boolean retrieval = false;

    /**
     * Agent type for categorization (e.g., "interrogation", "generation").
     */
    private String agentType;

    /**
     * Whether to create this agent on application startup.
     */
    @Builder.Default
    private Boolean createOnAppStart = false;

}
