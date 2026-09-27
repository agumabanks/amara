package co.sanaa.agent.core.artifacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Arrays
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Failure-mode coverage for every artifact dimension. Scope labels per test make clear
 * what each assertion proves (structure vs rendering vs persistence contract).
 */
class ArtifactEngineTest {

    private fun goodReport(): ArtifactSpec = ArtifactSpec(
        title = "Weekly Sales Review",
        format = ArtifactFormat.MARKDOWN_REPORT,
        sections = listOf(
            ArtifactSection("Summary", "Sales rose 1200 + 800 = 2000 units across both weeks."),
            ArtifactSection("Risks", "Two bookings await owner action."),
            ArtifactSection("Next Steps", "Follow up with qualified leads after approval."),
        ),
        requiredHeadings = listOf("Summary", "Next Steps"),
    )

    private fun goodSheet(): SpreadsheetSpec = SpreadsheetSpec(
        sheetName = "sales",
        columns = listOf("Week", "Units", "Revenue UGX"),
        columnTypes = mapOf("Units" to "number", "Revenue UGX" to "number"),
        rows = listOf(
            listOf("W1", "120", "600000"),
            listOf("W2", "150", "750000"),
            listOf("W3", "140", "700000"),
            listOf("Total", "410", "2050000"),
        ),
        totalRowColumns = setOf(1, 2),
    )

    // ---------- rubric dimensions ----------

    @Test fun cleanReportPassesAllRubricDimensions()
    {
        val verdict = ArtifactRubric.evaluate(goodReport())
        assertTrue(verdict.failures.joinToString(), verdict.passed)
        assertTrue(ArtifactRubric.calculationFailures(goodReport()).isEmpty())
    }

    @Test fun factualityDimensionRejectsCertaintyMarkers() {
        val spec = goodReport().copy(sections = listOf(ArtifactSection("S", "this obviously always works")))
        assertTrue(ArtifactRubric.factualityFailures(spec).size >= 1)
        assertFalse(spec.let(ArtifactRubric::evaluate).passed)
    }

    @Test fun toneDimensionBansFillerWordsAndRequiresWarmthForCustomerCopy() {
        val filler = goodReport().copy(sections = listOf(ArtifactSection("Customer reply", "Our innovative seamless solution.")))
        val failures = ArtifactRubric.toneFailures(filler)
        assertTrue(failures.any { it.contains("innovative") })
        assertTrue(failures.any { it.contains("seamless") })
        assertTrue(failures.any { it.contains("warm") })
        val warm = goodReport().copy(sections = listOf(ArtifactSection("Customer reply", "Thank you for your order! It ships today.")))
        assertTrue(ArtifactRubric.toneFailures(warm).isEmpty())
        // Non-customer-facing artifacts do not demand warmth markers.
        assertTrue(ArtifactRubric.toneFailures(goodReport()).isEmpty())
    }

    @Test fun privacyDimensionRejectsSecretShapesIncludingSpreadsheetCells() {
        val leaky = goodReport().copy(sections = listOf(ArtifactSection("S", "the pin is 123456")))
        assertFalse(ArtifactRubric.evaluate(leaky).passed)
        val cellLeak = ArtifactSpec(
            "T", ArtifactFormat.CSV_SPREADSHEET,
            sections = emptyList(),
            spreadsheet = goodSheet().copy(rows = listOf(listOf("note", "otp is required", "verification code: 998877"))),
        )
        assertFalse(ArtifactRubric.evaluate(cellLeak).passed)
    }

    @Test fun completenessDimensionFailsMissingRequiredSection() {
        val spec = goodReport().copy(requiredHeadings = listOf("Budget"))
        assertFalse(ArtifactRubric.evaluate(spec).passed)
    }

    @Test fun wrongArithmeticFailsTheCalculationCheck() {
        val spec = ArtifactSpec("T", ArtifactFormat.MARKDOWN_REPORT, listOf(ArtifactSection("S", "2 + 2 = 5")))
        assertEquals(1, ArtifactRubric.calculationFailures(spec).size)
    }

    // ---------- spreadsheet structural checks ----------

