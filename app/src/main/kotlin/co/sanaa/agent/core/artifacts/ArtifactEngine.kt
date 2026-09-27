package co.sanaa.agent.core.artifacts

import co.sanaa.agent.core.Redactor
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deliverable formats Amara produces without external runtime dependencies (Phase C). */
enum class ArtifactFormat { MARKDOWN_REPORT, CSV_SPREADSHEET, STRUCTURED_JSON, PDF_DOCUMENT, PPTX_PRESENTATION, PNG_IMAGE }

data class ArtifactSection(val heading: String, val body: String)

/**
 * Deterministic chart image specification (MISSION artifact tools: "images").
 * Rendered as a real PNG (signature + IHDR + IEND + zlib IDAT) by [PngWriter] with
 * no external runtime dependency. Dimensions and data are bounded; the rubric
 * rejects malformed input before any bytes are produced.
 */
data class ChartImageSpec(
    val widthPx: Int,
    val heightPx: Int,
    /** What the bars measure, e.g. "Weekly revenue (UGX)". Never rendered into pixels. */
    val seriesLabel: String,
    /** Non-negative bar values in series order. */
    val values: List<Double>,
    val barColorRgb: Int = 0x2C7873,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Image dimensions must be positive" }
        require(widthPx <= MAX_DIMENSION_PX && heightPx <= MAX_DIMENSION_PX) {
            "Image dimensions exceed the ${MAX_DIMENSION_PX}px limit"
        }
        require(widthPx.toLong() * heightPx <= MAX_PIXELS) { "Image exceeds the ${MAX_PIXELS}-pixel budget" }
        require(values.isNotEmpty()) { "A chart needs at least one value" }
        require(values.all { it.isFinite() && it >= 0.0 }) { "Chart values must be finite and non-negative" }
        require(seriesLabel.isNotBlank()) { "A chart names its series" }
        require(barColorRgb in 0..0xFFFFFF) { "Bar color must be RGB" }
    }

    companion object {
        const val MAX_DIMENSION_PX = 4_096
        const val MAX_PIXELS = 4_194_304L
    }
}

data class SpreadsheetSpec(
    val sheetName: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    /** Optional typed column schema: name → number|text. Unlisted columns are text. */
    val columnTypes: Map<String, String> = emptyMap(),
    /** Column index expected to hold a numeric total row at the end, if any. */
    val totalRowColumns: Set<Int> = emptySet(),
    /** Values below this fraction of the column median are flagged as anomalies. */
    val anomalyThresholdFactor: Double = 0.5,
)

