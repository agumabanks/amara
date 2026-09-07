import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/screens/settings/whatsapp_groups_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.sanaa.agent/core');
  testWidgets('same-name group switches save the selected identity', (
    tester,
  ) async {
    final updates = <Map<dynamic, dynamic>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'whatsappGroupSettings')
            return ['group-first', 'group-second']
                .map(
                  (id) => {
                    'id': id,
                    'name': 'Same group',
                    'listen': false,
                    'reply': false,
                    'promote': false,
                    'intervalMinutes': 1440,
                    'originVerified': true,
                    'promotionsReady': false,
                  },
                )
                .toList();
          if (call.method == 'updateWhatsappGroup')
            updates.add(call.arguments as Map);
          return true;
        });
    await tester.pumpWidget(const MaterialApp(home: WhatsAppGroupsScreen()));
    await tester.pumpAndSettle();
    expect(find.text('WhatsApp groups'), findsOneWidget);
    await tester.tap(find.widgetWithText(SwitchListTile, 'Listen').first);
    await tester.pumpAndSettle();
    expect(updates.single['id'], 'group-first');
    expect(updates.single['field'], 'listen');
    expect(updates.single['value'], true);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  testWidgets('paused promotions resume only the selected group', (tester) async {
    final updates = <Map<dynamic, dynamic>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'whatsappGroupSettings') return [{
        'id': 'paused-group', 'name': 'Checked group', 'listen': true,
        'reply': false, 'promote': true, 'paused': true,
        'lastReason': 'WhatsApp returned no matching recipient.',
        'intervalMinutes': 60,
      }];
      if (call.method == 'updateWhatsappGroup') updates.add(call.arguments as Map);
      return true;
    });
    await tester.pumpWidget(const MaterialApp(home: WhatsAppGroupsScreen()));
    await tester.pumpAndSettle();
    expect(find.textContaining('no matching recipient'), findsOneWidget);
    final resume = find.text('Resume promotions after checking');
    await tester.ensureVisible(resume);
    await tester.tap(resume);
    await tester.pumpAndSettle();
    expect(updates.single, {'id': 'paused-group', 'field': 'resume', 'value': true});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

}
