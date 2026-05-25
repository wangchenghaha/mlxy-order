import 'package:flutter/material.dart';

class PlatformTextInput extends StatelessWidget {
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
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      obscureText: obscureText,
      keyboardType: keyboardType,
      style: Theme.of(context).textTheme.bodyLarge,
      decoration: InputDecoration(
        hintText: placeholder,
        border: InputBorder.none,
        isDense: true,
        contentPadding: EdgeInsets.zero,
      ),
    );
  }
}
