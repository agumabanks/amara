import 'dart:convert';

import 'package:flutter/material.dart';
import '../../bridge/agent_channel.dart';

/// Owner-facing commercial controls: policy editing, the durable consent ledger,
/// owner-confirmed sales, opt-outs, and evidence drill-down over every dashboard
/// figure. Every figure links to its ledger evidence interactively — not just prose.
class CommercialScreen extends StatefulWidget {
  const CommercialScreen({
    super.key,
    this.initialTab = 0,
    this.revenue = const {},
  });
  final int initialTab;

  /// The latest [AgentChannel.revenueDashboard] payload, for evidence drill-down.
  final Map<String, dynamic> revenue;
  @override
  State<CommercialScreen> createState() => _CommercialScreenState();
}

class _CommercialScreenState extends State<CommercialScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs;
  Map<String, dynamic> _policy = const {};
  List<Map<String, dynamic>> _consents = const [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(
      length: 3,
      vsync: this,
      initialIndex: widget.initialTab,
    );
    _refresh();
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final policyJson = await AgentChannel.commercialPolicyGet();
      final consents = await AgentChannel.consentLedger();
      if (mounted) {
        setState(() {
          _policy = policyJson == null ? const {} : parsePolicy(policyJson);
          _consents = consents;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e.toString();
          _loading = false;
        });
      }
    }
  }

  Future<void> _savePolicyField(String key, String value) async {
    final updated = Map<String, dynamic>.from(_policy);
    updated[key] = value;
    final ok = await AgentChannel.commercialPolicySave(encodePolicy(updated));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(ok ? 'Policy saved' : 'Save refused — invalid policy'),
      ),
    );
    await _refresh();
  }

  void _startPolicySetup() {
    setState(() {
      // This is an editable, fail-closed draft only. Empty allow-lists, audience,
      // and timezone keep all outreach disabled until the owner deliberately fills
      // and saves the relevant fields.
      _policy = <String, dynamic>{
        'dailyQualifiedInquiryTarget': 1,
        'weeklyVerifiedSaleTarget': 3,
        'monthlyProfitFloorUgx': 0,
        'ownerTimeZoneId': '',
        'allowedProducts': '',
        'approvedChannels': '',
        'permittedAudience': '',
        'quietHoursStart': '',
        'quietHoursEnd': '',
        'dailyGlobalMessageCap': 0,
        'perCustomerDailyCap': 0,
        'followUpLimitPerOpportunity': 0,
        'discountCeilingPercent': 0,
        'attributionWindowDays': 7,
        'campaignBudgetUgx': 0,
      };
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Commercial'),
        bottom: TabBar(
          controller: _tabs,
          tabs: const [
            Tab(text: 'POLICY'),
            Tab(text: 'CONSENT'),
            Tab(text: 'EVIDENCE'),
          ],
        ),
        actions: [
          IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : (_error != null
                ? Center(
                    child: Text(
                      _error!,
                      style: const TextStyle(color: Colors.red),
                    ),
                  )
                : TabBarView(
                    controller: _tabs,
                    children: [_policyTab(), _consentTab(), _evidenceTab()],
                  )),
    );
  }

  // ---------------- POLICY ----------------

  Widget _policyTab() => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      if (_policy.isEmpty) ...[
        const Card(
          child: Padding(
            padding: EdgeInsets.all(16),
            child: Text(
              'No policy configured. Missing policy fails closed: '
              'no outreach happens until you configure targets and allow-lists.',
            ),
          ),
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: _startPolicySetup,
          icon: const Icon(Icons.tune),
          label: const Text('Configure commercial policy'),
        ),
      ] else ...[
        _policyField('Daily inquiry target', 'dailyQualifiedInquiryTarget'),
        _policyField('Weekly sale target', 'weeklyVerifiedSaleTarget'),
        _policyField('Monthly profit floor (UGX)', 'monthlyProfitFloorUgx'),
        _policyField(
          'Owner timezone',
          'ownerTimeZoneId',
          hint: 'e.g. Africa/Kampala',
        ),
        _policyField('Allowed products (comma-separated)', 'allowedProducts'),
        _policyField('Approved channels (comma-separated)', 'approvedChannels'),
        _policyField('Permitted audience', 'permittedAudience'),
        _policyField('Quiet hours start (HH:mm)', 'quietHoursStart'),
        _policyField('Quiet hours end (HH:mm)', 'quietHoursEnd'),
        _policyField('Daily global message cap', 'dailyGlobalMessageCap'),
        _policyField('Per-customer daily cap', 'perCustomerDailyCap'),
        _policyField(
          'Follow-up limit per opportunity',
          'followUpLimitPerOpportunity',
        ),
        _policyField('Discount ceiling %', 'discountCeilingPercent'),
        _policyField('Attribution window (days)', 'attributionWindowDays'),
        _policyField('Campaign budget (UGX)', 'campaignBudgetUgx'),
      ],
    ],
  );

  Widget _policyField(String label, String key, {String? hint}) {
    final controller = TextEditingController(text: '${_policy[key] ?? ''}');
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: controller,
              decoration: InputDecoration(labelText: label, hintText: hint),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.save),
            tooltip: 'Save $label',
            onPressed: () => _savePolicyField(key, controller.text.trim()),
          ),
        ],
      ),
    );
  }

  // ---------------- CONSENT ----------------

  Widget _consentTab() => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      for (final c in _consents)
        Card(
          child: ListTile(
            title: Text(
              '${c['contact']}',
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            subtitle: Text(
              'scope ${c['scope']} · source ${c['source']} · '
              'revoked: ${c['revokedAt'] != null}\nevidence: ${c['evidenceRef']}',
            ),
            isThreeLine: true,
            trailing: c['revokedAt'] == null
                ? IconButton(
                    icon: const Icon(Icons.block),
                    tooltip: 'Revoke consent',
                    onPressed: () => AgentChannel.revokeContactConsent(
                      '${c['contact']}',
                      'owner revocation',
                    ).then((_) => _refresh()),
                  )
                : const Icon(Icons.block, color: Colors.red),
          ),
        ),
      const SizedBox(height: 8),
      FilledButton.icon(
        icon: const Icon(Icons.add_moderator),
        label: const Text('Grant consent'),
        onPressed: _grantConsentDialog,
      ),
      const SizedBox(height: 24),
      FilledButton.tonalIcon(
        icon: const Icon(Icons.record_voice_over),
        label: const Text('Confirm a completed sale'),
        onPressed: _confirmSaleDialog,
      ),
    ],
  );

  Future<void> _grantConsentDialog() async {
    final contact = TextEditingController();
    final evidence = TextEditingController();
    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Grant contact consent'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: contact,
              decoration: const InputDecoration(labelText: 'Contact identity'),
            ),
            TextField(
              controller: evidence,
              decoration: const InputDecoration(
                labelText: 'Evidence reference (required)',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Grant'),
          ),
        ],
      ),
    );
    if (result == true &&
        contact.text.trim().isNotEmpty &&
        evidence.text.trim().isNotEmpty) {
      await AgentChannel.grantContactConsent(
        contactKey: contact.text.trim(),
        evidenceRef: evidence.text.trim(),
      );
      await _refresh();
    }
  }

  Future<void> _confirmSaleDialog() async {
    final saleRef = TextEditingController();
    final contact = TextEditingController();
    final product = TextEditingController();
    final amount = TextEditingController();
    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Confirm a completed sale'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: saleRef,
              decoration: const InputDecoration(
                labelText: 'Sale/order reference',
              ),
            ),
            TextField(
              controller: contact,
              decoration: const InputDecoration(labelText: 'Customer'),
            ),
            TextField(
              controller: product,
              decoration: const InputDecoration(labelText: 'Product'),
            ),
            TextField(
              controller: amount,
              decoration: const InputDecoration(labelText: 'Amount (UGX)'),
              keyboardType: TextInputType.number,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Record'),
          ),
        ],
      ),
    );
    if (result == true &&
        saleRef.text.trim().isNotEmpty &&
        contact.text.trim().isNotEmpty &&
        product.text.trim().isNotEmpty) {
      final amountUgx = num.tryParse(amount.text.trim()) ?? 0;
      final uniqueKey = await AgentChannel.confirmSaleByOwner(
        saleRef: saleRef.text.trim(),
        contactKey: contact.text.trim(),
        productRef: product.text.trim(),
        amountUgx: amountUgx,
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              uniqueKey.isEmpty
                  ? 'Sale refused by the ledger (duplicate or missing evidence)'
                  : 'Sale recorded: $uniqueKey',
            ),
          ),
        );
      }
      await _refresh();
    }
  }

  // ---------------- EVIDENCE ----------------

  Widget _evidenceTab() {
    final revenue = widget.revenue;
    final inquiries = (revenue['inquiryEvidence'] ?? const []).cast<Map>();
    final sales = (revenue['saleEvidence'] ?? const []).cast<Map>();
    final attribution = (revenue['attributionChain'] ?? const []).cast<Map>();
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'Qualified inquiries today',
          style: TextStyle(fontWeight: FontWeight.w700),
        ),
        if (inquiries.isEmpty) const Text('No inquiries recorded today.'),
        for (final i in inquiries)
          ListTile(
            dense: true,
            leading: const Icon(Icons.question_answer),
            title: Text('${i['product']}'),
            subtitle: Text('evidence ${i['ref']}'),
          ),
        const Divider(height: 32),
        const Text(
          'Verified sales this week',
          style: TextStyle(fontWeight: FontWeight.w700),
        ),
        if (sales.isEmpty) const Text('No verified sales recorded this week.'),
        for (final s in sales)
          ListTile(
            dense: true,
            leading: const Icon(Icons.receipt_long),
            title: Text('UGX ${s['amount']} — ${s['kind']}'),
            subtitle: Text('source record ${s['ref']}'),
          ),
        const Divider(height: 32),
        const Text(
          'Attribution chain (month)',
          style: TextStyle(fontWeight: FontWeight.w700),
        ),
        if (attribution.isEmpty)
          const Text('No attribution rows recorded this month.'),
        for (final a in attribution)
          ListTile(
            dense: true,
            leading: const Icon(Icons.link),
            title: Text('${a['label']} via ${a['rule']}'),
            subtitle: Text(
              'campaign ${a['campaign']} · touch ${a['touch']} · window ${a['windowDays']}d',
            ),
          ),
      ],
    );
  }
}