data class ArtifactSpec(
    val title: String,
    val format: ArtifactFormat,
    val sections: List<ArtifactSection>,
    val spreadsheet: SpreadsheetSpec? = null,
    val jsonPayload: String = "",
    val requiredHeadings: List<String> = emptyList(),
    val image: ChartImageSpec? = null,
) {
    /** Back-compat accessors used by existing call sites/tests. */
    val csvColumns: List<String> get() = spreadsheet?.columns ?: emptyList()
    val csvRows: List<List<String>> get() = spreadsheet?.rows ?: emptyList()

    /** Full-fidelity JSON form used by the durable artifact store (round-trips exactly). */
    fun toJson(): org.json.JSONObject {
        fun sectionJson(s: ArtifactSection) = org.json.JSONObject().put("heading", s.heading).put("body", s.body)
        return org.json.JSONObject().apply {
            put("title", title)
            put("format", format.name)
            put("sections", org.json.JSONArray(sections.map { sectionJson(it) }))
            spreadsheet?.let { sheet ->
                put("spreadsheet", org.json.JSONObject().apply {
                    put("sheetName", sheet.sheetName)
                    put("columns", org.json.JSONArray(sheet.columns))
                    put("rows", org.json.JSONArray(sheet.rows.map { org.json.JSONArray(it) }))
                    put("columnTypes", org.json.JSONObject(sheet.columnTypes))
                    put("totalRowColumns", org.json.JSONArray(sheet.totalRowColumns.sorted()))
                    put("anomalyThresholdFactor", sheet.anomalyThresholdFactor)
                })
            }
            if (jsonPayload.isNotEmpty()) put("jsonPayload", jsonPayload)
            if (requiredHeadings.isNotEmpty()) put("requiredHeadings", org.json.JSONArray(requiredHeadings))
            image?.let { img ->
                put("image", org.json.JSONObject().apply {
                    put("widthPx", img.widthPx); put("heightPx", img.heightPx)
                    put("seriesLabel", img.seriesLabel)
                    put("values", org.json.JSONArray(img.values))
                    put("barColorRgb", img.barColorRgb)
                })
            }
        }
    }

    companion object {
        fun fromJson(json: org.json.JSONObject): ArtifactSpec = ArtifactSpec(
            title = json.getString("title"),
            format = ArtifactFormat.valueOf(json.getString("format")),
            sections = json.optJSONArray("sections")?.let { array ->
                (0 until array.length()).map { i ->
                    val s = array.getJSONObject(i); ArtifactSection(s.getString("heading"), s.getString("body"))
                }
            } ?: emptyList(),
            spreadsheet = json.optJSONObject("spreadsheet")?.let { sheet ->
                SpreadsheetSpec(
                    sheetName = sheet.getString("sheetName"),
                    columns = sheet.getJSONArray("columns").let { a -> (0 until a.length()).map { a.getString(it) } },
                    rows = sheet.getJSONArray("rows").let { a ->
                        (0 until a.length()).map { r -> a.getJSONArray(r).let { b -> (0 until b.length()).map { b.getString(it) } } }
                    },
                    columnTypes = sheet.optJSONObject("columnTypes")?.let { ct ->
                        ct.keys().asSequence().associateWith { ct.getString(it) }
                    } ?: emptyMap(),
                    totalRowColumns = sheet.optJSONArray("totalRowColumns")?.let { a ->
                        (0 until a.length()).mapTo(mutableSetOf()) { a.getInt(it) }
                    } ?: emptySet(),
                    anomalyThresholdFactor = sheet.optDouble("anomalyThresholdFactor", 0.5),
                )
            },
            jsonPayload = json.optString("jsonPayload", ""),
            requiredHeadings = json.optJSONArray("requiredHeadings")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            image = json.optJSONObject("image")?.let { img ->
                ChartImageSpec(
                    widthPx = img.getInt("widthPx"),
                    heightPx = img.getInt("heightPx"),
                    seriesLabel = img.getString("seriesLabel"),
                    values = img.getJSONArray("values").let { a -> (0 until a.length()).map { a.getDouble(it) } },
                    barColorRgb = img.optInt("barColorRgb", 0x2C7873),
                )
            },
        )

        fun specJsonOf(spec: ArtifactSpec): String = spec.toJson().toString()
        fun parseSpecJson(raw: String): ArtifactSpec? =
            runCatching { fromJson(org.json.JSONObject(raw)) }.getOrNull()
    }
}

data class RubricResult(
    val passed: Boolean,
    val failures: List<String>,
)

/**
 * Role-specific quality rubric applied before any artifact ships. Dimensions:
 * factuality (unproven certainty markers), privacy (secret shapes), completeness
 * (required sections), formatting (non-empty, length caps), arithmetic re-check,
 * tone (banned corporate filler + required warmth markers for customer-facing copy),
 * and format-specific structural checks including spreadsheet totals/anomalies.
 */
object ArtifactRubric {

    private val certaintyMarkers = listOf("obviously", "everyone knows", "guaranteed", "always works")
    private val bannedTonePhrases = listOf(
        "seamless", "leverage", "innovative", "synergy", "cutting-edge",
        "world-class", "best-in-class", "revolutionary",
    )

