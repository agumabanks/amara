import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/market/insight_sheet.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'price preview validates reduction and requests a proposal only',
    (tester) async {
      const channel = MethodChannel('com.sanaa.agent/core');
      final commands = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            commands.add((call.arguments as Map)['command'] as String);
            return {'success': false, 'message': 'Shop verification required'};
          });
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: InsightSheet(
              kind: 'comparison',
              item: {'product': 'Receipt printer', 'ourPriceUgx': 100000},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byType(TextField));
      await tester.enterText(find.byType(TextField), '100');
      await tester.pump();
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey('price-proposal')),
            )
            .onPressed,
        isNull,
      );
      await tester.enterText(find.byType(TextField), '12');
      await tester.pump();
      expect(find.text('UGX 100000 → UGX 88000'), findsOneWidget);
      await tester.ensureVisible(find.text('Request price proposal'));
      await tester.tap(find.text('Request price proposal'));
      await tester.pumpAndSettle();
      expect(commands.single, contains('to UGX 88000'));
      expect(commands.single, contains('do not apply the edit'));
      expect(find.text('Shop verification required'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    },
  );
}
