import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/work/work_screen.dart';
void main() {
 TestWidgetsFlutterBinding.ensureInitialized();
 testWidgets('bulk closure requires a disposition and sends displayed keys only', (tester) async {
  const channel=MethodChannel('com.sanaa.agent/core');
  final calls=<Map>[];
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel,(call) async {
   if(call.method=='missionControl') return <String,dynamic>{};
   if(call.method=='autonomousDashboard') return {'queue':{'needsReview': calls.isEmpty ? [
    {'key':'one','kind':'WA_REPLY_INBOUND','reason':'Uncertain'},
    {'key':'two','kind':'WA_REPLY_INBOUND','reason':'Uncertain'}] : []}};
   if(call.method=='closeWorkReviews') {calls.add(call.arguments as Map);return 2;}
   return null;
  });
  await tester.pumpWidget(const MaterialApp(home:WorkScreen()));await tester.pumpAndSettle();
  await tester.tap(find.text('Resolve listed holds (2)'));await tester.pumpAndSettle();
  expect(calls,isEmpty);
  await tester.tap(find.text('Handled elsewhere'));await tester.pumpAndSettle();
  expect(calls.single,{'keys':['one','two'],'disposition':'handled_elsewhere'});
  expect(find.text('2 holds closed. History retained; no messages sent.'),findsOneWidget);
  await tester.pumpWidget(const SizedBox.shrink());
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel,null);
 });
}
