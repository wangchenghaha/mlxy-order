import 'dart:ui';

import 'package:flutter/material.dart';

void showTopToast(
  BuildContext context,
  String message, {
  IconData icon = Icons.check_circle_rounded,
}) {
  final overlay = Overlay.maybeOf(context);
  if (overlay == null) return;

  late final OverlayEntry entry;
  entry = OverlayEntry(
    builder: (_) => _TopToastOverlay(
      message: message,
      icon: icon,
      onDismissed: () => entry.remove(),
    ),
  );
  overlay.insert(entry);
}

class _TopToastOverlay extends StatefulWidget {
  final String message;
  final IconData icon;
  final VoidCallback onDismissed;

  const _TopToastOverlay({
    required this.message,
    required this.icon,
    required this.onDismissed,
  });

  @override
  State<_TopToastOverlay> createState() => _TopToastOverlayState();
}

class _TopToastOverlayState extends State<_TopToastOverlay>
    with SingleTickerProviderStateMixin {
  late final AnimationController controller;
  late final Animation<double> fade;
  late final Animation<Offset> slide;

  @override
  void initState() {
    super.initState();
    controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 260),
      reverseDuration: const Duration(milliseconds: 210),
    );
    fade = CurvedAnimation(parent: controller, curve: Curves.easeOutCubic);
    slide = Tween<Offset>(
      begin: const Offset(0, -0.85),
      end: Offset.zero,
    ).animate(CurvedAnimation(parent: controller, curve: Curves.easeOutBack));
    controller.forward();
    Future<void>.delayed(const Duration(milliseconds: 1750), () async {
      if (!mounted) return;
      await controller.reverse();
      if (mounted) widget.onDismissed();
    });
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: SafeArea(
        child: Align(
          alignment: Alignment.topCenter,
          child: SlideTransition(
            position: slide,
            child: FadeTransition(
              opacity: fade,
              child: Container(
                margin: const EdgeInsets.fromLTRB(16, 10, 16, 0),
                constraints: const BoxConstraints(maxWidth: 420),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(18),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.94),
                        borderRadius: BorderRadius.circular(18),
                        border: Border.all(
                            color: Colors.white.withValues(alpha: 0.72)),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withValues(alpha: 0.14),
                            blurRadius: 24,
                            offset: const Offset(0, 10),
                          ),
                        ],
                      ),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 16, vertical: 13),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(widget.icon,
                                color: const Color(0xff13815e), size: 22),
                            const SizedBox(width: 10),
                            Flexible(
                              child: Text(
                                widget.message,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context)
                                    .textTheme
                                    .titleSmall
                                    ?.copyWith(
                                      color: const Color(0xff173f35),
                                      fontWeight: FontWeight.w800,
                                      height: 1.2,
                                    ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
