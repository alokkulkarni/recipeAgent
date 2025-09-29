package com.alok.jira

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.*

@Serializable
data class JiraCredentials(
    val baseUrl: String,
    val email: String,
    val apiToken: String
)

@Serializable
data class ProjectInfo(
    val id: String,
    val key: String,
    val name: String
)

@Serializable
data class IssueSummary(
    val id: String,
    val key: String,
    val type: String,
    val summary: String,
    val status: String,
    val description: String = "",
    val epicKey: String? = null,
    val parentKey: String? = null
)

@Serializable
data class SprintInfo(
    val id: Int,
    val name: String,
    val state: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val goal: String? = null,
    val boardId: Int? = null
)

@Serializable
 data class SprintIssues(
     val projectName: String,
     val sprintIds: List<String>,
     val epics: List<IssueSummary>,
     val stories: List<IssueSummary>,
     val tasks: List<IssueSummary>,
     val subtasks: List<IssueSummary>,
     // Mapping of Story key -> list of related tasks/subtasks (based on parent relationship)
     val relatedToStory: Map<String, List<IssueSummary>>,
     val sprints: List<SprintInfo>
 )

//@LLMDescription("Service to interact with Jira for project and issue information. Retrieve the information to pass back to LLM")
class JiraService(): ToolSet {

    private data class HttpResult(val status: Int, val body: String, val headers: Map<String, List<String>> = emptyMap())

    private val client: HttpClient = HttpClient.newHttpClient()
    private val requestTimeoutSeconds: Long = 25
    private val maxRetries: Int = 5

