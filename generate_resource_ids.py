#!/usr/bin/env python3
"""Generate the core-message to Android R mapping from the default resource catalog."""
from pathlib import Path
import xml.etree.ElementTree as ET
import json
root=Path(__file__).resolve().parent
keys=[e.attrib['name'] for e in ET.parse(root/'app/src/main/res/values/strings.xml').getroot() if e.tag=='string']
source='// SPDX-License-Identifier: GPL-3.0-or-later\n// Generated; translations live in Android resources.\npackage com.didazz.descargasocial;\nfinal class ResourceIds {\n'
source+='    static final String[] KEYS = {'+','.join(json.dumps(k) for k in keys)+'};\n'
source+='    static final int[] IDS = {'+','.join('R.string.'+k for k in keys)+'};\n'
source+='    static int find(String key) { for(int i=0;i<KEYS.length;i++)if(KEYS[i].equals(key))return IDS[i];return 0; }\n}\n'
(root/'app/src/main/java/com/didazz/descargasocial/ResourceIds.java').write_text(source)
