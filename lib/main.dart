import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'theme/app_theme.dart';
import 'views/home_view.dart';
import 'viewmodels/home_viewmodel.dart';

void main() {
  runApp(const AttemptionApp());
}

class AttemptionApp extends StatelessWidget {
  const AttemptionApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [ChangeNotifierProvider(create: (_) => HomeViewModel())],
      child: MaterialApp(title: 'Attemption', theme: buildAppTheme(), home: const HomeView()),
    );
  }
}
