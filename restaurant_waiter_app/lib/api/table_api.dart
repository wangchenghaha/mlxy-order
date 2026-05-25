import 'api_service.dart';

class TableApi {
  static Future<List<dynamic>> list() async =>
      List<dynamic>.from(ApiService.unwrap(
        await ApiService.dio.get(
          '/waiter/tables',
          queryParameters: {'_t': DateTime.now().millisecondsSinceEpoch},
        ),
      ));
  static Future<dynamic> open(int id, int people) async =>
      ApiService.unwrap(await ApiService.dio.post('/waiter/tables/$id/open',
          data: {'people': people}, options: ApiService.idempotencyOptions()));
  static Future<dynamic> transfer(int fromTableId, int toTableId) async =>
      ApiService.unwrap(await ApiService.dio.post('/waiter/tables/transfer',
          data: {'fromTableId': fromTableId, 'toTableId': toTableId},
          options: ApiService.idempotencyOptions()));
  static Future<dynamic> cleanComplete(int id) async => ApiService.unwrap(
      await ApiService.dio.post('/waiter/tables/$id/clean-complete',
          options: ApiService.idempotencyOptions()));
}
