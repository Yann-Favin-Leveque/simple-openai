package io.github.sashirestela.openai.demo;

import io.github.sashirestela.openai.agent.Agent;
import io.github.sashirestela.openai.agent.AgentDefinition;
import io.github.sashirestela.openai.agent.AgentResult;
import io.github.sashirestela.openai.agent.AgentService;
import io.github.sashirestela.openai.agent.AgentServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive demo for AgentService showing various usage patterns.
 *
 * This demo showcases:
 * 1. Basic agent creation and configuration
 * 2. Azure multi-instance deployment with load balancing
 * 3. Agent loading from JSON definitions
 * 4. Startup agent loading pattern
 * 5. Structured outputs with JSON Schema
 * 6. Provider enum patterns (NEW!)
 * 7. Vector store with instance encoding (NEW!)
 * 8. Persistent thread API (NEW!)
 * 9. Vector store integration for RAG
 * 10. Chat completions (non-Assistant API)
 * 11. Image generation with DALL-E
 * 12. Batch processing
 *
 * NEW FEATURES (v3.18.0):
 * - Unified Provider enum (OpenAI/Azure) - replaces useAzure boolean
 * - Vector store instance encoding for Azure multi-instance affinity
 * - Persistent thread API for multi-turn conversations
 *
 * Prerequisites:
 * - Set environment variable OPENAI_API_KEY for standard OpenAI
 * - OR set AZURE_OPENAI_API_KEY, AZURE_OPENAI_BASE_URL, AZURE_OPENAI_API_VERSION for Azure
 */
public class AgentServiceDemo extends AbstractDemo {

    private static final Logger logger = LoggerFactory.getLogger(AgentServiceDemo.class);

    /**
     * Example result class for structured outputs.
     */
    public static class WeatherResult implements AgentResult {
        public String location;
        public double temperature;
        public String conditions;
        public String recommendation;

        @Override
        public String toString() {
            return String.format("Weather in %s: %.1f�C, %s. %s",
                location, temperature, conditions, recommendation);
        }
    }

    /**
     * Example result class for code analysis.
     */
    public static class CodeAnalysisResult implements AgentResult {
        public String language;
        public int complexity;
        public List<String> suggestions;
        public String summary;

        @Override
        public String toString() {
            return String.format("Language: %s, Complexity: %d\nSuggestions: %s\nSummary: %s",
                language, complexity, suggestions, summary);
        }
    }

    public static void main(String[] args) {
        AgentServiceDemo demo = new AgentServiceDemo();

        System.out.println("=== AgentService Demo ===\n");

        try {
            // Demo 1: Basic OpenAI configuration (no API calls - safe to run)
            demo.demoBasicOpenAI();

            // Demo 2: Azure multi-instance configuration (no API calls - safe to run)
            demo.demoAzureMultiInstance();

            // Demo 3: Agent from JSON definition (no API calls - safe to run)
            demo.demoAgentFromJson();

            // Demo 4: Startup agent loading pattern (no API calls - safe to run)
            demo.demoStartupAgentLoading();

            // Demo 5: Structured output (no API calls - safe to run)
            demo.demoStructuredOutput();

            // Demo 6: Provider enum patterns (no API calls - safe to run)
            demo.demoProviderEnum();

            // Demo 7: Vector store with instance encoding (no API calls - safe to run)
            demo.demoVectorStoreWithInstanceEncoding();

            // Demo 8: Persistent thread API (no API calls - safe to run)
            demo.demoPersistentThreadAPI();

            // Uncomment these if you have API keys configured:
            // demo.demoVectorStoreRAG();
            // demo.demoChatCompletions();
            // demo.demoImageGeneration();
            // demo.demoBatchProcessing();

        } catch (Exception e) {
            logger.error("Demo failed", e);
            e.printStackTrace();
        }
    }

