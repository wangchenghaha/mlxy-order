import 'api_service.dart';

class OrderApi {
  static Future<List<dynamic>> categories() async {
    final data = List<dynamic>.from(
        ApiService.unwrap(await ApiService.dio.get('/waiter/categories')));
    return data;
  }

  static Future<List<dynamic>> dishes() async {
    final data = List<dynamic>.from(
        ApiService.unwrap(await ApiService.dio.get('/waiter/dishes')));
    return data;
  }

  static Future<List<dynamic>> orders({
    String? status,
    String? startDate,
    String? endDate,
    int limit = 100,
    int offset = 0,
  }) async {
    final params = <String, dynamic>{
      'status': status,
      'startDate': startDate,
      'endDate': endDate,
      'limit': limit,
      'offset': offset,
    }..removeWhere((_, value) => value == null || value == '');
    return List<dynamic>.from(ApiService.unwrap(
        await ApiService.dio.get('/waiter/orders', queryParameters: params)));
  }

  static Future<List<dynamic>> historyOrders({
    String? startDate,
    String? endDate,
  }) async =>
      orders(
          status: 'PAID', startDate: startDate, endDate: endDate, limit: 100);
  static Future<List<dynamic>> tables() async => List<dynamic>.from(
      ApiService.unwrap(await ApiService.dio.get('/waiter/tables')));

  static Future<Map<String, dynamic>?> currentOrder(int tableId) async =>
      ApiService.unwrap(
          await ApiService.dio.get('/waiter/tables/$tableId/current-order'));

  static Future<dynamic> submit(Map<String, dynamic> body,
          {String? idempotencyKey}) async =>
      ApiService.unwrap(
        await ApiService.dio.post(
          '/waiter/orders',
          data: body,
          options: ApiService.idempotencyOptions(idempotencyKey),
        ),
      );

  static Future<dynamic> modify(int id, Map<String, dynamic> body,
          {String? idempotencyKey}) async =>
      ApiService.unwrap(
        await ApiService.dio.put(
          '/waiter/orders/$id',
          data: body,
          options: ApiService.idempotencyOptions(idempotencyKey),
        ),
      );

  static Future<dynamic> cancel(int id, String reason) async =>
      ApiService.unwrap(await ApiService.dio.post('/waiter/orders/$id/cancel',
          data: {'reason': reason}, options: ApiService.idempotencyOptions()));
  static Future<dynamic> markServed(int id, int itemId) async =>
      ApiService.unwrap(await ApiService.dio.post('/waiter/orders/$id/served',
          data: {'itemId': itemId}, options: ApiService.idempotencyOptions()));
  static Future<dynamic> requestCheckout(int id) async => ApiService.unwrap(
      await ApiService.dio.post('/waiter/orders/$id/checkout-request',
          options: ApiService.idempotencyOptions()));
}
