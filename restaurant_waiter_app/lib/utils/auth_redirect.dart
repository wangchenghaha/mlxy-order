import 'package:flutter/material.dart';

class AuthRedirect {
  static final GlobalKey<NavigatorState> navigatorKey =
      GlobalKey<NavigatorState>();
  static final ValueNotifier<int> unauthorizedNotifier = ValueNotifier<int>(0);
}