    fun evaluate(spec: ArtifactSpec): RubricResult {
        val failures = mutableListOf<String>()
        failures += factualityFailures(spec)
        failures += privacyFailures(spec)
        failures += completenessFailures(spec)
        failures += formattingFailures(spec)
        failures += toneFailures(spec)
        failures += structureFailures(spec)
        return RubricResult(passed = failures.isEmpty(), failures = failures)
    }

    fun factualityFailures(spec: ArtifactSpec): List<String> {
        val text = spec.sections.joinToString("\n") { "${it.heading}\n${it.body}" }.lowercase()
        return certaintyMarkers.filter { it in text }.map { "Unproven certainty marker: '$it'" }
    }

    fun privacyFailures(spec: ArtifactSpec): List<String> {
        val allText = spec.sections.joinToString("\n") { "${it.heading}\n${it.body}" } +
            spec.spreadsheet?.rows?.joinToString("|") { it.joinToString(",") }.orEmpty() + spec.jsonPayload +
            (spec.image?.let { it.seriesLabel } ?: "")
        return if (Redactor.containsSecretShape(allText)) listOf("Secret-shaped text detected in artifact") else emptyList()
    }

    fun completenessFailures(spec: ArtifactSpec): List<String> =
        spec.requiredHeadings.filter { required ->
            spec.sections.none { it.heading.equals(required, true) }
        }.map { "Missing required section: $it" }

    fun formattingFailures(spec: ArtifactSpec): List<String> = buildList {
        spec.sections.forEach { section ->
            if (section.heading.isBlank()) add("Blank heading")
            if (section.body.isBlank()) add("Empty body under '${section.heading}'")
            if (section.body.split('\n').size > 200) add("Section '${section.heading}' exceeds 200 lines")
        }
    }

    /** Tone dimension: filler words are banned; customer-facing sections must carry one warm marker. */
    fun toneFailures(spec: ArtifactSpec): List<String> {
        val failures = mutableListOf<String>()
        val joined = spec.sections.joinToString("\n") { "${it.heading}\n${it.body}" }.lowercase()
        bannedTonePhrases.forEach { phrase -> if (phrase in joined) failures += "Banned tone word: '$phrase'" }
        val customerFacing = spec.sections.any {
            it.heading.contains("customer", true) || it.heading.contains("reply", true) || it.heading.contains("message", true)
        }
        if (customerFacing) {
            val warm = listOf("thank", "welcome", "happy to", "sorry for", "please").any { it in joined }
            if (!warm) failures += "Customer-facing artifact lacks a warm acknowledgment"
        }
        return failures
    }

    /** Re-computes simple additive arithmetic claims inside section bodies. */
    fun calculationFailures(spec: ArtifactSpec): List<String> {
        val regex = Regex("(\\d{1,9})\\s*\\+\\s*(\\d{1,9})\\s*=\\s*(\\d{1,9})")
        return spec.sections.flatMap { section ->
            regex.findAll(section.body).mapNotNull { match ->
                val (a, b, c) = match.destructured
                if (a.toLong() + b.toLong() != c.toLong()) "Wrong arithmetic in '${section.heading}': ${match.value}"
                else null
            }.toList()
        }
    }

    fun structureFailures(spec: ArtifactSpec): List<String> = when (spec.format) {
        ArtifactFormat.CSV_SPREADSHEET -> spreadsheetFailures(spec)
        ArtifactFormat.PDF_DOCUMENT -> if (spec.sections.isEmpty()) listOf("PDF needs at least one section") else emptyList()
        ArtifactFormat.PPTX_PRESENTATION -> if (spec.title.isBlank()) listOf("Presentation needs a title slide heading") else emptyList()
        ArtifactFormat.PNG_IMAGE -> imageFailures(spec)
        ArtifactFormat.STRUCTURED_JSON ->
            if (runCatching { org.json.JSONObject(spec.jsonPayload) }.isFailure) listOf("Structured JSON artifact is not valid JSON") else emptyList()
        ArtifactFormat.MARKDOWN_REPORT -> emptyList()
    }