/// Parses the canonical CommercialPolicy JSON into the editable field map.
Map<String, dynamic> parsePolicy(String json) {
  final decoded = jsonDecode(json.isEmpty ? '{}' : json);
  final map = (decoded as Map?)?.cast<String, dynamic>() ?? <String, dynamic>{};
  return {
    'dailyQualifiedInquiryTarget': map['dailyQualifiedInquiryTarget'],
    'weeklyVerifiedSaleTarget': map['weeklyVerifiedSaleTarget'],
    'monthlyProfitFloorUgx': map['monthlyProfitFloorUgx'],
    'ownerTimeZoneId': map['ownerTimeZoneId'] ?? '',
    'allowedProducts': joinList(map['allowedProducts']),
    'approvedChannels': joinList(map['approvedChannels']),
    'permittedAudience': map['permittedAudience'] ?? '',
    'quietHoursStart': map['quietHoursStart'] ?? '',
    'quietHoursEnd': map['quietHoursEnd'] ?? '',
    'dailyGlobalMessageCap': map['dailyGlobalMessageCap'],
    'perCustomerDailyCap': map['perCustomerDailyCap'],
    'followUpLimitPerOpportunity': map['followUpLimitPerOpportunity'],
    'discountCeilingPercent': map['discountCeilingPercent'],
    'attributionWindowDays':
        (((map['attributionWindowMs'] ?? 0) as num) / 86400000).round(),
    'campaignBudgetUgx': map['campaignBudgetUgx'],
  };
}

