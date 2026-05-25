import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import '../config/app_config.dart';
import '../utils/hive_utils.dart';
import 'api_service.dart';

class AuthApi {
  static Future<Map<String, dynamic>> captcha() async {
    return Map<String, dynamic>.from(
        ApiService.unwrap(await ApiService.dio.get('/common/auth/captcha')));
  }

  static Future<Map<String, dynamic>> sendSmsCode(String phone) async {
    return Map<String, dynamic>.from(
      ApiService.unwrap(await ApiService.dio
          .post('/common/auth/sms-code', data: {'phone': phone})),
    );
  }

  static Future<void> loginByPassword(String username, String password,
      String captchaId, String captchaCode) async {
    final data = ApiService.unwrap(
      await ApiService.dio.post('/common/auth/login', data: {
        'loginType': 'PASSWORD',
        'username': username,
        'password': password,
        'captchaId': captchaId,
        'captchaCode': captchaCode,
      }),
    );
    await HiveUtils.saveToken(data['token']);
    HiveUtils.cachedProfile = Map<String, dynamic>.from(data);
  }

  static Future<void> loginBySms(String phone, String smsCode) async {
    final data = ApiService.unwrap(
      await ApiService.dio.post('/common/auth/login', data: {
        'loginType': 'SMS',
        'phone': phone,
        'smsCode': smsCode,
      }),
    );
    await HiveUtils.saveToken(data['token']);
    HiveUtils.cachedProfile = Map<String, dynamic>.from(data);
  }

  static Future<Map<String, dynamic>> profile() async {
    final data = Map<String, dynamic>.from(
      ApiService.unwrap(await ApiService.dio.get('/common/auth/profile')),
    );
    HiveUtils.cachedProfile = data;
    return data;
  }

  static Future<Map<String, dynamic>> updateProfile({
    required String phone,
    required String displayName,
    required String avatarUrl,
  }) async {
    final data = Map<String, dynamic>.from(
      ApiService.unwrap(
        await ApiService.dio.put(
          '/common/auth/profile',
          data: {
            'phone': phone,
            'displayName': displayName,
            'avatarUrl': avatarUrl,
          },
        ),
      ),
    );
    HiveUtils.cachedProfile = data;
    return data;
  }

  static Future<void> changePassword({
    required String oldPassword,
    required String newPassword,
    required String confirmPassword,
  }) async {
    ApiService.unwrap(
      await ApiService.dio.put(
        '/common/auth/password',
        data: {
          'oldPassword': oldPassword,
          'newPassword': newPassword,
          'confirmPassword': confirmPassword,
        },
      ),
    );
  }

  static Future<String> uploadAvatar(String path) async {
    if (kIsWeb) {
      throw const ApiException('当前测试网页不支持相册上传，请在 APP 中使用');
    }
    final data = FormData.fromMap({
      'file': await MultipartFile.fromFile(path),
    });
    final result = Map<String, dynamic>.from(
      ApiService.unwrap(
        await ApiService.dio.post('/common/auth/avatar', data: data),
      ),
    );
    return '${result['avatarUrl'] ?? ''}';
  }

  static Future<Map<String, dynamic>> checkUpdate() async {
    return Map<String, dynamic>.from(
      ApiService.unwrap(
        await ApiService.dio.get(
          '/common/app/update',
          queryParameters: {
            'platform': AppConfig.platform,
            'version': AppConfig.version,
            'build': AppConfig.buildNumber,
          },
        ),
      ),
    );
  }
}