    /**
     * Image structural checks. Malformed input (zero/negative or oversized dimensions,
     * empty or negative values, blank series label, missing chart definition) fails the
     * rubric before any rendering happens.
     */
    fun imageFailures(spec: ArtifactSpec): List<String> {
        val failures = mutableListOf<String>()
        val img = spec.image ?: return listOf("PNG artifact requires a chart image definition")
        if (img.widthPx <= 0 || img.heightPx <= 0) failures += "Image dimensions must be positive"
        if (img.widthPx > ChartImageSpec.MAX_DIMENSION_PX || img.heightPx > ChartImageSpec.MAX_DIMENSION_PX) {
            failures += "Image dimensions exceed the ${ChartImageSpec.MAX_DIMENSION_PX}px limit"
        }
        if (img.widthPx.toLong() * img.heightPx.toLong() > ChartImageSpec.MAX_PIXELS) failures += "Image exceeds the pixel budget"
        if (img.values.isEmpty()) failures += "Chart needs at least one value"
        if (img.values.any { !it.isFinite() || it < 0 }) failures += "Chart values must be finite and non-negative"
        if (img.seriesLabel.isBlank()) failures += "Chart series label is required"
        return failures.distinct()
    }

    /**
     * Spreadsheet structural checks: column count consistency, declared types,
     * total-row arithmetic, and low-value anomaly detection against the column median.
     */
    fun spreadsheetFailures(spec: ArtifactSpec): List<String> {
        val sheet = spec.spreadsheet ?: return listOf("CSV artifact requires a spreadsheet definition")
        val failures = mutableListOf<String>()
        if (sheet.columns.isEmpty()) failures += "CSV artifact needs columns"
        if (sheet.columns.any { it.isBlank() }) failures += "CSV contains a blank column header"
        sheet.rows.forEachIndexed { index, row ->
            if (row.size != sheet.columns.size) failures += "Spreadsheet row ${index + 1} has ${row.size} cells, expected ${sheet.columns.size}"
        }
        // Declared types honored.
        sheet.columnTypes.forEach { (column, type) ->
            val index = sheet.columns.indexOfFirst { it.equals(column, true) }
            if (index >= 0 && (type == "number")) {
                sheet.rows.forEachIndexed { rowIndex, row ->
                    val cell = row.getOrNull(index).orEmpty().trim()
                    if (cell.isNotEmpty() && cell.toDoubleOrNull() == null) {
                        failures += "Row ${rowIndex + 1} column '$column' should be numeric, found '$cell'"
                    }
                }
            }
        }
        // Total-row arithmetic.
        if (sheet.totalRowColumns.isNotEmpty() && sheet.rows.isNotEmpty()) {
            val last = sheet.rows.last()
            sheet.totalRowColumns.forEach { columnIndex ->
                val numbers = sheet.rows.dropLast(1).mapNotNull { it.getOrNull(columnIndex)?.trim()?.toDoubleOrNull() }
                val statedTotal = last.getOrNull(columnIndex)?.trim()?.toDoubleOrNull() ?: return@forEach
                val computed = numbers.sum()
                if (numbers.isNotEmpty() && kotlin.math.abs(computed - statedTotal) > 0.001) {
                    failures += "Total mismatch in column '${sheet.columns.getOrElse(columnIndex) { columnIndex }}': stated $statedTotal, rows sum to $computed"
                }
            }
        }
        // Anomaly detection per numeric column.
        sheet.columns.indices.forEach { columnIndex ->
            val values = sheet.rows.mapNotNull { it.getOrNull(columnIndex)?.trim()?.toDoubleOrNull() }
            if (values.size >= 4) {
                val sorted = values.sorted()
                val median = sorted[sorted.size / 2]
                if (median != 0.0) {
                    values.forEach { value ->
                        if (value < median * sheet.anomalyThresholdFactor) {
                            failures += "Anomalous low value $value in column '${sheet.columns[columnIndex]}' (median ${"%.2f".format(median)})"
                        }
                    }
                }
            }
        }
        return failures.distinct()
    }
}

