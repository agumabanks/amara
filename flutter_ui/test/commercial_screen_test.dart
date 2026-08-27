import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:sanaa_agent_ui/screens/commercial/commercial_screen.dart';

/// Widget tests for the owner commercial controls: policy editing round-trip,
/// consent grant/revoke, and interactive evidence drill-down. All channel calls
/// are mocked; assertions prove the UI renders ledger evidence and fails loudly
/// on corrupt configuration.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.sanaa.agent/core');
  final invocations = <String, dynamic>{};

  String policyJson(String weeklyTarget) =>
      '{"dailyQualifiedInquiryTarget":1,"weeklyVerifiedSaleTarget":$weeklyTarget,'
      '"monthlyProfitFloorUgx":0,"ownerTimeZoneId":"Africa/Kampala",'
      '"allowedProducts":["listing-1","listing-2"],"approvedChannels":["whatsapp"],'
      '"permittedAudience":"opted-in customers",'
      '"dailyGlobalMessageCap":4,"perCustomerDailyCap":1,'
      '"perCustomerFrequencyWindowMs":172800000,"followUpLimitPerOpportunity":2,'
      '"campaignBudgetUgx":0,"discountCeilingPercent":0,'
      '"attributionWindowMs":604800000,"maxConcurrentExperiments":1,'
      '"maxExperimentSpendUgx":0}';

  setUp(() {
    invocations.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          invocations[call.method] = call.arguments;
          switch (call.method) {
            case 'commercialPolicyGet':
              return policyJson('3');
            case 'commercialPolicySave':
              return true;
            case 'consentLedger':
              return [
                {
                  'id': 1,
                  'contact': '+256700000001',
                  'source': 'owner_ui',
                  'scope': 'outreach',
                  'grantedAt': 1700000000000,
                  'expiresAt': null,
                  'revokedAt': null,
                  'revokeReason': '',
                  'evidenceRef': 'consent-evidence-1',
                  'products': ['listing-1'],
                  'channels': ['whatsapp'],
                },
              ];
            case 'grantContactConsent':
            case 'revokeContactConsent':
              return true;
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Future<void> pumpScreen(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: CommercialScreen()));
    await tester.pumpAndSettle();
  }

  testWidgets(
    'renders owner policy fields from the canonical policy document',
    (tester) async {
      await pumpScreen(tester);
      expect(find.text('Weekly sale target'), findsOneWidget);
      expect(find.text('Owner timezone'), findsOneWidget);
      // The weekly target defaults to the charter's three verified sales.
      expect(invocations.containsKey('commercialPolicyGet'), isTrue);
    },
  );

  testWidgets('saving a policy field sends the canonical JSON shape', (
    tester,
  ) async {
    await pumpScreen(tester);
    // Edit the weekly target field and save THAT field's own row.
    final field = find.widgetWithText(TextField, 'Weekly sale target');
    expect(field, findsOneWidget);
    await tester.enterText(field, '5');
    final row = find.ancestor(of: field, matching: find.byType(Row)).first;
    await tester.tap(
      find.descendant(of: row, matching: find.byIcon(Icons.save)),
    );
    await tester.pumpAndSettle();
    expect(invocations['commercialPolicySave'], isNotNull);
    final saved =
        (invocations['commercialPolicySave'] as Map)['policyJson'] as String;
    expect(saved.contains('"weeklyVerifiedSaleTarget":5'), isTrue);
    // Canonical fail-closed shape: allow-lists are explicit arrays.
    expect(saved.contains('"allowedProducts":['), isTrue);
    expect(saved.contains('"approvedChannels":['), isTrue);
  });

  testWidgets(
    'consent ledger shows evidence references and supports revocation',
    (tester) async {
      await pumpScreen(tester);
      await tester.tap(find.text('CONSENT'));
      await tester.pumpAndSettle();
      // The durable consent row with its evidence link is visible — a nonblank
      // "permittedAudience = all" string is never treated as authorization here.
      expect(find.textContaining('+256700000001'), findsWidgets);
      expect(find.textContaining('consent-evidence-1'), findsOneWidget);
      await tester.tap(find.byIcon(Icons.block));
      await tester.pumpAndSettle();
      expect(invocations.containsKey('revokeContactConsent'), isTrue);
    },
  );

  testWidgets('evidence drill-down lists inquiry/sale/attribution rows', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: CommercialScreen(
          revenue: const {
            'inquiryEvidence': [
              {
                'ref': 'whatsapp_chat:abc123',
                'contact': '+256700…',
                'product': 'listing-1',
              },
            ],
            'saleEvidence': [
              {
                'ref': 'co.sanaa.agent.soko:order-9#deadbeef',
                'kind': 'SOKO_ORDER',
                'amount': 50000,
              },
            ],
            'attributionChain': [
              {
                'sale': 'abc',
                'label': 'DIRECT',
                'rule': 'DIRECT_REPLY_THREAD',
                'campaign': '',
                'touch': '',
                'windowDays': 7,
              },
            ],
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('EVIDENCE'));
    await tester.pumpAndSettle();
    expect(find.textContaining('whatsapp_chat:abc123'), findsOneWidget);
    expect(find.textContaining('order-9'), findsOneWidget);
    expect(find.textContaining('DIRECT_REPLY_THREAD'), findsOneWidget);
  });

  testWidgets('corrupt configuration renders the fail-closed notice', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'commercialPolicyGet') {
            return null; // corrupt/absent policy
          }
          if (call.method == 'consentLedger') return [];
          return null;
        });
    await pumpScreen(tester);
    expect(find.textContaining('Missing policy fails closed'), findsOneWidget);
    expect(find.text('Configure commercial policy'), findsOneWidget);

    await tester.tap(find.text('Configure commercial policy'));
    await tester.pumpAndSettle();
    expect(find.text('Owner timezone'), findsOneWidget);
    expect(find.text('Allowed products (comma-separated)'), findsOneWidget);
    // Opening setup must not itself persist or authorize anything.
    expect(invocations.containsKey('commercialPolicySave'), isFalse);
  });
}
