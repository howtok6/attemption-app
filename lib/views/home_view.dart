import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../viewmodels/home_viewmodel.dart';

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
      // Re-verificar permisos cuando la app vuelve a primer plano
      context.read<HomeViewModel>().checkPermissions();
    }
  }

  @override
  Widget build(BuildContext context) {
    final viewModel = context.watch<HomeViewModel>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Attemption'),
        backgroundColor: Colors.deepPurple,
        foregroundColor: Colors.white,
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.shield, size: 80, color: Colors.deepPurple),
              const SizedBox(height: 24),
              Text(
                'Protege tu tiempo',
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const SizedBox(height: 40),
              
              _buildPermissionCard(
                context,
                title: 'Acceso a Uso de Aplicaciones',
                isGranted: viewModel.hasUsagePermission,
                onTap: viewModel.requestUsagePermission,
              ),
              const SizedBox(height: 16),
              _buildPermissionCard(
                context,
                title: 'Mostrar sobre otras Apps',
                isGranted: viewModel.hasOverlayPermission,
                onTap: viewModel.requestOverlayPermission,
              ),
              
              const SizedBox(height: 40),
              if (viewModel.isReady)
                ElevatedButton.icon(
                  icon: Icon(viewModel.isServiceRunning ? Icons.check : Icons.play_arrow),
                  label: Text(viewModel.isServiceRunning ? 'Servicio Activo' : 'Iniciar Protección'),
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                    backgroundColor: viewModel.isServiceRunning ? Colors.green : Colors.deepPurple,
                    foregroundColor: Colors.white,
                  ),
                  onPressed: viewModel.isServiceRunning ? null : viewModel.startService,
                ),
                
              if (viewModel.isServiceRunning) ...[
                const SizedBox(height: 24),
                const Text(
                  'El servicio se está ejecutando en segundo plano.\nInstagram y TikTok están siendo monitoreados:\n5 min uso -> 10 min descanso -> Max 1 hora diaria.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey),
                ),
              ]
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPermissionCard(BuildContext context, {required String title, required bool isGranted, required VoidCallback onTap}) {
    return Card(
      elevation: 0,
      color: isGranted ? Colors.green.withOpacity(0.1) : Colors.orange.withOpacity(0.1),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: isGranted ? Colors.green : Colors.orange),
      ),
      child: ListTile(
        leading: Icon(
          isGranted ? Icons.check_circle : Icons.warning,
          color: isGranted ? Colors.green : Colors.orange,
        ),
        title: Text(title),
        trailing: isGranted
            ? null
            : ElevatedButton(
                onPressed: onTap,
                child: const Text('Permitir'),
              ),
      ),
    );
  }
}