private fun String.csvEscape(): String = "\"${replace("\"", "\"\"")}\""

/**
 * Versioned artifact store abstraction; production implementations persist revisions.
 * The default [InMemoryArtifactStore] serves JVM tests; SQLite-backed store lives in AmaraMemory.
 */
interface ArtifactStore {
    @Throws(IllegalArgumentException::class)
    fun commit(artifactId: String, spec: ArtifactSpec, nowMs: Long, author: String = "amara"): StoredRevision
    fun history(artifactId: String): List<StoredRevision>
    fun latest(artifactId: String): StoredRevision?

    data class StoredRevision(
        val id: Long,
        val artifactId: String,
        val spec: ArtifactSpec,
        val createdAtMs: Long,
        val author: String,
        val parentRevision: Long?,
        val renderedBytesHash: String,
    )
}

class InMemoryArtifactStore : ArtifactStore {
    private val revisions = mutableListOf<ArtifactStore.StoredRevision>()
    private var counter = 0L

    @Synchronized
    override fun commit(artifactId: String, spec: ArtifactSpec, nowMs: Long, author: String): ArtifactStore.StoredRevision {
        val verdict = ArtifactRubric.evaluate(spec)
        require(verdict.passed) { "Artifact failed the quality rubric: ${verdict.failures.joinToString("; ")}" }
        val calcFailures = ArtifactRubric.calculationFailures(spec)
        require(calcFailures.isEmpty()) { "Artifact failed calculation checks: ${calcFailures.joinToString("; ")}" }
        val parent = revisions.filter { it.artifactId == artifactId }.maxByOrNull { it.id }?.id
        counter += 1
        val revision = ArtifactStore.StoredRevision(
            counter, artifactId, spec, nowMs, author, parent,
            renderedBytesHash = ContentDigest.of(ArtifactRenderer.renderBytes(spec)),
        )
        revisions += revision
        return revision
    }

    @Synchronized
    override fun history(artifactId: String): List<ArtifactStore.StoredRevision> =
        revisions.filter { it.artifactId == artifactId }.sortedBy { it.id }

    @Synchronized
    override fun latest(artifactId: String): ArtifactStore.StoredRevision? = history(artifactId).lastOrNull()
}

object ContentDigest {
    fun of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Renders final deliverable bytes for every supported format, including dependency-free
 * PDF 1.4 documents and OOXML (.pptx) presentation packages.
 */
object ArtifactRenderer {

    fun renderBytes(spec: ArtifactSpec): ByteArray = when (spec.format) {
        ArtifactFormat.MARKDOWN_REPORT -> renderMarkdown(spec).toByteArray(StandardCharsets.UTF_8)
        ArtifactFormat.CSV_SPREADSHEET -> renderCsv(spec).toByteArray(StandardCharsets.UTF_8)
        ArtifactFormat.STRUCTURED_JSON -> spec.jsonPayload.toByteArray(StandardCharsets.UTF_8)
        ArtifactFormat.PDF_DOCUMENT -> SimplePdfWriter.write(spec.title, spec.sections)
        ArtifactFormat.PPTX_PRESENTATION -> PptxWriter.write(spec.title, spec.sections)
        ArtifactFormat.PNG_IMAGE -> SimpleChartRenderer.renderPng(requireNotNull(spec.image) { "PNG artifact requires a chart image definition" })
    }

    @Deprecated("Use renderBytes for binary formats", ReplaceWith("renderBytes(spec).toString(Charsets.UTF_8)"))
    fun render(spec: ArtifactSpec): String = renderBytes(spec).toString(StandardCharsets.UTF_8)

    private fun renderMarkdown(spec: ArtifactSpec): String = buildString {
        appendLine("# ${spec.title}")
        spec.sections.forEach { s ->
            appendLine(); appendLine("## ${s.heading}"); appendLine(s.body)
        }
    }.trimEnd() + "\n"