    /**
     * Demo 1: Basic OpenAI configuration and agent creation.
     */
    public void demoBasicOpenAI() throws IOException {
        System.out.println("--- Demo 1: Basic OpenAI Configuration ---\n");

        // NEW CLEAN API: Use forOpenAI() factory method
        AgentServiceConfig config = AgentServiceConfig
            .forOpenAI(System.getenv("OPENAI_API_KEY"))
            .agentResultClassPackage("io.github.sashirestela.openai.demo.AgentServiceDemo")
            .requestsPerSecond(5)
            .maxRetries(3)
            .build();

        // Validate configuration
        config.validate();

        System.out.println(" Configuration created and validated");
        System.out.println("  - API: OpenAI");
        System.out.println("  - Rate limit: 5 req/s");
        System.out.println("  - Max retries: 3");
        System.out.println("  - Timeout: 120s\n");

        // Create AgentService (note: this would need proper initialization)
        // AgentService service = new AgentService(config);

        // Create an agent programmatically
        Agent agent = Agent.builder()
            .id("demo-assistant")
            .name("Demo Assistant")
            .model("gpt-4o-mini")
            .instructions("You are a helpful assistant that provides concise answers.")
            .temperature(0.7)
            .responseTimeout(120000L)
            .retrieval(false)
            .build();

        System.out.println(" Created agent: " + agent.getName());
        System.out.println("  - Model: " + agent.getModel());
        System.out.println("  - Temperature: " + agent.getTemperature());
        System.out.println("  - Timeout: " + agent.getResponseTimeout() + "ms\n");

        // In a real scenario, you would:
        // 1. service.createAgent(agent.getId())
        // 2. service.requestAgent(agent.getId(), "Hello!", null, new HashMap<>())

        System.out.println("=� Note: This is a configuration demo. To actually create the agent,");
        System.out.println("   you need to initialize AgentService and call createAgent().\n");
    }

    /**
     * Demo 2: Azure multi-instance configuration with load balancing.
     */
    public void demoAzureMultiInstance() throws IOException {
        System.out.println("--- Demo 2: Azure Multi-Instance Configuration ---\n");

        System.out.println("=== OPTION 1: Single Azure Instance ===\n");
        System.out.println("AgentServiceConfig config = AgentServiceConfig");
        System.out.println("    .forAzure(");
        System.out.println("        \"your-api-key\",");
        System.out.println("        \"https://my-resource.openai.azure.com/\",");
        System.out.println("        \"2024-08-01-preview\"");
        System.out.println("    )");
        System.out.println("    .requestsPerSecond(5)");
        System.out.println("    .build();\n");

        System.out.println("=== OPTION 2: Multi-Instance (Load Balanced) ===\n");

        // NEW CLEAN API: Use forAzureMultiInstance() factory method
        AgentServiceConfig config = AgentServiceConfig
            .forAzureMultiInstance(
                List.of(
                    System.getenv("AZURE_OPENAI_API_KEY_1"),
                    System.getenv("AZURE_OPENAI_API_KEY_2")
                ),
                List.of(
                    System.getenv("AZURE_OPENAI_BASE_URL_1"),
                    System.getenv("AZURE_OPENAI_BASE_URL_2")
                ),
                "2024-08-01-preview"
            )
            .agentResultClassPackage("io.github.sashirestela.openai.demo.AgentServiceDemo")
            .requestsPerSecond(10)  // Higher with 2 instances
            .build();

        config.validate();

        System.out.println(" Azure configuration created");
        System.out.println("  - Instances: 2 (load balanced)");
        System.out.println("  - API Version: " + config.getAzureApiVersion());
        System.out.println("  - Combined rate limit: 10 req/s\n");

        // Create AgentService
        // AgentService service = new AgentService(config);

        // When you call service.requestAgent(), it automatically:
        // - Round-robin selects an Azure instance
        // - Uses the appropriate assistant ID for that instance
        // - Balances load across all instances

        System.out.println("=� Load balancing: Requests are distributed round-robin across instances.\n");
    }

