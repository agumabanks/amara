package co.sanaa.agent.api

import org.json.JSONArray
import org.json.JSONObject

/** JSON value types accepted by [ModelSchema] field declarations. */
enum class FieldType {
    STRING, NUMBER, BOOLEAN, OBJECT, ARRAY,
}

/**
 * Validation spec for the ELEMENTS of a declared array field. Either [elementType]
 * (arrays of primitives) or [requiredFields] (arrays of objects) must be used;
 * object elements additionally honor [enums], [requiredNonBlank], and [minItems].
 */
data class ElementSchema(
    val elementType: FieldType? = null,
    val requiredFields: Map<String, FieldType> = emptyMap(),
    val enums: Map<String, Set<String>> = emptyMap(),
    val requiredNonBlank: Set<String> = emptySet(),
    val minItems: Int = 0,
)

/** A required field that only becomes mandatory when a boolean gate field equals [whenGateEquals]. */
data class ConditionalRequirement(
    val gateField: String,
    val whenGateEquals: Boolean,
)

/**
 * A small enforceable schema for one model-output stage. Validation returns a
 * precise list of human-readable errors; an empty list means the output is
 * valid. Unknown extra fields are tolerated (providers add metadata), but every
 * declared required field must exist with exactly the declared type and any
 * declared enum constraint.
 *
 * Beyond plain types this schema can reject, BEFORE any side-effect planning:
 * blank required strings ([requiredNonBlank]), conditionally blank fields
 * ([conditionalRequirements]), out-of-range numbers ([numberRanges]), oversized
 * strings ([maxStringLengths]), and wrongly shaped nested array elements
 * ([arrayElements]).
 */
class ModelSchema(
    val name: String,
    val requiredFields: Map<String, FieldType>,
    val enums: Map<String, Set<String>> = emptyMap(),
    /** Fields the prompt asks for but that may be omitted without rejection. */
    val optionalFields: Set<String> = emptySet(),
    /** Required STRING fields whose value must not be empty/whitespace when present. */
    val requiredNonBlank: Set<String> = emptySet(),
    /** Inclusive numeric bounds for NUMBER fields. */
    val numberRanges: Map<String, ClosedFloatingPointRange<Double>> = emptyMap(),
    /** Hard maximum character length for STRING fields. */
    val maxStringLengths: Map<String, Int> = emptyMap(),
    /** Field becomes required non-blank only when its gate field matches. */
    val conditionalRequirements: Map<String, ConditionalRequirement> = emptyMap(),
    /** Per-array element shape enforcement. */
    val arrayElements: Map<String, ElementSchema> = emptyMap(),
) {

    fun validate(target: JSONObject): List<String> {
        if (target !is JSONObject) return listOf("unexpected root: expected a JSON object")
        val errors = mutableListOf<String>()
        for ((field, type) in requiredFields) {
            if (!target.has(field) || target.isNull(field)) {
                errors += "missing required field '$field'"
                continue
            }
            val value = target.get(field)
            if (!matchesType(value, type)) {
                errors += "field '$field' must be $type but was ${typeName(value)}"
            }
        }
        for ((field, allowed) in enums) {
            val value = target.opt(field) ?: continue
            if (value is String && value !in allowed) {
                errors += "field '$field' has invalid enum value '$value' (allowed: ${allowed.sorted()})"
            }
        }
        for (field in requiredNonBlank) {
            val value = target.opt(field) ?: continue
            if (value is String && value.isBlank()) {
                errors += "field '$field' must be a non-blank string"
            }
        }
        for ((field, rule) in conditionalRequirements) {
            val gateValue = target.opt(rule.gateField)
            if ((gateValue as? Boolean) == rule.whenGateEquals) {
                val value = target.opt(field)
                if (value == null || value !is String || value.isBlank()) {
                    errors += "field '$field' must be present and non-blank when '${rule.gateField}' is ${rule.whenGateEquals}"
                }
            }
        }
        for ((field, range) in numberRanges) {
            val value = target.opt(field) ?: continue
            if (value is Number && value.toDouble() !in range) {
                errors += "field '$field' must be within ${range.start}..${range.endInclusive} but was $value"
            }
        }
        for ((field, maxLength) in maxStringLengths) {
            val value = target.opt(field) ?: continue
            if (value is String && value.length > maxLength) {
                errors += "field '$field' exceeds the maximum length of $maxLength characters"
            }
        }
        for ((field, elementSpec) in arrayElements) {
            val array = target.optJSONArray(field) ?: continue
            if (array.length() < elementSpec.minItems) {
                errors += "field '$field' must contain at least ${elementSpec.minItems} item(s) but had ${array.length()}"
            }
            for (index in 0 until array.length()) {
                val path = "$field[$index]"
                val element = array.opt(index)
                if (elementSpec.elementType != null) {
                    if (!matchesType(element, elementSpec.elementType)) {
                        errors += "element '$path' must be ${elementSpec.elementType} but was ${typeName(element)}"
                    }
                    continue
                }
                if (element !is JSONObject) {
                    errors += "element '$path' must be an OBJECT but was ${typeName(element)}"
                    continue
                }
                for ((subField, subType) in elementSpec.requiredFields) {
                    if (!element.has(subField) || element.isNull(subField)) {
                        errors += "element '$path' is missing required field '$subField'"
                        continue
                    }
                    if (!matchesType(element.get(subField), subType)) {
                        errors += "element '$path.$subField' must be $subType but was ${typeName(element.get(subField))}"
                    }
                }
                for ((subField, allowed) in elementSpec.enums) {
                    val value = element.opt(subField) ?: continue
                    if (value is String && value !in allowed) {
                        errors += "element '$path.$subField' has invalid enum value '$value' (allowed: ${allowed.sorted()})"
                    }
                }
                for (subField in elementSpec.requiredNonBlank) {
                    val value = element.opt(subField) ?: continue
                    if (value is String && value.isBlank()) {
                        errors += "element '$path.$subField' must be a non-blank string"
                    }
                }
            }
        }
        return errors
    }

    private fun matchesType(value: Any?, type: FieldType): Boolean = when (type) {
        FieldType.STRING -> value is String
        FieldType.NUMBER -> value is Number
        FieldType.BOOLEAN -> value is Boolean
        FieldType.OBJECT -> value is JSONObject
        FieldType.ARRAY -> value is JSONArray
    }

    private fun typeName(value: Any?): String = when (value) {
        is String -> "STRING"
        is Number -> "NUMBER"
        is Boolean -> "BOOLEAN"
        is JSONObject -> "OBJECT"
        is JSONArray -> "ARRAY"
        null -> "null"
        else -> value.javaClass.simpleName
    }

    override fun toString(): String = "ModelSchema($name)"
}

