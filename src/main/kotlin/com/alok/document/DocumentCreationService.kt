package com.alok.document

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
data class DocumentCreationResult(
    val success: Boolean,
    val documentId: String,
    val fileName: String,
    val filePath: String,
    val downloadUrl: String,
    val message: String,
    val timestamp: String
)

@Serializable
data class DocumentMetadata(
    val title: String,
    val author: String = "Test Documentation Generator",
    val projectName: String,
    val sprintId: String,
    val documentType: String,
    val createdAt: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
)

class DocumentCreationService : ToolSet {

    private val documentsDir = File("generated-documents")
    private val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

    init {
        // Ensure documents directory exists
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }
    }

    @Tool
    @LLMDescription("Create a Test Strategy document file with the provided content. This tool will save the document as both Markdown and generate a downloadable file.")
    fun createTestStrategyDocument(
        @LLMDescription("The complete content of the Test Strategy document in Markdown format")
        content: String,
        @LLMDescription("Project name for the document")
        projectName: String,
        @LLMDescription("Sprint ID for the document")
        sprintId: String,
        @LLMDescription("Document title")
        title: String = "Test Strategy Document"
    ): DocumentCreationResult {
        return createDocument(
            content = content,
            documentType = "Test-Strategy",
            projectName = projectName,
            sprintId = sprintId,
            title = title
        )
    }

    @Tool
    @LLMDescription("Create a Test Approach document file with the provided content. This tool will save the document as both Markdown and generate a downloadable file.")
    fun createTestApproachDocument(
        @LLMDescription("The complete content of the Test Approach document in Markdown format")
        content: String,
        @LLMDescription("Project name for the document")
        projectName: String,
        @LLMDescription("Sprint ID for the document")
        sprintId: String,
        @LLMDescription("Document title")
        title: String = "Test Approach Document"
    ): DocumentCreationResult {
        return createDocument(
            content = content,
            documentType = "Test-Approach",
            projectName = projectName,
            sprintId = sprintId,
            title = title
        )
    }

    @Tool
    @LLMDescription("Create a Test Plan document file with the provided content. This tool will save the document as both Markdown and generate a downloadable file.")
    fun createTestPlanDocument(
        @LLMDescription("The complete content of the Test Plan document in Markdown format")
        content: String,
        @LLMDescription("Project name for the document")
        projectName: String,
        @LLMDescription("Sprint ID for the document")
        sprintId: String,
        @LLMDescription("Document title")
        title: String = "Test Plan Document"
    ): DocumentCreationResult {
        return createDocument(
            content = content,
            documentType = "Test-Plan",
            projectName = projectName,
            sprintId = sprintId,
            title = title
        )
    }

    @Tool
    @LLMDescription("List all created documents and their download links")
    fun listCreatedDocuments(): List<DocumentCreationResult> {
        val results = mutableListOf<DocumentCreationResult>()

        documentsDir.listFiles()?.filter { it.isFile && it.extension == "md" }?.forEach { file ->
            results.add(
                DocumentCreationResult(
                    success = true,
                    documentId = file.nameWithoutExtension,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    downloadUrl = "file://${file.absolutePath}",
                    message = "Document available for download",
                    timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )
            )
        }

        return results
    }

    private fun createDocument(
        content: String,
        documentType: String,
        projectName: String,
        sprintId: String,
        title: String
    ): DocumentCreationResult {
        return try {
            val documentId = UUID.randomUUID().toString().substring(0, 8)
            val sanitizedProjectName = projectName.replace(Regex("[^a-zA-Z0-9]"), "-")
            val sanitizedSprintId = sprintId.replace(Regex("[^a-zA-Z0-9]"), "-")

            val fileName = "${documentType}_${sanitizedProjectName}_${sanitizedSprintId}_${timestamp}.md"
            val file = File(documentsDir, fileName)

            // Create document metadata
            val metadata = DocumentMetadata(
                title = title,
                projectName = projectName,
                sprintId = sprintId,
                documentType = documentType
            )

            // Prepare final document content with metadata header
            val finalContent = buildString {
                appendLine("---")
                appendLine("title: ${metadata.title}")
                appendLine("author: ${metadata.author}")
                appendLine("project: ${metadata.projectName}")
                appendLine("sprint: ${metadata.sprintId}")
                appendLine("document_type: ${metadata.documentType}")
                appendLine("created_at: ${metadata.createdAt}")
                appendLine("document_id: $documentId")
                appendLine("---")
                appendLine()
                append(content)
            }

            // Write the file
            file.writeText(finalContent)

            // Also create an HTML version for better viewing
            val htmlFileName = "${documentType}_${sanitizedProjectName}_${sanitizedSprintId}_${timestamp}.html"
            val htmlFile = File(documentsDir, htmlFileName)
            createHtmlVersion(finalContent, htmlFile, metadata)

            println("✅ Document created successfully:")
            println("   📄 Markdown: ${file.absolutePath}")
            println("   🌐 HTML: ${htmlFile.absolutePath}")
            println("   📁 Document ID: $documentId")

            DocumentCreationResult(
                success = true,
                documentId = documentId,
                fileName = fileName,
                filePath = file.absolutePath,
                downloadUrl = "file://${file.absolutePath}",
                message = "Document created successfully. Available at: ${file.absolutePath}",
                timestamp = metadata.createdAt
            )

        } catch (e: Exception) {
            val errorMessage = "Failed to create document: ${e.message}"
            println("❌ $errorMessage")
            e.printStackTrace()

            DocumentCreationResult(
                success = false,
                documentId = "",
                fileName = "",
                filePath = "",
                downloadUrl = "",
                message = errorMessage,
                timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        }
    }

    private fun createHtmlVersion(markdownContent: String, htmlFile: File, metadata: DocumentMetadata) {
        val htmlContent = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${metadata.title}</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            line-height: 1.6;
            max-width: 1200px;
            margin: 0 auto;
            padding: 20px;
            color: #333;
        }
        .header {
            background: #f8f9fa;
            padding: 20px;
            border-radius: 8px;
            margin-bottom: 30px;
            border-left: 4px solid #007bff;
        }
        .metadata {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 10px;
            font-size: 0.9em;
            color: #666;
        }
        h1 { color: #2c3e50; border-bottom: 3px solid #3498db; padding-bottom: 10px; }
        h2 { color: #34495e; border-bottom: 2px solid #ecf0f1; padding-bottom: 8px; }
        h3 { color: #7f8c8d; }
        .content {
            white-space: pre-wrap;
            font-family: inherit;
        }
        table {
            border-collapse: collapse;
            width: 100%;
            margin: 20px 0;
        }
        table, th, td {
            border: 1px solid #ddd;
        }
        th, td {
            padding: 12px;
            text-align: left;
        }
        th {
            background-color: #f2f2f2;
        }
        .download-info {
            background: #e8f5e8;
            padding: 15px;
            border-radius: 5px;
            margin-top: 30px;
            border-left: 4px solid #28a745;
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>${metadata.title}</h1>
        <div class="metadata">
            <div><strong>Project:</strong> ${metadata.projectName}</div>
            <div><strong>Sprint:</strong> ${metadata.sprintId}</div>
            <div><strong>Document Type:</strong> ${metadata.documentType}</div>
            <div><strong>Created:</strong> ${metadata.createdAt}</div>
            <div><strong>Author:</strong> ${metadata.author}</div>
        </div>
    </div>
    
    <div class="content">
${markdownContent.replace(Regex("^---[\\s\\S]*?---\\s*"), "")}
    </div>
    
    <div class="download-info">
        <strong>📥 Download Options:</strong><br>
        • <strong>Markdown Version:</strong> Available in the same directory<br>
        • <strong>HTML Version:</strong> This file can be saved or printed as PDF<br>
        • <strong>Location:</strong> ${htmlFile.absolutePath}
    </div>
</body>
</html>
        """.trimIndent()

        htmlFile.writeText(htmlContent)
    }
}
