package io.legado.app.model.analyzeRule

import io.legado.app.data.entities.BookSource
import java.util.regex.Pattern

/**
 * 规则预校验器 — 运行前检查语法，提前发现问题
 */
object RuleValidator {

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    fun validate(ruleStr: String?, ruleType: String = ""): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (ruleStr.isNullOrBlank()) return ValidationResult(true)

        // 检查{{}}配对
        val openCount = ruleStr.count { it == '{' }
        val closeCount = ruleStr.count { it == '}' }
        if (openCount % 2 != 0 || closeCount % 2 != 0) {
            warnings.add("{{}}花括号不配对")
        }

        // 检查##正则
        val parts = ruleStr.split("##")
        if (parts.size > 4) warnings.add("##分隔超过4段")
        if (parts.size > 1 && parts[1].isNotBlank()) {
            try { Pattern.compile(parts[1]) }
            catch (e: Exception) { errors.add("正则错误: ${e.message}") }
        }

        // 检查@put/@get配对
        val putCount = Regex("@put:", RegexOption.IGNORE_CASE).findAll(ruleStr).count()
        val getCount = Regex("@get:", RegexOption.IGNORE_CASE).findAll(ruleStr).count()
        if (putCount > 0 && getCount == 0) warnings.add("有@put无@get，可能遗漏取值")

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    fun validateBookSource(source: BookSource): ValidationResult {
        val allErrors = mutableListOf<String>()
        val allWarnings = mutableListOf<String>()
        fun check(rule: String?, name: String) {
            val r = validate(rule, name)
            allErrors.addAll(r.errors.map { "$name: $it" })
            allWarnings.addAll(r.warnings.map { "$name: $it" })
        }
        source.ruleSearch?.let {
            check(it.bookList, "搜索.bookList"); check(it.name, "搜索.name")
            check(it.author, "搜索.author"); check(it.bookUrl, "搜索.bookUrl")
        }
        source.ruleBookInfo?.let {
            check(it.name, "详情.name"); check(it.author, "详情.author")
            check(it.intro, "详情.intro"); check(it.tocUrl, "详情.tocUrl")
        }
        source.ruleToc?.let {
            check(it.chapterList, "目录.chapterList")
            check(it.chapterName, "目录.chapterName")
            check(it.chapterUrl, "目录.chapterUrl")
            check(it.nextTocUrl, "目录.nextTocUrl")
        }
        source.ruleContent?.let {
            check(it.content, "正文.content")
            check(it.nextContentUrl, "正文.nextContentUrl")
        }
        return ValidationResult(allErrors.isEmpty(), allErrors, allWarnings)
    }
}