/**
 * Typed schemas for every consequential JSON stage. Each schema below guards one
 * production decision point BEFORE side-effect planning; PLANNER_PLAN and
 * RECOVERY_PLAN match core/AutonomyController's planner and recovery prompts
 * EXACTLY where it matters: `steps` must be an array.
 */
object ModelSchemas {

    /** {"observation","analysis","steps":[{"action","target","message","app","reason"}],"question"} */
    val PLANNER_PLAN = ModelSchema(
        name = "planner_plan",
        requiredFields = linkedMapOf("steps" to FieldType.ARRAY),
        optionalFields = setOf("observation", "analysis", "question"),
    )

    /** {"steps":[{"action","target","message","app","reason"}]} */
    val RECOVERY_PLAN = ModelSchema(
        name = "recovery_plan",
        requiredFields = linkedMapOf("steps" to FieldType.ARRAY),
    )

    /**
     * {"listing_name","image_description","mismatch","issue","confidence"} — vision
     * audits must identify the listing they inspected, describe real visible content,
     * and keep confidence inside a truthful 0..1 band.
     */
    val VISUAL_AUDIT = ModelSchema(
        name = "visual_audit",
        requiredFields = linkedMapOf(
            "listing_name" to FieldType.STRING,
            "image_description" to FieldType.STRING,
            "mismatch" to FieldType.BOOLEAN,
            "issue" to FieldType.STRING,
            "confidence" to FieldType.NUMBER,
        ),
        requiredNonBlank = setOf("listing_name", "image_description"),
        numberRanges = mapOf("confidence" to 0.0..1.0),
    )

    /** {"send":true,"message":"..."} for conversation reply decisions. */
    val CONVERSATION_DECISION = ModelSchema(
        name = "conversation_decision",
        requiredFields = linkedMapOf(
            "send" to FieldType.BOOLEAN,
            "message" to FieldType.STRING,
        ),
    )