/// Encodes the edited field map back into the canonical policy JSON shape.
String encodePolicy(Map<String, dynamic> fields) {
  int intOf(Object? v) => num.tryParse('$v')?.toInt() ?? 0;
  final quietStart = '${fields['quietHoursStart'] ?? ''}'.trim();
  final quietEnd = '${fields['quietHoursEnd'] ?? ''}'.trim();
  final policy = <String, dynamic>{
    'dailyQualifiedInquiryTarget': intOf(fields['dailyQualifiedInquiryTarget']),
    'weeklyVerifiedSaleTarget': intOf(fields['weeklyVerifiedSaleTarget']),
    'monthlyProfitFloorUgx': intOf(fields['monthlyProfitFloorUgx']),
    'ownerTimeZoneId': '${fields['ownerTimeZoneId'] ?? ''}'.trim(),
    'allowedProducts': jsonList(fields['allowedProducts']),
    'approvedChannels': jsonList(fields['approvedChannels']),
    'permittedAudience': '${fields['permittedAudience'] ?? ''}',
    if (quietStart.isNotEmpty && quietEnd.isNotEmpty) ...{
      'quietHoursStart': quietStart,
      'quietHoursEnd': quietEnd,
    },
    'dailyGlobalMessageCap': intOf(fields['dailyGlobalMessageCap']),
    'perCustomerDailyCap': intOf(fields['perCustomerDailyCap']),
    'perCustomerFrequencyWindowMs': 172800000,
    'followUpLimitPerOpportunity': intOf(fields['followUpLimitPerOpportunity']),
    'campaignBudgetUgx': intOf(fields['campaignBudgetUgx']),
    'discountCeilingPercent': intOf(fields['discountCeilingPercent']),
    // Attribution window: days → ms; a non-positive entry falls back to the
    // conservative 7-day default rather than disabling attribution.
    'attributionWindowMs': () {
      final d = intOf(fields['attributionWindowDays']);
      return d <= 0 ? 604800000 : d * 86400000;
    }(),
    'maxConcurrentExperiments': 1,
    'maxExperimentSpendUgx': intOf(fields['campaignBudgetUgx']),
  };
  return jsonEncode(policy);
}

Object? joinList(Object? raw) => raw is List ? raw.join(',') : (raw ?? '');

List<String> jsonList(Object? raw) =>
    '$raw'.split(',').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
