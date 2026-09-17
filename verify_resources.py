#!/usr/bin/env python3
"""Static Android resource completeness and reference audit; not a visual Android test."""
from pathlib import Path
import re,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent
res=root/'app/src/main/res'
def catalog(folder):
 tree=ET.parse(res/folder/'strings.xml');items=tree.getroot().findall('string')
 names=[x.attrib['name'] for x in items]
 assert len(names)==len(set(names)),f'duplicate keys: {folder}'
 return {x.attrib['name']:x.text or '' for x in items}
en=catalog('values');es=catalog('values-es');assert en.keys()==es.keys()
for key in en:
 assert en[key].strip() and es[key].strip(),key
 assert sorted(re.findall(r'%\d+\$[dsf]',en[key]))==sorted(re.findall(r'%\d+\$[dsf]',es[key])),key
for path in (root/'app/src/main/java').rglob('*.java'):
 source=path.read_text()
 for key in re.findall(r'(?:R\.string\.|Messages\.ref\(")([a-z][a-z0-9_]*)',source):
  assert key in en,f'missing {key} in {path}'
manifest=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot();android='{http://schemas.android.com/apk/res/android}'
assert manifest.attrib['package']=='com.didazz.descargasocial'
assert manifest.find('application').attrib[android+'label']=='@string/app_name'
assert manifest.find('application').attrib[android+'localeConfig']=='@xml/locales_config'
assert 'locale' in manifest.find('application/activity').attrib[android+'configChanges'].split('|')
assert 'layoutDirection' in manifest.find('application/activity').attrib[android+'configChanges'].split('|')
assert {x.attrib[android+'name'] for x in ET.parse(res/'xml/locales_config.xml').getroot()}>={'en','es'}
print(f'{len(en)} resources per language: ES/EN parity, placeholders, references and manifest OK.')
