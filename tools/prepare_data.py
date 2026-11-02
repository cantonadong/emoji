import json
import re
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / 'tools/cache'
ASSETS = ROOT / 'app/src/main/assets'
CACHE.mkdir(parents=True, exist_ok=True)
ASSETS.mkdir(parents=True, exist_ok=True)

def download(url, destination):
    if not destination.exists():
        print('Downloading', url, flush=True)
        with urllib.request.urlopen(url, timeout=90) as response:
            destination.write_bytes(response.read())
    return destination

def key(text):
    return text.replace('\ufe0f', '')

annotations = {}
for language in ('zh', 'en'):
    values = {}
    for folder in ('annotations', 'annotationsDerived'):
        path = download(f'https://raw.githubusercontent.com/unicode-org/cldr/main/common/{folder}/{language}.xml', CACHE / f'current-{folder}-{language}.xml')
        for node in ET.parse(path).iter('annotation'):
            entry = values.setdefault(key(node.attrib['cp']), {'name': '', 'keywords': []})
            if node.attrib.get('type') == 'tts':
                entry['name'] = node.text or ''
            else:
                entry['keywords'] += [word.strip() for word in (node.text or '').split('|') if word.strip()]
    annotations[language] = values

source = download('https://www.unicode.org/Public/emoji/latest/emoji-test.txt', CACHE / 'emoji-latest.txt')
items = []
group = subgroup = ''
for line in source.read_text(encoding='utf-8').splitlines():
    if line.startswith('# group: '):
        group = line.removeprefix('# group: ')
    if line.startswith('# subgroup: '):
        subgroup = line.removeprefix('# subgroup: ')
    match = re.match(r'^([0-9A-F ]+)\s*;\s*(fully-qualified|component)\s*#\s*\S+\s+E[\d.]+\s+(.+)$', line)
    if not match:
        continue
    points = [int(part, 16) for part in match[1].split()]
    if any(0x1F3FB <= point <= 0x1F3FF for point in points):
        continue
    emoji = ''.join(map(chr, points))
    zh = annotations['zh'].get(key(emoji), {})
    en = annotations['en'].get(key(emoji), {})
    words = list(dict.fromkeys(zh.get('keywords', []) + en.get('keywords', [])))
    category = {'Smileys & Emotion': 0, 'People & Body': 3, 'Component': 3, 'Animals & Nature': 4, 'Food & Drink': 5, 'Travel & Places': 6, 'Activities': 7, 'Objects': 8, 'Symbols': 9, 'Flags': 10}[group]
    if subgroup == 'heart':
        category = 1
    elif subgroup.startswith('hand'):
        category = 2
    items.append({'emoji': emoji, 'zh': zh.get('name') or match[3], 'en': en.get('name') or match[3], 'keywords': words, 'category': category})

by_emoji = {key(item['emoji']): item for item in items}
by_hex = {'-'.join(f'{ord(char):04X}' for char in key(item['emoji'])): item for item in items}
base = 'https://cdn.jsdelivr.net/npm/emojibase-data@18.0.0/'
for locale in ('zh', 'en', 'en-gb'):
    path = download(base + f'{locale}/data.json', CACHE / f'emojibase-18-{locale}.json')
    for entry in json.loads(path.read_text(encoding='utf-8')):
        item = by_emoji.get(key(entry['emoji']))
        if item is not None:
            item['keywords'] += entry.get('tags', [])
            item['keywords'].append(entry['label'])
            emoticons = entry.get('emoticon', [])
            item['keywords'] += [emoticons] if isinstance(emoticons, str) else emoticons

for locale, presets in (
    ('en', ('emojibase', 'github', 'iamcal', 'joypixels', 'cldr')),
    ('zh', ('cldr-native',)),
):
    for preset in presets:
        path = download(base + f'{locale}/shortcodes/{preset}.json', CACHE / f'codes-18-{locale}-{preset}.json')
        for hexcode, codes in json.loads(path.read_text(encoding='utf-8')).items():
            item = by_hex.get('-'.join(part for part in hexcode.split('-') if part != 'FE0F'))
            if item is not None:
                for code in ([codes] if isinstance(codes, str) else codes):
                    item['keywords'] += [code, ':' + code + ':', code.replace('_', ' ')]

