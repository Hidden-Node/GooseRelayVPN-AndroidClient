package com.gooserelay.gooserelayvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourceParityTest {

    private fun resolveResDir(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var dir: File? = File(userDir).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/res/values/strings.xml")
            if (candidate.exists()) return candidate.parentFile!!.parentFile!!
            val candidateApp = File(dir, "app/src/main/res/values/strings.xml")
            if (candidateApp.exists()) return candidateApp.parentFile!!.parentFile!!
            val candidateSub = File(dir, "android/app/src/main/res/values/strings.xml")
            if (candidateSub.exists()) return candidateSub.parentFile!!.parentFile!!
            dir = dir.parentFile
        }
        error("Could not locate res directory starting from $userDir")
    }

    private data class StringEntry(val name: String, val text: String, val translatable: Boolean)

    private fun parseStringsXml(file: File): Map<String, StringEntry> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val nodeList = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, StringEntry>()
        for (i in 0 until nodeList.length) {
            val node = nodeList.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                val name = element.getAttribute("name")
                val translatable = element.getAttribute("translatable") != "false"
                val text = element.textContent ?: ""
                result[name] = StringEntry(name, text, translatable)
            }
        }
        return result
    }

    private val placeholderRegex = Regex("%(\\d+\\\$)?[sd]")

    @Test
    fun testResourceParityAcrossLocales() {
        val resDir = resolveResDir()
        val baseStrings = parseStringsXml(File(resDir, "values/strings.xml"))
        val locales = listOf("values-fa", "values-zh-rCN", "values-ru")

        for (locale in locales) {
            val localeFile = File(resDir, "$locale/strings.xml")
            assertTrue("Missing locale strings file: ${localeFile.absolutePath}", localeFile.exists())
            val localeStrings = parseStringsXml(localeFile)

            for ((name, baseEntry) in baseStrings) {
                if (!baseEntry.translatable) continue

                assertTrue(
                    "Locale $locale is missing string resource '$name'",
                    localeStrings.containsKey(name)
                )

                val localizedEntry = localeStrings[name]!!
                val basePlaceholders = placeholderRegex.findAll(baseEntry.text).map { it.value }.sorted().toList()
                val locPlaceholders = placeholderRegex.findAll(localizedEntry.text).map { it.value }.sorted().toList()

                assertEquals(
                    "Placeholder mismatch in $locale for string '$name'",
                    basePlaceholders,
                    locPlaceholders
                )
            }
        }
    }
}
