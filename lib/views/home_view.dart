import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/app_usage_state.dart';
import '../services/app_monitor_service.dart';
import '../theme/app_theme.dart';
import '../viewmodels/home_viewmodel.dart';
import 'widgets/stop_confirmation_dialog.dart';

class HomeView extends StatefulWidget {
  const HomeView({super.key});

  @override
  State<HomeView> createState() => _HomeViewState();
}

class _HomeViewState extends State<HomeView> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // Re-verificar permisos y estado cuando la app vuelve a primer plano
      context.read<HomeViewModel>().refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final viewModel = context.watch<HomeViewModel>();

    return Scaffold(
      appBar: AppBar(title: const Text('Attemption')),
      // SafeArea: en Android 15+ la app ocupa toda la pantalla y el último botón
      // quedaba debajo de la barra de gestos.
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24.0),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: viewModel.isAndroid ? _buildContent(context, viewModel) : _buildUnsupported(context),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildUnsupported(BuildContext context) {
    return Text(
      'Attemption solo funciona en Android.',
      textAlign: TextAlign.center,
      style: Theme.of(context).textTheme.titleMedium,
    );
  }

  Widget _buildContent(BuildContext context, HomeViewModel viewModel) {
    final usage = viewModel.usage;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Icon(Icons.shield, size: 80, color: AppColors.primary),
        const SizedBox(height: 16),
        Text('Protege tu tiempo', textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 24),
        if (viewModel.isServiceRunning) ...[_StatusCard(usage: usage), const SizedBox(height: 24)],
        const _SectionTitle('Permisos necesarios'),
        _PermissionCard(
          title: 'Acceso a Uso de Aplicaciones',
          isGranted: viewModel.hasUsagePermission,
          onTap: viewModel.requestUsagePermission,
        ),
        const SizedBox(height: 12),
        _PermissionCard(
          title: 'Mostrar sobre otras Apps',
          isGranted: viewModel.hasOverlayPermission,
          onTap: viewModel.requestOverlayPermission,
        ),
        const SizedBox(height: 24),
        const _SectionTitle('Recomendados'),
        _PermissionCard(
          title: 'Notificaciones',
          subtitle: 'Para ver el tiempo restante en la barra de notificaciones',
          isGranted: viewModel.hasNotificationPermission,
          isRequired: false,
          onTap: viewModel.requestNotificationPermission,
        ),
        const SizedBox(height: 12),
        _PermissionCard(
          title: 'Sin restricción de batería',
          subtitle: 'Evita que Android cierre la protección en segundo plano',
          isGranted: viewModel.isIgnoringBatteryOptimizations,
          isRequired: false,
          onTap: viewModel.requestBatteryExemption,
        ),
        TextButton.icon(
          icon: const Icon(Icons.open_in_new, size: 18),
          label: Text(
            viewModel.manufacturer.isEmpty
                ? 'Guía para que tu teléfono no cierre la app'
                : 'Guía para teléfonos ${viewModel.manufacturer}',
          ),
          onPressed: viewModel.openBatteryGuide,
        ),
        const SizedBox(height: 24),
        if (viewModel.isServiceRunning)
          OutlinedButton.icon(
            icon: const Icon(Icons.power_settings_new),
            label: const Text('Desactivar protección'),
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.danger,
              side: const BorderSide(color: AppColors.danger),
              padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
            ),
            onPressed: () => _confirmStop(context, viewModel),
          )
        else if (viewModel.isReady)
          ElevatedButton.icon(
            icon: const Icon(Icons.play_arrow),
            label: const Text('Iniciar Protección'),
            style: ElevatedButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
              backgroundColor: AppColors.primary,
              foregroundColor: AppColors.onPrimary,
            ),
            onPressed: viewModel.startService,
          ),
      ],
    );
  }

  Future<void> _confirmStop(BuildContext context, HomeViewModel viewModel) async {
    final messenger = ScaffoldMessenger.of(context);
    final usage = viewModel.usage;
    if (usage.isLocked) {
      messenger.showSnackBar(SnackBar(content: Text(_lockedMessage(usage))));
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => const StopConfirmationDialog(),
    );
    if (confirmed != true) return;

    final result = await viewModel.stopService();
    switch (result) {
      case StopResult.stopped:
        messenger.showSnackBar(const SnackBar(content: Text('Protección desactivada.')));
      case StopResult.locked:
        messenger.showSnackBar(SnackBar(content: Text(_lockedMessage(viewModel.usage))));
      case StopResult.failed:
        messenger.showSnackBar(const SnackBar(content: Text('No se pudo desactivar la protección.')));
    }
  }

  String _lockedMessage(AppUsageModel usage) => usage.state == UsageState.blocked
      ? 'Ya alcanzaste tu límite diario: la protección no se puede desactivar hasta mañana.'
      : 'Estás en un descanso: podrás desactivar la protección en ${_minutesCeil(usage.restRemaining)} min.';
}

