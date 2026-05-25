// ignore_for_file: avoid_web_libraries_in_flutter, deprecated_member_use

import 'dart:html' as html;

import '../config/api_config.dart';
import '../utils/hive_utils.dart';
import 'api_service.dart';
import 'realtime_connection.dart';

class RealtimeApi {
  static Future<RealtimeConnection?> subscribe(
    void Function() onBusinessEvent, {
    void Function()? onDisconnected,
  }) async {
    if (HiveUtils.token.isEmpty) return null;

    final ticketResponse = await ApiService.dio.post('/common/events/ticket');
    final ticketData = ApiService.unwrap(ticketResponse);
    final uri = Uri.parse('${ApiConfig.baseUrl}/common/events').replace(
      queryParameters: {'ticket': '${ticketData['ticket'] ?? ''}'},
    );
    final source = html.EventSource(uri.toString());

    void handleBusinessEvent(html.Event event) {
      onBusinessEvent();
    }

    source.addEventListener('TABLE_CHANGED', handleBusinessEvent);
    source.addEventListener('ORDER_CHANGED', handleBusinessEvent);
    final errorSubscription =
        source.onError.listen((_) => onDisconnected?.call());

    return RealtimeConnection(() {
      source.removeEventListener('TABLE_CHANGED', handleBusinessEvent);
      source.removeEventListener('ORDER_CHANGED', handleBusinessEvent);
      errorSubscription.cancel();
      source.close();
    });
  }
}
