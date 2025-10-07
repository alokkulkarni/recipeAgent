package com.alok

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import com.alok.document.DocumentCreationService
import com.alok.jira.JiraService

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
suspend fun main() {

    println("Hello and welcome to Kotlin programming!")
    println("=".repeat(80))

    val client = OpenAILLMClient(System.getenv("OPENAI_API_KEY"))
//    val model = OpenAIModels.CostOptimized.GPT4_1Mini
    val model = OpenAIModels.Chat.GPT5
    val executor = SingleLLMPromptExecutor(client)

    // Create services
    val jiraTools = JiraService()
    val documentService = DocumentCreationService()

    // Read test documentation creation instructions
//    val testDocInstructionsFile = File(".agents/instructions/testDocCreationInstructions.md")
//    val testDocInstructions = if (testDocInstructionsFile.exists()) {
//        println("📖 Loading Test Documentation Creation Instructions...")
//        testDocInstructionsFile.readText()
//    } else {
//        println("⚠️ Test documentation instructions file not found at ${testDocInstructionsFile.absolutePath}")
//        ""
//    }

    // Test and display Jira tool responses before using with agent
    println("🔍 Testing Jira Service Tools...")
    println("-".repeat(60))

    var projectInfo: com.alok.jira.ProjectInfo? = null
    var sprintIssues: com.alok.jira.SprintIssues? = null

    try {
        // Test project info retrieval
        println("📋 Fetching Project Information for 'Flutter'...")
        projectInfo = jiraTools.getProjectInfo("Flutter")
        println("✅ Project Info Retrieved:")
        println("   ID: ${projectInfo.id}")
        println("   Key: ${projectInfo.key}")
        println("   Name: ${projectInfo.name}")
        println()

        // Test active and future sprints
        println("🏃‍♂️ Fetching Active and Future Sprints...")
        val sprints = jiraTools.getActiveAndFutureSprints("Flutter")
        println("✅ Found ${sprints.size} active/future sprints:")
        sprints.forEach { sprint ->
            println("   Sprint ${sprint.id}: ${sprint.name} (${sprint.state})")
            if (sprint.startDate != null) println("      Start: ${sprint.startDate}")
            if (sprint.endDate != null) println("      End: ${sprint.endDate}")
            if (sprint.goal != null) println("      Goal: ${sprint.goal}")
        }
        println()

        // Test sprint issues retrieval with specific sprint ID
        println("📝 Fetching Sprint Issues for 'Flutter' with sprint 'FLUT1'...")
        sprintIssues = jiraTools.getSprintIssues("Flutter", listOf("FLUT1"))

        println("✅ Sprint Issues Retrieved:")
        println("   Project: ${sprintIssues.projectName}")
        println("   Sprint IDs: ${sprintIssues.sprintIds.joinToString(", ")}")
        println("   Selected Sprints: ${sprintIssues.sprints.size}")
        sprintIssues.sprints.forEach { sprint ->
            println("      - ${sprint.name} (ID: ${sprint.id}, State: ${sprint.state})")
        }

        println("\n📊 Issue Summary:")
        println("   Epics: ${sprintIssues.epics.size}")
        println("   Stories: ${sprintIssues.stories.size}")
        println("   Tasks: ${sprintIssues.tasks.size}")
        println("   Subtasks: ${sprintIssues.subtasks.size}")

        if (sprintIssues.epics.isNotEmpty()) {
            println("\n🎯 Epics:")
            sprintIssues.epics.forEach { epic ->
                println("   ${epic.key}: ${epic.summary} [${epic.status}]")
                if (epic.description.isNotEmpty()) {
                    val truncatedDesc = if (epic.description.length > 100)
                        "${epic.description.take(100)}..."
                    else epic.description
                    println("      Description: $truncatedDesc")
                }
            }
        }

        if (sprintIssues.stories.isNotEmpty()) {
            println("\n📖 Stories:")
            sprintIssues.stories.take(5).forEach { story ->
                println("   ${story.key}: ${story.summary} [${story.status}]")
                if (story.epicKey != null) println("      Epic: ${story.epicKey}")
                if (story.description.isNotEmpty()) {
                    val truncatedDesc = if (story.description.length > 80)
                        "${story.description.take(80)}..."
                    else story.description
                    println("      Description: $truncatedDesc")
                }
            }
            if (sprintIssues.stories.size > 5) {
                println("   ... and ${sprintIssues.stories.size - 5} more stories")
            }
        }

        if (sprintIssues.tasks.isNotEmpty()) {
            println("\n📋 Tasks:")
            sprintIssues.tasks.take(5).forEach { task ->
                println("   ${task.key}: ${task.summary} [${task.status}]")
                if (task.parentKey != null) println("      Parent: ${task.parentKey}")
            }
            if (sprintIssues.tasks.size > 5) {
                println("   ... and ${sprintIssues.tasks.size - 5} more tasks")
            }
        }

        if (sprintIssues.relatedToStory.isNotEmpty()) {
            println("\n🔗 Story-Subtask Relationships:")
            sprintIssues.relatedToStory.entries.take(3).forEach { (storyKey, subtasks) ->
                println("   $storyKey has ${subtasks.size} related items:")
                subtasks.forEach { subtask ->
                    println("      - ${subtask.key}: ${subtask.summary}")
                }
            }
            if (sprintIssues.relatedToStory.size > 3) {
                println("   ... and ${sprintIssues.relatedToStory.size - 3} more story relationships")
            }
        }

    } catch (e: Exception) {
        println("❌ Error testing Jira tools: ${e.message}")
        println("   This might be due to missing JIRA_CREDENTIALS_JSON environment variable")
        println("   or network connectivity issues.")
        e.printStackTrace()
    }

    println("\n" + "=".repeat(80))
    println("🤖 Starting AI Agent with Jira Tools...")
    println("=".repeat(80))

    val toolRegistry = ToolRegistry {
        tools(jiraTools)
        tools(documentService)
    }

    try {
        // Build a simple prompt with Jira data and instruction to follow the guidelines
        val promptBuilder = StringBuilder()

        // Simple prompt instructing LLM to follow the instruction file
        promptBuilder.append("You are a professional Quality Assurance Lead and documentation expert. Please follow the comprehensive test documentation creation instructions provided in the testDocCreationInstructions.md file. " +
                "Also check with user if this is an existing project or new project." +
                "if existing then ask user to provide the existing design document, database link for tables to know the existing datastructures." +
                "else ignore continue to next steps.\n\n")
        promptBuilder.append("Use the Jira project data provided below to create detailed test documentation:\n\n")
        promptBuilder.append("Use tools as necessary to fetch the Jira data and document creation in various formats. understand the user query before making on decision on tool usage. Do not answer any other query that is not releated to the Jira or Test Document creation.\n\n")
        // Add project context from retrieved data
        promptBuilder.append("**PROJECT DATA:**\n")

        if (projectInfo != null) {
            promptBuilder.append("- Project: ${projectInfo.name} (Key: ${projectInfo.key}, ID: ${projectInfo.id})\n")
        }

        if (sprintIssues != null) {
            promptBuilder.append("- Sprint Focus: ${sprintIssues.sprintIds.joinToString(", ")}\n")
            promptBuilder.append("- Available Data: ${sprintIssues.epics.size} epics, ${sprintIssues.stories.size} stories, ")
            promptBuilder.append("${sprintIssues.tasks.size} tasks, ${sprintIssues.subtasks.size} subtasks\n")

            if (sprintIssues.sprints.isNotEmpty()) {
                promptBuilder.append("- Sprint Details: ")
                sprintIssues.sprints.forEach { sprint ->
                    promptBuilder.append("${sprint.name} (${sprint.state})")
                    if (sprint.goal != null) promptBuilder.append(" - Goal: ${sprint.goal}")
                    promptBuilder.append("; ")
                }
                promptBuilder.append("\n")
            }

            // Add detailed epic and story information
            if (sprintIssues.epics.isNotEmpty()) {
                promptBuilder.append("\n**Epic Details:**\n")
                sprintIssues.epics.forEach { epic ->
                    promptBuilder.append("- ${epic.key}: ${epic.summary} [${epic.status}]\n")
                    if (epic.description.isNotEmpty()) {
                        promptBuilder.append("  Description: ${epic.description.take(200)}${if (epic.description.length > 200) "..." else ""}\n")
                    }
                }
            }

            if (sprintIssues.stories.isNotEmpty()) {
                promptBuilder.append("\n**Story Details:**\n")
                sprintIssues.stories.take(10).forEach { story ->
                    promptBuilder.append("- ${story.key}: ${story.summary} [${story.status}]\n")
                    if (story.epicKey != null) promptBuilder.append("  Epic: ${story.epicKey}\n")
                    if (story.description.isNotEmpty()) {
                        promptBuilder.append("  Description: ${story.description.take(150)}${if (story.description.length > 150) "..." else ""}\n")
                    }
                }
                if (sprintIssues.stories.size > 10) {
                    promptBuilder.append("... and ${sprintIssues.stories.size - 10} more stories\n")
                }
            }

            if (sprintIssues.tasks.isNotEmpty()) {
                promptBuilder.append("\n**Task Details:**\n")
                sprintIssues.tasks.take(5).forEach { task ->
                    promptBuilder.append("- ${task.key}: ${task.summary} [${task.status}]\n")
                    if (task.parentKey != null) promptBuilder.append("  Parent: ${task.parentKey}\n")
                }
                if (sprintIssues.tasks.size > 5) {
                    promptBuilder.append("... and ${sprintIssues.tasks.size - 5} more tasks\n")
                }
            }
        }

        val enhancedPrompt = promptBuilder.toString()

        println("📝 Simple prompt prepared with Jira data and instruction to follow testDocCreationInstructions.md")
        println("🔄 Sending request to AI agent...")

        val agent = AIAgent(
            llmModel = model,
            systemPrompt = enhancedPrompt,
            toolRegistry = toolRegistry,
            executor = executor,
            temperature = 1.0
        )

        val userPrompt = prompt("user-prompt") {
            user("""Create various detailed test documentation for the Flutter project and sprint FLUT based on the provided Jira data. 
                |this is a new project across devices and also has a secured backend.
                |also generate this optional artifacts if possible and save as separate downloadable files named after each artifact requested below and not as Test Approach.
                |- Story-level traceability matrix (Jira to test cases to automation)
                |- BDD test cases (Gherkin) per story with positive/negative/resilience scenarios
                |- API contract test suite skeleton (Postman/Newman or Pact CDC)
                |- Security test checklist tailored to your auth and storage model
                |- Device/OS coverage matrix and execution schedule
                |- Test data seed plan for AI personas, rewards tiers, and waitlist scenarios
            """.trimMargin())
        }

        val result = client.moderate( userPrompt, OpenAIModels.Moderation.Omni)

        println("Moderation Result: $result")

        if (result.isHarmful) {
            println("❌ User prompt flagged by moderation: ${result.categories}")
            return
        }
        val response = agent.run(userPrompt.toString())


        println("\n🎯 Agent Response:")
        println("=".repeat(80))
        println(response)
        println("=".repeat(80))

    } catch (e: Exception) {
        println("❌ Error running agent: ${e.message}")
        e.printStackTrace()
    }
}

//@Serializable
//data class beneficiary(val name: String, val acocuntNumber: String, val bankName: String)
//
//@Tool
//@LLMDescription("This tool gets the sentence from the user")
//fun getUserBeneficiaries(userAccount: String): List<beneficiary> {
//    val beneficiaryList = listOf(beneficiary("Alok Kulkarni", "1234567890", "HDFC Bank"),
//        beneficiary ("John Doe", "9876543210", "ICICI Bank"))
//    return beneficiaryList
//}