    /**
     * Demo 3: Loading agent from JSON definition file.
     */
    public void demoAgentFromJson() throws IOException {
        System.out.println("--- Demo 3: Agent from JSON Definition ---\n");

        // Example JSON structure
        String exampleJson = "{\n" +
            "  \"id\": \"weather-assistant\",\n" +
            "  \"name\": \"Weather Assistant\",\n" +
            "  \"model\": \"gpt-4o\",\n" +
            "  \"instructions\": \"You are a weather assistant. Provide weather information in a structured format.\",\n" +
            "  \"resultClass\": \"WeatherResult\",\n" +
            "  \"temperature\": 0.7,\n" +
            "  \"retrieval\": false,\n" +
            "  \"responseTimeout\": 120000,\n" +
            "  \"openAiId\": \"asst_abc123xyz\"\n" +
            "}";

        System.out.println("Example agent JSON definition:");
        System.out.println(exampleJson);

        // In a real scenario, you would:
        // 1. Save this JSON to a file in your agents folder
        // 2. Configure AgentService with agentJsonFolderPath
        // 3. AgentService automatically loads all agents on initialization

        System.out.println("\n=� To use JSON definitions:");
        System.out.println("   1. Create JSON files in a folder (e.g., /config/agents/)");
        System.out.println("   2. Set agentJsonFolderPath in AgentServiceConfig");
        System.out.println("   3. Agents are loaded automatically on service initialization\n");
    }

    /**
     * Demo 4: Startup agent loading pattern (like ApplicationStartup).
     */
    public void demoStartupAgentLoading() throws IOException {
        System.out.println("--- Demo 4: Startup Agent Loading Pattern ---\n");

        System.out.println("This shows how to load and initialize agents at application startup.");
        System.out.println("Similar to Spring's ApplicationReadyEvent pattern:\n");

        System.out.println("Example startup code:");
        System.out.println("// Step 1: Configure AgentService with JSON folder\n" +
            "AgentServiceConfig config = AgentServiceConfig.builder()\n" +
            "    .openAiApiKey(System.getenv(\"OPENAI_API_KEY\"))\n" +
            "    .agentJsonFolderPath(\"/config/agents\")  // Auto-loads on construction\n" +
            "    .agentResultClassPackage(\"com.example.results\")\n" +
            "    .build();\n\n" +
            "// Step 2: Create AgentService - agents are loaded automatically!\n" +
            "AgentService service = new AgentService(config);\n" +
            "// All agent_*.json files are now loaded and ready to use\n\n" +
            "// Step 3: Optionally create/update agents on OpenAI (force refresh)\n" +
            "boolean forceUpdateAgents = Boolean.parseBoolean(\n" +
            "    System.getProperty(\"init.gen.agents\", \"false\"));\n\n" +
            "if (forceUpdateAgents) {\n" +
            "    // Create or update all loaded agents on OpenAI/Azure\n" +
            "    for (String agentId : service.getLoadedAgentIds()) {\n" +
            "        service.createAgent(agentId).join();\n" +
            "        logger.info(\"Agent {} created/updated\", agentId);\n" +
            "    }\n" +
            "}\n\n" +
            "// Step 4: Agents are ready to use!\n" +
            "String response = service.requestAgent(\n" +
            "    \"my-agent\",\n" +
            "    \"Hello!\",\n" +
            "    null,\n" +
            "    new HashMap<>()\n" +
            ").join();");

        System.out.println("\n\n📝 Best practices for production:");
        System.out.println("   1. Set agentJsonFolderPath to auto-load agents on startup");
        System.out.println("   2. Use environment variable/flag to control force updates");
        System.out.println("   3. Only force-update when agent definitions change");
        System.out.println("   4. Missing OpenAI IDs are created automatically on first use");
        System.out.println("\n   For Spring Boot:");
        System.out.println("   - Use @Value(\"${app.init-agents:false}\") for the flag");
        System.out.println("   - Initialize in ApplicationReadyEvent listener");
        System.out.println("   - Log each step for monitoring\n");
    }

