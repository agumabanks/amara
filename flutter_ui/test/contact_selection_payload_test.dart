import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/contacts/contact_permissions_screen.dart';

void main() {
  const channel = MethodChannel('com.sanaa.agent/core');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets(
    'picking a contact submits durable id, name, and normalized number',
    (tester) async {
      final submissions = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'contactPermissions') return <dynamic>[];
            if (call.method == 'pickContact') {
              return <String, dynamic>{
                'id': 'c-42',
                'name': 'Nakato',
                'number': '0772 123-456',
              };
            }
            if (call.method == 'setContactPermission') {
              submissions.add(call);
              return true;
            }
            return false;
          });

      await tester.pumpWidget(
        const MaterialApp(home: ContactPermissionsScreen()),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byIcon(Icons.add));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Pick from contacts'));
      await tester.pumpAndSettle();

      expect(submissions, hasLength(1));
      final payload = submissions.first.arguments as Map<Object?, Object?>;
      expect(payload['contactId'], 'c-42');
      expect(payload['name'], 'Nakato');
      expect(payload['number'], '+256772123456');
      expect(payload['isGroup'], false);
      expect(payload['permission'], 'FULL');
    },
  );

  testWidgets('normalizeUgandaPhone mirrors the Android normalizer', (
    tester,
  ) async {
    expect(normalizeUgandaPhone('0772123456'), '+256772123456');
    expect(normalizeUgandaPhone('+256 772 123 456'), '+256772123456');
    expect(normalizeUgandaPhone('256772123456'), '+256772123456');
    expect(normalizeUgandaPhone('772123456'), '+256772123456');
    expect(normalizeUgandaPhone('not a phone'), isNull);
    expect(normalizeUgandaPhone(''), isNull);
    expect(normalizeUgandaPhone(null), isNull);
  });

  testWidgets('unified discovery renders phone and WhatsApp provenance', (
    tester,
  ) async {
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          if (call.method == 'contactPermissions') return <dynamic>[];
          if (call.method == 'discoverAllContacts') {
            return <dynamic>[
              <String, dynamic>{
                'id': 'phone-1',
                'name': 'Phone Person',
                'number': '+256700000001',
                'isGroup': false,
                'permission': 'NONE',
                'source': 'ANDROID_CONTACTS',
                'ambiguous': false,
              },
              <String, dynamic>{
                'id': 'wa-1',
                'name': 'Sales Group',
                'number': '',
                'isGroup': true,
                'permission': 'NONE',
                'source': 'WHATSAPP',
                'ambiguous': false,
              },
            ];
          }
          return false;
        });

    await tester.pumpWidget(
      const MaterialApp(home: ContactPermissionsScreen()),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Discover all'));
    await tester.pumpAndSettle();

    expect(calls, contains('discoverAllContacts'));
    expect(find.text('Phone Person'), findsOneWidget);
    expect(find.text('Phone contact'), findsOneWidget);
    expect(find.text('Sales Group'), findsOneWidget);
    expect(find.text('WhatsApp'), findsOneWidget);
  });

  testWidgets(
    'Allow all requires confirmation and invokes bulk authority once',
    (tester) async {
      final bulkCalls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'contactPermissions') {
              return <dynamic>[
                <String, dynamic>{
                  'id': 'c-1',
                  'name': 'Customer One',
                  'number': '+256700000001',
                  'isGroup': false,
                  'permission': 'NONE',
                  'source': 'ANDROID_CONTACTS',
                  'ambiguous': false,
                },
              ];
            }
            if (call.method == 'setAllContactPermissions') {
              bulkCalls.add(call);
              return <String, dynamic>{'changed': 1, 'skippedAmbiguous': 0};
            }
            return false;
          });

      await tester.pumpWidget(
        const MaterialApp(home: ContactPermissionsScreen()),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Allow all'));
      await tester.pumpAndSettle();
      expect(bulkCalls, isEmpty);
      await tester.tap(find.text('Allow all').last);
      await tester.pumpAndSettle();

      expect(bulkCalls, hasLength(1));
      expect(
        (bulkCalls.single.arguments as Map<Object?, Object?>)['permission'],
        'FULL',
      );
    },
  );
}
