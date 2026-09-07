import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../bridge/agent_channel.dart';

/// Uganda/E.164 normalization mirroring the Android ContactDirectory normalizer:
/// 07… → +256…, 2567… → +256…, 7… (9 digits) → +256…; null when unparseable.
String? normalizeUgandaPhone(String? raw) {
  if (raw == null) return null;
  final cleaned = raw.replaceAll(RegExp(r'[^0-9+]'), '');
  final digits = cleaned.replaceAll(RegExp(r'[^0-9]'), '');
  if (digits.isEmpty) return null;
  if (cleaned.startsWith('+')) {
    return digits.startsWith('256') && digits.length == 12 ? '+$digits' : null;
  }
  if (digits.startsWith('256') && digits.length == 12) return '+$digits';
  if (digits.startsWith('0') && digits.length == 10) {
    return '+256${digits.substring(1)}';
  }
  if (digits.startsWith('7') && digits.length == 9) return '+256$digits';
  return null;
}

class ContactPermissionsScreen extends StatefulWidget {
  const ContactPermissionsScreen({super.key, this.groupsOnly = false});
  final bool groupsOnly;

  @override
  State<ContactPermissionsScreen> createState() =>
      _ContactPermissionsScreenState();
}

class _ContactPermissionsScreenState extends State<ContactPermissionsScreen> {
  List<Map<String, dynamic>> _permissions = [];
  List<Map<String, dynamic>> _discovered = [];
  bool _loading = true;
  bool _discovering = false;
  bool _bulkBusy = false;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final perms = await AgentChannel.contactPermissions();
    if (!mounted) return;
    setState(() {
      _permissions = perms;
      _loading = false;
    });
  }

  Future<void> _discover() async {
    if (_discovering) return;
    setState(() => _discovering = true);
    try {
      final discovered = await AgentChannel.discoverAllContacts();
      if (!mounted) return;
      setState(() {
        _permissions = discovered;
        _discovered = [];
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      if (error.code == 'CONTACTS_PERMISSION_REQUIRED') {
        await AgentChannel.requestContactsPermission();
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Allow Contacts, then tap Discover all again.'),
            ),
          );
        }
      }
    } finally {
      if (mounted) setState(() => _discovering = false);
    }
  }

  Future<void> _setAll(String permission) async {
    // The existing bulk endpoint covers the whole directory. Never invoke it
    // from the group-only view where it would unexpectedly affect individuals.
    if (widget.groupsOnly) return;
    if (_bulkBusy || _permissions.isEmpty) return;
    final granting = permission == 'FULL';
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(granting ? 'Allow all contacts?' : 'Revoke all contacts?'),
        content: Text(
          granting
              ? 'This grants monitoring, replying, sending, and writing permission to every unique discovered contact and group. Ambiguous identities remain blocked.'
              : 'Amara will immediately stop monitoring, replying to, sending to, and writing for every saved contact and group.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(granting ? 'Allow all' : 'Revoke all'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _bulkBusy = true);
    try {
      final result = await AgentChannel.setAllContactPermissions(permission);
      await _refresh();
      if (!mounted) return;
      final changed = result['changed'] ?? 0;
      final skipped = result['skippedAmbiguous'] ?? 0;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '${granting ? 'Authorized' : 'Revoked'} $changed contact(s).${skipped > 0 ? ' $skipped ambiguous contact(s) stayed blocked.' : ''}',
          ),
        ),
      );
    } finally {
      if (mounted) setState(() => _bulkBusy = false);
    }
  }

  Future<void> _pickFromContacts() async {
    final picked = await AgentChannel.pickContact();
    if (picked == null || !mounted) return;
    final name = picked['name']?.toString() ?? '';
    if (name.isEmpty) return;
    await _setPermission(
      name,
      normalizeUgandaPhone(picked['number']?.toString()),
      false,
      'FULL',
      contactId: picked['id']?.toString(),
    );
  }

  Future<void> _pickFromWhatsApp() async {
    final picked = await AgentChannel.pickWhatsAppContact();
    if (picked == null || !mounted) return;
    final name = picked['name']?.toString() ?? '';
    if (name.isEmpty) return;
    await _setPermission(
      name,
      normalizeUgandaPhone(picked['number']?.toString()),
      false,
      'FULL',
      contactId: picked['id']?.toString(),
    );
  }

  Future<void> _setPermission(
    String name,
    String? number,
    bool isGroup,
    String permission, {
    String? contactId,
  }) async {
    await AgentChannel.setContactPermission(
      name: name,
      number: number,
      isGroup: isGroup,
      permission: permission,
      contactId: contactId,
    );
    await _refresh();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(
        widget.groupsOnly ? 'WhatsApp Groups' : 'Contact permissions',
      ),
      actions: [
        PopupMenuButton<String>(
          icon: const Icon(Icons.add),
          onSelected: (v) {
            if (v == 'contacts') _pickFromContacts();
            if (v == 'whatsapp') _pickFromWhatsApp();
            if (v == 'discover') _discover();
          },
          itemBuilder: (_) => const [
            PopupMenuItem(value: 'contacts', child: Text('Pick from contacts')),
            PopupMenuItem(value: 'whatsapp', child: Text('Pick from WhatsApp')),
            PopupMenuItem(
              value: 'discover',
              child: Text('Discover phone + WhatsApp'),
            ),
          ],
        ),
      ],
    ),
    body: _loading
        ? const Center(child: CircularProgressIndicator())
        : ListView(
            padding: const EdgeInsets.fromLTRB(18, 12, 18, 40),
            children: [
              if (!widget.groupsOnly)
                ListTile(
                  leading: const Icon(Icons.groups_outlined),
                  title: const Text('WhatsApp Groups'),
                  subtitle: const Text(
                    'Dedicated monitoring and reply permissions for groups',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.push<void>(
                    context,
                    MaterialPageRoute(
                      builder: (_) =>
                          const ContactPermissionsScreen(groupsOnly: true),
                    ),
                  ).then((_) => _refresh()),
                ),
              if (widget.groupsOnly)
                const Card(
                  child: Padding(
                    padding: EdgeInsets.all(12),
                    child: Text(
                      'Watch permits monitoring only. Reply permits responding. Neither schedules ads. Group rules, ad windows and commercial permissions must be configured before promotion starts.',
                    ),
                  ),
                ),
              const Text(
                'Choose which contacts and groups Amara can monitor, reply to, send to, or write for. '
                'Add contacts by picking from your address book or WhatsApp.',
                style: TextStyle(color: Colors.white60, height: 1.5),
              ),
              const SizedBox(height: 18),
              TextField(
                onChanged: (value) => setState(() => _query = value.trim()),
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search),
                  hintText: 'Search contacts and groups',
                ),
              ),
              const SizedBox(height: 12),
              if (!widget.groupsOnly)
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    FilledButton.icon(
                      onPressed: _discovering ? null : _discover,
                      icon: _discovering
                          ? const SizedBox.square(
                              dimension: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.manage_search),
                      label: const Text('Discover all'),
                    ),
                    OutlinedButton.icon(
                      onPressed: _bulkBusy || _permissions.isEmpty
                          ? null
                          : () => _setAll('FULL'),
                      icon: const Icon(Icons.done_all),
                      label: const Text('Allow all'),
                    ),
                    TextButton.icon(
                      onPressed: _bulkBusy || _permissions.isEmpty
                          ? null
                          : () => _setAll('NONE'),
                      icon: const Icon(Icons.block),
                      label: const Text('Revoke all'),
                    ),
                  ],
                ),
              const SizedBox(height: 18),
              if (_discovered.isNotEmpty) ...[
                _heading('DISCOVERED', _filtered(_discovered).length),
                ..._filtered(_discovered).map(
                  (c) => _contactTile(
                    name: c['name']?.toString() ?? '',
                    isGroup: c['isGroup'] == true,
                    permission: c['permission']?.toString() ?? 'NONE',
                    source: c['source']?.toString(),
                    ambiguous: c['ambiguous'] == true,
                    onSet: (p) => _setPermission(
                      c['name']?.toString() ?? '',
                      normalizeUgandaPhone(c['number']?.toString()),
                      c['isGroup'] == true,
                      p,
                      contactId: c['id']?.toString(),
                    ),
                  ),
                ),
                const SizedBox(height: 18),
              ],
              _heading(
                widget.groupsOnly ? 'WHATSAPP GROUPS' : 'CONTACT DIRECTORY',
                _filtered(_permissions).length,
              ),
              if (_permissions.isEmpty)
                _empty(
                  'No contacts have permissions yet. Use + to add contacts.',
                )
              else
                ..._filtered(_permissions).map(
                  (c) => _contactTile(
                    name: c['name']?.toString() ?? '',
                    isGroup: c['isGroup'] == true,
                    permission: c['permission']?.toString() ?? 'NONE',
                    source: c['source']?.toString(),
                    ambiguous: c['ambiguous'] == true,
                    onSet: (p) => _setPermission(
                      c['name']?.toString() ?? '',
                      normalizeUgandaPhone(c['number']?.toString()),
                      c['isGroup'] == true,
                      p,
                      contactId: c['id']?.toString(),
                    ),
                  ),
                ),
            ],
          ),
  );

  Widget _heading(String title, int count) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Text(
      '$title  $count',
      style: const TextStyle(
        color: Colors.white38,
        letterSpacing: 1.5,
        fontWeight: FontWeight.w800,
        fontSize: 11,
      ),
    ),
  );

  Widget _empty(String text) => Card(
    child: Padding(
      padding: const EdgeInsets.all(18),
      child: Text(text, style: const TextStyle(color: Colors.white54)),
    ),
  );

  Widget _contactTile({
    required String name,
    required bool isGroup,
    required String permission,
    required ValueChanged<String> onSet,
    String? source,
    bool ambiguous = false,
  }) {
    const options = ['NONE', 'MONITOR', 'REPLY', 'SEND', 'FULL'];
    const labels = ['None', 'Watch', 'Reply', 'Send', 'All'];
    final currentIndex = options
        .indexOf(permission)
        .clamp(0, options.length - 1);
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  isGroup ? Icons.groups_outlined : Icons.person_outline,
                  color: const Color(0xFF50E3C2),
                  size: 18,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        name,
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                      if (source?.isNotEmpty == true || ambiguous)
                        Text(
                          [
                            if (source?.isNotEmpty == true)
                              _sourceLabel(source!),
                            if (ambiguous) 'Ambiguous · blocked',
                          ].join(' · '),
                          style: TextStyle(
                            color: ambiguous
                                ? const Color(0xFFF97316)
                                : Colors.white38,
                            fontSize: 11,
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              children: List.generate(
                options.length,
                (i) => ChoiceChip(
                  label: Text(labels[i], style: const TextStyle(fontSize: 11)),
                  selected: i == currentIndex,
                  onSelected: ambiguous ? null : (_) => onSet(options[i]),
                  selectedColor: const Color(0xFF50E3C2).withValues(alpha: 0.3),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  List<Map<String, dynamic>> _filtered(List<Map<String, dynamic>> contacts) {
    if (widget.groupsOnly) {
      contacts = contacts
          .where((contact) => contact['isGroup'] == true)
          .toList();
    }
    final needle = _query.toLowerCase();
    if (needle.isEmpty) return contacts;
    return contacts
        .where(
          (contact) =>
              contact['name']?.toString().toLowerCase().contains(needle) ==
              true,
        )
        .toList(growable: false);
  }

  String _sourceLabel(String source) => switch (source) {
    'ANDROID_CONTACTS' => 'Phone contact',
    'WHATSAPP' => 'WhatsApp',
    'OWNER_CREATED' => 'Added by owner',
    _ => 'Observed',
  };
}