    /**
     * Demo 5: Structured output with JSON Schema.
     */
    public void demoStructuredOutput() throws IOException {
        System.out.println("--- Demo 4: Structured Output with JSON Schema ---\n");

        // Create agent with result class
        Agent weatherAgent = Agent.builder()
            .id("weather-structured")
            .name("Weather Agent (Structured)")
            .model("gpt-4o")
            .instructions("Provide weather information for the requested location.")
            .resultClass("WeatherResult")  // Maps to WeatherResult class
            .temperature(0.5)
            .responseTimeout(120000L)
            .build();

        System.out.println(" Created agent with structured output");
        System.out.println("  - Result class: " + weatherAgent.getResultClass());
        System.out.println("  - JSON Schema auto-generated from class fields\n");

        System.out.println("WeatherResult class structure:");
        System.out.println("  - location: string");
        System.out.println("  - temperature: double");
        System.out.println("  - conditions: string");
        System.out.println("  - recommendation: string\n");

        // In a real scenario:
        // String jsonResponse = service.requestAgent("weather-structured",
        //     "What's the weather in Paris?", null, new HashMap<>()).join();
        // WeatherResult result = service.mapResponse(jsonResponse, "WeatherResult");
        // System.out.println(result);

        System.out.println("=� Benefits of structured outputs:");
        System.out.println("    Type-safe responses");
        System.out.println("    Guaranteed JSON schema compliance");
        System.out.println("    Easy parsing and validation");
        System.out.println("    No manual JSON parsing needed\n");
    }

    /**
     * Demo 6: Provider enum patterns (OpenAI vs Azure).
     */
    public void demoProviderEnum() throws IOException {
        System.out.println("--- Demo 6: Provider Enum Patterns ---\n");

        System.out.println("NEW: Unified Provider enum for OpenAI and Azure\n");

        System.out.println("=== OpenAI Provider ===\n");
        System.out.println("AgentServiceConfig config = AgentServiceConfig");
        System.out.println("    .forOpenAI(System.getenv(\"OPENAI_API_KEY\"))");
        System.out.println("    .requestsPerSecond(5)");
        System.out.println("    .build();\n");

        System.out.println("=== Azure Single Instance ===\n");
        System.out.println("AgentServiceConfig config = AgentServiceConfig");
        System.out.println("    .forAzure(");
        System.out.println("        apiKey,");
        System.out.println("        \"https://my-resource.openai.azure.com/\",");
        System.out.println("        \"2024-08-01-preview\"");
        System.out.println("    )");
        System.out.println("    .build();\n");

        System.out.println("=== Azure Multi-Instance (Load Balanced) ===\n");
        System.out.println("AgentServiceConfig config = AgentServiceConfig");
        System.out.println("    .forAzureMultiInstance(");
        System.out.println("        List.of(apiKey1, apiKey2, apiKey3),");
        System.out.println("        List.of(baseUrl1, baseUrl2, baseUrl3),");
        System.out.println("        \"2024-08-01-preview\"");
        System.out.println("    )");
        System.out.println("    .requestsPerSecond(15)  // 3 instances * 5 req/s each");
        System.out.println("    .build();\n");

        System.out.println("Key changes:");
        System.out.println("  - Replaced 'useAzure' boolean with Provider enum");
        System.out.println("  - Clean factory methods: forOpenAI(), forAzure(), forAzureMultiInstance()");
        System.out.println("  - Extensible for future providers (Claude, Grok, Gemini)\n");

        System.out.println("Future providers (commented for now):");
        System.out.println("  - Will use Chat Completion API fallback");
        System.out.println("  - No Assistant API (direct message with prepended instructions)");
        System.out.println("  - Example: Provider.CLAUDE, Provider.GROK\n");
    }

