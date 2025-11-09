import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:google_mobile_ads/google_mobile_ads.dart';
import '../models/cloned_app.dart';
import '../services/clone_app_service.dart';
import '../core/app_theme.dart';
import '../core/app_constants.dart';
import 'add_app_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with TickerProviderStateMixin {
  final CloneAppService _cloneAppService = CloneAppService();
  List<ClonedApp> _clonedApps = [];
  List<ClonedApp> _filteredApps = [];
  bool _isLoading = true;
  final TextEditingController _searchController = TextEditingController();
  late AnimationController _fabController;
  late Animation<double> _fabAnimation;

  // AdMob Banner Ad
  BannerAd? _bannerAd;
  bool _isBannerAdReady = false;

  @override
  void initState() {
    super.initState();
    _loadClonedApps();
    _loadBannerAd();

    // Add search listener
    _searchController.addListener(_onSearchChanged);

    _fabController = AnimationController(
      duration: AppConstants.cardAnimationDuration,
      vsync: this,
    );

    _fabAnimation = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _fabController, curve: Curves.elasticOut),
    );

    _fabController.forward();
  }

  @override
  void dispose() {
    _searchController.dispose();
    _fabController.dispose();
    _bannerAd?.dispose();
    super.dispose();
  }

  void _loadBannerAd() {
    if (kIsWeb) return; // Skip ads on web

    _bannerAd = BannerAd(
      adUnitId: AppConstants.bannerAdUnitId,
      request: const AdRequest(),
      size: AdSize.banner,
      listener: BannerAdListener(
        onAdLoaded: (ad) {
          setState(() {
            _isBannerAdReady = true;
          });
        },
        onAdFailedToLoad: (ad, error) {
          ad.dispose();
        },
      ),
    );

    _bannerAd!.load();
  }

  Future<void> _loadClonedApps() async {
    setState(() {
      _isLoading = true;
    });

    final clonedApps = await _cloneAppService.getClonedApps();

    setState(() {
      _clonedApps = clonedApps;
      _filteredApps = clonedApps; // Initialize filtered list
      _isLoading = false;
    });
  }

  void _onSearchChanged() {
    setState(() {
      final query = _searchController.text.toLowerCase();
      if (query.isEmpty) {
        _filteredApps = _clonedApps;
      } else {
        _filteredApps = _clonedApps.where((app) {
          return app.appName.toLowerCase().contains(query) ||
              app.packageName.toLowerCase().contains(query);
        }).toList();
      }
    });
  }

  void _clearSearch() {
    _searchController.clear();
    setState(() {
      _filteredApps = _clonedApps;
    });
  }

  Future<void> _launchApp(ClonedApp app) async {
    final success = await _cloneAppService.launchClonedApp(app);

    if (!success && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Failed to launch ${app.appName}'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _removeApp(ClonedApp app) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Remove Clone'),
        content: Text('Remove ${app.appName} from cloned apps?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('Remove'),
          ),
        ],
      ),
    );

    if (confirm == true) {
      final success = await _cloneAppService.removeClonedApp(app.id);
      if (success) {
        await _loadClonedApps();
        _onSearchChanged(); // Refresh filtered list
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('${app.appName} removed'),
              backgroundColor: Colors.green,
            ),
          );
        }
      }
    }
  }

  Widget _buildAppIcon(ClonedApp app) {
    if (app.appIcon != null) {
      try {
        final iconBytes = base64Decode(app.appIcon!);
        return ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: Image.memory(
            iconBytes,
            width: 48,
            height: 48,
            fit: BoxFit.cover,
          ),
        );
      } catch (e) {
        // Fall back to default icon if decoding fails
      }
    }

    return Container(
      width: 48,
      height: 48,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        gradient: LinearGradient(
          colors: [Colors.blue.shade400, Colors.purple.shade400],
        ),
      ),
      child: const Icon(Icons.android, color: Colors.white, size: 24),
    );
  }

  Widget _buildSearchBar() {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Container(
      margin: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        gradient: isDark
            ? LinearGradient(
                colors: [AppTheme.neonBlue, AppTheme.neonPurple],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              )
            : null,
        color: isDark ? null : Colors.grey.shade100,
        boxShadow: [
          BoxShadow(
            color: AppTheme.primaryCyan.withValues(alpha: 0.2),
            blurRadius: 8,
            spreadRadius: 2,
          ),
        ],
      ),
      child: TextField(
        controller: _searchController,
        decoration: InputDecoration(
          hintText: 'Search cloned apps...',
          prefixIcon: Icon(
            Icons.search,
            color: isDark ? Colors.white70 : Colors.grey.shade600,
          ),
          suffixIcon: _searchController.text.isNotEmpty
              ? IconButton(
                  icon: Icon(
                    Icons.clear,
                    color: isDark ? Colors.white70 : Colors.grey.shade600,
                  ),
                  onPressed: _clearSearch,
                )
              : null,
          border: InputBorder.none,
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 12,
          ),
          hintStyle: TextStyle(
            color: isDark ? Colors.white54 : Colors.grey.shade500,
          ),
        ),
        style: TextStyle(color: isDark ? Colors.white : Colors.black),
        onChanged: (value) => _onSearchChanged(),
      ),
    );
  }

  Widget _buildAdBanner() {
    if (kIsWeb || !_isBannerAdReady || _bannerAd == null) {
      // Simulated AdMob banner for web testing - looks like real ads
      return Container(
        margin: const EdgeInsets.all(16),
        height: 50,
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: Colors.grey.shade300),
          boxShadow: [
            BoxShadow(
              color: Colors.grey.withValues(alpha: 0.2),
              blurRadius: 4,
              spreadRadius: 1,
            ),
          ],
        ),
        child: Row(
          children: [
            // Simulated app icon
            Container(
              margin: const EdgeInsets.all(8),
              width: 34,
              height: 34,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(6),
                gradient: LinearGradient(
                  colors: [Colors.blue.shade400, Colors.purple.shade400],
                ),
              ),
              child: const Icon(Icons.games, color: Colors.white, size: 20),
            ),
            // Simulated ad content
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    'Epic Game Studio',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: Colors.grey.shade800,
                    ),
                  ),
                  Text(
                    'Download the #1 mobile game!',
                    style: TextStyle(fontSize: 10, color: Colors.grey.shade600),
                  ),
                ],
              ),
            ),
            // Simulated install button
            Container(
              margin: const EdgeInsets.all(8),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              decoration: BoxDecoration(
                color: Colors.green.shade600,
                borderRadius: BorderRadius.circular(20),
              ),
              child: const Text(
                'Install',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
      );
    }

    return Container(
      margin: const EdgeInsets.all(16),
      height: _bannerAd!.size.height.toDouble(),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.grey.shade300),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: AdWidget(ad: _bannerAd!),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              AppConstants.appName,
              style: const TextStyle(
                fontWeight: FontWeight.bold,
                fontFamily: 'GoogleSans',
                fontSize: 24,
              ),
            ),
            Text(
              '${_clonedApps.length} clones active',
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w400,
                fontFamily: 'GoogleSans',
              ),
            ),
          ],
        ),
        backgroundColor: Colors.transparent,
        elevation: 0,
        flexibleSpace: Container(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: isDark
                  ? [AppTheme.neonBlue, AppTheme.neonPurple]
                  : [AppTheme.primaryCyan, Colors.blue.shade600],
            ),
          ),
        ),
        foregroundColor: Colors.white,
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () {
              Navigator.push(
                context,
                PageRouteBuilder(
                  pageBuilder: (context, animation, secondaryAnimation) =>
                      const SettingsScreen(),
                  transitionDuration: AppConstants.pageTransitionDuration,
                  transitionsBuilder:
                      (context, animation, secondaryAnimation, child) {
                        return SlideTransition(
                          position: Tween<Offset>(
                            begin: const Offset(1.0, 0.0),
                            end: Offset.zero,
                          ).animate(animation),
                          child: child,
                        );
                      },
                ),
              );
            },
          ),
        ],
      ),
      body: Column(
        children: [
          _buildSearchBar(),
          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : _clonedApps.isEmpty
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Container(
                          padding: const EdgeInsets.all(24),
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            gradient: LinearGradient(
                              colors: [
                                AppTheme.primaryCyan.withValues(alpha: 0.2),
                                AppTheme.neonPurple.withValues(alpha: 0.2),
                              ],
                            ),
                          ),
                          child: Icon(
                            Icons.content_copy_rounded,
                            size: 80,
                            color: isDark
                                ? AppTheme.neonBlue
                                : AppTheme.primaryCyan,
                          ),
                        ),
                        const SizedBox(height: 24),
                        Text(
                          'No Cloned Apps Yet',
                          style: Theme.of(context).textTheme.headlineSmall
                              ?.copyWith(
                                fontWeight: FontWeight.w600,
                                fontFamily: 'GoogleSans',
                              ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Start by tapping the "Clone App" button below',
                          style: Theme.of(context).textTheme.bodyMedium
                              ?.copyWith(color: Colors.grey.shade500),
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),
                  )
                : _filteredApps.isEmpty && _searchController.text.isNotEmpty
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.search_off,
                          size: 80,
                          color: Colors.grey.shade400,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          'No apps found',
                          style: Theme.of(context).textTheme.titleLarge
                              ?.copyWith(
                                color: Colors.grey.shade600,
                                fontFamily: 'GoogleSans',
                              ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Try searching with a different term',
                          style: Theme.of(context).textTheme.bodyMedium
                              ?.copyWith(color: Colors.grey.shade500),
                        ),
                      ],
                    ),
                  )
                : RefreshIndicator(
                    onRefresh: _loadClonedApps,
                    child: ListView.builder(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 8,
                      ),
                      itemCount: _filteredApps.length,
                      itemBuilder: (context, index) {
                        final app = _filteredApps[index];
                        return Container(
                          margin: const EdgeInsets.only(bottom: 12),
                          child: Card(
                            elevation: 2,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: InkWell(
                              onTap: () => _launchApp(app),
                              onLongPress: () => _removeApp(app),
                              borderRadius: BorderRadius.circular(12),
                              child: Padding(
                                padding: const EdgeInsets.all(16),
                                child: Row(
                                  children: [
                                    // App Icon
                                    Hero(
                                      tag: 'app_${app.id}',
                                      child: _buildAppIcon(app),
                                    ),
                                    const SizedBox(width: 16),
                                    // App Info
                                    Expanded(
                                      child: Column(
                                        crossAxisAlignment:
                                            CrossAxisAlignment.start,
                                        children: [
                                          Text(
                                            '${app.appName} Clone ${index + 1}',
                                            style: Theme.of(context)
                                                .textTheme
                                                .titleMedium
                                                ?.copyWith(
                                                  fontWeight: FontWeight.w600,
                                                  fontFamily: 'GoogleSans',
                                                ),
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                          ),
                                          const SizedBox(height: 4),
                                          Text(
                                            'Cloned ${_formatDate(app.createdAt)}',
                                            style: Theme.of(context)
                                                .textTheme
                                                .bodySmall
                                                ?.copyWith(
                                                  color: Colors.grey.shade600,
                                                  fontFamily: 'GoogleSans',
                                                ),
                                          ),
                                        ],
                                      ),
                                    ),
                                    // Delete button
                                    IconButton(
                                      onPressed: () => _removeApp(app),
                                      icon: Icon(
                                        Icons.delete_outline,
                                        color: Colors.red.shade400,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                  ),
          ),
          _buildCloneButton(isDark),
          _buildAdBanner(),
        ],
      ),
    );
  }

  Widget _buildCloneButton(bool isDark) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      width: double.infinity,
      child: AnimatedBuilder(
        animation: _fabAnimation,
        builder: (context, child) {
          return Transform.scale(
            scale: _fabAnimation.value,
            child: ElevatedButton.icon(
              onPressed: () async {
                final result = await Navigator.push<bool>(
                  context,
                  PageRouteBuilder(
                    pageBuilder: (context, animation, secondaryAnimation) =>
                        const AddAppScreen(),
                    transitionDuration: AppConstants.pageTransitionDuration,
                    transitionsBuilder:
                        (context, animation, secondaryAnimation, child) {
                          return SlideTransition(
                            position: Tween<Offset>(
                              begin: const Offset(1.0, 0.0),
                              end: Offset.zero,
                            ).animate(animation),
                            child: child,
                          );
                        },
                  ),
                );

                if (result == true) {
                  await _loadClonedApps();
                  _onSearchChanged(); // Refresh filtered list
                }
              },
              style: ElevatedButton.styleFrom(
                backgroundColor: isDark
                    ? AppTheme.neonBlue
                    : AppTheme.primaryCyan,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 16),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                elevation: 8,
              ),
              icon: const Icon(Icons.add_rounded, size: 24),
              label: const Text(
                'Clone App',
                style: TextStyle(
                  fontFamily: 'GoogleSans',
                  fontWeight: FontWeight.w600,
                  fontSize: 16,
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  String _formatDate(DateTime date) {
    final now = DateTime.now();
    final difference = now.difference(date);

    if (difference.inDays > 0) {
      return '${difference.inDays}d ago';
    } else if (difference.inHours > 0) {
      return '${difference.inHours}h ago';
    } else if (difference.inMinutes > 0) {
      return '${difference.inMinutes}m ago';
    } else {
      return 'Just now';
    }
  }
}
