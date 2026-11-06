#!/usr/bin/env python3
"""Enforce the single Camry Java root owner and common app-to-root transport."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
pioneer = root / 'pioneer-adapter/src/main/java'
wireless = root / 'shared/src/main/java/com/shilapi/xcertplay/vehicle/adapters/pioneer/wireless'
errors = []
for base in (pioneer, wireless):
    for path in base.rglob('*'):
        if path.suffix not in ('.java', '.kt'):
            continue
        source = path.read_text()
        name = path.name
        if re.search(r'PioneerRootDispatcher(?:\.INSTANCE)?\.dispatch\s*\(', source) and name != 'PioneerRootSession.kt':
            errors.append(f'{path.relative_to(root)}: root dispatch bypasses PioneerRootSession')
        if 'pioneer.pfilesdoperator' in source and name != 'PioneerRootDispatcher.kt':
            errors.append(f'{path.relative_to(root)}: duplicates firmware root bootstrap')
        if re.search(r'(?:public\s+static\s+void|fun)\s+main\s*\(', source) and name != 'PioneerRootSessionHelper.java':
            errors.append(f'{path.relative_to(root)}: independent Java root entry point')
        if re.search(r'System\.exit\s*\(', source) and name != 'PioneerRootSessionHelper.java':
            errors.append(f'{path.relative_to(root)}: feature may terminate the shared root service')
        if re.search(r'new\s+LocalServerSocket\s*\(', source) and name != 'PioneerRootSessionHelper.java':
            errors.append(f'{path.relative_to(root)}: separate root IPC listener')
if errors:
    raise SystemExit('\n'.join(errors))
print('PASS single Java root owner, one firmware bootstrap, no feature exit/IPC listener bypass')
