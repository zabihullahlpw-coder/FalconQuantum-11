#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .test-build
if command -v javac >/dev/null 2>&1; then compiler=(javac); else compiler=(java com.sun.tools.javac.Main); fi
"${compiler[@]}" -encoding UTF-8 -d .test-build tools/SyntaxCheck.java
java -cp .test-build SyntaxCheck
python3 - <<'PY'
from pathlib import Path
p=[str(x) for x in Path('tests/stubs').rglob('*.java')]
p += [str(x) for x in Path('src/com/falcon/quantum').glob('*.java') if x.name not in ['MainActivity.java','ScanService.java','TradeChart.java','SecureKeys.java']]
p += ['tests/RegressionTests.java','tests/LiveControlTests.java','tests/ReverseTradeDirectionTests.java','tools/BacktestMain.java']
Path('.test-build/sources.txt').write_text('\n'.join(p))
PY
"${compiler[@]}" --release 8 -encoding UTF-8 -d .test-build @.test-build/sources.txt
java -cp .test-build com.falcon.quantum.RegressionTests
java -cp .test-build com.falcon.quantum.LiveControlTests
java -cp .test-build com.falcon.quantum.ReverseTradeDirectionTests
python3 tests/test_migrations.py
python3 tests/test_downloader.py
python3 -m unittest discover -s tests -p 'test_performance_audit.py' -v
python3 -m unittest discover -s tests -p 'test_security_configuration.py' -v