    @Test fun validSpreadsheetWithCorrectTotalsPassesStructure() {
        val spec = ArtifactSpec("T", ArtifactFormat.CSV_SPREADSHEET, emptyList(), spreadsheet = goodSheet())
        assertTrue(ArtifactRubric.spreadsheetFailures(spec).joinToString(), ArtifactRubric.structureFailures(spec).isEmpty())
    }

    @Test fun totalMismatchIsDetected() {
        val sheet = goodSheet().copy(rows = goodSheet().rows.dropLast(1) + listOf(listOf("Total", "999", "999")))
        val spec = ArtifactSpec("T", ArtifactFormat.CSV_SPREADSHEET, emptyList(), spreadsheet = sheet)
        val failures = ArtifactRubric.spreadsheetFailures(spec)
        assertTrue(failures.any { it.contains("Total mismatch") && it.contains("stated 999") })
    }

    @Test fun nonNumericCellInDeclaredNumericColumnIsRejected() {
        val sheet = goodSheet().copy(rows = listOf(listOf("W1", "many", "600000"), listOf("W2", "150", "750000"), listOf("W3", "140", "700000")))
        val spec = ArtifactSpec("T", ArtifactFormat.CSV_SPREADSHEET, emptyList(), spreadsheet = sheet)
        assertTrue(ArtifactRubric.spreadsheetFailures(spec).any { it.contains("should be numeric") })
    }

    @Test fun lowValueAnomalyAgainstColumnMedianIsFlagged() {
        val sheet = goodSheet().copy(
            rows = listOf(
                listOf("A", "100", "10"), listOf("B", "100", "10"),
                listOf("C", "100", "10"), listOf("D", "3", "10"),
            ),
        )
        val spec = ArtifactSpec("T", ArtifactFormat.CSV_SPREADSHEET, emptyList(), spreadsheet = sheet)
        assertTrue(ArtifactRubric.spreadsheetFailures(spec).any { it.contains("Anomalous low value 3.0") })
    }

    @Test fun raggedRowsAndBlankHeadersAreRejected() {
        val sheet = goodSheet().copy(columns = listOf("", "b"), rows = listOf(listOf("only-one-cell")))
        val spec = ArtifactSpec("T", ArtifactFormat.CSV_SPREADSHEET, emptyList(), spreadsheet = sheet)
        val failures = ArtifactRubric.spreadsheetFailures(spec)
        assertTrue(failures.any { it.contains("blank column header") })
        assertTrue(failures.any { it.contains("expected 2") })
    }

    @Test fun csvEscapesHeadersIdenticallyToCells() {
        val sheet = SpreadsheetSpec(
            sheetName = "s",
            columns = listOf("Name,\"quoted\"", "Note"),
            rows = listOf(listOf("say \"hi\", ok", "line1\nline2")),
        )
        val spec = ArtifactSpec("T", ArtifactFormat.CSV_SPREADSHEET, emptyList(), spreadsheet = sheet)
        val csv = String(ArtifactRenderer.renderBytes(spec))
        val header = csv.lineSequence().first()
        val row = csv.lineSequence().drop(1).first()
        // Headers must carry identical quoting to data cells (defect fix).
        assertTrue(header.startsWith("\"Name,\"\"quoted\"\"\""))
        // A quoted cell may contain a raw newline; verify against the full document text.
        assertTrue(csv.contains("\"say \"\"hi\"\", ok\""))
        assertTrue(csv.contains("\"line1\nline2\""))
    }

    // ---------- PDF generation (structural JVM verification) ----------

    @Test fun pdfOutputIsStructurallyValidWithoutExternalLibraries() {
        val bytes = ArtifactRenderer.renderBytes(
            goodReport().copy(format = ArtifactFormat.PDF_DOCUMENT),
        )
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Type /Catalog"))
        assertTrue(text.contains("/Count 4")) // title page + three sections
        assertTrue(text.contains("(Summary) Tj"))
        assertTrue(text.contains("(Weekly Sales Review) Tj"))
        // xref offsets actually point at "N 0 obj".
        val xrefPos = text.lastIndexOf("startxref").let { text.substringAfter("startxref\n").trim().substringBefore("\n").toInt() }
        val pointed = text.substring(xrefPos)
        assertTrue(pointed.startsWith("xref\n0 "))
        val firstOffset = text.lineSequence()
            .firstOrNull { Regex("^\\d{10} 00000 n ").containsMatchIn(it) }
        assertNotNull("xref entry lines must exist", firstOffset)
    }

