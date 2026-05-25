import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:hive_flutter/hive_flutter.dart';

class HiveUtils {
  static Box? _box;
  static final Map<String, dynamic> _memory = {};
  static const FlutterSecureStorage _secureStorage = FlutterSecureStorage(
    aOptions: AndroidOptions(resetOnError: true),
    iOptions: IOSOptions(
        accessibility: KeychainAccessibility.first_unlock_this_device),
    mOptions: MacOsOptions(
        accessibility: KeychainAccessibility.first_unlock_this_device),
  );

  static bool get ready => _box != null;

  static Future<void> init() async {
    _box = await Hive.openBox('waiter_app');
    final legacyToken = _box?.get('token', defaultValue: '') as String? ?? '';
    if (kIsWeb) {
      if (legacyToken.isNotEmpty) {
        _memory['token'] = legacyToken;
      }
      return;
    }
    final secureToken = await _secureStorage.read(key: 'token');
    final token = secureToken?.isNotEmpty == true ? secureToken! : legacyToken;
    if (token.isNotEmpty) {
      _memory['token'] = token;
      await _secureStorage.write(key: 'token', value: token);
      await _box?.delete('token');
    }
  }

  static String get token => _readString('token');
  static set token(String value) {
    _memory['token'] = value;
    if (kIsWeb) {
      if (value.isEmpty) {
        _box?.delete('token');
      } else {
        _box?.put('token', value);
      }
      return;
    }
    if (value.isEmpty) {
      _secureStorage.delete(key: 'token');
    } else {
      _secureStorage.write(key: 'token', value: value);
    }
  }

  static Future<void> saveToken(String value) async {
    _memory['token'] = value;
    if (kIsWeb) {
      if (value.isEmpty) {
        await _box?.delete('token');
      } else {
        await _box?.put('token', value);
      }
      return;
    }
    if (value.isEmpty) {
      await _secureStorage.delete(key: 'token');
    } else {
      await _secureStorage.write(key: 'token', value: value);
      await _box?.delete('token');
    }
  }

  static Future<void> clearToken() => saveToken('');

  static String get lang => _readString('lang');
  static set lang(String value) => _write('lang', value);

  static Map<String, String> get i18n {
    final data = _read('i18n_$lang', <String, String>{});
    return data is Map ? Map<String, String>.from(data) : <String, String>{};
  }

  static List<dynamic> get cachedTables => _readList('cache_tables');
  static set cachedTables(List<dynamic> value) =>
      _writeJsonLike('cache_tables', value);

  static List<dynamic> get cachedDishes => _readList('cache_dishes');
  static set cachedDishes(List<dynamic> value) =>
      _writeJsonLike('cache_dishes', value);

  static List<dynamic> get cachedCategories => _readList('cache_categories');
  static set cachedCategories(List<dynamic> value) =>
      _writeJsonLike('cache_categories', value);

  static List<dynamic> get cachedHistoryOrders =>
      _readList('cache_history_orders');
  static set cachedHistoryOrders(List<dynamic> value) =>
      _writeJsonLike('cache_history_orders', value);

  static Map<String, dynamic> get cachedProfile {
    final data = _read('cache_profile', <String, dynamic>{});
    return data is Map ? Map<String, dynamic>.from(data) : <String, dynamic>{};
  }

  static set cachedProfile(Map<String, dynamic> value) =>
      _writeJsonLike('cache_profile', value);

  static bool hasPromptedUpdate(Object? userId, String version) {
    if (version.isBlank) return false;
    return _readString(updatePromptKey(userId, version)) == '1';
  }

  static void markPromptedUpdate(Object? userId, String version) {
    if (version.isBlank) return;
    _write(updatePromptKey(userId, version), '1');
  }

  static Future<void> saveI18n(String lang, Map<String, String> map) async {
    _memory['i18n_$lang'] = map;
    await _box?.put('i18n_$lang', map);
  }

  static String _readString(String key) => _read(key, '') as String;

  static Object _read(String key, Object defaultValue) {
    if (_box == null) return _memory[key] ?? defaultValue;
    return _box!.get(key, defaultValue: defaultValue);
  }

  static List<dynamic> _readList(String key) {
    final data = _read(key, <dynamic>[]);
    return data is List ? List<dynamic>.from(data) : <dynamic>[];
  }

  static void _write(String key, String value) {
    _memory[key] = value;
    _box?.put(key, value);
  }

  static void _writeJsonLike(String key, Object value) {
    _memory[key] = value;
    _box?.put(key, value);
  }

  static String updatePromptKey(Object? userId, String version) {
    return 'update_prompt_${userId ?? 'anonymous'}_$version';
  }
}

extension on String {
  bool get isBlank => trim().isEmpty;
}
