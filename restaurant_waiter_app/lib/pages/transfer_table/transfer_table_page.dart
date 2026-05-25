import 'package:flutter/material.dart';

import '../../api/table_api.dart';
import '../../utils/locale_utils.dart';
import '../../widgets/common_button.dart';

class TransferTablePage extends StatefulWidget {
  final List<dynamic> tables;
  final Future<void> Function() onDone;

  const TransferTablePage(
      {super.key, required this.tables, required this.onDone});

  @override
  State<TransferTablePage> createState() => _TransferTablePageState();
}

class _TransferTablePageState extends State<TransferTablePage> {
  int? fromId;
  int? toId;

  Future<void> submit() async {
    if (fromId == null || toId == null) return;
    await TableApi.transfer(fromId!, toId!);
    await widget.onDone();
    if (mounted) setState(() => fromId = toId = null);
  }

  @override
  Widget build(BuildContext context) {
    final occupied =
        widget.tables.where((t) => t['status'] != 'EMPTY').toList();
    final empty = widget.tables.where((t) => t['status'] == 'EMPTY').toList();

    return ListView(
      padding: EdgeInsets.zero,
      children: [
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.96),
            borderRadius: BorderRadius.circular(24),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.05),
                blurRadius: 18,
                offset: const Offset(0, 8),
              ),
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                LocaleUtils.get('home_guest_handoff'),
                style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
              ),
              const SizedBox(height: 6),
              Text(
                LocaleUtils.get('table_transfer_dialog_title'),
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
              const SizedBox(height: 18),
              DropdownButtonFormField<int>(
                initialValue: fromId,
                decoration: InputDecoration(
                  labelText: LocaleUtils.get('table_from_table'),
                  prefixIcon: const Icon(Icons.table_bar_outlined),
                ),
                items: occupied
                    .map((t) => DropdownMenuItem<int>(
                        value: t['id'],
                        child: Text('${t['tableNo']} · ${t['area']}')))
                    .toList(),
                onChanged: (v) => setState(() => fromId = v),
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<int>(
                initialValue: toId,
                decoration: InputDecoration(
                  labelText: LocaleUtils.get('table_to_table'),
                  prefixIcon: const Icon(Icons.swap_horiz_rounded),
                ),
                items: empty
                    .map((t) => DropdownMenuItem<int>(
                        value: t['id'],
                        child: Text('${t['tableNo']} · ${t['area']}')))
                    .toList(),
                onChanged: (v) => setState(() => toId = v),
              ),
              const SizedBox(height: 18),
              CommonButton(
                text: LocaleUtils.get('table_transfer_confirm'),
                icon: Icons.compare_arrows_rounded,
                onPressed: submit,
              ),
            ],
          ),
        ),
      ],
    );
  }
}
