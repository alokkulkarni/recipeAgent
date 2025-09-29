package com.alok

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import com.alok.jira.JiraService
import com.alok.document.DocumentCreationService
import kotlinx.serialization.Serializable
import java.io.File

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
suspend fun main() {

    println("Hello and welcome to Kotlin programming!")
    println("=".repeat(80))

    val client = OpenAILLMClient(System.getenv("OPENAI_API_KEY"))
    val model = OpenAIModels.CostOptimized.GPT4_1Mini
    val executor = SingleLLMPromptExecutor(client)

    // Create services
    val jiraTools = JiraService()
    val documentService = DocumentCreationService()

    // Read test documentation creation instructions
    val testDocInstructionsFile = File(".agents/instructions/testDocCreationInstructions.md")
    val testDocInstructions = if (testDocInstructionsFile.exists()) {
        println("📖 Loading Test Documentation Creation Instructions...")
        testDocInstructionsFile.readText()
    } else {
        println("⚠️ Test documentation instructions file not found at ${testDocInstructionsFile.absolutePath}")
        ""
    }

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

    val agent = AIAgent(
        llmModel = model,
        toolRegistry = toolRegistry,
        executor = executor
    )

    try {
        // Build comprehensive prompt with test documentation instructions and Jira data
        val promptBuilder = StringBuilder()

        promptBuilder.append("You are a professional test documentation expert tasked with creating comprehensive test documentation based on Jira project data and specific instructions.\n\n")

        promptBuilder.append("**CRITICAL REQUIREMENTS:**\n")
        promptBuilder.append("1. You MUST thoroughly read and follow ALL the test documentation creation instructions provided below\n")
        promptBuilder.append("2. You MUST use the document creation tools to actually create and save the documents\n")
        promptBuilder.append("3. You MUST validate each document against the provided checklist before completion\n")
        promptBuilder.append("4. You MUST gather all Jira data first using the available tools\n\n")

        promptBuilder.append("**DOCUMENT CREATION WORKFLOW:**\n")
        promptBuilder.append("STEP 1: Use Jira tools to gather ALL project and sprint information for Flutter project with sprint FLUT1\n")
        promptBuilder.append("STEP 2: Create a Jira data fingerprint with epic/story summaries and counts\n")
        promptBuilder.append("STEP 3: Use 'checkExistingDocument' tool for each document type to see if similar documents exist\n")
        promptBuilder.append("STEP 4: If existing documents found with matching requirements, use those and skip creation\n")
        promptBuilder.append("STEP 5: Only create new documents if no matching existing documents found:\n")
        promptBuilder.append("   - Use 'createTestStrategyDocument' tool to create the Test Strategy document\n")
        promptBuilder.append("   - Use 'createTestApproachDocument' tool to create the Test Approach document\n")
        promptBuilder.append("   - Use 'createTestPlanDocument' tool to create the Test Plan document\n")
        promptBuilder.append("STEP 6: Use 'listCreatedDocuments' tool to provide download links\n")
        promptBuilder.append("STEP 7: Validate each document against the validation checklist\n\n")

        if (testDocInstructions.isNotEmpty()) {
            promptBuilder.append("**MANDATORY TEST DOCUMENTATION CREATION INSTRUCTIONS:**\n")
            promptBuilder.append("READ THESE INSTRUCTIONS CAREFULLY AND FOLLOW EVERY SECTION AND GUIDELINE:\n\n")
            promptBuilder.append(testDocInstructions)
            promptBuilder.append("\n\n")

            promptBuilder.append("**DOCUMENT VALIDATION CHECKLIST:**\n")
            promptBuilder.append("For each document created, ensure it includes ALL the following components:\n\n")

            promptBuilder.append("**TEST STRATEGY DOCUMENT CHECKLIST:**\n")
            promptBuilder.append("□ Executive Summary with scope, objectives, and risk assessment\n")
            promptBuilder.append("□ Vision and Mission Definition with clear testing objectives\n")
            promptBuilder.append("□ Test Scope with prioritization strategy based on Jira epics/stories\n")
            promptBuilder.append("□ Test Approach and Types with Test Pyramid Implementation (70-20-10 distribution)\n")
            promptBuilder.append("□ Automation Strategy with High ROI priorities and framework selection\n")
            promptBuilder.append("□ Roles and Responsibilities with Shared Ownership Model\n")
            promptBuilder.append("□ Test Environment Strategy with environment management details\n")
            promptBuilder.append("□ Tools and Technology Stack specifications\n")
            promptBuilder.append("□ Entry and Exit Criteria clearly defined\n")
            promptBuilder.append("□ Risk Management and Metrics with detailed framework and KPIs\n")
            promptBuilder.append("□ Continuous Improvement Strategy with feedback loops\n")
            promptBuilder.append("□ All sections must reference actual Jira data (epics, stories, tasks)\n\n")

            promptBuilder.append("**TEST APPROACH DOCUMENT CHECKLIST:**\n")
            promptBuilder.append("□ Introduction with project background from Jira data\n")
            promptBuilder.append("□ Test Planning Details with sprint-specific focus\n")
            promptBuilder.append("□ Test Design Approach using BDD/TDD methodology\n")
            promptBuilder.append("□ Test Execution Strategy aligned with sprint timeline\n")
            promptBuilder.append("□ Test Data and Environment Management\n")
            promptBuilder.append("□ Test Automation Strategy with CI/CD integration\n")
            promptBuilder.append("□ Reporting and Metrics with stakeholder communication\n")
            promptBuilder.append("□ Agile Testing Approaches Framework implementation\n")
            promptBuilder.append("□ Testing Approach Selection Guide applied to stories\n")
            promptBuilder.append("□ Sprint-Specific Testing Implementation details\n")
            promptBuilder.append("□ All testing approaches mapped to specific Jira stories/epics\n\n")

            promptBuilder.append("**TEST PLAN DOCUMENT CHECKLIST:**\n")
            promptBuilder.append("□ All 9 Key Steps of Agile Test Plan Creation implemented:\n")
            promptBuilder.append("  □ Step 1: Requirements understanding from Jira data\n")
            promptBuilder.append("  □ Step 2: Test objectives and scope definition\n")
            promptBuilder.append("  □ Step 3: Right testing approach selection\n")
            promptBuilder.append("  □ Step 4: Test environment and data requirements\n")
            promptBuilder.append("  □ Step 5: Test design and execution planning\n")
            promptBuilder.append("  □ Step 6: Testing resources allocation\n")
            promptBuilder.append("  □ Step 7: Defect management process\n")
            promptBuilder.append("  □ Step 8: Testing timelines establishment\n")
            promptBuilder.append("  □ Step 9: Communication and collaboration plan\n")
            promptBuilder.append("□ Requirements Traceability Matrix with all Jira epics/stories\n")
            promptBuilder.append("□ Testing Approach Matrix mapping stories to approaches\n")
            promptBuilder.append("□ Resource Allocation Plan with skill requirements\n")
            promptBuilder.append("□ Environment and Data Requirements table\n")
            promptBuilder.append("□ Testing Timeline and Milestones with dependencies\n")
            promptBuilder.append("□ Integration with Agile Ceremonies detailed\n")
            promptBuilder.append("□ Quality Gates and Success Criteria defined\n")
            promptBuilder.append("□ Best Practices for Agile Test Plan Creation included\n\n")

            promptBuilder.append("**CONTENT QUALITY REQUIREMENTS:**\n")
            promptBuilder.append("□ All documents must reference specific Jira data (epic keys, story keys, tasks)\n")
            promptBuilder.append("□ Include actual acceptance criteria from stories where available\n")
            promptBuilder.append("□ Map testing approaches to story complexity and risk levels\n")
            promptBuilder.append("□ Use professional formatting with tables and matrices\n")
            promptBuilder.append("□ Provide specific, measurable criteria and timelines\n")
            promptBuilder.append("□ Include rationale for all testing approach selections\n")
            promptBuilder.append("□ Ensure alignment with sprint goals and capacity\n")
            promptBuilder.append("□ Address both functional and non-functional testing needs\n\n")

            promptBuilder.append("**DOCUMENT REUSE STRATEGY:**\n")
            promptBuilder.append("Before creating any new document, you MUST:\n")
            promptBuilder.append("1. Create a fingerprint of the current Jira data (epic/story summaries, counts, etc.)\n")
            promptBuilder.append("2. Use 'checkExistingDocument' tool to see if similar documents already exist\n")
            promptBuilder.append("3. If existing documents are found with matching requirements, reuse them\n")
            promptBuilder.append("4. Only create new documents if no matching existing documents are found\n")
            promptBuilder.append("5. Always inform the user whether documents are reused or newly created\n\n")
        }

        promptBuilder.append("**PROJECT CONTEXT FROM RETRIEVED DATA:**\n")

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

            // Add detailed epic and story information for better context
            if (sprintIssues.epics.isNotEmpty()) {
                promptBuilder.append("- Epic Details:\n")
                sprintIssues.epics.forEach { epic ->
                    promptBuilder.append("  * ${epic.key}: ${epic.summary} [${epic.status}]\n")
                    if (epic.description.isNotEmpty()) {
                        promptBuilder.append("    Description: ${epic.description.take(200)}${if (epic.description.length > 200) "..." else ""}\n")
                    }
                }
            }

            if (sprintIssues.stories.isNotEmpty()) {
                promptBuilder.append("- Story Details:\n")
                sprintIssues.stories.take(10).forEach { story ->
                    promptBuilder.append("  * ${story.key}: ${story.summary} [${story.status}]\n")
                    if (story.epicKey != null) promptBuilder.append("    Epic: ${story.epicKey}\n")
                    if (story.description.isNotEmpty()) {
                        promptBuilder.append("    Description: ${story.description.take(150)}${if (story.description.length > 150) "..." else ""}\n")
                    }
                }
                if (sprintIssues.stories.size > 10) {
                    promptBuilder.append("  ... and ${sprintIssues.stories.size - 10} more stories\n")
                }
            }
        }

        promptBuilder.append("\n**MANDATORY ACTIONS:**\n")
        promptBuilder.append("1. FIRST: Gather additional Jira data if needed using available tools\n")
        promptBuilder.append("2. ANALYZE: Review all instructions and map them to the Jira data\n")
        promptBuilder.append("3. CREATE: Generate each document following the exact structure outlined in instructions\n")
        promptBuilder.append("4. VALIDATE: Check each document against the provided checklist\n")
        promptBuilder.append("5. SAVE: Use document creation tools to save each document\n")
        promptBuilder.append("6. VERIFY: Confirm all documents are created and provide download links\n\n")

        promptBuilder.append("**FINAL DELIVERABLES EXPECTED:**\n")
        promptBuilder.append("- Test Strategy Document (8-12 pages) with all 11 sections completed\n")
        promptBuilder.append("- Test Approach Document (6-10 pages) with all framework implementations\n")
        promptBuilder.append("- Test Plan Document (comprehensive) with all 9 steps and matrices\n")
        promptBuilder.append("- Download links for all created documents\n")
        promptBuilder.append("- Confirmation that all checklist items are addressed\n\n")

        promptBuilder.append("BEGIN NOW: Start by gathering any additional Jira data needed, then create comprehensive, ")
        promptBuilder.append("professional test documentation that strictly follows the provided instructions and ")
        promptBuilder.append("incorporates all the Jira project data for Flutter Sprint FLUT1.")

        val enhancedPrompt = promptBuilder.toString()

        println("📝 Enhanced prompt prepared with test documentation instructions")
        println("🔄 Sending request to AI agent...")

        val response = agent.run(enhancedPrompt)

        println("\n🎯 Agent Response:")
        println("=".repeat(80))
        println(response)
        println("=".repeat(80))

    } catch (e: Exception) {
        println("❌ Error running agent: ${e.message}")
        e.printStackTrace()
    }

}

@Serializable
data class beneficiary(val name: String, val acocuntNumber: String, val bankName: String)

@Tool
@LLMDescription("This tool gets the sentence from the user")
fun getUserBeneficiaries(userAccount: String): List<beneficiary> {
    val beneficiaryList = listOf(beneficiary("Alok Kulkarni", "1234567890", "HDFC Bank"),
        beneficiary("John Doe", "9876543210", "ICICI Bank"))
    return beneficiaryList
}