    /**
     * Demo 7: Vector store with instance encoding for Azure multi-instance.
     */
    public void demoVectorStoreWithInstanceEncoding() throws IOException {
        System.out.println("--- Demo 7: Vector Store Instance Encoding ---\n");

        System.out.println("NEW: Automatic instance encoding for Azure multi-instance\n");

        System.out.println("Example workflow:");
        System.out.println("// 1. Create vector store (Azure with 3 instances)\n" +
            "String vectorStoreRef = service.createVectorStore(\n" +
            "    \"Research Documents\",\n" +
            "    List.of(fileId1, fileId2, fileId3)\n" +
            ").join();\n\n" +
            "// Returned reference (Azure multi-instance):\n" +
            "// \"2_vs_abc123xyz\"  <- Instance 2, vector store ID vs_abc123xyz\n\n" +
            "// Returned reference (OpenAI or Azure single instance):\n" +
            "// \"vs_abc123xyz\"    <- Plain vector store ID\n\n");

        System.out.println("// 2. Use vector store in requests\n" +
            "String response = service.requestAgentWithVectorStorage(\n" +
            "    \"research-agent\",\n" +
            "    \"What are the key findings?\",\n" +
            "    vectorStoreRef  // Just pass the reference\n" +
            ").join();\n" +
            "// AgentService automatically:\n" +
            "// - Decodes the reference (\"2_vs_abc123xyz\" -> instance 2, ID vs_abc123xyz)\n" +
            "// - Routes request to correct Azure instance\n" +
            "// - Vector store access guaranteed\n\n");

        System.out.println("// 3. Delete vector store\n" +
            "service.deleteVectorStore(vectorStoreRef).join();\n" +
            "// Also decodes and routes to correct instance\n");

        System.out.println("\n=== Why Instance Encoding? ===\n");
        System.out.println("Azure Problem:");
        System.out.println("  - Vector stores are instance-specific");
        System.out.println("  - If created on instance 2, must access from instance 2");
        System.out.println("  - Round-robin could route to wrong instance\n");
        System.out.println("Solution:");
        System.out.println("  - Encode instance index in reference: \"instanceIndex_vectorStoreId\"");
        System.out.println("  - Self-describing references (no separate tracking needed)");
        System.out.println("  - Transparent to application code\n");
        System.out.println("Benefits:");
        System.out.println("  - Instance affinity maintained automatically");
        System.out.println("  - No external tracking required");
        System.out.println("  - Works seamlessly with OpenAI (no encoding needed)\n");
    }

