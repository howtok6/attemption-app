import 'dart:async';

import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';

/// Pide confirmación para desactivar la protección con una espera obligatoria.
/// La espera agrega fricción: a menudo las ganas de abrir la app pasan en esos segundos.
class StopConfirmationDialog extends StatefulWidget {
  const StopConfirmationDialog({super.key, this.waitSeconds = 30});

  final int waitSeconds;

  @override
  State<StopConfirmationDialog> createState() => _StopConfirmationDialogState();
}

class _StopConfirmationDialogState extends State<StopConfirmationDialog> {
  late int _secondsLeft = widget.waitSeconds;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      setState(() => _secondsLeft--);
      if (_secondsLeft <= 0) timer.cancel();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final waiting = _secondsLeft > 0;
    return AlertDialog(
      title: const Text('¿Desactivar la protección?'),
      content: Text(
        waiting
            ? 'Tómate un momento: ¿de verdad lo necesitas ahora?\n\nPodrás confirmar en $_secondsLeft s.'
            : 'Tómate un momento: ¿de verdad lo necesitas ahora?\n\nYa puedes confirmar.',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Mantener protección')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
          onPressed: waiting ? null : () => Navigator.of(context).pop(true),
          child: Text(waiting ? 'Desactivar ($_secondsLeft)' : 'Desactivar'),
        ),
      ],
    );
  }
}
