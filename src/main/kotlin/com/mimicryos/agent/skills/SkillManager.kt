package com.mimicryos.agent.skills

import com.mimicryos.agent.core.Message
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SkillManager(private val skillsDir: String) {
    private val skills = ConcurrentHashMap<String, Skill>()
    private val skillDirs = mutableListOf<File>()

    init {
        loadFromDir(File(skillsDir))
        loadFromDir(File(System.getProperty("user.home"), ".mimicry/skills"))
        loadFromDir(File(skillsDir, "../commands"))
    }

    private fun loadFromDir(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.walk().maxDepth(3).forEach { file -> if (file.isFile && (file.name.endsWith(".md") || file.name.endsWith(".markdown"))) loadSkill(file) }
    }

    private fun loadSkill(file: File) {
        try { val content = file.readText(); val skill = parseSkill(file.nameWithoutExtension, content, file.parentFile.path); skills[skill.name] = skill } catch (e: Exception) {}
    }

    private fun parseSkill(name: String, content: String, dir: String): Skill {
        val (frontmatter, body) = parseFrontmatter(content)
        return Skill(name = name, description = frontmatter["description"] as? String ?: "", content = body, dir = dir, triggers = (frontmatter["triggers"] as? List<*>)?.map { it.toString() } ?: emptyList(), whenToUse = frontmatter["whenToUse"] as? String, disableModelInvocation = frontmatter["disableModelInvocation"] as? Boolean ?: false)
    }

    private fun parseFrontmatter(content: String): Pair<Map<String, Any>, String> {
        if (!content.startsWith("---")) return emptyMap<String, Any>() to content
        val end = content.indexOf("---", 3); if (end == -1) return emptyMap<String, Any>() to content
        val frontmatterText = content.substring(3, end).trim(); val body = content.substring(end + 3).trim()
        val frontmatter = mutableMapOf<String, Any>()
        frontmatterText.lines().forEach { line -> val colon = line.indexOf(':'); if (colon > 0) { val key = line.substring(0, colon).trim(); val value = line.substring(colon + 1).trim(); frontmatter[key] = value.trim('"').trim('[').trim(']') } }
        return frontmatter to body
    }

    fun getSkill(name: String): Skill? = skills[name]
    fun getAllSkills(): List<Skill> = skills.values.toList()
    fun getActiveSkills(): List<Skill> = skills.values.filter { !it.disableModelInvocation }.toList()
    fun buildPrompt(): String {
        val sb = StringBuilder(); val active = getActiveSkills(); if (active.isEmpty()) return ""
        sb.append("## Available Skills\n\n"); active.forEach { skill -> sb.append("### ${skill.name}\n"); skill.description?.let { sb.append("Description: $it\n") }; skill.whenToUse?.let { sb.append("When to use: $it\n") }; sb.append("\n") }
        return sb.toString()
    }

    suspend fun executeSkill(skillName: String, input: String): SkillExecutionResult {
        val skill = getSkill(skillName) ?: return SkillExecutionResult(false, "Skill not found: $skillName", "")
        val scriptDir = File(skill.dir); val scripts = scriptDir.listFiles { f -> f.name.endsWith(".sh") || f.name.endsWith(".py") } ?: emptyArray()
        val outputs = mutableListOf<String>()
        for (script in scripts) { try { val process = ProcessBuilder(script.absolutePath).directory(scriptDir).redirectErrorStream(true).start(); val output = process.inputStream.readAllBytes().toString(java.nio.charset.StandardCharsets.UTF_8); process.waitFor(); outputs.add(output) } catch (e: Exception) { outputs.add("Script error: ${e.message}") } }
        return SkillExecutionResult(true, skill.content, skill.content)
    }

    data class Skill(val name: String, val description: String, val content: String, val dir: String, val triggers: List<String> = emptyList(), val whenToUse: String? = null, val disableModelInvocation: Boolean = false)
    data class SkillExecutionResult(val success: Boolean, val message: String, val output: String)
}
