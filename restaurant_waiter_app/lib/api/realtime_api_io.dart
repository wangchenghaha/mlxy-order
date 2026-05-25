import 'dart:convert';

import 'package:dio/dio.dart';

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
    try {
      final ticketResponse = await ApiService.dio.post('/common/events/ticket');
      final ticketData = ApiService.unwrap(ticketResponse);
      final response = await ApiService.dio.get<ResponseBody>(
        '${ApiConfig.baseUrl}/common/events',
        queryParameters: {'ticket': '${ticketData['ticket'] ?? ''}'},
        options: Options(responseType: ResponseType.stream),
      );
      var buffer = '';
      final subscription = response.data?.stream.listen(
        (chunk) {
          buffer +=
              utf8.decode(chunk, allowMalformed: true).replaceAll('\r\n', '\n');
          while (buffer.contains('\n\n')) {
            final index = buffer.indexOf('\n\n');
            final event = buffer.substring(0, index);
            buffer = buffer.substring(index + 2);
            if (event.contains('event:TABLE_CHANGED') ||
                event.contains('event:ORDER_CHANGED')) {
              onBusinessEvent();
            }
          }
        },
        onError: (_) => onDisconnected?.call(),
        onDone: () => onDisconnected?.call(),
        cancelOnError: true,
      );
      return subscription == null
          ? null
          : RealtimeConnection(subscription.cancel);
    } catch (error) {
      onDisconnected?.call();
      return null;
    }
  }
}