    /**
     * Demo 8: Persistent thread API for multi-turn conversations.
     */
    public void demoPersistentThreadAPI() throws IOException {
        System.out.println("--- Demo 8: Persistent Thread API ---\n");

        System.out.println("NEW: Thread management for multi-turn conversations\n");

        System.out.println("=== One-Shot vs Persistent Threads ===\n");

        System.out.println("OLD: One-Shot Request (no conversation history)");
        System.out.println("String response = service.requestAgent(\n" +
            "    \"101\",\n" +
            "    \"What's the capital of France?\",\n" +
            "    null,\n" +
            "    new HashMap<>()\n" +
            ").join();\n" +
            "// Internal: createThread -> sendMessage -> deleteThread\n" +
            "// Next request has no memory of this conversation\n\n");

        System.out.println("NEW: Persistent Thread (conversation history maintained)");
        System.out.println("// 1. Create thread once\n" +
            "String threadRef = service.createThread().join();\n" +
            "// Returns: \"thread_abc123\" or \"1_thread_abc123\" (Azure multi-instance)\n\n" +
            "// 2. First message\n" +
            "String response1 = service.sendMessageToThread(\n" +
            "    \"101\",           // agent ID\n" +
            "    threadRef,       // thread reference\n" +
            "    \"What's the capital of France?\"\n" +
            ").join();\n" +
            "// Response: \"The capital of France is Paris.\"\n\n" +
            "// 3. Follow-up message (context preserved!)\n" +
            "String response2 = service.sendMessageToThread(\n" +
            "    \"101\",\n" +
            "    threadRef,       // Same thread\n" +
            "    \"What's its population?\"\n" +
            ").join();\n" +
            "// Response: \"Paris has approximately 2.2 million people.\"\n" +
            "// Agent remembers we're talking about Paris!\n\n" +
            "// 4. More follow-ups...\n" +
            "String response3 = service.sendMessageToThread(\n" +
            "    \"101\",\n" +
            "    threadRef,\n" +
            "    \"What's the main tourist attraction?\"\n" +
            ").join();\n" +
            "// Response: \"The Eiffel Tower is Paris's most famous landmark.\"\n\n" +
            "// 5. Delete thread when conversation ends\n" +
            "service.deleteThread(threadRef).join();\n");

        System.out.println("\n=== Azure Multi-Instance Thread Encoding ===\n");
        System.out.println("Same pattern as vector stores:");
        System.out.println("  - createThread() returns: \"instanceIndex_threadId\"");
        System.out.println("  - Example: \"1_thread_xyz789\" (instance 1, thread thread_xyz789)");
        System.out.println("  - sendMessageToThread() decodes and routes to correct instance");
        System.out.println("  - deleteThread() also decodes automatically\n");

        System.out.println("=== Use Cases ===\n");
        System.out.println("Customer Support:");
        System.out.println("  - Create thread when chat starts");
        System.out.println("  - Send each message to same thread");
        System.out.println("  - Delete thread when customer closes chat\n");
        System.out.println("Tutoring/Coaching:");
        System.out.println("  - Thread per student session");
        System.out.println("  - Agent remembers previous questions/answers");
        System.out.println("  - Build on prior learning\n");
        System.out.println("Code Review:");
        System.out.println("  - Thread per PR/file");
        System.out.println("  - Iterative feedback loop");
        System.out.println("  - Context-aware suggestions\n");

        System.out.println("API Summary:");
        System.out.println("  createThread() -> String (thread reference)");
        System.out.println("  sendMessageToThread(agentId, threadRef, message) -> String (response)");
        System.out.println("  deleteThread(threadRef) -> Boolean (success)\n");
    }

    /**
     * Demo 5: Vector store integration for RAG.
     */
    public void demoVectorStoreRAG() throws IOException {
        System.out.println("--- Demo 5: Vector Store RAG ---\n");

        System.out.println("Steps for RAG with vector stores:");
        System.out.println("  1. Upload PDF/text files to OpenAI");
        System.out.println("  2. Create vector store with file IDs");
        System.out.println("  3. Request agent with vector store ID");
        System.out.println("  4. Agent retrieves relevant context automatically\n");

        // Example workflow:
        System.out.println("Example code:");
        System.out.println("// 1. Upload file\n" +
            "File uploadedFile = service.uploadFile(Paths.get(\"document.pdf\"));\n" +
            "String fileId = uploadedFile.getId();\n\n" +
            "// 2. Create vector store\n" +
            "String vectorStoreId = service.createVectorStore(\n" +
            "    \"My Documents\",\n" +
            "    List.of(fileId)\n" +
            ").join();\n\n" +
            "// 3. Request with vector store\n" +
            "String response = service.requestAgentWithVectorStorage(\n" +
            "    \"research-assistant\",\n" +
            "    \"Summarize the key findings from the document\",\n" +
            "    vectorStoreId\n" +
            ").join();\n\n" +
            "// 4. Clean up\n" +
            "service.deleteVectorStore(vectorStoreId);");

        System.out.println("\n=� Vector stores enable:");
        System.out.println("    Semantic search over documents");
        System.out.println("    Automatic context retrieval");
        System.out.println("    Up to 10,000 files per store");
        System.out.println("    Multiple stores per agent\n");
    }

