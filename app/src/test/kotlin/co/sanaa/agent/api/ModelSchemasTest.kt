package co.sanaa.agent.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSchemasTest {

    @Test
    fun schemaForResolvesAllThirteenStages() {
        assertEquals(ModelSchemas.PLANNER_PLAN, ModelSchemas.schemaFor("planner"))
        assertEquals(ModelSchemas.RECOVERY_PLAN, ModelSchemas.schemaFor("recovery"))
        assertEquals(ModelSchemas.VISUAL_AUDIT, ModelSchemas.schemaFor("visual_audit"))
        assertEquals(ModelSchemas.VISUAL_AUDIT, ModelSchemas.schemaFor("vision"))
        assertEquals(ModelSchemas.CONVERSATION_DECISION, ModelSchemas.schemaFor("conversation"))
        assertEquals(ModelSchemas.BROADCAST_PLAN, ModelSchemas.schemaFor("broadcast"))
        assertEquals(ModelSchemas.FOLLOW_UP_DECISION, ModelSchemas.schemaFor("follow_up"))
        assertEquals(ModelSchemas.CONVERSATION_REPLY, ModelSchemas.schemaFor("conversation_reply"))
        assertEquals(ModelSchemas.LISTING_ANALYSIS, ModelSchemas.schemaFor("listing_intelligence"))
        assertEquals(ModelSchemas.STUDIO_CAPTION, ModelSchemas.schemaFor("share_soko_studio_caption"))
        assertEquals(ModelSchemas.TIKTOK_CAPTION, ModelSchemas.schemaFor("tiktok_draft_or_publish"))
        assertEquals(ModelSchemas.CONTACT_MESSAGE_GENERATION, ModelSchemas.schemaFor("contact_message_generation"))
        assertEquals(ModelSchemas.WHATSAPP_INBOUND_CLASSIFICATION, ModelSchemas.schemaFor("whatsapp_inbound_classification"))
        assertEquals(ModelSchemas.SOKO_SERVICE_TEXT_PROPOSALS, ModelSchemas.schemaFor("soko_text_proposals"))
        assertNull(ModelSchemas.schemaFor("unknown_stage"))
        // All are distinct schemas.
        assertEquals(13, ModelSchemas.ALL.size)
        assertEquals(13, ModelSchemas.ALL.values.map { it.name }.toSet().size)
    }

    @Test
    fun plannerPlanRequiresStepsArray() {
        val errors = ModelSchemas.PLANNER_PLAN.validate(JSONObject("{\"observation\":\"screen\"}"))
        assertTrue(errors.any { it.contains("'steps'") && it.contains("missing required field") })
        val ok = ModelSchemas.PLANNER_PLAN.validate(JSONObject("{\"steps\":[{\"action\":\"open_app\"}]}"))
        assertTrue(ok.isEmpty())
    }

    @Test
    fun recoveryPlanRequiresStepsArray() {
        val errors = ModelSchemas.RECOVERY_PLAN.validate(JSONObject("{}"))
        assertTrue(errors.single().contains("missing required field 'steps'"))
        val wrongType = ModelSchemas.RECOVERY_PLAN.validate(JSONObject("{\"steps\":\"tap\"}"))
        assertTrue(wrongType.single().contains("must be ARRAY but was STRING"))
    }

    @Test
    fun visualAuditValidatesAllFiveFields() {
        val good = JSONObject(
            "{\"listing_name\":\"Red Dress\",\"image_description\":\"A red dress on a hanger\"," +
                "\"mismatch\":false,\"issue\":\"\",\"confidence\":0.92}",
        )
        assertTrue(ModelSchemas.VISUAL_AUDIT.validate(good).isEmpty())
        val empty = ModelSchemas.VISUAL_AUDIT.validate(JSONObject("{}"))
        assertEquals(5, empty.size)
        assertTrue(empty.all { it.contains("missing required field") })
    }

    @Test
    fun wrongTypedFieldIsReportedPrecisely() {
        val bad = JSONObject(
            "{\"listing_name\":\"Red Dress\",\"image_description\":\"A red dress on a hanger\"," +
                "\"mismatch\":\"no\",\"issue\":\"\",\"confidence\":\"high\"}",
        )
        val errors = ModelSchemas.VISUAL_AUDIT.validate(bad)
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.contains("'confidence'") && it.contains("must be NUMBER but was STRING") })
        assertTrue(errors.any { it.contains("'mismatch'") && it.contains("must be BOOLEAN but was STRING") })
    }

    @Test
    fun invalidEnumValueIsReported() {
        val schema = ModelSchema(
            name = "enum_probe",
            requiredFields = linkedMapOf("price_position" to FieldType.STRING),
            enums = mapOf("price_position" to setOf("too high", "competitive", "low")),
        )
        val errors = schema.validate(JSONObject("{\"price_position\":\"way too high\"}"))
        assertTrue(errors.single().contains("invalid enum value 'way too high'"))
        assertTrue(schema.validate(JSONObject("{\"price_position\":\"competitive\"}")).isEmpty())
    }

    @Test
    fun decisionSchemasRequireSendBooleanAndMessageString() {
        for (schema in listOf(ModelSchemas.CONVERSATION_DECISION, ModelSchemas.FOLLOW_UP_DECISION)) {
            assertTrue(schema.validate(JSONObject("{\"send\":true,\"message\":\"Hello there\"}")).isEmpty())
            val errors = schema.validate(JSONObject("{\"send\":\"yes\"}"))
            assertTrue(errors.any { it.contains("'message'") && it.contains("missing required field") })
            assertTrue(errors.any { it.contains("'send'") && it.contains("must be BOOLEAN but was STRING") })
        }
    }

    @Test
    fun broadcastPlanRequiresThreeStrings() {
        val good = JSONObject(
            "{\"broadcast_message\":\"Good morning Kampala\",\"tiktok_caption\":\"New stock alert\",\"featured_product\":\"Ankara fabric\"}",
        )
        assertTrue(ModelSchemas.BROADCAST_PLAN.validate(good).isEmpty())
        val partial = ModelSchemas.BROADCAST_PLAN.validate(JSONObject("{\"broadcast_message\":\"Hi\"}"))
        assertEquals(2, partial.size)
        assertTrue(partial.all { it.contains("missing required field") })
    }

    @Test
    fun extraFieldsAreToleratedButTypesStillChecked() {
        val json = JSONObject("{\"send\":true,\"message\":\"ok\",\"model_confidence\":0.5}")
        assertTrue(ModelSchemas.CONVERSATION_DECISION.validate(json).isEmpty())
        val nestedWrong = JSONObject("{\"send\":true,\"message\":{\"text\":\"object not string\"}}")
        val errors = ModelSchemas.CONVERSATION_DECISION.validate(nestedWrong)
        assertTrue(errors.single().contains("must be STRING but was OBJECT"))
    }

    @Test
    fun numberTypeAcceptsIntegersAndDoublesButNotStrings() {
        assertTrue(ModelSchemas.VISUAL_AUDIT.validate(
            JSONObject("{\"listing_name\":\"a\",\"image_description\":\"abcdefgh\",\"mismatch\":true,\"issue\":\"wrong image shown\",\"confidence\":1}"),
        ).isEmpty())
        assertNotEquals(
            emptyList<String>(),
            ModelSchemas.VISUAL_AUDIT.validate(
                JSONObject("{\"listing_name\":\"a\",\"image_description\":\"abcdefgh\",\"mismatch\":true,\"issue\":\"wrong image shown\",\"confidence\":\"0.9\"}"),
            ),
        )
    }

    // ---------------------------------------------------------- visual audit hardening

    @Test
    fun visualAuditRejectsBlankListingNameOrDescriptionAndOutOfRangeConfidence() {
        val blank = ModelSchemas.VISUAL_AUDIT.validate(
            JSONObject("{\"listing_name\":\" \",\"image_description\":\"\",\"mismatch\":false,\"issue\":\"\",\"confidence\":1.4}"),
        )
        assertTrue(blank.any { it.contains("'listing_name'") && it.contains("non-blank") })
        assertTrue(blank.any { it.contains("'image_description'") && it.contains("non-blank") })
        assertTrue(blank.any { it.contains("'confidence'") && it.contains("within 0.0..1.0") })
    }

    // ---------------------------------------------------------- listing analysis

    private fun goodListingAnalysis() = JSONObject(
        "{\"weakness_found\":\"title lacks keywords\",\"missing_keywords\":[\"custom mugs\"]," +
            "\"price_position\":\"competitive\",\"improved_title\":\"Custom Photo Mugs Kampala\"," +
            "\"improved_description\":\"Your logo on every desk mug in Kampala.\"," +
            "\"ad_headline\":\"Custom mugs, fast\",\"ad_caption\":\"Order your custom mug from 25,000 UGX today.\"," +
            "\"improvement_score\":72,\"owner_note\":\"consider bundle pricing\"}",
    )

    @Test
    fun listingAnalysisAcceptsAGroundedProposal() {
        assertTrue(ModelSchemas.LISTING_ANALYSIS.validate(goodListingAnalysis()).isEmpty())
    }

    @Test
    fun listingAnalysisRejectsMissingFieldsBlankCopyUnknownPricePositionAndBadScore() {
        val errors = ModelSchemas.LISTING_ANALYSIS.validate(
            JSONObject(
                "{\"weakness_found\":\"x\",\"missing_keywords\":[],\"price_position\":\"dirt cheap\"," +
                    "\"improved_title\":\"\",\"improved_description\":\"   \"," +
                    "\"improvement_score\":140}",
            ),
        )
        assertTrue(errors.any { it.contains("missing required field 'ad_headline'") })
        assertTrue(errors.any { it.contains("missing required field 'ad_caption'") })
        assertTrue(errors.any { it.contains("'improved_title'") && it.contains("non-blank") })
        assertTrue(errors.any { it.contains("'improved_description'") && it.contains("non-blank") })
        assertTrue(errors.any { it.contains("'price_position'") && it.contains("invalid enum value 'dirt cheap'") })
        assertTrue(errors.any { it.contains("'improvement_score'") && it.contains("within 0.0..100.0") })
    }

    @Test
    fun listingAnalysisRejectsNonStringKeywordElements() {
        val bad = goodListingAnalysis().put("missing_keywords", org.json.JSONArray(listOf(JSONObject().put("k", "v"))))
        val errors = ModelSchemas.LISTING_ANALYSIS.validate(bad)
        assertTrue(errors.single().contains("element 'missing_keywords[0]' must be STRING but was OBJECT"))
    }

    // ---------------------------------------------------------- caption stages

    @Test
    fun captionStagesRejectBlankAndOversizedCaptions() {
        for (schema in listOf(ModelSchemas.STUDIO_CAPTION, ModelSchemas.TIKTOK_CAPTION)) {
            assertTrue(schema.validate(JSONObject("{\"caption\":\"Warm caption with price\"}")).isEmpty())
            val blank = schema.validate(JSONObject("{\"caption\":\"   \"}"))
            assertTrue(blank.single().contains("'caption' must be a non-blank string"))
            val oversized = schema.validate(JSONObject().put("caption", "y".repeat(ModelSchemas.MAX_CAPTION_CHARS + 1)))
            assertTrue(oversized.single().contains("exceeds the maximum length"))
        }
    }

    // ---------------------------------------------------------- contact message generation

    @Test
    fun contactMessageGenerationDemandsTextWhenSending() {
        assertTrue(ModelSchemas.CONTACT_MESSAGE_GENERATION.validate(
            JSONObject("{\"send\":true,\"message\":\"Hello, your order is ready.\"}"),
        ).isEmpty())
        assertTrue(ModelSchemas.CONTACT_MESSAGE_GENERATION.validate(
            JSONObject("{\"send\":false,\"message\":\"\"}"),
        ).isEmpty())
        val errors = ModelSchemas.CONTACT_MESSAGE_GENERATION.validate(JSONObject("{\"send\":true,\"message\":\"\"}"))
        assertTrue(errors.single().contains("'message' must be present and non-blank when 'send' is true"))
    }

    // ---------------------------------------------------------- whatsapp inbound classification

    @Test
    fun whatsappInboundClassificationEnforcesConditionalFields() {
        assertTrue(ModelSchemas.WHATSAPP_INBOUND_CLASSIFICATION.validate(
            JSONObject("{\"classification\":\"inquiry\",\"response\":\"Delivery is 20,000 UGX.\",\"escalate\":false}"),
        ).isEmpty())
        assertTrue(ModelSchemas.WHATSAPP_INBOUND_CLASSIFICATION.validate(
            JSONObject(
                "{\"classification\":\"complaint\",\"response\":\"\",\"escalate\":true," +
                    "\"escalation_reason\":\"refund dispute\",\"escalation_urgency\":\"high\"}",
            ),
        ).isEmpty())
        val missingResponse = ModelSchemas.WHATSAPP_INBOUND_CLASSIFICATION.validate(
            JSONObject("{\"classification\":\"inquiry\",\"response\":\"\",\"escalate\":false}"),
        )
        assertTrue(missingResponse.single().contains("'response' must be present and non-blank when 'escalate' is false"))
        val missingReason = ModelSchemas.WHATSAPP_INBOUND_CLASSIFICATION.validate(
            JSONObject("{\"classification\":\"complaint\",\"response\":\"ok\",\"escalate\":true}"),
        )
        assertTrue(missingReason.single().contains("'escalation_reason' must be present and non-blank when 'escalate' is true"))
        val badEnum = ModelSchemas.WHATSAPP_INBOUND_CLASSIFICATION.validate(
            JSONObject("{\"classification\":\"vague\",\"response\":\"hi\",\"escalate\":false}"),
        )
        assertTrue(badEnum.single().contains("invalid enum value 'vague'"))
        val missedCall = ModelSchemas.WHATSAPP_INBOUND_CLASSIFICATION.validate(
            JSONObject("{\"classification\":\"missed_call\",\"response\":\"\",\"escalate\":true,\"escalation_reason\":\"missed call\"}"),
        )
        assertTrue(missedCall.isEmpty())
    }

    // ---------------------------------------------------------- soko text proposals (Agent 2 handoff)

    @Test
    fun sokoServiceTextProposalsValidateEveryNestedElement() {
        val good = JSONObject(
            "{\"proposals\":[{" +
                "\"current_name\":\"Car Wash Express\",\"proposed_name\":\"Express Car Wash Ntinda\"," +
                "\"proposed_description\":\"Get your car spotless in under thirty minutes at our Ntinda bay.\"," +
                "\"reason\":\"name lacked location keyword\"}]}",
        )
        assertTrue(ModelSchemas.SOKO_SERVICE_TEXT_PROPOSALS.validate(good).isEmpty())

        val bad = JSONObject(
            "{\"proposals\":[" +
                "{\"current_name\":\"\",\"proposed_name\":\"X\",\"proposed_description\":\"short\"}," +
                "{\"current_name\":\"A\",\"proposed_name\":\"B\",\"proposed_description\":\"valid description here\",\"reason\":\"r\"}," +
                "\"not an object\"]}",
        )
        val errors = ModelSchemas.SOKO_SERVICE_TEXT_PROPOSALS.validate(bad)
        assertTrue(errors.any { it.contains("proposals[0].current_name") && it.contains("non-blank") })
        assertTrue(errors.any { it.contains("'proposals[0]'") && it.contains("missing required field 'reason'") })
        assertTrue(errors.any { it.contains("element 'proposals[2]' must be an OBJECT but was STRING") })
    }
}