    /** {"broadcast_message","tiktok_caption","featured_product"} per MorningBroadcastModule. */
    val BROADCAST_PLAN = ModelSchema(
        name = "broadcast_plan",
        requiredFields = linkedMapOf(
            "broadcast_message" to FieldType.STRING,
            "tiktok_caption" to FieldType.STRING,
            "featured_product" to FieldType.STRING,
        ),
    )

    /** {"send":false,"message":""} per FollowUpEngine. */
    val FOLLOW_UP_DECISION = ModelSchema(
        name = "follow_up_decision",
        requiredFields = linkedMapOf(
            "send" to FieldType.BOOLEAN,
            "message" to FieldType.STRING,
        ),
    )

    /** Inbound customer-reply decision per ConversationEngine. */
    val CONVERSATION_REPLY = ModelSchema(
        name = "conversation_reply",
        requiredFields = linkedMapOf(
            "classification" to FieldType.STRING,
            "response" to FieldType.STRING,
            "escalate" to FieldType.BOOLEAN,
        ),
        enums = mapOf(
            "classification" to setOf(
                "inquiry", "order_intent", "complaint", "bulk_order",
                "custom_request", "price_negotiation", "spam",
            ),
            "escalation_urgency" to setOf("low", "medium", "high", "urgent"),
        ),
        optionalFields = setOf(
            "escalation_reason", "escalation_urgency",
            "suggested_owner_reply", "follow_up_hours", "detected_name",
        ),
    )

    /**
     * Nightly listing analysis per ListingIntelligenceModule. The consequential
     * proposal/ad-copy fields must be non-blank, price positioning is a closed
     * enum, keywords are a string array, and the improvement score stays honest
     * inside 0..100 so no junk can reach an approval request or owner note.
     */
    val LISTING_ANALYSIS = ModelSchema(
        name = "listing_analysis",
        requiredFields = linkedMapOf(
            "weakness_found" to FieldType.STRING,
            "missing_keywords" to FieldType.ARRAY,
            "price_position" to FieldType.STRING,
            "improved_title" to FieldType.STRING,
            "improved_description" to FieldType.STRING,
            "ad_headline" to FieldType.STRING,
            "ad_caption" to FieldType.STRING,
            "improvement_score" to FieldType.NUMBER,
        ),
        enums = mapOf("price_position" to setOf("too high", "competitive", "low")),
        optionalFields = setOf("owner_note"),
        requiredNonBlank = setOf("improved_title", "improved_description", "ad_headline", "ad_caption"),
        numberRanges = mapOf("improvement_score" to 0.0..100.0),
        arrayElements = mapOf("missing_keywords" to ElementSchema(elementType = FieldType.STRING)),
    )

    /**
     * Polished WhatsApp caption for one Soko Studio product per
     * SokoStudioSharingModule. A blank caption can never enter a send transaction.
     */
    val STUDIO_CAPTION = ModelSchema(
        name = "studio_share_caption",
        requiredFields = linkedMapOf("caption" to FieldType.STRING),
        requiredNonBlank = setOf("caption"),
        maxStringLengths = mapOf("caption" to MAX_CAPTION_CHARS),
    )

    /**
     * TikTok draft caption per TikTokSkill.generateCaption. The publish decision is
     * NEVER model-authored (it stays an explicit owner command); the model only
     * drafts the caption text.
     */
    val TIKTOK_CAPTION = ModelSchema(
        name = "tiktok_caption_draft",
        requiredFields = linkedMapOf("caption" to FieldType.STRING),
        requiredNonBlank = setOf("caption"),
        maxStringLengths = mapOf("caption" to MAX_CAPTION_CHARS),
    )

    /**
     * Outgoing message generation toward a saved contact: the send decision is a
     * typed boolean and a send=true answer must carry the actual message text.
     */
    val CONTACT_MESSAGE_GENERATION = ModelSchema(
        name = "contact_message_generation",
        requiredFields = linkedMapOf(
            "send" to FieldType.BOOLEAN,
            "message" to FieldType.STRING,
        ),
        conditionalRequirements = mapOf(
            "message" to ConditionalRequirement(gateField = "send", whenGateEquals = true),
        ),
    )

