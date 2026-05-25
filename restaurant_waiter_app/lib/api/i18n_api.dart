import 'api_service.dart';

class I18nApi {
  static Future<Map<String, String>> list(String lang) async {
    final data = ApiService.unwrap(await ApiService.dio
        .get('/common/i18n/list', queryParameters: {'lang': lang}));
    return Map<String, String>.from(data);
  }
}