    /**
     * Demo 7: Chat completions (non-Assistant API).
     */
    public void demoChatCompletions() throws IOException {
        System.out.println("--- Demo 7: Chat Completions ---\n");

        System.out.println("Chat Completions vs Assistants:");
        System.out.println("  Chat Completions:");
        System.out.println("    - Stateless (no threads)");
        System.out.println("    - Lower latency");
        System.out.println("    - Manual context management");
        System.out.println("    - Good for simple Q&A");
        System.out.println("");
        System.out.println("  Assistants:");
        System.out.println("    - Stateful (with threads)");
        System.out.println("    - Built-in context management");
        System.out.println("    - Tool support (code interpreter, file search)");
        System.out.println("    - Good for complex workflows\n");

        System.out.println("Example chat completion request:");
        System.out.println("List<ChatMessage> messages = List.of(\n" +
            "    ChatMessage.SystemMessage.of(\"You are a helpful assistant.\"),\n" +
            "    ChatMessage.UserMessage.of(\"Explain quantum computing in 50 words.\")\n" +
            ");\n\n" +
            "String response = service.requestChatCompletion(\n" +
            "    \"gpt-4o-mini\",\n" +
            "    messages,\n" +
            "    0.7,\n" +
            "    false  // use OpenAI (not Azure)\n" +
            ").join();");

        System.out.println("\n=� Use chat completions when you need:");
        System.out.println("    Fast, stateless responses");
        System.out.println("    Lower cost per request");
        System.out.println("    Simple conversational AI\n");
    }

    /**
     * Demo 8: Image generation with DALL-E.
     */
    public void demoImageGeneration() throws IOException {
        System.out.println("--- Demo 8: Image Generation ---\n");

        System.out.println("DALL-E image generation with AgentService:");
        System.out.println("String imageUrl = service.generateImage(\n" +
            "    \"A serene mountain landscape at sunset\",\n" +
            "    \"dall-e-3\",\n" +
            "    \"1024x1024\",\n" +
            "    \"standard\",\n" +
            "    true  // enable prompt sanitization fallback\n" +
            ").join();\n\n" +
            "System.out.println(\"Image URL: \" + imageUrl);");

        System.out.println("\n=� Features:");
        System.out.println("    Support for DALL-E 2 and DALL-E 3");
        System.out.println("    Multiple sizes (256x256, 512x512, 1024x1024, 1024x1792, 1792x1024)");
        System.out.println("    Quality options (standard, hd)");
        System.out.println("    Automatic retry on content policy violations");
        System.out.println("    Optional prompt sanitization with dedicated agent\n");

        System.out.println("Content policy handling:");
        System.out.println("  If content_policy_violation occurs:");
        System.out.println("    1. AgentService calls sanitizer agent (if enabled)");
        System.out.println("    2. Sanitizer rewrites prompt to be policy-compliant");
        System.out.println("    3. Retries image generation with sanitized prompt\n");
    }

    /**
     * Demo 9: Batch processing.
     */
    public void demoBatchProcessing() throws IOException {
        System.out.println("--- Demo 9: Batch Processing ---\n");

        System.out.println("Batch API for processing multiple requests asynchronously:");
        System.out.println("// 1. Create JSONL file with batch requests\n" +
            "// Format: {\"custom_id\": \"req-1\", \"method\": \"POST\", \"url\": \"/v1/chat/completions\", \"body\": {...}}\n\n" +
            "// 2. Upload file\n" +
            "File uploadedFile = service.uploadFile(Paths.get(\"batch_requests.jsonl\"));\n\n" +
            "// 3. Create batch\n" +
            "Batch batch = service.createChatCompletionBatch(\n" +
            "    uploadedFile.getId(),\n" +
            "    Map.of(\"purpose\", \"research_analysis\")\n" +
            ").join();\n\n" +
            "// 4. Poll for completion (runs in background)\n" +
            "Batch completedBatch = service.pollBatchUntilComplete(\n" +
            "    batch.getId(),\n" +
            "    30,   // poll every 30 seconds\n" +
            "    3600  // timeout after 1 hour\n" +
            ").join();\n\n" +
            "// 5. Download results\n" +
            "String outputFileId = completedBatch.getOutputFileId();\n" +
            "// Download and process results...");

        System.out.println("\n=� Batch API benefits:");
        System.out.println("    50% cost reduction vs real-time API");
        System.out.println("    Higher rate limits (200K requests/day)");
        System.out.println("    24-hour completion window");
        System.out.println("    Ideal for offline processing\n");
    }

}
