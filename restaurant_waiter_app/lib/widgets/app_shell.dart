import 'package:flutter/material.dart';

class AppShell extends StatelessWidget {
  final Widget child;
  final List<Widget>? headerActions;
  final String title;
  final String? subtitle;
  final bool scrollable;
  final Widget? bottomBar;
  final Widget? customHeader;
  final EdgeInsetsGeometry padding;

  const AppShell({
    super.key,
    required this.child,
    required this.title,
    this.subtitle,
    this.headerActions,
    this.scrollable = false,
    this.bottomBar,
    this.customHeader,
    this.padding = const EdgeInsets.fromLTRB(20, 18, 20, 18),
  });

  @override
  Widget build(BuildContext context) {
    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (customHeader != null)
          customHeader!
        else
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style:
                          Theme.of(context).textTheme.headlineMedium?.copyWith(
                                fontWeight: FontWeight.w800,
                              ),
                    ),
                    if (subtitle != null && subtitle!.isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Text(
                        subtitle!,
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: Theme.of(context)
                                  .colorScheme
                                  .onSurfaceVariant,
                            ),
                      ),
                    ],
                  ],
                ),
              ),
              if (headerActions != null) ...[
                const SizedBox(width: 12),
                Wrap(spacing: 8, runSpacing: 8, children: headerActions!),
              ],
            ],
          ),
        const SizedBox(height: 20),
        child,
      ],
    );

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            colors: [
              Color(0xfff7f3eb),
              Color(0xfff2f6f4),
              Color(0xffeef3fa),
            ],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        child: SafeArea(
          child: scrollable
              ? SingleChildScrollView(
                  padding: padding,
                  child: content,
                )
              : Padding(
                  padding: padding,
                  child: content,
                ),
        ),
      ),
      bottomNavigationBar: bottomBar == null
          ? null
          : DecoratedBox(
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [
                    Color(0xfff7f3eb),
                    Color(0xfff2f6f4),
                    Color(0xffeef3fa),
                  ],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                border: Border(
                  top: BorderSide(color: Colors.black.withValues(alpha: 0.05)),
                ),
              ),
              child: SafeArea(
                top: false,
                minimum: const EdgeInsets.fromLTRB(12, 4, 12, 6),
                child: bottomBar!,
              ),
            ),
    );
  }
}