int _minutesCeil(Duration d) => (d.inSeconds + 59) ~/ 60;

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(text, style: Theme.of(context).textTheme.titleSmall?.copyWith(color: AppColors.textSecondary)),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.usage});

  final AppUsageModel usage;

  @override
  Widget build(BuildContext context) {
    final (title, color, icon) = switch (usage.state) {
      UsageState.resting => (
        'Descanso: faltan ${_minutesCeil(usage.restRemaining)} min',
        AppColors.warning,
        Icons.self_improvement,
      ),
      UsageState.blocked => ('Límite diario alcanzado', AppColors.danger, Icons.block),
      _ => ('Protección activa', AppColors.success, Icons.check_circle),
    };

    return Card(
      elevation: 0,
      color: AppColors.tint(color),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: color),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: color),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    title,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            _UsageBar(label: 'Hoy', used: usage.dailyUsed, limit: usage.dailyLimit, color: color),
            const SizedBox(height: 12),
            _UsageBar(label: 'Sesión actual', used: usage.sessionUsed, limit: usage.sessionLimit, color: color),
            const SizedBox(height: 12),
            Text(
              'Instagram y TikTok: ${usage.sessionLimit.inMinutes} min de uso, luego ${usage.restLimit.inMinutes} min '
              'de descanso. Máximo ${usage.dailyLimit.inMinutes} min al día.',
              style: const TextStyle(color: AppColors.textSecondary),
            ),
          ],
        ),
      ),
    );
  }
}

class _UsageBar extends StatelessWidget {
  const _UsageBar({required this.label, required this.used, required this.limit, required this.color});

  final String label;
  final Duration used;
  final Duration limit;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final progress = limit.inMilliseconds == 0 ? 0.0 : (used.inMilliseconds / limit.inMilliseconds).clamp(0.0, 1.0);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$label: ${used.inMinutes} de ${limit.inMinutes} min'),
        const SizedBox(height: 4),
        LinearProgressIndicator(
          value: progress,
          minHeight: 8,
          borderRadius: BorderRadius.circular(4),
          color: color,
          backgroundColor: color.withValues(alpha: 0.2),
        ),
      ],
    );
  }
}

class _PermissionCard extends StatelessWidget {
  const _PermissionCard({
    required this.title,
    required this.isGranted,
    required this.onTap,
    this.subtitle,
    this.isRequired = true,
  });

  final String title;
  final String? subtitle;
  final bool isGranted;
  final bool isRequired;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final pendingColor = isRequired ? AppColors.warning : AppColors.neutral;
    final color = isGranted ? AppColors.success : pendingColor;
    final textTheme = Theme.of(context).textTheme;
    return Card(
      elevation: 0,
      margin: EdgeInsets.zero,
      color: AppColors.tint(color),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: color.withValues(alpha: 0.7)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              isGranted ? Icons.check_circle : (isRequired ? Icons.warning_amber_rounded : Icons.info_outline),
              color: color,
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: textTheme.titleMedium),
                  if (subtitle != null && !isGranted) ...[
                    const SizedBox(height: 4),
                    Text(subtitle!, style: textTheme.bodyMedium?.copyWith(color: AppColors.textSecondary)),
                  ],
                  if (!isGranted) ...[
                    const SizedBox(height: 12),
                    FilledButton.tonal(onPressed: onTap, child: const Text('Permitir')),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
