import 'dart:convert';
import '../widgets.dart';
import 'profiles.dart';
import 'profile_editor.dart';

const configEncoder = JsonEncoder.withIndent('  ');

class ProfileConfigStore extends ChangeNotifier {
  ProfileConfigStore(this.box, this.profile);
  final BoxController box;
  final Json profile;
  Json root = {};
  String revision = '';
  String? error;
  bool ready = false, loading = false, _disposed = false;
  int _generation = 0;

  void _receive(Json data) {
    root = json(jsonDecode(data['formatted'] as String));
    revision = data['revision'] as String;
    ready = true;
  }

  Future<void> load() async {
    if (_disposed) return;
    final generation = ++_generation;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final data = json(await box.call('profiles.config', {'id': profile['id']}));
      if (!_disposed && generation == _generation) _receive(data);
    } catch (failure) {
      if (!_disposed && generation == _generation) error = BoxController.describe(failure);
    } finally {
      if (!_disposed && generation == _generation) { loading = false; notifyListeners(); }
    }
  }

  dynamic value(List<String> path) {
    dynamic result = root;
    for (final key in path) {
      if (result is! Map) return null;
      result = result[key];
    }
    return result;
  }

  List<Json> entries(List<String> path) => rows(value(path));

  @override void dispose() { _disposed = true; super.dispose(); }
}

/// The group's configuration is its node list and nothing else. DNS, routing rules and log
/// settings are app settings — the same split Throne keeps — so the page shows the nodes and
/// points at the app pages that own everything else.
class ProfileConfigurationPage extends StatefulWidget {
  const ProfileConfigurationPage({super.key, this.profile});
  final Json? profile;
  @override State<ProfileConfigurationPage> createState() => _ProfileConfigurationPageState();
}

class _ProfileConfigurationPageState extends State<ProfileConfigurationPage> {
  ProfileConfigStore? _store;
  @override void didChangeDependencies() {
    super.didChangeDependencies();
    final profile = widget.profile ?? context.box.selectedProfile;
    if (_store == null && profile != null && !context.box.remote) {
      _store = ProfileConfigStore(context.box, profile);
      _store!.load();
    }
  }
  @override void dispose() { _store?.dispose(); super.dispose(); }
  @override Widget build(BuildContext context) {
    final store = _store;
    if (store == null) return CommonScaffold(title: context.tr('Advanced configuration'),
      body: NullStatus(label: context.tr(context.box.remote ? 'Disconnect remote session to edit local profiles' : 'No profile'),
        illustration: NullStatusIllustration.profile, action: context.box.remote ? null : TonalButton(size: TonalButtonSize.bar,
          glyph: AppGlyphs.profiles, label: context.tr('Profiles'), onPressed: () => openForm(context, const ProfilesPage(picker: true)))));
    return ListenableBuilder(listenable: store, builder: (context, _) {
      if (!store.ready) return CommonScaffold(title: context.tr('Advanced configuration'),
        body: store.error == null ? const Center(child: CommonCircleLoading.page()) : ErrorState(store.error!, retry: store.load));
      final nodes = store.entries(const ['nodes']);
      return BoxPage(title: 'Advanced configuration',
        actions: [IconButtonData(glyph: AppGlyphs.refresh, tooltip: context.tr('Reload'), onPressed: store.loading ? null : store.load)],
        children: [
          if (store.error != null) Padding(padding: const EdgeInsets.only(bottom: 12), child: SelectableText(store.error!, style: TextStyle(color: context.colorScheme.error))),
          generateSection(title: context.tr('Nodes'), items: [
            if (nodes.isEmpty)
              ListItem(title: Text(context.tr('No results')))
            else
              for (final node in nodes)
                ListItem(
                  title: Text(node['tag']?.toString() ?? ''),
                  subtitle: Text(node['type']?.toString() ?? ''),
                  trailing: ((node['delay'] as num?)?.toInt() ?? 0) > 0 ? Text('${node['delay']}ms') : null,
                ),
          ], footer: context.tr('This configuration carries only its node list; membership is edited on the Proxies screen.')),
          generateSection(title: context.tr('App settings'), items: [
            ListItem.open(leading: const GlyphIcon(AppGlyphs.dns), title: Text(context.tr('DNS')), widget: const DnsPage()),
            ListItem.open(leading: const GlyphIcon(AppGlyphs.rules), title: Text(context.tr('Routing')), widget: const RoutingPage()),
            ListItem.open(leading: const GlyphIcon(AppGlyphs.customize), title: Text(context.tr('Core')), widget: const CorePage()),
          ], footer: context.tr('DNS, routing rules and log settings are app settings, as in Throne.')),
          generateSection(items: [ListItem(leading: const GlyphIcon(AppGlyphs.code), title: Text(context.tr('Open configuration')),
            trailing: const GlyphIcon(AppGlyphs.chevronForward), onTap: () async {
              await openPage(context, ProfileEditor(profile: store.profile));
              await store.load();
            })]),
        ]);
    });
  }
}
