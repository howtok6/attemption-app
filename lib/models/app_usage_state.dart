enum UsageState {
  allowed,   // Dentro de los 5 minutos de uso permitidos
  resting,   // Durante los 10 minutos de descanso
  blocked,   // Bloqueado completamente (pasó de 1 hora en total)
}

class AppUsageModel {
  final Duration totalInstagramTime;
  final Duration currentSessionTime;
  final UsageState state;

  AppUsageModel({
    required this.totalInstagramTime,
    required this.currentSessionTime,
    required this.state,
  });
}
