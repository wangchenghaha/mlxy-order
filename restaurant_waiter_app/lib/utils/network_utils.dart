import 'package:connectivity_plus/connectivity_plus.dart';

class NetworkUtils {
  static Future<bool> get hasConnection async {
    final results = await Connectivity().checkConnectivity();
    return !results.contains(ConnectivityResult.none);
  }

  static Stream<bool> get onConnectionChanged {
    return Connectivity().onConnectivityChanged.map(
          (results) => !results.contains(ConnectivityResult.none),
        );
  }
}
