import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import '../config/api_config.dart';
import '../utils/auth_redirect.dart';
import '../utils/hive_utils.dart';

class ApiService {
  static bool _redirectingToLogin = false;

  static final Dio dio = Dio(
    BaseOptions(
      baseUrl: ApiConfig.baseUrl,
      connectTimeout: const Duration(seconds: 8),
      receiveTimeout: const Duration(seconds: 15),
      sendTimeout: const Duration(seconds: 8),
    ),
  )..interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          // Every request carries the current UI language so backend messages stay aligned with the APP.
          options.headers['lang'] = HiveUtils.lang;
          if (HiveUtils.token.isNotEmpty) {
            // Token is cached after login and reused by waiter table/order APIs.
            options.headers['Authorization'] = 'Bearer ${HiveUtils.token}';
          }
          handler.next(options);
        },
        onError: (error, handler) async {
          final data = error.response?.data;
          if (error.response?.statusCode == 401 ||
              data is Map && data['code'] == 401) {
            await handleUnauthorized();
          }
          if (_shouldRetry(error)) {
            try {
              final response = await _retry(error.requestOptions);
              handler.resolve(response);
              return;
            } catch (_) {
              // Fall through to the normalized error below.
            }
          }
          handler.reject(error);
        },
      ),
    );

  static final Map<String, DateTime> _idempotencyKeys = {};

  static dynamic unwrap(Response response) {
    // Backend uses a unified {code,message,data} envelope across APP, cashier, and admin clients.
    final data = response.data;
    if (data is Map && data['code'] == 0) return data['data'];
    if (data is Map && data['code'] == 401) {
      handleUnauthorized();
      throw ApiException('${data['message'] ?? '登录已过期，请重新登录'}',
          unauthorized: true);
    }
    throw ApiException(
        data is Map ? '${data['message'] ?? '请求失败'}' : '请求失败，请稍后重试');
  }

  static String friendlyError(Object error) {
    if (error is ApiException) return error.message;
    if (error is DioException) {
      final data = error.response?.data;
      if (data is Map && data['message'] != null) return '${data['message']}';
      return switch (error.type) {
        DioExceptionType.connectionTimeout ||
        DioExceptionType.receiveTimeout ||
        DioExceptionType.sendTimeout =>
          '网络超时，请检查网络后重试',
        DioExceptionType.badResponse => '服务暂时不可用，请稍后重试',
        DioExceptionType.connectionError ||
        DioExceptionType.unknown =>
          '网络连接失败，请检查网络',
        DioExceptionType.cancel => '请求已取消',
        _ => '请求失败，请稍后重试',
      };
    }
    return error.toString().replaceFirst('Exception: ', '');
  }

  static String nextIdempotencyKey(String action) {
    final now = DateTime.now();
    _idempotencyKeys.removeWhere(
        (_, value) => now.difference(value) > const Duration(minutes: 10));
    final key =
        '$action-${now.microsecondsSinceEpoch}-${identityHashCode(now)}';
    _idempotencyKeys[key] = now;
    return key;
  }

  static Options idempotencyOptions([String? key]) {
    return Options(
        headers: {'X-Idempotency-Key': key ?? nextIdempotencyKey('waiter')});
  }

  static bool _shouldRetry(DioException error) {
    final method = error.requestOptions.method.toUpperCase();
    if (method != 'GET') return false;
    if (error.requestOptions.headers['x-retry'] == '1') return false;
    return switch (error.type) {
      DioExceptionType.connectionTimeout ||
      DioExceptionType.receiveTimeout ||
      DioExceptionType.connectionError ||
      DioExceptionType.unknown =>
        true,
      _ => false,
    };
  }

  static Future<Response<dynamic>> _retry(RequestOptions options) async {
    await Future<void>.delayed(const Duration(milliseconds: 450));
    final headers = Map<String, dynamic>.from(options.headers);
    headers['x-retry'] = '1';
    debugPrint('retry waiter api ${options.method} ${options.path}');
    return dio.fetch<dynamic>(
      options.copyWith(headers: headers),
    );
  }

  static Future<void> handleUnauthorized() async {
    if (_redirectingToLogin) return;
    _redirectingToLogin = true;
    await HiveUtils.clearToken();
    AuthRedirect.unauthorizedNotifier.value++;
    Future<void>.delayed(const Duration(milliseconds: 500), () {
      _redirectingToLogin = false;
    });
  }
}

class ApiException implements Exception {
  final String message;
  final bool unauthorized;

  const ApiException(this.message, {this.unauthorized = false});

  @override
  String toString() => message;
}
