/// Estado de los límites, tal como lo informa el servicio nativo.
enum UsageState {
  inactive, // La protección no está corriendo
  allowed, // Dentro de los límites
  resting, // Durante el descanso obligatorio
  blocked, // Límite diario alcanzado
}

class AppUsageModel {
  final bool running;
  final bool protectionEnabled;
  final UsageState state;
  final Duration dailyUsed;
  final Duration dailyLimit;
  final Duration sessionUsed;
  final Duration sessionLimit;
  final Duration restRemaining;
  final Duration restLimit;

  const AppUsageModel({
    required this.running,
    required this.protectionEnabled,
    required this.state,
    required this.dailyUsed,
    required this.dailyLimit,
    required this.sessionUsed,
    required this.sessionLimit,
    required this.restRemaining,
    required this.restLimit,
  });

  static const empty = AppUsageModel(
    running: false,
    protectionEnabled: false,
    state: UsageState.inactive,
    dailyUsed: Duration.zero,
    dailyLimit: Duration(hours: 1),
    sessionUsed: Duration.zero,
    sessionLimit: Duration(minutes: 5),
    restRemaining: Duration.zero,
    restLimit: Duration(minutes: 10),
  );

  /// Durante un descanso o con el límite diario cumplido no se puede desactivar la protección.
  bool get isLocked => state == UsageState.resting || state == UsageState.blocked;

  factory AppUsageModel.fromMap(Map<Object?, Object?> map) {
    Duration ms(String key, Duration fallback) {
      final value = map[key];
      return value is num ? Duration(milliseconds: value.toInt()) : fallback;
    }

    return AppUsageModel(
      running: map['running'] == true,
      protectionEnabled: map['protectionEnabled'] == true,
      state: UsageState.values.firstWhere(
        (s) => s.name == map['state'],
        orElse: () => UsageState.inactive,
      ),
      dailyUsed: ms('dailyUsedMs', Duration.zero),
      dailyLimit: ms('dailyLimitMs', empty.dailyLimit),
      sessionUsed: ms('sessionUsedMs', Duration.zero),
      sessionLimit: ms('sessionLimitMs', empty.sessionLimit),
      restRemaining: ms('restLeftMs', Duration.zero),
      restLimit: ms('restLimitMs', empty.restLimit),
    );
  }

  AppUsageModel copyWith({bool? running, bool? protectionEnabled, UsageState? state}) => AppUsageModel(
        running: running ?? this.running,
        protectionEnabled: protectionEnabled ?? this.protectionEnabled,
        state: state ?? this.state,
        dailyUsed: dailyUsed,
        dailyLimit: dailyLimit,
        sessionUsed: sessionUsed,
        sessionLimit: sessionLimit,
        restRemaining: restRemaining,
        restLimit: restLimit,
      );
}