    // GET with timeouts, Retry-After handling, exponential backoff + jitter
    private fun httpGet(url: String): HttpResult {
        var attempt = 0
        var last: HttpResponse<String>? = null
        while (attempt < maxRetries) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(requestTimeoutSeconds))
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val code = resp.statusCode()
            if (code !in 500..599 && code != 429) {
                return HttpResult(code, resp.body(), resp.headers().map())
            }
            last = resp
            attempt++
            val retryAfter = resp.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()
            val baseDelay = if (retryAfter != null) retryAfter * 1000 else (200L * (1L shl (attempt.coerceAtMost(4))))
            val jitter = (Math.random() * 250).toLong()
            Thread.sleep(baseDelay + jitter)
        }
        val fallback = last
        return HttpResult(fallback?.statusCode() ?: 599, fallback?.body() ?: "", fallback?.headers()?.map() ?: emptyMap())
    }

    // POST JSON helper with same retry strategy
    private fun httpPostJson(url: String, bodyJson: String): HttpResult {
        var attempt = 0
        var last: HttpResponse<String>? = null
        while (attempt < maxRetries) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(requestTimeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val code = resp.statusCode()
            if (code !in 500..599 && code != 429) {
                return HttpResult(code, resp.body(), resp.headers().map())
            }
            last = resp
            attempt++
            val retryAfter = resp.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()
            val baseDelay = if (retryAfter != null) retryAfter * 1000 else (200L * (1L shl (attempt.coerceAtMost(4))))
            val jitter = (Math.random() * 250).toLong()
            Thread.sleep(baseDelay + jitter)
        }
        val fallback = last
        return HttpResult(fallback?.statusCode() ?: 599, fallback?.body() ?: "", fallback?.headers()?.map() ?: emptyMap())
    }

    /*
        {
            "baseUrl": "https://your-domain.atlassian.net",
            "email": "kulkarni.alok@gmail.com",
            "apiToken": "your_api_token"
         }
    */


    private val credentials: JiraCredentials = System.getenv("JIRA_CREDENTIALS_JSON")?.let {
        Json.decodeFromString(JiraCredentials.serializer(), it)
    } ?: throw IllegalStateException("JIRA_CREDENTIALS_JSON environment variable not set or invalid")

    private val json = Json { ignoreUnknownKeys = true }

    private fun authHeader(): String {
        val token = Base64.getEncoder()
            .encodeToString("${credentials.email}:${credentials.apiToken}".toByteArray(StandardCharsets.UTF_8))
        return "Basic $token"
    }

    // Try to resolve a user-provided project identifier (name or key) to a canonical Jira project key
    private fun resolveProjectKey(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Project key/name is blank")

        // Call Jira project search API which matches by key or name
        val url = "${credentials.baseUrl.trimEnd('/')}/rest/api/3/project/search?query=${encode(trimmed)}"
        val resp = httpGet(url)
        if (resp.status !in 200..299) {
            throw RuntimeException("Jira project search failed: HTTP ${resp.status} - ${resp.body}")
        }
        val root = json.parseToJsonElement(resp.body).jsonObject
        val values = root["values"]?.jsonArray ?: JsonArray(emptyList())
        if (values.isEmpty()) {
            // If nothing found, assume the user already gave a key and return it as-is
            return trimmed
        }
        // Try exact key match (case-insensitive)
        values.firstOrNull {
            val key = it.jsonObject["key"]?.jsonPrimitive?.content ?: return@firstOrNull false
            key.equals(trimmed, ignoreCase = true)
        }?.let {
            return it.jsonObject["key"]?.jsonPrimitive?.content ?: trimmed
        }
        // Try exact name match (case-insensitive)
        values.firstOrNull {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content ?: return@firstOrNull false
            name.equals(trimmed, ignoreCase = true)
        }?.let {
            return it.jsonObject["key"]?.jsonPrimitive?.content ?: trimmed
        }
        // Fallback: contains in name or key
        values.firstOrNull {
            val key = it.jsonObject["key"]?.jsonPrimitive?.content ?: ""
            val name = it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
            key.contains(trimmed, ignoreCase = true) || name.contains(trimmed, ignoreCase = true)
        }?.let {
            return it.jsonObject["key"]?.jsonPrimitive?.content ?: trimmed
        }
        return trimmed
    }

    @Tool
    @LLMDescription("Get the project information for the given project name")
    fun getProjectInfo(
        @LLMDescription("Project name for retrieving the project info from Jira")
        projectName: String): ProjectInfo
    {
        // Resolve user-provided identifier (project name) to canonical Jira project key
        val resolvedKey = resolveProjectKey(projectName)
        val url = "${credentials.baseUrl.trimEnd('/')}/rest/api/3/project/${encode(resolvedKey)}"
        val resp = httpGet(url)
        if (resp.status !in 200..299) {
            val msg = when (resp.status) {
                401, 403 -> "Authentication failed. Check JIRA_CREDENTIALS_JSON (email/token) and baseUrl permissions."
                404 -> "Project not found for key '$resolvedKey'."
                else -> "HTTP ${resp.status} - ${resp.body}"
            }
            throw RuntimeException("Jira getProjectInfo failed: $msg")
        }
        val obj = json.parseToJsonElement(resp.body).jsonObject
        val id = obj["id"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Project id missing in response")
        val key = obj["key"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Project key missing in response")
        val name = obj["name"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Project name missing in response")
        return ProjectInfo(id = id, key = key, name = name)
    }

    @Tool
    @LLMDescription("Get project issues by project name. Strategy: first retrieve all ACTIVE and FUTURE sprints for the project, match any provided sprint identifiers (numeric or alphanumeric) against those; if none match or none provided, use all active/future sprints. If no such sprints exist, fall back to querying all project issues or previously-resolved sprints.")
    fun getSprintIssues(
        @LLMDescription("Project name for retrieving the issues from Jira")
        projectName: String,
        @LLMDescription("Optional sprint ids (numeric or alphanumeric). If provided, issues will be filtered to these sprints; otherwise, all project issues are returned.")
        sprintIds: List<String>? = null
    ): SprintIssues {
        val resolvedKey = resolveProjectKey(projectName)

        // Step 1: Get all available sprints for the project
        val allAvailableSprintObjs = fetchActiveAndFutureSprintsForProject(resolvedKey)
        val allAvailableSprints = allAvailableSprintObjs.mapNotNull { toSprintInfo(it) }

        // Step 2: Process provided sprint identifiers
        val providedTokens = sprintIds?.mapNotNull { token ->
            token.trim().trim('"', '\'').takeIf { it.isNotEmpty() }
        } ?: emptyList()

        // Step 3: Resolve sprint identifiers to actual sprints
        val selectedSprints = if (providedTokens.isEmpty()) {
            // No sprint IDs provided - use all active/future sprints
            allAvailableSprints
        } else {
            // Resolve provided tokens to sprint objects
            resolveSprintTokensToSprints(resolvedKey, providedTokens, allAvailableSprints)
        }

        // Step 4: Build JQL query based on resolved sprints
        val jql = buildSprintJQL(resolvedKey, selectedSprints)

        // Step 5: Execute search and retrieve all issues
        val allIssues = executeSprintSearch(jql)

        // Step 6: If sprint-specific search returned no results, try Agile API fallback
        val finalIssues = if (selectedSprints.isNotEmpty() && allIssues.isEmpty()) {
            tryAgileApiFallback(selectedSprints, allIssues)
        } else {
            allIssues
        }

        // Step 7: Parse and categorize issues
        val (categorizedIssues, epicLinkFieldId) = categorizeIssues(finalIssues)

        // Step 8: Handle epics for sprint-filtered queries
        val finalEpics = if (selectedSprints.isNotEmpty()) {
            fetchRelevantEpics(resolvedKey, categorizedIssues.stories, epicLinkFieldId)
        } else {
            categorizedIssues.epics
        }

        // Step 9: Build response
        val sprintIdsForResponse = selectedSprints.map { it.id.toString() }

        return SprintIssues(
            projectName = projectName,
            sprintIds = sprintIdsForResponse,
            epics = finalEpics,
            stories = categorizedIssues.stories,
            tasks = categorizedIssues.tasks,
            subtasks = categorizedIssues.subtasks,
            relatedToStory = categorizedIssues.relatedMap,
            sprints = selectedSprints
        )
    }

    // Helper method to resolve sprint tokens to actual sprint objects
    private fun resolveSprintTokensToSprints(
        projectKey: String,
        tokens: List<String>,
        availableSprints: List<SprintInfo>
    ): List<SprintInfo> {
        val numericTokens = tokens.filter { it.all(Char::isDigit) }.mapNotNull { it.toIntOrNull() }.toSet()
        val textTokens = tokens.filter { !it.all(Char::isDigit) }

        val matchedSprints = mutableSetOf<SprintInfo>()

        // Match by numeric ID
        availableSprints.filter { numericTokens.contains(it.id) }.let { matchedSprints.addAll(it) }

        // Match by name (case-insensitive, exact and partial matching)
        for (token in textTokens) {
            val normalizedToken = normalizeSprintName(token)

            // Try exact match first
            availableSprints.firstOrNull {
                normalizeSprintName(it.name) == normalizedToken
            }?.let { matchedSprints.add(it) }

            // Try partial match if no exact match found
            if (matchedSprints.none { normalizeSprintName(it.name).contains(normalizedToken) }) {
                availableSprints.filter {
                    normalizeSprintName(it.name).contains(normalizedToken)
                }.let { matchedSprints.addAll(it) }
            }
        }

        // If no matches found in active/future, try broader search
        if (matchedSprints.isEmpty() && tokens.isNotEmpty()) {
            val broaderResolution = resolveSprintIdentifiers(projectKey, tokens)
            if (broaderResolution.first.isNotEmpty()) {
                // Fetch sprint info for resolved IDs
                return fetchSprintInfoByIds(projectKey, broaderResolution.first.toList())
            }
        }

        return matchedSprints.toList()
    }

    // Normalize sprint names for better matching
    private fun normalizeSprintName(name: String): String = name.lowercase()
        .replace("\u00A0", " ")
        .replace("_", " ")
        .replace("-", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    // Build optimized JQL query for sprint issues
    private fun buildSprintJQL(projectKey: String, sprints: List<SprintInfo>): String {
        return if (sprints.isNotEmpty()) {
            val sprintIds = sprints.joinToString(",") { it.id.toString() }
            "project = $projectKey AND sprint in ($sprintIds) ORDER BY Rank, created DESC"
        } else {
            "project = $projectKey ORDER BY Rank, created DESC"
        }
    }

    // Execute sprint search with proper pagination and error handling
    private fun executeSprintSearch(jql: String): List<JsonObject> {
        val allIssues = mutableListOf<JsonObject>()
        val fields = listOf(
            "summary", "issuetype", "status", "parent", "subtasks",
            "description", "epic", "assignee", "priority", "components",
            "fixVersions", "labels", "created", "updated"
        )

        var startAt = 0
        val maxResults = 100
        var epicLinkFieldId: String? = null

        try {
            while (true) {
                // Use the new JQL search endpoint as required by Jira
                val baseSearch = "${credentials.baseUrl.trimEnd('/')}/rest/api/3/search/jql"
                val usePost = jql.length > 1500

                val resp = if (usePost) {
                    val body = buildJsonObject {
                        put("jql", jql)
                        put("startAt", startAt)
                        put("maxResults", maxResults)
                        putJsonArray("fields") { fields.forEach { add(it) } }
                        putJsonArray("expand") { add("names") }
                    }.toString()
                    httpPostJson(baseSearch, body)
                } else {
                    val url = "$baseSearch?jql=${encode(jql)}&fields=${encode(fields.joinToString(","))}&startAt=$startAt&maxResults=$maxResults&expand=names"
                    val r = httpGet(url)
                    if (r.status == 414) {
                        // URI too long -> retry with POST
                        val body = buildJsonObject {
                            put("jql", jql)
                            put("startAt", startAt)
                            put("maxResults", maxResults)
                            putJsonArray("fields") { fields.forEach { add(it) } }
                            putJsonArray("expand") { add("names") }
                        }.toString()
                        httpPostJson(baseSearch, body)
                    } else r
                }

                if (resp.status !in 200..299) {
                    throw RuntimeException("Jira search failed: HTTP ${resp.status} - ${resp.body}")
                }

                val root = json.parseToJsonElement(resp.body).jsonObject

                // Resolve Epic Link custom field id once
                if (epicLinkFieldId == null) {
                    epicLinkFieldId = resolveEpicLinkFieldId(root)
                }

                val issuesArray = root["issues"]?.jsonArray ?: JsonArray(emptyList())
                for (issue in issuesArray) {
                    allIssues.add(issue.jsonObject)
                }

                val total = root["total"]?.jsonPrimitive?.int ?: allIssues.size
                startAt += issuesArray.size

                if (allIssues.size >= total || issuesArray.isEmpty()) break
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to execute sprint search: ${e.message}", e)
        }

        return allIssues
    }

    // Try Agile API as fallback for sprint issues
    private fun tryAgileApiFallback(sprints: List<SprintInfo>, currentIssues: List<JsonObject>): List<JsonObject> {
        if (sprints.isEmpty()) return currentIssues

        try {
            val sprintIds = sprints.map { it.id }.toSet()
            val fields = listOf(
                "summary", "issuetype", "status", "parent", "subtasks",
                "description", "epic", "assignee", "priority"
            )
            val (agileIssues, _) = fetchIssuesByAgileSprint(sprintIds, fields)
            return if (agileIssues.isNotEmpty()) agileIssues else currentIssues
        } catch (e: Exception) {
            // Log error but return current issues
            println("Agile API fallback failed: ${e.message}")
            return currentIssues
        }
    }

    // Data class for categorized issues
    private data class CategorizedIssues(
        val epics: List<IssueSummary>,
        val stories: List<IssueSummary>,
        val tasks: List<IssueSummary>,
        val subtasks: List<IssueSummary>,
        val relatedMap: Map<String, List<IssueSummary>>
    )

    // Categorize issues and build relationships
    private fun categorizeIssues(allIssues: List<JsonObject>): Pair<CategorizedIssues, String?> {
        val epics = mutableListOf<IssueSummary>()
        val stories = mutableListOf<IssueSummary>()
        val tasks = mutableListOf<IssueSummary>()
        val subtasks = mutableListOf<IssueSummary>()
        val relatedMap = mutableMapOf<String, MutableList<IssueSummary>>()

        var epicLinkFieldId: String? = null

        // First pass: resolve epic link field ID from any issue
        for (issue in allIssues) {
            if (epicLinkFieldId == null) {
                val mockRoot = buildJsonObject {
                    put("issues", buildJsonArray { add(issue) })
                    issue["names"]?.let { put("names", it) }
                }
                epicLinkFieldId = resolveEpicLinkFieldId(mockRoot)
            }
            if (epicLinkFieldId != null) break
        }

        // Second pass: categorize all issues
        for (issue in allIssues) {
            val parsed = parseIssue(issue, epicLinkFieldId)

            when (parsed.type.lowercase()) {
                "epic" -> epics += parsed
                "story", "user story" -> {
                    stories += parsed
                    // Handle story subtasks
                    addStorySubtasks(issue, parsed.key, relatedMap)
                }
                "task", "bug", "incident", "improvement", "new feature" -> tasks += parsed
                "sub-task", "subtask" -> {
                    subtasks += parsed
                    // Link subtask to parent
                    parsed.parentKey?.let { parentKey ->
                        relatedMap.getOrPut(parentKey) { mutableListOf() }.add(parsed)
                    }
                }
                else -> {
                    // Treat unknown types as tasks
                    tasks += parsed
                }
            }
        }

        val categorized = CategorizedIssues(epics, stories, tasks, subtasks, relatedMap)
        return Pair(categorized, epicLinkFieldId)
    }

    // Add subtasks declared in story's subtasks field
    private fun addStorySubtasks(
        storyIssue: JsonObject,
        storyKey: String,
        relatedMap: MutableMap<String, MutableList<IssueSummary>>
    ) {
        val fieldsObj = storyIssue["fields"]?.jsonObject ?: return
        val subTasksArray = fieldsObj["subtasks"]?.jsonArray ?: return

        for (subtask in subTasksArray) {
            val subtaskObj = subtask.jsonObject
            val subtaskKey = subtaskObj["key"]?.jsonPrimitive?.content ?: continue

            // Only add if not already present
            if (relatedMap[storyKey]?.none { it.key == subtaskKey } != false) {
                val subtaskSummary = IssueSummary(
                    id = subtaskObj["id"]?.jsonPrimitive?.content ?: subtaskKey,
                    key = subtaskKey,
                    type = subtaskObj["fields"]?.jsonObject?.get("issuetype")?.jsonObject
                        ?.get("name")?.jsonPrimitive?.content ?: "Sub-task",
                    summary = subtaskObj["fields"]?.jsonObject?.get("summary")?.jsonPrimitive?.content ?: "",
                    status = subtaskObj["fields"]?.jsonObject?.get("status")?.jsonObject
                        ?.get("name")?.jsonPrimitive?.content ?: "",
                    epicKey = null,
                    parentKey = storyKey
                )
                relatedMap.getOrPut(storyKey) { mutableListOf() }.add(subtaskSummary)
            }
        }
    }

    // Fetch relevant epics for sprint-filtered queries
    private fun fetchRelevantEpics(
        projectKey: String,
        stories: List<IssueSummary>,
        epicLinkFieldId: String?
    ): List<IssueSummary> {
        try {
            val referencedEpicKeys = stories.mapNotNull { it.epicKey }.toSet()
            val epicFields = listOf("summary", "issuetype", "status", "description")
            val epicsRaw = mutableListOf<JsonObject>()

            var startAtEpics = 0
            val maxResultsEpics = 100
            val epicJql = "project = $projectKey AND issuetype = Epic ORDER BY Rank"

            while (true) {
                // Use the new JQL search endpoint for epics as well
                val url = "${credentials.baseUrl.trimEnd('/')}/rest/api/3/search/jql?jql=${encode(epicJql)}&fields=${encode(epicFields.joinToString(","))}&startAt=$startAtEpics&maxResults=$maxResultsEpics"
                val resp = httpGet(url)
                if (resp.status !in 200..299) break

                val root = json.parseToJsonElement(resp.body).jsonObject
                val arr = root["issues"]?.jsonArray ?: JsonArray(emptyList())
                for (epic in arr) {
                    epicsRaw.add(epic.jsonObject)
                }

                val total = root["total"]?.jsonPrimitive?.int ?: (startAtEpics + arr.size)
                startAtEpics += arr.size
                if (arr.isEmpty() || startAtEpics >= total) break
            }

            val parsedEpics = epicsRaw.map { parseIssue(it, epicLinkFieldId) }
            return if (referencedEpicKeys.isNotEmpty()) {
                parsedEpics.filter { it.key in referencedEpicKeys }
            } else {
                parsedEpics
            }
        } catch (e: Exception) {
            // Return empty list on error
            println("Failed to fetch epics: ${e.message}")
            return emptyList()
        }
    }

    @Tool
    @LLMDescription("List active and future sprints for a given project name in Jira. Useful to validate sprint identifiers like 'FLUT1' before querying issues.")
    fun getActiveAndFutureSprints(
        @LLMDescription("Project name (or key) to resolve and list its active/future sprints")
        projectName: String
    ): List<SprintInfo> {
        val resolvedKey = resolveProjectKey(projectName)
        val sprintObjs = fetchActiveAndFutureSprintsForProject(resolvedKey)
        return sprintObjs.mapNotNull { toSprintInfo(it) }
    }

    @Tool
    @LLMDescription("Debug retrieval of sprint issues: validates Jira API paths, shows resolved project key, boards, active/future sprints, provided tokens, matched/resolved sprint IDs, final JQL, and issue counts. Use this to validate sprint like 'FLUT1'.")
    fun getSprintIssuesDebug(
        @LLMDescription("Project name for retrieving the issues from Jira")
        projectName: String,
        @LLMDescription("Optional sprint ids (numeric or alphanumeric) for filtering; e.g., ['FLUT1']")
        sprintIds: List<String>? = null
    ): SprintIssues {
        val resolvedKey = resolveProjectKey(projectName)
        println("[DEBUG_LOG] Resolved project '$projectName' -> key '$resolvedKey'")
        val boards = fetchBoardsForProject(resolvedKey)
        println("[DEBUG_LOG] Boards for project: ${boards.joinToString(", ")}")
        val afSprints = fetchActiveAndFutureSprintsForProject(resolvedKey).mapNotNull { toSprintInfo(it) }
        val afSummary = afSprints.joinToString(", ") { "${it.id}:${it.name}(${it.state})" }
        println("[DEBUG_LOG] Active/Future sprints: $afSummary")

        val providedTokens = sprintIds?.map { it.trim().trim('"', '\'') }?.filter { it.isNotEmpty() } ?: emptyList()
        println("[DEBUG_LOG] Provided sprint tokens: ${providedTokens.joinToString(", ")}")
        val numericTokens = providedTokens.filter { tok -> tok.all { ch -> ch.isDigit() } }.mapNotNull { it.toIntOrNull() }.toSet()

        fun normDbg(x: String) = normalizeSprintName(x)
        val providedNormDbg = providedTokens.map { normDbg(it) }
        var selectedSprints: List<SprintInfo> = if (providedTokens.isEmpty()) {
            afSprints
        } else {
            afSprints.filter { s ->
                val sNorm = normDbg(s.name)
                numericTokens.contains(s.id) || providedNormDbg.any { t -> sNorm == t || sNorm.contains(t) }
            }
        }

        var usedFallbackResolver = false
        var fallbackResolved: Pair<Set<Int>, List<String>> = Pair(emptySet(), emptyList())
        if ((providedTokens.isNotEmpty() && selectedSprints.isEmpty()) || (providedTokens.isEmpty() && afSprints.isEmpty())) {
            usedFallbackResolver = providedTokens.isNotEmpty()
            if (providedTokens.isNotEmpty()) {
                fallbackResolved = resolveSprintIdentifiers(resolvedKey, providedTokens)
            }
        }

        val jql = when {
            selectedSprints.isNotEmpty() -> {
                val idList = selectedSprints.joinToString(",") { it.id.toString() }
                "project=$resolvedKey AND sprint in ($idList) ORDER BY Rank"
            }
            usedFallbackResolver && (fallbackResolved.first.isNotEmpty() || fallbackResolved.second.isNotEmpty()) -> {
                val numericPart = fallbackResolved.first.joinToString(",") { it.toString() }
                val stringPart = fallbackResolved.second.joinToString(",") { token ->
                    "\"" + token.replace("\"", "\\\"") + "\""
                }
                val parts = listOf(numericPart, stringPart).filter { it.isNotBlank() }
                val combined = parts.joinToString(",")
                if (combined.isNotBlank()) "project=$resolvedKey AND sprint in ($combined) ORDER BY Rank" else "project=$resolvedKey ORDER BY Rank"
            }
            else -> {
                "project=$resolvedKey ORDER BY Rank"
            }
        }
        println("[DEBUG_LOG] Final JQL to query issues: $jql")

        val result = getSprintIssues(projectName, sprintIds)
        println("[DEBUG_LOG] Retrieved issues — Epics: ${result.epics.size}, Stories: ${result.stories.size}, Tasks: ${result.tasks.size}, Subtasks: ${result.subtasks.size}")
        if (result.sprintIds.isNotEmpty()) {
            println("[DEBUG_LOG] Selected sprint IDs: ${result.sprintIds.joinToString(", ")}")
        } else {
            println("[DEBUG_LOG] No specific sprint filter applied (project-wide query)")
        }
        return result
    }

    // Resolve provided sprint identifiers (numeric IDs or name fragments) to numeric sprint IDs using the Agile API.
    // Returns Pair<resolvedNumericIds, unresolvedTokens>
    private fun resolveSprintIdentifiers(projectKey: String, tokens: List<String>): Pair<Set<Int>, List<String>> {
        val unresolved = mutableListOf<String>()
        val resolved = linkedSetOf<Int>()

        // Separate numeric tokens early
        tokens.forEach { t ->
            val trimmed = t.trim()
            if (trimmed.all { it.isDigit() }) {
                resolved.add(trimmed.toInt())
            } else if (trimmed.isNotEmpty()) {
                unresolved.add(trimmed)
            }
        }
        if (unresolved.isEmpty()) return Pair(resolved, emptyList())

        // Fetch boards for project
        val boards = fetchBoardsForProject(projectKey)
        if (boards.isEmpty()) {
            // can't resolve; mark all as unresolved
            unresolved.addAll(unresolved)
            return Pair(resolved, unresolved)
        }

        // Fetch sprints for each board and build a name->id map (case-insensitive)
        val sprintIndex = mutableMapOf<Int, String>() // id -> name
        for (b in boards) {
            val sprints = fetchSprintsForBoard(b)
            for (s in sprints) {
                val id = s["id"]?.jsonPrimitive?.intOrNull ?: continue
                val name = s["name"]?.jsonPrimitive?.content ?: continue
                sprintIndex[id] = name
            }
        }

        // Try exact (case-insensitive) name matches first, then contains/startsWith
        fun resolveByPredicate(pred: (String) -> Boolean): Set<Int> = sprintIndex
            .filter { (_, name) -> pred(name) }
            .keys

        for (token in unresolved) {
            val lower = token.lowercase()
            val exact = resolveByPredicate { it.equals(token, ignoreCase = true) }
            val matches = if (exact.isNotEmpty()) exact
            else resolveByPredicate { it.lowercase().contains(lower) || it.lowercase().startsWith(lower) }

            if (matches.isNotEmpty()) resolved.addAll(matches) else unresolved.add(token)
        }

        return Pair(resolved, unresolved)
    }

    // Fetch sprint info by IDs
    private fun fetchSprintInfoByIds(projectKey: String, sprintIds: List<Int>): List<SprintInfo> {
        val result = mutableListOf<SprintInfo>()
        val boards = fetchBoardsForProject(projectKey)

        for (boardId in boards) {
            val allSprints = fetchSprintsForBoard(boardId)
            for (sprintObj in allSprints) {
                val sprintInfo = toSprintInfo(sprintObj)
                if (sprintInfo != null && sprintIds.contains(sprintInfo.id)) {
                    result.add(sprintInfo)
                }
            }
        }

        return result.distinctBy { it.id }
    }

    private fun fetchBoardsForProject(projectKey: String): List<Int> {
        val boards = mutableListOf<Int>()
        var startAt = 0
        val maxResults = 50
        while (true) {
            val url = "${credentials.baseUrl.trimEnd('/')}/rest/agile/1.0/board?projectKeyOrId=${encode(projectKey)}&startAt=$startAt&maxResults=$maxResults"
            val resp = httpGet(url)
            if (resp.status !in 200..299) break
            val obj = json.parseToJsonElement(resp.body).jsonObject
            val values = obj["values"]?.jsonArray ?: JsonArray(emptyList())
            for (v in values) {
                val id = v.jsonObject["id"]?.jsonPrimitive?.intOrNull
                if (id != null) boards.add(id)
            }
            val isLast = obj["isLast"]?.jsonPrimitive?.booleanOrNull
            if (values.isEmpty() || isLast == true) break
            startAt += values.size
        }
        return boards
    }

    private fun fetchSprintsForBoard(boardId: Int): List<JsonObject> {
        val sprints = mutableListOf<JsonObject>()
        var startAt = 0
        val maxResults = 50
        while (true) {
            val url = "${credentials.baseUrl.trimEnd('/')}/rest/agile/1.0/board/$boardId/sprint?state=active,closed,future&startAt=$startAt&maxResults=$maxResults"
            val resp = httpGet(url)
            if (resp.status !in 200..299) break
            val obj = json.parseToJsonElement(resp.body).jsonObject
            val values = obj["values"]?.jsonArray ?: JsonArray(emptyList())
            for (v in values) {
                sprints.add(v.jsonObject)
            }
            val isLast = obj["isLast"]?.jsonPrimitive?.booleanOrNull
            if (values.isEmpty() || isLast == true) break
            startAt += values.size
        }
        return sprints
    }

    private fun fetchActiveAndFutureSprintsForProject(projectKey: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val boards = fetchBoardsForProject(projectKey)
        if (boards.isEmpty()) return result
        for (b in boards) {
            val sprints = fetchSprintsForBoard(b).filter { s ->
                val state = s["state"]?.jsonPrimitive?.content?.lowercase()
                state == "active" || state == "future"
            }
            result.addAll(sprints)
        }
        return result
    }

    private fun toSprintInfo(obj: JsonObject): SprintInfo? {
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.content ?: ""
        val state = obj["state"]?.jsonPrimitive?.content ?: ""
        val start = obj["startDate"]?.jsonPrimitive?.contentOrNull
        val end = obj["endDate"]?.jsonPrimitive?.contentOrNull
        val goal = obj["goal"]?.jsonPrimitive?.contentOrNull
        val boardId = obj["originBoardId"]?.jsonPrimitive?.intOrNull
        return SprintInfo(
            id = id,
            name = name,
            state = state,
            startDate = start,
            endDate = end,
            goal = goal,
            boardId = boardId
        )
    }

    private fun parseIssue(issueObj: JsonObject, epicLinkFieldId: String?): IssueSummary {
        val id = issueObj["id"]?.jsonPrimitive?.content ?: ""
        val key = issueObj["key"]?.jsonPrimitive?.content ?: ""
        val fields = issueObj["fields"]?.jsonObject ?: JsonObject(emptyMap())
        val summary = fields["summary"]?.jsonPrimitive?.content ?: ""
        val type = fields["issuetype"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
        val status = fields["status"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
        val parentKey = fields["parent"]?.jsonObject?.get("key")?.jsonPrimitive?.content

        // Derive epicKey robustly across company-managed and team-managed projects
        val epicKey: String? = when {
            // Company-managed: custom Epic Link field
            epicLinkFieldId != null && fields[epicLinkFieldId] != null -> fields[epicLinkFieldId]!!.jsonPrimitive.contentOrNull
            // Team-managed: 'epic' field is an object with a 'key'
            fields["epic"] is JsonObject -> fields["epic"]!!.jsonObject["key"]?.jsonPrimitive?.contentOrNull
            else -> null
        }

        val descriptionText = fields["description"]?.let { adf -> adfToPlainText(adf) } ?: ""
        return IssueSummary(
            id = id,
            key = key,
            type = type,
            summary = summary,
            status = status,
            description = descriptionText,
            epicKey = epicKey,
            parentKey = parentKey
        )
    }

    private fun resolveEpicLinkFieldId(root: JsonObject): String? {
        val names = root["names"]?.jsonObject ?: return null
        for ((fieldId, displayNameElement) in names) {
            val display = displayNameElement.jsonPrimitive.content
            if (display.equals("Epic Link", ignoreCase = true)) return fieldId
        }
        return null
    }

    // Convert Jira ADF (Atlassian Document Format) JSON into plain text
    private fun adfToPlainText(element: JsonElement): String {
        val sb = StringBuilder()
        fun walk(el: JsonElement?) {
            when (el) {
                is JsonObject -> {
                    val type = el["type"]?.jsonPrimitive?.content
                    when (type) {
                        "text" -> sb.append(el["text"]?.jsonPrimitive?.content ?: "")
                        "paragraph", "heading", "blockquote" -> {
                            el["content"]?.jsonArray?.forEach { walk(it) }
                            sb.append('\n')
                        }
                        "hardBreak" -> sb.append('\n')
                        "listItem" -> {
                            el["content"]?.jsonArray?.forEach { walk(it) }
                            sb.append('\n')
                        }
                        "bulletList", "orderedList" -> {
                            el["content"]?.jsonArray?.forEach { walk(it) }
                        }
                        "codeBlock" -> {
                            val content = el["content"]?.jsonArray
                            if (content != null) {
                                content.forEach { c ->
                                    if (c is JsonObject && c["type"]?.jsonPrimitive?.content == "text") {
                                        sb.append(c["text"]?.jsonPrimitive?.content ?: "")
                                    }
                                }
                            }
                            sb.append('\n')
                        }
                        else -> {
                            el["content"]?.jsonArray?.forEach { walk(it) }
                        }
                    }
                }
                is JsonArray -> el.forEach { walk(it) }
                else -> {}
            }
        }
        walk(element)
        return sb.toString().trim()
    }

    private fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    // Fetch issues via Agile API for given sprint IDs; returns issues and Epic Link field id if resolvable
    private fun fetchIssuesByAgileSprint(sprintIds: Set<Int>, fields: List<String>): Pair<List<JsonObject>, String?> {
        val aggregated = LinkedHashMap<String, JsonObject>()
        var epicFieldId: String? = null
        val base = credentials.baseUrl.trimEnd('/')
        for (sid in sprintIds) {
            var startAt = 0
            val maxResults = 100
            while (true) {
                val url = "$base/rest/agile/1.0/sprint/$sid/issue?startAt=$startAt&maxResults=$maxResults&fields=${encode(fields.joinToString(","))}&expand=names"
                val resp = httpGet(url)
                if (resp.status !in 200..299) break
                val root = json.parseToJsonElement(resp.body).jsonObject
                if (epicFieldId == null) epicFieldId = resolveEpicLinkFieldId(root)
                val arr = root["issues"]?.jsonArray ?: JsonArray(emptyList())
                for (e in arr) {
                    val obj = e.jsonObject
                    val key = obj["key"]?.jsonPrimitive?.content
                    if (key != null) aggregated[key] = obj
                }
                if (arr.isEmpty()) break
                val total = root["total"]?.jsonPrimitive?.int ?: (startAt + arr.size)
                startAt += arr.size
                if (startAt >= total) break
            }
        }
        return Pair(aggregated.values.toList(), epicFieldId)
    }
}