    /**
     * CSV rendering: headers AND cells share identical RFC-4180 quoting/escaping.
     */
    private fun renderCsv(spec: ArtifactSpec): String {
        val sheet = requireNotNull(spec.spreadsheet) { "CSV artifact requires a spreadsheet definition" }
        return buildString {
            appendLine(sheet.columns.joinToString(",") { it.csvEscape() })
            sheet.rows.forEach { row -> appendLine(row.joinToString(",") { cell -> cell.csvEscape() }) }
        }
    }
}

/**
 * Minimal but standards-shaped PDF 1.4 writer: multi-page, WinAnsi Helvetica text,
 * correct xref offsets and byte-count stream lengths. Output starts with `%PDF-` and
 * ends with `%%EOF`; structure is verifiable without external libraries.
 */
object SimplePdfWriter {

    fun write(title: String, sections: List<ArtifactSection>): ByteArray {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        fun sanitize(text: String): String = Redactor.redact(text)
            .replace("\\", "")
            .replace("(", "[")
            .replace(")", "]")
            .map { if (it.code in 32..126 || it in 'À'..'ÿ') it else '?' }
            .joinToString("")
            .take(2_000)

        data class Line(val textLine: String)

        val pages: List<List<Line>> = buildList {
            add(listOf(Line(sanitize(title)), Line("Generated $stamp")))
            sections.forEach { section ->
                val bodyLines = sanitize(section.body).chunked(88)
                add(listOf(Line(sanitize(section.heading))) + bodyLines.map(::Line))
            }
        }

        val objects = mutableListOf<String>()
        fun addObject(content: String): Int {
            objects += content
            return objects.size
        }

        val pageCount = pages.size
        val kidsIds = (0 until pageCount).map { index -> 4 + index * 2 } // each page: obj(page) + obj(contents)
        val catalogId = addObject("<< /Type /Catalog /Pages 2 0 R >>")
        check(catalogId == 1)
        val pagesId = addObject("<< /Type /Pages /Kids [${kidsIds.joinToString(" ") { "$it 0 R" }}] /Count $pageCount >>")
        check(pagesId == 2)
        addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>") // id 3

        pages.forEachIndexed { pageIndex, lines ->
            val contentText = buildString {
                appendLine("BT /F1 11 Tf 50 ${780} Td 14 TL")
                lines.forEach { line -> appendLine("(${line.textLine.replace("\"", "'")}) Tj T*") }
                append("ET")
            }
            val pageId = addObject(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                    "/Resources << /Font << /F1 3 0 R >> >> /Contents ${pageIdOf(pageIndex)} 0 R >>",
            )
            check(pageId == kidsIds[pageIndex])
            addObject("<< /Length ${contentText.toByteArray(StandardCharsets.US_ASCII).size} >>\nstream\n$contentText\nendstream")
        }

        val infoId = objects.size + 1
        objects += "<< /Title (${sanitize(title).replace("\"", "'")}) /Producer (Amara artifact engine) >>"

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(StandardCharsets.US_ASCII))
        val offsets = IntArray(objects.size + 1)
        objects.forEachIndexed { index, content ->
            offsets[index + 1] = out.size()
            out.write("${index + 1} 0 obj\n$content\nendobj\n".toByteArray(StandardCharsets.US_ASCII))
        }
        val xrefPosition = out.size()
        out.write("xref\n0 ${objects.size + 1}\n".toByteArray(StandardCharsets.US_ASCII))
        out.write("0000000000 65535 f \n".toByteArray(StandardCharsets.US_ASCII))
        (1..objects.size).forEach { index ->
            out.write("%010d 00000 n \n".format(offsets[index]).toByteArray(StandardCharsets.US_ASCII))
        }
        out.write(
            ("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Info $infoId 0 R >>\n" +
                "startxref\n$xrefPosition\n%%EOF\n").toByteArray(StandardCharsets.US_ASCII),
        )
        return out.toByteArray()
    }

    private fun pageIdOf(pageIndex: Int): Int = 5 + pageIndex * 2
}

