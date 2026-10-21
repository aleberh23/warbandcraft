"""
Junta los números de juego en el archivo de configuración (config/warbandcraft-common.toml).

Número de juego = campo `static` (sin `final`) int/long/double/float en MAYÚSCULAS de una clase del mod (fuera de
client/network/gametest/init/mixin/config y de EXCLUDE). Con --strip, primero les saca el `final` a los que todavía
lo tienen. Genera:
  - src/main/java/com/ale/warbandcraft/config/TuningClasses.java  (lista de clases)
  - src/main/resources/warbandcraft_tuning.properties            (comentario de cada número, del /** */ de arriba)
Correrlo cada vez que se agrega una constante nueva:  python tools/gen_tuning.py [--strip]
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
SRC = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'ale', 'warbandcraft')
SKIP_DIRS = {'client', 'network', 'gametest', 'init', 'mixin', 'config'}
# Índices, ids de pestañas, banderas de bits: no son números de juego
EXCLUDE = {'camp/KitEditorMenu.java', 'camp/ShipmentMenu.java', 'village/VillageHud.java', 'camp/CampStats.java', 'camp/CampTabs.java', 'panel/Panel.java', 'nav/NavGrid.java', 'nav/NavPath.java'}
# Códigos de estado de Recruits, colores, tamaños de inventario: quedan `final` (fuera del archivo)
FIXED_NAMES = {'FOLLOW', 'HOLD', 'BACK_TO_POS', 'WANDER', 'PROTECT', 'FOLLOW_OWNER', 'HOLD_OWNER_POS', 'PASSIVE', 'NEUTRAL',
               'FOLLOW_HOLD', 'GREEN', 'GREY', 'RED', 'YELLOW', 'SIZE', 'NEVER', 'MAX_NAME_LENGTH', 'FIRST_BACKPACK_SLOT',
               'BACKPACK_SIZE', 'ARRIVED_FOLLOW', 'ARRIVED_HOLD'}
DECL = re.compile(r'^(\s*(?:public |private |protected )?static )final ((?:int|long|double|float) [A-Z][A-Z_0-9]* *=)')
FIELD = re.compile(r'^\s*(?:public |private |protected )?static (int|long|double|float) ([A-Z][A-Z_0-9]* *=.*);')


def escape(text):
    out = []
    for ch in text:
        if ord(ch) > 126:
            out.append('\\u%04x' % ord(ch))
        else:
            out.append(ch)
    return ''.join(out)


def clean(line):
    s = line.strip()
    for a in ('/**', '*/'):
        s = s.replace(a, '')
    s = s.lstrip('* ').strip()
    return re.sub(r'\{@(?:link|code) ([^}]*)\}', r'\1', s)


strip = '--strip' in sys.argv
classes, comments = [], {}
for d, dirs, files in os.walk(SRC):
    rel_dir = os.path.relpath(d, SRC).replace(os.sep, '/')
    if rel_dir.split('/')[0] in SKIP_DIRS:
        continue
    for f in sorted(files):
        if not f.endswith('.java'):
            continue
        rel = f if rel_dir == '.' else rel_dir + '/' + f
        if rel in EXCLUDE:
            continue
        path = os.path.join(d, f)
        with open(path, encoding='utf-8', newline='') as fh:
            raw = fh.read()
        lines = raw.split('\n')
        changed = False
        if strip:
            for i, l in enumerate(lines):
                if DECL.match(l):
                    lines[i] = DECL.sub(r'\1\2', l)
                    changed = True
        for i, l in enumerate(lines):   # los de FIXED_NAMES vuelven a ser final
            m = FIELD.match(l)
            if m:
                names = {p.split('=')[0].strip() for p in re.split(r',\s*(?=[A-Z][A-Z_0-9]* *=)', m.group(2))}
                if names <= FIXED_NAMES:
                    lines[i] = re.sub(r'static (int|long|double|float) ', r'static final \g<1> ', l, count=1)
                    changed = True
        cls = rel[:-5].replace('/', '.')
        found = False
        doc, in_doc = [], False
        for l in lines:
            s = l.strip()
            if s.startswith('/**'):
                doc, in_doc = [clean(s)], not s.endswith('*/')
                continue
            if in_doc:
                doc.append(clean(s))
                in_doc = not s.endswith('*/')
                continue
            m = FIELD.match(l)
            if m:
                found = True
                text = ' '.join(x for x in doc if x)
                for part in re.split(r',\s*(?=[A-Z][A-Z_0-9]* *=)', m.group(2)):
                    n = part.split('=')[0].strip()
                    if re.fullmatch(r'[A-Z][A-Z_0-9]*', n):
                        comments[cls + '.' + n] = text
            if s and not s.startswith('@'):
                doc = []
        if changed:
            with open(path, 'w', encoding='utf-8', newline='') as fh:
                fh.write('\n'.join(lines))
        if found:
            classes.append(cls)

classes.sort()
os.makedirs(os.path.join(SRC, 'config'), exist_ok=True)
with open(os.path.join(SRC, 'config', 'TuningClasses.java'), 'w', encoding='utf-8') as out:
    out.write('package com.ale.warbandcraft.config;\n\n')
    out.write('/** Generado por tools/gen_tuning.py: las clases con números de juego. No editar a mano. */\n')
    out.write('final class TuningClasses {\n    static final String[] NAMES = {\n')
    for c in classes:
        out.write('            "%s",\n' % c)
    out.write('    };\n\n    private TuningClasses() {}\n}\n')
with open(os.path.join(ROOT, 'src', 'main', 'resources', 'warbandcraft_tuning.properties'), 'w', encoding='ascii') as out:
    out.write('# Generado por tools/gen_tuning.py: comentario de cada numero del archivo de configuracion\n')
    for k in sorted(comments):
        out.write('%s=%s\n' % (k, escape(re.sub(r'\s+', ' ', comments[k]).strip())))
print(len(classes), 'clases,', len(comments), 'numeros')
