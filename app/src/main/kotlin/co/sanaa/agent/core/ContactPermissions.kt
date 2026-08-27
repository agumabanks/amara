package co.sanaa.agent.core

enum class ContactPermission {
    NONE,
    MONITOR,
    REPLY,
    SEND,
    FULL
}

data class ContactAccess(
    val name: String,
    val number: String?,
    val isGroup: Boolean,
    val permission: ContactPermission,
) {
    val key: String get() = "${if (isGroup) "group" else "contact"}:${number?.filter(Char::isDigit) ?: name.lowercase().filter(Char::isLetterOrDigit)}"
}

object ContactPermissions {
    private const val PREF_KEY = "contact_permissions"

    fun get(config: SecureConfig): List<ContactAccess> {
        val raw = config.contactPermissionsJson
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(ContactAccess(
                        name = obj.optString("name"),
                        number = obj.optString("number").takeIf { it.isNotBlank() },
                        isGroup = obj.optBoolean("isGroup", false),
                        permission = ContactPermission.valueOf(obj.optString("permission", "NONE")),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun set(config: SecureConfig, contacts: List<ContactAccess>) {
        val array = org.json.JSONArray()
        contacts.forEach { contact ->
            array.put(org.json.JSONObject().apply {
                put("name", contact.name)
                put("number", contact.number ?: "")
                put("isGroup", contact.isGroup)
                put("permission", contact.permission.name)
            })
        }
        config.contactPermissionsJson = array.toString()
    }

    /**
     * Effective permission for a raw label/number pair. When the canonical directory
     * hook is installed it wins: unique identities answer from durable state, ambiguous
     * identities fail closed to NONE, and revocation applies immediately even while the
     * legacy JSON still carries stale grants. Only NotFound falls back to legacy prefs.
     */
    fun permissionFor(config: SecureConfig, name: String, number: String?, isGroup: Boolean): ContactPermission {
        val directory = ContactDirectoryProvider.instance
        if (directory != null) {
            directory.permissionLevelFor(name, number, isGroup)?.let { return it }
        }
        val key = "${if (isGroup) "group" else "contact"}:${number?.filter(Char::isDigit) ?: name.lowercase().filter(Char::isLetterOrDigit)}"
        return get(config).firstOrNull { it.key == key }?.permission ?: ContactPermission.NONE
    }

    /** Directory-backed permission change; keeps the legacy JSON view in sync until the lead removes it. */
    fun setPermission(config: SecureConfig, name: String, number: String?, isGroup: Boolean, permission: ContactPermission) {
        ContactDirectoryProvider.instance?.let { directory ->
            upsertForPermission(directory, name, number, isGroup, permission)
        }
        val contacts = get(config).toMutableList()
        val key = "${if (isGroup) "group" else "contact"}:${number?.filter(Char::isDigit) ?: name.lowercase().filter(Char::isLetterOrDigit)}"
        val existing = contacts.indexOfFirst { it.key == key }
        if (existing >= 0) {
            if (permission == ContactPermission.NONE) {
                contacts.removeAt(existing)
            } else {
                contacts[existing] = ContactAccess(name, number, isGroup, permission)
            }
        } else if (permission != ContactPermission.NONE) {
            contacts.add(ContactAccess(name, number, isGroup, permission))
        }
        set(config, contacts)
    }

    private fun upsertForPermission(directory: ContactDirectory, name: String, number: String?, isGroup: Boolean, permission: ContactPermission) {
        runCatching {
            val phone = Normalizer.normalizeUganda(number)
            val resolution = directory.resolve(
                ContactQuery(name = name.takeIf { phone == null }, phone = phone, isGroup = if (phone != null) isGroup else null),
            )
            val entryId = when (resolution) {
                is Resolution.Unique -> resolution.entry.id
                is Resolution.Ambiguous -> return@runCatching
                Resolution.NotFound -> directory.upsert(
                    DirectoryEntry(
                        id = "", displayName = name, normalizedPhone = phone, aliases = emptySet(),
                        isGroup = isGroup, source = EntrySource.OWNER_CREATED, lastVerifiedAt = System.currentTimeMillis(),
                        ambiguity = Ambiguity.UNIQUE, classification = Classification.UNKNOWN,
                        commercialConsent = CommercialConsent.UNKNOWN,
                        permissions = ContactDirectoryStore.operationsForLevel(ContactPermission.NONE),
                        whatsappSurfaceEvidence = null, revocationEvidence = null,
                    ),
                ).id
            }
            ContactPermissionsGrant.applyAll(directory, entryId, permission)
        }
    }

    fun canMonitor(config: SecureConfig, name: String, number: String?, isGroup: Boolean): Boolean {
        val p = permissionFor(config, name, number, isGroup)
        return p == ContactPermission.MONITOR || p == ContactPermission.REPLY || p == ContactPermission.SEND || p == ContactPermission.FULL
    }

    fun canReply(config: SecureConfig, name: String, number: String?, isGroup: Boolean): Boolean {
        val p = permissionFor(config, name, number, isGroup)
        return p == ContactPermission.REPLY || p == ContactPermission.SEND || p == ContactPermission.FULL
    }

    fun canSend(config: SecureConfig, name: String, number: String?, isGroup: Boolean): Boolean {
        val p = permissionFor(config, name, number, isGroup)
        return p == ContactPermission.SEND || p == ContactPermission.FULL
    }

    fun canWrite(config: SecureConfig, name: String, number: String?, isGroup: Boolean): Boolean {
        return permissionFor(config, name, number, isGroup) == ContactPermission.FULL
    }
}

/** Applies a legacy cumulative level onto a durable directory entry. */
internal object ContactPermissionsGrant {
    fun applyAll(directory: ContactDirectory, id: String, level: ContactPermission) {
        val grants = ContactDirectoryStore.operationsForLevel(level)
        grants.forEach { (op, grant) -> directory.setPermission(id, op, grant == Permission.ALLOW) }
    }
}