/**
 * OOXML presentation (.pptx) writer producing an OPC package with the standard part
 * layout ([Content_Types].xml, rels, presentation.xml, slide parts, layout, master,
 * theme). Scope note: this generates a structurally valid package whose XML parses and
 * whose slide text round-trips through unzip+parse; it makes no claim beyond that.
 */
object PptxWriter {

    fun write(title: String, sections: List<ArtifactSection>): ByteArray {
        val slides = listOf(SlideContent(sanitizeXml(title), listOf("Generated by Amara"))) +
            sections.map { SlideContent(sanitizeXml(it.heading), sanitizeXml(it.body).split("\n").filter { l -> l.isNotBlank() }.take(8)) }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }

            val overrides = slides.indices.joinToString("") { index ->
                "<Override PartName=\"/ppt/slides/slide${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
            } + "<Override PartName=\"/ppt/notesSlides/notesSlide1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml\"/>"

            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>$overrides
</Types>""")

            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""")

            val slideIds = slides.indices.joinToString("") { index ->
                "<p:sldId id=\"${256 + index}\" r:id=\"rId${index + 2}\"/>"
            }
            put("ppt/presentation.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
<p:sldIdLst>$slideIds</p:sldIdLst>
<p:sldSz cx="9144000" cy="6858000"/></p:presentation>""")

            val presRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>""" +
                slides.indices.joinToString("") { index ->
                    "\n<Relationship Id=\"rId${index + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${index + 1}.xml\"/>"
                } + "\n<Relationship Id=\"rId${slides.size + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>\n</Relationships>"
            put("ppt/_rels/presentation.xml.rels", presRels)

            slides.forEachIndexed { index, slide ->
                val paragraphs = slide.bullets.joinToString("") { bullet ->
                    """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>${bullet}</a:t></a:r></a:p></p:txBody>"""
                }
                put("ppt/slides/slide${index + 1}.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/></p:nvGrpSpPr>
<p:grpSpPr/>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>${slide.title}</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="3" name="Content"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr>
<p:spPr/>$paragraphs</p:sp>
</p:spTree></p:cSld></p:sld>""")
                put("ppt/slides/_rels/slide${index + 1}.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>""")
            }

            put("ppt/slideLayouts/slideLayout1.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" type="obj" preserve="1">
<p:cSld name="Title and Content"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>
<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>""")
            put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>""")

            put("ppt/slideMasters/slideMaster1.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>
<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>""")
            put("ppt/slideMasters/_rels/slideMaster1.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>""")

            put("ppt/theme/theme1.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Amara"><a:themeElements>
<a:clrScheme name="Amara"><a:dk1><a:srgbClr val="101010"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>
<a:dk2><a:srgbClr val="223333"/></a:dk2><a:lt2><a:srgbClr val="EEF2EF"/></a:lt2>
<a:accent1><a:srgbClr val="50E3C2"/></a:accent1><a:accent2><a:srgbClr val="2C7873"/></a:accent2>
<a:accent3><a:srgbClr val="888888"/></a:accent3><a:accent4><a:srgbClr val="444444"/></a:accent4>
<a:accent5><a:srgbClr val="666666"/></a:accent5><a:accent6><a:srgbClr val="999999"/></a:accent6>
<a:hlink><a:srgbClr val="1155CC"/></a:hlink><a:folHlink><a:srgbClr val="551188"/></a:folHlink></a:clrScheme>
<a:fontScheme name="Amara"><a:majorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>
<a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>
<a:fmtScheme name="Amara"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>
<a:lnStyleLst><a:ln><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>
<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>
<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>
</a:fmtScheme></a:themeElements></a:theme>""")

            put("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title>${sanitizeXml(title)}</dc:title><dc:creator>Amara</dc:creator></cp:coreProperties>""")
        }
        return out.toByteArray()
    }

    data class SlideContent(val title: String, val bullets: List<String>)

    private fun sanitizeXml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

