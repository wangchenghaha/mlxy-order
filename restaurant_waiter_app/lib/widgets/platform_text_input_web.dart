// ignore_for_file: avoid_web_libraries_in_flutter, deprecated_member_use

import 'dart:html' as html;
import 'dart:ui_web' as ui_web;

import 'package:flutter/material.dart';

class PlatformTextInput extends StatefulWidget {
  final TextEditingController controller;
  final String placeholder;
  final bool obscureText;
  final TextInputType? keyboardType;

  const PlatformTextInput({
    super.key,
    required this.controller,
    required this.placeholder,
    this.obscureText = false,
    this.keyboardType,
  });

  @override
  State<PlatformTextInput> createState() => _PlatformTextInputState();
}

class _PlatformTextInputState extends State<PlatformTextInput> {
  late final String viewType;
  late final html.InputElement input;

  @override
  void initState() {
    super.initState();
    viewType = 'waiter-input-${identityHashCode(this)}';
    input = html.InputElement()
      ..value = widget.controller.text
      ..placeholder = widget.placeholder
      ..type = widget.obscureText ? 'password' : inputType
      ..style.width = '100%'
      ..style.height = '100%'
      ..style.boxSizing = 'border-box'
      ..style.border = '0'
      ..style.outline = '0'
      ..style.background = 'transparent'
      ..style.fontSize = '16px'
      ..style.color = '#263430'
      ..style.fontFamily = 'Arial, sans-serif';
    input.onInput.listen((_) => widget.controller.text = input.value ?? '');
    widget.controller.addListener(syncFromController);
    ui_web.platformViewRegistry.registerViewFactory(viewType, (_) => input);
  }

  String get inputType {
    if (widget.keyboardType == TextInputType.phone) return 'tel';
    if (widget.keyboardType == TextInputType.number) return 'number';
    return 'text';
  }

  @override
  void didUpdateWidget(covariant PlatformTextInput oldWidget) {
    super.didUpdateWidget(oldWidget);
    input
      ..placeholder = widget.placeholder
      ..type = widget.obscureText ? 'password' : inputType;
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(syncFromController);
      widget.controller.addListener(syncFromController);
      input.value = widget.controller.text;
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(syncFromController);
    super.dispose();
  }

  void syncFromController() {
    if (input.value != widget.controller.text) {
      input.value = widget.controller.text;
    }
  }

  @override
  Widget build(BuildContext context) {
    return HtmlElementView(viewType: viewType);
  }
}
