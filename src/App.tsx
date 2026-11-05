import { useState } from 'react';
import {
  Download,
  Copy,
  Check,
  Terminal,
  FileCode,
  FolderGit2,
  ShieldCheck,
  Search,
} from 'lucide-react';
import {
  ANDROID_REPO_FILES,
  AndroidRepoFile,
  filterRepoFiles,
} from './utils/androidProjectManifest';

export default function App() {
  const [selectedCategory, setSelectedCategory] = useState<
    'all' | AndroidRepoFile['category']
  >('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFilePath, setSelectedFilePath] = useState<string>(
    ANDROID_REPO_FILES[0].path
  );
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const filteredFiles = filterRepoFiles(
    ANDROID_REPO_FILES,
    selectedCategory,
    searchQuery
  );

  const activeFile =
    filteredFiles.find((f) => f.path === selectedFilePath) ??
    filteredFiles[0] ??
    ANDROID_REPO_FILES[0];

  const handleCopy = async (text: string, key: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedKey(key);
      window.setTimeout(() => {
        setCopiedKey((prev) => (prev === key ? null : prev));
      }, 1800);
    } catch {
      // Fallback ignored in headless environments
    }
  };

  const buildCommands = `chmod +x ./gradlew
./gradlew testDebugUnitTest
./gradlew assembleDebug`;

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 font-sans">
      <header className="border-b border-slate-800 bg-slate-900/70">
        <div className="max-w-7xl mx-auto px-6 py-5 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
          <div>
            <div className="flex items-center gap-2.5 text-emerald-400 text-xs font-mono uppercase tracking-wider">
              <FolderGit2 className="w-4 h-4" />
              <span>Android Gradle Wrapper Repository (No Emulator Preview)</span>
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-white mt-1">
              TouchLock Edge — Android Project{' '}
              <code className="text-lg font-mono text-emerald-400 font-normal">
                (com.touchlock.edge)
              </code>
            </h1>
            <p className="text-sm text-slate-400 mt-1">
              Full-screen <code className="text-slate-200 font-mono">TYPE_APPLICATION_OVERLAY</code> touch blocker with an edge-docked toggle button. Ready for GitHub &amp; <code className="text-slate-200 font-mono">./gradlew assembleDebug</code>.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <a
              href="/TouchLockEdge-Android.zip"
              download="TouchLockEdge-Android.zip"
              className="inline-flex items-center gap-2 px-4 py-2.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-medium text-sm transition-colors"
            >
              <Download className="w-4 h-4" />
              <span>Download Android Repo (.zip)</span>
            </a>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-6 py-8 space-y-8">
        {/* Build & Architecture Summary Grid */}
        <section
          aria-label="Gradle Build and Architecture Overview"
          className="grid grid-cols-1 lg:grid-cols-3 gap-6"
        >
          <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col justify-between">
            <div>
              <div className="flex items-center justify-between">
                <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300 flex items-center gap-2">
                  <Terminal className="w-4 h-4 text-emerald-400" />
                  <span>Build with gradlew</span>
                </h2>
                <button
                  type="button"
                  onClick={() => handleCopy(buildCommands, 'gradle-cmd')}
                  className="inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-200 transition-colors"
                >
                  {copiedKey === 'gradle-cmd' ? (
                    <>
                      <Check className="w-3.5 h-3.5 text-emerald-400" />
                      <span>Copied</span>
                    </>
                  ) : (
                    <>
                      <Copy className="w-3.5 h-3.5" />
                      <span>Copy Commands</span>
                    </>
                  )}
                </button>
              </div>
              <pre className="mt-3 p-3.5 rounded-lg bg-slate-950 border border-slate-800 text-xs font-mono text-emerald-300 overflow-x-auto">
                {buildCommands}
              </pre>
            </div>
            <p className="text-xs text-slate-400 mt-3">
              Outputs APK to{' '}
              <code className="text-slate-200 font-mono">
                app/build/outputs/apk/debug/app-debug.apk
              </code>
              .
            </p>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300 flex items-center gap-2">
              <ShieldCheck className="w-4 h-4 text-emerald-400" />
              <span>Dual-Window Overlay Architecture</span>
            </h2>
            <ul className="mt-3 space-y-2.5 text-xs text-slate-300 leading-relaxed">
              <li>
                <strong className="text-white">1. Full-Screen Touch Blocker (Lower Z):</strong>{' '}
                Attached first via <code className="font-mono text-emerald-300">WindowManager</code>. When locked, removes <code className="font-mono">FLAG_NOT_TOUCHABLE</code> and consumes 100% of screen touches. When unlocked, sets <code className="font-mono">FLAG_NOT_TOUCHABLE</code> and <code className="font-mono">View.GONE</code>.
              </li>
              <li>
                <strong className="text-white">2. Edge Toggle Button (Higher Z):</strong>{' '}
                Attached second on top of the blocker at <code className="font-mono text-emerald-300">Gravity.END</code> or <code className="font-mono text-emerald-300">Gravity.START</code>. Always receives taps to toggle the touch lock on/off and supports vertical edge dragging.
              </li>
            </ul>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300 flex items-center gap-2">
              <FileCode className="w-4 h-4 text-emerald-400" />
              <span>Included Gradle Wrapper &amp; CI</span>
            </h2>
            <dl className="mt-3 space-y-2 text-xs">
              <div className="flex justify-between py-1 border-b border-slate-800/80">
                <dt className="text-slate-400">Gradle Wrapper</dt>
                <dd className="font-mono text-slate-200">8.9 (gradlew + jar)</dd>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-800/80">
                <dt className="text-slate-400">Android Gradle Plugin</dt>
                <dd className="font-mono text-slate-200">8.7.2 / Kotlin 1.9.24</dd>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-800/80">
                <dt className="text-slate-400">Target / Min SDK</dt>
                <dd className="font-mono text-slate-200">API 35 / API 26</dd>
              </div>
              <div className="flex justify-between py-1">
                <dt className="text-slate-400">GitHub Actions</dt>
                <dd className="font-mono text-emerald-400">.github/workflows/build.yml</dd>
              </div>
            </dl>
          </div>
        </section>

        {/* Source File Browser */}
        <section
          aria-label="Android Repository Files"
          className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden"
        >
          <div className="p-4 border-b border-slate-800 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Filter files by category">
              {(
                [
                  { id: 'all', label: 'All Files' },
                  { id: 'kotlin', label: 'Kotlin Source' },
                  { id: 'manifest-res', label: 'AndroidManifest' },
                  { id: 'gradle', label: 'Gradle Scripts' },
                  { id: 'ci-docs', label: 'GitHub Actions' },
                ] as const
              ).map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setSelectedCategory(tab.id)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                    selectedCategory === tab.id
                      ? 'bg-emerald-600 text-white'
                      : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                  }`}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            <div className="relative w-full sm:w-64">
              <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
              <input
                type="search"
                aria-label="Search repository files"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search file path..."
                className="w-full pl-9 pr-3 py-1.5 bg-slate-950 border border-slate-800 rounded-lg text-xs text-slate-100 placeholder-slate-500 focus:outline-none focus:border-emerald-500"
              />
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-12 min-h-[480px]">
            {/* File list sidebar */}
            <div className="lg:col-span-4 border-b lg:border-b-0 lg:border-r border-slate-800 divide-y divide-slate-800/60">
              {filteredFiles.map((file) => {
                const isSelected = file.path === activeFile.path;
                return (
                  <button
                    key={file.path}
                    type="button"
                    onClick={() => setSelectedFilePath(file.path)}
                    className={`w-full text-left p-4 transition-colors ${
                      isSelected
                        ? 'bg-slate-800/90 border-l-2 border-l-emerald-400'
                        : 'hover:bg-slate-800/40'
                    }`}
                  >
                    <div className="font-mono text-xs text-emerald-300 break-all">
                      {file.path}
                    </div>
                    <p className="text-xs text-slate-400 mt-1 line-clamp-2">
                      {file.summary}
                    </p>
                  </button>
                );
              })}
            </div>

            {/* Code viewer */}
            <div className="lg:col-span-8 flex flex-col bg-slate-950">
              <div className="px-4 py-3 border-b border-slate-800 flex items-center justify-between gap-2 bg-slate-900/40">
                <div>
                  <div className="font-mono text-xs text-white font-semibold">
                    {activeFile.path}
                  </div>
                  <div className="text-xs text-slate-400 mt-0.5">
                    {activeFile.summary}
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => handleCopy(activeFile.content, activeFile.path)}
                  className="inline-flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 shrink-0 transition-colors"
                >
                  {copiedKey === activeFile.path ? (
                    <>
                      <Check className="w-3.5 h-3.5 text-emerald-400" />
                      <span>Copied File</span>
                    </>
                  ) : (
                    <>
                      <Copy className="w-3.5 h-3.5" />
                      <span>Copy File</span>
                    </>
                  )}
                </button>
              </div>
              <pre className="p-4 text-xs font-mono text-slate-200 overflow-x-auto leading-relaxed flex-1">
                <code>{activeFile.content}</code>
              </pre>
            </div>
          </div>
        </section>
      </main>
    </div>
  );
}