/**
 * Dependency-free PNG (ISO/IEC 15948) encoder: 8-bit RGBA, filter type 0 per scanline,
 * zlib-compressed IDAT via java.util.zip.Deflater, CRC32-chunked structure with the
 * required IHDR→IDAT→IEND ordering. Deterministic: identical input yields identical bytes.
 */
object PngWriter {

    fun write(width: Int, height: Int, argbPixels: IntArray): ByteArray {
        require(width > 0 && height > 0) { "PNG dimensions must be positive" }
        require(argbPixels.size.toLong() == width.toLong() * height) { "Pixel buffer must be width×height" }
        val raw = ByteArray(height * (1 + width * 4))
        var cursor = 0
        for (y in 0 until height) {
            raw[cursor++] = 0 // filter type None
            for (x in 0 until width) {
                val p = argbPixels[y * width + x]
                raw[cursor++] = ((p ushr 16) and 0xFF).toByte() // R
                raw[cursor++] = ((p ushr 8) and 0xFF).toByte()  // G
                raw[cursor++] = (p and 0xFF).toByte()           // B
                raw[cursor++] = ((p ushr 24) and 0xFF).toByte() // A
            }
        }
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(65_536)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", run {
            val ihdr = ByteArray(13)
            writeInt(ihdr, 0, width); writeInt(ihdr, 4, height)
            ihdr[8] = 8  // bit depth
            ihdr[9] = 6  // color type RGBA
            ihdr[10] = 0 // compression: deflate
            ihdr[11] = 0 // filter: adaptive (type 0 used)
            ihdr[12] = 0 // interlace: none
            ihdr
        })
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val body = type.toByteArray(StandardCharsets.US_ASCII) + data
        val lengthBytes = ByteArray(4).also { writeInt(it, 0, data.size) }
        val crc = java.util.zip.CRC32().apply { update(body) }
        out.write(lengthBytes)
        out.write(body)
        out.write(ByteArray(4).also { writeInt(it, 0, crc.value.toInt()) })
    }
}

/**
 * Renders a deterministic bar chart into an ARGB pixel buffer and encodes it through
 * [PngWriter]. Pure function of the spec: no clock, no locale, no random source.
 */
object SimpleChartRenderer {

    private const val BACKGROUND = 0xFFFFFFFF.toInt()
    private const val AXIS = 0xFF101010.toInt()

    fun renderPng(spec: ChartImageSpec): ByteArray = PngWriter.write(spec.widthPx, spec.heightPx, pixels(spec))

    fun pixels(spec: ChartImageSpec): IntArray {
        val width = spec.widthPx
        val height = spec.heightPx
        val px = IntArray(width * height) { BACKGROUND }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
            for (y in y0..y1) for (x in x0..x1) if (x in 0 until width && y in 0 until height) px[y * width + x] = color
        }
        val max = spec.values.max()
        val left = (width * 0.08).toInt().coerceAtLeast(1)
        val bottomBaseline = (height * 0.92).toInt()
        val topMargin = (height * 0.08).toInt().coerceAtLeast(1)
        fill(left, bottomBaseline, width - 1, bottomBaseline + 1, AXIS)                       // x axis
        fill(left, topMargin, left + 1, bottomBaseline, AXIS)                                 // y axis
        val slot = (width - left - 2).toDouble() / spec.values.size
        val barWidth = (slot * 0.6).toInt().coerceAtLeast(1)
        val usableHeight = (bottomBaseline - topMargin).coerceAtLeast(1)
        spec.values.forEachIndexed { index, value ->
            val barHeight = if (max == 0.0) 0 else Math.round(value / max * usableHeight).toInt().coerceIn(0, usableHeight)
            val x0 = left + 2 + (index * slot).toInt()
            if (barHeight > 0) fill(x0, bottomBaseline - barHeight, (x0 + barWidth).coerceAtMost(width - 2), bottomBaseline - 1, 0xFF000000.toInt() or spec.barColorRgb)
        }
        return px
    }
}