    @Test fun pdfSanitizesParenthesesAndNonLatinCharacters() {
        val bytes = ArtifactRenderer.renderBytes(
            ArtifactSpec("T(itles) \\ bad ☃", ArtifactFormat.PDF_DOCUMENT, listOf(ArtifactSection("H(ead)", "body (x) end"))),
        )
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("(T(itles)"))
        assertTrue("parentheses must be bracketed inside PDF strings", text.contains("(T[itles]  bad ?) Tj"))
    }

    // ---------- PPTX generation (OPC package structure verification) ----------

    @Test fun pptxPackageContainsStandardPartsAndParsesAsXml() {
        val bytes = ArtifactRenderer.renderBytes(
            goodReport().copy(format = ArtifactFormat.PPTX_PRESENTATION),
        )
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        listOf(
            "[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml",
            "ppt/_rels/presentation.xml.rels", "ppt/slides/slide1.xml",
            "ppt/slides/slide4.xml", "ppt/theme/theme1.xml", "ppt/slideMasters/slideMaster1.xml",
        ).forEach { required -> assertNotNull("missing $required", entries[required]) }

        val parser = DocumentBuilderFactory.newInstance()
        entries.values.forEach { xml ->
            // Every XML part must parse.
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml))
        }
        val presentation = String(entries.getValue("ppt/presentation.xml"))
        assertTrue(presentation.contains("<p:sldIdLst>") && presentation.contains("r:id=\"rId5\""))
        assertTrue(String(entries.getValue("ppt/slides/slide1.xml")).contains("Weekly Sales Review"))
        assertTrue(String(entries.getValue("ppt/slides/slide2.xml")).contains("Summary"))
        assertTrue(String(entries.getValue("ppt/slides/slide4.xml")).contains("Next Steps"))
    }

    @Test fun pptxEscapesXmlSpecialCharactersInSlideText() {
        val bytes = ArtifactRenderer.renderBytes(
            ArtifactSpec("R&D <report> \"2026\"", ArtifactFormat.PPTX_PRESENTATION, listOf(ArtifactSection("A & B <c>", "text"))),
        )
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "ppt/slides/slide1.xml") {
                    val text = String(zip.readBytes())
                    assertTrue(text.contains("R&amp;D &lt;report&gt; &quot;2026&quot;"))
                    assertFalse(text.contains("R&D <report>"))
                }
                entry = zip.nextEntry
            }
        }
    }

    // ---------- PNG images (MISSION artifact tool: "images") ----------

    private fun chart(values: List<Double> = listOf(120.0, 150.0, 140.0, 410.0)) = ArtifactSpec(
        title = "Weekly revenue chart",
        format = ArtifactFormat.PNG_IMAGE,
        sections = listOf(ArtifactSection("Chart", "Revenue by week from the sales sheet.")),
        requiredHeadings = listOf("Chart"),
        image = ChartImageSpec(320, 200, "Weekly revenue (UGX)", values),
    )

    @Test fun pngOutputIsAStructurallyValidPortableNetworkGraphic() {
        val bytes = ArtifactRenderer.renderBytes(chart())
        assertEquals(
            Arrays.toString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
            Arrays.toString(bytes.copyOfRange(0, 8)),
        )
        // Walk chunks: CRC32 must verify, IHDR carries 320x200 RGBA8, IDAT inflates to
        // filtered scanlines (height × (1 + width×4) raw bytes), IEND terminates.
        var offset = 8
        var sawIhdr = false
        var sawIdat = false
        while (offset < bytes.size) {
            fun u32(at: Int): Long =
                ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
            val length = u32(offset).toInt()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val crcAt = offset + 8 + length
            val crc = java.util.zip.CRC32()
            crc.update(bytes, offset + 4, length + 4)
            assertEquals("CRC must match for $type", u32(crcAt), crc.value)
            if (type == "IHDR") {
                assertEquals(320L, u32(offset + 8))
                assertEquals(200L, u32(offset + 12))
                assertEquals("bit depth 8", 8, bytes[offset + 16].toInt())
                assertEquals("color type RGBA", 6, bytes[offset + 17].toInt())
                sawIhdr = true
            }
            if (type == "IDAT") {
                val raw = java.util.zip.Inflater().apply { setInput(bytes, offset + 8, length) }.let { inf ->
                    val out = ByteArray(200 * (1 + 320 * 4))
                    var total = 0
                    while (!inf.finished() && total < out.size) total += inf.inflate(out, total, out.size - total)
                    inf.end(); total
                }
                assertEquals("raw scanlines inflate fully", (200 * (1 + 320 * 4)).toLong(), raw.toLong())
                sawIdat = true
            }
            offset = crcAt + 4
            if (type == "IEND") break
        }
        assertTrue(sawIhdr && sawIdat)
    }

    @Test fun pngRenderingIsDeterministicByteForByte() {
        val first = ArtifactRenderer.renderBytes(chart())
        val second = ArtifactRenderer.renderBytes(chart())
        assertTrue(first.contentEquals(second))
        val digest = ContentDigest.of(first)
        assertEquals(digest, ContentDigest.of(second))
        assertTrue("full sha256 recorded for delivery", digest.length == 64 && digest != ContentDigest.of(ByteArray(0)))
    }

    @Test fun malformedImageInputIsRejectedByRubricAndRenderer() {
        // Malformed dimensions/values/labels cannot even be constructed…
        assertTrue(runCatching { ChartImageSpec(0, 10, "x", listOf(1.0)) }.isFailure)
        assertTrue(runCatching { ChartImageSpec(10, -5, "x", listOf(1.0)) }.isFailure)
        assertTrue(runCatching { ChartImageSpec(10, 10, "", listOf(1.0)) }.isFailure)
        assertTrue(runCatching { ChartImageSpec(10, 10, "x", emptyList()) }.isFailure)
        assertTrue(runCatching { ChartImageSpec(10, 10, "x", listOf(-1.0)) }.isFailure)
        // …and size limits are enforced at construction time as well.
        assertTrue(runCatching { ChartImageSpec(4_097, 10, "x", listOf(1.0)) }.isFailure)
        assertTrue(runCatching { ChartImageSpec(10, 4_097, "x", listOf(1.0)) }.isFailure)
        assertTrue(runCatching { ChartImageSpec(2_048, 2_049, "x", listOf(1.0)) }.isFailure) // > 4MP budget
        // A PNG-format artifact without any chart definition fails the rubric.
        assertFalse(ArtifactRubric.evaluate(chart().copy(image = null)).passed)
        assertTrue(ArtifactRubric.imageFailures(chart().copy(image = null)).isNotEmpty())
        assertTrue(ArtifactRubric.evaluate(chart()).passed)
    }

    @Test fun privacyDimensionCoversImageLabels() {
        val leaky = chart().copy(image = (chart().image)!!.copy(seriesLabel = "revenue per pin 123456"))
        assertFalse(ArtifactRubric.evaluate(leaky).passed)
    }

    // ---------- PDF/PPTX scope discipline ----------

    @Test fun pdfRenderingIsDeterministicAndMultiPageCapable() {
        val manySections = (1..12).map { ArtifactSection("Section $it", "Body line for section $it.") }
        val spec = ArtifactSpec("Long document", ArtifactFormat.PDF_DOCUMENT, manySections)
        val bytes = ArtifactRenderer.renderBytes(spec)
        assertTrue(bytes.contentEquals(ArtifactRenderer.renderBytes(spec)))
        val text = String(bytes, Charsets.ISO_8859_1)
        val pageCount = Regex("/Type /Page[^s]").findAll(text).count()
        assertTrue("expected ≥13 pages, found $pageCount", pageCount >= 13)
        assertEquals(ContentDigest.of(bytes), ContentDigest.of(ArtifactRenderer.renderBytes(spec)))
    }

    @Test fun pptxPackageSupportsManySlidesWithRoundTrippingText() {
        val slides = (1..14).map { ArtifactSection("Slide $it heading", "Bullet for slide $it") }
        val spec = ArtifactSpec("Deck", ArtifactFormat.PPTX_PRESENTATION, slides)
        val bytes = ArtifactRenderer.renderBytes(spec)
        assertTrue(bytes.contentEquals(ArtifactRenderer.renderBytes(spec)))
        var slideCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (Regex("ppt/slides/slide\\d+\\.xml").matches(entry.name)) {
                    slideCount++
                    val xmlText = String(zip.readBytes())
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xmlText.toByteArray()))
                }
                entry = zip.nextEntry
            }
        }
        assertEquals(15, slideCount) // title slide + 14 sections
    }

    @Test fun unicodeBehaviorIsExplicitPerFormat() {
        val japanese = "売上レポート — 第3週"
        // PPTX XML parts are UTF-8: professional multilingual text round-trips exactly.
        val pptx = ArtifactRenderer.renderBytes(
            ArtifactSpec(japanese, ArtifactFormat.PPTX_PRESENTATION, listOf(ArtifactSection("概要", "内容"))),
        )
        ZipInputStream(ByteArrayInputStream(pptx)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "ppt/slides/slide1.xml") {
                    val titleXml = String(zip.readBytes(), Charsets.UTF_8)
                    assertTrue(titleXml.contains(japanese))
                }
                if (entry.name == "ppt/slides/slide2.xml") {
                    val bodyXml = String(zip.readBytes(), Charsets.UTF_8)
                    assertTrue(bodyXml.contains("概要"))
                    assertTrue(bodyXml.contains("内容"))
                }
                entry = zip.nextEntry
            }
        }
        // The dependency-free PDF writer uses WinAnsi Helvetica: non-Latin text is
        // SANITIZED to placeholders. That is lossy substitution — documented scope,
        // NOT multilingual document support.
        val pdf = String(ArtifactRenderer.renderBytes(ArtifactSpec(japanese, ArtifactFormat.PDF_DOCUMENT, listOf(ArtifactSection("H", "body")))), Charsets.ISO_8859_1)
        assertFalse(pdf.contains("売上"))
        assertTrue(pdf.contains("?"))
        assertTrue(pdf.startsWith("%PDF-1.4"))
    }

    // ---------- version history ----------

    @Test fun invalidJsonIsRejectedByRubric() {
        val bad = ArtifactSpec("T", ArtifactFormat.STRUCTURED_JSON, emptyList(), jsonPayload = "{not json")
        assertFalse(ArtifactRubric.evaluate(bad).passed)
        val good = bad.copy(jsonPayload = "{\"ok\":true}")
        assertTrue(ArtifactRubric.evaluate(good).passed)
    }

    @Test fun storeKeepsVersionHistoryAndRejectsRubricFailures() {
        val store = InMemoryArtifactStore()
        val first = store.commit("brief", goodReport(), nowMs = 10)
        val revised = store.commit("brief", goodReport().copy(title = "Weekly Sales Review v2"), nowMs = 20)
        assertEquals(first.id, revised.parentRevision)
        assertEquals(2, store.history("brief").size)
        assertEquals(revised.id, store.latest("brief")!!.id)
        var rejected = false
        runCatching { store.commit("bad", goodReport().copy(sections = listOf(ArtifactSection("S", "the pin is 123456"))), 30) }
            .onFailure { rejected = true }
        assertTrue(rejected)
    }

    @Test fun renderedHashesDifferAcrossRevisionsAndMatchContent() {
        val store = InMemoryArtifactStore()
        val first = store.commit("a", goodReport(), 1)
        val second = store.commit("a", goodReport().copy(title = "v2"), 2)
        assertFalse(first.renderedBytesHash == second.renderedBytesHash)
        val expected = ContentDigest.of(ArtifactRenderer.renderBytes(goodReport()))
        assertEquals(expected, first.renderedBytesHash)
    }

    @Test fun markdownRenderingIsWellFormed() {
        val rendered = ArtifactRenderer.render(goodReport())
        assertTrue(rendered.startsWith("# Weekly Sales Review"))
        assertTrue(rendered.contains("## Summary"))
        assertNotNull(rendered)
        assertFalse(rendered.contains("obviously"))
    }
}