    /**
     * Classification of one inbound WhatsApp message. Mirrors the conversation-reply
     * contract with strict conditional rules: a non-escalating reply needs response
     * text, an escalation needs a stated reason, urgency is a closed enum.
     */
    val WHATSAPP_INBOUND_CLASSIFICATION = ModelSchema(
        name = "whatsapp_inbound_classification",
        requiredFields = linkedMapOf(
            "classification" to FieldType.STRING,
            "response" to FieldType.STRING,
            "escalate" to FieldType.BOOLEAN,
        ),
        enums = mapOf(
            "classification" to setOf(
                "inquiry", "order_intent", "complaint", "bulk_order",
                "custom_request", "price_negotiation", "spam", "missed_call",
            ),
            "escalation_urgency" to setOf("low", "medium", "high", "urgent"),
        ),
        optionalFields = setOf(
            "escalation_reason", "escalation_urgency",
            "suggested_owner_reply", "follow_up_hours", "detected_name",
        ),
        conditionalRequirements = mapOf(
            "response" to ConditionalRequirement(gateField = "escalate", whenGateEquals = false),
            "escalation_reason" to ConditionalRequirement(gateField = "escalate", whenGateEquals = true),
        ),
    )

    /**
     * Text-only Soko service listing proposals consumed by SokoIntelligenceModule
     * (Agent 2). Published here per contract §6; Agent 2 wires the constant into
     * their call site — see coordination/INTERFACE_REQUESTS.md.
     */
    val SOKO_SERVICE_TEXT_PROPOSALS = ModelSchema(
        name = "soko_service_text_proposals",
        requiredFields = linkedMapOf("proposals" to FieldType.ARRAY),
        arrayElements = mapOf(
            "proposals" to ElementSchema(
                requiredFields = linkedMapOf(
                    "current_name" to FieldType.STRING,
                    "proposed_name" to FieldType.STRING,
                    "proposed_description" to FieldType.STRING,
                    "reason" to FieldType.STRING,
                ),
                requiredNonBlank = setOf("current_name", "proposed_name", "proposed_description"),
            ),
        ),
    )

    val ALL: Map<String, ModelSchema> = listOf(
        PLANNER_PLAN, RECOVERY_PLAN, VISUAL_AUDIT,
        CONVERSATION_DECISION, BROADCAST_PLAN, FOLLOW_UP_DECISION,
        CONVERSATION_REPLY,
        LISTING_ANALYSIS, STUDIO_CAPTION, TIKTOK_CAPTION,
        CONTACT_MESSAGE_GENERATION, WHATSAPP_INBOUND_CLASSIFICATION,
        SOKO_SERVICE_TEXT_PROPOSALS,
    ).associateBy { it.name }

    /** Resolves a stage label to its schema; null for unknown stages. */
    fun schemaFor(stage: String): ModelSchema? = when (stage.trim().lowercase().replace('-', '_')) {
        "planner", "plan", "planner_plan", "task_plan", "autonomy_plan" -> PLANNER_PLAN
        "recovery", "recovery_plan", "foreground_recovery" -> RECOVERY_PLAN
        "visual_audit", "vision", "vision_audit", "listing_audit" -> VISUAL_AUDIT
        "conversation", "conversation_decision", "reply_decision" -> CONVERSATION_DECISION
        "conversation_reply", "inbound_reply", "customer_reply" -> CONVERSATION_REPLY
        "broadcast", "broadcast_plan", "morning_broadcast" -> BROADCAST_PLAN
        "follow_up", "follow_up_decision", "followup", "followup_decision" -> FOLLOW_UP_DECISION
        "listing_analysis", "listing_intelligence", "nightly_listing_review" -> LISTING_ANALYSIS
        "studio_share_caption", "studio_caption", "sharing_decision", "share_soko_studio_caption" -> STUDIO_CAPTION
        "tiktok_caption_draft", "tiktok_caption", "tiktok_draft", "tiktok_draft_or_publish" -> TIKTOK_CAPTION
        "contact_message_generation", "contact_message" -> CONTACT_MESSAGE_GENERATION
        "whatsapp_inbound_classification", "whatsapp_inbound", "inbound_classification" -> WHATSAPP_INBOUND_CLASSIFICATION
        "soko_service_text_proposals", "soko_text_proposals" -> SOKO_SERVICE_TEXT_PROPOSALS
        else -> null
    }

    /** Hard safety ceiling for generated captions; prompts ask for far shorter text. */
    const val MAX_CAPTION_CHARS = 600
}