territories = download('https://raw.githubusercontent.com/unicode-org/cldr/main/common/supplemental/supplementalData.xml', CACHE / 'supplementalData.xml')
alpha3 = {node.attrib['type']: node.attrib.get('alpha3', '') for node in ET.parse(territories).iter('territoryCodes')}
for item in items:
    points = list(map(ord, item['emoji']))
    if len(points) == 2 and all(0x1F1E6 <= point <= 0x1F1FF for point in points):
        country = ''.join(chr(point - 0x1F1E6 + ord('A')) for point in points)
        item['keywords'] += [country, alpha3.get(country, '')]
        if country == 'GB':
            item['keywords'] += ['UK', 'Britain', '英国']

download('https://cdn.jsdelivr.net/npm/emojibase-data@18.0.0/LICENSE', ASSETS / 'Emojibase-LICENSE.txt')

# Everyday search words complement the CLDR labels.
aliases = [
    ('开心 快乐 高兴 happy', '😀😃😄😁😆😊🙂🥳😺😸'),
    ('爱 喜欢 爱心 love', '❤️🩷🧡💛💚💙🩵💜🖤🩶🤍🤎😍🥰😘🫶'),
    ('伤心 难过 sad', '😢😭🥺😞😔💔'),
    ('笑 哈哈 搞笑 laugh', '😂🤣😆😄😁😹'),
    ('赞 点赞 好的 棒 good', '👍👌👏💯'),
    ('庆祝 生日 派对 celebration', '🥳🎉🎊🎂🎈🎁'),
    ('狗头 狗狗 汪汪 犬 puppy dog', '🐶🐕'),
    ('猫咪 猫猫 喵喵 kitten kitty cat', '🐱🐈😺😸😹😻😼😽🙀😿😾'),
    ('无语 无奈 speechless', '😑😶🙄'),
    ('尴尬 社死 awkward embarrassed', '😅😬🫠'),
    ('加油 努力 坚持 fighting strong', '💪✊🙌'),
    ('谢谢 感谢 拜托 祈祷 thanks please pray', '🙏'),
    ('再见 拜拜 你好 hello goodbye bye', '👋'),
    ('生气 愤怒 气死 angry mad', '😠😡🤬'),
    ('震惊 惊讶 吃惊 shocked surprised', '😮😲😱🤯'),
    ('困了 睡觉 晚安 sleepy goodnight', '😴🥱💤'),
    ('吃饭 干饭 美食 food yummy', '🍚🍜🍱🍽️😋'),
    ('上班 工作 打工 办公 work office', '💼🧑‍💼👨‍💼👩‍💼🏢'),
    ('钱 金钱 发财 富贵 money rich cash', '💰💵💴💶💷🤑'),
    ('哭 哭泣 眼泪 委屈 cry tears', '😢😭🥺🥹'),
]
for words, emojis in aliases:
    for item in items:
        if re.search(r'(?<!\u200d)' + re.escape(key(item['emoji'])) + r'(?!\u200d)', key(emojis)):
            item['keywords'] = list(dict.fromkeys(item['keywords'] + words.split()))

for item in items:
    item['keywords'] = list(dict.fromkeys(word.strip() for word in [item['zh'], item['en']] + item['keywords'] if word.strip()))

assert len(items) > 1800 and len({item['emoji'] for item in items}) == len(items)
assert all(not any(0x1F3FB <= ord(char) <= 0x1F3FF for char in item['emoji']) for item in items)
(ASSETS / 'emoji.json').write_text(json.dumps(items, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
download('https://raw.githubusercontent.com/googlefonts/noto-emoji/main/2D/fonts/NotoColorEmoji.ttf', ASSETS / 'NotoColorEmoji.ttf')
download('https://raw.githubusercontent.com/googlefonts/noto-emoji/main/2D/fonts/LICENSE', ASSETS / 'Noto-LICENSE.txt')
download('https://www.unicode.org/license.txt', ASSETS / 'Unicode-LICENSE.txt')
print(f'Prepared {len(items)} emoji; Chinese labels: {sum(item["zh"] != item["en"] for item in items)}', flush=True)
print(f'Keywords: {sum(len(item["keywords"]) for item in items)}; minimum per emoji: {min(len(item["keywords"]) for item in items)}', flush=True)
